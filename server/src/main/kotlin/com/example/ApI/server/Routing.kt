package com.example.ApI.server

import com.example.ApI.data.model.ApiKey
import com.example.ApI.server.streaming.resendRoute
import com.example.ApI.server.streaming.sendRoute
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class LoginRequest(val password: String)

@Serializable
data class MeResponse(val username: String, val email: String)

// ── P2 DTOs ───────────────────────────────────────────────────────────────────

/**
 * Flat model-picker item returned by GET /api/providers/models.
 */
@Serializable
data class ProviderModelItem(val provider: String, val modelName: String)

/**
 * Model entry returned by GET /api/providers/detailed.
 * Exposes the in-memory fields the Android model selector uses (pricing,
 * release order, web-search support) that the compact [ModelSerializer]
 * representation drops.
 */
@Serializable
data class ModelDetailDto(
    val name: String,
    val pricing: com.example.ApI.data.model.ModelPricing? = null,
    val releaseOrder: Int? = null,
    val webSearch: String? = null
)

@Serializable
data class ProviderDetailDto(val provider: String, val models: List<ModelDetailDto>)

/**
 * Search result DTO — SearchResult in :shared is NOT @Serializable (contains a Chat),
 * so we map to this flat shape that matches the API contract.
 */
@Serializable
data class SearchResultDto(
    val chatId: String,
    val chatTitle: String,
    val matchType: String,        // "TITLE" | "CONTENT" | "FILE_NAME"
    val messageIndex: Int,
    val snippet: String
)

/**
 * Response body for GET /api/sync/status.
 * The auth token is deliberately omitted — it must never leave the server.
 * [reachable] is null when sync is disabled (no probe attempted);
 * true/false when sync is enabled and the remote health check ran.
 */
@Serializable
data class SyncStatusResponse(
    val enabled: Boolean,
    val serverBaseUrl: String,
    val lastChangeTick: Long,
    val reachable: Boolean? = null
)

/**
 * ApiKey with its secret replaced by a masked form for HTTP read responses.
 * The full key is never sent over the wire; the last 4 chars are surfaced so
 * users can identify which key is which.
 */
@Serializable
data class MaskedApiKey(
    val id: String,
    val provider: String,
    val key: String,             // masked form, e.g. "sk-...abcd"
    val isActive: Boolean,
    val customName: String?
)

/** Mask an ApiKey for HTTP responses — last 4 chars visible, rest replaced by "...". */
fun ApiKey.masked(): MaskedApiKey {
    val maskedKey = if (key.length <= 4) "****" else "...${key.takeLast(4)}"
    return MaskedApiKey(id = id, provider = provider, key = maskedKey, isActive = isActive, customName = customName)
}

/**
 * Constant-time password comparison — prevents timing-oracle attacks.
 */
