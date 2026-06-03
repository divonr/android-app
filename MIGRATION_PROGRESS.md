# Shared Module Migration — Progress Tracker

## Status Legend
- ✅ Done (committed)
- 🔄 In progress
- ⏳ Pending

## Phases

### Phase 0 ✅ Branch + local.properties
### Phase 1 ✅ Empty :shared module (commit a0f7c96)
### Phase 2 (models) — PARTIAL

---

## Step A — Stabilize in-flight Phase 2 state 🔄
**Goal:** untrack build dir, delete desktop duplicates, reduce DesktopUiModels.kt, build green, commit

- [ ] A1: `git rm -r --cached shared/build` + `git add shared/.gitignore`
- [ ] A2: Delete `shared/src/.../Placeholder.kt`
- [ ] A3: Delete 17 model duplicates from `desktop/src/.../data/model/` + `tools/Tool.kt`
- [ ] A4: Reduce `DesktopUiModels.kt` — remove classes now in SharedModels.kt
- [ ] A5: Build :shared, :app:compileDebugKotlin, :desktop:compileKotlin — ALL GREEN
- [ ] A6: Commit "Phase 2: move data models to :shared; remove desktop duplicates; stop tracking shared/build"

## Step B — Platform abstractions ⏳
**Goal:** Logger + PlatformStorage interfaces in :shared; Android impl in :app; desktop impl in :desktop

- [ ] B1: Add Logger interface + AppLogger bridge in :shared
- [ ] B2: Add Android Logger impl in :app
- [ ] B3: Add Desktop Logger impl in :desktop
- [ ] B4: Add PlatformStorage interface in :shared
- [ ] B5: Add Android PlatformStorage impl in :app
- [ ] B6: Add Desktop PlatformStorage impl in :desktop
- [ ] B7: Build all three green
- [ ] B8: Commit

## Step C — Network layer ⏳
**Goal:** Move providers + streaming + LLMApiService + Google/GitHub API services to :shared

- [ ] C1: Move network files (refactor Context→PlatformStorage, Log→Logger, Base64)
- [ ] C2: Delete duplicates from both apps
- [ ] C3: Build all three green
- [ ] C4: Commit

## Step D — Repository + util + tools ⏳
**Goal:** Move DataRepository, util, tools to :shared

- [ ] D1: Move repository (build+commit)
- [ ] D2: Move util (build+commit)
- [ ] D3: Move tools (build+commit)

## Step E — Cleanup ⏳
**Goal:** Delete obsolete android shims; verify no duplicates remain

- [ ] E1: Delete desktop android/* shims
- [ ] E2: Verify no duplicates in either app
- [ ] E3: Build + smoke test :desktop:run
- [ ] E4: Commit

## Step F — Final verification ✅ target
- [ ] F1: :app:assembleDebug GREEN
- [ ] F2: :desktop package/run GREEN

---

## Notes
- JVM target: :shared uses jvmToolchain(17), :app targets JVM 11 — verify on first :app build
- Base64: use kotlin.io.encoding.Base64, NEVER java.util.Base64 (needs API 26)
- android.util.Log → AppLogger/Logger interface
- android.content.Context → PlatformStorage interface
- android.net.Uri → String path or abstraction
