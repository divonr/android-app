# Cloud Runs — plan and wire protocol

Goal: when the user enables "נהל ביצוע בקשות API בענן", LLM requests are executed by the web
backend (`server/`, unit `llm-web`, public https://app.api-divonr.xyz) instead of by the device, so that

1. a request is not lost when the client disconnects after sending, and
2. a streaming reply is visible live on every connected client of the account (phone, web).

The existing on-device path (Android `StreamingService` foreground service) stays 100% intact and
is the fallback whenever the cloud cannot take the request.

Progress is tracked in `CLOUD_RUNS_PROGRESS.md` (update your stage there when done).
Do NOT commit or push — the orchestrator commits after reviewing each stage.

## Principles

- **Server owns the run.** A run executes in a server-owned coroutine scope, never in the scope of
  an HTTP call. Client disconnects never cancel it. Only an explicit cancel does.
- **Server is the single writer of the reply.** The server saves tool messages / the final reply into
  ITS copy of the user's chat history with `repo.addAnchoredResponse(...)` and the ordinary sync
  uploads it. Clients never save the reply of a cloud run themselves; they pull it through sync.
- **Clients save their own user message** exactly as today (Android: locally, before sending).
- **One user-level event stream.** Each client keeps ONE SSE connection per account while it is in
  the foreground. On connect it receives a snapshot of all runs, then live events of all runs.
  Reconnect = new snapshot. No per-run attach, no sequence numbers.
- **Fallback, not failure.** If the cloud cannot take a request (unreachable, auth, missing key,
  unsupported tool/attachment), the Android client runs it locally through the unchanged service path.

## Auth

`/api/*` on llm-web accepts, in addition to the session cookie, `Authorization: Bearer <sync token>`
(the token the device already holds in `RemoteSyncSettings.authToken`).

- sync-server gets `GET /auth/whoami` (bearer auth) → `{"username": "...", "email": "..."}`.
- llm-web validates a bearer token by calling whoami on the sync server (cache token-hash → identity
  for ~5 minutes; never log tokens), applies the same allowed-emails rule as the Google login, and
  resolves the `UserContext` for that username. Every failure (missing/unknown token, sync server
  unreachable, email not allowed) is the same `401 {"error":"unauthorized"}`. A valid session cookie
  wins over a bearer header. Because of the cache, a token revoked on the sync server keeps working
  on llm-web for up to 5 minutes.
- If the user's server-side context has no running sync, bootstrap it with the presented token (same
  thing the login flow does, including the bounded ≤8 s wait for the first pull — so the very first
  bearer request of an account can be slow), so the server copy syncs. Two cases:
  - the user never logged in on the web (no sync credentials on the server): the copy starts syncing
    **with API-key sync on** (`remoteSync.syncApiKeys = true` in the server copy's device-local
    settings) — without it the server would never have the keys;
  - the stored token was rejected (`needsReauth`) or the pull loop is not running: sync resumes with
    the presented token and the user's existing API-key sync choice is kept.
- **API keys (S1 finding, needs a product decision):** `syncApiKeys` is a per-device opt-in and the
  web backend is a device. A user who logged in on the web and left "sync API keys" off there has no
  keys in the server copy, so every `POST /api/runs` answers `409 no_api_key` and the phone falls
  back to local execution, silently. S1 does NOT flip that web setting on behalf of a device.
  Either the user turns it on in the web settings, or a later stage decides to turn it on when a
  device asks for cloud runs.

## API (all under `/api`, cookie or bearer)

### `POST /api/runs` — start a run
Body (`RunRequest`, superset of today's `SendRequest`):
```
{ "runId": "<client uuid>", "chatId", "provider", "modelName",
  "messages": [Message...],            // the chat path, last = message being answered
  "systemPrompt", "webSearchEnabled", "enabledToolIds": [..],
  "thinkingBudget": "<string>", "temperature": <float|null>,
  "projectAttachments": [Attachment...],
  "groupId": "<the chat's group on the device>|null" }   // optional (S5), see Tools
```
`thinkingBudget` (this route and the web send/resend routes; shared `ThinkingBudgetWire`): the
legacy forms `none` | `low` | `medium` | `high` | `max` (= high) | `<integer>` (token budget), plus
the explicit `effort:<level>` and `tokens:<count>`. A device sends `effort:<level>` for the effort
levels the legacy forms cannot carry (`minimal`, `xhigh`, and the effort `none`, which a provider
treats differently from "no budget").

The server does NOT persist a user message for this route (the client already saved it and it
arrives through sync). The reply anchor is `ReplyAnchor.forRequest(messages)`; `addAnchoredResponse`
already restores the question path when the server copy does not have it yet. The server kicks a
non-blocking sync pull at run start.

`messages` MUST be the chat's path messages exactly as stored (same `id`s, and every user message
with its `nodeId` and `variantId`): the anchor and the path restore are built from them. A request
whose user messages lack `nodeId`/`variantId` can only be saved if the server copy already has the
chat; otherwise its reply is dropped.

Verified by tests (`CloudRunsSyncTest`): a run whose chat — or whose last question — is not in the
server copy yet saves its reply under the restored question, and after the device's own upload both
copies converge to one chat with one question and one reply (no duplicate node/variant; the device's
chat name and system prompt win over the restored placeholder). No bounded wait was needed.

`runId` is unique per user. `enabledToolIds` are tool ids as the shared `ToolRegistry` names them
(a device sends the ids of the tool specifications it would have sent to the provider itself); which
of them the server can run is the Tools section.

Responses:
- `202 {"runId","status":"running"}` — accepted.
- `200 {"runId","status":...}` — same `runId` seen before (idempotent retry; never starts twice).
  Checked before anything else, so a retry gets 200 even if the request would now be rejected.
  The registry is in memory: runs are known while running and for 10 minutes after they finish, and
  are forgotten by a server restart (a retry after that starts a new run).
- `409 {"error": "<human text>", "code": "<code>"}` — cloud cannot run it; client falls back locally.
  Codes, checked in this order: `unknown_provider`, `no_api_key` (no active key for the provider in
  the server copy), `unsupported_tools` (an enabled tool id the server has no tool for in this
  run — see Tools; the error text names the ids), `attachments_unsupported` (the request needs attachment bytes the
  server cannot get — see "Attachment store (round 2)", Server).
