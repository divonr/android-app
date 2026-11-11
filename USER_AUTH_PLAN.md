# Per-User Sync & Google Sign-In — Full Refactor Plan

Goal: replace the single shared-token, single-user ("default") sync/web model with
real per-user identity based on **Sign in with Google**, with strict per-user data
isolation across all three UIs (Android, Desktop, Web).

Execution model (MANDATORY):
- The executor session acts as **orchestrator/manager** only.
- **Every code-writing step is performed by a Sonnet subagent** (`Agent` tool,
  `model: "sonnet"`), with a precise, self-contained prompt.
- After each step: **build the affected module(s) + commit** before moving on.
- Progress is checkpointed in `USER_AUTH_PROGRESS.md` (physical file, survives
  crashes / usage-limit cutoffs). Subagents read it FIRST, mark `[~]` on start,
  `[x]` + one-line note + commit hash on finish. Never redo an `[x]` step.

Branch: `google-auth`, created off `web-ui` (which contains all other branches).
The sync server (`~/ApI/sync-server`) gets its own git repo (Step 0).

The existing remote DB (`sync_data.db`) contains only the owner's data, which is
backed up locally on his phone. **It is approved to wipe it.**

---

## 1. Current state (verified in code)

- **sync-server** (`~/ApI/sync-server/server.py`, FastAPI :8090, systemd
  `sync-server.service`, Cloudflare `sync.api-divonr.xyz`): SQLite blob store
  keyed `(user, filename)`. Auth = ONE shared bearer `SYNC_TOKEN` for everyone;
  any token holder can read ANY `{user}` path. `api_keys*` blobs Fernet-encrypted.
  Not under version control.
- **shared module**: `SyncEngine` + `RemoteStorageClient` + `SyncState`. Tracked
  filenames derived from `AppSettings.current_user` (always `"default"` today).
  `RemoteSyncSettings { enabled, serverBaseUrl, authToken, syncApiKeys }` stored
  in `app_settings.json`, stripped before upload.
- **Android**: settings UI `RemoteSyncSection` with manual URL+token entry.
  Google Sign-In SDK already integrated (`GoogleWorkspaceAuthService`, web client
  id `926212364522-f5ppm0o9cj95te2rerborkdg62e8qlc4.apps.googleusercontent.com`,
  `requestIdToken`) — used today for the Workspace integration.
