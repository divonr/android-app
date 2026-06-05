package com.example.ApI.server

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.EnabledGoogleServices
import com.example.ApI.data.model.GitHubConnection
import com.example.ApI.data.model.GoogleWorkspaceConnection
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom

// ── Max upload size ───────────────────────────────────────────────────────────
private const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024  // 50 MB

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

@Serializable
data class DeleteFileRequest(
    val filePath: String
)

// ── File routes (inside authenticated /api block) ─────────────────────────────

fun Route.fileRoutes() {

    // POST /api/files/upload — multipart/form-data, returns Attachment
    post("/files/upload") {
        val repo = call.application.appModule.repository

        val multipart = call.receiveMultipart()
        var fileName: String? = null
        var mimeType: String? = null
        var fileBytes: ByteArray? = null
        var tooLarge = false

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    if (!tooLarge) {
                        fileName = part.originalFileName?.ifBlank { null }
                            ?: part.name?.ifBlank { null }
                            ?: "upload"
                        mimeType = part.contentType?.toString() ?: "application/octet-stream"
                        val bytes = part.streamProvider().use { it.readBytes() }
                        if (bytes.size.toLong() > MAX_UPLOAD_BYTES) {
                            tooLarge = true
                        } else {
                            fileBytes = bytes
                        }
                    }
                }
                is PartData.FormItem -> {
                    // Optional form fields (provider, chatId) — accepted but not used here;
                    // provider upload happens at send time inside sendMessage.
                }
                else -> {}
            }
            part.dispose()
        }

        if (tooLarge) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                mapOf("error" to "File exceeds maximum allowed size (50 MB)")
            )
            return@post
        }

        val bytes = fileBytes
        if (bytes == null || bytes.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file content received"))
            return@post
        }

        val name = fileName ?: "upload"
        val mime = mimeType ?: "application/octet-stream"

        val localPath = repo.saveFileLocally(name, bytes)
        if (localPath == null) {
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to save file locally")
            )
            return@post
        }

        val attachment = Attachment(
            local_file_path = localPath,
            file_name = name,
            mime_type = mime
        )
        call.respond(HttpStatusCode.Created, attachment)
    }

    // DELETE /api/files — delete a locally saved file
    delete("/files") {
        val body = call.receive<DeleteFileRequest>()
        val filePath = body.filePath
        if (filePath.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "filePath is required"))
            return@delete
        }
        val file = File(filePath)
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
            return@delete
        }
        file.delete()
        call.respond(HttpStatusCode.NoContent)
    }
}

// ── Integration routes (inside authenticated /api block) ──────────────────────

fun Route.integrationRoutes() {

    // GET /api/integrations — combined status for all integrations
    get("/integrations") {
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
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
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val conn = repo.loadGitHubConnection(username)
        if (conn == null) {
            call.respond(HttpStatusCode.OK, mapOf<String, String>())  // empty object signals "not connected"
        } else {
            call.respond(HttpStatusCode.OK, conn)
        }
    }

    // DELETE /api/integrations/github — disconnect GitHub
    delete("/integrations/github") {
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        repo.removeGitHubConnection(username)
        call.respond(HttpStatusCode.NoContent)
    }

    // POST /api/integrations/github/start — returns authorize URL, stores CSRF state in session
    post("/integrations/github/start") {
        val session = call.sessions.get<UserSession>()
            ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "unauthorized")
            )

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

        call.respond(HttpStatusCode.OK, OAuthStartResponse(authorizeUrl = authorizeUrl))
    }

    // GET /api/integrations/google — Google connection or null
    get("/integrations/google") {
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val conn = repo.loadGoogleWorkspaceConnection(username)
        if (conn == null) {
            call.respond(HttpStatusCode.OK, mapOf<String, String>())
        } else {
            call.respond(HttpStatusCode.OK, conn)
        }
    }

    // DELETE /api/integrations/google — disconnect Google
    delete("/integrations/google") {
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        repo.removeGoogleWorkspaceConnection(username)
        call.respond(HttpStatusCode.NoContent)
    }

    // POST /api/integrations/google/start — returns authorize URL, stores CSRF state in session
    post("/integrations/google/start") {
        val session = call.sessions.get<UserSession>()
            ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "unauthorized")
            )

        val clientId = resolveGoogleClientId()
        if (clientId.isBlank()) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Google OAuth not configured — set GOOGLE_OAUTH_CLIENT_ID and GOOGLE_OAUTH_CLIENT_SECRET")
            )
            return@post
        }

        val state = generateOAuthState()
        call.sessions.set(session.copy(googleOAuthState = state))

        val baseUrl = resolvePublicBaseUrl()
        val redirectUri = "$baseUrl/oauth/google/callback"
        // Scopes from desktop GoogleWorkspaceAuthService companion (SCOPE_GMAIL_MODIFY, SCOPE_CALENDAR, SCOPE_DRIVE_FILE)
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

        call.respond(HttpStatusCode.OK, OAuthStartResponse(authorizeUrl = authorizeUrl))
    }

    // PATCH /api/integrations/google/services — update enabled Google services
    patch("/integrations/google/services") {
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val services = call.receive<EnabledGoogleServices>()
        repo.updateGoogleWorkspaceEnabledServices(username, services)
        call.respond(HttpStatusCode.OK, services)
    }
}

// ── PUBLIC OAuth callback routes (outside auth block) ─────────────────────────

/**
 * Registers the public OAuth callback routes at `/oauth/github/callback` and
 * `/oauth/google/callback`.  Called from the root routing block (NOT inside
 * `authenticate { }`), because GitHub/Google redirect the browser directly here.
 *
 * CSRF protection: a random [state] token is stored in the session by the
 * corresponding `/start` route and validated here before exchanging the code.
 */
fun Route.oauthCallbackRoutes() {

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

        val repo = call.application.appModule.repository
        val exchanger = call.application.appModule.oauthExchanger
        val username = call.currentUsername()

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

        val repo = call.application.appModule.repository
        val exchanger = call.application.appModule.oauthExchanger
        val username = call.currentUsername()

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
