package com.example.ApI.data.attachments

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.repository.AttachmentOwner
import com.example.ApI.tools.PythonToolFiles
import com.example.ApI.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether [provider] reads [attachment]'s file when it sends a request (the same rule as the web
 * backend's `attachmentNeedsFile`): openai sends images / PDFs by `file_OPENAI_id` and inlines
 * every other type; poe and google send by their id; every other provider inlines the bytes.
 * A file that is not read does not hold a cloud run back.
 */
fun providerReadsFile(provider: String, attachment: Attachment): Boolean = when (provider) {
    "openai" -> {
        val sentById = attachment.mime_type.startsWith("image/") || attachment.mime_type == "application/pdf"
        !(sentById && !attachment.file_OPENAI_id.isNullOrBlank())
    }
    "poe" -> attachment.file_POE_url.isNullOrBlank()
    "google" -> attachment.file_GOOGLE_uri.isNullOrBlank() || attachment.file_GOOGLE_uri == "{file_URI}"
    else -> true
}

/** What the UI shows on a file that is on its way to the store. No state: nothing to show. */
sealed class AttachmentTransferState {
    /** Hashing ([total] = 0) or uploading. */
    data class Uploading(val sent: Long, val total: Long) : AttachmentTransferState()

    /** The upload failed; [DeviceAttachments.retry] starts it again. */
    data class Failed(val message: String) : AttachmentTransferState()
}

/** A request whose attachments were brought to the store as far as the time allowed. */
class PreparedRequest(
    /** The request's messages; attachments whose identity became known carry `sha256` + `size`. */
    val messages: List<Message>,
    val projectAttachments: List<Attachment>,
    /** Every file the provider reads is in the store (or is not this device's to upload). */
    val ready: Boolean,
    /** Names of the files that hold the request back (for the log). */
    val missing: List<String>
)

/**
 * The hashes this device has seen committed in the store, so a file is asked about once and not
 * for every request. Kept per account (store URL + token) in [dir]; without [dir], in memory.
 * A committed file is never deleted by the store, so an entry stays true; [forget] is for the
 * case the server says otherwise.
 */
class KnownStoredFiles(private val dir: File?, private val account: () -> String?) {
    private var loadedFor: String? = null
    private val hashes = HashSet<String>()

    @Synchronized
    fun contains(sha256: String): Boolean {
        load()
        return sha256 in hashes
    }

