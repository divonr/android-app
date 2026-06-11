# Per-User Sync & Google Sign-In — Implementation Progress

Persistent checkpoint file. Survives crashes / usage-limit cutoffs. **Every
subagent reads this FIRST, marks its step `[~]` on start and `[x]` on finish
with a one-line note + commit hash. Never redo an `[x]` step.**

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done

Plan file: `USER_AUTH_PLAN.md` (same directory — read it for full design, §2)
Branch: `google-auth` (off `web-ui`) in android-app.
Sync server repo: `/home/divonr/ApI/sync-server` (own git repo since Step 0).

---

## Step 0 — Scaffolding [O]
- [x] branch `google-auth` created off `web-ui`
- [x] plan + progress files committed — be693bd
- [x] sync-server: git init + .gitignore + baseline commit of current code — 3ccee5f (sync-server repo, branch main)

## Step 1 — Sync server v2 (Python) [S]
- [x] `server.py` rewrite: users/tokens/blobs schema, POST /auth/google
      (google-auth verify, mocked in tests), opaque tokens (sha256 stored),
      v2 paths /sync/manifest, /sync/file/{filename} (no {user} segment) — 14/14 tests pass
- [x] `requirements.txt` + google-auth installed into venv — fastapi/uvicorn/cryptography/google-auth/requests/pydantic/pytest/httpx
- [x] tests (mocked verifier): 401s, mint, per-user isolation, Fernet round-trip — 14/14 tests pass
- [x] `sync-server.service` env update — file unchanged (EnvironmentFile=.env); orchestrator must update .env (drop SYNC_TOKEN, add GOOGLE_OAUTH_CLIENT_IDS)
- [x] orchestrator: wiped sync_data.db, .env updated (SYNC_TOKEN→GOOGLE_OAUTH_CLIENT_IDS),
      service restarted (via kill + Restart=always — no sudo), health OK on
      localhost AND https://sync.api-divonr.xyz, 401s verified, fresh v2 schema confirmed
- [x] commit (sync-server repo)
- commit: f300511 (sync-server repo)

## Step 2 — Shared module (:shared) [S]
- [x] `RemoteSyncSettings` v2 (authToken=minted, accountEmail; back-compat defaults)
- [x] `GoogleSignInProvider` / `GoogleIdentity` interfaces — new file data/sync/GoogleSignInProvider.kt
- [x] `RemoteStorageClient` v2 paths + `authGoogle(idToken)` + sealed Unauthorized exception
- [x] `SyncEngine`: no user path segment; `needsReauth` StateFlow on 401; `clearReauth()` method
- [x] `UserMigration.migrateToAccount(newUsername)`: rename per-user files
      (chat_history, api_keys, custom_providers, full_custom_providers,
      github_auth, google_workspace_auth) + re-key githubConnections /
      googleWorkspaceConnections + set current_user; switch-only if target exists
- [x] `DataRepository.signInToSync(identity)` / `signOutOfSync()` + `needsReauth` exposed
- [x] build `:shared:compileKotlin` ✓  `:app:compileDebugKotlin` ✓  `:desktop:compileKotlin` ✓
      no compile-fixes needed in :app or :desktop; existing UI still compiles unchanged
      (orchestrator also verified :server:compileKotlin ✓)
- commit: 2c1ac98

## Step 3 — Android (:app) [S]
- [x] `SyncGoogleSignInProvider` — minimal scopes (id/email/profile only), reuses
      GoogleWorkspaceAuthService.CLIENT_ID, fires sign-out before returning intent
      so account picker always appears
- [x] `RemoteSyncSection` rework — auth-token field removed; signed-out/signed-in/
      needsReauth/in-progress states; account email row + sign-out; URL field kept
      as "advanced"; all strings via strings.xml
- [x] ViewModel wiring — getSyncSignInIntent / handleSyncSignInResult (exchanges
      token, runs signInToSync, reloads user data, shows snackbar) / signOutOfSync;
      syncNeedsReauth + syncSignInInProgress StateFlows exposed; updateRemoteSyncAuthToken removed
- [x] Activity result wiring in UserSettingsScreen mirrors IntegrationsScreen pattern
- [x] build `:app:assembleDebug` ✓ (1m 3s, 0 errors)
- commit: 972c10f

## Step 4 — Desktop (:desktop) [S]
- [x] `DesktopGoogleSignInProvider`: loopback OAuth on 127.0.0.1:53682, PKCE S256,
      OkHttp code→id_token exchange, JWT payload decode for email; client secret
      from env GOOGLE_OAUTH_CLIENT_SECRET or empty bundled constant (same pattern
      as GitHubOAuthService.CLIENT_SECRET); 3-minute timeout via CompletableDeferred
- [x] desktop `RemoteSyncSection` rework — token field removed; 4 states (signed-out,
      signed-in+email row+sign-out, needsReauth warning, in-progress spinner);
      URL field labeled "advanced"; 10 new R.kt string constants (99–108);
      UsernameScreen wired to new params + sign-in/sign-out callbacks
