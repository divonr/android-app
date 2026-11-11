package com.example.ApI.data.attachments

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/** Where a background upload stands. */
sealed class AttachmentUploadState {
    object Hashing : AttachmentUploadState()
    data class Uploading(val digest: FileDigest, val sent: Long, val total: Long) : AttachmentUploadState()
    /** The store has the file (`pending`, or `committed` once [committed] is true). */
    data class Stored(val digest: FileDigest, val committed: Boolean) : AttachmentUploadState()
    data class Failed(val digest: FileDigest?, val error: StoreError, val message: String) : AttachmentUploadState()
    object Cancelled : AttachmentUploadState()
}

/**
 * One file on its way to the store, started by [AttachmentUploader.start] when the file is
 * attached. It outlives whoever awaits it: giving up on [awaitStored] (a timeout at send time)
 * does not stop the upload, only [AttachmentUploader.discard] / [cancel] do.
 */
class AttachmentUpload internal constructor(
    val file: File,
    private val store: AttachmentStoreClient,
    private val scope: CoroutineScope,
    knownDigest: FileDigest?
) {
    private val _state = MutableStateFlow<AttachmentUploadState>(AttachmentUploadState.Hashing)
    val state: StateFlow<AttachmentUploadState> = _state

    private val digestResult = CompletableDeferred<FileDigest?>()
    private val stored: Deferred<StoreResult<FileDigest>>
    private val lock = Any()
    private var commitJob: Deferred<StoreResult<FileDigest>>? = null

    init {
        stored = scope.async {
            val digest = knownDigest ?: try {
                AttachmentHash.of(file)
            } catch (e: IOException) {
                digestResult.complete(null)
                return@async fail(null, StoreError.LOCAL_IO, "Cannot read ${file.name}: ${e.message}")
            }
            digestResult.complete(digest)
            _state.value = AttachmentUploadState.Uploading(digest, 0, digest.size)
            val result = store.upload(file, digest) { sent, total ->
                _state.value = AttachmentUploadState.Uploading(digest, sent, total)
            }
            when (result) {
                is StoreResult.Ok -> {
                    _state.value = AttachmentUploadState.Stored(digest, result.value.state == StoredState.COMMITTED)
                    StoreResult.Ok(digest)
                }
                is StoreResult.Failed -> fail(digest, result.error, result.message)
            }
        }
        stored.invokeOnCompletion { cause ->
            if (cause != null) {
                digestResult.complete(null)
                _state.value = AttachmentUploadState.Cancelled
            }
        }
    }

    private fun fail(digest: FileDigest?, error: StoreError, message: String): StoreResult.Failed {
        _state.value = AttachmentUploadState.Failed(digest, error, message)
        return StoreResult.Failed(error, message)
    }

    /**
     * The file's identity, known as soon as it is hashed — long before the upload ends, so a
     * message can be saved with the hash even when the upload is slow. null: unreadable file,
     * or cancelled before the hash was known.
     */
    suspend fun digest(): FileDigest? = digestResult.await()

    /** Waits until the store has the file (or the upload failed). */
    suspend fun awaitStored(): StoreResult<FileDigest> = try {
        stored.await()
    } catch (e: CancellationException) {
        // The caller was cancelled → propagate; the upload was cancelled → an ordinary failure
        currentCoroutineContext().ensureActive()
        StoreResult.Failed(StoreError.UNAVAILABLE, "The upload was cancelled")
    }

    /**
     * [awaitStored] with a bound: null when the upload is still running after [timeoutMs] (it
     * keeps running). The send path uses this: null / a failure → the request falls back to the
     * device, the file still reaches the store later.
     */
    suspend fun awaitStored(timeoutMs: Long): StoreResult<FileDigest>? = withTimeoutOrNull(timeoutMs) { awaitStored() }

    /**
     * The message / project that references the file is being saved: commit the file as soon as
     * the store has it (now, if it already does). Idempotent; returns the commit's outcome
     * without making the caller wait for it. A file that the store lost meanwhile (deleted while
     * pending) is uploaded again.
     */
    fun commitWhenStored(): Deferred<StoreResult<FileDigest>> = synchronized(lock) {
        commitJob ?: scope.async {
            when (val result = awaitStored()) {
                is StoreResult.Failed -> result
                is StoreResult.Ok -> {
                    val committed = commitStored(store, file, result.value)
                    if (committed is StoreResult.Ok) _state.value = AttachmentUploadState.Stored(result.value, committed = true)
                    committed
                }
            }
        }.also { commitJob = it }
    }

    /** Whether [commitWhenStored] was asked for (the file is, or will be, in use). */
    val commitRequested: Boolean get() = synchronized(lock) { commitJob != null }

    /** Stops the upload (nothing is deleted from the store; see [AttachmentUploader.discard]). */
    fun cancel() {
        stored.cancel()
    }

    /** [cancel], then wait until the transfer has really stopped. */
    internal suspend fun cancelAndJoin() = stored.cancelAndJoin()
}

