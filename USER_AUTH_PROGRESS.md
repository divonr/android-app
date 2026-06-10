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
- [ ] branch `google-auth` created off `web-ui`
- [ ] plan + progress files committed
- [ ] sync-server: git init + .gitignore + baseline commit of current code

## Step 1 — Sync server v2 (Python) [S]
- [ ] `server.py` rewrite: users/tokens/blobs schema, POST /auth/google
      (google-auth verify, mocked in tests), opaque tokens (sha256 stored),
      v2 paths /sync/manifest, /sync/file/{filename} (no {user} segment)
- [ ] `requirements.txt` + google-auth installed into venv
- [ ] tests (mocked verifier): 401s, mint, per-user isolation, Fernet round-trip
- [ ] `sync-server.service` env update (drop SYNC_TOKEN, add GOOGLE_OAUTH_CLIENT_IDS)
- [ ] orchestrator: wipe sync_data.db (APPROVED), update real env, restart
      service, curl health + 401 check
- [ ] commit (sync-server repo)
- commit:

## Step 2 — Shared module (:shared) [S]
- [ ] `RemoteSyncSettings` v2 (authToken=minted, accountEmail; back-compat defaults)
- [ ] `GoogleSignInProvider` / `GoogleIdentity` interfaces
- [ ] `RemoteStorageClient` v2 paths + `authGoogle(idToken)` + Unauthorized exception
- [ ] `SyncEngine`: no user path segment; `needsReauth` StateFlow on 401
- [ ] `UserMigration.migrateToAccount(newUsername)`: rename per-user files
      (chat_history, api_keys, custom_providers, full_custom_providers,
      github_auth, google_workspace_auth) + re-key githubConnections /
      googleWorkspaceConnections + set current_user; switch-only if target exists
- [ ] `DataRepository.signInToSync(identity)` / `signOutOfSync()`
- [ ] build `:shared:compileKotlin`, commit
- commit:

## Step 3 — Android (:app) [S]
- [ ] `SyncGoogleSignInProvider` (minimal scopes, existing web client id)
- [ ] `RemoteSyncSection` rework: sign-in button / account row / sign-out /
      needsReauth state; token field removed; URL field stays
- [ ] ViewModel wiring (signInToSync / signOutOfSync)
- [ ] build `:app:assembleDebug`, commit
- commit:

## Step 4 — Desktop (:desktop) [S]
- [ ] `DesktopGoogleSignInProvider`: loopback OAuth on 127.0.0.1:53682, PKCE,
      code→id_token exchange, scopes `openid email profile`
- [ ] desktop `RemoteSyncSection` rework (mirror Step 3)
- [ ] build `:desktop:compileKotlin`, commit
- commit:

## Step 5 — Web server (:server) multi-user [S]
- [ ] `UserSession(username, email)`; password login + WEB_UI_PASSWORD removed
- [ ] `/auth/google/start|callback` + `GoogleTokenVerifier` (real + test fake)
      + `ALLOWED_GOOGLE_EMAILS` allowlist
- [ ] `UserRegistry`: per-user dirs `{data}/users/{username}/`, per-user
      DataRepository + SyncEngine (token minted at login via sync server,
      stored in user dir; periodic pull per user; stop on shutdown)
- [ ] ALL routes resolve username+repo from session (Routing, MutationRoutes,
      P5Routes, SendRoute); `GET /api/me` added
- [ ] tests updated + new isolation test (A cannot read B)
- [ ] API_CONTRACT.md + llm-web.env.example + DEPLOY.md updated
- [ ] build `:server:test :server:installDist`, commit
- commit:

## Step 6 — Web frontend (web/) [S]
- [ ] LoginPage → "Sign in with Google" (navigate /auth/google/start) + error display
- [ ] account chip (GET /api/me) + logout in Settings; 401 redirect kept
- [ ] build `npm --prefix web run build` + vitest, commit
- commit:

## Step 7 — Deploy & E2E [O]+[U]
- [ ] [U] Google Cloud Console: add redirect URIs
      `https://app.api-divonr.xyz/auth/google/callback` and
      `http://127.0.0.1:53682/` to client 926212364522-…; client secret into envs
- [ ] [O] update llm-web.env, rebuild dist+installDist, restart llm-web.service
- [ ] [U] real-device E2E: phone sign-in → migration default→username → upload;
      web sign-in same account → same chats; desktop sign-in → same
- [ ] [O] isolation spot-check via curl; docs final pass; merge google-auth → web-ui, push
- commit:

---

## Notes / decisions log
(append datestamped notes here as execution proceeds)
