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

## S2 — Android + shared client (done, not committed, not installed on a device)

shared (`shared/.../data/cloud/`, no Android dependencies)
- `ThinkingBudgetWire`: the `thinkingBudget` string, both directions. New explicit forms
  `effort:<level>` / `tokens:<count>` next to the legacy ones (the legacy parser lost `minimal`,
  `xhigh` and the effort `none`). The server's `parseBudget` (`SendRoute.kt`) now delegates to it.
- `CloudRunModels.kt`: `CloudRunRequest`, `CloudRunState`, `CloudRunEvent` (+ parser), `SseParser`.
- `CloudRunClient`: `startRun` (never throws; `Accepted` / `Fallback(reason, mayHaveStarted)`),
  `cancelRun`, `events()` (snapshot + live events, `Disconnected`, reconnect with backoff, 45 s
  silence timeout). Bearer = `RemoteSyncSettings.authToken`.
- `CloudRunTracker`: pure state machine, stream events + local starts → `CloudRunAction`s (one
  displayed run per chat, snapshot reconciliation, adoption of unknown runs, abandoned runs).
- `RemoteSyncSettings.cloudExecution` / `cloudBaseUrl` / `cloudExecutionEffective`;
  `DataRepository.signOutOfSync` turns `cloudExecution` off.

app
- `ui/managers/streaming/CloudRunManager.kt`: routing (`tryStart`), stream lifecycle (resumed +
  effective), actions → the existing per-chat streaming state, awaited pull before the overlay is
  cleared, stop → cancel endpoint.
- `ChatViewModel`: `startStreamingRequest` is the router (suspend); the old body is
  `startServiceStreamingRequest`, untouched. `stopStreamingAndSave` / `cancelStreamingRequest` ask
  the cloud manager first. `updateCloudExecution`; key sync / sync off turn the option off.
- `RemoteSyncSection` checkbox + strings; `MessageSendingManager`'s start lambda is `suspend`.
- `StreamingService`, `StreamingEventManager`, `TitleGenerationManager`: not touched.

Tests (shared, `data/cloud/`): `CloudRunClientTest` (fake HTTP server: request body, bearer,
fallback classification, cancel, stream, reconnect, backoff, silence), `CloudRunTrackerTest`,
`CloudRunWireTest` (budget round trip, SSE framing, event parsing, settings compatibility).

Notes
- Nothing here ran on a device or against a live server: the Android glue (`CloudRunManager`, the
  ViewModel routing, the checkbox) is compiled only.
- Desktop: no checkbox, no routing (compiles).
- Device-only tools (GitHub, Google Workspace, skills, Python, group conversations) and custom
  providers always answer 409 → every such request shows the "sent from the device" toast.
- A fallback that happens after the app left the foreground may be refused by Android
  (foreground-service start from the background): the request then fails with the usual error.

## S3 — web client (done, not committed, not deployed)

All in `web/`:
- `src/api/stream.ts`: `run` event (`onRun`), `cancelled` terminal event (`onCancelled`), ping
  comments ignored, nothing dispatched after a terminal event; a stream that breaks / ends before
  the terminal event is `onStreamLost({runId})` (falls back to `onError` before the run id is known).
- `src/api/runs.ts`: `cancelRun(runId, keepPartial)`, `connectRunsStream` (account-level stream,
  reconnect with backoff, idle timeout, 401 stop).
- `src/utils/streamState.ts`: the streaming-bubble state + reducer shared by both stream sources.
- `src/stores/runsStore.ts` + `src/hooks/useRunsStream.ts` (mounted in `AppLayout`): all runs of the
  account, own/foreign dedupe by run id, chat / chat-list reloads, snapshot reconciliation.
- `src/stores/chatStore.ts`: silent `refreshChat(chatId)` / `refreshHistory()`.
- `src/pages/ChatPage.tsx`: own stream or foreign run in one bubble; stop = cancel endpoint with
  `keepPartial: true`; hand-over to the account stream on a broken stream and when leaving the chat;
  errors of foreign runs in the error banner.
- Tests: `runs.test.ts`, `runsStore.test.ts`, `useRunsStream.test.tsx` (new), `stream.test.ts`,
  `ChatPage.test.tsx` (extended). 14 files / 191 tests pass; `npm run build` and `npm run lint`
  (`tsc --noEmit`) pass.

