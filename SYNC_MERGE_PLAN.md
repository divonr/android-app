# Sync Merge Redesign — Plan

Goal: replace whole-file last-write-wins sync with **conflict-free merging** so that the
Android app, desktop app and the web (Ktor server, which is just another sync device per
user) never lose each other's data.

Progress checkpoint: `SYNC_MERGE_PROGRESS.md` (read it first, mark `[~]`/`[x]` + commit hash).
Branch: `google-auth` (deployed). Sync server: `/home/divonr/ApI/sync-server` (own repo, `main`).
Background analysis (current behavior, races, file:line facts) lives in the orchestrator's
scratchpad; the essentials are restated here.

## 0. User's required behavior

1. Google account that never used the app signs into the **web** → account is created
   immediately and the web keeps maintaining it. Later the app enables sync with the same
   account → everything syncs to the app immediately (and the app's own local chats are
   kept and reach the web too).
2. App vs web disagree:
   - one side is a pure extension of the other (more chats / more messages in a chat) →
     the extending side wins;
   - both sides added → full merge containing everything:
     - different chats added on each side → all chats kept;
     - different messages added to the same chat from the same point → both continuations
       kept as **sibling branches** of the existing branching model
       (`MessageNode.variants`), appearing naturally in the branch arrows.

We generalize (1)/(2) with a **3-way merge against the last synced base**, which gives
exactly the "who extends whom" outcome, and in addition respects deliberate deletions
(a chat deleted on one side is not resurrected by the other side merely still having it).
When no base exists (first link of a device, lost state) we fall back to a **2-way union**.

## 1. Sync server (Python) — optimistic concurrency + history