private fun passwordsEqual(submitted: String, expected: String): Boolean =
    MessageDigest.isEqual(submitted.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

/**
 * Wires all routes for the application.
 *
 * Public routes (no auth required):
 *   GET  /health
 *   POST /login
 *   POST /logout
 *   GET  /auth/google/start       ← Google login start (Step 5a)
 *   GET  /auth/google/callback    ← Google login callback (Step 5a)
 *
 * Authenticated routes (`authenticate("session") { route("/api") { ... } }`):
 *   GET  /api/session
 *   GET  /api/me
 *   ... (later phases add routes via [Route.apiRoutes])
 */
private val routingLog = LoggerFactory.getLogger("Routing")

/**
 * Resolves the directory from which the built React SPA is served.
 *
 * Priority order:
 *   1. Test hook: [StaticDirTestHook.override] (used by unit tests; avoids env-var mutation).
 *   2. `WEB_STATIC_DIR` environment variable (production + E2E tests).
 *   3. `web/dist` relative to the current working directory (dev fallback).
 *
 * Returns null if the resolved directory does not exist; the server will log a
 * warning and continue serving only the API (no crash).
 */
fun resolveStaticDir(): File? {
    // Test hook first so unit tests can inject a temp dir without touching env vars.
    StaticDirTestHook.override?.let { return if (it.isDirectory) it else null }
    val envVal = System.getenv("WEB_STATIC_DIR")
    val dir = if (!envVal.isNullOrBlank()) {
        File(envVal)
    } else {
        // Fallback: web/dist relative to the working directory
        File(System.getProperty("user.dir"), "web/dist")
    }
    return if (dir.isDirectory) dir else null
}

/**
 * Test-only hook for injecting a static directory without env-var mutation.
 * Set before starting [testApplication], clear in a finally block.
 */
object StaticDirTestHook {
    @Volatile var override: File? = null
}

// ── Per-user context helper ───────────────────────────────────────────────────

/**
 * Resolves the [UserContext] for the currently authenticated user.
 *
 * Inside `authenticate("session") { }` blocks [principal] holds the validated
 * [UserSession]; outside (e.g. OAuth callbacks) we fall back to the raw session
 * cookie.  Both paths return the same [UserContext] for the same username.
 */
suspend fun ApplicationCall.userContext(): UserContext {
    val session = principal<UserSession>()
        ?: sessions.get<UserSession>()
        ?: error("No authenticated session found")
    return application.appModule.registry.context(session.username)
}

fun Application.configureRouting(
    authConfig: AuthConfig = AuthConfig(password = resolvePassword()),
    allowedGoogleEmails: Set<String> = resolveAllowedGoogleEmails()
) {
    routing {
        // ── Public ───────────────────────────────────────────────────────────
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }

        post("/login") {
            val body = call.receive<LoginRequest>()
            if (passwordsEqual(body.password, authConfig.password)) {
                call.sessions.set(
                    UserSession(
                        authenticated = true,
                        username = "default",
                        issuedAt = System.currentTimeMillis()
                    )
                )
                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            } else {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid password"))
            }
        }

        post("/logout") {
            call.sessions.clear<UserSession>()
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }

        // ── Google login routes (distinct from /oauth/google/start|callback Workspace routes) ──
        googleLoginRoutes(allowedGoogleEmails)

        // ── Public OAuth callbacks (must be outside auth block) ──────────────
        oauthCallbackRoutes()

        // ── Authenticated API ─────────────────────────────────────────────────
        authenticate("session") {
            route("/api") {
                // P1: session check endpoint
                get("/session") {
                    call.respond(HttpStatusCode.OK, mapOf("authenticated" to true))
                }

                // GET /api/me — returns the current user's identity
                get("/me") {
                    val session = call.principal<UserSession>()!!
                    call.respond(HttpStatusCode.OK, MeResponse(username = session.username, email = session.email))
                }

                // ── Insertion point for future phases ──────────────────────
                apiRoutes()
            }
        }

        // ── P8: Static SPA serving ───────────────────────────────────────────
        val staticDir = resolveStaticDir()
        if (staticDir != null) {
            routingLog.info("Serving SPA from ${staticDir.absolutePath}")
            val indexFile = File(staticDir, "index.html")
            get("{...}") {
                val rawPath = call.request.uri.substringBefore('?')
                if (rawPath.startsWith("/api/") || rawPath == "/api") {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not found"))
                    return@get
                }
                val candidate = File(staticDir, rawPath.trimStart('/'))
                if (candidate.isFile && candidate.canonicalPath.startsWith(staticDir.canonicalPath)) {
                    call.respondFile(candidate)
                } else {
                    call.respondFile(indexFile)
                }
            }
        } else {
            routingLog.warn(
                "WEB_STATIC_DIR is not set or does not exist — serving API only (no SPA). " +
                "Set WEB_STATIC_DIR=<path-to-web/dist> to serve the frontend."
            )
        }
    }
}

// ── Google login routes ───────────────────────────────────────────────────────

/**
 * Registers `GET /auth/google/start` and `GET /auth/google/callback` for the
 * Google sign-in login flow.
 *
 * These routes are **distinct** from the existing `/oauth/google/start|callback` routes which
 * handle the Google Workspace integration and MUST stay unchanged.
 *
 * CSRF protection: a random state token is stored in a pre-auth session slot
 * ([UserSession.googleLoginState]) before the Google redirect and validated in
 * the callback.  A pre-auth session is used (authenticated=false) so the state
 * survives the round-trip before the user is formally logged in.
 */
