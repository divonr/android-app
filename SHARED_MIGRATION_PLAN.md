# `:shared` Module Migration — Continuation Plan

You are continuing a structural refactor that a previous agent started and left **mid-way through Phase 2, with the build currently broken on the desktop side**. Read this whole file first; it is self-contained.

## Goal
This repo contains an Android app (`:app`) and a Desktop port (`:desktop`) that **duplicate ~53 platform-agnostic logic files** (data models, network/providers, streaming, repository, tools, util). The goal is to move that shared logic into ONE new `:shared` Gradle module that both apps depend on, so each file exists once. **This is a pure structural refactor — no behavior may change in either app.** The UI layer stays per-platform and is out of scope.

## Environment (verified — rely on these)
- **Work entirely in this worktree:** `C:\Users\DELL\Documents\Cursor\chatGPT api\android app desktop`. It contains top-level `app/`, `desktop/`, and the new `shared/`.
- **You are on branch `shared-module-migration`** (forked from `codex/desktop-app`; the known-good pre-refactor commit is `300d6a2` — keep it as a fallback).
- The OTHER checkout `C:\Users\DELL\Documents\Cursor\chatGPT api\android app` (branch `personal`) is unrelated — **do not touch it**.
- **Windows. Build with `.\gradlew.bat ... --console=plain`.** Key commands:
  - `.\gradlew.bat :shared:compileKotlin`
  - `.\gradlew.bat :app:compileDebugKotlin` (fast Android check) / `.\gradlew.bat :app:assembleDebug` (full)
  - `.\gradlew.bat :desktop:compileKotlin` / `.\gradlew.bat :desktop:run`
- **Build-lock gotcha:** if a build fails with `FileSystemException ... problems-report.html ... used by another process`, it's a stale lock from a running app/daemon — run `.\gradlew.bat --stop`, kill leftover `java` processes if needed, and retry. It is NOT a code error.
- **Android SDK** is at `C:\Users\DELL\AppData\Local\Android\Sdk`; `local.properties` already exists in the worktree root pointing to it (gitignored — leave it).
- `:app` settings: compileSdk 35, **minSdk 24**, targetSdk 35, **jvmTarget = "11"**.

## Architecture decisions (already made — follow them)
- `:shared` is a **plain `kotlin("jvm")` library** (NOT Kotlin Multiplatform). Both apps are JVM and can consume it. No `expect`/`actual`.
- **`:shared` must NOT reference any `android.*`, `androidx.*`, or Compose API.** That is what lets `:app` consume it. (Current `shared/src` is verified clean — keep it that way.)
- Keep **package names identical** (`com.example.ApI.data.*`, `.tools.*`, `.util.*`) when moving files, so imports in both apps don't change.
- `:shared` currently uses `jvmToolchain(17)`. `:app` targets JVM 11. **Verify `:app` can consume the 17-bytecode `:shared` jar during the first build; if it fails, lower `:shared` to JVM 11.**
- Platform-specific pieces are injected via **interfaces** defined in `:shared`, implemented in `:app` (Android) and `:desktop`:
  - `android.util.Log` (15 files) → reuse/extend `com.example.ApI.util.AppLogger` or a simple `Logger` interface in `:shared`; bridge to Logcat in `:app`, console in `:desktop`.
  - `android.util.Base64` (2 files) → use **`kotlin.io.encoding.Base64`** (safe on minSdk 24). **Never `java.util.Base64` — it needs API 26.**
  - `android.content.Context` (~16 files) + `android.os.Environment` (1) → a `PlatformStorage` interface (app files dir + bundled-resource reader, e.g. providers.json/models.json). Android impl wraps `Context`/assets; desktop impl is file/classpath-based (reuse the existing `DesktopContext`/`DesktopRepository`).
  - `android.net.Uri` (1) → replace with a `String` path or tiny abstraction.
- **Stays per-platform — do NOT move to `:shared`:** everything under `ui/` (screens, components, managers, theme, `ChatViewModel`); Android `StreamingService` and real `R`; desktop `DesktopMain`, `DesktopChatViewModel`, `DesktopContext`, `DesktopRepository`, `DesktopStreamingCoordinator`, `DesktopAsyncImage`, `R.kt` shim, `PasswordEncryption`. The desktop `android/*` shim files become obsolete once `:shared` no longer needs them — delete them in cleanup.

