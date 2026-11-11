package com.example.ApI.data.attachments

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.RemoteSyncSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentStoreClientTest {
    private lateinit var server: FakeAttachmentStoreServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @TempDir
    lateinit var dir: File

    @BeforeEach
    fun setUp() {
        server = FakeAttachmentStoreServer()
        server.addUser("tok-1")
        server.addUser("tok-2")
        server.start()
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.stop()
    }

    private fun client(token: String = "tok-1", maxFileBytes: Long = AttachmentStoreClient.DEFAULT_MAX_FILE_BYTES) =
        AttachmentStoreClient(
            config = { AttachmentStoreConfig(server.baseUrl + "/", token) },
            maxFileBytes = maxFileBytes,
            controlTimeoutMs = 5_000,
            transferIdleTimeoutMs = 5_000
        )

    private fun file(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    private fun partFiles(): List<String> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.map { it.name }.toList()

    private fun <T> ok(result: StoreResult<T>): T = assertIs<StoreResult.Ok<T>>(result, "expected Ok, got $result").value

    private fun failed(result: StoreResult<*>): StoreError = assertIs<StoreResult.Failed>(result, "expected Failed, got $result").error

    // ── hashing ──────────────────────────────────────────────────────────────

    @Test
    fun `hash of a file is its lowercase hex sha256 and its size`() = runBlocking {
        val bytes = Random(1).nextBytes(200_000)
        val digest = AttachmentHash.of(file("a.bin", bytes))
        assertEquals(FakeAttachmentStoreServer.sha256(bytes), digest.sha256)
        assertEquals(200_000L, digest.size)
        assertTrue(AttachmentHash.isValid(digest.sha256))
        // The well-known digest of the empty input
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AttachmentHash.of(file("empty", ByteArray(0))).sha256
        )
        assertFalse(AttachmentHash.isValid(digest.sha256.uppercase()))
        assertFalse(AttachmentHash.isValid("../" + digest.sha256.drop(3)))
        assertFalse(AttachmentHash.isValid(digest.sha256 + "0"))
        assertFalse(AttachmentHash.isValid(""))
    }

    // ── round trips ──────────────────────────────────────────────────────────

    @Test
    fun `upload exists commit download round trip`() = runBlocking {
        val bytes = Random(2).nextBytes(300_000)
        val source = file("photo.jpg", bytes)
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val c = client()

        assertNull(ok(c.exists(sha)))

        val progress = CopyOnWriteArrayList<Pair<Long, Long>>()
        val stored = ok(c.upload(source) { sent, total -> progress += sent to total })
        assertEquals(StoredFile(sha, 300_000, StoredState.PENDING), stored)
        assertContentEquals(bytes, server.files("tok-1").getValue(sha).bytes)
        assertEquals(300_000L to 300_000L, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a.first <= b.first }, "progress goes forward")

        assertEquals(StoredFile(sha, 300_000, StoredState.PENDING), ok(c.exists(sha)))
        assertEquals(StoredState.COMMITTED, ok(c.commit(sha)).state)
        assertEquals(StoredState.COMMITTED, ok(c.commit(sha)).state)  // idempotent
        assertEquals(StoredState.COMMITTED, ok(c.exists(sha))!!.state)

        val dest = File(dir, "cache/$sha")
        dest.parentFile.mkdirs()
        val received = CopyOnWriteArrayList<Long>()
        val downloaded = ok(c.download(sha, dest) { got, _ -> received += got })
        assertEquals(dest.absoluteFile, downloaded)
        assertContentEquals(bytes, dest.readBytes())
        assertEquals(300_000L, received.last())
        assertEquals(emptyList(), partFiles())

        // Every request carried the bearer token and went to /files/<sha>[/commit]
        assertTrue(server.requests.all { it.authorization == "Bearer tok-1" })
        assertTrue(server.requests.all { it.path == "/files/$sha" || it.path == "/files/$sha/commit" })
    }

    @Test
    fun `empty file round trip`() = runBlocking {
        val source = file("empty.txt", ByteArray(0))
        val c = client()
        val stored = ok(c.upload(source))
        assertEquals(0L, stored.size)
        val dest = File(dir, "empty.out")
        ok(c.download(stored.sha256, dest))
        assertEquals(0L, dest.length())
    }

    @Test
    fun `a file the store already has is not sent again`() = runBlocking {
        val bytes = Random(3).nextBytes(50_000)
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val c = client()
        ok(c.upload(file("one.bin", bytes)))
        ok(c.commit(sha))
        assertEquals(1, server.count("PUT"))

        val again = ok(c.upload(file("copy-with-another-name.bin", bytes)))
        assertEquals(StoredState.COMMITTED, again.state)
        assertEquals(1, server.count("PUT"), "the second upload only asked whether the file exists")
    }

    @Test
    fun `users do not see each other's files`() = runBlocking {
        val bytes = "private".toByteArray()
        val sha = ok(client("tok-1").upload(file("p.txt", bytes))).sha256
        assertNull(ok(client("tok-2").exists(sha)))
        assertEquals(StoreError.NOT_FOUND, failed(client("tok-2").download(sha, File(dir, "stolen"))))
        assertEquals(StoreError.NOT_FOUND, failed(client("tok-2").commit(sha)))
        assertFalse(File(dir, "stolen").exists())
    }

    // ── commit / delete ──────────────────────────────────────────────────────

    @Test
    fun `delete removes a pending file and leaves a committed one`() = runBlocking {
        val c = client()
        val pending = ok(c.upload(file("removed.txt", "attached then removed".toByteArray()))).sha256
        val used = ok(c.upload(file("sent.txt", "attached and sent".toByteArray()))).sha256
        ok(c.commit(used))

        assertEquals(true, ok(c.deletePending(pending)))
        assertNull(ok(c.exists(pending)))
        assertEquals(false, ok(c.deletePending(pending)), "nothing left to delete")

        assertEquals(false, ok(c.deletePending(used)))
        assertEquals(StoredState.COMMITTED, ok(c.exists(used))!!.state)
    }

    @Test
    fun `commit of a file the store does not have is NOT_FOUND`() = runBlocking {
        assertEquals(StoreError.NOT_FOUND, failed(client().commit("a".repeat(64))))
    }

    // ── hash mismatch ────────────────────────────────────────────────────────

    @Test
    fun `upload whose bytes are not the announced hash is rejected and nothing is stored`() = runBlocking {
        val source = file("changed.txt", "the file as it is now".toByteArray())
        val stale = FileDigest(FakeAttachmentStoreServer.sha256("the file as it was hashed".toByteArray()), source.length())
        assertEquals(StoreError.HASH_MISMATCH, failed(client().upload(source, stale)))
        assertTrue(server.files("tok-1").isEmpty())
    }

    @Test
    fun `download whose bytes are not the hash is rejected and leaves no file`() = runBlocking {
        val bytes = Random(4).nextBytes(100_000)
        val sha = ok(client().upload(file("src.bin", bytes))).sha256
        server.corruptDownloads = true

        val dest = File(dir, "out/$sha").apply { parentFile.mkdirs() }
        assertEquals(StoreError.HASH_MISMATCH, failed(client().download(sha, dest)))
        assertFalse(dest.exists())
        assertEquals(emptyList(), partFiles())

        // An older good copy at the destination is not replaced by a bad download
        dest.writeBytes(bytes)
        assertEquals(StoreError.HASH_MISMATCH, failed(client().download(sha, dest)))
        assertContentEquals(bytes, dest.readBytes())
    }

    @Test
    fun `download that breaks halfway leaves no file`() = runBlocking {
        val bytes = Random(5).nextBytes(400_000)
        val sha = ok(client().upload(file("src.bin", bytes))).sha256
        server.dropDownloadsHalfway = true
        val dest = File(dir, "half.bin")
        assertEquals(StoreError.UNAVAILABLE, failed(client().download(sha, dest)))
        assertFalse(dest.exists())
        assertEquals(emptyList(), partFiles())

        server.dropDownloadsHalfway = false
        ok(client().download(sha, dest))
        assertContentEquals(bytes, dest.readBytes())
    }

    @Test
    fun `download of an unknown file is NOT_FOUND`() = runBlocking {
        val dest = File(dir, "nothing")
        assertEquals(StoreError.NOT_FOUND, failed(client().download("b".repeat(64), dest)))
        assertFalse(dest.exists())
    }

    // ── limits and failures ──────────────────────────────────────────────────

    @Test
    fun `file over the client's limit is not offered to the store`() = runBlocking {
        val source = file("big.bin", ByteArray(2_000))
        assertEquals(StoreError.TOO_LARGE, failed(client(maxFileBytes = 1_999).upload(source)))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `413 from the store is TOO_LARGE`() = runBlocking {
        server.maxFileBytes = 1_000
        assertEquals(StoreError.TOO_LARGE, failed(client().upload(file("big.bin", ByteArray(1_001)))))
        assertTrue(server.files("tok-1").isEmpty())
    }

    @Test
    fun `401 is UNAUTHORIZED for every operation`() = runBlocking {
        val c = client(token = "revoked")
        val sha = "c".repeat(64)
        assertEquals(StoreError.UNAUTHORIZED, failed(c.exists(sha)))
        assertEquals(StoreError.UNAUTHORIZED, failed(c.upload(file("x.txt", "x".toByteArray()))))
        assertEquals(StoreError.UNAUTHORIZED, failed(c.commit(sha)))
        assertEquals(StoreError.UNAUTHORIZED, failed(c.deletePending(sha)))
        assertEquals(StoreError.UNAUTHORIZED, failed(c.download(sha, File(dir, "d"))))
    }

    @Test
    fun `server errors and an unreachable server are UNAVAILABLE`() = runBlocking {
        val sha = "d".repeat(64)
        server.failWith = 503
        assertEquals(StoreError.UNAVAILABLE, failed(client().exists(sha)))
        assertEquals(StoreError.UNAVAILABLE, failed(client().commit(sha)))
        assertEquals(StoreError.UNAVAILABLE, failed(client().upload(file("x.txt", "x".toByteArray()))))

        val freePort = ServerSocket(0).use { it.localPort }
        val nowhere = AttachmentStoreClient({ AttachmentStoreConfig("http://127.0.0.1:$freePort", "t") }, controlTimeoutMs = 2_000)
        assertEquals(StoreError.UNAVAILABLE, failed(nowhere.exists(sha)))
        assertEquals(StoreError.UNAVAILABLE, failed(nowhere.download(sha, File(dir, "d"))))
        assertEquals(emptyList(), partFiles())
    }

    @Test
    fun `without a configuration nothing is sent`() = runBlocking {
        val c = AttachmentStoreClient({ null })
        assertFalse(c.isConfigured)
        val sha = "e".repeat(64)
        assertEquals(StoreError.NOT_CONFIGURED, failed(c.exists(sha)))
        assertEquals(StoreError.NOT_CONFIGURED, failed(c.upload(file("x.txt", "x".toByteArray()))))
        assertEquals(StoreError.NOT_CONFIGURED, failed(c.commit(sha)))
        assertEquals(StoreError.NOT_CONFIGURED, failed(c.deletePending(sha)))
        assertEquals(StoreError.NOT_CONFIGURED, failed(c.download(sha, File(dir, "d"))))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `a hash that is not 64 lowercase hex digits never reaches the server`() = runBlocking {
        val c = client()
        for (bad in listOf("../../etc/passwd", "A".repeat(64), "a".repeat(63), "a".repeat(64) + "/commit", "")) {
            assertEquals(StoreError.NOT_FOUND, failed(c.exists(bad)))
            assertEquals(StoreError.NOT_FOUND, failed(c.commit(bad)))
            assertEquals(StoreError.NOT_FOUND, failed(c.deletePending(bad)))
            assertEquals(StoreError.NOT_FOUND, failed(c.download(bad, File(dir, "d"))))
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `unreadable local file is LOCAL_IO`() = runBlocking {
        assertEquals(StoreError.LOCAL_IO, failed(client().upload(File(dir, "does-not-exist.bin"))))
        assertTrue(server.requests.isEmpty())
    }

    // ── cancellation ─────────────────────────────────────────────────────────

    @Test
    fun `cancelling an upload aborts it and nothing is stored`() = runBlocking {
        val gate = CountDownLatch(1)
        server.putGate = gate
        // Larger than the socket buffers: the body is still being sent when it is cancelled
        val source = file("slow.bin", Random(6).nextBytes(24 * 1024 * 1024))
        val job = scope.async { client().upload(source) }
        // Wait until the PUT is on the wire
        withTimeout(5_000) { while (server.count("PUT") == 0) kotlinx.coroutines.delay(10) }

        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        gate.countDown()
        kotlinx.coroutines.delay(300)
        assertTrue(server.files("tok-1").isEmpty(), "the aborted upload stored nothing")
    }

    @Test
    fun `cancelling a download aborts it and leaves no partial file`() = runBlocking {
        val bytes = Random(7).nextBytes(400_000)
        val sha = ok(client().upload(file("src.bin", bytes))).sha256
        val gate = CountDownLatch(1)
        server.getGate = gate
        val dest = File(dir, "cancelled.bin")
        val job = scope.async { client().download(sha, dest) }
        withTimeout(5_000) { while (partFiles().isEmpty()) kotlinx.coroutines.delay(10) }

        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertFalse(dest.exists())
        assertEquals(emptyList(), partFiles())
        gate.countDown()
    }

    // ── model glue ───────────────────────────────────────────────────────────

    @Test
    fun `store configuration comes from the sync settings`() {
        assertNull(RemoteSyncSettings().attachmentStoreConfig())
        assertNull(RemoteSyncSettings(enabled = true).attachmentStoreConfig(), "signed out")
        assertNull(RemoteSyncSettings(enabled = false, authToken = "t").attachmentStoreConfig(), "sync off")
        assertEquals(
            AttachmentStoreConfig("https://sync.example", "t"),
            RemoteSyncSettings(enabled = true, authToken = "t", serverBaseUrl = "https://sync.example").attachmentStoreConfig()
        )
    }

    @Test
    fun `digest is recorded on and read from an attachment`() {
        val a = Attachment(local_file_path = "/x/y.png", file_name = "y.png", mime_type = "image/png", file_OPENAI_id = "f1")
        assertNull(a.digest)
        val d = FileDigest("f".repeat(64), 42)
        val b = a.withDigest(d)
        assertEquals(d, b.digest)
        assertEquals(a, b.copy(sha256 = null, size = null), "nothing else changed")
        assertNull(a.copy(sha256 = "not-a-hash").digest)
    }
}
