package com.example.ApI.server.auth

import com.example.ApI.util.JsonConfig
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ── Data classes ─────────────────────────────────────────────────────────────

/**
 * Verified claims extracted from a Google ID token.
 *
 * @param sub    Stable, unique Google user identifier (primary key — never changes).
 * @param email  The user's Google account email address.
 * @param aud    The client id ("audience") the token was issued for.
 */
data class GoogleClaims(val sub: String, val email: String, val aud: String)

// ── Interface ─────────────────────────────────────────────────────────────────

/**
 * Seam interface for Google ID token verification.
 *
 * The real implementation calls Google's tokeninfo endpoint.  Tests inject a
 * fake that returns scripted [GoogleClaims] without network calls — same
 * pattern as [com.example.ApI.server.oauth.OAuthTokenExchanger].
 */
interface GoogleTokenVerifier {
    /**
     * Verifies [idToken] against Google's tokeninfo endpoint.
     *
     * Returns [GoogleClaims] on success, or null if the token is invalid,
     * expired, intended for a different audience, or the network call fails.
     */
    suspend fun verify(idToken: String): GoogleClaims?
}

// ── Real implementation ───────────────────────────────────────────────────────

/**
 * Real [GoogleTokenVerifier] that calls
 * `https://oauth2.googleapis.com/tokeninfo?id_token=...` via HttpURLConnection.
 *
 * Validates:
 * - `aud` must equal [expectedClientId] (if provided).
 * - `iss` must be `accounts.google.com` or `https://accounts.google.com`.
 *
 * @param expectedClientId  The OAuth client id expected in the token's `aud`
 *                          claim.  Pass an empty string to skip audience
 *                          validation (not recommended for production).
 */
class RealGoogleTokenVerifier(
    private val expectedClientId: String = resolveGoogleClientIdForVerifier()
) : GoogleTokenVerifier {

    override suspend fun verify(idToken: String): GoogleClaims? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = URL(
                    "https://oauth2.googleapis.com/tokeninfo" +
                    "?id_token=${URLEncoder.encode(idToken, "UTF-8")}"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                val code = conn.responseCode
                val body = if (code in 200..299)
                    conn.inputStream.bufferedReader().use { it.readText() }
                else
                    null

                if (body == null) return@withContext null

                val json = JsonConfig.standard.parseToJsonElement(body).jsonObject
                val iss = json["iss"]?.jsonPrimitive?.content ?: return@withContext null
                val aud = json["aud"]?.jsonPrimitive?.content ?: return@withContext null
                val sub = json["sub"]?.jsonPrimitive?.content ?: return@withContext null
                val email = json["email"]?.jsonPrimitive?.content ?: return@withContext null

                // Validate issuer
                val validIssuers = setOf("accounts.google.com", "https://accounts.google.com")
                if (iss !in validIssuers) return@withContext null

                // Validate audience if a client id is configured
                if (expectedClientId.isNotBlank() && aud != expectedClientId) {
                    return@withContext null
                }

                GoogleClaims(sub = sub, email = email, aud = aud)
            } catch (_: Exception) {
                null
            }
        }
}

// ── Env helper ────────────────────────────────────────────────────────────────

private fun resolveGoogleClientIdForVerifier(): String =
    System.getenv("GOOGLE_OAUTH_CLIENT_ID") ?: ""
