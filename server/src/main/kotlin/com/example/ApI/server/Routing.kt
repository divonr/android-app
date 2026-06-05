package com.example.ApI.server

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
fun Route.apiRoutes() {
    // P2+ will add routes here.
}
