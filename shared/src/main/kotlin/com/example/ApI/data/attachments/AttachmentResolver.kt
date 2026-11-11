package com.example.ApI.data.attachments

import com.example.ApI.data.model.Attachment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Where a resolved attachment file came from. */
enum class AttachmentSource {
    /** The attachment's own `local_file_path` on this device. */
    LOCAL,
    /** This device's cache of store files. */
    CACHE,
    /** Just downloaded from the store (now in the cache). */
    DOWNLOADED
}

sealed class ResolvedAttachment {
    /** [file] holds the attachment's bytes and can be read. */
    data class Available(val file: File, val source: AttachmentSource) : ResolvedAttachment()

    /**
     * The file cannot be had right now. [error] says why: [StoreError.NOT_FOUND] also covers an
     * attachment without a hash whose local file is not on this device (nothing to ask the
     * store for); [StoreError.UNAVAILABLE] / [StoreError.NOT_CONFIGURED] may work later.
     */
    data class Unavailable(val error: StoreError, val message: String) : ResolvedAttachment()

    val fileOrNull: File? get() = (this as? Available)?.file
}

/**
 * The one way every platform finds an attachment's file (CLOUD_RUNS_PLAN.md, "Identity"):
 *
 * 1. `local_file_path` exists on this device (and [trustLocalPath] accepts it) → that file;
 * 2. the attachment has a hash and the cache holds it (`<cacheDir>/<sha256>`) → the cached file;
 * 3. it has a hash → download it from the store into the cache (verified, atomic);
 * 4. otherwise unavailable.
 *
 * Concurrent resolutions of the same hash download it once.
 *
 * @param cacheDir       `PlatformStorage.attachmentsCacheDir` of this device (per user on the web server).
 * @param trustLocalPath whether a `local_file_path` may be read here. A device trusts its own
 *                       history; the web server must only accept paths inside the user's own
 *                       directory (the path comes from a client and could name any server file).
 */
class AttachmentResolver(
    private val cacheDir: File,
    private val store: AttachmentStoreClient,
    private val trustLocalPath: (File) -> Boolean = { true }
) {
    /** One lock per hash being fetched; entries are dropped when nobody waits for them. */
    private class Slot {
        val mutex = Mutex()
        var users = 0
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /** The cache file of a hash (whether or not it exists yet). */
    fun cacheFile(sha256: String): File = File(cacheDir, sha256)

    /**
     * Steps 1–2 only (no network, no suspension): the attachment's file if this device already
     * has it. For UI that must not trigger a download.
     */
    fun resolveLocal(attachment: Attachment): ResolvedAttachment.Available? {
        localFile(attachment)?.let { return ResolvedAttachment.Available(it, AttachmentSource.LOCAL) }
        val sha = validHash(attachment) ?: return null
        return cached(sha)?.let { ResolvedAttachment.Available(it, AttachmentSource.CACHE) }
    }

    /** The full resolution; downloads when needed. [onProgress]: (bytes received, total or -1). */
    suspend fun resolve(
        attachment: Attachment,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }
    ): ResolvedAttachment {
        resolveLocal(attachment)?.let { return it }
        val sha = validHash(attachment) ?: return ResolvedAttachment.Unavailable(
            StoreError.NOT_FOUND,
            if (attachment.sha256 == null) "${attachment.file_name} is only on the device that attached it (no hash)"
            else "${attachment.file_name} has an invalid hash"
        )
        return fetch(sha, onProgress)
    }

    /** Steps 2–3 for a bare hash (no attachment at hand). */
    suspend fun resolveHash(
        sha256: String,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }
    ): ResolvedAttachment {
        if (!AttachmentHash.isValid(sha256)) return ResolvedAttachment.Unavailable(StoreError.NOT_FOUND, "Not a SHA-256")
        cached(sha256)?.let { return ResolvedAttachment.Available(it, AttachmentSource.CACHE) }
        return fetch(sha256, onProgress)
    }

    private suspend fun fetch(sha: String, onProgress: (Long, Long) -> Unit): ResolvedAttachment {
        val slot = slots.compute(sha) { _, existing -> (existing ?: Slot()).also { it.users++ } }!!
        try {
            return slot.mutex.withLock {
                // Someone else may have downloaded it while we waited
                cached(sha)?.let { return@withLock ResolvedAttachment.Available(it, AttachmentSource.CACHE) }
                when (val result = store.download(sha, cacheFile(sha), onProgress)) {
                    is StoreResult.Ok -> ResolvedAttachment.Available(result.value, AttachmentSource.DOWNLOADED)
                    is StoreResult.Failed -> ResolvedAttachment.Unavailable(result.error, result.message)
                }
            }
        } finally {
            slots.compute(sha) { _, existing -> existing?.takeIf { --it.users > 0 } }
        }
    }

    private fun validHash(attachment: Attachment): String? = attachment.sha256?.takeIf(AttachmentHash::isValid)

    private fun localFile(attachment: Attachment): File? {
        val path = attachment.local_file_path?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        return try {
            file.takeIf { it.isFile && it.canRead() && trustLocalPath(it) }
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * The cached file of a hash. Files enter the cache only verified and by an atomic rename, so
     * existence is enough.
     */
    private fun cached(sha: String): File? = cacheFile(sha).takeIf { it.isFile }
}