/** Commit, re-uploading first when the store no longer has the file. */
internal suspend fun commitStored(store: AttachmentStoreClient, file: File?, digest: FileDigest): StoreResult<FileDigest> {
    when (val first = store.commit(digest.sha256)) {
        is StoreResult.Ok -> return StoreResult.Ok(digest)
        is StoreResult.Failed -> if (first.error != StoreError.NOT_FOUND || file == null) return first
    }
    when (val again = store.upload(file!!, digest)) {
        is StoreResult.Failed -> return again
        is StoreResult.Ok -> Unit
    }
    return when (val second = store.commit(digest.sha256)) {
        is StoreResult.Ok -> StoreResult.Ok(digest)
        is StoreResult.Failed -> second
    }
}

/**
 * Client-side life cycle of attachment files in the store (CLOUD_RUNS_PLAN.md, "Clients"):
 *
 * - attach → [start] (background hash + upload, the file is `pending` in the store);
 * - removed before sending → [discard] (stop + delete the pending file);
 * - send → `upload.awaitStored(timeout)` to decide cloud vs device, `upload.digest()` for the
 *   hash to save on the attachment, [AttachmentUpload.commitWhenStored] when the message is saved;
 * - an old attachment without a hash, or a file made on this device (tool output) →
 *   [ensureStored] (hash + upload + commit in one go), then record the digest on the attachment.
 *
 * [scope] owns the uploads: they survive the screen / request that started them and stop only
 * when the scope is cancelled.
 */
class AttachmentUploader(
    private val store: AttachmentStoreClient,
    private val scope: CoroutineScope
) {
    /** Starts hashing + uploading [file] in the background. [digest]: when already known. */
    fun start(file: File, digest: FileDigest? = null): AttachmentUpload = AttachmentUpload(file, store, scope, digest)

    /**
     * The attachment was removed before it was used: stop the upload and delete the pending
     * file from the store (best effort, in the background; the store's 24 h sweep covers a
     * failure — and an upload stopped at the very moment its last byte arrived, which the
     * store may still record after this delete). Does nothing to a file whose commit was
     * already requested.
     *
     * The store keeps one copy per hash: discarding one of two pending attachments of the same
     * file also removes it for the other, whose commit then uploads it again (see
     * [AttachmentUpload.commitWhenStored]).
     */
    fun discard(upload: AttachmentUpload): Job = scope.launch {
        if (upload.commitRequested) return@launch
        // Stopped first, so an upload still in flight cannot land after the delete
        upload.cancelAndJoin()
        val digest = upload.digest()
        if (digest != null && !upload.commitRequested) store.deletePending(digest.sha256)
    }

    /**
     * Hash + upload + commit, awaited: the lazy backfill of an attachment that has no hash yet
     * and the storing of a file this device just produced. The caller records the returned
     * digest on the attachment (`attachment.withDigest(...)`).
     */
    suspend fun ensureStored(file: File, digest: FileDigest? = null): StoreResult<FileDigest> {
        val known = digest ?: try {
            AttachmentHash.of(file)
        } catch (e: IOException) {
            return StoreResult.Failed(StoreError.LOCAL_IO, "Cannot read ${file.name}: ${e.message}")
        }
        when (val uploaded = store.upload(file, known)) {
            is StoreResult.Failed -> return uploaded
            is StoreResult.Ok -> if (uploaded.value.state == StoredState.COMMITTED) return StoreResult.Ok(known)
        }
        return commitStored(store, file, known)
    }

    /**
     * Commit a file whose digest is already on an attachment (e.g. a message saved while its
     * upload had not finished, sent again later). [file]: this device's copy, if it has one — it
     * is uploaded again when the store does not have the file.
     */
    suspend fun commit(digest: FileDigest, file: File? = null): StoreResult<FileDigest> = commitStored(store, file, digest)
}