- `400 {"error"}` — malformed body, or blank `runId` / `chatId`, or empty `messages`.
- `401` — bad/missing auth.

### `GET /api/runs/stream` — user-level SSE
Frame format as today: `event: <type>\ndata: <json>\n\n`. A `: ping` comment line (frame `: ping\n\n`)
whenever nothing was sent for 15 s. The stream never ends by itself (only on server shutdown).
- First event: `snapshot` → `{"runs":[RunState...]}` (running runs + runs finished in the last 10 min).
- Then live events; every data object carries `runId` and `chatId` (in addition to the fields below):
  - `run_started` → `{"run": RunState}`
  - `partial` → `{"text"}` (delta), `thinking_started` → `{}`, `thinking_partial` → `{"text"}` (delta),
    `thinking_complete` → `{"thoughts": "...|null","durationSeconds","status": "NONE|PRESENT|UNAVAILABLE"}`
  - `tool_call` → `{"toolId","toolName","parameters"}`, `tool_result` → `{"toolId","success","output"}`
  - `messages_added` → `{}` (the text before a tool call + the tool messages are saved; accumulated
    text and thinking text reset)
  - terminal, exactly one per run, no event of the run's state follows it:
    `complete` → `{"text","messageId"}` (`messageId` is `""` if the reply could not be attached
    because the chat was deleted on the server), `error` → `{"error"}` (nothing saved by this event),
    `cancelled` → `{}`
  - `chat_updated` → `{}` (S5; only `runId` + `chatId`): not about the run's state — after the run
    ended, the server changed its chat (the automatic title) and uploaded it. At most one per run,
    always after the run's terminal event, possibly many seconds later (or never: no title was due,
    the generation failed, or it gave the same name). A client refreshes its chat list and the
    chat's title; it must not treat the run as running again. Clients that do not know the event
    ignore it (unknown event types are ignored by both clients).
- Snapshot + subscription are atomic (no event lost or duplicated between them).
- `messages_added`, `complete` and `cancelled` are emitted only after the save (if any) AND a bounded
  (≈3 s) flush of the sync upload, so a client can pull immediately.
- **Order at the end of a run (S5):** save the reply → flush the upload → terminal event → only then
  the automatic title generation (server-owned, in the registry's scope, given up after 20 s) → if
  it renamed the chat: flush the upload → `chat_updated`. The terminal event never waits for the
  title. Title generation runs after a saved `complete` reply and after a `cancelled` run whose
  partial reply was kept; not after `error`, and not on server shutdown.
- A run's retention and its `RunState` in snapshots are not affected by the title: a client that
  connects after the title was generated gets it through its normal sync / reload, not through an
  event.

`RunState`: `{"runId","chatId","provider","modelName","status":"running|complete|error|cancelled",
"text": "<text accumulated since the last messages_added>", "thinkingActive": bool,
"thinkingText": "<thinking text accumulated since the last messages_added>", "error": "...|null",
"startedAt": <ms>}`. `thinkingActive` is true between `thinking_started` and `thinking_complete`.
For a finished run `text` is whatever was streamed last (not authoritative — pull the chat).

What the server saves mirrors Android's `StreamingService`: the reply (and the text preceding a
tool call) is saved with its thoughts (`thoughts`, `thinkingDurationSeconds`, `thoughtsStatus`) and
`model`; tool-call / tool-response messages as the provider built them.

### `POST /api/runs/{runId}/cancel`
Body `{"keepPartial": true|false}` (no body = `false`). Mirrors Android's two stop behaviours
(`StreamingService.cancelRequest` vs `stopAndComplete` + the ViewModel's partial save):
`keepPartial=true` saves the text accumulated since the last `messages_added` (if not blank) as the
assistant reply (no thoughts); after the `cancelled` event the title generation runs as for a
completed reply (→ `chat_updated`); `false` saves nothing. Tool messages saved earlier stay in both modes.

Responses: `200 {"runId","status"}` — accepted, or the run had already finished (then nothing
changes; the first cancel of a run wins); `404` — unknown run (also: another user's run, or
forgotten after 10 min / a restart). The cancel is asynchronous: `status` in the response is
usually still `"running"`; the `cancelled` event (after the save + flush) is the confirmation.
The server stops listening to the provider at once, whatever the provider is doing.

Server shutdown/restart stops every running run as with `keepPartial=true`, then ends all streams.

### Existing web routes (`POST /api/chat/send`, `POST /api/chats/{id}/messages/{mid}/resend`)
Keep URL, request body and per-request SSE wire format, but run through the same run registry:
the route prepares the chat as today (persist user message / create branch), starts a run (server
generated `runId`), sends a first `run` event `{"runId"}`, then relays that run's events — same
event names and data as before, WITHOUT `runId`/`chatId` in the data and without `run_started` —
and ends the response after the run's terminal event. New on this stream: the `run` event, a
`cancelled` → `{}` terminal event (when the run was cancelled through the cancel endpoint), and
`: ping` comment frames. Closing this response does NOT cancel the run (until S3 the web's stop
button therefore no longer stops anything: the reply is still saved). Title generation after
completion stays (server side) for every run; since S5 it runs after the terminal event, so this
response has ended by then and the title is announced only on the user-level stream
(`chat_updated`). The pre-validation errors of these routes are
unchanged (400 unknown provider, 404 chat not found…): the 409 codes belong to `POST /api/runs` only.
Attachments are resolved as for `POST /api/runs` (round 2, Server), but there is no device to fall
back to: a file the server cannot get is — as before — not sent to the provider.
(A6) **Project chats.** The web does not build the project context; these two routes take it from
the server's own copy: when the chat's group is a project (`is_project` — Android's
`getCurrentChatProjectGroup`; a plain group adds nothing) the request gets the group's
`group_attachments` as its project attachments (the `projectAttachments` of the body is ignored
then; outside a project it is used as sent) and the system prompt is the project's `system_prompt`,
followed by a blank line and the request's `systemPrompt` (the chat's own prompt) when that is not
empty. (Android adds the chat's prompt only while the dialog's "override" switch is on; the web has
no switch, so a chat prompt is always added.) `POST /api/runs` is unchanged: the device sends its
own list and its effective prompt.
(A6) New pre-validation error of `POST /api/chat/send`: `409 {"error","code":"attachment_missing"}`
— a file of the new message exists neither in the store nor on the server (round 2, Server, Commit);
nothing is saved and no run starts. Tools: these routes use the same per-user tool set as
`POST /api/runs` (Tools section), but an enabled tool id the server has no tool for is — as before —
silently not offered to the model instead of a 409.

