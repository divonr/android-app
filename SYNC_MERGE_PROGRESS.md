# Sync Merge Redesign — Progress

Read `SYNC_MERGE_PLAN.md` first. Mark `[~]` when starting, `[x]` + one-line note + commit
hash when done. Never redo an `[x]` step.

## T1 — Sync server CAS + history (sync-server repo)
- [ ] implementation + tests

## T2 — Merge engine (ChatHistoryMerger, JsonMerger) + unit/fuzz tests
- [ ] implementation + tests

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
