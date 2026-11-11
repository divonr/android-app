package com.example.ApI.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic pull while the app is in the foreground (Android: resumed; desktop: window focused),
 * on top of the pull on resume / focus, so another device's changes show up without user action.
 * [start] is idempotent; [stop] cancels the loop (paused / focus lost). [pull] must be cheap and
 * fire-and-forget (e.g. `repository.pullNow()`, which coalesces requests and is a no-op while
 * sync is disabled).
 */
class ForegroundSyncTicker(
    private val scope: CoroutineScope,
    private val intervalMs: Long = INTERVAL_MS,
    private val pull: () -> Unit
) {
    companion object {
        const val INTERVAL_MS = 30_000L
    }

    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                pull()
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    val running: Boolean @Synchronized get() = job?.isActive == true
}
