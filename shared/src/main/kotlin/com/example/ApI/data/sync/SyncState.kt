package com.example.ApI.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Per-file sync bookkeeping, persisted as `sync_state.json` in the app's internalDir.
 * This file itself is NEVER uploaded to the remote server.
 *
 * @param baseServerVersion The server `updated_at` timestamp of the last version we either
 *        pulled or successfully pushed.  0 = never synced.
 * @param dirty             True if local writes have occurred since the last successful push.
 * @param lastLocalWriteAt  Wall-clock timestamp of the most recent local write (informational only).
 */
@Serializable
data class FileSyncEntry(
    val baseServerVersion: Long = 0L,
    val dirty: Boolean = false,
    val lastLocalWriteAt: Long = 0L
)

@Serializable
data class SyncStateData(
    val files: Map<String, FileSyncEntry> = emptyMap()
)

/**
 * In-memory holder for per-file sync state.  Call [load] once on startup,
 * then mutate via the helper methods and call [save] to persist.
 */
class SyncState(
    private val internalDir: File,
    private val json: Json
) {
    private val stateFile: File get() = File(internalDir, "sync_state.json")

    private var data: SyncStateData = SyncStateData()

    /** Load from disk.  If the file doesn't exist or is corrupt, start fresh. */
    fun load() {
        data = if (stateFile.exists()) {
            try {
                json.decodeFromString<SyncStateData>(stateFile.readText())
            } catch (e: Exception) {
                SyncStateData()
            }
        } else {
            SyncStateData()
        }
    }

    /** Persist current state to disk. */
    fun save() {
        try {
            stateFile.writeText(json.encodeToString(data))
        } catch (e: IOException) {
            // Best-effort; will reconcile on next startup.
        }
    }

    fun get(filename: String): FileSyncEntry = data.files[filename] ?: FileSyncEntry()

    fun markDirty(filename: String, writeAt: Long = System.currentTimeMillis()) {
        val entry = get(filename)
        data = data.copy(
            files = data.files + (filename to entry.copy(dirty = true, lastLocalWriteAt = writeAt))
        )
    }

    fun markPushed(filename: String, serverVersion: Long) {
        val entry = get(filename)
        data = data.copy(
            files = data.files + (filename to entry.copy(
                baseServerVersion = serverVersion,
                dirty = false
            ))
        )
    }

    fun markPulled(filename: String, serverVersion: Long) {
        val entry = get(filename)
        data = data.copy(
            files = data.files + (filename to entry.copy(
                baseServerVersion = serverVersion,
                dirty = false
            ))
        )
    }

    fun isDirty(filename: String): Boolean = get(filename).dirty

    fun baseServerVersion(filename: String): Long = get(filename).baseServerVersion
}
