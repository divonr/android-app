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

/** Thrown for any non-2xx (except 404 which is handled as null on get). */
class RemoteSyncException(message: String, val statusCode: Int = -1) : IOException(message)

// ──────────────────────────── Client ────────────────────────────────────────

/**
 * Thin OkHttp wrapper for the remote sync server.
 *
 * All suspend functions run on [Dispatchers.IO].
 * Filenames are percent-encoded before embedding into URL path segments.
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

    suspend fun manifest(user: String): List<BlobMeta> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/sync/${encodedFilename(user)}/manifest"
        val request = authedGet(url)
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful)
                throw RemoteSyncException("manifest returned ${resp.code}", resp.code)
            val body = resp.body?.string() ?: "[]"
            json.decodeFromString<List<BlobMeta>>(body)
        }
    }

    // ── get ──────────────────────────────────────────────────────────────────

    /** Returns null on 404 (file absent on server). */
    suspend fun get(user: String, filename: String): RemoteBlob? = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/sync/${encodedFilename(user)}/${encodedFilename(filename)}"
        val request = authedGet(url)
        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful)
                throw RemoteSyncException("get($filename) returned ${resp.code}", resp.code)
            val body = resp.body?.string()
                ?: throw RemoteSyncException("get($filename) empty body")
            json.decodeFromString<RemoteBlob>(body)
        }
    }

    // ── put ──────────────────────────────────────────────────────────────────

    suspend fun put(user: String, filename: String, content: String): BlobMeta =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/sync/${encodedFilename(user)}/${encodedFilename(filename)}"

            val bodyStr = json.encodeToString(PutRequest(content))
            val requestBody = bodyStr.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .put(requestBody)
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful)
                    throw RemoteSyncException("put($filename) returned ${resp.code}", resp.code)
                val respBody = resp.body?.string()
                    ?: throw RemoteSyncException("put($filename) empty response body")
                json.decodeFromString<BlobMeta>(respBody)
            }
        }
}
