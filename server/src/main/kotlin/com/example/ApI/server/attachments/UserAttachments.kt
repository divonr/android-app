package com.example.ApI.server.attachments

import com.example.ApI.data.attachments.AttachmentHash
import com.example.ApI.data.attachments.AttachmentResolver
import com.example.ApI.data.attachments.AttachmentStoreClient
import com.example.ApI.data.attachments.AttachmentUploader
import com.example.ApI.data.attachments.FileDigest
import com.example.ApI.data.attachments.StoreError
import com.example.ApI.data.attachments.StoreResult
import com.example.ApI.data.attachments.attachmentStoreConfig
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.repository.AttachmentOwner
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.ServerPlatformStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * The files of a message / project attachment that is being saved ([UserAttachments.beginSave]).
 * From its creation until [end] the files cannot be deleted through this server.
 */
class FileSave internal constructor(
    /**
     * Hashes whose file exists nowhere this server can reach — the store answered "not found"
     * and the server has no copy: saving would reference a file that is gone.
     */
    val missing: Set<String>,
    private val release: () -> Unit
) {
    private val ended = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The save is over (done or given up): the files are let go once their commits have ended. */
    fun end() {
        if (ended.compareAndSet(false, true)) release()
    }
}

/** What `DELETE /api/files/{sha256}` did ([UserAttachments.deleteUnused]). */
sealed class UnusedFileDelete {
    /** Gone from the store and from this server. */
    object Deleted : UnusedFileDelete()
    /** Kept: [state] is `committed`, `in_use` (a save in progress / a saved message references it) or null (no such file). */
    class Kept(val state: String?) : UnusedFileDelete()
    class Failed(val failure: StoreResult.Failed) : UnusedFileDelete()
}

/** An upload that is larger than the server accepts. */
class FileTooLargeException(val maxBytes: Long) : IOException("The file is larger than $maxBytes bytes")

/**
 * The attachment files of ONE user on this server (CLOUD_RUNS_PLAN.md, "Attachment store"):
 * the client of the user's store on the sync server (with the user's own sync token), the
 * user's cache of store files (`<user dir>/files/attachments_cache/<sha256>`) and the resolver
 * every server path uses to find an attachment's file.
 *
 * A `local_file_path` reaches the server from clients, so it is only ever read when it names a
 * file inside the user's own attachment directories ([isOwnFile]); everything else is fetched
 * from the store by hash.
 */