private fun Route.googleLoginRoutes(allowedGoogleEmails: Set<String>) {

    // GET /auth/google/start — redirect to Google authorize URL
    get("/auth/google/start") {
        val clientId = resolveGoogleClientId()
        if (clientId.isBlank()) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Google login not configured — set GOOGLE_OAUTH_CLIENT_ID")
            )
            return@get
        }

        val state = generateOAuthState()

        // Store state in a pre-auth session slot so the callback can validate it.
        val existing = call.sessions.get<UserSession>() ?: UserSession(authenticated = false)
        call.sessions.set(existing.copy(googleLoginState = state))

        val baseUrl = resolvePublicBaseUrl()
        val redirectUri = "$baseUrl/auth/google/callback"
        val scopes = "openid email profile"

        val authorizeUrl = buildString {
            append("https://accounts.google.com/o/oauth2/v2/auth")
            append("?client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
            append("&response_type=code")
            append("&scope=").append(URLEncoder.encode(scopes, "UTF-8"))
            append("&state=").append(state)
            append("&prompt=select_account")
        }

        call.respondRedirect(authorizeUrl)
    }

    // GET /auth/google/callback?code=&state= — complete the login
    get("/auth/google/callback") {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]

        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing code or state"))
            return@get
        }

        val session = call.sessions.get<UserSession>()
        if (session?.googleLoginState == null || session.googleLoginState != state) {
            call.respondRedirect("/login?error=invalid_state")
            return@get
        }

        // Consume state (one-time use)
        call.sessions.set(session.copy(googleLoginState = null))

        val appModule = call.application.appModule
        val exchanger = appModule.oauthExchanger
        val verifier = appModule.googleTokenVerifier
        val syncAuth = appModule.syncAuthClient

        // Step 1: exchange code → ID token
        val clientId = resolveGoogleClientId()
        val clientSecret = resolveGoogleClientSecret()
        val baseUrl = resolvePublicBaseUrl()
        val redirectUri = "$baseUrl/auth/google/callback"

        val idTokenResult = exchanger.exchangeGoogleIdToken(code, clientId, clientSecret, redirectUri)
        if (idTokenResult.isFailure) {
            routingLog.warn("Google id_token exchange failed: ${idTokenResult.exceptionOrNull()?.message}")
            call.respondRedirect("/login?error=token_exchange_failed")
            return@get
        }
        val idToken = idTokenResult.getOrThrow()

        // Step 2: verify ID token
        val claims = verifier.verify(idToken)
        if (claims == null) {
            call.respondRedirect("/login?error=token_invalid")
            return@get
        }

        // Step 3: optional allowlist check
        if (allowedGoogleEmails.isNotEmpty() && claims.email !in allowedGoogleEmails) {
            routingLog.warn("Google login rejected: email '${claims.email}' not in allowlist")
            call.respondRedirect("/login?error=not_allowed")
            return@get
        }

        // Step 4: exchange with sync server to get canonical username + token
        val syncResult = syncAuth.exchange(idToken)
        if (syncResult == null) {
            routingLog.warn("Sync server unavailable during login for '${claims.email}'")
            call.respondRedirect("/login?error=sync_unavailable")
            return@get
        }

        // Step 5: bootstrap user in registry (seeds AppSettings, starts engine)
        appModule.registry.bootstrapUserSync(
            username = syncResult.username,
            email = syncResult.email,
            syncToken = syncResult.token
        )

        // Step 6: set authenticated session
        call.sessions.set(
            UserSession(
                authenticated = true,
                username = syncResult.username,
                email = syncResult.email,
                issuedAt = System.currentTimeMillis()
            )
        )

        routingLog.info("Google login successful for '${syncResult.email}' → username='${syncResult.username}'")
        call.respondRedirect("/")
    }
}

// ── P2 read routes ────────────────────────────────────────────────────────────

