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
  "projectAttachments": [Attachment...] }
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

`runId` is unique per user. `enabledToolIds` are the ids of the shared `ToolRegistry`.

Responses:
- `202 {"runId","status":"running"}` — accepted.
- `200 {"runId","status":...}` — same `runId` seen before (idempotent retry; never starts twice).
  Checked before anything else, so a retry gets 200 even if the request would now be rejected.
  The registry is in memory: runs are known while running and for 10 minutes after they finish, and
  are forgotten by a server restart (a retry after that starts a new run).
- `409 {"error": "<human text>", "code": "<code>"}` — cloud cannot run it; client falls back locally.
  Codes, checked in this order: `unknown_provider`, `no_api_key` (no active key for the provider in
  the server copy), `unsupported_tools` (an enabled tool id that is not registered in the server's
  `ToolRegistry` — today the server only has `get_date_time`; GitHub / Google Workspace / skills /
  Python tools are device-only), `attachments_unsupported` (the request needs attachment bytes the
  server does not have — see Attachments).
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
  - terminal, exactly one per run, nothing of the run follows it:
    `complete` → `{"text","messageId"}` (`messageId` is `""` if the reply could not be attached
    because the chat was deleted on the server), `error` → `{"error"}` (nothing saved by this event),
    `cancelled` → `{}`
- Snapshot + subscription are atomic (no event lost or duplicated between them).
- `messages_added`, `complete` and `cancelled` are emitted only after the save (if any) AND a bounded
  (≈3 s) flush of the sync upload, so a client can pull immediately. (Before `complete` the server
  also runs the automatic title generation, bounded to 20 s, so the title is uploaded with the reply.)

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
assistant reply (no thoughts), then title generation as for a completed reply; `false` saves
nothing. Tool messages saved earlier stay in both modes.

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
completion stays (server side) for every run. The pre-validation errors of these routes are
unchanged (400 unknown provider, 404 chat not found…): the 409 codes belong to `POST /api/runs` only,
and so does the attachments rule.

## Attachments (v1)
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
  `StreamingEvent` handling. Anything else → the unchanged service path, plus a short toast
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
- Stop button on a cloud run → cancel endpoint with `keepPartial=true` (the device's stop button is
  `stopStreamingAndSave`); the text stops growing at once and the overlay goes with the `cancelled`
  event (which can lag by the title generation, ≤ 20 s, when the kept partial is the chat's first
  reply). Stop while the start request is still in flight is applied when it returns. If the cancel
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
- Own sends keep using the per-request SSE. A per-request stream that breaks before the terminal
  event (after the `run` event), and leaving the chat while it streams, hand the run over to the
  account-level stream (no error; the fetch is closed, the run goes on).
- `messages_added` (both paths): the streamed segment stays on screen until the chat reload finishes,
  then it is dropped (the saved messages replace it) and a new segment starts.
- Stop button → `POST /api/runs/{runId}/cancel` with `keepPartial: true` (Android's stop button is
  `stopStreamingAndSave`), for own and foreign runs; the fetch is not aborted. The streamed text stays
  until the run's `cancelled` event, then the chat is re-read. Stop pressed before the `run` event
  arrives cancels as soon as it does. `cancelled` can lag by the title generation (≤ 20 s) when the
  kept partial reply is the chat's first reply.
- `web/dist` is what production serves (`WEB_STATIC_DIR`): `npm --prefix web run build` IS the web
  deploy. The S3 client needs the S1 server (against an older server the stop button does nothing,
  because there is no `run` event) — build only together with the server restart.

## Stages
- S1 server: sync-server whoami; llm-web bearer auth, run registry, routes, web routes on registry, tests.
- S2 Android + shared client. S3 web client. S4 deploy + live test.
