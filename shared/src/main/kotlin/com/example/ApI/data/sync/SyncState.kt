package com.example.ApI.data.sync

import com.example.ApI.util.AppLogger
import com.example.ApI.util.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Per-file sync bookkeeping, persisted as `sync_state.json` in the app's internalDir.
 * This file itself is NEVER uploaded to the remote server.
 *
 * The base of a file is the last version both sides agreed on: the server version
 * ([baseServerVersion] / [baseServerSha]) and the local content that corresponds to it
 * ([baseLocalSha], snapshot kept in `sync_base/<filename>`, see [SyncBaseStore]).
 *
 * @param baseServerVersion The server `updated_at` of the base.  0 = never synced.
 * @param baseServerSha     The server sha of the base (null: unknown, state written by an old version).
 * @param baseLocalSha      sha256 of the local content (in upload form) at the base; the local file
 *                          changed iff its sha differs (null: unknown → treated as changed).
 * @param dirty             Scheduling hint only: a local write happened since the last sync of
 *                          the file.  Change detection is content based, never this flag.
 * @param lastLocalWriteAt  Wall-clock timestamp of the most recent local write (informational only).
 */
@Serializable
data class FileSyncEntry(
    val baseServerVersion: Long = 0L,
    val baseServerSha: String? = null,
    val baseLocalSha: String? = null,
    val dirty: Boolean = false,
    val lastLocalWriteAt: Long = 0L
)

/**
 * @param accountUsername The account the bases belong to.  null: written by a version that did
 *        not record it (adopted as the current account on load); "": reset, no account yet.
 */
@Serializable
data class SyncStateData(
    val accountUsername: String? = null,
    val files: Map<String, FileSyncEntry> = emptyMap(),
    /** Files whose next sync takes the remote copy as is (account switch: no cross-account leak). */
    val adoptRemote: Set<String> = emptySet()
)

/**
 * Thread-safe holder for per-file sync state.  Every method is synchronized; every mutation
 * that matters is persisted atomically by [save] (callers decide when).
 *
 * [generation] increases on every [reset]; writers that started before a reset pass the
 * generation they saw and their late updates are dropped (see [recordBase]).
 */
