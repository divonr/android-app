# Sync Merge Redesign — Progress

Read `SYNC_MERGE_PLAN.md` first. Mark `[~]` when starting, `[x]` + one-line note + commit
hash when done. Never redo an `[x]` step.

## T1 — Sync server CAS + history (sync-server repo)
- [x] implementation + tests — sync-server 9bde608: base_version CAS/409, sha-equal no-op, BEGIN IMMEDIATE + max(now,prev+1), WAL, last_used throttle, blob_history (30/file), ?version=N, /sync/history; 47 pytest pass. Review fixes sync-server cce036b: /sync/history + ?version reads use one snapshot (BEGIN), ?version bounded 0..2^63-1 (422 not 500), .gitignore *.db-wal/*.db-shm; 54 pytest pass.

## T2 — Merge engine (ChatHistoryMerger, JsonMerger) + unit/fuzz tests
- [x] implementation + tests — 4a1f845: `data/sync/merge/` ChatHistoryMerger, LegacyChatConverter, JsonMerger(+Policy), SyncFileMerger, ChatTreeRepair; 49 tests incl. 250-seed 2-replica fuzz + 60-seed 3-device convergence fuzz through the real MessageBranchingManager; `:shared:test` green. Review fixes 3a7ba2f: fork
  keeps remote content under the shared variant id (local → fork, view follows), continuations stay
  after the response list they followed (suffix deletion loses / else fork), redundant leaf twins
  dropped, losing group deletion restores chats' group, unreadable local app_settings kept as is;
  review tests enabled + 2/3-device random sync loops; 71 tests green.

## T3 — Storage hardening (atomic writes, FileLocks, updateChatHistory, user_name, deterministic migration, pinned responses)
- [x] implementation + tests — c3d439f: util AtomicFiles (temp+fsync+rename, ATOMIC_MOVE→REPLACE→renameTo fallbacks) for every synced-file writer (+ SyncEngine pull write); FileLocks registry; ChatHistoryManager.updateChatHistory/modifyChatHistory and every shared load-modify-save (CHM, GroupProjectManager, MBM single-transform ops, cleanupEmptyChats) on it; user_name normalized; corrupt file kept as .corrupt-<ts>; MBM migration = LegacyChatConverter; pinned targetVariantId; importSingleChat fresh id; 13 new tests, `:shared:test` (84) green, server/desktop/app compile. Review fixes 93fc7f0: unreadable chat history → byte-exact .corrupt copy +
  `SyncHolds` hold (no sync hook while held; SyncEngine never uploads a held file, pull merges it
  into remote with an empty base, then releases; UserMigration moves the hold); attachment
  re-upload rewrites attachments by message id in messages + tree (no stale snapshot); nested
  same-file write inside a transform throws ISE; AtomicFiles retries/falls back on Windows
  AccessDenied and LinkageError; review tests enabled + 5 new; `:shared:test` (98) green.

## T4 — SyncEngine rewrite + RemoteStorageClient CAS + SyncState + migration/sign-in fixes + multi-device tests
- [x] implementation + tests — d739878: client CAS put/Conflict/get(version)/history; SyncState v2 (+legacy decode,
  generation-guarded reset) + `sync_base/` snapshots; content-based per-file sync (plan §4 steps 1-4, merge written
  only if local unchanged under FileLocks, ≤5 restarts, 30 s retry pull), Mutex, `SyncEngine.forDir` registry used by
  DataRepository/DesktopRepository; UserMigration default-only/no overwrite/user_name rewrite; sign-in/out reset;
  FakeSyncServer with full CAS + faults; 18 S1–S9 multi-device scenarios + seeded 3-device fuzz (20 seeds in CI, 200
  extra seeds pass); `:shared:test` (135) green, server/desktop/app compile. Review fixes d9a56ec:
  no sync at all with a server lacking `"cas": true` (health checked every pull, PUT response verified,
  `serverLacksCas` flag, testConnection false); remote older than the base (DB restore) → 2-way union; undecodable
  remote / failed merge → both copies untouched + retry; adopt-remote keeps the switch-time copy as base (later local
  changes merge); a local rename while signed in is not an account switch; 3 tests JUnit silently skipped (non-Unit
  `runBlocking` return) now run; review tests enabled + 3 new; `:shared:test` (148) green.

## T5 — Android + desktop integration
- [x] implementation + build — 1d169f3: app/desktop chat-history load-modify-saves → `updateChatHistory` (deletes, titles,
  group prompt/project toggle, attachment re-upload via `updateChatWithNewAttachments`), UI refreshed from disk not snapshots;
  `LocalStorageManager.updateAppSettings` (+ repos) and every VM/manager/ExternalConnections settings write is a transform;
  streamed replies pinned with `ReplyAnchor` (variant + expected tail; MBM resolves a merge fork), StreamingService extras,
  Android/desktop stop-and-save; `SyncReload` changeTick reload; `ForegroundSyncTicker` 30 s pulls; `EmptyChatCleanup`;
  sign-out keeps the sync state; synced `null` auth file on disconnect; desktop rename blocked while syncing (only the
  unused DesktopChatViewModel has a rename); `serverLacksCas` in both sync UIs; `@EncodeDefault` on time-based model
  defaults; `:shared:test` (172) green, `:app:assembleDebug`, `:desktop:compileKotlin`, `:server:compileKotlin` ok.
  Review fixes 55c12e9: anchored replies never fall back to the current path — a question/chat deleted elsewhere
  mid-stream is restored from the anchor's path (`LocalDeletions`: deleted on this device → reply dropped); cleanup
  honours the kept base while signed out and reads it inside the history lock (engine records a merge's base under
  the same lock); auth-file merge: `null` = absence without a base, connection beats disconnect when both changed;
  skills_enabled/skills_sources locked transforms; changeTick reload re-reads when the chat state/settings changed
  during the read (CAS apply) and re-syncs integration tool registration; review tests enabled + 5 new;
  `:shared:test` (183) green.

## T6 — Ktor server + web frontend
- [ ] implementation + tests

## T7 — Adversarial verification, E2E, fixes
- [ ] 

## T8 — Deploy
- [ ] 

## Notes
- T3 storage API (for T4/T5/T6):
  - `AtomicFiles.write(file, text)` / `writeBytes` (throws IOException; holds the file's lock while
    writing). `FileLocks.withLock(file) { }` (inline, reentrant, canonical-path key, process-wide).
    T4's "write M only if local is still L": `FileLocks.withLock(f) { if (sha(read) == shaL) AtomicFiles.write(f, M) }`.
  - `ChatHistoryManager.updateChatHistory(username) { h -> h' }` / `modifyChatHistory(username) { h -> h' to result }`
    (also `DataRepository`/`DesktopRepository.updateChatHistory`, `saveChatHistory(username, h)`).
    Transform runs under the lock: keep it quick, no I/O, no other file locks (deadlock risk).
    Returning an equal history writes nothing and does not call the sync hook; the hook runs after
    the lock is released. A write to the same file from inside a transform throws
    IllegalStateException (it would be overwritten by the outer result); reads are fine.
  - Unreadable chat history: exact bytes kept as `.corrupt-<ts>`, empty history returned, and a
    durable hold `<file>.sync-hold` recorded (`util.SyncHolds`). While held, changes are written
    locally but the sync hook is NOT called. T4 contract: never upload a held file; on pull,
    `SyncFileMerger.mergeFile(name, base = null, local, remote)` (union, nothing counts as
    deleted), write under the file lock, `SyncHolds.release(file)`, upload if merged != remote;
    no remote copy → release + upload. The current SyncEngine already does this (`reconcileHeld`).
  - `loadChatHistory(u)` always returns `user_name = u`, so the one-arg `saveChatHistory(h)` of a
    loaded history is safe; files are always written with `user_name` = the target username.
    The stale-`user_name` rewrite on migration (plan §4) was not needed for routing and is left to T4.
  - `updateChatWithNewAttachments(u, chatId, snapshot)` now only replaces the attachments of
    messages with matching ids (messages + tree); it no longer writes `messages` from the snapshot.
  - `addResponseToCurrentVariant(u, chatId, msg, targetVariantId = null)` on MBM/DataRepository/
    DesktopRepository; unknown target → logs + current path's last variant. Callers still pass
    nothing (T5/T6 must pin).
  - `LocalStorageManager` api_keys/custom_providers mutators now run under the file lock;
    app_settings load-modify-save sites (ExternalConnectionsManager, UI code) are NOT locked yet.
  - `importSingleChat` collision: fresh random chat_id and cleared shareLink/shareId on the copy
    (the share belongs to the original chat).
  - `:server:test` was not run in T3: `UserRegistry`/`SyncAuthClient` default to the live
    `localhost:8090`, so T6 must inject the sync URL before running server tests.
- T1 API details for client work (T4): PUT body `{"content", "base_version"?}`; base_version
  must be >= 0 (negative → 422). sha-equal PUT returns 200 with stored meta even when the base
  is stale/0. `GET /sync/history/{f}` returns `[{filename, updated_at, sha, current:true}, then
  {..., current:false, replaced_at}]` newest first, `[]` for a missing blob (not 404).
  `GET /sync/file/{f}?version=N` → 404 if unknown/pruned, 422 if N < 0 or > 2^63-1. `init_db()` (run at startup) switches
  the DB to WAL and adds `blob_history`; `/auth/google` now also runs in BEGIN IMMEDIATE.
- T2 merge engine (for T3/T4/T5/T6):
  - Entry point for the engine: `SyncFileMerger.mergeFile(filename, base, local, remote, json)`
    (pure, never throws; unparseable local → remote, unparseable remote → local, bad base →
    2-way; unexpected failure → local). Pass `JsonConfig.prettyPrint`.
  - T3: `migrateChatToBranchingStructure` must delegate to `LegacyChatConverter.toBranching(chat)`
    (same grouping rules; ids: node `nameUUID("$chatId:node:$index:$msgId")`, variant
    `nameUUID("$chatId:variant:$index:$msgId")`, blank message id → `nameUUID("$chatId:msg:$index")`,
    index = position in `chat.messages`). Like today it drops messages before the first user
    message and unknown roles.
  - `messages` of branching chats is recomputed from the tree on every merge: messages-only
    writes (MessageSendingManager attachment-id rewrite, addMessageToChat/replaceMessageInChat/
    deleteMessagesFromPoint, web Delete/Regenerate truncation) are undone by a merge — T5/T6 must
    make those edit the tree.
  - Deviations/choices: keyed-list/chat/group order adopts remote order when local order equals
    base (else local order + remote-only); a chat counts as "modified" (beats deletion) on
    tree/name/systemPrompt/share changes, not on `group` or view state; typed JSON files are
    canonicalized through their model with `encodeDefaults=true` before merging (a value reset
    to default is a change, not a missing key) and re-encoded through the model (unknown keys
    dropped, same as the app's own save); app_settings arrays merge as sets; github/google
    workspace auth files merge atomically (whole document as one value, tokens stay consistent);
    missing ids in stored JSON (legacy messages, api keys, providers) are filled deterministically
    before decoding; fork ids `nameUUID(remoteVariantId+":fork")` re-hashed on collision; folded
    node survivor = present in base, else smallest nodeId (symmetric).
  - T2 review fixes (deviations from plan §3 step 2):
    - Fork orientation is reversed vs. the plan: on diverged responses the REMOTE content (already
      on the server, maybe on other devices) stays under the shared variantId and local's
      unpublished content moves to the fork `nameUUID(localVariantId+":fork")`; the local
      `currentVariantPath` is remapped to the fork. The plan's "keep local, fork remote" made the
      device that uploaded first see its variant's content swapped (follow-ups re-parented under
      the other answer, duplicate forks on its next merge). Assumes T4 always calls
      merge(base, local = device, remote = server).
    - Anchoring: a child node follows the last response of the side that created it, so the merged
      response list must equal that side's list (same ids); a child shared by both sides is exempt.
      A suffix deletion therefore loses against a continuation made after it; anything else that
      would move a continuation (remote appended a reply while local continued after the old one,
      remote replaced responses) forks instead.
    - After assembly a LEAF variant whose messages equal a sibling's (ids included, i.e. a fork copy)
      is dropped and the local view remapped to the twin.
    - Group deletion that loses (other side modified the group) also undoes the deleting side's
      clearing of `group` on chats.
    - `SyncFileMerger`: unreadable local app_settings (files with device-local keys) → local text is
      returned unchanged (remote's stripped remoteSync / other device's current_user are never
      adopted). T4 must not upload a merge result that does not parse.
- T5 client integration (for T6/T7):
  - New shared APIs: `DataRepository/DesktopRepository.updateAppSettings { }` (= `LocalStorageManager.updateAppSettings`,
    load+transform+save under the app_settings FileLocks lock — the engine's merge-write lock; equal result writes nothing;
    hook after the lock), `modifyChatHistory`, `syncServerLacksCas`, `addResponseToCurrentVariant(..., expectedTailId)`,
    `addAnchoredResponse(u, chatId, msg, ReplyAnchor)`. T6: server `MutationRoutes` PATCH /api/settings and `UserRegistry`
    still `saveAppSettings` a merged/stale copy — switch them to `updateAppSettings`; SendRoute should pin with
    `ReplyAnchor.forRequest(messagesSent)` + `addAnchoredResponse` for every save of the request.
  - Pinning + fork orientation: `ReplyAnchor` = (variantId, id of the message the next response follows), created from the
    last message sent and advanced after every save. MBM: target variant whose tail (last response, else user message)
    is the expected id → it; else a sibling in the same node whose LAST RESPONSE id is the expected id (the merge fork of
    this device's content, whatever its id/re-hash); target gone → any variant of the chat ending with that response; no
    match → the target (e.g. another device appended after our message without a fork: reply goes after its content).
    User-message ids are never matched on siblings (edits keep the original id).
  - T5 review fixes (T6 must follow):
    - `addAnchoredResponse` never attaches a reply elsewhere: pinned variant gone (no fork match) or chat gone →
      the request's path (`ReplyAnchor.path()` = messages sent + responses saved since; `ReplyAnchor(v, tail, messages)`)
      is restored (missing variants re-created with their ids, as a sibling if the node after the previous variant
      exists; a restored chat gets the first question as title, no system prompt/group) and the reply appended —
      "modification beats deletion" independent of pull timing. Deleted on THIS device (`LocalDeletions`, per chat
      history file, process lifetime: `repo.deleteChat(u, id)` and MBM user-message deletions) → reply dropped (null).
      Plain `addResponseToCurrentVariant(targetVariantId)` keeps the T3 fallback. T6: the web chat delete route must use
      `repo.deleteChat` (it still saves a filtered snapshot) and SendRoute `ReplyAnchor.forRequest(messagesSent)`.
    - `EmptyChatCleanup` no longer looks at "sync enabled": empty chats in the base are kept whenever a base exists
      (sign-out keeps it); the base is read inside the history transform, and the engine records a merge's base inside
      the merge-write lock (`FileSync`), so a just-pulled chat is always in the base the cleanup sees.
    - Auth files: `SyncFileMerger.connectionMerge` — no base: `null` = absence (union); both sides changed and one is
      `null`: the connection wins (matches the per-key settings entry, so file and entry stay consistent).
    - Sync reload (both VMs): re-reads when `currentChat`/`chatHistory`/`groups`/`currentGroup`/settings changed during
      the disk read, applies with `compareAndSet`; then `AuthManager.refreshIntegrationToolsAfterSync()` registers or
      unregisters GitHub/Workspace tools to match the auth files.
  - Sign-out no longer resets the sync state (task decision, plan §4 updated); `prepareForSignIn` resets on an account
    switch. MultiDeviceSyncTest S8e updated, S8g (no resurrection after re-sign-in) / S8h (switch after sign-out) added.
  - `cleanupEmptyChats` (shared `EmptyChatCleanup`; review fix: no base → every empty chat, whether sync is on or off) — as first built: sync off → every empty chat; sync on → empty chats absent from
    `baseContent(chat_history)` plus empty chats created by THIS repository instance (deviation: otherwise every
    uploaded "new chat" left untyped would stay forever; its removal syncs as a deletion that loses against another
    device's writes). Unreadable base → only own chats. Residual: own empty chats uploaded before a process restart stay.
  - Disconnect: `github_auth_*` / `google_workspace_auth_*` hold JSON `null` (`ExternalConnectionsManager.DISCONNECTED`)
    instead of being deleted; loaders return null; SyncFileMerger decodes these files with nullable serializers (atomic
    3-way: disconnect vs untouched → null; reconnect after a disconnect → the new connection; no base → remote).
    `canonicalTyped` no longer drops a legitimately-null decode. Old clients read "null" as not connected.
  - Found by the T5 tests: kotlinx omits a property equal to its default, and defaults like `System.currentTimeMillis()`
    (GitHubConnectionInfo/GoogleWorkspaceConnectionInfo.lastUsed, auth createdAt/connectedAt, custom provider createdAt)
    equal the value set in the same millisecond → the field vanished from the file and every decode invented a new
    timestamp, so merges saw phantom modifications (a removed GitHub entry came back). Fixed with `@EncodeDefault`.
    Files already written without the field still decode to "now" until rewritten.
  - changeTick reload (`SyncReload`, both VMs): settings, chats, groups, current chat (vanished → kept while a request for
    it is loading/streaming; with an unsent draft → no current chat so the draft starts a new chat; else the newest chat),
    current group (vanished on the group screen → chat history), system prompt (not while its dialog is open), selected
    provider/model if the synced settings name an available one, excludedToolIds; stream state/draft untouched.
    Providers/API-key lists are not re-filtered on a tick (that path writes settings → could ping-pong between devices).
  - Foreground pulls: `ForegroundSyncTicker` (30 s) started on Activity ON_RESUME / desktop window focus, stopped on
    ON_PAUSE / focus lost, plus the existing immediate pull.
  - Desktop stop-and-save now marks the coordinator request stopped (its later output is no longer saved/emitted — it used
    to duplicate the partial reply); the HTTP stream itself still runs to its end in the background (pre-existing).
  - SyncEngineTest "debounced upload ... records the new base" waited only for the server's PUT, not the engine's record
    (flaky under load); it now polls the state.
- T4 sync engine (for T5/T6/T7):
  - API: `SyncEngine.forDir(internalDir, json[, uploadDebounceMs, retryDelayMs]) { settings }` (one engine per dir per
    process; first caller's params win; DataRepository/DesktopRepository use it, so Android's StreamingService repo
    shares the UI's engine). `pullNow()`/`start()` fire-and-forget (coalesced: at most one queued pull);
    `suspend pull()` waits (use `withTimeout(8_000) { repo.syncEngine.pull() }` for T6's login wait);
    `uploadNow(f)`, `flushPendingUploads()`, `runExclusive { }` (sign-in), `resetSyncState(account = "",
    adoptRemoteGlobals = false)`, `prepareForSignIn(username, migrationResult)`, `baseContent(filename)` (validated
    base snapshot or null — T5's `cleanupEmptyChats` "not in the sync base" test), `syncStateAccount()`,
    `close()`/`closeAndJoin()`. `RemoteStorageClient.put(f, content, baseVersion: Long? = null)` throws
    `RemoteSyncException.Conflict(current: BlobMeta?)`; `get(f, version: Long? = null)`; `history(f)`; one shared
    OkHttpClient. `SyncFileMerger.sameContent(f, a, b, json)` / `isValid(f, text, json)`; top-level `sha256Hex`.
  - Deviations / choices beyond plan §4:
    - Step 3 uploads the merge only if it differs from R in CONTENT (`sameContent`: ignores chat view state
      `currentVariantPath`/derived `messages`, app_settings device-local keys and formatting). A byte/sha test would
      ping-pong forever between devices looking at different branches (every merge keeps the local path).
    - Before PUTting a merge the base is recorded as R (+ R as snapshot), so a failed PUT is retried by step 4.
    - Snapshot missing: besides the plan's "baseServerVersion == R.updated_at → B = R", a local copy whose sha equals
      `baseLocalSha` is used as B (it IS the base) — keeps 3-way deletions working when `sync_base/` is lost.
    - Remote sha == base sha with another version → just follow the version (no merge).
    - app_settings: change detection, base snapshot and upload all use the stripped form (remoteSync removed;
      `current_user` stays in the upload for old clients); the snapshot never contains the token.
    - Account switch from another account (MigrationResult.Switched, or a state recorded for another account)
      resets the state AND marks the account-global files (app_settings, skills_*) "adopt remote": their first sync
      is merge(base = local, local, remote) = the new account's copy (+ device-local keys) — no settings leak.
      Re-sign-in to the account the state belongs to (needsReauth) keeps the bases.
    - Locally missing file with a remote copy → adopted (file deletions are still not propagated: github/workspace
      disconnect resurrects the auth file, L-6 open). An unreadable local file replaced by a merge is kept as
      `<name>.corrupt-<ts>`; unparseable local content is never uploaded.
    - UserMigration from `default` skips (keeps under `default`) each file whose target exists instead of the old
      all-or-nothing switch when `chat_history_<new>` existed; `MigrationResult.Switched/Migrated` gained fields.
  - Open for T5/T6: app_settings load-modify-save sites are still unlocked and ViewModels write stale snapshots
    (a 3-way merge reads a stale snapshot as reverting the remote changes); `cleanupEmptyChats` must use
    `baseContent`; UserRegistry's `startSync()+pullNow()` is now harmless (coalesced) but redundant; `:server:test`
    still not run (live sync URL default).
  - T4 review fixes (deviations / choices):
    - CAS required: a server without `"cas": true` (pre-T1) is not synced with at all (no merge, no upload) — the
      engine can't make a stale upload safe there. `/sync/health` is checked on every pull (confirmation cached per
      URL for debounced uploads); a conditional PUT whose response lacks `cas` throws
      `RemoteSyncException.CasUnsupported` (that write is never recorded as a base). New: `SyncEngine.serverLacksCas`
      (T5/T6 may show it), `RemoteStorageClient.casSupported()`. testConnection() is false for such a server.
      Consequence for T8: deploy the sync server first (as planned) or new clients don't sync until it is deployed.
    - Remote version < base version (and other sha) = server lost versions (backup restore) → base dropped, 2-way
      union (also on the 409 path). Residual: if another device already re-uploaded after the restore, the version
      is newer again and can't be told apart; data that only reached the server (not that device) before the
      restore can then still be merged as a remote deletion.
    - `SyncFileMerger.tryMergeFile` (null when remote doesn't parse / merge throws): the engine then leaves both
      copies untouched and schedules a retry; a missing local file is not replaced by an unparseable remote either.
    - Adopt-remote marks store the switch-time local sha (`SyncStateData.adoptRemoteBase`, "" = absent) and that
      copy as the base snapshot: unchanged → the account's copy wins; changed after the switch → 3-way against
      the switch-time copy; absent at the switch → 2-way. Old marks without a sha behave as before.
    - The state account follows the signed-in account (set by `prepareForSignIn`), not `current_user`: a differing
      `current_user` (desktop rename while signed in) keeps the state; `Switched` into the account the state
      already belongs to keeps the bases. T5: the desktop rename still makes the app sync
      `chat_history_<newname>.json` into the same server account — block renaming while sync is on.
  - Tests: `FakeSyncServer` (CAS, per-token accounts, `/auth/google` with id_token `google:<user>`, fault injection,
    `sun.net.httpserver.nodelay`), `SimDevice` (real DataRepository on a temp dir, test-configured engine registered
    first), `SimAssert.quiesce/assertConverged`. Deeper fuzz: `SYNC_FUZZ_SEEDS=500 SYNC_FUZZ_START=n ./gradlew
    :shared:test --tests '*MultiDeviceSyncFuzzTest'` (seeds 30000–30199 pass).