- `PUT /sync/file/{f}` body gains optional `base_version: int | None`.
  - absent → legacy unconditional write (old clients keep working).
  - `0` → create-only (409 if the blob exists).
  - `N>0` → write only if current `updated_at == N`.
  - mismatch → **409** `{"detail":{"error":"version_conflict","current":{filename,updated_at,sha}|null}}`.
  - incoming sha == stored sha → return stored meta with 200, no new version (idempotent
    retry after lost response, and no-op PUTs don't bump versions).
- Run check+write inside `BEGIN IMMEDIATE`; `updated_at = max(now_ms, prev+1)` computed
  inside the lock → strictly increasing per blob.
- `PRAGMA journal_mode=WAL`, `busy_timeout`; throttle `tokens.last_used` updates (≤1/min).
- `blob_history` table: before every content-changing write copy the previous row
  (zlib-compressed, encrypted rows copied as-is). Keep last 30 per file.
- `GET /sync/file/{f}?version=N` (current or history), `GET /sync/history/{f}` (metadata).
- `GET /sync/health` → `{"status":"ok","cas":true}`; PUT response includes `"cas": true`.
- Tests for all of the above (CAS, 409, create-only, idempotency, monotonic under
  concurrency, history retention, versioned GET, legacy PUT, isolation).

## 2. Shared — storage hardening (`:shared`)

- `util/AtomicFiles.write(file, text)`: write temp file in same dir → fsync → atomic rename.
  All writers of synced files use it (managers' `writeAndNotify`, SyncState, base snapshots,
  UserMigration).
- `util/FileLocks.withLock(file) { }`: process-wide **reentrant** lock registry keyed by
  canonical path (companion/object, so Android's two DataRepository instances share it).
- `ChatHistoryManager.updateChatHistory(username, transform): UserChatHistory` — load +
  transform + save under the file lock. Every load-modify-save in `:shared`
  (ChatHistoryManager, GroupProjectManager, MessageBranchingManager, DataRepository
  cleanup etc.) goes through it. Exposed on DataRepository/DesktopRepository for callers.
- `loadChatHistory(username)` always returns `user_name = username`; `saveChatHistory`
  writes to the file of the passed/normalized username (never trust content `user_name`).
- Parse failure of an existing non-empty file: retry once; if still failing, copy it to
  `chat_history_x.json.corrupt-<ts>` before returning empty (never silently destroy).
- `MessageBranchingManager.migrateChatToBranchingStructure` becomes **deterministic**:
  nodeId/variantId = `UUID.nameUUIDFromBytes(...)` from chatId + message ids (+ index), so
  two devices migrating the same legacy chat produce identical IDs.
- `addResponseToCurrentVariant(..., targetVariantId: String? = null)`: when given, append to
  that variant (wherever it is), not `currentVariantPath.last()`. Callers pass the variant of
  the user message saved at send time (pinning; see §5/§6).
- `importSingleChat`: assign a fresh chat_id when the id already exists.

## 3. Shared — merge engine (pure, heavily unit tested)

`data/sync/merge/ChatHistoryMerger.merge(base: UserChatHistory?, local, remote): UserChatHistory`

Identity keys: chats by `chat_id`, groups by `group_id`, nodes by `nodeId`, variants by
`variantId`. **Never** key on `Message.id` across variants (sibling variants share
userMessage ids by design); response lists are compared by message-id sequence with a
content-equality fallback.

Normalization per side first: dedupe duplicate chat_ids inside one file (2-way merge them);
legacy chats (empty `messageNodes`) are converted with the deterministic migration before
tree merging (only if needed: if both sides are legacy and one `messages` list is a prefix
of the other / equal, take the longer without converting).

Chat set (3-way): present in only one side → if absent from base it was **added** → keep;
if present in base → it was **deleted** on the other side → drop **unless** the side that
has it modified it relative to base (modification beats deletion → keep). No base → union.
Order: local order, then remote-only chats in remote order.

Chat scalar fields (`preview_name`, `systemPrompt`, `group`, `shareLink`, `shareId`):
3-way per field; both changed differently → local (the merging device is the latest writer).
No base → local unless local value is empty/default and remote is not.

Tree merge (per chat, after normalization):
1. Union nodes by nodeId; within a node union variants by variantId. Variant order: base
   order, then local-only, then remote-only (append only — branch indexes must not shift for
   existing variants).
2. Variant on both sides: responses — if one sequence is a prefix of the other → longer; if
   equal → same; if divergent after common prefix P → keep local variant unchanged and
   **fork**: new variant in the same node, deterministic id
   `nameUUID(remoteVariantId+":fork")`, userMessage copied, responses = P + remote tail,
   childNodeId = remote's child. (3-way: if one side's responses equal base, the other side
   wins outright, including suffix deletions.)
   `childNodeId` both set and different → fold (see 3) the remote child into the local child.
3. Node present on one side whose parent variant V has a different child on the other side
   (both sides continued after V with different next messages) → **fold** the nodes: the
   surviving node (present in base, else smaller nodeId) receives the other node's variants
   (append), messages re-stamped with the surviving nodeId, descendants' `parentNodeId`
   re-pointed. Same for two roots.
4. Deletions 3-way: a node/variant/response present in base, missing on one side and
   unchanged on the other → deleted. If the other side extended it → keep.
5. Validate/repair: exactly one root; every childNodeId resolves and child.parentNodeId ==
   owning node; no variantId in two nodes; dangling refs nulled; re-stamp every message's
   `nodeId`/`variantId` from its position.
6. `currentVariantPath` is **device view state**: take local's path, drop ids that no longer
   exist, repair by walking from the root (path variant if present at that node, else the
   variant with the newest content), and extend to the leaf so new messages become visible.
   If local doesn't have the chat → remote's path (repaired).
7. Recompute `messages` from the repaired path (tree authoritative). Chats that are legacy
   on both sides keep `messages` merged as in normalization.

Groups: union by group_id, 3-way per field and 3-way deletion; deleting a group 3-way also
clears `group` on chats that pointed at it only if the deletion wins.

`data/sync/merge/JsonMerger` — generic 3-way JSON merge for the other synced files:
objects recursively per key; arrays of objects with a verified identity key merged as keyed
sets (3-way add/delete/modify); other arrays and scalars 3-way (only-one-side-changed wins;
both changed → local). No base → objects union, keyed arrays union, scalar conflicts →
**remote** (the account's existing value beats a freshly signed-in device's defaults).
Per-file policy (implementer verifies models): `app_settings.json` (device-local keys
`remoteSync`, `current_user` are ALWAYS kept from local, never adopted, never compared),
`api_keys_*` (array keyed by `id`), `custom_providers_*`, `full_custom_providers_*`,
`github_auth_*`, `google_workspace_auth_*`, `skills_*` (check shape).

## 4. Shared — SyncEngine rewrite

State per tracked file (`sync_state.json`, never uploaded, written atomically, all access
synchronized): `baseServerVersion`, `baseServerSha`, `baseLocalSha`, `dirty` (scheduling
hint only). Base snapshot content: `sync_base/<filename>` (never uploaded) = the local
content that corresponds to `baseServerVersion`. State records `accountUsername`; if it
differs from `current_user` (account switch / sign-in) the state and snapshots are reset.

"Local changed" = `sha(local) != baseLocalSha` (content based — robust against lost dirty
flags, edits made while sync was off, second processes). "Remote changed" =
`remote.updated_at != baseServerVersion && remote.sha != baseServerSha`.

All sync work in one engine is serialized by a `Mutex`. One engine per data dir per
process (registry keyed by canonical internalDir) — fixes Android StreamingService's second
engine.

`syncFile(f)` (used by pull for every tracked file and by debounced upload):
1. Remote missing → if local exists: PUT `base_version=0`; 409 → restart.
2. `sha(local) == remote.sha` → record base (= local) and stop.
3. Remote changed → GET R; base B = snapshot (if missing but `baseServerVersion ==
   R.updated_at`, B = R; else null → 2-way). M = merge(B, L, R) (chat_history →
   ChatHistoryMerger, others → JsonMerger policy). Write M locally **only if the local file
   is still L** (check sha under FileLocks; else restart the file). If local changed → PUT M
   with `base_version=R.updated_at`; on 409 restart (max 5 attempts, then leave for next
   cycle). Record base = M (local content), server version/sha = the PUT result (or R if no
   PUT was needed). Bump `changeTick` when local content changed.
4. Remote unchanged, local changed → PUT L with `base_version=baseServerVersion`; 409 →
   restart (goes to 3).
Fail-safe: any exception leaves files untouched; 401 → `needsReauth` (as today).

Pull on start, on `pullNow`, plus caller-driven periodic pulls. Upload debounce stays 750 ms;
failed uploads are retried by the next pull (engine may also schedule a retry pull after
~30 s backoff).

Sign-in/out and migration (`DataRepository`/`DesktopRepository`/`UserMigration`):
- Migrate local data into an account **only from the local `default` user**. Signing into
  account B while the device holds account A's data → switch-only (B starts from whatever
  the server has); A's data stays local under A. Never rename onto an existing file.
