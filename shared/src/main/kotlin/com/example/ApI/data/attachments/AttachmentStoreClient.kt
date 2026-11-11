package com.example.ApI.data.attachments

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.RemoteSyncSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Where the account's attachment store is (the sync server) and who is asking (the sync token). */
data class AttachmentStoreConfig(val baseUrl: String, val token: String)

/** The store of this device's account, or null when sync is off / signed out. */
fun RemoteSyncSettings.attachmentStoreConfig(): AttachmentStoreConfig? =
    if (enabled && authToken.isNotBlank() && serverBaseUrl.isNotBlank()) AttachmentStoreConfig(serverBaseUrl, authToken)
    else null

/** A file's identity in the store: SHA-256 of its bytes (64 lowercase hex) and its size. */
data class FileDigest(val sha256: String, val size: Long)

/** The attachment with the file's identity recorded on it (see [Attachment.sha256]). */
fun Attachment.withDigest(digest: FileDigest): Attachment = copy(sha256 = digest.sha256, size = digest.size)

/** The identity recorded on the attachment, if any. */
val Attachment.digest: FileDigest? get() = sha256?.takeIf(AttachmentHash::isValid)?.let { FileDigest(it, size ?: -1L) }

object AttachmentHash {
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** Exactly what the store accepts as a file name: 64 lowercase hex digits. */
    fun isValid(sha256: String): Boolean = SHA256.matches(sha256)

    /** Hashes [file] (streamed, cancellable). Throws [IOException] when it cannot be read. */
    suspend fun of(file: File): FileDigest = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
                size += n
            }
        }
        FileDigest(hex(md.digest()), size)
    }

    internal fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
    internal const val BUFFER_SIZE = 64 * 1024
}

enum class StoredState { PENDING, COMMITTED }

/** A file as the store has it. `size` is -1 when the server did not say. */
data class StoredFile(val sha256: String, val size: Long, val state: StoredState)

enum class StoreError {
    /** Sync is off / signed out: there is no store to talk to. */
    NOT_CONFIGURED,
    /** 401: the sync token was revoked. */
    UNAUTHORIZED,
    /** The store does not have the file. */
    NOT_FOUND,
    /** Larger than the store accepts (413, or over the client's own limit). */
    TOO_LARGE,
    /** The bytes are not the file the hash names (upload: the file changed; download: corrupt). */
    HASH_MISMATCH,
    /** The store could not be reached or failed (network, timeout, 5xx…): worth retrying later. */
    UNAVAILABLE,
    /** The local file could not be read / written. */
    LOCAL_IO
}

/** Outcome of a store operation. The operations never throw (except for coroutine cancellation). */
sealed class StoreResult<out T> {
    data class Ok<T>(val value: T) : StoreResult<T>()
    data class Failed(val error: StoreError, val message: String) : StoreResult<Nothing>()

    fun getOrNull(): T? = (this as? Ok<T>)?.value
    val isOk: Boolean get() = this is Ok
}

/**
 * HTTP client of the account's attachment store on the sync server (CLOUD_RUNS_PLAN.md,
 * "Attachment store"): files are stored under their SHA-256, per user, `pending` until some
 * saved message / project references them (`commit`).
 *
 * Every request carries `Authorization: Bearer <sync token>`. [config] is read for every request
 * (the settings may change); null = not configured. All functions are cancellable: cancelling the
 * calling coroutine aborts the transfer.
 */
