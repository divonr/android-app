# Web UI — Implementation Progress

Persistent checkpoint file. Survives across sessions so work can resume cleanly
if interrupted. **Every subagent reads this FIRST, marks its step `[~]` on start
and `[x]` on finish with a one-line note + commit hash. Never redo an `[x]` step.**

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done

Plan file: `/home/divonr/.claude/plans/cosmic-coalescing-cray.md`
Branch: `web-ui`

---

## P0 — Scaffold + API contract
- [x] `:server` Gradle module (Ktor 2.3.12 + Netty, depends `:shared`), `ServerPlatformStorage`, `ServerMain`, `AppModule`, `configureRouting` with `/health`; `server/API_CONTRACT.md` (full REST+SSE spec for all phases); Ktor testApplication health test + DataRepository smoke tests — all passing. Note: `ktor-server-sse` requires Ktor 3.x; SSE for P3 will use `respondTextWriter` on Ktor 2.x.
- commit: 4fe993c

## P1 — Auth + session
- [x] `POST /login` (password from env `WEB_UI_PASSWORD`), Ktor Sessions signed-cookie (`llm_web_session`), `authenticate("session")` guard on all `/api/**` routes via `Route.apiRoutes()` extension, `POST /logout`. `AuthConfig` data class lets tests inject password + secret without env vars. Tests: 401 without session, login round-trip (200 + Set-Cookie), logout invalidates, /health still public, 401 JSON body — all 10 tests pass.
- commit: a8abeaf

## P2 — Read APIs
- [x] GET /api/providers, /api/providers/models, /api/settings, /api/chats, /api/chats/{chatId}, /api/groups, /api/keys (masked), /api/search. `currentUsername()` helper reads `AppSettings.current_user`. API keys masked (last 4 chars). `SearchResultDto` server DTO because `:shared` `SearchResult` is not @Serializable. 27 tests total (17 new), all passing.
- commit: 171ecdd

## P3 — Streaming send (core)
- [x] POST /api/chat/send SSE endpoint using `respondTextWriter` + `Channel<String>` bridge. `ChatEngine` interface seam (real: `RepositoryChatEngine` delegates to `DataRepository.sendMessage`; test: `FakeChatEngine` scripts callback directly). Tool execution via `ToolRegistry.executeTool`, tool messages persisted via `addResponseToCurrentVariant`. Title generation mirrors `TitleGenerationManager` (1st or 3rd assistant message). User message persisted before streaming via `addUserMessageAsNewNode`. 8 new tests (35 total), all passing.
- commit: b06f4a9

## P4 — Mutation APIs
- [x] Chat CRUD (create/rename/system-prompt/delete/export/import), messages (add/delete-from-point), branching (createBranch/switchVariant/branch-info/deleteMessageFromBranch), groups full CRUD + project-status + chat membership + attachments, API keys (add/patch/delete/toggle/reorder), settings PATCH (merge semantics), custom providers CRUD, full custom providers CRUD, skills (list/get-content/create/import/enable-disable/delete), POST /api/providers/refresh. Settings PUT semantics: merge (GET-then-PATCH pattern, only supplied fields overwritten). 46 new tests (81 total), all passing.
- commit: 2a05c8b

## P5 — Files + integrations OAuth
- [x] POST /api/files/upload (multipart, 50 MB limit) → Attachment; DELETE /api/files. GET/DELETE /api/integrations/github|google; POST /api/integrations/github|google/start (CSRF state in session); GET /oauth/github|google/callback (public); PATCH /api/integrations/google/services. OAuthTokenExchanger seam (RealOAuthTokenExchanger / FakeOAuthTokenExchanger in tests). UserSession extended with githubOAuthState/googleOAuthState fields. GitHub credentials from env (GITHUB_OAUTH_CLIENT_ID / GITHUB_OAUTH_CLIENT_SECRET) with desktop app fallback. Google credentials from env only (GOOGLE_OAUTH_CLIENT_ID / GOOGLE_OAUTH_CLIENT_SECRET). PUBLIC_BASE_URL env for redirect URIs. 17 new P5 tests (98 total), all passing. commit: 2fd3040

## P6 — Frontend scaffold
- [x] Vite 5 + React 18 + TS 5 in `web/`. Typed API client (`client.ts`) covering all endpoints, SSE stream client (`stream.ts`) with fetch-based frame parser handling chunk boundaries, typed models (`types.ts`) mirroring all shared Kotlin DTOs. App shell: BrowserRouter v6, AuthGuard checking `/api/session`, AppLayout (sidebar + responsive mobile hamburger), Login page. Stores: Zustand `authStore` + `chatStore`. CSS Modules dark theme (palette from Color.kt). `<Markdown>` with remark-gfm/math + rehype-katex + rehype-highlight + copy button. PWA manifest. 28 Vitest tests (stream parser, request helper, login flow, Markdown smoke) all green. `tsc --noEmit` + `vite build` → `web/dist` produced clean. Node 18 forced Vite 5 (not 6/7). Styling: CSS Modules (no Tailwind — avoids Node-18 JIT compat issues).