- **Desktop**: `GoogleWorkspaceAuthService` is a stub ("Google Sign-In SDK is
  Android-only"). GitHub OAuth on desktop bundles a client secret — precedent.
- **server module** (Ktor :8091, `app.api-divonr.xyz`): **single-user**. Password
  login (`WEB_UI_PASSWORD`) → signed session cookie. One `DataRepository` over
  `~/.llm-api-web`. Sync seeded from env (`SYNC_ENABLED/SYNC_TOKEN/SYNC_USER`).
  Has Google/GitHub OAuth routes — but for the *Workspace/GitHub integrations*,
  not for login.
- **User model**: just `current_user: String` in `app_settings.json` + per-user
  files (`chat_history_{u}.json`, `api_keys_{u}.json`, `custom_providers_{u}.json`,
  `full_custom_providers_{u}.json`, `github_auth_{u}.json`,
  `google_workspace_auth_{u}.json`). Also per-username-keyed maps inside
  `AppSettings`: `githubConnections`, `googleWorkspaceConnections`. No user
  switching UI, no registry of users.

---

## 2. Target design

### 2.1 Identity

- A user = a Google account. Verified server-side from a **Google ID token**.
- **Canonical username** (computed ONCE by the sync server at first sign-in, the
  single source of truth): sanitized email — lowercase, every char outside
  `[a-z0-9]` → `_`, e.g. `haravsihot@gmail.com` → `haravsihot_gmail_com`.
  Used as the local filename suffix and the server-side data key.
- The **Google `sub`** (stable numeric subject id) is the primary key in the
  server's `users` table, so a future email change cannot fork the data.
- Local-only use stays exactly as today: `current_user = "default"`, no account.

### 2.2 Sync server v2 (breaking; old DB wiped)

SQLite schema:
```
users  (sub TEXT PK, email TEXT, username TEXT UNIQUE, created_at INTEGER)
tokens (token_sha TEXT PK, sub TEXT REFERENCES users, created_at INTEGER, last_used INTEGER)
blobs  (username TEXT, filename TEXT, content BLOB, updated_at INTEGER,
        sha TEXT, _enc INTEGER, PRIMARY KEY (username, filename))
```

Endpoints:
```
GET  /sync/health                  (unauthenticated, unchanged)
POST /auth/google {id_token}       → verify via google-auth lib
                                     (issuer accounts.google.com, audience in
                                      GOOGLE_OAUTH_CLIENT_IDS env, signature),
                                     upsert user, mint opaque token
                                     (secrets.token_urlsafe(32), store sha256)
                                   → {token, username, email}
GET  /sync/manifest                (Bearer <user token>; username derived from token)
GET  /sync/file/{filename}         (Bearer)
PUT  /sync/file/{filename}         (Bearer)
```

- The `{user}` path segment is GONE — identity comes only from the token.
  Cross-user access is structurally impossible.
- `api_keys*` Fernet encryption at rest kept as-is.
- Env: `SYNC_ENC_KEY` (kept), `GOOGLE_OAUTH_CLIENT_IDS` (new, comma-separated;
  initially just the web client id above). `SYNC_TOKEN` (deleted).
- New dependency: `google-auth` (+ `requests`) in the venv.
- Tokens are long-lived and revocable (delete row). 401 → client must re-sign-in.

### 2.3 Client flow (Android & Desktop — shared logic in `:shared`)

"Enable sync" in settings no longer shows a token field. Instead:

1. Tap **Sign in with Google** → platform sign-in → Google **ID token**.
2. `POST {serverBaseUrl}/auth/google` → `{token, username, email}`.
3. **One-time local migration** (shared `UserMigration`):
   - If `chat_history_{username}.json` already exists locally → just set
     `current_user = username` (re-sign-in / account switch case).
   - Else rename ALL per-user files of the current local user (normally
     `default`) to the new `username`, and re-key that user's entries inside
     `githubConnections` / `googleWorkspaceConnections`.
4. Save `RemoteSyncSettings(enabled=true, serverBaseUrl, authToken=<minted>,
   accountEmail, …)` (still stripped from uploaded `app_settings.json`).
5. `startSync()` → initial pull (server empty after wipe) → debounced uploads
   push the full local state. Server now holds the data under the real user.

- **Sign out** = disable sync + clear token/email. Local data and username stay.
- **Switch account** = sign out → sign in with another Google account (step 3
  handles both "new user on this device" and "returning user" cases).
- SyncEngine: replace `/sync/{user}/...` calls with v2 paths; on 401 set a
  `needsReauth` state surfaced in settings UI ("Session expired — sign in again").

`RemoteSyncSettings` becomes:
```kotlin
data class RemoteSyncSettings(
    val enabled: Boolean = false,
    val serverBaseUrl: String = "https://sync.api-divonr.xyz",
    val authToken: String = "",        // server-minted, NOT user-entered anymore
    val accountEmail: String = "",     // display
    val syncApiKeys: Boolean = false
)
```

Platform sign-in abstraction in `:shared`:
```kotlin
interface GoogleSignInProvider {            // returns a verified-by-Google ID token
    suspend fun signIn(): Result<GoogleIdentity>  // { idToken, email }
}
```
- **Android impl**: reuse the existing Google Sign-In SDK plumbing
  (`requestIdToken` with the web client id) but with minimal scopes
  (id/email/profile only — independent from the Workspace integration consent).
- **Desktop impl**: standard installed-app **loopback flow**: spin up
  `http://127.0.0.1:53682/` listener, open browser to Google's auth endpoint
  (web client id + secret, like the GitHub desktop precedent), exchange code →
  ID token. Requires adding that redirect URI in Google Cloud Console (USER step).

### 2.4 Web (Ktor `:server` + React `web/`) — multi-user

- **Login = Google OAuth only.** `WEB_UI_PASSWORD` flow removed. Server-side
  code flow: `GET /auth/google/start` → Google → `GET /auth/google/callback`
  → verify ID token → derive username → set session. (Distinct routes from the
  existing `/oauth/google/*` Workspace-integration flow, which stays.)
- Optional allowlist: `ALLOWED_GOOGLE_EMAILS` env (comma-separated). Empty =
  open sign-up. **Recommended: set it** — this is a personal server.
- `UserSession` gains `username`, `email`. All `/api/**` routes resolve the
  repository from the session's username — never from `AppSettings.current_user`.
- **Per-user storage**: `{LLM_WEB_DATA_DIR}/users/{username}/` with a lazy
  `UserRegistry` (mutex-guarded map username → `UserContext(repository,
  appModule services, syncEngine)`); rehydrated lazily after restarts (session
  cookie valid 30 days; sync token read from the user dir).
- **Per-user sync**: at login callback the server ALSO posts the same Google ID
  token to the sync server's `/auth/google`, stores the minted token in
  `users/{username}/sync_credentials.json` (never synced), and starts that
  user's SyncEngine + periodic pull loop. So the web server is just another
  device of that user — no privileged access to the sync server, no special-casing.
  Env simplifies to `SYNC_SERVER_URL` (default `http://localhost:8090`);
  `SYNC_ENABLED/SYNC_TOKEN/SYNC_USER/SYNC_API_KEYS` removed.
- Old `~/.llm-api-web` flat data: discarded (it is just a sync replica; it will
  re-pull per user). Note in DEPLOY.md.
- **Frontend**: LoginPage → "Sign in with Google" button (navigates to
  `/auth/google/start`); account chip (email) + logout in settings; existing
  401→login redirect handling reused.
- Tests: inject a fake `GoogleTokenVerifier` / fake sync-auth exchanger so the
  whole flow is testable hermetically (pattern already exists with
  `oauthExchanger`, `SyncConfig.startEngine=false`).

### 2.5 What does NOT change

- Blob-level last-write-wins sync semantics, debounce, dirty tracking.
- `app_settings.json` strip/merge of `remoteSync`.
- Cloudflare tunnel routes (`sync.` :8090, `app.` :8091).
- The Workspace/GitHub integrations (separate OAuth flows and storage).
- Local-only usage without sync (user stays `default`).

---

## 3. Execution steps

Legend: **[S]** = Sonnet subagent writes the code. **[O]** = orchestrator.
**[U]** = user (manual, outside this machine or needs his browser/phone).
Every [S] step ends with: orchestrator builds affected modules, commits, updates
`USER_AUTH_PROGRESS.md`.

### Step 0 — [O] Scaffolding
- `git checkout -b google-auth` (off `web-ui`) in `android-app`.
- `git init` in `~/ApI/sync-server` + `.gitignore` (venv/, *.db, *.log, .env,
  __pycache__/) + initial commit of current `server.py` & unit file (pre-refactor
  baseline).
- Commit plan + progress files in `android-app`.

### Step 1 — [S] Sync server v2 (Python)
- Rewrite `server.py` per §2.2 (schema, /auth/google, token auth, v2 paths).
- `pip install google-auth requests` into the venv; write `requirements.txt`.
- Pytest (or curl-script) coverage with a **mocked** `verify_oauth2_token`:
  401 paths, token mint, per-user isolation (token A cannot see user B), Fernet
  round-trip, manifest.
- Update `sync-server.service` (env: drop SYNC_TOKEN, add GOOGLE_OAUTH_CLIENT_IDS).
- Build/verify: run tests + boot server locally on a temp port with temp DB.
- Orchestrator: **delete `sync_data.db`** (approved), update the real env file,
  restart `sync-server.service`, curl `/sync/health` + a 401 check. Commit
  (sync-server repo).

### Step 2 — [S] Shared module (`:shared`)
- `RemoteSyncSettings` v2 fields (§2.3) with backward-compatible defaults.
- `GoogleSignInProvider` + `GoogleIdentity` interfaces.
- `RemoteStorageClient`: v2 paths, `authGoogle(idToken)`, typed 401 →
  `RemoteSyncException.Unauthorized`.
- `SyncEngine`: drop user path segment; `needsReauth` StateFlow on 401.
- New `UserMigration` (in `data/repository/`): `migrateToAccount(newUsername)`
  per §2.3 step 3 (file renames + settings re-keying), idempotent, unit-testable
  pure-ish function over `internalDir`.
- `DataRepository`: expose `signInToSync(identity)` orchestration (exchange via
  client, migrate, save settings, startSync) + `signOutOfSync()`.
- Build: `./gradlew :shared:compileKotlin` (+ any shared tests). Commit.

### Step 3 — [S] Android (`:app`)
- `SyncGoogleSignInProvider` impl (minimal-scope GoogleSignInOptions, reuse
  client id constant; activity-result wiring like the Workspace flow).
- `RemoteSyncSection` rework: token field → Sign-in button / signed-in account
  row (email, avatar optional) / Sign out / "Session expired" re-auth state.
  Server URL field stays (advanced).
- Wire `signInToSync` / `signOutOfSync` through `ChatViewModel`.
- Build: `./gradlew :app:assembleDebug`. Commit.

### Step 4 — [S] Desktop (`:desktop`)
- `DesktopGoogleSignInProvider`: loopback OAuth per §2.3 (fixed port 53682,
  PKCE + client secret, exchange code → id_token via `https://oauth2.googleapis.com/token`,
  minimal scopes `openid email profile`).
- Same `RemoteSyncSection` rework as Android (desktop component copy).
- Build: `./gradlew :desktop:compileKotlin`. Commit.

### Step 5 — [S] Web server (`:server`) — multi-user core
- `UserSession(username, email, …)`; remove password login + `WEB_UI_PASSWORD`.
- `/auth/google/start|callback` login flow + `GoogleTokenVerifier` interface
  (real impl + test fake), `ALLOWED_GOOGLE_EMAILS` allowlist.
- `UserRegistry` + per-user data dirs + per-user SyncEngine bootstrap (sync
  token minted at login via the sync server, stored in user dir; periodic pull
  loop per user; engines stopped on ApplicationStopping).
- Refactor ALL routes (Routing/MutationRoutes/P5Routes/SendRoute) to resolve
  `username`+repository from session via the registry.
- Update tests (Auth/ReadApis/Mutation/Streaming/Sync/Static…): authenticate via
  fake verifier; add an **isolation test** (user A's session cannot read user
  B's chats).
- Update `API_CONTRACT.md` (auth section + "single-user" notes) and
  `llm-web.env.example` / `DEPLOY.md` (new env vars, removed ones).
- Build: `./gradlew :server:test :server:installDist`. Commit.
  (If too large for one subagent run, split: 5a auth+registry, 5b route refactor
  +tests — orchestrator decides at execution time, each half builds+commits.)

### Step 6 — [S] Web frontend (`web/`)
- LoginPage → Google sign-in button (`window.location = '/auth/google/start'`),
  error query-param display (e.g. not-allowlisted).
- Account chip (email from new `GET /api/me`… add tiny endpoint in step 5) +
  Logout in Settings page; keep 401 redirect behavior.
- Build: `npm --prefix web run build` (+ existing vitest suite). Commit.

### Step 7 — [O]+[U] Deploy & end-to-end
- **[U] Google Cloud Console** (one-time, do early — can be done right after
  Step 0): on OAuth client `926212364522-…`, add redirect URIs:
  `https://app.api-divonr.xyz/auth/google/callback` and
  `http://127.0.0.1:53682/` (desktop loopback). Provide the client secret to
  the env files if not already there (`GOOGLE_OAUTH_CLIENT_SECRET`).
- [O] Update `llm-web.env`, rebuild `web/dist` + `:server:installDist`, restart
  `llm-web.service`; verify sync-server already live from Step 1.
- [U] Real-device test: phone → Settings → enable sync → Google sign-in →
  verify migration (username changed from `default`), full upload; web →
  sign in with the same account → same chats appear; desktop → sign in → same.
- [O] Isolation spot-check via curl (token A vs blobs of B), docs final pass,
  merge `google-auth` → `web-ui`, push.

---

## 4. Risks / decisions on record

- **Wiping the remote DB is approved** by the owner (local backup exists on his
  phone; he will re-upload via the new sign-in flow).
- Username = sanitized email (human-readable filenames); `sub` kept server-side
  as the stable key. Collisions after sanitization are theoretically possible
  and rejected at sign-up (`username UNIQUE`, different `sub` → 409) — acceptable
  for a personal server.
- Desktop bundles the Google client secret (same precedent as GitHub desktop
  flow in this repo). For an installed-app loopback flow this is standard
  practice (the secret is not treated as confidential for native apps).
- Web sessions live 30 days; sync tokens are long-lived. Revocation = delete
  token rows / change session secret.
- `app_settings.json` remains a synced, per-user blob; `remoteSync` block (now
  containing the minted token) is still stripped on upload — verify in Step 2
  tests.
- The Ktor route refactor (Step 5) is the largest single step; the existing test
  suite is the safety net and must pass before commit.
