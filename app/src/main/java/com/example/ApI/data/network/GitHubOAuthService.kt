package com.example.ApI.data.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.ApI.data.model.GitHubAuth
import com.example.ApI.data.model.GitHubOAuthConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Service for handling GitHub OAuth authentication flow
 */
class GitHubOAuthService(private val context: Context) {

    companion object {
        private const val TAG = "GitHubOAuthService"
        private const val STATE_LENGTH = 32

        // GitHub OAuth app credentials for "ApI"
        // Registered at: https://github.com/settings/developers
        private const val CLIENT_ID = "Ov23liIqbBxkhRQcaTn1"
        private const val CLIENT_SECRET = "6b2e01569404a3ea854e5bb4187d63ff9316f59d"
    }

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val config = GitHubOAuthConfig(
        clientId = CLIENT_ID,
        clientSecret = CLIENT_SECRET,
        redirectUri = GitHubOAuthConfig.DEFAULT_REDIRECT_URI
    )

    /**
     * Generate a random state parameter for OAuth security
     */
    fun generateState(): String {
        val random = SecureRandom()
        val bytes = ByteArray(STATE_LENGTH)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Start the OAuth authorization flow
     * Opens the browser to GitHub authorization page
     * @return The state parameter used for verification
     */
    fun startAuthorizationFlow(): String {
        val state = generateState()
        val authUrl = config.getAuthorizationUrl(state)

        Log.d(TAG, "Starting OAuth flow with state: $state")
        Log.d(TAG, "Authorization URL: $authUrl")

        // Open browser with authorization URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)

        return state
    }

    /**
     * Exchange authorization code for access token
     * @param code The authorization code from the callback
     * @return Result containing GitHubAuth or error
     */
    suspend fun exchangeCodeForToken(code: String): Result<GitHubAuth> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Exchanging code for token")

            val url = URL(GitHubOAuthConfig.ACCESS_TOKEN_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            // Build request body
            val requestBody = buildString {
                append("client_id=${URLEncoder.encode(config.clientId, "UTF-8")}")
                append("&client_secret=${URLEncoder.encode(config.clientSecret, "UTF-8")}")
                append("&code=${URLEncoder.encode(code, "UTF-8")}")
                append("&redirect_uri=${URLEncoder.encode(config.redirectUri, "UTF-8")}")
            }

            // Write request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            Log.d(TAG, "Token exchange response: $responseCode")

            if (responseCode in 200..299) {
                val tokenResponse = json.decodeFromString<TokenResponse>(responseBody)

                // Check for error in response
                tokenResponse.error?.let { error ->
                    return@withContext Result.failure(Exception("OAuth error: $error - ${tokenResponse.errorDescription}"))
                }

                val auth = GitHubAuth(
                    accessToken = tokenResponse.accessToken ?: return@withContext Result.failure(
                        Exception("No access token in response")
                    ),
                    tokenType = tokenResponse.tokenType ?: "bearer",
                    scope = tokenResponse.scope ?: "",
                    expiresIn = tokenResponse.expiresIn,
                    refreshToken = tokenResponse.refreshToken,
                    refreshTokenExpiresIn = tokenResponse.refreshTokenExpiresIn
                )

                Log.d(TAG, "Successfully obtained access token")
                Result.success(auth)
            } else {
                Log.e(TAG, "Token exchange failed: $responseCode - $responseBody")
                Result.failure(Exception("Token exchange failed: HTTP $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exchanging code for token", e)
            Result.failure(e)
        }
    }

    /**
     * Refresh an expired access token
     * Note: GitHub OAuth apps don't currently support refresh tokens by default
     * User-to-server tokens don't expire. If you need refresh tokens, use GitHub Apps instead.
     * @param refreshToken The refresh token
     * @return Result containing new GitHubAuth or error
     */
    suspend fun refreshAccessToken(refreshToken: String): Result<GitHubAuth> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Refreshing access token")

            val url = URL(GitHubOAuthConfig.ACCESS_TOKEN_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            // Build request body
            val requestBody = buildString {
                append("client_id=${URLEncoder.encode(config.clientId, "UTF-8")}")
                append("&client_secret=${URLEncoder.encode(config.clientSecret, "UTF-8")}")
                append("&grant_type=refresh_token")
                append("&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}")
            }

            // Write request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            Log.d(TAG, "Token refresh response: $responseCode")

            if (responseCode in 200..299) {
                val tokenResponse = json.decodeFromString<TokenResponse>(responseBody)

                tokenResponse.error?.let { error ->
                    return@withContext Result.failure(Exception("OAuth error: $error - ${tokenResponse.errorDescription}"))
                }

                val auth = GitHubAuth(
                    accessToken = tokenResponse.accessToken ?: return@withContext Result.failure(
                        Exception("No access token in response")
                    ),
                    tokenType = tokenResponse.tokenType ?: "bearer",
                    scope = tokenResponse.scope ?: "",
                    expiresIn = tokenResponse.expiresIn,
                    refreshToken = tokenResponse.refreshToken ?: refreshToken, // Keep old refresh token if not provided
                    refreshTokenExpiresIn = tokenResponse.refreshTokenExpiresIn
                )

                Log.d(TAG, "Successfully refreshed access token")
                Result.success(auth)
            } else {
                Log.e(TAG, "Token refresh failed: $responseCode - $responseBody")
                Result.failure(Exception("Token refresh failed: HTTP $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing access token", e)
            Result.failure(e)
        }
    }

    /**
     * Revoke an access token
     */
    suspend fun revokeToken(accessToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Revoking access token")

            // GitHub uses basic auth with client_id:client_secret for token revocation
            val credentials = "${config.clientId}:${config.clientSecret}"
            val encodedCredentials = android.util.Base64.encodeToString(
                credentials.toByteArray(),
                android.util.Base64.NO_WRAP
            )

            val url = URL("https://api.github.com/applications/${config.clientId}/token")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Authorization", "Basic $encodedCredentials")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            // Build request body
            val requestBody = buildString {
                append("{")
                append("\"access_token\":\"$accessToken\"")
                append("}")
            }

            // Write request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Token revocation response: $responseCode")

            if (responseCode in 200..299 || responseCode == 404) {
                // 404 means token was already invalid/revoked
                Log.d(TAG, "Successfully revoked access token")
                Result.success(Unit)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Token revocation failed: $responseCode - $errorBody")
                Result.failure(Exception("Token revocation failed: HTTP $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error revoking access token", e)
            Result.failure(e)
        }
    }

    /**
     * Data class for GitHub OAuth token response
     */
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
        val errorUri get() = error_uri
    }
}
