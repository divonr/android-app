package com.example.ApI.server

import com.example.ApI.data.repository.DataRepository
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main() {
    embeddedServer(Netty, port = 8091, module = Application::module).start(wait = true)
}

/**
 * Top-level Ktor Application module.
 *
 * Called by [embeddedServer] at startup and also by [testApplication] in tests,
 * so tests can exercise the full stack without network overhead.
 *
 * The [storage] parameter allows tests to supply an isolated [ServerPlatformStorage]
 * pointing at a temp directory, keeping tests hermetic.
 */
fun Application.module(storage: ServerPlatformStorage = ServerPlatformStorage()) {
    // ── Dependency wiring ────────────────────────────────────────────────────
    val repository = DataRepository(storage)
    installAppModule(AppModule(repository))

    // ── Plugins ──────────────────────────────────────────────────────────────
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Internal server error"))
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not found"))
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
        }
    }

    install(CallLogging) {
        level = Level.INFO
    }

    install(CORS) {
        // Dev: allow all origins; P1 will tighten this with proper allowHost calls.
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowCredentials = true
    }

    // ── Routes ───────────────────────────────────────────────────────────────
    configureRouting()
}
