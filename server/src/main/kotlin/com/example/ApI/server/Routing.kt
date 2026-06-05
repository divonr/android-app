package com.example.ApI.server

import com.example.ApI.data.model.ApiKey
import com.example.ApI.server.streaming.sendRoute
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
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
