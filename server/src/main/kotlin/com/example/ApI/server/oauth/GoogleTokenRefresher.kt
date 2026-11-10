package com.example.ApI.server.oauth

import com.example.ApI.server.resolveGoogleClientId
import com.example.ApI.server.resolveGoogleClientSecret
import com.example.ApI.util.JsonConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What a refresh of a Google Workspace access token returns. */
data class RefreshedGoogleToken(
    val accessToken: String,
    /** Unix time (ms) at which [accessToken] expires. */
    val expiresAt: Long,
    /** The granted scopes, when Google names them. */
    val scopes: List<String>? = null,
    /** A new refresh token, when Google rotated it. */
    val refreshToken: String? = null
)

/**
 * Google refused the refresh token itself (revoked, expired, issued to another OAuth client, or
 * not a refresh token at all): trying it again is pointless.
 */
class GoogleRefreshRejectedException(message: String) : Exception(message)

/**
 * Seam for refreshing a Google Workspace access token with its refresh token, so tests never
 * call Google.  A failure that is a [GoogleRefreshRejectedException] is final for that refresh
 * token; any other failure (network, 5xx, not configured) may be retried later.
 */
fun interface GoogleTokenRefresher {
    suspend fun refresh(refreshToken: String): Result<RefreshedGoogleToken>
}

@Serializable
private data class GoogleRefreshResponse(
    val access_token: String? = null,
    val expires_in: Long? = null,
    val refresh_token: String? = null,
    val scope: String? = null,
    val error: String? = null,
    val error_description: String? = null
)

/**
 * The real refresh: `grant_type=refresh_token` at Google's token endpoint with this server's
 * OAuth client (`GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET`).  Only a refresh token
 * that was issued to this client can be refreshed — i.e. a connection made through the web's
 * `/oauth/google/start`.
 */
class RealGoogleTokenRefresher(
    private val clientId: () -> String = ::resolveGoogleClientId,
    private val clientSecret: () -> String = ::resolveGoogleClientSecret
) : GoogleTokenRefresher {

    override suspend fun refresh(refreshToken: String): Result<RefreshedGoogleToken> = withContext(Dispatchers.IO) {
        val id = clientId()
        val secret = clientSecret()
        if (id.isBlank() || secret.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Google OAuth is not configured on this server"))
        }
        try {
            val conn = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            // Short: a run's start request waits for this
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val body = buildString {
                append("client_id=${URLEncoder.encode(id, "UTF-8")}")
                append("&client_secret=${URLEncoder.encode(secret, "UTF-8")}")
                append("&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}")
                append("&grant_type=refresh_token")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body); it.flush() }

            val status = conn.responseCode
            val text = if (status in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
            else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            val response = try {
                JsonConfig.standard.decodeFromString<GoogleRefreshResponse>(text)
            } catch (_: Exception) {
                GoogleRefreshResponse()
            }

            // 400 invalid_grant / 401 invalid_client: the token (or the client) is refused for good
            if (status == 400 || status == 401) {
                return@withContext Result.failure(
                    GoogleRefreshRejectedException("Google refused the refresh token: ${response.error ?: "HTTP $status"}")
                )
            }
            if (status !in 200..299) {
                return@withContext Result.failure(Exception("Google token refresh failed: HTTP $status"))
            }
            val accessToken = response.access_token?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(Exception("Google token refresh: no access_token in the response"))
            Result.success(
                RefreshedGoogleToken(
                    accessToken = accessToken,
                    expiresAt = System.currentTimeMillis() + (response.expires_in ?: 3600L) * 1000L,
                    scopes = response.scope?.split(" ")?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
                    refreshToken = response.refresh_token?.takeIf { it.isNotBlank() }
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