- [x] DesktopRepository: needsReauth StateFlow + signInToSync + signOutOfSync delegates
      to syncEngine / UserMigration / RemoteStorageClient (mirrors DataRepository)
- [x] ChatViewModel (desktop): syncSignInInProgress + syncNeedsReauth StateFlows,
      signInToSyncWithGoogle() direct coroutine (no activity-result indirection),
      signOutOfSync(), reloadUserDataAfterSignIn(); updateRemoteSyncAuthToken removed
- [x] build `:desktop:compileKotlin` ✓ (BUILD SUCCESSFUL in 9s)
- commit: 9e814f3

## Step 5 — Web server (:server) multi-user [S] — split into 5a + 5b
### 5a (DONE) — multi-user core, password login KEPT temporarily (maps to "default")
- [x] `UserSession(username="default", email)`; password login still works (5b removes it)
- [x] `/auth/google/start|callback` login routes + `GoogleTokenVerifier` (tokeninfo
      endpoint, injectable) + `SyncAuthClient` (mints per-user sync token via live
      sync server, canonical username from its response) + `ALLOWED_GOOGLE_EMAILS`
- [x] `UserRegistry`: per-user dirs `{data}/users/{username}/`, per-user
      DataRepository + SyncEngine seeded at login (token in user's app_settings
      remoteSync block; periodic pull per user; lazy rehydration; stop on shutdown)
- [x] ALL routes resolve username+repo from session via registry (Routing,
      MutationRoutes, P5Routes, SendRoute); `GET /api/me` added
- [x] existing tests adapted to users/default/ layout — 131 tests, 0 failures
- [x] build `:server:test :server:installDist` ✓ (orchestrator re-verified)
- commit (5a): 3771fb6
### 5b (DONE) — remove password auth, Google-only login, isolation tests, docs
- [x] remove password login + WEB_UI_PASSWORD + AuthConfig.password; delete legacy
      applySyncConfig/SyncConfig env seeding (SYNC_ENABLED/SYNC_USER/SYNC_TOKEN) —
      superseded by per-user sync; keep SYNC_SERVER_URL + SYNC_PULL_INTERVAL_SECONDS
- [x] NEW `GoogleLoginTest.kt` (was deferred from 5a): fake verifier + fake
      SyncAuthClient; login flow sets session + creates users/{username}/ with
      seeded settings; allowlist reject; /api/me; sync_unavailable redirect;
      two users → two dirs
- [x] rewrite all tests' login helper: password POST /login → fake-Google login
      (helper in ONE shared place); AuthTest reworked for new scheme
- [x] new isolation test: user A session cannot read user B chats/keys
- [x] API_CONTRACT.md auth section + llm-web.env.example + DEPLOY.md updated
- [x] build `:server:test :server:installDist`, commit
- commit (5b): 47f0bb6

## Step 6 — Web frontend (web/) [S]
- [x] LoginPage → "Sign in with Google" (navigate /auth/google/start) + error display
      for all 5 error codes (incl. token_exchange_failed); password login API removed
- [x] account chip (GET /api/me via auth.session→MeResponse) + logout in Settings;
      AuthGuard 401 redirect unchanged; 8 new he.ts i18n keys
- [x] build `npm --prefix web run build` ✓ + vitest 123/123 ✓ (13 new tests)
- commit: 2206f18

## Step 7 — Deploy & E2E [O]+[U]
- [ ] [U] Google Cloud Console: add redirect URIs
      `https://app.api-divonr.xyz/auth/google/callback` and
      `http://127.0.0.1:53682/` to client 926212364522-…; client secret into envs
- [x] [O] update llm-web.env (password+legacy sync vars removed; client ID filled;
      ALLOWED_GOOGLE_EMAILS=haravsihot@gmail.com; GOOGLE_OAUTH_CLIENT_SECRET left
      EMPTY — [U] must paste it), rebuilt dist+installDist, service restarted;
      verified: /health ok, /api/* 401 w/o session, POST /login 404,
      /auth/google/start 302→accounts.google.com w/ correct redirect_uri —
      locally AND via https://app.api-divonr.xyz
- [ ] [U] real-device E2E: phone sign-in → migration default→username → upload;
      web sign-in same account → same chats; desktop sign-in → same
- [ ] [O] isolation spot-check via curl; docs final pass; merge google-auth → web-ui, push
- commit:

---

## Notes / decisions log
(append datestamped notes here as execution proceeds)
- 2026-06-11: 5b finished by fix-round subagent (followRedirects=false in 4 tests;
  docs: API_CONTRACT/DEPLOY/env.example) — 139/139 server tests. Step 6 subagent:
  Google login page + /api/me account chip + logout — 123/123 vitest, build ok.
  Step 7 [O] deploy done; login will work once [U] adds redirect URIs in Google
  Console and pastes GOOGLE_OAUTH_CLIENT_SECRET into server/deploy/llm-web.env
  (then restart llm-web). Merge google-auth→web-ui deliberately held until real
  E2E passes. Old single-user files left untouched at ~/.llm-api-web root
  (new layout is users/{username}/ — old files are simply ignored).
