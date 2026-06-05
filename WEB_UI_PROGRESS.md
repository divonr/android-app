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
- [ ] All screens: streaming chat, markdown/LaTeX, branching, tools/skills, custom providers, integrations, child lock. Tests: Vitest + React Testing Library.

## P8 — E2E + deploy
- [ ] Playwright E2E, systemd unit `llm-web.service`, vite-build→server-static pipeline, Cloudflare route `api-divonr.xyz`. Off-box smoke test.