## P7 — Frontend screens (parity)
- [x] All screens implemented with desktop parity: ChatPage (streaming SSE, live partial text, collapsible thinking, tool_call/tool_result inline, model/provider picker, web-search toggle, thinking-budget + temperature, stop/abort, message edit→branch, delete-from-point, copy, regenerate, branch navigator ‹ n/m ›), ChatHistoryPage (list/groups/search/create/rename/delete), KeysPage (API keys + custom providers + full custom providers CRUD with toggle/reorder), SettingsPage (all AppSettings fields incl. child-lock + title-generation + starred-models), GroupPage (project mode + shared system prompt + attachments), SkillsPage (skill editor + enable/disable + create/delete), IntegrationsPage (GitHub/Google connect/disconnect + google services toggle). CSS Modules dark theme. AuthGuard on all routes. RTL/LTR dir="auto" on message bubbles. 74/74 Vitest tests green. build clean. Root cause of 6 test failures: (1-3) vi.restoreAllMocks() in SmokeTests beforeEach stripped mock implementations — fixed by switching to vi.clearAllMocks(); (4-6) getByText(/api keys|child lock|title generation/i) matched multiple DOM nodes — fixed by switching to getByRole('heading', ...) queries. commit: e5867a7

## P8 — E2E + deploy
- [x] Static SPA serving from Ktor (WEB_STATIC_DIR env, tailcard GET fallback for SPA routes, /api paths never swallowed). KTOR_PORT + LLM_WEB_DATA_DIR env overrides added. 4 new server tests (102 total, all green). Playwright @1.48.2 + Chromium installed; 9 E2E tests authored and passing (auth, chat CRUD, settings persistence, API keys UI, seeded chat navigation, SPA fallback routing). `npm run test:e2e` wired; Vitest `test` script unchanged (e2e/ excluded). `./gradlew :server:installDist` produces runnable artifact at `server/build/install/server/bin/server`. Smoke test: `curl localhost:8091/health` → `{"status":"ok"}`, `curl localhost:8091/` → SPA index.html. Deployment artifacts: `server/deploy/llm-web.service`, `llm-web.env.example`, `build-and-run.sh`, `DEPLOY.md`. Root .gitignore updated for secrets + Playwright output. commit: 38e5e34

## P9 — Remote sync bootstrap

- [x] Added `SyncConfig` data class + `resolveSyncConfig()` (reads `SYNC_ENABLED`, `SYNC_SERVER_URL`, `SYNC_TOKEN`, `SYNC_USER`, `SYNC_PULL_INTERVAL_SECONDS` env vars). `SyncConfig.startEngine=false` gate lets tests assert config seeding without opening sockets. On startup (when enabled + token set): seeds `RemoteSyncSettings` + optional `current_user` into `AppSettings`, calls `startSync()` + initial `pullNow()`, launches a periodic pull loop (default 20 s) tied to `ApplicationStopping` lifecycle. Added `POST /api/sync/pull` + `GET /api/sync/status` endpoints (auth-gated; token never returned). 8 new tests (110 total, all green). `installDist` still produces a runnable artifact. Docs: `llm-web.env.example` + `DEPLOY.md` updated with sync section.
- commit: bbe1f13

## UI Refactor — R3 (Chat screen body + input)

