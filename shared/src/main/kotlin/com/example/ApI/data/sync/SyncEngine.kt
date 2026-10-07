package com.example.ApI.data.sync

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.RemoteSyncSettings
import com.example.ApI.data.repository.MigrationResult
import com.example.ApI.data.sync.merge.SyncFileMerger
import com.example.ApI.util.AppLogger
import com.example.ApI.util.AtomicFiles
import com.example.ApI.util.FileLocks
import com.example.ApI.util.SyncHolds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central sync coordinator: keeps the tracked local files and the account's copies on the sync
 * server merged (SYNC_MERGE_PLAN.md §4).
 *
 * Lifecycle:
 *   - Obtain it with [forDir] (one engine per data dir per process, shared by every repository
 *     over that dir, e.g. Android's UI and StreamingService).
 *   - Call [start] after settings are loaded (only does real work when enabled).
 *   - Call [pullNow] on app resume / "Sync now" button / periodically.
 *   - Register the [onFileWritten] callback with all storage managers so every local write is
 *     uploaded soon (debounced).
 *
 * Design invariants:
 *   - Every tracked file is synced by [syncFile]-style steps against its BASE (the last version
 *     both sides agreed on: server version + the matching local content, snapshot in
 *     `sync_base/`): local changed = its sha differs from the base's; remote changed = its
 *     version and sha differ from the base's.  Both changed → 3-way merge (no base → 2-way union)
 *     via [SyncFileMerger].  Change detection is content based, so lost flags, edits made while
 *     sync was off or by another process, and failed uploads are all picked up by the next sync.
 *   - Uploads are compare-and-swap PUTs (`base_version`); a 409 restarts the file's sync (merge
 *     with the newer remote copy).  The server never loses a version it didn't merge.  A server
 *     without CAS (no `"cas": true` in `/sync/health` / the PUT response) is not synced with at
 *     all ([serverLacksCas]): it would silently apply stale uploads, which the next 3-way merge
 *     elsewhere would read as deletions.
 *   - A remote copy OLDER than the base (server restored from a backup) or one this client can't
 *     decode is never merged as "the remote deleted it" / overwritten: the first is merged 2-way
 *     (union), the second leaves both copies untouched.
 *   - A merge result is written locally only if the local file is still what was merged (checked
 *     under the file's [FileLocks] lock, the one every local read-modify-write holds); otherwise
 *     the file's sync restarts.  A local write is never overwritten.
 *   - All sync work of one engine is serialized by a [Mutex].
 *   - `sync_state.json` and `sync_base/` are never uploaded.
 *   - `app_settings.json` uploads strip the `remoteSync` block; its device-local keys
 *     (`remoteSync`, `current_user`) are never adopted.  Change detection and base snapshots
 *     use the stripped (upload) form.
 *   - A file under a [SyncHolds] hold (rebuilt locally from an unreadable copy) is never
 *     uploaded as is: pull merges it into the remote copy with an empty base, then releases it.
 *   - The sync state belongs to the signed-in account (set at sign-in, [prepareForSignIn]);
 *     signing into another account resets the state and base snapshots and adopts the new
 *     account's copies of the account-global files instead of merging the old account's into
 *     them (only the switch-time copies: later local changes are merged).  Renaming the local
 *     user while signed in is not an account switch.
 *
 * ### Re-authentication flow
 * If any authenticated server call returns HTTP 401 (token revoked/expired), [needsReauth] is
 * set to `true` and further uploads/pulls are skipped to avoid hammering the server.
 * The UI should observe [needsReauth] and prompt the user to sign in again.
 * Call [clearReauth] after a successful re-sign-in to resume normal operation.
 */
class SyncEngine(
    private val internalDir: File,
    private val json: Json,
    private val settingsProvider: () -> AppSettings,
    private val uploadDebounceMs: Long = UPLOAD_DEBOUNCE_MS,
    private val retryDelayMs: Long = RETRY_DELAY_MS
) {
    companion object {
        private const val TAG = "SyncEngine"
        const val UPLOAD_DEBOUNCE_MS = 750L

        /** A sync that failed (network, too many conflicts) is retried by a pull after this. */
        const val RETRY_DELAY_MS = 30_000L

        /** Restarts of one file's sync (409 / local write during a merge) per cycle. */
        private const val MAX_ATTEMPTS = 5

        const val APP_SETTINGS = "app_settings.json"

        /** Synced files whose name is the same in every account (not per-user). */
        val ACCOUNT_GLOBAL_FILES = setOf(APP_SETTINGS, "skills_enabled.json", "skills_sources.json")

        private val registry = HashMap<String, SyncEngine>()

        private fun registryKey(dir: File): String = try {
            dir.canonicalPath
        } catch (e: IOException) {
            dir.absoluteFile.normalize().path
        }

        /**
         * The process-wide engine of [internalDir] (keyed by canonical path), created on first
         * use.  Every repository over the same data dir must share it, so that one Mutex
         * serializes all sync work and one [SyncState] is in memory.  The parameters of the first
         * caller win.
         */
        fun forDir(
            internalDir: File,
            json: Json,
            uploadDebounceMs: Long = UPLOAD_DEBOUNCE_MS,
            retryDelayMs: Long = RETRY_DELAY_MS,
            settingsProvider: () -> AppSettings
        ): SyncEngine = synchronized(registry) {
            val key = registryKey(internalDir)
            registry[key]?.takeUnless { it.closed }
                ?: SyncEngine(internalDir, json, settingsProvider, uploadDebounceMs, retryDelayMs).also { registry[key] = it }
        }
    }

    // SupervisorJob does NOT swallow uncaught exceptions from `launch` — without a
    // CoroutineExceptionHandler, any Throwable escaping an upload/pull coroutine is
    // handed to the thread's default uncaught-exception handler and crashes the app.
    // Sync is best-effort and must never take the app down.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.e("[$TAG] Uncaught error in sync coroutine", throwable)
    }

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO + exceptionHandler)

    /** Serializes every pull / upload / sign-in step of this engine. */
    private val mutex = Mutex()

    private val syncState = SyncState(internalDir, json)
    private val baseStore = SyncBaseStore(internalDir)

    @Volatile
    private var closed = false

    private val _changeTick = MutableStateFlow(0L)

    /**
     * Incremented whenever a sync changed the content of one or more local files so that
     * ViewModels can observe the flow and reload their data.
     */
    val changeTick: StateFlow<Long> = _changeTick.asStateFlow()

    private val _needsReauth = MutableStateFlow(false)

    /**
     * `true` when the last authenticated call received HTTP 401.
     * While `true`, no further uploads or pulls are attempted.
     * Reset to `false` by [clearReauth] (called after successful re-sign-in) or
     * after any successful authenticated call.
     */
    val needsReauth: StateFlow<Boolean> = _needsReauth.asStateFlow()

    private val _serverLacksCas = MutableStateFlow(false)

    /**
     * `true` when the sync server does not support compare-and-swap uploads (it predates
     * `base_version`).  Nothing is synced with such a server; checked again on every pull.
     */
    val serverLacksCas: StateFlow<Boolean> = _serverLacksCas.asStateFlow()

    /** The server URL whose CAS support was last confirmed (null: not confirmed). */
    @Volatile
    private var casConfirmedFor: String? = null

    // Per-filename debounce jobs
    private class PendingUpload(val job: Job) {
        /** Past the debounce: never cancelled any more (its PUT may already be on the wire). */
        var started = false
    }

    private val pendingUploads = mutableMapOf<String, PendingUpload>()
    private val pendingLock = Any()
    private var retryJob: Job? = null
    private val pullQueued = AtomicBoolean(false)

    init {
        syncState.load()
    }

    // ── Public surface ────────────────────────────────────────────────────────

    /** Called by DataRepository after construction if sync is enabled. */
    fun start() {
        val settings = settingsProvider()
        if (!settings.remoteSync.enabled) return
        requestPull()
    }

    /** External trigger: app resume, "Sync now" button press, periodic timer, etc. */
    fun pullNow() {
        val settings = settingsProvider()
        if (!settings.remoteSync.enabled) return
        requestPull()
    }

    /**
     * Called by each storage manager after every local file write.
     * Must be fast (runs on the caller's thread — often the UI thread, via the
     * save paths whose callers only catch IOException).  Fire-and-forget:
     * NOTHING here may throw, or it would escape into the save/UI call stack.
     */
    fun onFileWritten(file: File) {
        try {
            if (closed) return
            val settings = settingsProvider()
            if (!settings.remoteSync.enabled) return

            val filename = file.name
            if (!isTracked(filename, settings)) return

            // Scheduling hint only (persisted with the next state save); the sync itself
            // detects the change from the content
            syncState.markDirty(filename)
            scheduleUpload(filename)
        } catch (t: Throwable) {
            AppLogger.e("[$TAG] onFileWritten(${file.name}) failed — sync skipped", t)
        }
    }

    /** Health-check the remote server with current settings.  Used by UI "Test connection". */
    suspend fun testConnection(): Boolean {
        val settings = settingsProvider()
        if (!settings.remoteSync.enabled) return false
        return try {
            val client = buildClient(settings)
            // Reachable is not enough: this engine refuses to sync with a server without CAS
            client.health() && checkCas(client, settings, recheck = true) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clear the re-authentication flag.
     *
     * Must be called after a successful re-sign-in so that normal upload/pull
     * scheduling resumes.
     */
    fun clearReauth() {
        _needsReauth.value = false
        AppLogger.d("[$TAG] clearReauth(): needsReauth reset — resuming sync")
    }

    /**
     * Sync every tracked file now (waits for any sync in progress first).  Public for callers
     * that need to wait for the result (tests, a login that wants the account's data first);
     * fire-and-forget callers use [pullNow].
     */
    suspend fun pull() {
        if (closed) return
        mutex.withLock { pullLocked() }
    }

    /** Sync one tracked file now (what a debounced upload does), waiting for the result. */
    suspend fun uploadNow(filename: String) {
        if (closed) return
        mutex.withLock { uploadLocked(filename) }
    }

    /** Run every pending debounced upload now instead of after its debounce. */
    suspend fun flushPendingUploads() {
        val names = synchronized(pendingLock) {
            pendingUploads.filterValues { !it.started }.keys.toList().onEach { name ->
                pendingUploads.remove(name)?.job?.cancel()
            }
        }
        for (name in names) uploadNow(name)
    }

    /**
     * Run [block] while holding the engine's sync lock, so no pull/upload interleaves with it
     * (sign-in: migration + reset + credentials).  [block] must not call [pull]/[uploadNow].
     */
    suspend fun <T> runExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Forget every base and snapshot: the next sync of each file is a 2-way merge (or, with
     * [adoptRemoteGlobals], takes the account's copy of the account-global files).  Pending
     * uploads and retries are cancelled.  Used on sign-out ([accountUsername] = "") and when
     * signing into another account.  Work already in flight can't record into the new state.
     */
    fun resetSyncState(accountUsername: String = "", adoptRemoteGlobals: Boolean = false) {
        synchronized(pendingLock) {
            pendingUploads.values.filter { !it.started }.forEach { it.job.cancel() }
            pendingUploads.clear()
            retryJob?.cancel()
            retryJob = null
        }
        resetState(accountUsername, adoptRemoteGlobals)
        AppLogger.i("[$TAG] Sync state reset (account='$accountUsername', adoptRemoteGlobals=$adoptRemoteGlobals)")
    }

    /**
     * Sign-in, after [com.example.ApI.data.repository.UserMigration] (inside [runExclusive]):
     * make the sync state belong to [username].  Local `default` data moved into the account →
     * fresh state (the first sync 2-way merges it with the account's copies); a switch from
     * another account → fresh state that adopts the account's global files; re-signing into the
     * account the state belongs to (e.g. after a 401) keeps the bases.
     */
    fun prepareForSignIn(username: String, migration: MigrationResult) {
        when (migration) {
            // The state already belongs to this account (the local user was renamed meanwhile):
            // its bases still describe this account's copies
            is MigrationResult.Switched -> if (syncStateAccount() != username) resetSyncState(username, adoptRemoteGlobals = true)
            is MigrationResult.Migrated -> resetSyncState(username)
            MigrationResult.NoOp -> {
                val account = syncStateAccount()
                if (account != null && account != username) resetSyncState(username, adoptRemoteGlobals = account.isNotEmpty())
            }
        }
    }

    /**
     * The content of [filename] at its last synced base (upload form), or null if there is none
     * (never synced, state reset, snapshot lost).  E.g. to tell local-only chats from synced ones.
     */
    fun baseContent(filename: String): String? = baseStore.read(filename, syncState.entry(filename)?.baseLocalSha)

    /** The account the current sync state belongs to (null: legacy state, "": reset). */
    fun syncStateAccount(): String? = syncState.accountUsername()

    /** Stop all work of this engine and drop it from the registry. */
    fun close() {
        closed = true
        scope.cancel()
        synchronized(registry) {
            val key = registryKey(internalDir)
            if (registry[key] === this) registry.remove(key)
        }
    }

    /** [close] and wait until every coroutine of the engine finished. */
    suspend fun closeAndJoin() {
        close()
        scopeJob.join()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun buildClient(settings: AppSettings): RemoteStorageClient =
        RemoteStorageClient(
            baseUrl = settings.remoteSync.serverBaseUrl,
            token = settings.remoteSync.authToken
        )

    /**
     * The set of filenames we should sync for the current user.
     * Evaluated fresh each call so it automatically picks up user switches.
     */
    private fun trackedFilenames(settings: AppSettings): Set<String> {
        val u = settings.current_user
        return buildSet {
            add("chat_history_$u.json")
            add(APP_SETTINGS)
            add("custom_providers_$u.json")
            add("full_custom_providers_$u.json")
            add("github_auth_$u.json")
            add("google_workspace_auth_$u.json")
            add("skills_enabled.json")
            add("skills_sources.json")
            if (settings.remoteSync.syncApiKeys) {
                add("api_keys_$u.json")
            }
        }
    }

    private fun isTracked(filename: String, settings: AppSettings): Boolean =
        filename in trackedFilenames(settings)

    /**
     * Make sure the sync state belongs to an account: a legacy state (no account recorded) is
     * adopted for [user] with its bases; a reset state starts fresh for [user].  A state of
     * another account is kept: the account is set at sign-in ([prepareForSignIn], which resets on
     * a real switch), so `current_user` differing from it means the local user was renamed while
     * signed in — still the same server account, whose bases (keyed by filename) stay valid.
     */
    private fun ensureAccount(user: String) {
        when (val account = syncState.accountUsername()) {
            user -> {}
            null -> syncState.adoptAccount(user)
            "" -> resetState(user, adoptGlobals = false)
            else -> AppLogger.d("[$TAG] current_user '$user' differs from the signed-in account '$account'; keeping the sync state")
        }
    }

    /**
     * Reset the state to [account] and drop the base snapshots.  With [adoptGlobals] the
     * account-global files are marked "adopt remote"; their current local copies are kept as
     * their base snapshots, so only those switch-time copies give way to the account's.
     */
    private fun resetState(account: String, adoptGlobals: Boolean) {
        syncState.reset(
            account,
            if (adoptGlobals) ACCOUNT_GLOBAL_FILES else emptySet(),
            adoptRemoteBase = {
                ACCOUNT_GLOBAL_FILES.associateWith { filename ->
                    val local = try {
                        File(internalDir, filename).takeIf { it.exists() }?.readText(Charsets.UTF_8)
                    } catch (e: IOException) {
                        null
                    }
                    val form = local?.let { uploadForm(filename, it) }
                    if (form == null) "" else sha256Hex(form).also { baseStore.write(filename, form) }
                }
            }
        ) { baseStore.clear() }
    }

    /**
     * Whether the server at [settings]' URL does CAS (null: unreachable).  A confirmation is
     * cached per URL; [recheck] asks the server again (every pull, so a server rolled back to a
     * version without CAS is noticed).
     */
    private suspend fun checkCas(client: RemoteStorageClient, settings: AppSettings, recheck: Boolean): Boolean? {
        val url = settings.remoteSync.serverBaseUrl
        if (!recheck && casConfirmedFor == url) return true
        val cas = client.casSupported() ?: return null
        onCasResult(url, cas)
        return cas
    }

    private fun onCasResult(url: String, cas: Boolean) {
        casConfirmedFor = if (cas) url else null
        if (!cas && !_serverLacksCas.value) {
            AppLogger.e("[$TAG] Sync server $url does not support compare-and-swap uploads (\"cas\"); not syncing with it")
        }
        _serverLacksCas.value = !cas
    }

    /** What is uploaded / compared for the local [text] of [filename] (null: unreadable app_settings). */
    private fun uploadForm(filename: String, text: String): String? {
        if (filename != APP_SETTINGS) return text
        return try {
            // Strip the remoteSync block so tokens/config stay device-local
            val parsed = json.decodeFromString<AppSettings>(text)
            json.encodeToString(parsed.copy(remoteSync = RemoteSyncSettings()))
        } catch (e: Exception) {
            null
        }
    }

    private fun bumpChangeTick() {
        _changeTick.update { it + 1 }
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    /** Queue one pull; requests made while one is already queued are coalesced. */
    private fun requestPull() {
        if (closed) return
        if (!pullQueued.compareAndSet(false, true)) return
        scope.launch {
            try {
                mutex.withLock {
                    pullQueued.set(false)
                    pullLocked()
                }
            } finally {
                pullQueued.set(false)
            }
        }
    }

    private fun scheduleUpload(filename: String) {
        // Skip scheduling when a re-authentication is required
        if (_needsReauth.value || closed) return

        synchronized(pendingLock) {
            val existing = pendingUploads[filename]
            if (existing != null && !existing.started) existing.job.cancel()
            lateinit var pending: PendingUpload
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(uploadDebounceMs)
                synchronized(pendingLock) { pending.started = true }
                try {
                    mutex.withLock { uploadLocked(filename) }
                } finally {
                    synchronized(pendingLock) {
                        if (pendingUploads[filename] === pending) pendingUploads.remove(filename)
                    }
                }
            }
            pending = PendingUpload(job)
            pendingUploads[filename] = pending
            job.start()
        }
    }

    /** Retry a failed sync with a pull after [retryDelayMs] (one retry pending at a time). */
    private fun scheduleRetry() {
        if (retryDelayMs <= 0 || closed) return
        synchronized(pendingLock) {
            if (retryJob?.isActive == true) return
            retryJob = scope.launch {
                delay(retryDelayMs)
                requestPull()
            }
        }
    }

    // ── Upload (debounced, one file) ─────────────────────────────────────────

    private suspend fun uploadLocked(filename: String) {
        val settings = settingsProvider()
        if (!settings.remoteSync.enabled) return
        if (_needsReauth.value) return
        if (!isTracked(filename, settings)) return
        ensureAccount(settings.current_user)
        val client = buildClient(settings)
        when (checkCas(client, settings, recheck = false)) {
            true -> {}
            false -> return  // never upload to a server that would ignore the base
            null -> {
                AppLogger.w("[$TAG] upload($filename): sync server unreachable — will retry")
                scheduleRetry()
                return
            }
        }

        // Optimistic: assume the server is still at our base; the CAS PUT verifies it and a 409
        // hands us the current version (no base yet → create-only PUT, same)
        val entry = syncState.entry(filename)
        val assumed = entry?.takeIf { it.baseServerVersion > 0 }
            ?.let { BlobMeta(filename, it.baseServerVersion, it.baseServerSha ?: "") }
        val sync = FileSync(client, settings, filename, uploadOnly = true, generation = syncState.generation)
        try {
            if (!sync.run(assumed)) scheduleRetry()
            _needsReauth.value = false  // Successful calls — clear any stale flag
        } catch (e: RemoteSyncException.Unauthorized) {
            _needsReauth.value = true
            AppLogger.e("[$TAG] upload($filename): 401 Unauthorized — needsReauth set", e)
        } catch (e: RemoteSyncException.CasUnsupported) {
            onCasResult(settings.remoteSync.serverBaseUrl, cas = false)
            AppLogger.e("[$TAG] upload($filename): the server ignored base_version", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("[$TAG] upload($filename) failed — will retry", e)
            scheduleRetry()
        } finally {
            if (sync.wroteLocal) bumpChangeTick()
        }
    }

    // ── Pull (every tracked file) ────────────────────────────────────────────

    private suspend fun pullLocked() {
        val settings = settingsProvider()
        if (!settings.remoteSync.enabled) return
        if (_needsReauth.value) return
        ensureAccount(settings.current_user)
        val generation = syncState.generation

        val tracked = trackedFilenames(settings)
        val client = buildClient(settings)

        when (checkCas(client, settings, recheck = true)) {
            true -> {}
            false -> return
            null -> {
                AppLogger.w("[$TAG] pull(): sync server unreachable")
                scheduleRetry()
                return
            }
        }

        val manifestEntries: List<BlobMeta> = try {
            client.manifest()
        } catch (e: RemoteSyncException.Unauthorized) {
            _needsReauth.value = true
            AppLogger.e("[$TAG] pull(): manifest 401 Unauthorized — needsReauth set", e)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("[$TAG] pull(): manifest failed", e)
            scheduleRetry()
            return
        }

        // Successful manifest call — clear any stale reauth flag
        _needsReauth.value = false

        // Index by filename for O(1) lookup
        val remoteIndex = manifestEntries.associateBy { it.filename }

        var anythingChanged = false
        var incomplete = false
        try {
            for (filename in tracked) {
                val sync = FileSync(client, settings, filename, uploadOnly = false, generation = generation)
                try {
                    if (!sync.run(remoteIndex[filename])) incomplete = true
                } catch (e: RemoteSyncException.Unauthorized) {
                    throw e
                } catch (e: RemoteSyncException.CasUnsupported) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("[$TAG] pull(): sync of $filename failed — will retry", e)
                    incomplete = true
                } finally {
                    if (sync.wroteLocal) anythingChanged = true
                }
            }
        } catch (e: RemoteSyncException.Unauthorized) {
            _needsReauth.value = true
            AppLogger.e("[$TAG] pull(): 401 Unauthorized — needsReauth set", e)
        } catch (e: RemoteSyncException.CasUnsupported) {
            onCasResult(settings.remoteSync.serverBaseUrl, cas = false)
            AppLogger.e("[$TAG] pull(): the server ignored base_version; stopping", e)
            return
        } finally {
            // Even after a 401 mid-pull: files already rewritten must be reloaded by the UI
            if (anythingChanged) bumpChangeTick()
        }
        if (incomplete && !_needsReauth.value) scheduleRetry()
    }

    // ── One file ─────────────────────────────────────────────────────────────

    /** Restart the file's sync with the server at [meta] (null: the blob does not exist). */
    private class Restart(val meta: BlobMeta?)

    /**
     * The sync of one tracked file (plan §4 `syncFile`), restarted on a 409 or on a local
     * write that raced the merge, at most [MAX_ATTEMPTS] times per cycle.
     *
     * @param uploadOnly a debounced upload: never touches a held or locally missing file
     *        (those are reconciled by the next pull).
     */
    private inner class FileSync(
        private val client: RemoteStorageClient,
        private val settings: AppSettings,
        private val filename: String,
        private val uploadOnly: Boolean,
        private val generation: Long
    ) {
        private val file = File(internalDir, filename)
        private var cachedBlob: RemoteBlob? = null

        /** True once this sync changed the local file's content. */
        var wroteLocal = false
            private set

        /** Set when the file was deliberately left unsynced for now (retried later). */
        private var skipped = false

        /** @return false if the file is left unsynced for now (too many restarts, unmergeable remote). */
        suspend fun run(initialMeta: BlobMeta?): Boolean {
            var meta = initialMeta
            repeat(MAX_ATTEMPTS) {
                val restart = attempt(meta) ?: return !skipped
                meta = restart.meta
            }
            AppLogger.w("[$TAG] $filename: still conflicting after $MAX_ATTEMPTS attempts; leaving it for the next sync")
            return false
        }

        private fun stale(): Boolean = syncState.generation != generation

        private suspend fun attempt(meta: BlobMeta?): Restart? {
            if (stale()) return null
            val held = SyncHolds.isHeld(file)
            if (held && uploadOnly) {
                AppLogger.w("[$TAG] $filename: sync held (rebuilt from an unreadable copy), not uploading")
                return null
            }
            val local = readLocal()
            if (local == null && uploadOnly) return null
            val localForm = local?.let { uploadForm(filename, it) }
            if (local != null && localForm == null) {
                AppLogger.e("[$TAG] $filename: local copy unreadable; not syncing it", IOException(file.path))
                return null
            }
            val entry = syncState.entry(filename)

            // 1. Not on the server: create it (create-only, so a concurrent creation is merged)
            if (meta == null) {
                if (localForm == null) return null
                if (!SyncFileMerger.isValid(filename, localForm, json)) {
                    AppLogger.e("[$TAG] $filename: local copy does not parse; not uploading it", IOException(file.path))
                    return null
                }
                if (held) SyncHolds.release(file)  // nothing remote to protect
                return put(localForm, baseVersion = 0L)
            }

            // 2. Same content on both sides: this is the new base
            if (localForm != null && sha256Hex(localForm) == meta.sha) {
                if (held) SyncHolds.release(file)
                record(meta, localForm)
                return null
            }

            val adoptRemote = syncState.shouldAdoptRemote(filename)
            // Versions only grow, so a remote copy older than the base means the server lost
            // versions (restored from a backup): our base is newer than anything it has
            val rolledBack = entry != null && meta.updated_at < entry.baseServerVersion &&
                (entry.baseServerSha == null || meta.sha != entry.baseServerSha)
            val remoteChanged = held || adoptRemote || local == null || entry == null || rolledBack ||
                entry.baseServerVersion == 0L ||
                (meta.updated_at != entry.baseServerVersion && (entry.baseServerSha == null || meta.sha != entry.baseServerSha))

            if (!remoteChanged) {
                entry!!
                localForm!!
                if (meta.updated_at != entry.baseServerVersion) {
                    // Same content re-labelled with another version: follow it, or our next CAS PUT would 409
                    syncState.updateServerVersion(filename, meta.updated_at, meta.sha, generation)
                }
                val localChanged = entry.baseLocalSha == null || sha256Hex(localForm) != entry.baseLocalSha
                if (!localChanged) return null
                // 4. Only local changed: upload it on top of the base version
                if (!SyncFileMerger.isValid(filename, localForm, json)) {
                    AppLogger.e("[$TAG] $filename: local copy does not parse; not uploading it", IOException(file.path))
                    return null
                }
                return put(localForm, baseVersion = meta.updated_at)
            }

            // 3. Remote changed: merge it with the local copy against the base
            val blob = cachedBlob?.takeIf { it.updated_at == meta.updated_at && it.sha == meta.sha }
                ?: client.get(filename)
                ?: return Restart(null)  // vanished between manifest and GET
            cachedBlob = blob
            val blobMeta = BlobMeta(filename, blob.updated_at, blob.sha)

            // A remote copy this client can't decode is neither adopted nor overwritten
            if (!SyncFileMerger.isValid(filename, blob.content, json)) {
                AppLogger.e("[$TAG] $filename: remote copy (version ${blob.updated_at}) does not parse; leaving both copies untouched")
                skipped = true
                return null
            }

            val base: String? = when {
                held -> null  // the rebuilt copy is not a descendant of any base: union
                // Everything the server lost would read as "deleted remotely": union instead
                rolledBack || (entry != null && blob.updated_at < entry.baseServerVersion && blob.sha != entry.baseServerSha) -> {
                    AppLogger.w("[$TAG] $filename: server copy (version ${blob.updated_at}) is older than our base (${entry?.baseServerVersion}); merging without a base")
                    null
                }
                // Account switch: the switch-time local copy is the base, so it gives way to the
                // account's copy while changes made since the switch are merged
                adoptRemote -> when (val switchSha = syncState.adoptRemoteBaseSha(filename)) {
                    null -> localForm  // mark from before switch-time copies were kept
                    "" -> null  // no local copy at the switch: whatever is here now is newer
                    else -> baseStore.read(filename, switchSha) ?: localForm?.takeIf { sha256Hex(it) == switchSha }
                }
                else -> baseStore.read(filename, entry?.baseLocalSha)
                    // Snapshot lost but the local copy is unchanged since the base: it IS the base
                    ?: localForm?.takeIf { entry?.baseLocalSha != null && sha256Hex(it) == entry.baseLocalSha }
                    // Upgrade path (state from before snapshots existed): the base IS this version
                    ?: blob.content.takeIf { entry != null && entry.baseServerVersion == blob.updated_at }
            }
            val merged = if (local == null) remoteForLocal(blob.content)
            else SyncFileMerger.tryMergeFile(filename, base, local, blob.content, json) ?: run {
                AppLogger.e("[$TAG] $filename: merge failed; leaving both copies untouched")
                skipped = true
                return null
            }
            val mergedForm = uploadForm(filename, merged) ?: run {
                AppLogger.e("[$TAG] $filename: merge result unreadable; not syncing it", IOException(file.path))
                return null
            }

            // Write the merge only if the local file is still what we merged
            var raced = false
            FileLocks.withLock(file) {
                if (readLocal() != local) {
                    raced = true
                } else {
                    if (merged != local) {
                        if (local != null) preserveIfUnreadable(local)
                        AtomicFiles.write(file, merged)
                        wroteLocal = true
                    }
                    if (held) SyncHolds.release(file)
                }
            }
            if (raced) {
                AppLogger.d("[$TAG] $filename: local write during the merge; restarting")
                return Restart(blobMeta)
            }

            if (SyncFileMerger.sameContent(filename, mergedForm, blob.content, json)) {
                // Nothing for the server: the merge is the remote copy (+ device view state)
                record(blobMeta, mergedForm)
                return null
            }
            // The remote copy is the base until our upload of the merge lands
            record(blobMeta, blob.content)
            return put(mergedForm, baseVersion = blob.updated_at)
        }

        /** CAS PUT; on success [content] is the new base.  409 → restart at the current version. */
        private suspend fun put(content: String, baseVersion: Long): Restart? {
            if (stale()) return null
            val meta = try {
                client.put(filename, content, baseVersion)
            } catch (e: RemoteSyncException.Conflict) {
                AppLogger.d("[$TAG] $filename: version conflict (base $baseVersion, server ${e.current?.updated_at}); restarting")
                return Restart(e.current)
            }
            record(meta, content)
            AppLogger.d("[$TAG] Uploaded $filename (server updated_at=${meta.updated_at})")
            return null
        }

        private fun record(meta: BlobMeta, snapshot: String) {
            val sha = sha256Hex(snapshot)
            val entry = syncState.entry(filename)
            if (entry != null && entry.baseServerVersion == meta.updated_at && entry.baseServerSha == meta.sha &&
                entry.baseLocalSha == sha && !entry.dirty && !syncState.shouldAdoptRemote(filename) &&
                baseStore.file(filename).exists()
            ) return
            syncState.recordBase(filename, meta.updated_at, meta.sha, sha, generation) {
                baseStore.write(filename, snapshot)
            }
        }

        private fun readLocal(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

        /** The remote copy as a local file (app_settings: with this device's local keys). */
        private fun remoteForLocal(remote: String): String {
            if (filename != APP_SETTINGS) return remote
            return try {
                val parsed = json.decodeFromString<AppSettings>(remote)
                json.encodeToString(parsed.copy(remoteSync = settings.remoteSync, current_user = settings.current_user))
            } catch (e: Exception) {
                remote
            }
        }

        /** Keep a byte-exact copy of an unreadable local file before a merge replaces it. */
        private fun preserveIfUnreadable(local: String) {
            if (SyncFileMerger.isValid(filename, local, json)) return
            try {
                val prefix = "$filename.corrupt-"
                val bytes = local.toByteArray(Charsets.UTF_8)
                val kept = internalDir.listFiles()?.any { other ->
                    other.name.startsWith(prefix) && other.length() == bytes.size.toLong() && other.readBytes().contentEquals(bytes)
                } ?: false
                if (!kept) AtomicFiles.writeBytes(File(internalDir, "$prefix${System.currentTimeMillis()}"), bytes)
            } catch (e: Exception) {
                AppLogger.e("[$TAG] Could not preserve unreadable $filename", e)
            }
        }
    }
}
