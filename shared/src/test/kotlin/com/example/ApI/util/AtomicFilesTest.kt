package com.example.ApI.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AtomicFilesTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `write creates, replaces and leaves no temp files`() {
        val file = File(dir, "nested/data.json")
        AtomicFiles.write(file, "first")
        assertEquals("first", file.readText())
        AtomicFiles.write(file, "second ✓")
        assertEquals("second ✓", file.readText())
        assertEquals(listOf("data.json"), file.parentFile.list()!!.toList())
    }

    @Test
    fun `readers never see a partial file while it is being replaced`() {
        val file = File(dir, "big.json")
        // Payloads big enough that a non-atomic write would be observable half-written
        val payloads = (0 until 4).map { i -> "{\"v\":$i,\"pad\":\"" + "x".repeat(200_000 + i) + "\"}" }
        AtomicFiles.write(file, payloads[0])

        val stop = AtomicBoolean(false)
        val badReads = AtomicInteger(0)
        val reads = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        repeat(2) {
            pool.execute {
                while (!stop.get()) {
                    val text = file.readText()
                    reads.incrementAndGet()
                    if (text !in payloads) badReads.incrementAndGet()
                }
            }
        }
        val writers = CountDownLatch(2)
        repeat(2) { w ->
            pool.execute {
                repeat(40) { i -> AtomicFiles.write(file, payloads[(w + i) % payloads.size]) }
                writers.countDown()
            }
        }
        assertTrue(writers.await(60, TimeUnit.SECONDS))
        stop.set(true)
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        assertTrue(reads.get() > 0)
        assertEquals(0, badReads.get(), "a reader saw a torn file")
        assertEquals(listOf("big.json"), dir.list()!!.toList())
    }

    @Test
    fun `file locks are shared across path spellings and reentrant`() {
        val a = File(dir, "x.json")
        val b = File(File(dir, "sub/.."), "x.json")
        assertSame(FileLocks.lockFor(a), FileLocks.lockFor(b))
        val value = FileLocks.withLock(a) { FileLocks.withLock(b) { 42 } }
        assertEquals(42, value)
    }
}
