package com.example.ApI.server.oauth

import com.example.ApI.data.model.GitHubAuth
import com.example.ApI.data.model.GitHubUser
import com.example.ApI.data.model.GoogleWorkspaceAuth
import com.example.ApI.data.model.GoogleWorkspaceUser
import com.example.ApI.util.JsonConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ── GitHub token-exchange response (mirrors GitHubOAuthService in desktop) ────

@Serializable
private data class GitHubTokenResponse(
    val access_token: String? = null,
    val token_type: String? = null,
    val scope: String? = null,
    val expires_in: Long? = null,
    val refresh_token: String? = null,
    val refresh_token_expires_in: Long? = null,
    val error: String? = null,
    val error_description: String? = null
)

// ── Google token-exchange response ───────────────────────────────────────────

@Serializable
private data class GoogleTokenResponse(
    val access_token: String? = null,
    val expires_in: Long? = null,
    val refresh_token: String? = null,
    val scope: String? = null,
    val token_type: String? = null,
    val error: String? = null,
    val error_description: String? = null
)

/**
 * Seam interface for OAuth token exchange — allows tests to inject a fake
 * without making real network calls.  The production implementation uses
 * plain HttpURLConnection (same as desktop's GitHubOAuthService).
 */
interface OAuthTokenExchanger {

    /** Exchange a GitHub OAuth code for a [GitHubAuth] + [GitHubUser]. */
    suspend fun exchangeGitHub(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): Result<Pair<GitHubAuth, GitHubUser>>

    /** Exchange a Google OAuth code for a [GoogleWorkspaceAuth] + [GoogleWorkspaceUser]. */
    suspend fun exchangeGoogle(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): Result<Pair<GoogleWorkspaceAuth, GoogleWorkspaceUser>>
}

/** Real implementation — makes network calls to GitHub and Google. */
class RealOAuthTokenExchanger : OAuthTokenExchanger {

    override suspend fun exchangeGitHub(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): Result<Pair<GitHubAuth, GitHubUser>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            // Step 1: exchange code for token
            val tokenUrl = URL("https://github.com/login/oauth/access_token")
            val tokenConn = tokenUrl.openConnection() as HttpURLConnection
            tokenConn.requestMethod = "POST"
            tokenConn.setRequestProperty("Accept", "application/json")
            tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            tokenConn.doOutput = true
            tokenConn.connectTimeout = 30_000
            tokenConn.readTimeout = 30_000

            val body = buildString {
                append("client_id=${URLEncoder.encode(clientId, "UTF-8")}")
                append("&client_secret=${URLEncoder.encode(clientSecret, "UTF-8")}")
                append("&code=${URLEncoder.encode(code, "UTF-8")}")
                append("&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}")
            }
            OutputStreamWriter(tokenConn.outputStream).use { it.write(body); it.flush() }

            val tokenCode = tokenConn.responseCode
            val tokenBody = if (tokenCode in 200..299)
                tokenConn.inputStream.bufferedReader().use { it.readText() }
            else
                tokenConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""

            if (tokenCode !in 200..299) {
                return@withContext Result.failure(Exception("GitHub token exchange failed: HTTP $tokenCode: $tokenBody"))
            }

            val tokenResp = JsonConfig.standard.decodeFromString<GitHubTokenResponse>(tokenBody)
            if (tokenResp.error != null) {
                return@withContext Result.failure(Exception("GitHub OAuth error: ${tokenResp.error} — ${tokenResp.error_description}"))
            }
            val accessToken = tokenResp.access_token
                ?: return@withContext Result.failure(Exception("GitHub: no access_token in response"))

            val auth = GitHubAuth(
                accessToken = accessToken,
                tokenType = tokenResp.token_type ?: "bearer",
                scope = tokenResp.scope ?: "",
                expiresIn = tokenResp.expires_in,
                refreshToken = tokenResp.refresh_token,
                refreshTokenExpiresIn = tokenResp.refresh_token_expires_in
            )

