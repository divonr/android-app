package com.example.ApI.data.attachments

import com.example.ApI.data.model.Attachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentUploaderTest {
    private lateinit var server: FakeAttachmentStoreServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @TempDir
    lateinit var dir: File

    private lateinit var client: AttachmentStoreClient
    private lateinit var uploader: AttachmentUploader

    @BeforeEach
    fun setUp() {
        server = FakeAttachmentStoreServer()
        server.addUser("tok")
        server.start()
        client = AttachmentStoreClient({ AttachmentStoreConfig(server.baseUrl, "tok") }, controlTimeoutMs = 5_000, transferIdleTimeoutMs = 5_000)
        uploader = AttachmentUploader(client, scope)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.stop()
    }

    private fun file(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }
    private fun stored() = server.files("tok")
    private fun <T> ok(result: StoreResult<T>?): T = assertIs<StoreResult.Ok<T>>(result, "expected Ok, got $result").value

    @Test
    fun `attach then send - upload in the background, hash for the message, commit`() = runBlocking {
        val bytes = Random(1).nextBytes(120_000)
        val sha = FakeAttachmentStoreServer.sha256(bytes)

        // Attach
        val upload = uploader.start(file("photo.jpg", bytes))
        // Send: bounded wait, then the digest goes onto the attachment and the file is committed
        val digest = ok(upload.awaitStored(5_000))
        assertEquals(FileDigest(sha, 120_000), digest)
        assertEquals(digest, upload.digest())
        assertEquals(AttachmentUploadState.Stored(digest, committed = false), upload.state.value)
        assertFalse(stored().getValue(sha).committed, "pending until the message is saved")

        val attachment = Attachment(local_file_path = upload.file.path, file_name = "photo.jpg", mime_type = "image/jpeg").withDigest(digest)
        assertEquals(sha, attachment.sha256)
        assertEquals(120_000L, attachment.size)

        assertFalse(upload.commitRequested)
        assertEquals(digest, ok(upload.commitWhenStored().await()))
        assertTrue(upload.commitRequested)
        assertTrue(stored().getValue(sha).committed)
        assertEquals(AttachmentUploadState.Stored(digest, committed = true), upload.state.value)
        // Idempotent: one commit request however often it is asked for
        assertEquals(digest, ok(upload.commitWhenStored().await()))
        assertEquals(1, server.count("POST"))
    }

    @Test
    fun `send does not wait for a slow upload - it goes on and is committed when it lands`() = runBlocking {
        val gate = CountDownLatch(1)
        server.putGate = gate
        val bytes = Random(2).nextBytes(80_000)
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val upload = uploader.start(file("slow.bin", bytes))

        // The hash is known although the upload is stuck: the message can be saved with it
        assertEquals(sha, withTimeout(5_000) { upload.digest() }!!.sha256)
        // The bounded wait gives up (→ the request falls back to the device)...
        assertNull(upload.awaitStored(200))
        assertIs<AttachmentUploadState.Uploading>(upload.state.value)
        // ...the message is saved, so the commit is requested, while the upload keeps going
        val commit = upload.commitWhenStored()
        assertFalse(commit.isCompleted)
        assertNull(stored()[sha])

        gate.countDown()
        assertEquals(sha, ok(withTimeout(5_000) { commit.await() }).sha256)
        assertTrue(stored().getValue(sha).committed, "the file reached the store and was committed")
        assertEquals(sha, ok(upload.awaitStored()).sha256)
    }

    @Test
    fun `removing an attachment before sending deletes the pending file`() = runBlocking {
        val bytes = "attached by mistake".toByteArray()
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val upload = uploader.start(file("oops.txt", bytes))
        ok(upload.awaitStored(5_000))
        assertNotNull(stored()[sha])

        withTimeout(5_000) { uploader.discard(upload).join() }
        assertNull(stored()[sha])
        assertEquals(1, server.count("DELETE"))
    }

    @Test
    fun `removing an attachment while it uploads stops the upload`() = runBlocking {
        val gate = CountDownLatch(1)
        server.putGate = gate
        // Larger than the socket buffers: the body is still being sent when it is removed
        val bytes = Random(3).nextBytes(24 * 1024 * 1024)
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val upload = uploader.start(file("removed.bin", bytes))
        withTimeout(5_000) { while (server.count("PUT") == 0) delay(10) }

        withTimeout(5_000) { uploader.discard(upload).join() }
        assertEquals(AttachmentUploadState.Cancelled, upload.state.value)
        assertIs<StoreResult.Failed>(upload.awaitStored(), "awaiting a cancelled upload is a failure, not a cancellation")
        gate.countDown()
        delay(300)
        assertNull(stored()[sha], "the aborted upload stored nothing")
    }

    @Test
    fun `discard leaves alone a file whose message was saved`() = runBlocking {
        val bytes = "sent".toByteArray()
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val upload = uploader.start(file("sent.txt", bytes))
        ok(upload.commitWhenStored().await())

        withTimeout(5_000) { uploader.discard(upload).join() }
        assertTrue(stored().getValue(sha).committed)
        assertEquals(0, server.count("DELETE"))
    }

    @Test
    fun `commit uploads again a file the store lost while it was pending`() = runBlocking {
        val bytes = Random(4).nextBytes(30_000)
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val upload = uploader.start(file("twice.bin", bytes))
        ok(upload.awaitStored(5_000))
        // The 24 h sweep, or the same file removed from another draft
        stored().remove(sha)

        assertEquals(sha, ok(upload.commitWhenStored().await()).sha256)
        assertTrue(stored().getValue(sha).committed)
        assertEquals(2, server.count("PUT"))
    }

    @Test
    fun `lazy backfill - hash, upload and commit in one call`() = runBlocking {
        val bytes = Random(5).nextBytes(70_000)
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val old = Attachment(local_file_path = file("old.pdf", bytes).path, file_name = "old.pdf", mime_type = "application/pdf", file_GOOGLE_uri = "uri")
        assertNull(old.sha256)

        val digest = ok(uploader.ensureStored(File(old.local_file_path!!)))
        assertEquals(FileDigest(sha, 70_000), digest)
        assertTrue(stored().getValue(sha).committed)
        assertEquals(old.copy(sha256 = sha, size = 70_000), old.withDigest(digest))

        // Again (another request needing the same file): nothing is sent, no second commit needed
        val puts = server.count("PUT")
        val posts = server.count("POST")
        assertEquals(digest, ok(uploader.ensureStored(File(old.local_file_path!!))))
        assertEquals(puts, server.count("PUT"))
        assertEquals(posts, server.count("POST"))
    }

    @Test
    fun `commit by digest alone - with and without a local copy`() = runBlocking {
        val bytes = "saved before its upload finished".toByteArray()
        val digest = FileDigest(FakeAttachmentStoreServer.sha256(bytes), bytes.size.toLong())

        // The store does not have it and this device has no copy: NOT_FOUND
        assertEquals(StoreError.NOT_FOUND, assertIs<StoreResult.Failed>(uploader.commit(digest)).error)
        // With the local copy it is uploaded, then committed
        assertEquals(digest, ok(uploader.commit(digest, file("late.txt", bytes))))
        assertTrue(stored().getValue(digest.sha256).committed)
        assertEquals(digest, ok(uploader.commit(digest)))
    }

    @Test
    fun `a failed upload is reported and can be started again`() = runBlocking {
        server.failWith = 503
        val source = file("retry.bin", Random(6).nextBytes(10_000))
        val failed = uploader.start(source)
        assertEquals(StoreError.UNAVAILABLE, assertIs<StoreResult.Failed>(failed.awaitStored(5_000)).error)
        val state = assertIs<AttachmentUploadState.Failed>(failed.state.first { it is AttachmentUploadState.Failed })
        assertNotNull(state.digest, "the hash is known even though the upload failed")
        assertIs<StoreResult.Failed>(failed.commitWhenStored().await())
        assertIs<StoreResult.Failed>(uploader.ensureStored(source))

        server.failWith = null
        val retry = uploader.start(source, state.digest)
        assertEquals(state.digest, ok(retry.awaitStored(5_000)))
    }

    @Test
    fun `an unreadable file fails without a digest`() = runBlocking {
        val upload = uploader.start(File(dir, "missing.bin"))
        assertEquals(StoreError.LOCAL_IO, assertIs<StoreResult.Failed>(upload.awaitStored(5_000)).error)
        assertNull(upload.digest())
        assertEquals(StoreError.LOCAL_IO, assertIs<StoreResult.Failed>(uploader.ensureStored(File(dir, "missing.bin"))).error)
        assertTrue(server.requests.isEmpty())
    }
}
