package com.example.ApI.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safe file replacement.
 *
 * [write] puts the new content into a temp file in the same directory, fsyncs it and renames it
 * over the target, so readers (and a process that dies mid-write) only ever see the complete old
 * or the complete new content, never a truncated file.
 */
object AtomicFiles {

    private const val TAG = "AtomicFiles"
    private const val MOVE_RETRIES = 4
    private const val MOVE_RETRY_DELAY_MS = 25L

    /** java.nio.file only exists on Android API 26+ (always on the JVM). */
    private val nioAvailable: Boolean = try {
        Class.forName("java.nio.file.Files")
        true
    } catch (t: Throwable) {
        false
    }

    /** Atomically replace [file] with [text] (UTF-8). Holds the file's [FileLocks] lock. Throws [IOException]. */
    fun write(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))

    /** Atomically replace [file] with [bytes]. Holds the file's [FileLocks] lock. Throws [IOException]. */
    fun writeBytes(file: File, bytes: ByteArray) {
        val target = file.absoluteFile
        val dir = target.parentFile ?: throw IOException("No parent directory for $target")
        if (!dir.exists()) dir.mkdirs()

        FileLocks.withLock(target) {
            val tmp = File.createTempFile(".${target.name}.", ".tmp", dir)
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                moveIntoPlace(tmp, target)
                if (nioAvailable) Nio.syncDirectory(dir)
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    private fun moveIntoPlace(tmp: File, target: File) {
        if (nioAvailable) {
            try {
                if (Nio.moveWithRetry(tmp, target)) return
            } catch (e: LinkageError) {
                // java.nio.file present but incomplete (e.g. File.toPath missing before API 26)
            }
        }
        // Old Android: rename(2) replaces the target atomically on POSIX file systems
        if (tmp.renameTo(target)) return
        AppLogger.w("[$TAG] rename failed for ${target.name}; falling back to a non-atomic copy")
        tmp.copyTo(target, overwrite = true)
    }

    /** Kept in its own object so old Android never has to resolve java.nio.file classes. */
    private object Nio {
        fun move(tmp: File, target: File) {
            val from = tmp.toPath()
            val to = target.toPath()
            try {
                java.nio.file.Files.move(
                    from, to,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }

        /**
         * [move], retried briefly when the file system refuses (Windows denies replacing a file
         * another reader holds open). False: still refused, the caller falls back to a copy.
         */
        fun moveWithRetry(tmp: File, target: File): Boolean {
            for (attempt in 0..MOVE_RETRIES) {
                if (attempt > 0) Thread.sleep(MOVE_RETRY_DELAY_MS * attempt)
                try {
                    move(tmp, target)
                    return true
                } catch (e: java.nio.file.FileSystemException) {
                    AppLogger.w("[$TAG] replacing ${target.name} failed (${e.javaClass.simpleName}), attempt ${attempt + 1}")
                }
            }
            return false
        }

        /** Best-effort: persist the rename itself (not supported on every platform, e.g. Windows). */
        fun syncDirectory(dir: File) {
            try {
                java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            } catch (e: Throwable) {
                // Ignored: the content itself is already synced
            }
        }
    }
}