fun Route.apiRoutes() {

    // ── P3: Streaming send ────────────────────────────────────────────────────
    sendRoute()

    // ── P4: Resend (SSE, same plumbing as sendRoute) ──────────────────────────
    resendRoute()

    // ── P4: Mutation APIs ─────────────────────────────────────────────────────
    mutationRoutes()

    // ── P5: File upload + integrations OAuth ─────────────────────────────────
    fileRoutes()
    integrationRoutes()

    // ── Sync ─────────────────────────────────────────────────────────────────

    // POST /api/sync/pull — trigger an immediate pull from the sync server
    post("/sync/pull") {
        val ctx = call.userContext()
        ctx.repository.pullNow()
        call.respond(HttpStatusCode.OK, mapOf("ok" to true))
    }

    // GET /api/sync/status — returns sync enablement state + last change tick + reachability probe.
    get("/sync/status") {
        val ctx = call.userContext()
        val repo = ctx.repository
        val syncSettings = repo.loadAppSettings().remoteSync
        val reachable: Boolean? = if (syncSettings.enabled) {
            try { repo.testSyncConnection() } catch (_: Exception) { false }
        } else {
            null
        }
        call.respond(
            HttpStatusCode.OK,
            SyncStatusResponse(
                enabled = syncSettings.enabled,
                serverBaseUrl = syncSettings.serverBaseUrl,
                lastChangeTick = repo.syncChangeTick.value,
                reachable = reachable
            )
        )
    }

    // ── Providers ────────────────────────────────────────────────────────────

    // GET /api/providers — full list of Provider objects
    get("/providers") {
        val ctx = call.userContext()
        call.respond(HttpStatusCode.OK, ctx.repository.loadProviders())
    }

    // GET /api/providers/detailed — providers with full model metadata
    get("/providers/detailed") {
        val ctx = call.userContext()
        val providers = ctx.repository.loadProviders()
        val result = providers.map { p ->
            ProviderDetailDto(
                provider = p.provider,
                models = p.models.mapNotNull { m ->
                    m.name?.let { name ->
                        ModelDetailDto(
                            name = name,
                            pricing = m.pricing,
                            releaseOrder = m.releaseOrder,
                            webSearch = m.webSearch
                        )
                    }
                }
            )
        }
        call.respond(HttpStatusCode.OK, result)
    }

    // GET /api/providers/models — flat model-picker list grouped by provider
    get("/providers/models") {
        val ctx = call.userContext()
        val providers = ctx.repository.loadProviders()
        val items = providers.flatMap { p ->
            p.models.mapNotNull { m -> m.name?.let { name -> ProviderModelItem(p.provider, name) } }
        }
        call.respond(HttpStatusCode.OK, items)
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    // GET /api/settings — AppSettings for the current user
    get("/settings") {
        val ctx = call.userContext()
        call.respond(HttpStatusCode.OK, ctx.repository.loadAppSettings())
    }

    // ── Chat history ─────────────────────────────────────────────────────────

    // GET /api/chats — full UserChatHistory for the current user
    get("/chats") {
        val ctx = call.userContext()
        call.respond(HttpStatusCode.OK, ctx.repository.loadChatHistory(ctx.username))
    }

    // GET /api/chats/{chatId} — single Chat by ID; 404 if not found
    get("/chats/{chatId}") {
        val chatId = call.parameters["chatId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val ctx = call.userContext()
        val chat = ctx.repository.loadChatHistory(ctx.username).chat_history.find { it.chat_id == chatId }
        if (chat == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
        } else {
            call.respond(HttpStatusCode.OK, chat)
        }
    }

    // ── Groups ───────────────────────────────────────────────────────────────

    // GET /api/groups — groups list from UserChatHistory
    get("/groups") {
        val ctx = call.userContext()
        call.respond(HttpStatusCode.OK, ctx.repository.loadChatHistory(ctx.username).groups)
    }

    // ── API Keys ─────────────────────────────────────────────────────────────

    // GET /api/keys — list of ApiKey with secret masked
    get("/keys") {
        val ctx = call.userContext()
        call.respond(HttpStatusCode.OK, ctx.repository.loadApiKeys(ctx.username).map { it.masked() })
    }

    // ── Search ───────────────────────────────────────────────────────────────

    // GET /api/search?q={query} — search chats and messages
    get("/search") {
        val query = call.request.queryParameters["q"] ?: ""
        if (query.isBlank()) {
            call.respond(HttpStatusCode.OK, emptyList<SearchResultDto>())
            return@get
        }
        val ctx = call.userContext()
        val results = ctx.repository.searchChats(ctx.username, query).map { sr ->
            SearchResultDto(
                chatId = sr.chat.chat_id,
                chatTitle = sr.chat.preview_name,
                matchType = sr.matchType.name,
                messageIndex = sr.messageIndex,
                snippet = if (sr.messageIndex >= 0)
                    sr.chat.messages.getOrNull(sr.messageIndex)?.text?.take(200) ?: ""
                else
                    sr.chat.preview_name
            )
        }
        call.respond(HttpStatusCode.OK, results)
    }
}
