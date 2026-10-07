package com.example.ApI.util

import java.io.File

/**
 * Durable "do not sync as authoritative" markers for local files.
 *
 * When a synced file is found unreadable (corrupt), its content is unknown: a change made on top
 * of the empty history that replaces it must never reach the server as the file's new state (an
 * upload would replace, or a 3-way merge would read as "everything deleted", the account's copy).
 * The storage layer records a hold (`<file>.sync-hold`, next to the file, never synced itself)
 * and stops calling the sync hook for that file. The sync engine must not upload a held file;
 * it reconciles it with the remote copy using an EMPTY base (a 2-way union, so nothing counts as
 * deleted locally) and then calls [release].
 */
object SyncHolds {

    private const val TAG = "SyncHolds"
    private const val SUFFIX = ".sync-hold"

    fun markerFor(file: File): File = File(file.absoluteFile.parentFile, file.name + SUFFIX)

    fun isHeld(file: File): Boolean = markerFor(file).exists()

    /** Record a hold on [file]. Returns false if it could not be recorded. */
    fun hold(file: File, reason: String): Boolean = try {
        AtomicFiles.write(markerFor(file), reason)
        AppLogger.w("[$TAG] Sync of ${file.name} held: $reason")
        true
    } catch (e: Exception) {
        AppLogger.e("[$TAG] Could not record sync hold for ${file.name}", e)
        false
    }

    /** Lift the hold on [file] (after the sync engine reconciled it). */
    fun release(file: File) {
        val marker = markerFor(file)
        if (marker.exists() && !marker.delete()) {
            AppLogger.e("[$TAG] Could not release sync hold for ${file.name}", Exception(marker.path))
        }
    }

    /** Carry a hold along when [from] is renamed to [to] (user migration). */
    fun moveHold(from: File, to: File) {
        val marker = markerFor(from)
        if (!marker.exists()) return
        if (!marker.renameTo(markerFor(to))) {
            hold(to, "moved from ${from.name}")
            marker.delete()
        }
    }
}
