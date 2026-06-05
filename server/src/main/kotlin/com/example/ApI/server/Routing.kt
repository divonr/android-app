package com.example.ApI.server

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

/**
 * Wires all routes for the application.
 *
 * P0: Only /health is present.
 * Later phases add auth, api, sse, etc. to this block.
 */
fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }

        // Future phases will add:
        //   post("/login")  { ... }
        //   post("/logout") { ... }
        //   route("/api")   { ... }   (guarded by session auth)
    }
}