## Tools (S5)
The server is multi-user, so it never uses the process-wide `ToolRegistry.getInstance()` (that
singleton belongs to the single-user clients, Android and desktop, and is unchanged). Every run
gets its **own** `ToolRegistry` instance (`RunTools`, `server/.../runs/RunTools.kt`), built by the
user's `UserTools` (owned by `UserContext`) from that user's data **at the start of the run** — so a
connection that just synced, a disconnect and a refreshed token are picked up by the next run, and
one user's tools / tokens cannot serve another user's run. The same `RunTools` instance is used for
the capability check (`409 unsupported_tools`), the specifications sent to the provider, the
`toolName` of the `tool_call` event and the execution. A family is only built when the request
enables one of its ids. A run keeps the tools (and tokens) it started with until it ends.

| Family (tool ids) | Runs on the server when | Otherwise |
|---|---|---|
| `get_date_time` | always | — |
| GitHub (`github_*`, 8 tools) | the user's synced `github_auth_<user>.json` holds a connection whose token is not expired | 409 |
| Google Workspace (`gmail_*`, `calendar_*`, `drive_*`) | the user's synced `google_workspace_auth_<user>.json` holds a connection, the tool's service is enabled in it (`enabledServices`), and its access token is valid (after a refresh, below) | 409 |
| `get_current_group_conversations` | the run's chat is in a group of the user (below) | 409 |
| Skills (`read_skill`, `read_skill_file`, `write_skill_file`, `edit_skill_file`) | never | 409 |
| `python_interpreter` | (A2) the user has an attachment store, i.e. sync is on with a token in the server copy — always the case for an authenticated request (round 2, Server) | 409 |

- **Group tool context.** On Android the tool is built from the open chat and its group. On the
  server it is built for the run: `currentChatId` = the run's `chatId`; the group is the request's
  `groupId` when the device sent one (a chat created a moment ago is not in the server copy yet),
  else the `group` of the server copy's chat; it must be one of the user's groups in the server
  copy (its name goes into the tool description). The tool reads the user's chat history at
  execution time, from the user's own repository. The web routes send no `groupId`.
- **Google token refresh.** Android never refreshes: its access token (from `GoogleAuthUtil`) lives
  about an hour, after which the tools are unregistered until the user reconnects; the
  `refreshToken` field of an Android-made connection holds a one-time *server auth code*, not a
  refresh token. A connection made on the web (`/oauth/google/start`, `access_type=offline`) holds
  a real refresh token of the server's OAuth client. At run start, when a Google tool is enabled
  and the access token expires within 5 minutes, the server refreshes it (`grant_type=refresh_token`
  with `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET`, one refresh at a time per user) and
  saves the new access token + expiry into the connection file (locked load-modify-save), which
  syncs to the user's devices. So: web-made connections keep working; Android-made ones run in the
  cloud for the hour their token lives, then answer 409 (Google refuses the auth code; a refused
  token is not tried again until the connection changes) — exactly when the device itself has lost
  the tools too. A token that expires in the middle of a run is not refreshed for that run.
- **GitHub tokens** of the OAuth app do not expire; an expiring one (`expiresIn` set) is not
  refreshed (neither on the server nor on Android).
- **Why skills stay on the device:** sync carries only `skills_enabled.json` / `skills_sources.json`;
  the skill folders (`skills/<name>/SKILL.md` …) are not synced, so the server copy does not have
  the device's skills (it has the ones made on the web), and the skills catalog in the request's
  system prompt is the device's. A device with enabled skills therefore still falls back for every
  request. Needs skill-folder sync first.
- **Python (A2, was device-only in S5):** `PythonInterpreterTool` is never in a process registry —
  Android builds it per request from the open chat; the server builds it per run (round 2, Server).

## Attachments (v1) — superseded by "Attachment store (round 2)"; `unservableAttachment` is gone
Device-local attachment files are not on the server. The server answers
`409 attachments_unsupported` when executing the request would require reading a file that is not
the user's own file on the server; the client then falls back to local execution. The server never
writes server-local paths or re-uploaded ids of device attachments into the synced chat history.

The rule implemented in S1 (`unservableAttachment` in `server/.../runs/RunRoutes.kt`), derived from
`FileUploadManager.ensureFilesUploadedForProvider` and the provider classes:

1. Only attachments of **user** messages in `messages`, plus `projectAttachments`, are ever sent to
   a provider; only those are checked. (Attachments on assistant/tool messages are ignored.)
2. An attachment **needs its file** unless the selected provider sends it by reference:
   - `openai`: `image/*` and `application/pdf` with a non-blank `file_OPENAI_id` need no file. Every
     other mime type always needs the file (OpenAI gets it inlined as text read from disk).
   - `poe`: a non-blank `file_POE_url` needs no file.
   - `google` (AI Studio): a non-blank `file_GOOGLE_uri` (other than the `{file_URI}` placeholder)
     needs no file. (As on the device, an expired Files-API uri is not detected.)
   - every other provider (`anthropic`, `vertex`, `cohere`, `openrouter`, `llmstats`, custom
     providers): always needs the file (they inline the bytes).
3. An attachment that needs its file is servable only if `local_file_path` is an existing file
   **inside this user's directory on the server** (i.e. it was uploaded through the web's
   `/api/files/upload`). Anything else → `409`. For such web-uploaded files the server may upload to
   the provider and store the provider id in the history, exactly as the web's own send does.
4. Safety net: for the uploading providers (`openai`/`poe`/`google`) the request is also rejected
   if ANY attachment of ANY message lacks that provider's id and has a `local_file_path` that exists
   on the server but outside the user's directory — `ensureFilesUploadedForProvider` would upload
   that foreign file and write its id into the synced history.