- [x] **R3 — Message bubbles, ThoughtsBubble, ToolCallBlock, BranchNavigator, streaming render, ChatInputArea, scroll buttons, Markdown restyle, i18n, tests**
  - **`web/src/components/chat/MessageBubble.tsx`** (new): User=#6C7CE7 (topLeftRadius 6, align flex-start=RIGHT in RTL), assistant=#2A2B3A (topRightRadius 6, align flex-end=LEFT in RTL), system=#3A3B4A. Inline ThoughtsBubble + ToolCallBlock for assistant. Model avatar 32px. ContextMenu (portaled, hover-on ⋯). BranchNavigator below user messages.
  - **`web/src/components/chat/ThoughtsBubble.tsx`** (new): Live elapsed timer (setInterval 100ms) while streaming. primary-08 bg, primary-20 border. Hebrew "מחשבות... (x.x שניות)". Expand/collapse when done.
  - **`web/src/components/chat/ToolCallBlock.tsx`** (new): MdExtension icon in 40px circle. Tool name, parameters JSON, result with green/red bg per success/failure. Status: מבצע.../הושלם/נכשל.
  - **`web/src/components/chat/BranchNavigator.tsx`** (new): `‹ n/m ›` with prev/next chevrons; hidden when totalVariants ≤ 1.
  - **`web/src/components/chat/ChatInputArea.tsx`** (new): Pill layout (MdAdd attach | textarea | action buttons). Edit banner with cancel X. File previews (single=list, multiple=4-col grid). isExpanded ≥3 estimated lines → vertical stack. WebSearchToggleBtn (primary-15 when on). RoundBtn 40px: send/stop/confirm/resend.
  - **`web/src/pages/ChatPage.tsx`** (rewritten): makeStreamCallbacks() helper; thinkingStartTime/durationSeconds in StreamState; streaming bubble with ThoughtsBubble + ToolCallBlock + partial text; floating scroll buttons (MdArrowUpward/Downward); ChatInputArea integration.
  - **`web/src/pages/ChatPage.module.css`** (simplified): page-shell only; .streamingBubble, .streamingDots (streamPulse animation), .scrollBtn (absolute, 42px circle, primary bg, RTL-safe centering).
  - **`web/src/components/Markdown.tsx`** + **`Markdown.module.css`**: Code blocks: .codeHeader bar with language chip (.codeLang) + copy button (.copyBtn), uses t('copy_code')/t('copied'). RTL-safe: padding-inline-start on lists, border-inline-start on blockquotes, text-align:start on tables.
  - **`web/src/i18n/he.ts`**: +20 R3 Hebrew keys (thoughts_seconds, editing_message, tool_executing/completed/failed, confirm_edit_and_resend, copy_code, copied, scroll_to_top/bottom, etc.).
  - **`web/src/__tests__/ChatPage.test.tsx`**: +3 R3 tests (full streaming sequence thoughts→tool→text→complete, send/stop toggle, edit mode architecture). Hebrew placeholder + מחשבות assertions.
  - `npm run build` ✓ | `npx vitest run` 83/83 ✓
  - commit: TBD

## UI Refactor — R2 (Chat screen chrome)

- [x] **R2 — Chat screen chrome — top bar, quick settings, model selector, system-prompt dialog**
  - **`web/src/utils/chatUtils.ts`** (new): hoisted `getModelInitial` + `formatTimestamp` from `ChatHistoryPage`; re-imported there.
  - **`web/src/components/chat/ChatTopBar.tsx`** (new): `NormalModeTopBar` (back+search / provider+model center / share+delete), `SearchModeTopBar` (back + full-width input + close/search icon), floating 28px chevron toggle at `bottom:-14px; inset-inline-end:20px`. All inline styles, CSS logical props for RTL.
  - **`web/src/components/chat/QuickSettingsBar.tsx`** (new): animated expand/collapse row of 36px icon-buttons (thinking-budget, temperature, tools, text-direction, system-prompt). Active states: thinking≠none, temp≠null, some tools excluded, prompt≠''. `PopupPortal` via `getBoundingClientRect+createPortal` avoids overflow clipping. Four popups: `ThinkingBudgetPopup` (none/low/medium/high/max), `TemperaturePopup` (slider 0–2 + reset), `ToolToggleDropdown` (master toggle + per-tool checkboxes), `TextDirectionMenu` (RTL/A/LTR buttons). Exports `TextDirectionMode` type.
  - **`web/src/components/chat/ModelSelectorDialog.tsx`** (new): provider tab bar (star + one per provider), `StarredPage` (starred models or empty hint), `ProviderModelList` (filtered rows + star toggle), custom model name entry, filter search input.
  - **`web/src/components/chat/SystemPromptDialog.tsx`** (new): multiline textarea (`direction:auto`), cancel/אישור buttons via R0 `Dialog`/`DialogButton`, saves via `chats.update(chatId, {systemPrompt})`.
  - **`web/src/pages/ChatPage.tsx`**: integrated all four components; added state for searchMode, quickSettingsExpanded, textDirectionMode, providersList, starredModels, showModelSelector, showSystemPromptDialog, showDeleteConfirm. All new hooks hoisted before early return (Rules of Hooks). `handleDeleteChat` → confirm dialog → `chats.delete`; `handleSaveSystemPrompt` → `chats.update`; `handleModelSelect` / `handleToggleStar` → settings update.
  - **`web/src/i18n/he.ts`**: added 30+ R2 Hebrew keys (thinking labels, temperature, tools, text-direction, model-selector, starred, quick-settings).
  - **`web/src/__tests__/ChatPage.test.tsx`**: added 4 new R2 chrome tests; updated topbar test to use `getAllByText` for model name (appears in both topbar + message bubble).
  - **`web/src/__tests__/SmokeTests.test.tsx`**: added `providers.list` mock.
  - `npm run build` ✓ | `npx vitest run` 80/80 ✓