            // Step 2: fetch user profile
            val userUrl = URL("https://api.github.com/user")
            val userConn = userUrl.openConnection() as HttpURLConnection
            userConn.requestMethod = "GET"
            userConn.setRequestProperty("Authorization", "Bearer $accessToken")
            userConn.setRequestProperty("Accept", "application/vnd.github+json")
            userConn.connectTimeout = 30_000
            userConn.readTimeout = 30_000

            val userCode = userConn.responseCode
            val userBody = if (userCode in 200..299)
                userConn.inputStream.bufferedReader().use { it.readText() }
            else
                userConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""

            if (userCode !in 200..299) {
                return@withContext Result.failure(Exception("GitHub user fetch failed: HTTP $userCode: $userBody"))
            }

            val user = JsonConfig.standard.decodeFromString<GitHubUser>(userBody)
            Result.success(Pair(auth, user))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun exchangeGoogle(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): Result<Pair<GoogleWorkspaceAuth, GoogleWorkspaceUser>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            // Step 1: exchange code for token
            val tokenUrl = URL("https://oauth2.googleapis.com/token")
            val tokenConn = tokenUrl.openConnection() as HttpURLConnection
            tokenConn.requestMethod = "POST"
            tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            tokenConn.doOutput = true
            tokenConn.connectTimeout = 30_000
            tokenConn.readTimeout = 30_000

            val body = buildString {
                append("client_id=${URLEncoder.encode(clientId, "UTF-8")}")
                append("&client_secret=${URLEncoder.encode(clientSecret, "UTF-8")}")
                append("&code=${URLEncoder.encode(code, "UTF-8")}")
                append("&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}")
                append("&grant_type=authorization_code")
            }
            OutputStreamWriter(tokenConn.outputStream).use { it.write(body); it.flush() }

            val tokenCode = tokenConn.responseCode
            val tokenBody = if (tokenCode in 200..299)
                tokenConn.inputStream.bufferedReader().use { it.readText() }
            else
                tokenConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""

            if (tokenCode !in 200..299) {
                return@withContext Result.failure(Exception("Google token exchange failed: HTTP $tokenCode: $tokenBody"))
            }

            val tokenResp = JsonConfig.standard.decodeFromString<GoogleTokenResponse>(tokenBody)
            if (tokenResp.error != null) {
                return@withContext Result.failure(Exception("Google OAuth error: ${tokenResp.error} — ${tokenResp.error_description}"))
            }
            val accessToken = tokenResp.access_token
                ?: return@withContext Result.failure(Exception("Google: no access_token in response"))

            val expiresAt = System.currentTimeMillis() + ((tokenResp.expires_in ?: 3600L) * 1000L)
            val scopes = tokenResp.scope?.split(" ") ?: emptyList()

            val googleAuth = GoogleWorkspaceAuth(
                accessToken = accessToken,
                refreshToken = tokenResp.refresh_token,
                expiresAt = expiresAt,
                scopes = scopes
            )

            // Step 2: fetch user profile via userinfo endpoint
            val userUrl = URL("https://www.googleapis.com/oauth2/v2/userinfo")
            val userConn = userUrl.openConnection() as HttpURLConnection
            userConn.requestMethod = "GET"
            userConn.setRequestProperty("Authorization", "Bearer $accessToken")
            userConn.connectTimeout = 30_000
            userConn.readTimeout = 30_000

            val userCode = userConn.responseCode
            val userBody = if (userCode in 200..299)
                userConn.inputStream.bufferedReader().use { it.readText() }
            else
                userConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""

            if (userCode !in 200..299) {
                return@withContext Result.failure(Exception("Google userinfo fetch failed: HTTP $userCode: $userBody"))
            }

            // Parse the userinfo JSON manually to avoid needing another data class
            val json = JsonConfig.standard.parseToJsonElement(userBody).jsonObject
            val googleUser = GoogleWorkspaceUser(
                id = json["id"]?.jsonPrimitive?.content ?: "",
                email = json["email"]?.jsonPrimitive?.content ?: "",
                displayName = json["name"]?.jsonPrimitive?.content,
                photoUrl = json["picture"]?.jsonPrimitive?.content
            )

            Result.success(Pair(googleAuth, googleUser))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
