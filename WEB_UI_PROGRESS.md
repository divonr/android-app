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
- [ ] Chat CRUD, message edit/resend/delete, branching ops, groups CRUD, api key CRUD, settings update, custom providers CRUD, skills CRUD. Tests: route tests with persistence assertions.

## P5 — Files + integrations OAuth
- [ ] Multipart upload → attachment, GitHub OAuth web redirect + callback, Google OAuth. Tests: upload round-trip; OAuth state/callback with mocked token exchange.

## P6 — Frontend scaffold
- [ ] Vite+React+TS, typed API+SSE clients, login flow, app shell + routing, theme, Vitest. Tests: Vitest unit tests for API/SSE client + auth flow (mocked fetch).

## P7 — Frontend screens (parity)
- [ ] All screens: streaming chat, markdown/LaTeX, branching, tools/skills, custom providers, integrations, child lock. Tests: Vitest + React Testing Library.

## P8 — E2E + deploy
- [ ] Playwright E2E, systemd unit `llm-web.service`, vite-build→server-static pipeline, Cloudflare route `api-divonr.xyz`. Off-box smoke test.