class AttachmentStoreClient(
    private val config: () -> AttachmentStoreConfig?,
    baseHttpClient: OkHttpClient = OkHttpClient(),
    /** Files larger than this are not even offered to the store (its default limit). */
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    controlTimeoutMs: Long = CONTROL_TIMEOUT_MS,
    transferIdleTimeoutMs: Long = TRANSFER_IDLE_TIMEOUT_MS
) {
    companion object {
        /** The store's default `MAX_FILE_BYTES` (Cloudflare caps a request body at 100 MB). */
        const val DEFAULT_MAX_FILE_BYTES = 95L * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000L
        /** exists / commit / delete: the whole call. */
        const val CONTROL_TIMEOUT_MS = 15_000L
        /** upload / download: no limit on the whole call, only on a stalled connection. */
        const val TRANSFER_IDLE_TIMEOUT_MS = 60_000L

        private val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()
        private val StoreJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }

    @Serializable
    private data class FileAnswer(
        val sha256: String = "",
        val size: Long = -1,
        val state: String? = null,
        val existed: Boolean = false,
        val deleted: Boolean = false
    )

    private val controlClient = baseHttpClient.newBuilder()
        .connectTimeout(minOf(CONNECT_TIMEOUT_MS, controlTimeoutMs), TimeUnit.MILLISECONDS)
        .callTimeout(controlTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val transferClient = baseHttpClient.newBuilder()
        .connectTimeout(minOf(CONNECT_TIMEOUT_MS, transferIdleTimeoutMs), TimeUnit.MILLISECONDS)
        .readTimeout(transferIdleTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(transferIdleTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Whether there is a store to talk to right now. */
    val isConfigured: Boolean get() = config() != null

    // ── exists ───────────────────────────────────────────────────────────────

    /** `HEAD /files/{sha256}`: the stored file, or `Ok(null)` when the store does not have it. */
    suspend fun exists(sha256: String): StoreResult<StoredFile?> = request(sha256, controlClient, { head() }) { resp ->
        when (resp.code) {
            200 -> StoreResult.Ok(
                StoredFile(sha256, resp.header("Content-Length")?.toLongOrNull() ?: -1L, stateOf(resp.header("X-File-State")))
            )
            404 -> StoreResult.Ok(null)
            else -> failure(resp, "exists")
        }
    }

    // ── upload ───────────────────────────────────────────────────────────────

    /**
     * Makes sure the store has [file]: hashes it (unless [digest] is given), asks whether it is
     * already there and otherwise streams it up (`PUT /files/{sha256}`). A new file is `pending`
     * until [commit]. [onProgress] gets (bytes sent, total) while the body is written; it is
     * called on an I/O thread.
     */
    suspend fun upload(
        file: File,
        digest: FileDigest? = null,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): StoreResult<StoredFile> {
        if (config() == null) return notConfigured()
        val known = digest ?: try {
            AttachmentHash.of(file)
        } catch (e: IOException) {
            return StoreResult.Failed(StoreError.LOCAL_IO, "Cannot read ${file.name}: ${e.message}")
        }
        if (!AttachmentHash.isValid(known.sha256)) return invalidHash(known.sha256)
        if (known.size > maxFileBytes) {
            return StoreResult.Failed(StoreError.TOO_LARGE, "${file.name} is ${known.size} bytes (limit $maxFileBytes)")
        }
        // Cheap question first: never send bytes the store already has
        when (val present = exists(known.sha256)) {
            is StoreResult.Ok -> present.value?.let { return StoreResult.Ok(it) }
            is StoreResult.Failed -> return present
        }
        val body = FileBody(file, known.size, onProgress)
        return request(known.sha256, transferClient, { put(body) }) { resp ->
            when (resp.code) {
                200, 201 -> {
                    val answer = decode(resp)
                    StoreResult.Ok(StoredFile(known.sha256, answer.size.takeIf { it >= 0 } ?: known.size, stateOf(answer.state)))
                }
                413 -> StoreResult.Failed(StoreError.TOO_LARGE, "The store refused ${file.name} (${known.size} bytes)")
                422 -> StoreResult.Failed(StoreError.HASH_MISMATCH, "${file.name} changed while it was uploaded")
                else -> failure(resp, "upload")
            }
        }.let { result ->
            // A file that vanished / became unreadable mid-upload surfaces as a network error
            if (result is StoreResult.Failed && result.error == StoreError.UNAVAILABLE && body.readError != null) {
                StoreResult.Failed(StoreError.LOCAL_IO, "Cannot read ${file.name}: ${body.readError?.message}")
            } else result
        }
    }

    // ── commit / delete ──────────────────────────────────────────────────────

    /**
     * `POST /files/{sha256}/commit` (idempotent): a saved message / project references the file,
     * so it is kept. [StoreError.NOT_FOUND]: the store does not have the file (never uploaded,
     * or deleted while pending) — upload it again, then commit.
     */
    suspend fun commit(sha256: String): StoreResult<StoredFile> =
        request(sha256, controlClient, { post(ByteArray(0).toRequestBody(null)) }, suffix = "/commit") { resp ->
            when (resp.code) {
                200 -> StoreResult.Ok(StoredFile(sha256, decode(resp).size, StoredState.COMMITTED))
                404 -> StoreResult.Failed(StoreError.NOT_FOUND, "The store does not have $sha256")
                else -> failure(resp, "commit")
            }
        }

    /**
     * `DELETE /files/{sha256}`: removes a file that was uploaded but never committed (attached,
     * then removed before sending). `Ok(true)`: deleted. `Ok(false)`: nothing to delete — the
     * file is committed (and stays), or the store does not have it.
     */
    suspend fun deletePending(sha256: String): StoreResult<Boolean> =
        request(sha256, controlClient, { delete() }) { resp ->
            when (resp.code) {
                200 -> StoreResult.Ok(decode(resp).deleted)
                404 -> StoreResult.Ok(false)
                else -> failure(resp, "delete")
            }
        }

    // ── download ─────────────────────────────────────────────────────────────

    /**
     * `GET /files/{sha256}` into [dest]. The bytes go to a temp file next to [dest], are verified
     * against [sha256] and only then renamed over [dest]: [dest] never holds a partial or wrong
     * file. [onProgress] gets (bytes received, total or -1); it is called on an I/O thread.
     */
    suspend fun download(
        sha256: String,
        dest: File,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }
    ): StoreResult<File> {
        val target = dest.absoluteFile
        val dir = target.parentFile ?: return StoreResult.Failed(StoreError.LOCAL_IO, "No directory for $dest")
        val tmp = File(dir, ".${target.name}.${UUID.randomUUID()}.part")
        try {
            return request(sha256, transferClient, { get() }) { resp ->
                when (resp.code) {
                    200 -> receive(resp, sha256, tmp, target, onProgress)
                    404 -> StoreResult.Failed(StoreError.NOT_FOUND, "The store does not have $sha256")
                    else -> failure(resp, "download")
                }
            }
        } finally {
            // Also after a cancellation: no partial file stays behind
            tmp.delete()
        }
    }

    private fun receive(
        resp: Response,
        sha256: String,
        tmp: File,
        target: File,
        onProgress: (Long, Long) -> Unit
    ): StoreResult<File> {
        val body = resp.body ?: return StoreResult.Failed(StoreError.UNAVAILABLE, "download: empty answer")
        val total = body.contentLength()
        val md = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            tmp.parentFile?.mkdirs()
            tmp.outputStream().use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(AttachmentHash.BUFFER_SIZE)
                while (true) {
                    // A read failure is the network's (IOException → UNAVAILABLE by the caller)
                    val n = input.read(buffer)
                    if (n < 0) break
                    md.update(buffer, 0, n)
                    try {
                        out.write(buffer, 0, n)
                    } catch (e: IOException) {
                        throw LocalIoException(e)
                    }
                    received += n
                    onProgress(received, total)
                }
            }
        } catch (e: LocalIoException) {
            return StoreResult.Failed(StoreError.LOCAL_IO, "Cannot write ${target.name}: ${e.cause?.message}")
        } catch (e: java.io.FileNotFoundException) {
            return StoreResult.Failed(StoreError.LOCAL_IO, "Cannot write ${target.name}: ${e.message}")
        }
        val actual = AttachmentHash.hex(md.digest())
        if (actual != sha256) {
            return StoreResult.Failed(StoreError.HASH_MISMATCH, "Downloaded bytes are $actual, not $sha256")
        }
        if (!moveIntoPlace(tmp, target)) {
            return StoreResult.Failed(StoreError.LOCAL_IO, "Cannot move the download to ${target.path}")
        }
        return StoreResult.Ok(target)
    }

    private class LocalIoException(cause: IOException) : RuntimeException(cause)

    /** rename(2) replaces the target atomically; Windows needs the target gone first. */
    private fun moveIntoPlace(tmp: File, target: File): Boolean {
        if (tmp.renameTo(target)) return true
        if (target.exists() && target.delete() && tmp.renameTo(target)) return true
        return false
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun notConfigured() = StoreResult.Failed(StoreError.NOT_CONFIGURED, "Sync is off: no attachment store")

    private fun invalidHash(sha256: String) =
        StoreResult.Failed(StoreError.NOT_FOUND, "Not a SHA-256: ${sha256.take(80)}")

    private fun stateOf(text: String?): StoredState =
        if (text.equals("committed", ignoreCase = true)) StoredState.COMMITTED else StoredState.PENDING

    private fun decode(resp: Response): FileAnswer = try {
        StoreJson.decodeFromString(FileAnswer.serializer(), resp.body?.string().orEmpty())
    } catch (_: Exception) {
        FileAnswer()
    }

    private fun failure(resp: Response, what: String): StoreResult.Failed = when (resp.code) {
        401 -> StoreResult.Failed(StoreError.UNAUTHORIZED, "$what: 401 (the sync token was rejected)")
        413 -> StoreResult.Failed(StoreError.TOO_LARGE, "$what: 413")
        else -> StoreResult.Failed(StoreError.UNAVAILABLE, "$what: HTTP ${resp.code}")
    }

    /**
     * One request to `/files/{sha256}[suffix]` (the builder block adds the method). Never throws
     * except for cancellation, which also aborts the HTTP call.
     */
    private suspend fun <T> request(
        sha256: String,
        client: OkHttpClient,
        method: Request.Builder.() -> Request.Builder,
        suffix: String = "",
        handle: (Response) -> StoreResult<T>
    ): StoreResult<T> {
        val cfg = config() ?: return notConfigured()
        // The hash becomes a URL path segment (and a file name on the server)
        if (!AttachmentHash.isValid(sha256)) return invalidHash(sha256)
        val request = try {
            Request.Builder()
                .url(cfg.baseUrl.trimEnd('/') + "/files/" + sha256 + suffix)
                .header("Authorization", "Bearer ${cfg.token}")
                .method()
                .build()
        } catch (e: IllegalArgumentException) {
            return StoreResult.Failed(StoreError.NOT_CONFIGURED, "Bad store URL: ${e.message}")
        }
        val call = client.newCall(request)
        return try {
            execute(call, handle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A call aborted because the caller was cancelled is a cancellation, not a failure
            currentCoroutineContext().ensureActive()
            StoreResult.Failed(StoreError.UNAVAILABLE, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Runs the blocking call on the I/O dispatcher; cancelling the caller cancels the call. */
    private suspend fun <T> execute(call: Call, handle: (Response) -> T): T = coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(handle) }
        } finally {
            watcher.cancel()
        }
    }

    /** Streams a file as the request body, reporting progress. */
    private class FileBody(
        private val file: File,
        private val size: Long,
        private val onProgress: (Long, Long) -> Unit
    ) : RequestBody() {
        @Volatile var readError: IOException? = null

        override fun contentType(): MediaType = OCTET_STREAM
        override fun contentLength(): Long = size

        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            val input = try {
                file.inputStream()
            } catch (e: IOException) {
                readError = e
                throw e
            }
            input.use {
                val buffer = ByteArray(AttachmentHash.BUFFER_SIZE)
                while (true) {
                    val n = try {
                        it.read(buffer)
                    } catch (e: IOException) {
                        readError = e
                        throw e
                    }
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                    sent += n
                    onProgress(sent, size)
                }
            }
        }
    }
}
