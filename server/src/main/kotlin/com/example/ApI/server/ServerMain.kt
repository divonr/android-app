package com.example.ApI.server

import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.auth.GoogleTokenVerifier
import com.example.ApI.server.auth.RealGoogleTokenVerifier
import com.example.ApI.server.auth.RealSyncAuthClient
import com.example.ApI.server.auth.SyncAuthClient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * `WEB_UI_SESSION_SECRET` environment variable.
 *
 * **Step 5a additions**: [username] and [email] carry per-user identity.
 * Password login sets `username = "default"` (no change in session shape for
 * existing clients; the extra fields have safe defaults).
 *
 * [googleLoginState] is the CSRF state token for the **login** OAuth flow
 * (`/auth/google/start|callback`).  It is stored in a pre-auth session slot so
 * it survives the Google redirect before the user is authenticated.
 */
@Serializable
data class UserSession(
    val authenticated: Boolean = true,
    val issuedAt: Long = System.currentTimeMillis(),
    /** Canonical username from the sync server, or "default" for password login. */
    val username: String = "default",
    /** Google account email; empty for password login. */
    val email: String = "",
    /** CSRF state token stored during GitHub OAuth flow. Cleared after use. */
    val githubOAuthState: String? = null,
    /** CSRF state token stored during Google Workspace OAuth flow. Cleared after use. */
    val googleOAuthState: String? = null,
    /** CSRF state token stored during Google login flow. Cleared after use. */
    val googleLoginState: String? = null
) : Principal

/**
 * Auth configuration injected into [Application.module].
 *
 * @param sessionSecret  The signing secret for the session cookie.
 */
data class AuthConfig(
    val sessionSecret: String = resolveSessionSecret()
)

/** Reads or generates the session signing secret (called at startup). */
fun resolveSessionSecret(): String {
    val env = System.getenv("WEB_UI_SESSION_SECRET")
    if (!env.isNullOrBlank()) return env
    val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
    return Base64.getEncoder().encodeToString(random)
}

/** Reads the allowed Google emails allowlist from the environment. */
fun resolveAllowedGoogleEmails(): Set<String> {
    val env = System.getenv("ALLOWED_GOOGLE_EMAILS") ?: return emptySet()
    return env.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
}

