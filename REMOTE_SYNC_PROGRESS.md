# Remote Sync — Implementation Progress

Persistent checkpoint file. Survives across sessions so work can resume cleanly
if interrupted (e.g. quota renewal mid-task). **Every subagent reads this FIRST,
marks its step `[~]` on start and `[x]` on finish with a one-line note + commit
hash. Never redo an `[x]` step.**

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done

Plan file: `/home/divonr/.claude/plans/buzzing-wishing-quill.md`
Target branch for client code: `shared-module-migration`

---

## Task 1 — Sync server (`~/ApI/sync-server/`)  [outside android repo]
- [x] venv + deps (fastapi, uvicorn, cryptography) — installed into /home/divonr/ApI/sync-server/venv
- [x] `server.py`: FastAPI, SQLite blob store, bearer auth, GET manifest / GET / PUT — written to /home/divonr/ApI/sync-server/server.py
- [x] at-rest encryption for `api_keys_*` blobs — via Fernet, _enc column, transparent decrypt on GET
- [x] `sync-server.service` systemd unit (port 8090) — unit file at /home/divonr/ApI/sync-server/sync-server.service; sudo unavailable so manual install required
- [x] curl-verified: 401 without token, PUT→GET round-trip, manifest, updated_at advances, api_keys encrypted at rest — all PASS
- [x] commit — N/A: sync-server lives outside any git repo and holds .env secrets; left as files on disk (not versioned)
- commit: (none — not under version control)

## Task 2 — Cloudflare route + systemd  [USER step — DONE]
- [x] systemd service active (pid managed; boot-persistent). NOTE: a leftover setup process was squatting port 8090 causing the unit to fail; orchestrator killed it, service now active.
- [x] Cloudflare route added: sync.api-divonr.xyz → http://localhost:8090
- [x] verified off-box: https://sync.api-divonr.xyz/sync/health → {"status":"ok"}; phone synced 3.2MB chat history + app_settings to server

## Task 3 — Shared core (`shared` module)
- [x] branch switch to shared-module-migration resolved — personal's max_tokens change committed+pushed on both branches (personal e7acede, shared d3eeb97); now on shared-module-migration
- [x] `RemoteStorageClient.kt` — OkHttp wrapper with manifest/get/put/health + bearer auth + URL-encoding; typed RemoteSyncException; BlobMeta/RemoteBlob data models
- [x] `SyncEngine.kt` — CoroutineScope, pull (manifest-diff, dirty-skip, merge app_settings), debounced upload (750ms), changeTick StateFlow, onFileWritten callback
- [x] `SyncState.kt` — per-file dirty/baseServerVersion tracking, persisted as sync_state.json (never synced)
- [x] `onFileWritten` hooks in ChatHistoryManager (saveChatHistory), LocalStorageManager (saveApiKeys/saveAppSettings/saveCustomProviders/saveFullCustomProviders), ExternalConnectionsManager (saveGitHub/saveGoogleWorkspace), SkillsStorageManager (saveEnabledState/saveSourceUrls)
- [x] `DataRepository.kt` wiring — syncHook indirection pattern; exposes startSync/pullNow/syncChangeTick/testSyncConnection; disabled=no-op via syncHookImpl={}
- [x] `RemoteSyncSettings` in ApiKey.kt — enabled=false/serverBaseUrl/authToken/syncApiKeys=false default; added to AppSettings; backward-compat via coerceInputValues
- [x] build `:shared`, commit — `./gradlew :shared:compileKotlin` BUILD SUCCESSFUL (needed JDK17 toolchain: installed to ~/.jdks/jdk-17.0.19+10, registered in ~/.gradle/gradle.properties)
- commit: 1b51ef8

## Task 4 — App integration (Android)
- [x] startup/resume hooks — startSync() at end of loadInitialData(); pullNow() via LifecycleEventObserver ON_RESUME in LLMChatApp; syncChangeTick observed via observeSyncChangeTick() coroutine
- [x] Settings UI — RemoteSyncSection.kt added; enable/disable toggle, server URL, auth token (show/hide), syncApiKeys toggle with warning, Sync Now button, Test Connection button with live status; wired into UserSettingsScreen section 6 via viewModel update methods
- [x] build `:app`, commit — `./gradlew :app:assembleDebug` BUILD SUCCESSFUL; fresh APK at app/build/outputs/apk/debug/app-debug.apk (served by apk-server)
- commit: 2399c91

## Task 5 — Desktop integration
- [x] startup/resume hooks — startSync() at end of loadInitialData(); observeSyncChangeTick() wired; window focus listener calls onWindowFocused()→pullNow() via WindowAdapter in DesktopMain
- [x] Settings UI — RemoteSyncSection.kt added to desktop/ui/components/; wired into UserSettingsScreen section 6; R.kt updated with 11 new string constants; update methods in desktop ChatViewModel mirror Android names
- [x] build `:desktop`, commit — `./gradlew :desktop:compileKotlin` BUILD SUCCESSFUL
- commit: 763fccb (desktop) + 47c2c32 (default URL → sync.api-divonr.xyz)

## Task 6 — End-to-end  [DONE]
- [x] phone→server verified live by user (3.2MB history + app_settings uploaded; UI reported success)
- [x] server→device-B pull path verified via tunnel with real token: manifest lists both files; chat_history pulls 24 chats/3 groups/3.2MB sha-ok; app_settings pulls with remoteSync correctly STRIPPED; 401 without token
- [x] final checkpoint — feature complete; remaining real-GUI two-device test is user-driven (run desktop app / install on 2nd phone)

NOTE on earlier setup-time log errors (manifest/PUT 404, DNS fail, 401): all transient — caused by an in-progress/typo'd server URL (`api-divonr.xyz` w/o `sync.` → 404; `syapi-divonr.xyz` → DNS fail) and token not yet entered (401). Resolved once correct URL+token set; default URL now hardcoded to sync.api-divonr.xyz so fresh installs avoid this.
