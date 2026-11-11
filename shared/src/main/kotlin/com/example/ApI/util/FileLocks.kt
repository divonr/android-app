package com.example.ApI.util

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-wide registry of reentrant locks, one per file (keyed by canonical path).
 *
 * Every read-modify-write of a synced file holds the file's lock, so concurrent writers in the
 * same process (UI, streaming service, sync engine, several repository instances over the same
 * data dir) never interleave and lose each other's updates. Locks are reentrant: a locked
 * operation may call other locked operations on the same file.
 *
 * Blocks run while the lock is held, so they must stay short and must not suspend.
 */
object FileLocks {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** The lock that guards [file]. The same instance for every path spelling of one file. */
    fun lockFor(file: File): ReentrantLock = locks.computeIfAbsent(keyFor(file)) { ReentrantLock() }

    inline fun <T> withLock(file: File, block: () -> T): T = lockFor(file).withLock(block)

    private fun keyFor(file: File): String = try {
        file.canonicalPath
    } catch (e: IOException) {
        file.absoluteFile.normalize().path
    }
}
