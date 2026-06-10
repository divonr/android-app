package com.example.ApI.data.sync

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.RemoteSyncSettings
import com.example.ApI.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Central sync coordinator.
 *
 * Lifecycle:
 *   - Construct once in [DataRepository].
 *   - Call [start] after settings are loaded (only does real work when enabled).
 *   - Call [pullNow] on app resume / "Sync now" button.
 *   - Register the [onFileWritten] callback with all four storage managers so every local
 *     write is captured.
 *
 * Design invariants:
 *   - Local files are the source of truth; we never discard a local dirty write.
 *   - The server's `updated_at` timestamp is the authority for "which server version do we have".
 *   - We never compare local wall-clock against server timestamps.
 *   - `sync_state.json` is never uploaded.
 *   - `app_settings.json` uploads strip the `remoteSync` block; pulls MERGE it back from local.
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
    private val settingsProvider: () -> AppSettings
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val UPLOAD_DEBOUNCE_MS = 750L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val syncState = SyncState(internalDir, json)

    private val _changeTick = MutableStateFlow(0L)

    /**
     * Incremented whenever a pull overwrites one or more local files so that ViewModels
     * can observe the flow and reload their data.
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

    // Per-filename debounce jobs
    private val pendingUploads = mutableMapOf<String, Job>()
    private val pendingLock = Any()

    init {
        syncState.load()
    }

    // ── Public surface ────────────────────────────────────────────────────────

    /** Called by DataRepository after construction if sync is enabled. */
    fun start() {
        val settings = settingsProvider()
        if (!settings.remoteSync.enabled) return
        scope.launch { pull() }
    }

    /** External trigger: app resume, "Sync now" button press, etc. */
    fun pullNow() {
        val settings = settingsProvider()
        if (!settings.remoteSync.enabled) return
        scope.launch { pull() }
    }

    /**
     * Called by each storage manager after every local file write.
     * Must be fast (runs on the caller's thread).
     */
    fun onFileWritten(file: File) {
        val settings = settingsProvider()
        if (!settings.remoteSync.enabled) return

        val filename = file.name
        if (!isTracked(filename, settings)) return

        syncState.markDirty(filename)
        syncState.save()

        scheduleUpload(filename)
    }

    /** Health-check the remote server with current settings.  Used by UI "Test connection". */
    suspend fun testConnection(): Boolean {
        val settings = settingsProvider()
        if (!settings.remoteSync.enabled) return false
        return try {
            buildClient(settings).health()
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
            add("app_settings.json")
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

    // ── Debounced upload scheduling ──────────────────────────────────────────

    private fun scheduleUpload(filename: String) {
        // Skip scheduling when a re-authentication is required
        if (_needsReauth.value) return

        synchronized(pendingLock) {
            pendingUploads[filename]?.cancel()
            pendingUploads[filename] = scope.launch {
                delay(UPLOAD_DEBOUNCE_MS)
                upload(filename)
                synchronized(pendingLock) { pendingUploads.remove(filename) }
            }
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    private suspend fun upload(filename: String) {
        val settings = settingsProvider()
        if (!settings.remoteSync.enabled) return
        if (_needsReauth.value) return

        val localFile = File(internalDir, filename)
        if (!localFile.exists()) return

        val content = try {
            if (filename == "app_settings.json") {
                // Strip the remoteSync block so tokens/config stay device-local
                val raw = localFile.readText()
                val parsed = try {
                    json.decodeFromString<AppSettings>(raw)
                } catch (e: Exception) {
                    AppLogger.e("[$TAG] Could not parse app_settings.json for strip; skipping upload", e)
                    return
                }
                json.encodeToString(parsed.copy(remoteSync = RemoteSyncSettings()))
            } else {
                localFile.readText()
            }
        } catch (e: Exception) {
            AppLogger.e("[$TAG] upload($filename): could not read file", e)
            return
        }

        val client = buildClient(settings)
        try {
            val meta = client.put(filename, content)
            _needsReauth.value = false  // Successful call — clear any stale flag
            syncState.markPushed(filename, meta.updated_at)
            syncState.save()
            AppLogger.d("[$TAG] Uploaded $filename (server updated_at=${meta.updated_at})")
        } catch (e: RemoteSyncException.Unauthorized) {
            _needsReauth.value = true
            AppLogger.e("[$TAG] upload($filename): 401 Unauthorized — needsReauth set", e)
        } catch (e: Exception) {
            AppLogger.e("[$TAG] upload($filename): PUT failed — will retry on next pull", e)
            // Leave dirty=true so pull() will trigger another attempt
        }
    }

    // ── Pull ─────────────────────────────────────────────────────────────────

    suspend fun pull() {
        val settings = settingsProvider()
        if (!settings.remoteSync.enabled) return
        if (_needsReauth.value) return

        val tracked = trackedFilenames(settings)
        val client = buildClient(settings)

        val manifestEntries: List<BlobMeta> = try {
            client.manifest()
        } catch (e: RemoteSyncException.Unauthorized) {
            _needsReauth.value = true
            AppLogger.e("[$TAG] pull(): manifest 401 Unauthorized — needsReauth set", e)
            return
        } catch (e: Exception) {
            AppLogger.e("[$TAG] pull(): manifest failed", e)
            return
        }

        // Successful manifest call — clear any stale reauth flag
        _needsReauth.value = false

        // Index by filename for O(1) lookup
        val remoteIndex = manifestEntries.associateBy { it.filename }

        var anythingChanged = false

        for (filename in tracked) {
            val remoteMeta = remoteIndex[filename]
            if (remoteMeta == null) {
                // File not on server yet — if it exists locally (and is not the
                // bookkeeping file that is never uploaded), mark it dirty so the
                // end-of-pull flush loop schedules the initial upload.  This handles
                // newly-tracked files such as api_keys when syncApiKeys is toggled on.
                if (filename != "sync_state.json") {
                    val localFile = File(internalDir, filename)
                    if (localFile.exists()) {
                        syncState.markDirty(filename)
                    }
                }
                continue
            }

            val entry = syncState.get(filename)

            if (entry.dirty) {
                // We have unsent local changes — last-write-wins means we push later; skip pull
                AppLogger.d("[$TAG] pull(): $filename is dirty, skipping remote adopt")
                continue
            }

            val localFile = File(internalDir, filename)
            val localExists = localFile.exists()

            if (!localExists || remoteMeta.updated_at > entry.baseServerVersion) {
                // Fetch and adopt
                val blob: RemoteBlob = try {
                    client.get(filename) ?: continue // 404 (race)
                } catch (e: RemoteSyncException.Unauthorized) {
                    _needsReauth.value = true
                    AppLogger.e("[$TAG] pull(): GET $filename 401 Unauthorized — needsReauth set", e)
                    return
                } catch (e: Exception) {
                    AppLogger.e("[$TAG] pull(): GET $filename failed", e)
                    continue
                }

                val contentToWrite = if (filename == "app_settings.json") {
                    mergeAppSettings(blob.content, localFile)
                } else {
                    blob.content
                }

                if (contentToWrite == null) {
                    AppLogger.e("[$TAG] pull(): could not produce merged content for $filename, skipping")
                    continue
                }

                try {
                    // Write directly — do NOT call onFileWritten to avoid dirty-loop
                    localFile.writeText(contentToWrite)
                } catch (e: Exception) {
                    AppLogger.e("[$TAG] pull(): could not write $filename to disk", e)
                    continue
                }

                syncState.markPulled(filename, blob.updated_at)
                anythingChanged = true
                AppLogger.d("[$TAG] Pulled $filename (server updated_at=${blob.updated_at})")
            }
        }

        syncState.save()

        if (anythingChanged) {
            _changeTick.value = _changeTick.value + 1
        }

        // Flush any dirty files that have been waiting (e.g. uploads that failed earlier)
        for (filename in tracked) {
            if (syncState.isDirty(filename)) {
                scheduleUpload(filename)
            }
        }
    }

    // ── AppSettings merge ─────────────────────────────────────────────────────

    /**
     * Merges the remotely-fetched `app_settings.json` content with the locally-held
     * `remoteSync` block.  The remote blob must never clobber sync configuration
     * (server URL, minted token, account email, enabled flag) — those are device-local.
     *
     * Returns null if neither parse succeeds.
     */
    private fun mergeAppSettings(remoteContent: String, localFile: File): String? {
        val remote: AppSettings = try {
            json.decodeFromString<AppSettings>(remoteContent)
        } catch (e: Exception) {
            AppLogger.e("[$TAG] mergeAppSettings: could not parse remote content", e)
            return null
        }

        val localRemoteSync: RemoteSyncSettings = if (localFile.exists()) {
            try {
                json.decodeFromString<AppSettings>(localFile.readText()).remoteSync
            } catch (e: Exception) {
                settingsProvider().remoteSync
            }
        } else {
            settingsProvider().remoteSync
        }

        val merged = remote.copy(remoteSync = localRemoteSync)
        return try {
            json.encodeToString(merged)
        } catch (e: Exception) {
            AppLogger.e("[$TAG] mergeAppSettings: could not serialize merged settings", e)
            null
        }
    }
}
