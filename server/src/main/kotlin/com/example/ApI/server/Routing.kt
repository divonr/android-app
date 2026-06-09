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
import java.security.MessageDigest

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class LoginRequest(val password: String)

// ── P2 DTOs ───────────────────────────────────────────────────────────────────

/**
 * Flat model-picker item returned by GET /api/providers/models.
 */
@Serializable
data class ProviderModelItem(val provider: String, val modelName: String)

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
 */
@Serializable
data class SyncStatusResponse(
    val enabled: Boolean,
    val serverBaseUrl: String,
    val lastChangeTick: Long
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
 *
 * Authenticated routes (`authenticate("session") { route("/api") { ... } }`):
 *   GET  /api/session
 *   ... (later phases add routes via [Route.apiRoutes])
 *
 * To add new authenticated API endpoints in later phases, place them inside
 * [Route.apiRoutes] — that function is the single insertion point that future
 * subagents use.
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

fun Application.configureRouting(authConfig: AuthConfig = AuthConfig(password = resolvePassword())) {
    routing {
        // ── Public ───────────────────────────────────────────────────────────
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }

        post("/login") {
            val body = call.receive<LoginRequest>()
            if (passwordsEqual(body.password, authConfig.password)) {
                call.sessions.set(UserSession(authenticated = true, issuedAt = System.currentTimeMillis()))
                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            } else {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid password"))
            }
        }

        post("/logout") {
            call.sessions.clear<UserSession>()
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }

        // ── Public OAuth callbacks (must be outside auth block) ──────────────
        oauthCallbackRoutes()

        // ── Authenticated API ─────────────────────────────────────────────────
        authenticate("session") {
            route("/api") {
                // P1: session check endpoint
                get("/session") {
                    call.respond(HttpStatusCode.OK, mapOf("authenticated" to true))
                }

                // ── Insertion point for future phases ──────────────────────
                // P2+: call apiRoutes() here to add more authenticated endpoints.
                apiRoutes()
            }
        }

        // ── P8: Static SPA serving ───────────────────────────────────────────
        // Serve the built React app from WEB_STATIC_DIR (or web/dist by default).
        // API and auth routes declared above always take precedence because Ktor
        // evaluates routes in declaration order and these are declared last.
        //
        // Strategy: a single tailcard GET handler that
        //   - Serves real asset files (JS/CSS/images/favicon) from disk when they exist.
        //   - Falls back to index.html for any unknown path (client-side SPA routes).
        //   - Never swallows /api paths (defensive guard — those are matched before this).
        val staticDir = resolveStaticDir()
        if (staticDir != null) {
            routingLog.info("Serving SPA from ${staticDir.absolutePath}")
            val indexFile = File(staticDir, "index.html")
            // Tailcard `{...}` matches zero or more remaining path segments — this is
            // the last-resort handler for all GET requests not matched by specific routes.
            get("{...}") {
                val rawPath = call.request.uri.substringBefore('?') // strip query string
                // Defensive guard: /api paths must not reach this handler.
                if (rawPath.startsWith("/api/") || rawPath == "/api") {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not found"))
                    return@get
                }
                // Try to serve the requested file from the static dir.
                val candidate = File(staticDir, rawPath.trimStart('/'))
                if (candidate.isFile && candidate.canonicalPath.startsWith(staticDir.canonicalPath)) {
                    call.respondFile(candidate)
                } else {
                    // SPA fallback: return index.html so the React router handles the path.
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

/**
 * Extension point for authenticated `/api` routes added in phases P2 and beyond.
 *
 * This function is called inside `authenticate("session") { route("/api") { ... } }`,
 * so every route defined here is automatically protected by session auth.
 *
 * Usage (later phases just add to this function):
 * ```kotlin
 * fun Route.apiRoutes() {
 *     get("/chats") { ... }
 *     post("/chat/send") { ... }
 *     // etc.
 * }
 * ```
 */
// ── Username helper ──────────────────────────────────────────────────────────

/**
 * Resolves the "current user" for repository calls.
 *
 * The server is single-user: the canonical username comes from
 * [AppSettings.current_user].  An optional `?username=` query param is
 * accepted for callers that know the username explicitly, but in practice
 * the server always uses the stored current user.
 *
 * Reusable by P3 (streaming send) and P4 (mutation APIs).
 */
fun ApplicationCall.currentUsername(): String {
    val explicit = request.queryParameters["username"]
    if (!explicit.isNullOrBlank()) return explicit
    val repo = application.appModule.repository
    return repo.loadAppSettings().current_user
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
    // Safe no-op when sync is disabled (SyncEngine.pullNow() guards on enabled flag).
    post("/sync/pull") {
        call.application.appModule.repository.pullNow()
        call.respond(HttpStatusCode.OK, mapOf("ok" to true))
    }

    // GET /api/sync/status — returns sync enablement state + last change tick.
    // Auth token is deliberately excluded from the response.
    get("/sync/status") {
        val repo = call.application.appModule.repository
        val syncSettings = repo.loadAppSettings().remoteSync
        call.respond(
            HttpStatusCode.OK,
            SyncStatusResponse(
                enabled = syncSettings.enabled,
                serverBaseUrl = syncSettings.serverBaseUrl,
                lastChangeTick = repo.syncChangeTick.value
            )
        )
    }

    // ── Providers ────────────────────────────────────────────────────────────

    // GET /api/providers — full list of Provider objects
    get("/providers") {
        val providers = call.application.appModule.repository.loadProviders()
        call.respond(HttpStatusCode.OK, providers)
    }

    // GET /api/providers/models — flat model-picker list grouped by provider
    get("/providers/models") {
        val providers = call.application.appModule.repository.loadProviders()
        val items = providers.flatMap { p ->
            p.models.mapNotNull { m -> m.name?.let { name -> ProviderModelItem(p.provider, name) } }
        }
        call.respond(HttpStatusCode.OK, items)
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    // GET /api/settings — AppSettings for the current user
    get("/settings") {
        val settings = call.application.appModule.repository.loadAppSettings()
        call.respond(HttpStatusCode.OK, settings)
    }

    // ── Chat history ─────────────────────────────────────────────────────────

    // GET /api/chats — full UserChatHistory for the current user
    get("/chats") {
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val history = repo.loadChatHistory(username)
        call.respond(HttpStatusCode.OK, history)
    }

    // GET /api/chats/{chatId} — single Chat by ID; 404 if not found
    get("/chats/{chatId}") {
        val chatId = call.parameters["chatId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val chat = repo.loadChatHistory(username).chat_history.find { it.chat_id == chatId }
        if (chat == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
        } else {
            call.respond(HttpStatusCode.OK, chat)
        }
    }

    // ── Groups ───────────────────────────────────────────────────────────────

    // GET /api/groups — groups list from UserChatHistory
    get("/groups") {
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val groups = repo.loadChatHistory(username).groups
        call.respond(HttpStatusCode.OK, groups)
    }

    // ── API Keys ─────────────────────────────────────────────────────────────

    // GET /api/keys — list of ApiKey with secret masked (last 4 chars visible)
    get("/keys") {
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val keys = repo.loadApiKeys(username).map { it.masked() }
        call.respond(HttpStatusCode.OK, keys)
    }

    // ── Search ───────────────────────────────────────────────────────────────

    // GET /api/search?q={query} — search chats and messages
    get("/search") {
        val query = call.request.queryParameters["q"] ?: ""
        if (query.isBlank()) {
            call.respond(HttpStatusCode.OK, emptyList<SearchResultDto>())
            return@get
        }
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val results = repo.searchChats(username, query).map { sr ->
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
