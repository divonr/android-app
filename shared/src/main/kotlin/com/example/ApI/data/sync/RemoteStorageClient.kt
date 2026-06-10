package com.example.ApI.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.io.IOException

// ───────────────────────── Data models ──────────────────────────────────────

@Serializable
data class BlobMeta(
    val filename: String,
    val updated_at: Long,
    val sha: String
)

@Serializable
data class RemoteBlob(
    val filename: String,
    val content: String,
    val updated_at: Long,
    val sha: String
)

@Serializable
private data class PutRequest(val content: String)

@Serializable
private data class AuthGoogleRequest(val id_token: String)

/**
 * Result of a successful `POST /auth/google` exchange.
 *
 * @param token    Server-minted opaque session token.  Store in [RemoteSyncSettings.authToken].
 * @param username Canonical username derived server-side from the Google `sub`/email.
 *                 Use as [AppSettings.current_user] after migration.
 * @param email    Google account email — display only.
 */
data class AuthResult(val token: String, val username: String, val email: String)

@Serializable
private data class AuthResultRaw(val token: String, val username: String, val email: String)

// ──────────────────────── Exception hierarchy ────────────────────────────────

/**
 * Thrown for any non-2xx response from the sync server (except 404 on GET,
 * which is returned as null).
 *
 * Use [RemoteSyncException.Unauthorized] to detect a revoked/expired token
 * — the client must prompt the user to sign in again.
 */
sealed class RemoteSyncException(message: String, val statusCode: Int = -1) : IOException(message) {
    /** HTTP 401 — token revoked or invalid.  Client must re-authenticate. */
    class Unauthorized(message: String = "401 Unauthorized — token revoked or expired") :
        RemoteSyncException(message, 401)

    /** Any other non-2xx error. */
    class HttpError(message: String, statusCode: Int) : RemoteSyncException(message, statusCode)
}

// ──────────────────────────── Client ────────────────────────────────────────

/**
 * Thin OkHttp wrapper for the remote sync server v2.
 *
 * All suspend functions run on [Dispatchers.IO].
 * Filenames are percent-encoded before embedding into URL path segments.
 *
 * The `{user}` path segment is gone in v2 — identity comes exclusively from
 * the `Authorization: Bearer` token.  Pass `token = ""` only for the
 * unauthenticated [authGoogle] call.
 */
class RemoteStorageClient(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun encodedFilename(filename: String): String =
        URLEncoder.encode(filename, "UTF-8").replace("+", "%20")

    private fun authedGet(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

    /** Throw the correct [RemoteSyncException] subtype for a non-2xx status code. */
    private fun throwForStatus(statusCode: Int, context: String): Nothing {
        if (statusCode == 401) throw RemoteSyncException.Unauthorized()
        throw RemoteSyncException.HttpError("$context returned $statusCode", statusCode)
    }

    // ── auth/google ───────────────────────────────────────────────────────────

    /**
     * Exchange a Google ID token for a server-minted session token.
     *
     * `POST {baseUrl}/auth/google`
     *
     * This is the only call that should be made with `token = ""` (the client
     * is not yet authenticated).  On success the returned [AuthResult.token]
     * should be stored in [RemoteSyncSettings.authToken] and used for all
     * subsequent calls.
     *
     * @throws RemoteSyncException.HttpError on 409 (username collision) or other errors.
     */
    suspend fun authGoogle(idToken: String): AuthResult = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/auth/google"
        val bodyStr = json.encodeToString(AuthGoogleRequest(idToken))
        val requestBody = bodyStr.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throwForStatus(resp.code, "authGoogle")
            val raw = resp.body?.string()
                ?: throw RemoteSyncException.HttpError("authGoogle empty response body", resp.code)
            val parsed = json.decodeFromString<AuthResultRaw>(raw)
            AuthResult(token = parsed.token, username = parsed.username, email = parsed.email)
        }
    }

    // ── health ───────────────────────────────────────────────────────────────

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/sync/health")
            .get()
            .build()
        return@withContext try {
            client.newCall(request).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    // ── manifest ─────────────────────────────────────────────────────────────

    /**
     * Fetch the manifest of all blobs owned by the authenticated user.
     *
     * `GET {baseUrl}/sync/manifest`
     *
     * @throws RemoteSyncException.Unauthorized on 401.
     * @throws RemoteSyncException.HttpError on other non-2xx responses.
     */
    suspend fun manifest(): List<BlobMeta> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/sync/manifest"
        val request = authedGet(url)
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throwForStatus(resp.code, "manifest")
            val body = resp.body?.string() ?: "[]"
            json.decodeFromString<List<BlobMeta>>(body)
        }
    }

    // ── get ──────────────────────────────────────────────────────────────────

    /**
     * Fetch a single blob by filename.
     *
     * `GET {baseUrl}/sync/file/{filename}`
     *
     * Returns null on 404 (file not yet on server).
     *
     * @throws RemoteSyncException.Unauthorized on 401.
     * @throws RemoteSyncException.HttpError on other non-2xx responses.
     */
    suspend fun get(filename: String): RemoteBlob? = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/sync/file/${encodedFilename(filename)}"
        val request = authedGet(url)
        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) throwForStatus(resp.code, "get($filename)")
            val body = resp.body?.string()
                ?: throw RemoteSyncException.HttpError("get($filename) empty body", resp.code)
            json.decodeFromString<RemoteBlob>(body)
        }
    }

    // ── put ──────────────────────────────────────────────────────────────────

    /**
     * Upload or overwrite a blob.
     *
     * `PUT {baseUrl}/sync/file/{filename}`
     *
     * @throws RemoteSyncException.Unauthorized on 401.
     * @throws RemoteSyncException.HttpError on other non-2xx responses.
     */
    suspend fun put(filename: String, content: String): BlobMeta =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/sync/file/${encodedFilename(filename)}"
            val bodyStr = json.encodeToString(PutRequest(content))
            val requestBody = bodyStr.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .put(requestBody)
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throwForStatus(resp.code, "put($filename)")
                val respBody = resp.body?.string()
                    ?: throw RemoteSyncException.HttpError("put($filename) empty response body", resp.code)
                json.decodeFromString<BlobMeta>(respBody)
            }
        }
}
