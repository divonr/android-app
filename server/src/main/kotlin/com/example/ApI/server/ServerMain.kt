package com.example.ApI.server

import com.example.ApI.data.repository.DataRepository
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.SecureRandom
import java.util.Base64

// ── Session / Auth models ────────────────────────────────────────────────────

private val log = LoggerFactory.getLogger("ServerMain")

/**
 * Signed session data stored in the `llm_web_session` cookie.
 *
 * The cookie is HTTP-only and signed with a secret read from the
 * `WEB_UI_SESSION_SECRET` environment variable.  If that variable is
 * unset, a random per-process secret is generated (everyone is logged
 * out on restart — acceptable for local/dev use).
 */
@Serializable
data class UserSession(val authenticated: Boolean = true, val issuedAt: Long = System.currentTimeMillis()) : Principal

/**
 * Auth configuration injected into [Application.module].
 *
 * Separating it from the module signature lets tests supply a known password
 * without relying on the real `WEB_UI_PASSWORD` environment variable.
 *
 * @param password  The password that `POST /login` accepts.
 * @param sessionSecret  The signing secret for the session cookie.
 */
data class AuthConfig(
    val password: String,
    val sessionSecret: String = resolveSessionSecret()
)

/** Reads or generates the session signing secret (called at startup). */
fun resolveSessionSecret(): String {
    val env = System.getenv("WEB_UI_SESSION_SECRET")
    if (!env.isNullOrBlank()) return env
    val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
    return Base64.getEncoder().encodeToString(random)
}

/** Reads the web-UI password from the environment, with a noisy dev fallback. */
fun resolvePassword(): String {
    val pw = System.getenv("WEB_UI_PASSWORD")
    if (!pw.isNullOrBlank()) return pw
    log.warn("WEB_UI_PASSWORD is not set — using insecure default password 'changeme'. Set WEB_UI_PASSWORD for production use.")
    return "changeme"
}

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
 *
 * The [authConfig] parameter allows tests to inject a known password and secret
 * without depending on the real environment variable.
 */
fun Application.module(
    storage: ServerPlatformStorage = ServerPlatformStorage(),
    authConfig: AuthConfig = AuthConfig(password = resolvePassword())
) {
    // ── Dependency wiring ────────────────────────────────────────────────────
    val repository = DataRepository(storage)
    installAppModule(AppModule(repository))

    // ── Sessions ─────────────────────────────────────────────────────────────
    install(Sessions) {
        cookie<UserSession>("llm_web_session") {
            cookie.httpOnly = true
            cookie.path = "/"
            // TLS terminates at Cloudflare; the app-level connection is plain HTTP.
            cookie.secure = false
            cookie.maxAgeInSeconds = 30L * 24 * 60 * 60  // 30 days
            cookie.extensions["SameSite"] = "Strict"
            transform(SessionTransportTransformerMessageAuthentication(authConfig.sessionSecret.toByteArray()))
        }
    }

    // ── Authentication ────────────────────────────────────────────────────────
    install(Authentication) {
        session<UserSession>("session") {
            validate { session ->
                if (session.authenticated) session else null
            }
            challenge {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            }
        }
    }

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
    }

    install(CallLogging) {
        level = Level.INFO
    }

    install(CORS) {
        // Dev: allow all origins; tighten with proper allowHost calls when deploying.
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
    configureRouting(authConfig)
}