- Migration rewrites `user_name` inside the chat history file.
- Sign-in with a fresh state → first pull does 2-way merges: the device's local chats and
  the account's server chats are unioned and the union is uploaded (S1 requirement).
- Sign-in `saveAppSettings` must not clobber the account's settings: handled by the
  app_settings JSON policy (no base → remote wins for non-device-local keys).
- Sign-out → reset sync state + base snapshots (next sign-in starts 2-way).

## 5. Android / desktop

- Every load-modify-save call site goes through `updateChatHistory` (no snapshot writes from
  UI state: MessageSendingManager attachment rewrite, GroupManager system prompt, etc.).
- Pin streaming replies: pass the user message's variantId as `targetVariantId`.
- StreamingService shares the process engine (via the registry) — verify.
- `changeTick` reload: refresh chat list, groups, current chat (if the current chat vanished →
  fall back gracefully), settings; don't disturb an active stream.
- Foreground periodic pull (e.g. every 30 s while resumed) in addition to resume.
- `cleanupEmptyChats` only removes empty chats that are NOT in the sync base (i.e. local
  junk), so it never deletes an empty chat another device just created.

## 6. Ktor server / web

- Server routes use `updateChatHistory`; SendRoute pins `targetVariantId`.
- Login callback waits (bounded, ~8 s) for the user's first pull before redirecting, so a
  returning user sees their data immediately; `clearReauth()` on bootstrap; no double pull.
- On server startup, rehydrate every user dir with sync enabled (pull loop runs even before
  the user's first request).
- Server pins `current_user` to the session username (device-local; never adopted).
- `GET /api/settings` never returns `remoteSync.authToken`; `PATCH /api/settings` ignores
  `remoteSync` except `syncApiKeys`.
- `module()` takes the sync server URL as a parameter so tests never hit the live :8090.
- Web frontend: poll `GET /api/sync/status` (`lastChangeTick`) every ~10 s while the tab is
  visible + on focus; when it changes reload the chat list / current chat (not while
  streaming).

## 7. Verification

- Python: pytest for CAS/history.
- Kotlin unit: ChatHistoryMerger / JsonMerger exhaustive cases incl. property-based fuzz
  (random op sequences on 2–3 replicas → merge → invariants: no added message lost, tree
  valid, merge(B,L,L)=L, merge(B,B,R)=R modulo view state, convergence after sync).
- Kotlin integration: multi-device simulation (2–3 SyncEngines on temp dirs) against a
  CAS-capable FakeSyncServer, scripted scenarios S1–S9 + randomized interleavings.
- E2E: the real `server.py` on a temp port + temp DB with the real SyncEngine(s).
- Existing suites: `:shared:test`, `:server:test`, web vitest, `:app:assembleDebug`,
  `:desktop:compileKotlin`, `npm --prefix web run build`.

## 8. Deploy order

1. Back up `sync_data.db` and `~/.llm-api-web`. 2. Deploy sync server (CAS is
backward compatible). 3. Rebuild + restart web. 4. Build APK (phone must update: an old
APK still does unconditional legacy PUTs that can clobber merged data).
