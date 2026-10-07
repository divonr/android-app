package com.example.ApI.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForegroundSyncTickerTest {

    @Test
    fun `pulls periodically while started, never after stop`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pulls = AtomicInteger()
            val ticker = ForegroundSyncTicker(scope, intervalMs = 20) { pulls.incrementAndGet() }
            ticker.start()
            ticker.start()  // idempotent: one loop
            Thread.sleep(150)
            ticker.stop()
            val atStop = pulls.get()
            assertTrue(atStop in 3..9, "about one pull per interval with a single loop, got $atStop")
            assertFalse(ticker.running)
            Thread.sleep(80)
            assertEquals(atStop, pulls.get(), "no pull after stop")
            ticker.start()
            assertTrue(ticker.running)
            ticker.stop()
        } finally {
            scope.cancel()
        }
    }
}