## TWO MANDATORY RULES
1. **Build after every significant or risky step** — run the relevant compiles (`:shared`, `:app`, `:desktop`) and confirm green BEFORE continuing. Never let breakage accumulate.
2. **Commit a separate checkpoint after every significant step that builds green.** Short clean messages, no self-crediting. Do NOT push.

---

## What is already DONE
- **Phase 0 ✅** branch + `local.properties` created.
- **Phase 1 ✅ committed (`a0f7c96`)** — empty `:shared` module created, registered in `settings.gradle.kts`, and both `:app` and `:desktop` declare `implementation(project(":shared"))`. `shared/build.gradle.kts` already has deps: kotlinx-serialization, coroutines-core, okhttp(+logging), and the Google API client libs.
  - ⚠️ **Defect to fix:** this commit accidentally tracked the `shared/build/` artifacts directory. A `shared/.gitignore` (`/build`) was added but is still untracked, so the build dir remains tracked.
- **Phase 2 (models) — PARTIAL, UNCOMMITTED, build currently BROKEN:**
  - 17 model files were **deleted from `:app`** and **moved into `shared/src/.../data/model/`** (clean, no android refs). A new `SharedModels.kt` consolidates UI-leak-free models (`WebSearchSupport`, `TextDirectionMode`, `SearchResult`, `SearchMatchType`, `ExecutingToolInfo`, etc.). `tools/Tool.kt` was also moved to `:shared`.
  - ❌ **The matching duplicate copies were NOT removed from `:desktop`** — `desktop/src/.../data/model/*` (17 files) and `desktop/src/.../tools/Tool.kt` still exist. With the same classes now also coming from `:shared`, `:desktop` has duplicate-class conflicts and **does not compile**.
  - `desktop/.../data/model/DesktopUiModels.kt` still exists; its pure classes now live in `SharedModels.kt`, so its remaining content must be reduced to only the Compose/`DpOffset`-using bits (or deleted if nothing remains).
  - There is a leftover `shared/.../Placeholder.kt` from the empty-module phase — delete once real files exist.

---

## Remaining work (ordered; build + commit at each step)

**Step A — Stabilize and clean up the in-flight state (do this first):**
1. Untrack the build dir: `git rm -r --cached shared/build`, and `git add shared/.gitignore`.
2. Finish Phase 2: delete the duplicate model files and `tools/Tool.kt` from `desktop/src/...`; reduce/delete `DesktopUiModels.kt` to only Compose-dependent classes (the pure ones are in `SharedModels.kt`).
3. Build `:shared`, `:app:compileDebugKotlin`, `:desktop:compileKotlin` — all green. **Verify the JVM-target compatibility note above on this first `:app` build.**
4. Commit: e.g. "Phase 2: move data models to :shared; remove desktop duplicates; stop tracking shared/build".

**Step B — Platform abstractions in `:shared`:** add `Logger` + `PlatformStorage` interfaces; add Android impl in `:app` and desktop impl in `:desktop`. Build all three. Commit.

**Step C — Move network layer** (providers + streaming + `LLMApiService` + Google/GitHub API services), refactoring `Context`→`PlatformStorage`, `Log`→`Logger`, `Base64`→`kotlin.io.encoding.Base64`. Delete duplicates from BOTH apps. Build all three. Commit. (Split into sub-steps with their own build+commit if large.)

**Step D — Move repository + util + tools**, same refactors. Largest/riskiest — split into repository, then util, then tools, each with build+commit. Delete duplicates from both apps.

**Step E — Cleanup:** delete obsolete desktop `android/*` shims and any dead duplicate logic now sourced from `:shared`; confirm neither `:app` nor `:desktop` retains its own copy of any moved file. Build all three; run `.\gradlew.bat :desktop:run` smoke test. Commit.

**Step F — Final verification:** `.\gradlew.bat :app:assembleDebug` and `.\gradlew.bat :desktop:packageUberJarForCurrentOS` (or `:desktop:run`). Confirm both build from the single shared source.

## Definition of done (audit with build evidence)
- `:shared` is a kotlin-jvm module with zero `android.*`/`androidx.*`/Compose refs, containing the single copy of every platform-agnostic logic file.
- Neither `:app` nor `:desktop` contains a duplicate of any moved file.
- `:app:assembleDebug` AND `:desktop` (run/package) are BOTH green against the shared source.
- No behavior changed. A clean chain of checkpoint commits exists on `shared-module-migration`. Nothing pushed.
- If the Android build cannot be produced on this machine at all, STOP and report rather than pressing on (rule #1 can't otherwise be honored).