Consequences for clients: a chat with a device-only image runs in the cloud on OpenAI/Poe/Google
once the device has uploaded it to that provider (the id is in the message it sends); it never runs
in the cloud on Anthropic & co., and text-like files never run in the cloud on OpenAI.

## Android client
Shared, no Android dependencies (`shared/.../data/cloud/`): `CloudRunClient` (start, cancel, the
event stream with reconnect), `CloudRunTracker` (the runs' client-side state → UI actions),
`ThinkingBudgetWire`. Android glue: `CloudRunManager` (`app/.../ui/managers/streaming/`).

- `RemoteSyncSettings` gains `cloudExecution: Boolean = false` and
  `cloudBaseUrl: String = "https://app.api-divonr.xyz"` (`remoteSync` is device-local, not merged).
  "Effective" = `cloudExecution` + sync enabled + a token + `syncApiKeys`
  (`RemoteSyncSettings.cloudExecutionEffective`).
- Settings UI (RemoteSyncSection, under the "sync API keys" toggle): checkbox "נהל ביצוע בקשות API
  בענן" with small grey text below: "אם אפשרות זו מופעלת, שליחת הפרומפטים וקבלת התשובות תבוצע בענן,
  תסונכרן בין מכשירים ולא תהיה תלויה בחיבור פעיל של האפליקציה בזמן הזרמת התשובות."
  It cannot be turned on unless API-key sync (`syncApiKeys`) is on: trying shows a toast saying the
  option requires syncing the keys to the cloud (another toast when sync is off / signed out).
  Turning key sync (or sync itself / sign-out) off turns it off too.
- Routing at the single choke point where requests are handed to `StreamingService`
  (`ChatViewModel.startStreamingRequest`, now `suspend`): effective → `POST /api/runs` (whole call
  ≤ 15 s, connect ≤ 5 s; the request id is the `runId`). Accepted (202/200) → the run is a "cloud
  run": NO foreground service is started; UI state is driven by the event stream through the same
  `StreamingEvent` handling. The request carries the chat's `groupId` (for the group tool). Anything else → the unchanged service path, plus a short toast
  ("הבקשה נשלחת מהמכשיר"); the reason goes to the app log. A request whose user messages lack
  `nodeId`/`variantId` is not offered to the cloud at all.
  Fallback classification: a 4xx answer (401, 409…) and a connection that was never made
  (refused, unknown host, no route) are definite; everything else (timeout, connection lost, 5xx)
  is ambiguous → a best-effort cancel (`keepPartial=false`, up to 3 attempts in the background)
  for that `runId` while the device answers, and every later event of that run is ignored.
- Event stream: one connection while the app is resumed and the feature is effective; reconnect
  with backoff 1 s → 30 s (reset by a snapshot); a connection silent for 45 s is dropped. From the
  snapshot/events, chats with running runs show the streaming UI even when the run was started by
  another device (and a run nobody announced is adopted from its first event). A chat displays one
  run: a run in a chat that is busy with an on-device request (a fallback run) or with another
  cloud run is followed silently (only pulls) until the display is free.
  A run started by another device triggers a pull when it appears (and one more 3 s later): its
  chat / question may not have synced yet.
  On `messages_added` / terminal events: pull (awaited, up to 3 attempts of ≤ 6 s), reload the chat
  from the local repo, then clear the streaming overlay in the same UI update — `complete` waits
  until its `messageId` is in the chat, `messages_added` until the chat's messages changed; text
  that streamed after a `messages_added` stays. A locally known cloud run that is absent from a
  fresh snapshot (or finished in it) is treated as finished (pull + clear); one that failed in it
  shows its error. `error` is shown like an on-device error (snackbar) + a pull.
  `chat_updated` → a pull; the ordinary reload after a sync refreshes the chat list and the open
  chat's title. It never adopts, shows or ends a run (also handled for a run the device gave up).
- Stop button on a cloud run → cancel endpoint with `keepPartial=true` (the device's stop button is
  `stopStreamingAndSave`); the text stops growing at once and the overlay goes with the `cancelled`
  event (which no longer waits for the title generation). Stop while the start request is still in flight is applied when it returns. If the cancel
  cannot reach the server (or answers 404) the chat is given back at once; a run that is in fact
  still going shows again with its next event.
- No local save of replies, no local title generation for cloud runs (the server does both). No
  push notifications. Turning the feature off, or another account, forgets every followed run.

## Web client
- One connection to `/api/runs/stream` per tab while a signed-in page is mounted (`useRunsStream` in
  `AppLayout`; fetch reader, cookie auth). Reconnect with backoff 1 s → 30 s (reset by a snapshot);
  also reconnects when nothing — not even a ping — arrived for 45 s; stops on 401.
- `runsStore` follows EVERY run of the account from that stream. Runs whose per-request stream the
  tab is reading are "own" (claimed by the `run` event's `runId`): the store keeps their state but
  has no side effects for them, so the two streams never both act on one run. Every other run of the
  open chat is shown with the same streaming bubble as an own stream (text, thinking, tool calls;
  a snapshot carries no tool calls), and the store reloads the open chat + the chat list on
  `run_started` (another device's question / new chat), `messages_added` and terminal events. A
  fresh snapshot is the truth: known runs missing from it (or finished in it) are cleared and their
  chat re-read.
- `chat_updated` (own and foreign runs alike, also for a run the store no longer knows): re-read the
  chat list and take the new name into the open chat (`chatStore.refreshTitle`) — only the name, so
  a reply that is streaming into the chat is not disturbed. The per-request stream of an own send
  ends at the terminal event, before the title exists; the title always arrives this way.
- Own sends keep using the per-request SSE. A per-request stream that breaks before the terminal
  event (after the `run` event), and leaving the chat while it streams, hand the run over to the
  account-level stream (no error; the fetch is closed, the run goes on).
- `messages_added` (both paths): the streamed segment stays on screen until the chat reload finishes,
  then it is dropped (the saved messages replace it) and a new segment starts.
- Stop button → `POST /api/runs/{runId}/cancel` with `keepPartial: true` (Android's stop button is
  `stopStreamingAndSave`), for own and foreign runs; the fetch is not aborted. The streamed text stays
  until the run's `cancelled` event, then the chat is re-read. Stop pressed before the `run` event
  arrives cancels as soon as it does.
- `web/dist` is what production serves (`WEB_STATIC_DIR`): `npm --prefix web run build` IS the web
  deploy. The S3 client needs the S1 server (against an older server the stop button does nothing,
  because there is no `run` event) — build only together with the server restart.

## Stages
- S1 server: sync-server whoami; llm-web bearer auth, run registry, routes, web routes on registry, tests.
- S2 Android + shared client. S3 web client. S4 deploy + live test.
- S5: per-user / per-run tools on the server (GitHub, Google Workspace, group tool), terminal event
  before the title generation + `chat_updated`.

---

# Attachment store (round 2) — supersedes "Attachments (v1)" above

Goal: attachment files are available to the server (cloud runs with files, Python tool) and to every
client of the account (a file attached on the phone is viewable on the web and vice versa). Applies
whenever sync is enabled, not only with cloud execution. The local fallback path stays intact.

## Identity
`Attachment` gains `sha256: String? = null` and `size: Long? = null` (backward compatible). The hash is
the file's identity across devices; `local_file_path` stays a device-specific hint only.
Resolution (one shared function used by every platform, `AttachmentResolver`): local path exists → use
it; else hash present → per-device cache `attachments_cache/<sha256>`
(`PlatformStorage.attachmentsCacheDir`, under `filesDir`, so per user on the web server); else download
from the store into the cache (verified, atomic rename; concurrent resolutions of one hash download
once); else unavailable.
- (A1) The server must not trust a `local_file_path` that a client sent: its resolver is built with a
  `trustLocalPath` predicate (only files inside the user's own directory); an untrusted path is
  skipped and the store copy is used.
- (A1) **Merge / older clients.** The chat merge compares whole messages, and a client that predates
  the fields rewrites the history without them — a plain 3-way merge would then drop the hashes (and
  count every such message as "modified", which also beats a deletion made on another device).
  `ChatHistoryMerger` therefore never loses `sha256` / `size` to a side that lacks them
  (`AttachmentIdentityRestore`): an attachment without a hash takes it from the same attachment — same
  chat or group, same message id, same `local_file_path`, name and mime type — of the base (before
  the merge) and of any of the three copies (after it). The repaired file is uploaded, so the server
  copy heals too. A hash can therefore never be removed from an attachment by sync; replace the
  attachment (another path) instead.

## Store (sync-server, per user, bearer auth)
Files on disk (not in SQLite) under `SYNC_FILES_DIR` (env; default `sync_files/` next to the DB,
git-ignored) as `<username>/<sha[:2]>/<sha256>`; metadata table `files` with state
`pending | committed`. `{sha256}` must be exactly 64 lowercase hex digits (else `400`).
- `PUT /files/{sha256}` — raw body, streamed to a private temp file, hash verified, then renamed into
  place; size limit `MAX_FILE_BYTES` (env, default 95 MB — the public URL is behind Cloudflare, which
  caps a single request body at 100 MB).
  New file → `201 {"sha256","size","state":"pending","existed":false}`. Already present →
  `200 {…,"existed":true}` without re-upload (the body is read and dropped; a pending file's 24 h
  clock restarts). `413` over the limit. `422 {"detail":{"error":"hash_mismatch",…}}` when the body
  is not the file the URL names. Nothing is kept after a failed or interrupted upload.
- `HEAD /files/{sha256}` → 200/404 (`Content-Length`, `X-File-State: pending|committed`).
  `GET /files/{sha256}` → bytes (Range supported).
- `POST /files/{sha256}/commit` → `200 {"sha256","size","state":"committed"}` — mark as used
  (idempotent). Done by whoever saves a message / project attachment that references the file.
  `404` when the store does not have the file: the committer uploads it again, then commits.
- `DELETE /files/{sha256}` — deletes ONLY a `pending` file (attached, then removed before any use):
  `{"deleted": true}`; a committed file is left alone (`{"deleted": false, "state": "committed"}`);
  `404` when the store does not have it.
- Sweep (at startup and every hour, `FILES_SWEEP_INTERVAL_S`): `pending` files older than 24 h are
  deleted (covers a client that died before its delete), and upload temp files idle for 1 h.
No cross-user dedupe. Deleting chats does not delete committed files (cleanup job is out of scope).
One copy per (user, hash): deleting a pending file also removes it for another draft that attached
the same bytes — that draft's commit then gets `404` and uploads again (handled by the shared client).
No per-user quota.

## Clients
Shared, no Android dependencies (`shared/.../data/attachments/`): `AttachmentStoreClient` (the routes
above; results, never exceptions), `AttachmentResolver`, `AttachmentUploader` / `AttachmentUpload`.
- Upload starts when the file is attached (background: `uploader.start(file)`). Removing the attachment
  before sending → DELETE (`uploader.discard(upload)`).
  At send: bounded wait for the upload (`upload.awaitStored(timeoutMs)`); on failure/timeout the request
  falls back to the device path (upload keeps going so the file still reaches other devices). The hash
  is known as soon as the file is hashed (`upload.digest()`), before the upload ends, so the message is
  saved with `sha256` + `size` either way. Sending commits the file (`upload.commitWhenStored()`:
  commits when the store has the file, re-uploading if the store lost it meanwhile).
- Lazy backfill: attachments already in history have no hash; a device that has the file hashes +
  uploads + commits it when a request needs it (`uploader.ensureStored(file)`), and records the hash
  on the stored attachment.
- Download only on demand (rendering / opening / using the file), cached per device.
- Web: uploads go through llm-web (cookie auth) into the store; attachments from any device are
  viewable/downloadable in the web UI.

## Android — A3
Shared, testable (`shared/.../data/attachments/DeviceAttachments.kt`): `DeviceAttachments` (the
device's whole side of the store), `KnownStoredFiles`, `providerReadsFile` (the server's
`attachmentNeedsFile` rule), `AttachmentTransferState`. Android glue: `DeviceAttachmentsProvider`
(`app/.../data/`: one instance per process, its own scope — shared by the UI and `StreamingService`),
`ui/components/AttachmentViews.kt`. Everything below applies while sync is enabled and signed in
(`RemoteSyncSettings.attachmentStoreConfig()`), with or without cloud execution; with sync off no
upload, hash, state or network call happens and the app behaves as before.

- **Attach** (every entry point goes through `AttachmentManager.addFileFromUri` /
  `addMultipleFilesFromUris`: file picker, gallery, camera, share intent, "attach this JSON instead
  of importing it"): the upload starts at once (`DeviceAttachments.attach`). The input area draws
  the file greyed with a spinning circle until the store has it (`transfers`, by local path); a
  failed upload shows a red error mark and "upload failed · tap to retry" (`retry`). A file over
  95 MB is not uploaded, shows nothing and stays device-only (no hash).
- **Remove before sending** → the upload is stopped and the pending file deleted from the store
  (`DeviceAttachments.remove` → `AttachmentUploader.discard`).
- **Send** (`MessageSendingManager`): the attachment is saved with `sha256` + `size`
  (`DeviceAttachments.use`: waits for the hash only — local I/O, at most 10 s, else the message is
  saved without it and the backfill below adds it) and the file is committed when the store has it;
  a commit / upload that cannot reach the store is tried again after 2 s, 10 s and 30 s. A failed
  upload is restarted by the send. With cloud execution effective the device does **not** upload the
  new files to the provider first (the server does and writes the ids back); when the request falls
  back, `DataRepository.sendMessage` uploads them lazily as for any other file.
- **Cloud run** (`CloudRunManager.tryStart`): before `POST /api/runs`, `prepareRequest` waits at most
  **20 s** for the files the provider reads (user-message attachments and project attachments, by
  `providerReadsFile`) to be in the store: an upload in flight is awaited; a hashed file this device
  has and has not seen committed is committed (uploaded when the store lacks it); a file without a
  hash is backfilled (below). The request carries the hashes. Not ready in time (or a device-only
  file is needed) → the device runs the request ("הבקשה נשלחת מהמכשיר"), the uploads go on in the
  background. An attachment with a hash and no file here (another device's) is assumed stored; one
  without a hash and without a file here is left to the server. `409 attachments_unsupported` →
  the ordinary fallback, and the hashes of the request are forgotten from `KnownStoredFiles`, so the
  next request asks the store again. Stop during the wait works as during the start request.
- **Lazy backfill**: an attachment without a hash whose file is on this device is hashed, uploaded,
  committed and its identity recorded (`DataRepository.recordAttachmentIdentity`, chat message or
  group) — awaited for the files a cloud run needs, in the background for the rest of the request
  (assistant attachments, files sent by provider id). When the device runs a request (sync on),
  `ChatViewModel.startStreamingRequest` starts the same backfill without waiting. Hashes seen
  committed are remembered per account (`files/attachments_state/known_<key>.txt`), so each file is
  asked about once.
- **Project files** (`GroupManager`): uploaded + committed when added, saved with their hash; the
  thumbnail shows the same uploading / failed state. Removing one stops an upload still in flight
  (a committed file stays in the store).
- **Files this device does not have** (web / another device / the cloud's Python tool):
  `rememberAttachmentFile` resolves through `AttachmentResolver` — `resolveLocal` at once; an image
  in a message bubble or among a tool call's generated files is downloaded when it is shown
  (spinner, "tap to retry" on failure); any other file, and a project file, when it is tapped
  (spinner on the chip). Downloads go to `files/attachments_cache/<sha256>` once and run in the
  process scope. A cached file is opened through a copy that carries the attachment's name
  (`cache/opened_attachments/`).
- **Device path** (`StreamingService`): `DeviceAttachments.localize` gives the request a copy whose
  user-message / project attachments point at this device's file (the cache copy, downloaded now if
  needed), so the provider upload and the inlining providers work for files of other devices. The
  copy is never saved: `ChatHistoryManager.updateChatWithNewAttachments` now writes only the three
  provider ids onto the stored attachment that is the same file (hash, else path + name + mime
  type) — never a path, and never over a hash recorded meanwhile.
- **Python tool on the device** (`ToolManager`): with sync on it gets `DeviceAttachments.pythonFiles`
  — input files also from the store; generated files saved in `files/attachments` as always, with
  `sha256` + `size`, and uploaded + committed in the background. With sync off: unchanged.
- **Lifetime**: uploads / downloads belong to the process, not to a screen: they survive rotation
  and leaving the chat, and go on while the app is in the background as long as the process lives
  (there is no foreground service for them). After a process death a draft's pending file is swept
  by the store (24 h); a sent message's file is stored by the next request that includes the
  message (it has the hash, or gets it by backfill).
- Desktop: no attachment sync (compiles; the shared write-back change applies to it too).

## Server (llm-web) — A2
Per user (`UserContext.attachments`, `server/.../attachments/UserAttachments.kt`): an
`AttachmentStoreClient` with the sync token of the user's server copy, the user's cache
(`users/<u>/files/attachments_cache/<sha256>`, never evicted) and an `AttachmentResolver`.

- **Path trust.** A `local_file_path` comes from a client. The server reads it only when it is a
  file inside the user's own attachment directories — `files/llm_data/attachments` (what the web
  saved before the store existed) and `files/attachments_cache` — after resolving symlinks and `..`.
  Every other path (another user's dir, the user's own data files, anything else on the machine) is
  ignored and the file is taken from the store by hash.
- **Which files a run needs** (`attachmentNeedsFile`): only attachments of **user** messages and
  the project attachments, and only when the provider reads the file — `openai`: everything except
  an image / PDF that has `file_OPENAI_id`; `poe`: no `file_POE_url`; `google`: no `file_GOOGLE_uri`;
  every other provider: always.
- **`POST /api/runs`** resolves the needed files before it answers (own file → cache → store
  download; at most 10 s for all of them): `409 attachments_unsupported` when one cannot be had —
  it has no hash and is not the user's file here, the store does not have it (the device's upload
  has not finished), the store cannot be reached, or the 10 s passed. Nothing is changed by a
  rejected request. The web's send / resend routes resolve the same way (60 s) but send the request
  without a file they cannot get.
- **In-flight copy.** The provider code reads `local_file_path`. The run hands the engine a copy of
  the request in which every attachment has this server's path of its file, or no path at all
  (`AttachmentFiles.inFlight`; a file that is sent by id gets none). The copy is never saved:
  `RepositoryChatEngine` calls `DataRepository.sendMessage` without a chat id, which turns off the
  repository's own write-back of the request's attachments (the one a device uses).
- **Provider ids.** For `openai` / `poe` / `google` the run uploads a resolved file whose id is
  missing (`ProviderFileUploader`, the device's upload code) before the request, and writes the id
  onto the stored attachment with `DataRepository.recordAttachmentProviderIds`: the attachment of
  that message id (every copy: path and tree) — or of the chat's group for a project attachment —
  with the same hash (no hash on either side: same path + name + mime type). Only the three id
  fields are written, in one locked load-modify-save; never a path. When the chat is not in the
  server copy yet, the write is tried again at the run's next save (the first reply restores the
  question). Project attachment ids are written to the request's `groupId`, else the group of the
  server copy's chat.
- **Lazy backfill on the server.** A needed attachment found in the user's own directory without a
  hash (a pre-store web upload) is hashed, uploaded and committed in the background and its identity
  recorded with `DataRepository.recordAttachmentIdentity` (the shared helper A3 uses on the device:
  the attachment of that chat / group + message id without a hash and with the same path, name and
  mime type gets `sha256` + `size`; an attachment that has a hash is never changed).
- **Commit — and a delete of the same bytes (A6).** A web upload's bytes exist only in the store
  (pending) and in the server's cache, and `DELETE /api/files/{sha}` removes both. So a delete of
  the same bytes (another tab, another draft, the phone through llm-web) must never win against a
  save that references them. Every server path that saves a new user message or a project
  attachment — `POST /api/chat/send`, `POST /api/chats/{id}/messages`, `POST /api/chats/{id}/branch`,
  `POST /api/groups/{id}/attachments` — goes through `UserAttachments.beginSave`:
  1. the hashes are **held** (in memory, per user) — taking the hold passes through the same
     per-user mutex under which a delete does its whole work, so a delete is either completely
     over before the hold exists or sees it;
  2. the files are **committed before the save**, waiting at most 10 s for the first attempt of
     each (a `404` → upload again from the server's cache, then commit; a store that cannot be
     reached is retried after 1 s and 5 s in the background and does not stop the save);
  3. a file that the store does not have (`404`, or the user has no store) and the server has no
     copy of was deleted before the save: send / add-message / group-attachment answer
     `409 {"error","code":"attachment_missing"}` and save nothing (the branch route saves anyway —
     an edit keeps the attachments of an already saved message, possibly another device's);
  4. the save; the hold ends when the save is over **and** the commits have ended.
  The delete (`UserAttachments.deleteUnused`) refuses — `{"deleted":false,"state":"in_use"}` — a
  hash that is held or that the user's stored history references (any message, variant or project
  of the server copy); for a referenced file it starts a commit, which heals a commit that had
  given up. After the hold the saved message is what protects the file, so no order of save,
  commit and delete leaves a saved reference to a file this server deleted.
  `…/resend` saves a copy of a message that is already in the history (so already protected) and
  keeps the plain background commit (`commitInBackground`). `POST /api/runs` does not commit (the
  device saved the message and commits it).
  Not covered, by design: a delete that goes **directly to the sync server** (a device) knows only
  pending / committed. For a device's own message that is no loss — the device keeps the bytes
  and its commit uploads again on `404`, at the send and, after a restart, by the next request
  that includes the message (tests in `DeviceAttachmentsTest`). For a web upload it is closed by
  step 2 in the normal case (committed before the save). Only when the store was unreachable at
  the save and for the two retries does the file stay pending: a direct device delete, or the
  store's 24 h sweep, can then remove the store copy. The server's cache copy stays (only
  llm-web's own delete removes it, and that one is refused), so the web still shows the file; the
  store gets it again at the next refused delete or `POST /api/files/{sha}/commit` — there is no
  periodic re-commit.
- **Python tool** (`UserTools.pythonTool`): built per run when the request enables it and the user
  has a store. Input files: the attachments of the request and of the server copy's chat and group
  that have a hash (or are the user's own file here), fetched through the resolver when the code
  asks for them by name. Generated files: written to the user's cache under their hash, uploaded
  and committed (`AttachmentUploader.ensureStored`; retried in the background when the store
  cannot be reached) and described by `file_name`, `mime_type`, `sha256`, `size` — no path. As in
  Android's `StreamingService`, they are attached to the **next assistant message** the run saves
  (the text before the next tool call, or the final reply; text `[קובץ מצורף]` when there is none;
  on a cancel with `keepPartial` they are kept with the partial reply). The tool's result
  (`toolCall.result.details.output_files`, stored in the tool-call message) now carries `sha256` /
  `size` and has `local_file_path` only on a device; `PythonInterpreterTool.outputAttachments` is
  the one parser (used by the service and the server). The server's Python session is its own
  (`python_sessions.json` in the user's dir), not the device's.

## Web file routes (llm-web, under `/api`, session cookie or sync bearer) — A2
The browser cannot talk to the sync server; these routes work on the authenticated user's store
with the user's own sync token. `{sha256}` = 64 lowercase hex digits, else `400 {"error","code":"invalid_hash"}`.
Errors are `{"error": "<text>", "code": "<code>"}`; every `404` of the server is `{"error":"Not found"}`.
A hash is not a capability: another user's request for it is a `404`.

- `POST /api/files/upload` — `multipart/form-data`, the file in a part named `file` (other fields are
  ignored). Streamed to disk while hashed, then uploaded to the store as **pending**.
  `201` → an `Attachment`: `{"file_name","mime_type","sha256","size"}` and **no `local_file_path`**
  (`null`; provider ids `null`). The same bytes again → the same answer without a second upload.
  `400 no_file` (no file part / empty file). `413 file_too_large` + `"maxBytes"` over 95 MB (a
  request whose `Content-Length` is far over the limit is refused unread). `502 store_unavailable`
  when the store cannot take it (nothing is kept).
- `GET /api/files/{sha256}?name=<file name>&mime=<mime_type>[&download=1]` — the bytes, from the
  user's cache or (downloaded on demand, then cached) the store. `404` when neither has it, `502`
  when the store cannot be reached. `name` and `mime` are the attachment's own fields; both are
  optional and neither is trusted:
  - `mime` in `image/png|jpeg|gif|webp|avif|bmp` → that `Content-Type`, `Content-Disposition: inline`
    (`attachment` with `download=1`) — usable as `<img src>`;
  - `application/pdf`, `text/plain`, `audio/mpeg|mp4|wav|x-wav|ogg|webm|aac|flac`,
    `video/mp4|webm|ogg|quicktime` → that `Content-Type`, but `Content-Disposition: attachment`
    (usable as `<audio>` / `<video>` source or fetched into a blob; not navigable as a page);
  - everything else (HTML, SVG, XML, scripts, no `mime`) → `application/octet-stream`, `attachment`.
  Always `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; sandbox`,
  `Cache-Control: private, max-age=31536000, immutable`, `Accept-Ranges: bytes`. `name` only fills
  the `filename` of the disposition (sanitized, plus `filename*=UTF-8''…`). Single `Range` requests
  (`bytes=a-b`, `a-`, `-n`) → `206` + `Content-Range`; unsatisfiable → `416`; multi-range → the whole file.
  The web UI must not turn fetched bytes of a non-image type into a same-origin document itself
  (e.g. a blob URL of `text/html` / `image/svg+xml` opened in the app's origin).
- `POST /api/files/{sha256}/commit` — `200 {"sha256","size","state":"committed"}` (idempotent). When
  the store lost the file and the server still has it, it is uploaded again first. `404` when
  neither has it. Not needed after the send / add-message / group-attachment routes (they commit).
- `DELETE /api/files/{sha256}` — remove an upload that was never used: `200 {"deleted":true}` (gone
  from the store and from the server's cache) or `{"deleted":false,"state":"committed"|"in_use"|null}`
  — a committed file is never deleted; `in_use` (A6): a save that references the file is in
  progress, or a saved message / project of the user references it (it is then committed if it was
  not); `null`: the store does not have it.
- `DELETE /api/files` (legacy URL) with `{"sha256"}` → the same. With `{"filePath"}` (the old web
  client): only a file inside the user's own `files/llm_data/attachments` that no saved message /
  project uses is deleted (`204`); anything else is `404` (`409` when in use) — the old route
  deleted any path the caller sent.
- A user whose server copy has no sync credentials (cannot happen after a login / bearer request)
  gets uploads kept in the server cache only, `commit` → `"state":"local"`.

How the web UI uses them: attach → upload → keep the returned `Attachment` in the draft; remove from
the draft → `DELETE /api/files/{sha256}`; send → put the `Attachment` (as returned) into the
message / group — the server commits; show any attachment that has `sha256` (from any device)
through `GET /api/files/{sha256}?name=&mime=`. An attachment without `sha256` exists only on the
device that attached it.

## Web UI — A4
- **Composer** (`useDraftAttachments`, one draft per open chat, in memory only). A picked file
  (several can be picked at once) starts uploading immediately and is shown greyed with a spinner
  until the upload answers; a failed upload stays in the draft in its own state with the reason
  (`store_unavailable`, expired session, `file_too_large`…) and a retry button (no retry for a
  file the server called too large / empty). A file over 95 MB, or an empty one, is refused before
  any request (error banner). **Sending is disabled** (button and Enter) while a file of the draft
  is uploading or failed — the failed one has to be retried or removed; a line under the files
  says why. Nothing is sent without its files and nothing is queued.
- **Deleting un-sent uploads.** Removing a file from the draft → `DELETE /api/files/{sha256}` at
  once; while it is still uploading the request is aborted, and if the upload completed anyway the
  file is deleted when its hash arrives. Leaving the chat (or the page) with un-sent files deletes
  them; closing / reloading the tab does the same with `keepalive` requests (`pagehide`). The
  draft itself is not kept over a reload. Failed deletes are silent (the store's 24 h sweep is the
  net — also for an aborted upload the server had in fact stored, whose hash the tab never saw).
  The delete is skipped when the store's single copy is still needed: another entry of the draft
  has the same hash (the same file attached twice), another upload of the same size is still
  running (it may be the same file), or the tab already put that hash into a message / project
  (kept as a second line of defence; since A6 the server itself refuses such a delete — `in_use`).
- **Send** puts the `Attachment`s as returned by the upload into the message (send and the
  multi-message buffer); the web never calls `commit`. (A6) A message may consist of files only:
  it is sent (button and Enter, also in multi-message mode) with the text `[קובץ מצורף]`, the
  same stored text as Android's; an edit still needs a text (the composer's files are not part of
  an edit). The web has no first-message chat naming at all (a chat is created as "שיחה חדשה"
  before its page opens and renamed by the title generation), so Android's "name a new chat after
  its first file" has no counterpart. A send refused with `409 attachment_missing` shows the
  server's text in the error banner; the file has to be attached again. An edit keeps the message's own attachments;
  files in the draft at that moment are discarded (deleted).
- **Showing attachments** (`AttachmentView`; messages of every role — user, and assistant for the
  Python tool's files — and the project files of a group): with a `sha256` (64 lowercase hex) and a
  `mime_type` of the server's inline image list → the image, `<img src>` = the file route, requested
  only when its placeholder comes near the viewport (IntersectionObserver); a click opens the route
  in a new tab, a corner button downloads (`download=1`). Any other type with a hash — SVG, HTML,
  PDF, audio, video included — is a download link (`download=1`), never rendered in the page and
  never fetched into a blob. Without a hash: the name with "זמין רק במכשיר שממנו צורף", no request.
  An image the route cannot serve (404 / 502) falls back to its name with "הקובץ אינו זמין כרגע".
  There are no server-made thumbnails: a thumbnail is the full image, scaled by the browser.
- **Project files** (`GroupPage`): upload → `POST /api/groups/{id}/attachments` (the server
  commits); a tile with a spinner meanwhile; the same size limit and error texts; an upload the
  group route refused (4xx) is deleted again.

## Stages
A1 sync-server store + shared model/client/resolver · A2 llm-web (runs, Python tool, web file routes)
· A3 Android · A4 web UI (the section above) · A5 deploy + live test (llm-web A2 needs the sync-server of A1 running)
· A6 fixes: delete-vs-save race on the server, project context on web sends, files-only messages on the web.