class SyncState(
    private val internalDir: File,
    private val json: Json
) {
    companion object {
        private const val TAG = "SyncState"
        const val FILE_NAME = "sync_state.json"
    }

    private val stateFile: File get() = File(internalDir, FILE_NAME)

    private var data: SyncStateData = SyncStateData()

    @Volatile
    var generation: Long = 0L
        private set

    /** Load from disk.  If the file doesn't exist or is corrupt, start fresh. */
    @Synchronized
    fun load() {
        data = if (stateFile.exists()) {
            try {
                json.decodeFromString<SyncStateData>(stateFile.readText())
            } catch (e: Exception) {
                AppLogger.w("[$TAG] Unreadable $FILE_NAME; starting from an empty sync state")
                SyncStateData()
            }
        } else {
            SyncStateData()
        }
    }

    /** Persist current state to disk.  Best-effort: must never throw into callers. */
    @Synchronized
    fun save() {
        try {
            AtomicFiles.write(stateFile, json.encodeToString(data))
        } catch (e: Exception) {
            // Best-effort; content-based change detection reconciles on the next sync.
        }
    }

    @Synchronized
    fun snapshot(): SyncStateData = data

    @Synchronized
    fun get(filename: String): FileSyncEntry = data.files[filename] ?: FileSyncEntry()

    /** The entry of [filename], or null if it was never synced (or the state was reset). */
    @Synchronized
    fun entry(filename: String): FileSyncEntry? = data.files[filename]

    @Synchronized
    fun accountUsername(): String? = data.accountUsername

    @Synchronized
    fun markDirty(filename: String, writeAt: Long = System.currentTimeMillis()) {
        val entry = get(filename)
        data = data.copy(files = data.files + (filename to entry.copy(dirty = true, lastLocalWriteAt = writeAt)))
    }

    /**
     * Record a new base for [filename]: server version/sha and the sha of the local content
     * (upload form) that corresponds to it.  [writeSnapshot] (storing that content) runs inside
     * the same critical section, so a concurrent [reset] can't interleave.  Dropped (returns
     * false) if the state was reset after [expectedGeneration] was read.  Persists the state.
     */
    @Synchronized
    fun recordBase(
        filename: String,
        serverVersion: Long,
        serverSha: String,
        localSha: String,
        expectedGeneration: Long,
        writeSnapshot: () -> Unit = {}
    ): Boolean {
        if (expectedGeneration != generation) return false
        writeSnapshot()
        val entry = get(filename)
        data = data.copy(
            files = data.files + (filename to entry.copy(
                baseServerVersion = serverVersion,
                baseServerSha = serverSha,
                baseLocalSha = localSha,
                dirty = false
            )),
            adoptRemote = data.adoptRemote - filename
        )
        save()
        return true
    }

    /** The server re-labelled the base content with another version (same sha). */
    @Synchronized
    fun updateServerVersion(filename: String, serverVersion: Long, serverSha: String, expectedGeneration: Long) {
        if (expectedGeneration != generation) return
        val entry = data.files[filename] ?: return
        data = data.copy(files = data.files + (filename to entry.copy(baseServerVersion = serverVersion, baseServerSha = serverSha)))
        save()
    }

    @Synchronized
    fun shouldAdoptRemote(filename: String): Boolean = filename in data.adoptRemote

    @Synchronized
    fun clearAdoptRemote(filename: String, expectedGeneration: Long) {
        if (expectedGeneration != generation || filename !in data.adoptRemote) return
        data = data.copy(adoptRemote = data.adoptRemote - filename)
        save()
    }

    /**
     * Forget every base (account switch, sign-out, sign-in to another account).
     * [onReset] runs inside the critical section (used to delete the base snapshots).
     * Persists the state.
     */
    @Synchronized
    fun reset(accountUsername: String, adoptRemote: Set<String> = emptySet(), onReset: () -> Unit = {}) {
        generation++
        onReset()
        data = SyncStateData(accountUsername = accountUsername, adoptRemote = adoptRemote)
        save()
    }

    /** Adopt [accountUsername] for a state written before accounts were recorded (bases kept). */
    @Synchronized
    fun adoptAccount(accountUsername: String) {
        if (data.accountUsername != null) return
        data = data.copy(accountUsername = accountUsername)
        save()
    }

    // ── Legacy helpers (version only; kept for callers/tests of the v1 state) ──

    @Synchronized
    fun markPushed(filename: String, serverVersion: Long) = markVersion(filename, serverVersion)

    @Synchronized
    fun markPulled(filename: String, serverVersion: Long) = markVersion(filename, serverVersion)

    private fun markVersion(filename: String, serverVersion: Long) {
        val entry = get(filename)
        data = data.copy(files = data.files + (filename to entry.copy(baseServerVersion = serverVersion, dirty = false)))
    }

    @Synchronized
    fun isDirty(filename: String): Boolean = get(filename).dirty

    @Synchronized
    fun baseServerVersion(filename: String): Long = get(filename).baseServerVersion
}

/**
 * Base snapshots: `sync_base/<filename>` holds the local content (upload form) of the last synced
 * base of each file, the common ancestor of the next 3-way merge.  Never uploaded.  A snapshot is
 * only trusted when its sha matches the state's `baseLocalSha`.
 */
class SyncBaseStore(internalDir: File) {
    private val dir = File(internalDir, "sync_base")

    fun file(filename: String): File = File(dir, filename)

    /** The snapshot of [filename] if it exists and its sha is [expectedSha]; else null. */
    fun read(filename: String, expectedSha: String?): String? {
        if (expectedSha == null) return null
        val f = file(filename)
        if (!f.exists()) return null
        return try {
            f.readText(Charsets.UTF_8).takeIf { sha256Hex(it) == expectedSha }
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort: a missing snapshot only downgrades the next merge (see SyncEngine). */
    fun write(filename: String, content: String) {
        try {
            AtomicFiles.write(file(filename), content)
        } catch (e: Exception) {
            AppLogger.e("[SyncBaseStore] Could not store the base snapshot of $filename", e)
        }
    }

    fun clear() {
        if (dir.exists() && !dir.deleteRecursively()) {
            AppLogger.w("[SyncBaseStore] Could not delete every base snapshot in $dir")
        }
    }
}

/** Hex sha256 of the UTF-8 bytes of [text] — matches the server's `sha` (computed over plaintext). */
fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
