package com.example.ApI.server

import com.example.ApI.data.model.EnabledGoogleServices
import com.example.ApI.data.model.GitHubConnection
import com.example.ApI.data.model.GoogleWorkspaceConnection
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.security.SecureRandom

// ── DTOs ─────────────────────────────────────────────────────────────────────

@Serializable
data class IntegrationsStatusResponse(
    val github: GitHubConnection?,
    val googleWorkspace: GoogleWorkspaceConnection?,
    val isGitHubConnected: Boolean,
    val isGoogleWorkspaceConnected: Boolean
)

@Serializable
data class OAuthStartResponse(
    val authorizeUrl: String
)

// ── Integration routes (inside authenticated /api block) ──────────────────────

fun Route.integrationRoutes() {

    // GET /api/integrations — combined status for all integrations
    get("/integrations") {
        val ctx = call.userContext()
        val repo = ctx.repository
        val username = ctx.username
        val github = repo.loadGitHubConnection(username)
        val google = repo.loadGoogleWorkspaceConnection(username)
        call.respond(
            HttpStatusCode.OK,
            IntegrationsStatusResponse(
                github = github,
                googleWorkspace = google,
                isGitHubConnected = repo.isGitHubConnected(username),
                isGoogleWorkspaceConnected = repo.isGoogleWorkspaceConnected(username)
            )
        )
    }

    // GET /api/integrations/github — GitHub connection or null
    get("/integrations/github") {
        val ctx = call.userContext()
        val repo = ctx.repository
        val username = ctx.username
        val conn = repo.loadGitHubConnection(username)
        if (conn == null) {
            call.respond(HttpStatusCode.OK, mapOf<String, String>())  // empty object signals "not connected"
        } else {
            call.respond(HttpStatusCode.OK, conn)
        }
    }

    // DELETE /api/integrations/github — disconnect GitHub
    delete("/integrations/github") {
        val ctx = call.userContext()
        val repo = ctx.repository
        val username = ctx.username
        repo.removeGitHubConnection(username)
        call.respond(HttpStatusCode.NoContent)
    }

    // (POST /api/integrations/github/start removed — use GET /oauth/github/start instead)

    // GET /api/integrations/google — Google connection or null
    get("/integrations/google") {
        val ctx = call.userContext()
        val repo = ctx.repository
        val username = ctx.username
        val conn = repo.loadGoogleWorkspaceConnection(username)
        if (conn == null) {
            call.respond(HttpStatusCode.OK, mapOf<String, String>())
        } else {
            call.respond(HttpStatusCode.OK, conn)
        }
    }

    // DELETE /api/integrations/google — disconnect Google
    delete("/integrations/google") {
        val ctx = call.userContext()
        val repo = ctx.repository
        val username = ctx.username
        repo.removeGoogleWorkspaceConnection(username)
        call.respond(HttpStatusCode.NoContent)
    }

    // (POST /api/integrations/google/start removed — use GET /oauth/google/start instead)

    // PATCH /api/integrations/google/services — update enabled Google services
    patch("/integrations/google/services") {
        val ctx = call.userContext()
        val repo = ctx.repository
        val username = ctx.username
        val services = call.receive<EnabledGoogleServices>()
        repo.updateGoogleWorkspaceEnabledServices(username, services)
        call.respond(HttpStatusCode.OK, services)
    }
}

// ── PUBLIC OAuth callback routes (outside auth block) ─────────────────────────

/**
 * Registers the public OAuth start + callback routes at `/oauth/{provider}/start`
 * and `/oauth/{provider}/callback`.  Called from the root routing block (NOT inside
 * `authenticate { }`), because the browser navigates to these URLs directly.
 *
 * `/start` validates the session manually (no Ktor auth plugin — the session
 * cookie IS sent by the browser during top-level navigation).  Unauthenticated
 * requests are redirected to `/login`.
 *
 * CSRF protection: a random [state] token is stored in the session by the
 * `/start` route and validated in the corresponding `/callback` route.
 */