    @Synchronized
    fun add(sha256: String) {
        load()
        if (!hashes.add(sha256)) return
        try {
            file()?.appendText(sha256 + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun forget(sha256s: Collection<String>) {
        load()
        if (!hashes.removeAll(sha256s.toSet())) return
        try {
            file()?.writeText(hashes.joinToString("") { it + "\n" })
        } catch (_: Exception) {
        }
    }

    private fun file(): File? {
        val key = loadedFor ?: return null
        return dir?.let { File(it, "known_$key.txt") }
    }

    private fun load() {
        val key = account()
        if (key == loadedFor) return
        hashes.clear()
        loadedFor = key
        if (dir == null || key == null) return
        try {
            dir.mkdirs()
            // Another account's (or an older token's) list is of no use any more
            dir.listFiles()?.filter { it.name.startsWith("known_") && it.name != "known_$key.txt" }?.forEach { it.delete() }
            val file = File(dir, "known_$key.txt")
            if (file.isFile) file.forEachLine { line -> line.trim().takeIf(AttachmentHash::isValid)?.let(hashes::add) }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** A name for the account that does not reveal the token. */
        fun accountKey(config: AttachmentStoreConfig?): String? = config?.let {
            val md = MessageDigest.getInstance("SHA-256")
            AttachmentHash.hex(md.digest((it.baseUrl + "\n" + it.token).toByteArray())).take(16)
        }
    }
}

/**
 * A device's side of the attachment store (CLOUD_RUNS_PLAN.md, "Attachment store", Clients):
 *
 * - **drafts** — [attach] starts the upload when a file is attached, [transfers] is what the UI
 *   draws on it, [remove] deletes it from the store again, [use] is the send (hash for the saved
 *   message + commit);
 * - **requests** — [prepareRequest] brings the files of a request to the store before a cloud
 *   run (bounded; what is not done in time goes on in the background), [backfill] does the same
 *   without waiting; both hash + upload + commit attachments that were saved before the store
 *   existed and record their identity through [recordIdentity];
 * - **files of other devices** — [localize] gives the device path a copy of the request whose
 *   attachments point at this device's files (downloaded into the cache when needed).
 *
 * One instance per process and data directory. Everything is a no-op while sync is off
 * ([enabled] false): no upload, no hash, no state.
 *
 * @param scope owns the uploads; they outlive the screen and the request that started them.
 * @param recordIdentity `DataRepository.recordAttachmentIdentity`.
 */
class DeviceAttachments(
    val store: AttachmentStoreClient,
    val resolver: AttachmentResolver,
    private val scope: CoroutineScope,
    private val recordIdentity: (username: String, owner: AttachmentOwner, attachment: Attachment, digest: FileDigest) -> Boolean,
    private val known: KnownStoredFiles = KnownStoredFiles(null) { "memory" },
    /** How long [use] waits for the hash of a file (local I/O only). */
    private val hashWaitMs: Long = HASH_WAIT_MS,
    /** Pauses before a commit that could not reach the store is tried again. */
    private val commitRetryDelaysMs: List<Long> = COMMIT_RETRY_DELAYS_MS
) {
    companion object {
        const val HASH_WAIT_MS = 10_000L
        val COMMIT_RETRY_DELAYS_MS = listOf(2_000L, 10_000L, 30_000L)
        /** Files of one request that are stored at the same time. */
        const val PARALLEL_TRANSFERS = 3
    }

    private val uploader = AttachmentUploader(store, scope)

    /** Sync is on and signed in: attachments go to the store. */
    val enabled: Boolean get() = store.isConfigured

    // ── Drafts ────────────────────────────────────────────────────────────────

    private class Tracked(val file: File, val upload: AttachmentUpload, var used: Boolean) {
        var watcher: Job? = null
    }

    private val lock = Any()
    /** Files whose upload the UI follows, by local path. */
    private val tracked = HashMap<String, Tracked>()
    /** Uploads of files that were sent, by hash: a request waits for these instead of uploading again. */
    private val sent = HashMap<String, AttachmentUpload>()

    private val _transfers = MutableStateFlow<Map<String, AttachmentTransferState>>(emptyMap())

    /** By local path: the files that are uploading or whose upload failed. */
    val transfers: StateFlow<Map<String, AttachmentTransferState>> = _transfers

    /**
     * A file was attached: start hashing + uploading it in the background. Nothing happens when
     * sync is off or the file is larger than the store takes (it stays a device-only file).
     */
    fun attach(file: File) {
        if (!enabled || !file.isFile || file.length() > store.maxFileBytes) return
        track(file, uploader.start(file), used = false)
    }

    private fun track(file: File, upload: AttachmentUpload, used: Boolean): Tracked {
        val path = file.path
        val entry = Tracked(file, upload, used)
        synchronized(lock) {
            tracked.put(path, entry)?.watcher?.cancel()
            entry.watcher = scope.launch { upload.state.collect { state -> onState(path, entry, state) } }
        }
        return entry
    }

    private fun onState(path: String, entry: Tracked, state: AttachmentUploadState) {
        synchronized(lock) {
            if (tracked[path] !== entry) return
            when (state) {
                is AttachmentUploadState.Hashing -> show(path, AttachmentTransferState.Uploading(0, 0))
                is AttachmentUploadState.Uploading -> show(path, AttachmentTransferState.Uploading(state.sent, state.total))
                is AttachmentUploadState.Stored -> {
                    show(path, null)
                    if (state.committed) known.add(state.digest.sha256)
                    if (state.committed && entry.used) forgetTracked(path, entry)
                }
                is AttachmentUploadState.Failed ->
                    show(path, if (isDeviceOnly(state.error)) null else AttachmentTransferState.Failed(state.message))
                is AttachmentUploadState.Cancelled -> show(path, null)
            }
        }
    }

    /** Not a failure to show: the file simply is not stored (too large, or sync went off). */
    private fun isDeviceOnly(error: StoreError) = error == StoreError.TOO_LARGE || error == StoreError.NOT_CONFIGURED

    private fun show(path: String, state: AttachmentTransferState?) {
        _transfers.update { if (state == null) it - path else it + (path to state) }
    }

    private fun forgetTracked(path: String, entry: Tracked) {
        if (tracked[path] === entry) tracked.remove(path)
        entry.watcher?.cancel()
    }

    /** Start a failed upload of [path] again (the tap on a failed file). */
    fun retry(path: String) {
        val entry = synchronized(lock) { tracked[path] } ?: return
        val state = entry.upload.state.value
        if (state !is AttachmentUploadState.Failed && state !is AttachmentUploadState.Cancelled) return
        val digest = (state as? AttachmentUploadState.Failed)?.digest
        val again = track(entry.file, uploader.start(entry.file, digest), entry.used)
        if (entry.used) commitInBackground(path, again.upload)
    }

    /**
     * The file was removed. A draft (never sent) is deleted from the store — and its upload
     * stopped — so attaching and removing leaves nothing behind. For a file that is in use (a
     * project file) an upload still in flight is stopped; a committed file stays in the store.
     */
    fun remove(path: String) {
        val entry = synchronized(lock) {
            tracked.remove(path)?.also {
                it.watcher?.cancel()
                show(path, null)
            }
        } ?: return
        val upload = entry.upload
        if (!upload.commitRequested) {
            uploader.discard(upload)
            return
        }
        if (upload.state.value is AttachmentUploadState.Stored) return
        scope.launch {
            upload.cancelAndJoin()
            // Deletes only a pending file: one that another message committed stays
            upload.digest()?.let { store.deletePending(it.sha256) }
        }
    }

    /**
     * The attachment of the file at [path] is being saved in a message / project: returns it with
     * the file's identity (as soon as the file is hashed — the upload may still be running) and
     * commits the file when the store has it. An upload that failed is started again, and one is
     * started for a file that had none (sync was turned on after it was attached).
     * Unchanged when sync is off or the file is not stored (too large).
     */
    suspend fun use(path: String?, attachment: Attachment): Attachment {
        if (path == null || !enabled) return attachment
        val file = File(path)
        var entry = synchronized(lock) { tracked[path] }
        val state = entry?.upload?.state?.value
        if (state is AttachmentUploadState.Failed && isDeviceOnly(state.error)) return attachment
        if (entry == null || state is AttachmentUploadState.Failed || state is AttachmentUploadState.Cancelled) {
            if (!file.isFile || file.length() > store.maxFileBytes) return attachment
            entry = track(file, uploader.start(file, (state as? AttachmentUploadState.Failed)?.digest), used = true)
        } else {
            synchronized(lock) { entry.used = true }
        }
        val upload = entry.upload
        commitInBackground(path, upload)
        val digest = withTimeoutOrNull(hashWaitMs) { upload.digest() } ?: return attachment
        synchronized(lock) { sent[digest.sha256] = upload }
        return attachment.withDigest(digest)
    }

    /** Commit when stored; a store that cannot be reached is tried again a few times. */
    private fun commitInBackground(path: String, upload: AttachmentUpload) {
        scope.launch {
            var result = upload.commitWhenStored().await()
            for (pause in commitRetryDelaysMs) {
                val failed = result as? StoreResult.Failed ?: break
                if (failed.error != StoreError.UNAVAILABLE) break
                delay(pause)
                val digest = upload.digest() ?: break
                result = uploader.commit(digest, upload.file)
            }
            val digest = result.getOrNull()
            synchronized(lock) {
                val entry = tracked[path]?.takeIf { it.upload === upload }
                if (digest != null) {
                    known.add(digest.sha256)
                    if (entry != null) {
                        show(path, null)
                        forgetTracked(path, entry)
                    }
                }
                // Done either way: a later request stores the file itself if this did not
                if (digest != null || result is StoreResult.Failed) sent.values.remove(upload)
            }
        }
    }

    // ── Requests ──────────────────────────────────────────────────────────────

    private sealed class Plan {
        /** In the store as far as this device can tell. */
        object Ready : Plan()
        /** No hash and no file here: only the server can tell (its own legacy upload). */
        object Unknown : Plan()
        /** This device has the file but it cannot be stored (too large, sync off). */
        object DeviceOnly : Plan()
        class Pending(val digest: Deferred<FileDigest?>) : Plan()
    }

    private val jobs = ConcurrentHashMap<String, Deferred<FileDigest?>>()
    private val transferSlots = Semaphore(PARALLEL_TRANSFERS)
    /** path|length|modified → digest, so a file is hashed once per process. */
    private val digests = ConcurrentHashMap<String, FileDigest>()

    /** One store job per file at a time; it runs in [scope], whoever waits for it. */
    private fun job(key: String, block: suspend () -> FileDigest?): Deferred<FileDigest?> =
        jobs.compute(key) { _, running ->
            running?.takeIf { it.isActive } ?: scope.async {
                try {
                    transferSlots.withPermit { block() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("Storing an attachment failed: ${e.message}")
                    null
                }
            }
        }!!

    private fun plan(username: String, owner: AttachmentOwner?, attachment: Attachment): Plan {
        if (!enabled) return Plan.DeviceOnly
        val sha = attachment.sha256?.takeIf(AttachmentHash::isValid)
        val local = resolver.resolveLocal(attachment)?.file
        if (sha != null) {
            if (known.contains(sha)) return Plan.Ready
            val upload = synchronized(lock) { sent[sha] }
            // Not this device's file: it came from the store
            if (local == null && upload == null) return Plan.Ready
            return Plan.Pending(job("sha:$sha") { storeHashed(sha, attachment, local, upload) })
        }
        if (local == null) return Plan.Unknown
        if (local.length() > store.maxFileBytes) return Plan.DeviceOnly
        val stored = job("path:${local.path}") { storeUnhashed(local) }
        // The identity is recorded even when nobody waits for the upload any more
        return Plan.Pending(scope.async {
            stored.await()?.also { digest ->
                if (owner != null) {
                    try {
                        recordIdentity(username, owner, attachment, digest)
                    } catch (e: Exception) {
                        AppLogger.w("Recording the hash of ${attachment.file_name} failed: ${e.message}")
                    }
                }
            }
        })
    }

    /** A file whose hash is on the attachment: commit it (uploading it when the store lacks it). */
    private suspend fun storeHashed(sha: String, attachment: Attachment, local: File?, upload: AttachmentUpload?): FileDigest? {
        if (upload != null) {
            val result = upload.commitWhenStored().await()
            synchronized(lock) { sent.values.remove(upload) }
            result.getOrNull()?.let {
                known.add(sha)
                return it
            }
        }
        val file = local ?: return null
        val digest = FileDigest(sha, attachment.size ?: file.length())
        return uploader.commit(digest, file).getOrNull()?.also { known.add(sha) }
    }

    /** A file saved before the store existed: hash + upload + commit. */
    private suspend fun storeUnhashed(file: File): FileDigest? {
        val key = "${file.path}|${file.length()}|${file.lastModified()}"
        val cached = digests[key]
        if (cached != null && known.contains(cached.sha256)) return cached
        val digest = uploader.ensureStored(file, cached).getOrNull() ?: return null
        digests[key] = digest
        known.add(digest.sha256)
        return digest
    }

    private class Item(val attachment: Attachment, val needed: Boolean, val plan: Plan)

    private suspend fun plans(
        username: String,
        chatId: String,
        groupId: String?,
        messages: List<Message>,
        projectAttachments: List<Attachment>,
        needsFile: (Attachment) -> Boolean
    ): Pair<List<List<Item>>, List<Item>> = withContext(Dispatchers.IO) {
        val ofMessages = messages.map { message ->
            val owner = message.id.takeIf { it.isNotBlank() }?.let { AttachmentOwner.ChatMessage(chatId, it) }
            message.attachments.map { Item(it, message.role == "user" && needsFile(it), plan(username, owner, it)) }
        }
        val group = groupId?.let { AttachmentOwner.Group(it) }
        ofMessages to projectAttachments.map { Item(it, needsFile(it), plan(username, group, it)) }
    }

    /**
     * Before a cloud run: make sure the store has the files of the request. Files in flight (just
     * attached) are awaited, files saved before the store existed are hashed, uploaded, committed
     * and their identity recorded; at most [timeoutMs] is spent waiting for the files the provider
     * reads ([needsFile]; only attachments of user messages and the project's). What is not done by
     * then goes on in the background, and the request is not [PreparedRequest.ready]: the device
     * runs it. The other attachments of the chat are stored in the background without waiting.
     */
    suspend fun prepareRequest(
        username: String,
        chatId: String,
        groupId: String?,
        messages: List<Message>,
        projectAttachments: List<Attachment>,
        needsFile: (Attachment) -> Boolean,
        timeoutMs: Long
    ): PreparedRequest {
        if (!enabled || (messages.all { it.attachments.isEmpty() } && projectAttachments.isEmpty())) {
            return PreparedRequest(messages, projectAttachments, ready = true, missing = emptyList())
        }
        val (ofMessages, ofProject) = plans(username, chatId, groupId, messages, projectAttachments, needsFile)
        val all = ofMessages.flatten() + ofProject
        val awaited = all.filter { it.needed }.mapNotNull { (it.plan as? Plan.Pending)?.digest }
        if (awaited.isNotEmpty()) withTimeoutOrNull(timeoutMs) { awaited.awaitAll() }

        // What is done by now (the rest goes on in the background)
        val done = HashMap<Item, FileDigest>()
        for (item in all) {
            val pending = (item.plan as? Plan.Pending)?.digest?.takeIf { it.isCompleted && !it.isCancelled } ?: continue
            pending.await()?.let { done[item] = it }
        }
        fun Item.result(): Attachment = done[this]?.let { attachment.withDigest(it) } ?: attachment
        val missing = all.filter { item ->
            item.needed && when (item.plan) {
                is Plan.Ready, is Plan.Unknown -> false
                is Plan.DeviceOnly -> true
                is Plan.Pending -> item !in done
            }
        }
        return PreparedRequest(
            messages = messages.mapIndexed { i, message ->
                if (message.attachments.isEmpty()) message else message.copy(attachments = ofMessages[i].map { it.result() })
            },
            projectAttachments = ofProject.map { it.result() },
            ready = missing.isEmpty(),
            missing = missing.map { it.attachment.file_name }
        )
    }

    /**
     * The device runs the request: store its files in the background anyway (hash + upload +
     * commit + record), so the other devices and the web can show them and the chat can run in
     * the cloud next time.
     */
    fun backfill(username: String, chatId: String, groupId: String?, messages: List<Message>, projectAttachments: List<Attachment>) {
        if (!enabled || (messages.all { it.attachments.isEmpty() } && projectAttachments.isEmpty())) return
        scope.launch {
            try {
                plans(username, chatId, groupId, messages, projectAttachments) { false }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("Attachment backfill failed: ${e.message}")
            }
        }
    }

    /**
     * The server said it could not get a file of this request although this device believed the
     * store had it: ask the store again next time.
     */
    fun forgetStored(messages: List<Message>, projectAttachments: List<Attachment>) {
        val hashes = (messages.flatMap { it.attachments } + projectAttachments).mapNotNull { it.sha256 }
        if (hashes.isNotEmpty()) known.forget(hashes)
    }

    // ── Files of other devices ────────────────────────────────────────────────

    /**
     * For the device path: the request with every attachment the provider code may read (user
     * messages, project) pointing at a file on this device — its own path when the file is here,
     * else the cache copy, downloaded now when it is not cached. An attachment that cannot be had
     * is left as it is. The result is for the request only: it must not be saved (the path of a
     * cache copy belongs to this device).
     */
    suspend fun localize(messages: List<Message>, projectAttachments: List<Attachment>): Pair<List<Message>, List<Attachment>> {
        val candidates = messages.filter { it.role == "user" }.flatMap { it.attachments } + projectAttachments
        if (candidates.none { it.sha256 != null }) return messages to projectAttachments
        return withContext(Dispatchers.IO) {
            suspend fun Attachment.here(): Attachment {
                if (sha256 == null) return this
                val found = resolver.resolveLocal(this) ?: (resolver.resolve(this) as? ResolvedAttachment.Available)
                return when {
                    found == null || found.source == AttachmentSource.LOCAL -> this
                    else -> copy(local_file_path = found.file.absolutePath)
                }
            }
            val localMessages = messages.map { message ->
                if (message.role != "user" || message.attachments.isEmpty()) message
                else message.copy(attachments = message.attachments.map { it.here() })
            }
            localMessages to projectAttachments.map { it.here() }
        }
    }

    // ── Python tool ───────────────────────────────────────────────────────────

    /**
     * The device's files for the Python tool while sync is on: input files also from the store
     * (attachments of other devices), generated files saved in [outputDir] as always, plus their
     * hash on the attachment and a background upload + commit so the web can show them.
     */
    fun pythonFiles(outputDir: File): PythonToolFiles = object : PythonToolFiles {
        override fun isAvailable(attachment: Attachment): Boolean =
            attachment.local_file_path != null || attachment.sha256?.let(AttachmentHash::isValid) == true

        override suspend fun inputFile(attachment: Attachment): File? =
            resolver.resolve(attachment).fileOrNull ?: attachment.local_file_path?.let { File(it) }

        override suspend fun saveOutput(fileName: String, mimeType: String, bytes: ByteArray): Attachment? {
            val file = withContext(Dispatchers.IO) {
                outputDir.mkdirs()
                File(outputDir, "${UUID.randomUUID()}_$fileName").also { it.writeBytes(bytes) }
            }
            val attachment = Attachment(local_file_path = file.absolutePath, file_name = fileName, mime_type = mimeType)
            if (!enabled || bytes.size > store.maxFileBytes) return attachment
            val digest = FileDigest(AttachmentHash.hex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.size.toLong())
            // A failure is healed by the next request that includes the message (the hash is on it)
            job("sha:${digest.sha256}") {
                uploader.ensureStored(file, digest).getOrNull()?.also { known.add(it.sha256) }
            }
            return attachment.withDigest(digest)
        }
    }
}
