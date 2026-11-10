# Cloud Runs — progress

Contract: `CLOUD_RUNS_PLAN.md`. One entry per stage; update yours when done.

## S1 — server (done, not committed, not deployed)

sync-server (`/home/divonr/ApI/sync-server`, separate repo)
- `GET /auth/whoami` (bearer) → `{"username","email"}`; 3 tests in `tests/test_server.py`.

llm-web (`server/`)
- `auth/SyncBearerAuth.kt`: `SyncWhoamiClient` seam (real / fake), `SyncBearerAuth` (5 min cache by
  token hash, allowed-emails rule, sync bootstrap), Ktor provider `sync-bearer`. `/api/*` is now
  `authenticate("session", "sync-bearer")`.
- `UserRegistry.ensureUserSync` (+ `bootstrapUserSync(enableApiKeySync)`): bearer bootstrap.
- `runs/RunRegistry.kt`: server-owned scope, per-user runs, idempotent start, `RunState`, atomic
  snapshot + subscribe, 10 min retention, cancel (`keepPartial`), shutdown. Saves through
  `repo.addAnchoredResponse`, then flushes the sync upload (`SyncEngine.flushPendingUploads`, bounded
  3 s) before `messages_added` / `complete` / `cancelled`.
- `runs/RunRoutes.kt`: `POST /api/runs`, `GET /api/runs/stream`, `POST /api/runs/{runId}/cancel`,
  the attachments rule, SSE helpers.
- `streaming/SendRoute.kt`: `POST /api/chat/send` and `…/resend` start a run and relay it (first
  event `run`); closing the response no longer cancels.
- `streaming/RepositoryChatEngine.kt`: registers the user's custom providers before each request
  (like Android's service).
- No change in `shared/`.

Tests: `CloudRunsTest`, `CloudRunsSyncTest` (+ `LiveServerSupport`: real Netty server + JDK client,
because the `testApplication` client cannot read an open stream), `FakeCasSyncServer` got whoami.

Open points for later stages
- API keys: a user logged in on the web with "sync API keys" off there gets `409 no_api_key`
  (see Auth in the plan) — decide before S4.
- Until S3 the web's stop button only closes its stream; the run goes on and its reply is saved.
  Deploy S1 together with S3.
- The run registry is in memory: a server restart stops running runs (partial text is saved) and
  forgets run ids.
