package com.example.ApI.data.network

import android.content.Context
import android.util.Log
import com.example.ApI.data.model.GitHubAuth
import com.example.ApI.data.model.GitHubOAuthConfig
import com.example.ApI.util.JsonConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.awt.Desktop
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * Service for handling GitHub OAuth authentication flow.
 * Desktop adaptation: opens system browser instead of Android Intent.
 */
class GitHubOAuthService(private val context: Context) {

    companion object {
        private const val TAG = "GitHubOAuthService"
        private const val STATE_LENGTH = 32
        private const val CLIENT_ID = "Ov23liIqbBxkhRQcaTn1"
        private const val CLIENT_SECRET = "6b2e01569404a3ea854e5bb4187d63ff9316f59d"
    }

    private val config = GitHubOAuthConfig(
        clientId = CLIENT_ID,
        clientSecret = CLIENT_SECRET,
        redirectUri = GitHubOAuthConfig.DEFAULT_REDIRECT_URI
    )

    fun generateState(): String {
        val random = SecureRandom()
        val bytes = ByteArray(STATE_LENGTH)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Desktop: opens system browser for OAuth. Returns state for verification. */
    fun startAuthorizationFlow(): String {
        val state = generateState()
        val authUrl = config.getAuthorizationUrl(state)
        Log.d(TAG, "Starting OAuth flow with state: $state")
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(authUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open browser", e)
        }
        return state
    }

    fun getAuthorizationUrlAndState(): Pair<String, String> {
        val state = generateState()
        val authUrl = config.getAuthorizationUrl(state)
        return Pair(authUrl, state)
    }

    suspend fun exchangeCodeForToken(code: String): Result<GitHubAuth> = withContext(Dispatchers.IO) {
        try {
            val url = URL(GitHubOAuthConfig.ACCESS_TOKEN_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            val requestBody = buildString {
                append("client_id=${URLEncoder.encode(config.clientId, "UTF-8")}")
                append("&client_secret=${URLEncoder.encode(config.clientSecret, "UTF-8")}")
                append("&code=${URLEncoder.encode(code, "UTF-8")}")
                append("&redirect_uri=${URLEncoder.encode(config.redirectUri, "UTF-8")}")
            }
            OutputStreamWriter(connection.outputStream).use { it.write(requestBody); it.flush() }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) connection.inputStream.bufferedReader().use { it.readText() }
            else connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""

            if (responseCode in 200..299) {
                val tokenResponse = JsonConfig.standard.decodeFromString<TokenResponse>(responseBody)
                tokenResponse.error?.let { return@withContext Result.failure(Exception("OAuth error: $it - ${tokenResponse.errorDescription}")) }
                val auth = GitHubAuth(
                    accessToken = tokenResponse.accessToken ?: return@withContext Result.failure(Exception("No access token")),
                    tokenType = tokenResponse.tokenType ?: "bearer",
                    scope = tokenResponse.scope ?: "",
                    expiresIn = tokenResponse.expiresIn,
                    refreshToken = tokenResponse.refreshToken,
                    refreshTokenExpiresIn = tokenResponse.refreshTokenExpiresIn
                )
                Result.success(auth)
            } else {
                Result.failure(Exception("Token exchange failed: HTTP $responseCode"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun revokeToken(accessToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val credentials = "${config.clientId}:${config.clientSecret}"
            val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())
            val url = URL("https://api.github.com/applications/${config.clientId}/token")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Authorization", "Basic $encodedCredentials")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            OutputStreamWriter(connection.outputStream).use { it.write("{\"access_token\":\"$accessToken\"}"); it.flush() }
            val responseCode = connection.responseCode
            if (responseCode in 200..299 || responseCode == 404) Result.success(Unit)
            else Result.failure(Exception("Token revocation failed: HTTP $responseCode"))
        } catch (e: Exception) { Result.failure(e) }
    }

    @Serializable
    private data class TokenResponse(
        val access_token: String? = null,
        val token_type: String? = null,
        val scope: String? = null,
        val expires_in: Long? = null,
        val refresh_token: String? = null,
        val refresh_token_expires_in: Long? = null,
        val error: String? = null,
        val error_description: String? = null,
        val error_uri: String? = null
    ) {
        val accessToken get() = access_token
        val tokenType get() = token_type
        val expiresIn get() = expires_in
        val refreshToken get() = refresh_token
        val refreshTokenExpiresIn get() = refresh_token_expires_in
        val errorDescription get() = error_description
    }
}
