package com.example.ApI.server

import com.example.ApI.data.model.RemoteSyncSettings
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
import kotlinx.coroutines.*
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

// ── Remote sync configuration ────────────────────────────────────────────────

/**
 * Remote-sync configuration injected into [Application.module].
 *
 * In Step 5a this is still used to seed the "default" user's sync config from
 * environment variables (backward-compatible with the pre-5a deployment).
 * Step 5b will remove this in favour of per-user sync seeded at login.
 *
 * @param enabled              Whether to activate sync for the "default" user.
 * @param serverBaseUrl        Sync server base URL (mirrors SYNC_SERVER_URL env var).
 * @param authToken            Bearer token (mirrors SYNC_TOKEN env var).
 * @param syncUser             Username whose data to load; null = keep existing current_user.
 * @param pullIntervalSeconds  Seconds between periodic background pulls.
 * @param startEngine          When false, config is seeded but no engine is started.
 *                             Used in tests to assert config without opening sockets.
 * @param syncApiKeys          Whether to sync API keys (mirrors SYNC_API_KEYS env var).
 */
data class SyncConfig(
    val enabled: Boolean = false,
    val serverBaseUrl: String = RemoteSyncSettings().serverBaseUrl,
    val authToken: String = "",
    val syncUser: String? = null,
    val pullIntervalSeconds: Long = 20L,
    val startEngine: Boolean = true,
    val syncApiKeys: Boolean = false
)

/** Reads sync configuration from environment variables. */
fun resolveSyncConfig(): SyncConfig {
    val rawEnabled = System.getenv("SYNC_ENABLED")?.trim()?.lowercase()
    val enabled = rawEnabled == "true" || rawEnabled == "1"
    val serverBaseUrl = System.getenv("SYNC_SERVER_URL")?.takeIf { it.isNotBlank() }
        ?: RemoteSyncSettings().serverBaseUrl
    val authToken = System.getenv("SYNC_TOKEN") ?: ""
    val syncUser = System.getenv("SYNC_USER")?.takeIf { it.isNotBlank() }
    val pullIntervalSeconds = System.getenv("SYNC_PULL_INTERVAL_SECONDS")?.toLongOrNull() ?: 20L
    val rawSyncApiKeys = System.getenv("SYNC_API_KEYS")?.trim()?.lowercase()
    val syncApiKeys = rawSyncApiKeys == "true" || rawSyncApiKeys == "1"
    return SyncConfig(
        enabled = enabled,
        serverBaseUrl = serverBaseUrl,
        authToken = authToken,
        syncUser = syncUser,
        pullIntervalSeconds = pullIntervalSeconds,
        syncApiKeys = syncApiKeys
    )
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
 * **Injection surface** (same parameter names as before; new params have defaults):
 * @param storage               Root [ServerPlatformStorage].  Tests pass a temp dir.
 * @param authConfig            Auth credentials.  Tests inject a known password.
 * @param oauthExchanger        GitHub / Google Workspace token exchanger.
 * @param syncConfig            Legacy env-driven sync config for the "default" user.
 * @param titleGeneratorFactory Factory for per-user [TitleGenerator].
 * @param chatEngineFactory     Factory for per-user [ChatEngine].
 * @param googleTokenVerifier   Google ID token verifier.  Tests pass a fake.
 * @param syncAuthClient        Sync-server exchanger.  Tests pass a fake.
 * @param startRegistry         When false, the registry's sync engine is never
 *                              started (test isolation flag — mirrors [SyncConfig.startEngine]).
 * @param allowedGoogleEmails   Allowlist for Google login.  Empty = allow all.
 */
fun Application.module(
    storage: ServerPlatformStorage = ServerPlatformStorage(),
    authConfig: AuthConfig = AuthConfig(password = resolvePassword()),
    oauthExchanger: com.example.ApI.server.oauth.OAuthTokenExchanger? = null,
    syncConfig: SyncConfig = resolveSyncConfig(),
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

    // ── Legacy sync bootstrap (seeds the "default" user's AppSettings) ────────
    applySyncConfig(registry, syncConfig)

    // ── Models cache bootstrap ───────────────────────────────────────────────
    // Mirrors the Android app startup: fetch the GitHub-backed models.json into
    // the local cache (no-op while the 24h cache is still valid).
    launch(Dispatchers.IO) {
        val defaultRepo = registry.context("default").repository
        runCatching { defaultRepo.refreshModelsIfNeeded() }
            .onFailure { log.warn("Startup models refresh failed: ${it.message}") }
    }

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
    configureRouting(authConfig, allowedGoogleEmails)
}

// ── Legacy remote sync bootstrap helper ────────────────────────────────────────

/**
 * Seeds [syncConfig] into the **"default" user's** AppSettings and, when enabled,
 * starts the sync engine and a periodic pull loop for that user.
 *
 * This function is kept for Step 5a backward compatibility — it allows existing
 * deployments driven by `SYNC_ENABLED/SYNC_TOKEN/SYNC_USER` env vars to continue
 * working without any env changes.  It will be removed in Step 5b.
 *
 * The "default" user context is created synchronously (via [runBlocking]) so the
 * config is written before the server begins handling requests.
 */
private fun Application.applySyncConfig(registry: UserRegistry, syncConfig: SyncConfig) {
    if (!syncConfig.enabled) return

    if (syncConfig.authToken.isBlank()) {
        log.warn(
            "SYNC_ENABLED=true but SYNC_TOKEN is blank — remote sync will NOT start. " +
            "Set SYNC_TOKEN in server/deploy/llm-web.env to enable sync."
        )
        return
    }

    // Acquire the "default" user's repository synchronously at startup.
    val defaultRepo = runBlocking { registry.context("default").repository }

    val current = defaultRepo.loadAppSettings()
    val updated = current.copy(
        remoteSync = RemoteSyncSettings(
            enabled = true,
            serverBaseUrl = syncConfig.serverBaseUrl,
            authToken = syncConfig.authToken,
            syncApiKeys = syncConfig.syncApiKeys
        ),
        current_user = syncConfig.syncUser ?: current.current_user
    )
    defaultRepo.saveAppSettings(updated)
    log.info(
        "Remote sync configured for 'default' user: url=${syncConfig.serverBaseUrl}, " +
        "user=${updated.current_user}, interval=${syncConfig.pullIntervalSeconds}s"
    )

    if (!syncConfig.startEngine) return  // test mode: config seeded, engine not started

    defaultRepo.startSync()
    log.info("Remote sync engine started — triggering initial pull...")
    defaultRepo.pullNow()

    // Periodic pull loop for the "default" user (compensates for the server having
    // no "window focus" event).  Uses its own CoroutineScope so it can be cancelled
    // cleanly on shutdown.  The registry's stopAll() does NOT cancel this scope; we
    // subscribe to ApplicationStopping directly to match the pre-5a behaviour.
    val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    environment.monitor.subscribe(ApplicationStopping) {
        log.info("Stopping legacy default-user sync pull loop")
        syncScope.cancel()
    }
    syncScope.launch {
        while (isActive) {
            delay(syncConfig.pullIntervalSeconds * 1000L)
            try {
                defaultRepo.pullNow()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log.warn("Periodic sync pull failed: ${e.message}")
            }
        }
    }
    log.info("Periodic sync pull loop started (interval=${syncConfig.pullIntervalSeconds}s)")
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