Notes
- Not verified in a live browser against a live server (unit / component tests only).
- The chat list has no "streaming" indicator (there was none to reuse); a run of a chat that is not
  open shows when the chat is opened.
- `web/dist` is served live by llm-web: the verification build briefly put the S3 client in
  production against the pre-S1 server; `web/dist` was rebuilt from HEAD's `web/` right after. S4
  must build the web together with the server restart (see the plan's Web client section).

## S5 — server tools + terminal event before the title (done, not committed, not deployed)

A. Tools on the server (plan: "Tools")
- `server/.../runs/RunTools.kt` (new): `RunTools` (one run's private `ToolRegistry`), `UserTools`
  (per user, on `UserContext.tools`; `forRun(chatId, enabledToolIds, groupId)` builds the run's tools
  from the user's connections at run start), `IntegrationTools` seam (`RealIntegrationTools` = the
  shared tool classes). The server no longer touches `ToolRegistry.getInstance()`.
- `RunSpec.tools`; the run registry describes, names and executes tools only through it.
  `POST /api/runs` checks `unsupported_tools` against it; `/api/chat/send` and resend use it too
  (unavailable ids are still dropped silently there).
- Runs in the cloud now: date/time, GitHub (with the synced connection), Google Workspace (with the
  synced connection, its enabled services and a valid token), the group-conversations tool (chat in
  a group; `RunRequest.groupId` from the device for a chat that has not synced yet).
- Still 409 → device: skill tools (skill folders are not synced — only the enabled / source lists)
  and the Python interpreter (device attachment files in and out).
- Google token refresh: `server/.../oauth/GoogleTokenRefresher.kt` (seam + real refresh-token
  grant); the refreshed token is saved with the new `DataRepository.updateGoogleWorkspaceAuth`
  (locked load-modify-save, synced). Works for web-made connections; Android-made connections have
  no refresh token (see the plan).
- shared: `ExternalConnectionsManager.updateGoogleWorkspaceAuth` (+ `DataRepository` delegate),
  `CloudRunRequest.groupId`. `ToolRegistry` itself is unchanged.

B. Terminal event before the title
- `RunRegistry`: reply saved + flushed → `complete` / `cancelled` → title generation in the
  registry's scope (≤ 20 s, through the user's `TitleGenerator`) → on a rename: flush + new
  user-stream event `chat_updated`. `maybeGenerateTitle` returns whether it renamed the chat.
- shared / Android: `CloudRunEvent.ChatUpdated`, `CloudRunAction.ChatUpdated` → `pullNow()` (the
  sync reload refreshes list + title); `CloudRunManager` sends the chat's `groupId`.
- web: `runsStore` handles `chat_updated` for own and foreign runs → `chatStore.refreshTitle`
  (chat list + the open chat's name only). Per-request streams end at the terminal event.

Tests
- server `CloudRunToolsTest` (14): two users with different GitHub connections each run with their
  own token; a user without a connection gets 409, and a tool call of his run / web send finds no
  tool while another user's run holds its tools; GitHub / Google (services, expiry, refresh, refused
  and transient refresh failures) / group tool (run context, `groupId`, other user's group) / skills
  + Python; the web send route's tools; `complete` / `cancelled` before the title, then
  `chat_updated`; per-request stream ends first; no event without a rename or after the timeout.
  Fakes: `FakeIntegrationTools`, `FakeGoogleTokenRefresher` (default in all server tests: nothing
  reaches GitHub / Google); `LiveServer` now uses a blank title generator by default.
- shared: `chat_updated` parsing, tracker actions, stream delivery; `groupId` on the wire.
- web: `runsStore.test.ts` (4 new), `runs.test.ts`.

Notes
- Not run against the real GitHub / Google APIs or a live server: the real tool classes are the
  ones Android uses, wired by `RealIntegrationTools` (compiled, not executed in tests); the real
  Google refresh call is untested against Google.
- The web now offers GitHub / Google tools to the model on its own sends when the account has the
  connections and the tools are enabled in the synced settings (they were silently dropped before).
- A run stopped by a server shutdown gets no title for its kept partial reply.
- Deploy: server + web together as before (an S3 web client against an S5 server only misses the
  title refresh until the next sync refresh; an S5 Android client against an older server is fine —
  `groupId` is ignored there).
