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
- `RemoteSyncSettings` gains `cloudExecution: Boolean = false` and
  `cloudBaseUrl: String = "https://app.api-divonr.xyz"` (`remoteSync` is device-local, not merged).
- Settings UI (RemoteSyncSection, next to the sync toggles): checkbox "נהל ביצוע בקשות API בענן" with
  small grey text below: "אם אפשרות זו מופעלת, שליחת הפרומפטים וקבלת התשובות תבוצע בענן, תסונכרן בין
  מכשירים ולא תהיה תלויה בחיבור פעיל של האפליקציה בזמן הזרמת התשובות."
  It cannot be turned on unless API-key sync (`syncApiKeys`) is on: trying shows a toast saying the
  option requires syncing the keys to the cloud. Turning key sync (or sync itself / sign-out) off
  turns it off too.
- Routing at the single choke point where requests are handed to `StreamingService`
  (`startStreamingRequest`): cloud effective (sync enabled + signed in + syncApiKeys + cloudExecution)
  → `POST /api/runs` with short timeouts. Accepted → the run is a "cloud run": NO foreground service
  is started; UI state is driven by the event stream through the same `StreamingEvent` handling.
  Any failure → the unchanged service path, plus a brief unobtrusive notice that the request is
  being sent from the device. On an ambiguous failure (timeout after the request may have reached
  the server) send a best-effort cancel for that `runId` before falling back, to avoid two replies.
- Event stream: connected while the app is in the foreground and the feature is effective; reconnect
  with backoff. From the snapshot/events, chats with running runs show the streaming UI even when the
  run was started by another device. On `messages_added` / terminal events: `pullNow()`, reload the
  chat from the local repo, then clear the streaming overlay (keep showing the streamed text until
  the pulled message is there; bounded wait). A locally known cloud run that is absent from a fresh
  snapshot is treated as finished (pull + clear).
- Stop button on a cloud run → cancel endpoint. No local save of replies, no local title generation
  for cloud runs (the server does both). No push notifications.

## Web client
- Open `/api/runs/stream` while the tab is open; show live streaming for runs the tab did not start
  (other device, or this tab before a reload); on `messages_added`/terminal events reload the chat.
- Own sends keep using the per-request SSE; use the `run` event's `runId` to ignore duplicates from
  the user-level stream. Stop button → cancel endpoint (aborting the fetch no longer stops the run).

## Stages
- S1 server: sync-server whoami; llm-web bearer auth, run registry, routes, web routes on registry, tests.
- S2 Android + shared client. S3 web client. S4 deploy + live test.