fun Route.oauthCallbackRoutes() {

    // GET /oauth/github/start — redirect to GitHub authorize URL
    get("/oauth/github/start") {
        val session = call.sessions.get<UserSession>()
        if (session == null || !session.authenticated) {
            call.respondRedirect("/login")
            return@get
        }

        val state = generateOAuthState()
        call.sessions.set(session.copy(githubOAuthState = state))

        val clientId = resolveGitHubClientId()
        val baseUrl = resolvePublicBaseUrl()
        val redirectUri = "$baseUrl/oauth/github/callback"
        val scopes = "repo,read:user,user:email,read:org"

        val authorizeUrl = buildString {
            append("https://github.com/login/oauth/authorize")
            append("?client_id=").append(clientId)
            append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
            append("&scope=").append(URLEncoder.encode(scopes, "UTF-8"))
            append("&state=").append(state)
        }

        call.respondRedirect(authorizeUrl)
    }

    // GET /oauth/google/start — redirect to Google authorize URL
    get("/oauth/google/start") {
        val session = call.sessions.get<UserSession>()
        if (session == null || !session.authenticated) {
            call.respondRedirect("/login")
            return@get
        }

        val clientId = resolveGoogleClientId()
        if (clientId.isBlank()) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Google OAuth not configured — set GOOGLE_OAUTH_CLIENT_ID and GOOGLE_OAUTH_CLIENT_SECRET")
            )
            return@get
        }

        val state = generateOAuthState()
        call.sessions.set(session.copy(googleOAuthState = state))

        val baseUrl = resolvePublicBaseUrl()
        val redirectUri = "$baseUrl/oauth/google/callback"
        val scopes = listOf(
            "https://www.googleapis.com/auth/gmail.modify",
            "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/auth/drive.file",
            "openid",
            "email",
            "profile"
        ).joinToString(" ")

        val authorizeUrl = buildString {
            append("https://accounts.google.com/o/oauth2/v2/auth")
            append("?client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
            append("&response_type=code")
            append("&scope=").append(URLEncoder.encode(scopes, "UTF-8"))
            append("&state=").append(state)
            append("&access_type=offline")
            append("&prompt=consent")
        }

        call.respondRedirect(authorizeUrl)
    }

    // GET /oauth/github/callback?code=&state=
    get("/oauth/github/callback") {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]

        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing code or state parameter")
            )
            return@get
        }

        val session = call.sessions.get<UserSession>()
        if (session == null || session.githubOAuthState == null || session.githubOAuthState != state) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid or missing OAuth state — please restart the authorization flow")
            )
            return@get
        }

        // Consume the state token (one-time use)
        call.sessions.set(session.copy(githubOAuthState = null))

        val ctx = call.application.appModule.registry.context(session.username)
        val repo = ctx.repository
        val username = ctx.username
        val exchanger = call.application.appModule.oauthExchanger

        val clientId = resolveGitHubClientId()
        val clientSecret = resolveGitHubClientSecret()
        val baseUrl = resolvePublicBaseUrl()
        val redirectUri = "$baseUrl/oauth/github/callback"

        val result = exchanger.exchangeGitHub(code, clientId, clientSecret, redirectUri)
        if (result.isFailure) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Token exchange failed: ${result.exceptionOrNull()?.message}")
            )
            return@get
        }

        val (auth, user) = result.getOrThrow()
        val connection = GitHubConnection(auth = auth, user = user)
        repo.saveGitHubConnection(username, connection)

        call.respondRedirect("/integrations?github=connected")
    }

    // GET /oauth/google/callback?code=&state=
    get("/oauth/google/callback") {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]

        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing code or state parameter")
            )
            return@get
        }

        val session = call.sessions.get<UserSession>()
        if (session == null || session.googleOAuthState == null || session.googleOAuthState != state) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid or missing OAuth state — please restart the authorization flow")
            )
            return@get
        }

        // Consume the state token (one-time use)
        call.sessions.set(session.copy(googleOAuthState = null))

        val ctx = call.application.appModule.registry.context(session.username)
        val repo = ctx.repository
        val username = ctx.username
        val exchanger = call.application.appModule.oauthExchanger

        val clientId = resolveGoogleClientId()
        val clientSecret = resolveGoogleClientSecret()
        val baseUrl = resolvePublicBaseUrl()
        val redirectUri = "$baseUrl/oauth/google/callback"

        if (clientId.isBlank() || clientSecret.isBlank()) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Google OAuth not configured on this server")
            )
            return@get
        }

        val result = exchanger.exchangeGoogle(code, clientId, clientSecret, redirectUri)
        if (result.isFailure) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Token exchange failed: ${result.exceptionOrNull()?.message}")
            )
            return@get
        }

        val (auth, user) = result.getOrThrow()
        val connection = GoogleWorkspaceConnection(auth = auth, user = user)
        repo.saveGoogleWorkspaceConnection(username, connection)

        call.respondRedirect("/integrations?google=connected")
    }
}

// ── Helper ────────────────────────────────────────────────────────────────────

/** Generate a cryptographically random hex state token for CSRF protection. */
fun generateOAuthState(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
