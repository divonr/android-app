package com.example.ApI.data.network

import android.util.Log
import com.example.ApI.data.sync.GoogleIdentity
import com.example.ApI.data.sync.GoogleSignInProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Desktop Google Sign-In implementation using the standard installed-app loopback redirect flow.
 *
 * Flow:
 *  1. Generate PKCE verifier/challenge (S256) + random state.
 *  2. Start a minimal HTTP listener on http://127.0.0.1:53682/.
 *  3. Open the system browser to Google's authorization endpoint.
 *  4. On callback: validate state, show "you can close this tab" page, exchange code for tokens.
 *  5. Decode the id_token JWT payload (Base64url) to extract email.
 *  6. Return GoogleIdentity(idToken, email). Timeout after 3 minutes.
 *
 * Client secret: read from env var GOOGLE_OAUTH_CLIENT_SECRET if set, else the bundled
 * constant GOOGLE_DESKTOP_CLIENT_SECRET (empty by default).  The user must supply the
 * secret via the env var until the constant is filled in — mirroring the GitHub desktop
 * precedent in GitHubOAuthService where CLIENT_SECRET is a bundled literal.
 *
 * NOTE (Step 7 / User action required): Add http://127.0.0.1:53682/ as an authorized
 * redirect URI in Google Cloud Console for OAuth client 926212364522-…
 */
class DesktopGoogleSignInProvider : GoogleSignInProvider {

    companion object {
        private const val TAG = "DesktopGoogleSignIn"

        const val CLIENT_ID = "926212364522-f5ppm0o9cj95te2rerborkdg62e8qlc4.apps.googleusercontent.com"

        /**
         * Bundled client secret — same pattern as [GitHubOAuthService.CLIENT_SECRET].
         * For an installed-app loopback flow the secret is not considered confidential
         * (it is shipped in the binary), but it is required by Google's token endpoint.
         *
         * Leave this empty and set the GOOGLE_OAUTH_CLIENT_SECRET environment variable
         * until a real value is available here.
         */
        private const val GOOGLE_DESKTOP_CLIENT_SECRET = "" // set env GOOGLE_OAUTH_CLIENT_SECRET or fill in here

        private const val REDIRECT_URI = "http://127.0.0.1:53682/"
        private const val PORT = 53682
        private const val TIMEOUT_MS = 3L * 60 * 1000 // 3 minutes
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun signIn(): Result<GoogleIdentity> = withContext(Dispatchers.IO) {
        val clientSecret = System.getenv("GOOGLE_OAUTH_CLIENT_SECRET")?.takeIf { it.isNotBlank() }
            ?: GOOGLE_DESKTOP_CLIENT_SECRET

        if (clientSecret.isBlank()) {
            return@withContext Result.failure(
                Exception(
                    "Google OAuth client secret is not configured. " +
                    "Set the GOOGLE_OAUTH_CLIENT_SECRET environment variable."
                )
            )
        }

        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()

        val callbackDeferred = CompletableDeferred<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", PORT), 0)

        try {
            server.createContext("/") { exchange ->
                val query = exchange.requestURI.rawQuery ?: ""
                val params = parseQueryString(query)
                val html = """
                    <!DOCTYPE html>
                    <html><body style="font-family:sans-serif;padding:40px;text-align:center">
                    <h2>Authentication complete</h2>
                    <p>You can close this tab and return to the app.</p>
                    </body></html>
                """.trimIndent().toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, html.size.toLong())
                exchange.responseBody.use { it.write(html) }
                callbackDeferred.complete(params) // no-op if already completed
            }
            server.executor = null
            server.start()

            val authUrl = buildString {
                append(AUTH_ENDPOINT)
                append("?client_id=${URLEncoder.encode(CLIENT_ID, "UTF-8")}")
                append("&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}")
                append("&response_type=code")
                append("&scope=${URLEncoder.encode("openid email profile", "UTF-8")}")
                append("&code_challenge=${URLEncoder.encode(challenge, "UTF-8")}")
                append("&code_challenge_method=S256")
                append("&state=${URLEncoder.encode(state, "UTF-8")}")
                append("&access_type=online")
                append("&prompt=select_account")
            }

            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(authUrl))
                else Log.w(TAG, "java.awt.Desktop not supported — cannot open browser automatically")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open browser: ${e.message}")
            }

            // Suspend until the callback arrives or timeout expires
            val params = withTimeoutOrNull(TIMEOUT_MS) {
                callbackDeferred.await()
            } ?: return@withContext Result.failure(Exception("Sign-in timed out after 3 minutes"))

            // Validate state to prevent CSRF
            if (params["state"] != state) {
                return@withContext Result.failure(Exception("OAuth state mismatch — possible CSRF attack"))
            }

            // Check for OAuth error response
            val error = params["error"]
            if (error != null) {
                val desc = params["error_description"] ?: ""
                return@withContext Result.failure(Exception("OAuth error: $error — $desc"))
            }

            val code = params["code"]
                ?: return@withContext Result.failure(Exception("No authorization code in callback"))

            exchangeCodeForIdentity(code, verifier, clientSecret)
        } finally {
            server.stop(0)
        }
    }

    private fun exchangeCodeForIdentity(
        code: String,
        verifier: String,
        clientSecret: String
    ): Result<GoogleIdentity> {
        return try {
            val body = FormBody.Builder()
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .add("client_secret", clientSecret)
                .add("redirect_uri", REDIRECT_URI)
                .add("grant_type", "authorization_code")
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return Result.failure(
                    Exception("Token exchange failed: HTTP ${response.code} — $responseBody")
                )
            }

            val idToken = extractJsonString(responseBody, "id_token")
                ?: return Result.failure(Exception("No id_token in token response"))

            val email = extractEmailFromJwt(idToken)
                ?: return Result.failure(Exception("Could not extract email from id_token JWT"))

            Log.d(TAG, "Desktop sync sign-in succeeded for $email")
            Result.success(GoogleIdentity(idToken = idToken, email = email))
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
            Result.failure(e)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Generate a random PKCE code verifier (32 random bytes, base64url-encoded). */
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Compute the PKCE S256 code challenge: BASE64URL(SHA256(ASCII(verifier))). */
    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** Generate a random opaque state value for CSRF protection. */
    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@mapNotNull null
            URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
    }

    /**
     * Minimal JSON string-field extractor.
     * Sufficient for id_token (a dot-separated base64url string with no quotes) and email.
     */
    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    /**
     * Decode the JWT payload (second dot-separated segment, base64url) and return the
     * "email" claim.  No signature verification — the sync server verifies the token.
     */
    private fun extractEmailFromJwt(jwt: String): String? {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            // Base64url may omit padding — restore it before decoding
            val segment = parts[1].let { s ->
                s + "=".repeat((4 - s.length % 4) % 4)
            }
            val payload = String(Base64.getUrlDecoder().decode(segment), Charsets.UTF_8)
            extractJsonString(payload, "email")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode JWT payload", e)
            null
        }
    }
}