fun main() {
    val port = System.getenv("KTOR_PORT")?.toIntOrNull() ?: 8091
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

/**
 * Top-level Ktor Application module.
 *
 * Called by [embeddedServer] at startup and also by [testApplication] in tests.
 *
 * **Injection surface**:
 * @param storage               Root [ServerPlatformStorage].  Tests pass a temp dir.
 * @param authConfig            Session signing secret.  Tests may inject a fixed secret.
 * @param oauthExchanger        GitHub / Google Workspace token exchanger.
 * @param titleGeneratorFactory Factory for per-user [TitleGenerator].
 * @param chatEngineFactory     Factory for per-user [ChatEngine].
 * @param googleTokenVerifier   Google ID token verifier.  Tests pass a fake.
 * @param syncAuthClient        Sync-server exchanger.  Tests pass a fake.
 * @param startRegistry         When false, the registry's sync engine is never
 *                              started (test isolation flag — suppresses all network calls).
 * @param allowedGoogleEmails   Allowlist for Google login.  Empty = allow all.
 */
fun Application.module(
    storage: ServerPlatformStorage = ServerPlatformStorage(),
    authConfig: AuthConfig = AuthConfig(),
    oauthExchanger: com.example.ApI.server.oauth.OAuthTokenExchanger? = null,
    titleGeneratorFactory: ((DataRepository) -> TitleGenerator)? = null,
    googleTokenVerifier: GoogleTokenVerifier? = null,
    syncAuthClient: SyncAuthClient? = null,
    startRegistry: Boolean = true,
    allowedGoogleEmails: Set<String> = resolveAllowedGoogleEmails(),
    // Keep last so that `module(storage, authConfig) { repo -> engine }` trailing-lambda syntax works.
    chatEngineFactory: ((DataRepository) -> com.example.ApI.server.streaming.ChatEngine)? = null
) {
    // ── Per-user sync params ─────────────────────────────────────────────────
    val syncServerUrl = System.getenv("SYNC_SERVER_URL")?.takeIf { it.isNotBlank() }
        ?: "http://localhost:8090"
    val pullIntervalSeconds = System.getenv("SYNC_PULL_INTERVAL_SECONDS")?.toLongOrNull() ?: 20L

    // ── UserRegistry ─────────────────────────────────────────────────────────
    val registry = UserRegistry(
        baseDir = storage.baseDir,
        syncServerUrl = syncServerUrl,
        pullIntervalSeconds = pullIntervalSeconds,
        startEngine = startRegistry,
        chatEngineFactory = chatEngineFactory ?: { repo ->
            com.example.ApI.server.streaming.RepositoryChatEngine(repo)
        },
        titleGeneratorFactory = titleGeneratorFactory ?: { repo ->
            TitleGenerator { username, chatId, provider ->
                repo.generateConversationTitle(username, chatId, provider)
            }
        }
    )

    // Stop all user pull loops on shutdown
    environment.monitor.subscribe(ApplicationStopping) {
        registry.stopAll()
    }

    // ── AppModule ─────────────────────────────────────────────────────────────
    val appModule = AppModule(
        registry = registry,
        oauthExchanger = oauthExchanger
            ?: com.example.ApI.server.oauth.RealOAuthTokenExchanger(),
        googleTokenVerifier = googleTokenVerifier ?: RealGoogleTokenVerifier(),
        syncAuthClient = syncAuthClient ?: RealSyncAuthClient(syncServerUrl)
    )
    installAppModule(appModule)

    // ── Sessions ─────────────────────────────────────────────────────────────
    install(Sessions) {
        cookie<UserSession>("llm_web_session") {
            cookie.httpOnly = true
            cookie.path = "/"
            // TLS terminates at Cloudflare; the app-level connection is plain HTTP.
            cookie.secure = false
            cookie.maxAgeInSeconds = 30L * 24 * 60 * 60  // 30 days
            // Lax (not Strict): the cookie must be sent on the top-level GET
            // redirect back from accounts.google.com to /auth/google/callback,
            // otherwise the OAuth state session slot is lost and login fails
            // with "invalid_state" in real browsers (curl ignores SameSite).
            cookie.extensions["SameSite"] = "Lax"
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
    configureRouting(allowedGoogleEmails)
}

// ── OAuth client credentials helpers ────────────────────────────────────────

/**
 * Resolve the public base URL used to build OAuth redirect URIs.
 *
 * In production this should be `https://app.api-divonr.xyz`.
 * Set `PUBLIC_BASE_URL` in the environment; defaults to `http://localhost:8091`.
 */
fun resolvePublicBaseUrl(): String =
    System.getenv("PUBLIC_BASE_URL")?.trimEnd('/') ?: "http://localhost:8091"

/**
 * GitHub OAuth client id.
 *
 * Same OAuth app as the desktop/Android client (app `Ov23liIqbBxkhRQcaTn1`).
 * Override with env `GITHUB_OAUTH_CLIENT_ID` if needed.
 */
fun resolveGitHubClientId(): String =
    System.getenv("GITHUB_OAUTH_CLIENT_ID")?.takeIf { it.isNotBlank() }
        ?: "Ov23liIqbBxkhRQcaTn1"   // desktop fallback (GitHubOAuthService.CLIENT_ID)

/**
 * GitHub OAuth client secret.
 * Override with env `GITHUB_OAUTH_CLIENT_SECRET`.
 */
fun resolveGitHubClientSecret(): String =
    System.getenv("GITHUB_OAUTH_CLIENT_SECRET")?.takeIf { it.isNotBlank() }
        ?: "6b2e01569404a3ea854e5bb4187d63ff9316f59d"  // desktop fallback

/**
 * Google OAuth client id.
 * Set via env `GOOGLE_OAUTH_CLIENT_ID` (no desktop fallback — Android uses Google Sign-In,
 * desktop doesn't support it; provide a real Web application client id for production).
 */
fun resolveGoogleClientId(): String =
    System.getenv("GOOGLE_OAUTH_CLIENT_ID") ?: ""

/**
 * Google OAuth client secret.
 * Set via env `GOOGLE_OAUTH_CLIENT_SECRET`.
 */
fun resolveGoogleClientSecret(): String =
    System.getenv("GOOGLE_OAUTH_CLIENT_SECRET") ?: ""