## UI Refactor — R1 (ChatHistory screen)

- [x] **R1 — ChatHistory screen — Hebrew/RTL, groups, search, context menus, dialogs**
  - Rewrote `web/src/pages/ChatHistoryPage.tsx` to match Android `ChatHistoryScreen.kt` pixel-for-pixel.
  - Top bar: forced-LTR "**A**p**I**" logo, search icon (enters search mode), API-keys icon, settings icon.
  - Search mode: full-width search field with Hebrew placeholder "חיפוש בשיחות...", live debounced results via `search.query`, close/clear button.
  - List: `organizeAndSortAllItems` ported from `ChatListOrganizer.kt` — groups + ungrouped chats sorted by last-message timestamp.
  - Chat item (`ChatItemCard`): 48px model-initial avatar (color-coded circle), title, last-message preview, timestamp (`formatTimestamp` from `ChatUtils.kt`), message count with MdForum icon.
  - Group item (`GroupItemCard`): folder icon in secondary color, name, chat count, expand-in-place arrow — click navigates to group screen.
  - Grouped chats: indented 48px `margin-inline-start` (RTL-aware, mirrors Android's `padding(start=32dp)`).
  - FAB: fixed `inset-inline-end: 24px; bottom: 24px` (RTL → bottom-left, matching Android Scaffold FAB bottomEnd placement), single tap creates "שיחה חדשה" and navigates.
  - Context menus: right-click + 500 ms long-press (pointer-down timer), portaled to `document.body`, chat menu and group menu with full menu item sets from `ChatHistoryContextMenus.kt`.
  - Dialogs: rename-chat, delete-chat-confirm, create-group, rename-group, delete-group-confirm — all using R0 `Dialog`/`DialogButton`.
  - Added 22 new Hebrew i18n keys to `web/src/i18n/he.ts` (search_, close_search, share_chat, add_to_group, remove_from_group, create_new_group, rename_group, make_project, delete_group_scatter, group_name_label, etc.).
  - Added `MdFolder`, `MdStar`, `MdForum`, `MdRemove`, `MdStar` to `web/src/ui/icons.ts`.
  - Tests: rewrote `ChatHistoryPage.test.tsx` for Hebrew UI (12 tests, up from 10): top-bar icon buttons, FAB direct-create, Hebrew search placeholder, search mode toggle, context-menu-rename→dialog→API-call. All 76 vitest tests green.
  - `npm run build` ✓ | `npx vitest run` 76/76 ✓
  - commit: aa540d6

## UI Refactor — R0 (Foundation)

- [x] **R0 — Design tokens + RTL shell + UI kit + i18n + nav scaffold**
  - `web/src/styles/theme.css` — all Color.kt + Type.kt tokens as CSS variables (`--bg`, `--surface`, `--primary`, typography scale, radius, spacing, alpha overlays).
  - `web/index.html` — `<html lang="he" dir="rtl">` (RTL shell).
  - `web/src/index.css` — imports `theme.css` globally.
  - `web/src/i18n/he.ts` — 99 Hebrew strings from `strings.xml` (verbatim), `t()` helper + `useT()` hook.
  - `web/src/ui/icons.ts` — named re-exports from `react-icons/md` (single import point for screens).
  - `web/src/ui/IconButton.tsx` — 36px rounded-square icon button, `active` prop → `--primary-15` bg.
  - `web/src/ui/Surface.tsx` — bg + border-radius + elevation helper.
  - `web/src/ui/Card.tsx` — convenience wrapper over Surface.
  - `web/src/ui/RoundButton.tsx` — 40px circular primary action button (send/confirm/web-search).
  - `web/src/ui/Popup.tsx` + `PopupItem` — anchored dropdown menu (portaled, dismissible).
  - `web/src/ui/Dialog.tsx` + `DialogButton` — modal dialog (Material3 dark look).
  - `web/src/components/AppLayout.tsx` — sidebar REMOVED; replaced with centered 520px `.shell`/`.frame` column.
  - `web/src/pages/LoginPage.tsx` — restyled dark/Hebrew (labels from `i18n/he.ts`).
  - `web/src/__tests__/LoginPage.test.tsx` — updated for Hebrew button text.
  - `react-icons@^4.12.0` added to dependencies.
  - `npm run build` ✓ | `npx vitest run` 74/74 ✓
  - commit: 575edb9

## How to run (production)
See `server/deploy/DEPLOY.md` for the full deployment guide.
Short version: set secrets in `server/deploy/llm-web.env`, run `./gradlew :server:installDist && npm --prefix web run build`, then `sudo systemctl enable --now llm-web` (after copying the unit file).
