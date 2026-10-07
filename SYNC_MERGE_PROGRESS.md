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
- [ ] implementation + tests

## T4 — SyncEngine rewrite + RemoteStorageClient CAS + SyncState + migration/sign-in fixes + multi-device tests
- [ ] implementation + tests

## T5 — Android + desktop integration
- [ ] implementation + build

## T6 — Ktor server + web frontend
- [ ] implementation + tests

## T7 — Adversarial verification, E2E, fixes
- [ ] 

## T8 — Deploy
- [ ] 

## Notes
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
