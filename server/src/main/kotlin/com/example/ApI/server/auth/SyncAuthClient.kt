package com.example.ApI.server.auth

import com.example.ApI.util.JsonConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// ── Data classes ─────────────────────────────────────────────────────────────

/**
 * Successful result from the sync server's `/auth/google` endpoint.
 *
 * @param token     Long-lived bearer token minted by the sync server.
 * @param username  Canonical username derived by the sync server (single
 *                  source of truth — sanitized email, e.g. `haravsihot_gmail_com`).
 * @param email     The Google account email address.
 */
data class SyncAuthResult(val token: String, val username: String, val email: String)

// ── Interface ─────────────────────────────────────────────────────────────────

/**
 * Seam interface for exchanging a Google ID token with the sync server.
 *
 * The real implementation calls `POST {SYNC_SERVER_URL}/auth/google`.
 * Tests inject a fake — the same injection pattern used for
 * [com.example.ApI.server.oauth.OAuthTokenExchanger] and
 * [GoogleTokenVerifier].
 */
interface SyncAuthClient {
    /**
     * Exchanges [idToken] with the sync server.
     *
     * Returns a [SyncAuthResult] on success, or null when the server is
     * unavailable or rejects the token.
     */
    suspend fun exchange(idToken: String): SyncAuthResult?
}

// ── Request / response DTOs ───────────────────────────────────────────────────

@Serializable
private data class SyncAuthRequest(val id_token: String)

// ── Real implementation ───────────────────────────────────────────────────────

/**
 * Real [SyncAuthClient] that posts the Google ID token to the sync server.
 *
 * @param syncServerUrl  Base URL of the sync server (default `http://localhost:8090`).
 */
class RealSyncAuthClient(
    private val syncServerUrl: String = System.getenv("SYNC_SERVER_URL")
        ?.takeIf { it.isNotBlank() } ?: "http://localhost:8090"
) : SyncAuthClient {

    override suspend fun exchange(idToken: String): SyncAuthResult? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = URL("$syncServerUrl/auth/google")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                val requestBody = JsonConfig.standard.encodeToString(
                    SyncAuthRequest.serializer(),
                    SyncAuthRequest(id_token = idToken)
                )
                OutputStreamWriter(conn.outputStream).use { it.write(requestBody); it.flush() }

                val code = conn.responseCode
                val body = if (code in 200..299)
                    conn.inputStream.bufferedReader().use { it.readText() }
                else
                    null

                if (body == null) return@withContext null

                val json = JsonConfig.standard.parseToJsonElement(body).jsonObject
                val token = json["token"]?.jsonPrimitive?.content ?: return@withContext null
                val username = json["username"]?.jsonPrimitive?.content ?: return@withContext null
                val email = json["email"]?.jsonPrimitive?.content ?: return@withContext null

                SyncAuthResult(token = token, username = username, email = email)
            } catch (_: Exception) {
                null
            }
        }
}

// ── Test fake ─────────────────────────────────────────────────────────────────

/**
 * Fake [SyncAuthClient] for tests.
 *
 * Derives a deterministic username by sanitizing the email exactly as the
 * sync server would: lowercase, every character outside `[a-z0-9]` → `_`.
 *
 * @param tokenSuffix  Appended to "fake-token-" to form the minted token.
 * @param alwaysNull   When true, simulates an unavailable sync server.
 */
class FakeSyncAuthClient(
    private val tokenSuffix: String = "test",
    private val alwaysNull: Boolean = false
) : SyncAuthClient {

    override suspend fun exchange(idToken: String): SyncAuthResult? {
        if (alwaysNull) return null
        // The fake verifier is expected to populate claims before exchange is
        // called; extract email from the token string by convention in tests.
        // Here we use a deterministic mapping: return a result for the caller
        // to identify.  Callers in GoogleLoginTest pass a parseable fake token.
        val email = parseFakeTokenEmail(idToken)
        val username = sanitizeEmail(email)
        return SyncAuthResult(
            token = "fake-token-$tokenSuffix",
            username = username,
            email = email
        )
    }

    companion object {
        /** Sanitize an email to a canonical username (mirrors sync server logic). */
        fun sanitizeEmail(email: String): String =
            email.lowercase().map { c ->
                if (c in 'a'..'z' || c in '0'..'9') c else '_'
            }.joinToString("")

        /**
         * Convention used by tests: fake ID tokens are structured as
         * `"fake-id-token-{email}"` so the fake client can extract the email.
         */
        fun parseFakeTokenEmail(idToken: String): String =
            if (idToken.startsWith("fake-id-token-")) idToken.removePrefix("fake-id-token-")
            else "unknown_user_example_com"
    }
}
