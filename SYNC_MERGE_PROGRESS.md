# Sync Merge Redesign — Progress

Read `SYNC_MERGE_PLAN.md` first. Mark `[~]` when starting, `[x]` + one-line note + commit
hash when done. Never redo an `[x]` step.

## T1 — Sync server CAS + history (sync-server repo)
- [x] implementation + tests — sync-server 9bde608: base_version CAS/409, sha-equal no-op, BEGIN IMMEDIATE + max(now,prev+1), WAL, last_used throttle, blob_history (30/file), ?version=N, /sync/history; 47 pytest pass. Review fixes sync-server cce036b: /sync/history + ?version reads use one snapshot (BEGIN), ?version bounded 0..2^63-1 (422 not 500), .gitignore *.db-wal/*.db-shm; 54 pytest pass.

## T2 — Merge engine (ChatHistoryMerger, JsonMerger) + unit/fuzz tests
- [~] implementation + tests

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