class UserAttachments(
    private val username: String,
    private val storage: ServerPlatformStorage,
    private val repository: DataRepository,
    /** The largest file accepted (the store's limit). */
    val maxFileBytes: Long = AttachmentStoreClient.DEFAULT_MAX_FILE_BYTES,
    private val commitRetryDelaysMs: List<Long> = listOf(1_000L, 5_000L),
    /** How long a save waits for the first commit attempt of its files ([beginSave]). */
    private val saveCommitWaitMs: Long = 10_000L
) {
    private val log = LoggerFactory.getLogger(UserAttachments::class.java)

    /** Owns the background commits / uploads: they outlive the request that asked for them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The user's store, with the sync token of the user's settings as they are right now. */
    val store = AttachmentStoreClient(
        config = { repository.loadAppSettings().remoteSync.attachmentStoreConfig() },
        maxFileBytes = maxFileBytes
    )

    val cacheDir: File get() = storage.attachmentsCacheDir

    /**
     * The only directories a client-supplied `local_file_path` may point into: the files the web
     * saved before the store existed (`llm_data/attachments`) and the user's cache.
     */
    private val legacyUploadsDir = File(storage.filesDir, "llm_data/attachments")
    private val ownDirs: List<File> = listOf(legacyUploadsDir, storage.attachmentsCacheDir)

    val resolver = AttachmentResolver(storage.attachmentsCacheDir, store, ::isOwnFile)
    val uploader = AttachmentUploader(store, scope)

    /** Whether [file] is one of this user's attachment files on this server (symlinks resolved). */
    fun isOwnFile(file: File): Boolean = ownDirs.any { inside(it, file) }

    /** Whether [file] is a file the web saved for this user before the store existed. */
    fun isLegacyUpload(file: File): Boolean = inside(legacyUploadsDir, file)

    private fun inside(dir: File, file: File): Boolean = try {
        val root = dir.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        path != root && path.startsWith(root)
    } catch (_: Exception) {
        false
    }

    /** Whether an attachment of a saved message or project of the user satisfies [match]. */
    fun isReferenced(match: (Attachment) -> Boolean): Boolean {
        val history = repository.loadChatHistory(username)
        return history.groups.any { g -> g.group_attachments.any(match) } ||
            history.chat_history.any { chat ->
                chat.messages.any { m -> m.attachments.any(match) } ||
                    chat.messageNodes.any { node ->
                        node.variants.any { v ->
                            v.userMessage.attachments.any(match) || v.responses.any { r -> r.attachments.any(match) }
                        }
                    }
            }
    }

    /** The cached file of [sha256], if this server has it. */
    fun cached(sha256: String): File? =
        if (AttachmentHash.isValid(sha256)) resolver.cacheFile(sha256).takeIf { it.isFile } else null

    // ── Bringing bytes into the cache ─────────────────────────────────────────

    /**
     * Stream [input] into the user's cache under its hash.  Throws [FileTooLargeException] past
     * [maxFileBytes] (nothing is kept) and [IOException] when it cannot be written.
     */
    suspend fun receive(input: InputStream): Pair<File, FileDigest> = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val tmp = File(cacheDir, ".upload-${UUID.randomUUID()}.part")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    if (size > maxFileBytes) throw FileTooLargeException(maxFileBytes)
                    md.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
            val digest = FileDigest(md.digest().joinToString("") { "%02x".format(it) }, size)
            val target = resolver.cacheFile(digest.sha256)
            // The same bytes may be there already (and being read): keep that file
            if (!target.isFile && !tmp.renameTo(target) && !target.isFile) {
                throw IOException("Cannot move the upload into the cache")
            }
            target to digest
        } finally {
            tmp.delete()
        }
    }

    /** [receive] for bytes already in memory (a file a tool generated). */
    suspend fun receive(bytes: ByteArray): Pair<File, FileDigest> = receive(bytes.inputStream())

    // ── Store operations ──────────────────────────────────────────────────────

    /**
     * Commit [sha256] in the store ("a saved message / project references it").  A store that
     * no longer has the file gets it again from this server's cache first.
     */
    suspend fun commit(sha256: String): StoreResult<FileDigest> {
        when (val first = store.commit(sha256)) {
            is StoreResult.Ok -> return StoreResult.Ok(FileDigest(sha256, first.value.size))
            is StoreResult.Failed -> if (first.error != StoreError.NOT_FOUND) return first
        }
        // "Upload again, then commit" — when this server has the bytes
        val file = cached(sha256) ?: return StoreResult.Failed(StoreError.NOT_FOUND, "The store does not have $sha256")
        return uploader.ensureStored(file, FileDigest(sha256, file.length()))
    }

    // ── A save and a delete of the same file ──────────────────────────────────
    //
    // A pending file is deleted by `DELETE /api/files/{sha}` (store copy and this server's copy),
    // and the bytes of a web upload exist nowhere else.  A delete of the same bytes (another tab,
    // another draft, the phone) must therefore never win against a save that references them:
    //
    //  - a save first HOLDS its hashes ([beginSave]) and only then commits and saves; the hold
    //    ends when the save is over and the commits have ended;
    //  - a delete ([deleteUnused]) runs under [gate] and refuses a hash that is held or that the
    //    user's stored history references.
    //
    // Taking a hold also passes through [gate], so for one hash either the delete has completely
    // finished before the hold exists — then the commit that precedes the save finds the file
    // gone and the save is refused ([FileSave.missing]) instead of referencing nothing — or the
    // delete sees the hold.  After the hold is released the saved message itself protects the file.

    /** Orders a delete against the start of a save (per user; held across the store's delete call). */
    private val gate = Mutex()
    /** sha256 → number of saves in progress that reference it. */
    private val holds = HashMap<String, Int>()

    private fun isHeld(sha256: String): Boolean = synchronized(holds) { sha256 in holds }

    private fun releaseHolds(hashes: Collection<String>) = synchronized(holds) {
        for (sha in hashes) {
            val left = (holds[sha] ?: continue) - 1
            if (left <= 0) holds.remove(sha) else holds[sha] = left
        }
    }

    /**
     * A message / project attachment with [attachments] is about to be saved: hold their files
     * against a delete and commit them, waiting (bounded) for the first attempt of every commit.
     * A store that cannot be reached is retried in the background, as in [commitInBackground].
     *
     * The caller saves only when it accepts [FileSave.missing], and calls [FileSave.end] when the
     * save is over, whatever its outcome.
     */
    suspend fun beginSave(attachments: Collection<Attachment>): FileSave {
        val hashes = attachments.mapNotNull { it.sha256 }.filter(AttachmentHash::isValid).distinct()
        if (hashes.isEmpty()) return FileSave(emptySet()) {}
        gate.withLock {
            synchronized(holds) { hashes.forEach { holds[it] = (holds[it] ?: 0) + 1 } }
        }
        val firstAttempts = hashes.associateWith { CompletableDeferred<StoreResult<*>>() }
        val jobs = hashes.map { sha ->
            scope.launch {
                val first = firstAttempts.getValue(sha)
                try {
                    retrying("commit of $sha") { commit(sha).also { first.complete(it) } }
                } finally {
                    first.cancel()
                }
            }
        }
        val missing = HashSet<String>()
        try {
            withTimeoutOrNull(saveCommitWaitMs) {
                for ((sha, first) in firstAttempts) {
                    val result = try {
                        first.await()
                    } catch (ce: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        continue        // the commit job died (shutdown), not the caller
                    }
                    // Not in the store (or there is no store) and not here: the file is gone
                    val nowhere = result is StoreResult.Failed &&
                        (result.error == StoreError.NOT_FOUND || result.error == StoreError.NOT_CONFIGURED) &&
                        cached(sha) == null
                    if (nowhere) missing.add(sha)
                }
            }
        } catch (ce: CancellationException) {
            releaseHolds(hashes)
            throw ce
        }
        return FileSave(missing) {
            // Held until the commits are over too: a file that could not be committed yet stays
            // protected by the saved message that references it (see deleteUnused)
            scope.launch {
                try {
                    jobs.joinAll()
                } finally {
                    releaseHolds(hashes)
                }
            }
        }
    }

    /**
     * Delete a file that was uploaded and never used: from the store (only a `pending` file) and
     * from this server's cache.  Refused for a file a save in progress holds or the user's saved
     * history references — such a file is committed instead, if it is not yet.
     */
    suspend fun deleteUnused(sha256: String): UnusedFileDelete = gate.withLock {
        if (isHeld(sha256)) return@withLock UnusedFileDelete.Kept("in_use")
        if (isReferenced { it.sha256 == sha256 }) {
            // Its commit did not get through (yet): the reference is what keeps the file
            commitInBackground(listOf(Attachment(file_name = "", mime_type = "", sha256 = sha256)))
            return@withLock UnusedFileDelete.Kept("in_use")
        }
        when (val result = store.deletePending(sha256)) {
            is StoreResult.Ok -> if (result.value) {
                cached(sha256)?.delete()
                UnusedFileDelete.Deleted
            } else {
                val present = store.exists(sha256)
                UnusedFileDelete.Kept(if (present is StoreResult.Ok && present.value != null) "committed" else null)
            }
            is StoreResult.Failed -> when {
                result.error != StoreError.NOT_CONFIGURED -> UnusedFileDelete.Failed(result)
                // No store (sync is off): the file only lives here
                cached(sha256)?.delete() == true -> UnusedFileDelete.Deleted
                else -> UnusedFileDelete.Kept(null)
            }
        }
    }

    /**
     * Whoever saves a message / project attachment commits its files: commit the files of
     * [attachments] in the background, retrying a store that cannot be reached.  Idempotent and
     * best effort.  For files that the stored history already references (a resend); a save of
     * new files goes through [beginSave].
     */
    fun commitInBackground(attachments: Collection<Attachment>): List<Job> =
        attachments.mapNotNull { it.sha256 }.filter(AttachmentHash::isValid).distinct().map { sha ->
            scope.launch {
                retrying("commit of $sha") { commit(sha) }
            }
        }

    /**
     * Upload + commit [file] (a file this server produced or holds), awaited once; a store that
     * cannot be reached is retried in the background.  Returns whether the store has it now.
     */
    suspend fun storeNow(file: File, digest: FileDigest): Boolean {
        val first = uploader.ensureStored(file, digest)
        if (first.isOk) return true
        if (first is StoreResult.Failed && first.error == StoreError.UNAVAILABLE) {
            scope.launch { retrying("upload of ${digest.sha256}", firstDelay = true) { uploader.ensureStored(file, digest) } }
        } else {
            log.warn("Storing ${digest.sha256} for '$username' failed: ${(first as? StoreResult.Failed)?.message}")
        }
        return false
    }

    /**
     * The lazy backfill of an attachment this server has the file of but that has no hash yet
     * (saved by the web before the store existed): hash + upload + commit [file] in the
     * background, then record the identity on the stored attachment of [owner].
     */
    fun backfillInBackground(owner: AttachmentOwner, attachment: Attachment, file: File): Job = scope.launch {
        try {
            val digest = AttachmentHash.of(file)
            var stored = false
            retrying("backfill of ${digest.sha256}") {
                uploader.ensureStored(file, digest).also { stored = it.isOk }
            }
            // Only a file the store has gets a hash: other devices fetch it by that hash
            if (stored) repository.recordAttachmentIdentity(username, owner, attachment, digest)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            log.warn("Backfilling an attachment of '$username' failed: ${e.message}")
        }
    }

    /** Run [operation] until it succeeds, fails for good, or the retries are used up. */
    private suspend fun retrying(what: String, firstDelay: Boolean = false, operation: suspend () -> StoreResult<*>) {
        val delays = if (firstDelay) commitRetryDelaysMs else listOf(0L) + commitRetryDelaysMs
        for ((attempt, wait) in delays.withIndex()) {
            if (wait > 0) delay(wait)
            when (val result = operation()) {
                is StoreResult.Ok -> return
                is StoreResult.Failed -> {
                    val last = attempt == delays.lastIndex
                    // Only an unreachable store is worth another try
                    if (result.error != StoreError.UNAVAILABLE || last) {
                        if (result.error != StoreError.NOT_CONFIGURED) {
                            log.warn("The $what for '$username' failed: ${result.error} ${result.message}")
                        }
                        return
                    }
                }
            }
        }
    }

    /** Stop the background work (server shutdown). */
    fun close() {
        scope.cancel()
    }
}
