package com.example.ApI.data.attachments

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.repository.AttachmentOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device's side of the attachment store (CLOUD_RUNS_PLAN.md, "Attachment store", Clients /
 * Android): draft uploads and their UI state, the send, the files of a request (wait, backfill,
 * fallback) and attachments of other devices — against the fake store server.
 */
class DeviceAttachmentsTest {
    private lateinit var server: FakeAttachmentStoreServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @TempDir
    lateinit var dir: File

    private var config: AttachmentStoreConfig? = null
    private lateinit var client: AttachmentStoreClient
    private lateinit var device: DeviceAttachments

    private class Recorded(val username: String, val owner: AttachmentOwner, val attachment: Attachment, val digest: FileDigest)
    private val recorded = CopyOnWriteArrayList<Recorded>()

    @BeforeEach
    fun setUp() {
        server = FakeAttachmentStoreServer()
        server.addUser("tok")
        server.start()
        config = AttachmentStoreConfig(server.baseUrl, "tok")
        device = newDevice()
    }

    private fun newDevice(known: KnownStoredFiles = KnownStoredFiles(null) { "memory" }, maxFileBytes: Long = 1_000_000): DeviceAttachments {
        client = AttachmentStoreClient({ config }, maxFileBytes = maxFileBytes, controlTimeoutMs = 5_000, transferIdleTimeoutMs = 5_000)
        return DeviceAttachments(
            store = client,
            resolver = AttachmentResolver(File(dir, "cache").apply { mkdirs() }, client),
            scope = scope,
            recordIdentity = { username, owner, attachment, digest ->
                recorded += Recorded(username, owner, attachment, digest)
                true
            },
            known = known,
            hashWaitMs = 5_000,
            commitRetryDelaysMs = listOf(50L, 50L)
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.stop()
    }

    private fun file(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }
    private fun stored() = server.files("tok")
    private fun attachment(file: File, mime: String = "image/png") =
        Attachment(local_file_path = file.path, file_name = file.name, mime_type = mime)

    private suspend fun until(what: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what")
        }
    }

    // ── Drafts ────────────────────────────────────────────────────────────────

    @Test
    fun `attach uploads in the background and shows the upload until it is stored`() = runBlocking {
        val bytes = Random(1).nextBytes(50_000)
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val gate = CountDownLatch(1)
        server.putGate = gate
        val f = file("a.png", bytes)

        device.attach(f)
        // Greyed + spinner while it is on its way
        until("the uploading state") { device.transfers.value[f.path] is AttachmentTransferState.Uploading }
        gate.countDown()
        until("the state to clear") { device.transfers.value.isEmpty() }
        assertEquals(false, stored()[sha]?.committed, "a draft is pending, not committed")
    }

    @Test
    fun `removing a draft deletes it from the store - attach, remove, attach, remove leaves nothing`() = runBlocking {
        val bytes = Random(2).nextBytes(20_000)
        repeat(2) { round ->
            val f = file("draft$round.bin", bytes)
            device.attach(f)
            until("the upload") { stored().isNotEmpty() && device.transfers.value.isEmpty() }
            device.remove(f.path)
            until("the delete") { stored().isEmpty() }
        }
        assertEquals(2, server.count("DELETE"))
        assertTrue(device.transfers.value.isEmpty())
    }

    @Test
    fun `removing a draft whose upload is still running stops it and leaves nothing`() = runBlocking {
        val gate = CountDownLatch(1)
        server.putGate = gate
        val f = file("slow.bin", Random(3).nextBytes(20_000))
        device.attach(f)
        until("the uploading state") { (device.transfers.value[f.path] as? AttachmentTransferState.Uploading)?.total == 20_000L }
        device.remove(f.path)
        assertNull(device.transfers.value[f.path], "the state goes with the file")
        gate.countDown()
        delay(300)
        assertTrue(stored().isEmpty(), "nothing may stay in the store")
    }

    @Test
    fun `a failed upload is a visible state of its own and retry uploads the file`() = runBlocking {
        val bytes = Random(4).nextBytes(10_000)
        val f = file("f.bin", bytes)
        server.failWith = 500
        device.attach(f)
        until("the failed state") { device.transfers.value[f.path] is AttachmentTransferState.Failed }

        server.failWith = null
        device.retry(f.path)
        until("the retry to finish") { device.transfers.value.isEmpty() }
        assertNotNull(stored()[FakeAttachmentStoreServer.sha256(bytes)])
    }

    @Test
    fun `a file over the limit is not uploaded, has no state and no hash`() = runBlocking {
        device = newDevice(maxFileBytes = 1_000)
        val f = file("big.bin", Random(5).nextBytes(5_000))
        device.attach(f)
        val used = device.use(f.path, attachment(f))
        delay(100)
        assertNull(used.sha256, "a device-only file carries no hash")
        assertTrue(device.transfers.value.isEmpty())
        assertEquals(0, server.requests.size, "nothing is asked of the store")
    }

    @Test
    fun `with sync off nothing happens at all`() = runBlocking {
        config = null
        val f = file("off.bin", Random(6).nextBytes(5_000))
        val a = attachment(f)
        device.attach(f)
        assertEquals(a, device.use(f.path, a))
        device.remove(f.path)
        val message = Message(id = "m", role = "user", text = "q", attachments = listOf(a))
        val prepared = device.prepareRequest("u", "c", null, listOf(message), emptyList(), { true }, 1_000)
        assertEquals(listOf(message), prepared.messages)
        device.backfill("u", "c", null, listOf(message), emptyList())
        assertEquals(listOf(message) to emptyList(), device.localize(listOf(message), emptyList()))
        delay(100)
        assertTrue(device.transfers.value.isEmpty())
        assertEquals(0, server.requests.size)
        assertTrue(recorded.isEmpty())
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    @Test
    fun `send gives the attachment its hash before the upload ends and commits when it is stored`() = runBlocking {
        val bytes = Random(7).nextBytes(60_000)
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val gate = CountDownLatch(1)
        server.putGate = gate
        val f = file("s.png", bytes)
        device.attach(f)

        val used = device.use(f.path, attachment(f))
        assertEquals(sha, used.sha256)
        assertEquals(60_000L, used.size)
        assertTrue(stored().isEmpty(), "the upload is still running")

        gate.countDown()
        until("the commit") { stored()[sha]?.committed == true }
        until("the state to clear") { device.transfers.value.isEmpty() }
        // Removing the (sent) file's path later does not delete a committed file
        device.remove(f.path)
        delay(100)
        assertNotNull(stored()[sha])
    }

    @Test
    fun `send of a file whose upload failed starts it again and the commit is retried`() = runBlocking {
        val bytes = Random(8).nextBytes(10_000)
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val f = file("r.bin", bytes)
        server.failWith = 500
        device.attach(f)
        until("the failed state") { device.transfers.value[f.path] is AttachmentTransferState.Failed }

        val used = device.use(f.path, attachment(f))
        assertEquals(sha, used.sha256, "the message is saved with the hash although the store is down")
        server.failWith = null
        until("the file to be stored and committed later") { stored()[sha]?.committed == true }
        until("the state to clear") { device.transfers.value.isEmpty() }
    }

    @Test
    fun `send of a file that was attached while sync was off uploads it now`() = runBlocking {
        val bytes = Random(9).nextBytes(10_000)
        val f = file("late.bin", bytes)
        val used = device.use(f.path, attachment(f))
        assertEquals(FakeAttachmentStoreServer.sha256(bytes), used.sha256)
        until("the commit") { stored()[used.sha256]?.committed == true }
    }

    // ── Requests ──────────────────────────────────────────────────────────────

    @Test
    fun `a cloud request waits for the upload of a just-attached file`() = runBlocking {
        val bytes = Random(10).nextBytes(30_000)
        val gate = CountDownLatch(1)
        server.putGate = gate
        val f = file("w.png", bytes)
        device.attach(f)
        val used = device.use(f.path, attachment(f))
        val message = Message(id = "m1", role = "user", text = "q", attachments = listOf(used))

        val opener = Thread { Thread.sleep(300); gate.countDown() }.apply { start() }
        val prepared = device.prepareRequest("u", "c", null, listOf(message), emptyList(), { true }, 5_000)
        opener.join()
        assertTrue(prepared.ready, "missing: ${prepared.missing}")
        assertEquals(true, stored()[used.sha256]?.committed)
        assertEquals(1, server.count("PUT"), "the file is uploaded once")
    }

    @Test
    fun `an upload that is not done in time sends the request to the device and goes on in the background`() = runBlocking {
        val bytes = Random(11).nextBytes(30_000)
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val gate = CountDownLatch(1)
        server.putGate = gate
        val f = file("t.png", bytes)
        device.attach(f)
        val used = device.use(f.path, attachment(f))
        val message = Message(id = "m1", role = "user", text = "q", attachments = listOf(used))

        val prepared = device.prepareRequest("u", "c", null, listOf(message), emptyList(), { true }, 200)
        assertFalse(prepared.ready)
        assertEquals(listOf("t.png"), prepared.missing)
        assertEquals(sha, prepared.messages.single().attachments.single().sha256)

        gate.countDown()
        until("the upload to finish later") { stored()[sha]?.committed == true }
        // The next request finds it there
        assertTrue(device.prepareRequest("u", "c", null, listOf(message), emptyList(), { true }, 200).ready)
    }

    @Test
    fun `backfill - old attachments without a hash are stored, committed and their identity recorded`() = runBlocking {
        val a = file("old-a.png", Random(12).nextBytes(8_000))
        val p = file("project.pdf", Random(13).nextBytes(9_000))
        val shaA = FakeAttachmentStoreServer.sha256(a.readBytes())
        val shaP = FakeAttachmentStoreServer.sha256(p.readBytes())
        val gone = Attachment(local_file_path = File(dir, "deleted.png").path, file_name = "deleted.png", mime_type = "image/png")
        val messages = listOf(
            Message(id = "q1", role = "user", text = "q", attachments = listOf(attachment(a), gone)),
            Message(id = "a1", role = "assistant", text = "a")
        )

        val prepared = device.prepareRequest("alice", "chat", "group", messages, listOf(attachment(p, "application/pdf")), { true }, 5_000)

        assertTrue(prepared.ready, "a file that is nowhere is left to the server to judge; missing: ${prepared.missing}")
        assertEquals(listOf(shaA, null), prepared.messages[0].attachments.map { it.sha256 })
        assertEquals(a.path, prepared.messages[0].attachments[0].local_file_path, "the path stays")
        assertEquals(shaP, prepared.projectAttachments.single().sha256)
        assertEquals(true, stored()[shaA]?.committed)
        assertEquals(true, stored()[shaP]?.committed)
        // Recorded on the stored attachment, by owner, before the request goes out
        val byOwner = recorded.associate { it.owner to it }
        assertEquals(setOf(AttachmentOwner.ChatMessage("chat", "q1"), AttachmentOwner.Group("group")), byOwner.keys)
        val message = byOwner.getValue(AttachmentOwner.ChatMessage("chat", "q1"))
        assertEquals("alice", message.username)
        assertEquals(FileDigest(shaA, 8_000), message.digest)
        assertNull(message.attachment.sha256, "matched by the attachment as it is stored (no hash yet)")

        // Once stored, the same request costs nothing
        val before = server.requests.size
        assertTrue(device.prepareRequest("alice", "chat", "group", prepared.messages, prepared.projectAttachments, { true }, 5_000).ready)
        assertEquals(before, server.requests.size)
    }

    @Test
    fun `only the files the provider reads hold a request back - the others are stored in the background`() = runBlocking {
        val needed = file("needed.txt", Random(14).nextBytes(4_000))
        val byId = file("by-id.png", Random(15).nextBytes(4_000))
        val ofAssistant = file("generated.png", Random(16).nextBytes(4_000))
        val gate = CountDownLatch(1)
        val messages = listOf(
            Message(id = "q1", role = "user", text = "q", attachments = listOf(
                attachment(needed, "text/plain"),
                attachment(byId).copy(file_OPENAI_id = "file-1")
            )),
            Message(id = "a1", role = "assistant", text = "a", attachments = listOf(attachment(ofAssistant)))
        )
        // Only the needed file gets through; the others wait at the gate
        device.prepareRequest("u", "c", null, messages.take(1).map { it.copy(attachments = it.attachments.take(1)) }, emptyList(), { true }, 5_000)
        server.putGate = gate

        val prepared = device.prepareRequest("u", "c", null, messages, emptyList(), { providerReadsFile("openai", it) }, 300)
        assertTrue(prepared.ready, "missing: ${prepared.missing}")
        assertEquals(1, stored().size)

        gate.countDown()
        until("the other files to be stored in the background") { stored().size == 3 && stored().values.all { it.committed } }
        until("their identity to be recorded") { recorded.map { it.attachment.file_name }.toSet().size == 3 }
    }

    @Test
    fun `a device-only file of a request keeps it on the device`() = runBlocking {
        device = newDevice(maxFileBytes = 1_000)
        val big = file("big.bin", Random(17).nextBytes(5_000))
        val message = Message(id = "q", role = "user", text = "q", attachments = listOf(attachment(big, "application/zip")))
        val prepared = device.prepareRequest("u", "c", null, listOf(message), emptyList(), { true }, 1_000)
        assertFalse(prepared.ready)
        assertEquals(0, server.requests.size)
    }

    @Test
    fun `a hashed attachment whose file never reached the store is uploaded by the next request`() = runBlocking {
        // The message was saved with the hash, then the app died before the upload finished
        val bytes = Random(18).nextBytes(7_000)
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        val f = file("orphan.png", bytes)
        val message = Message(id = "q", role = "user", text = "q", attachments = listOf(attachment(f).copy(sha256 = sha, size = 7_000)))

        device.backfill("u", "c", null, listOf(message), emptyList())
        until("the upload + commit") { stored()[sha]?.committed == true }
        assertTrue(recorded.isEmpty(), "it already has its hash")
    }

    @Test
    fun `a pending file that another client deleted before the commit is uploaded again - at the send and after a restart`() = runBlocking {
        // The store only knows pending / committed: another client that attached the same bytes
        // and removed them deletes this device's pending file too. The device still has the bytes.
        val otherClient = AttachmentStoreClient({ config }, controlTimeoutMs = 5_000, transferIdleTimeoutMs = 5_000)

        // 1. Deleted between the upload and the send's commit
        val first = Random(31).nextBytes(9_000)
        val firstSha = FakeAttachmentStoreServer.sha256(first)
        val f1 = file("first.png", first)
        device.attach(f1)
        until("the upload") { stored()[firstSha] != null && device.transfers.value.isEmpty() }
        assertEquals(true, otherClient.deletePending(firstSha).getOrNull())
        assertNull(stored()[firstSha])
        assertEquals(firstSha, device.use(f1.path, attachment(f1)).sha256)
        until("the re-upload + commit") { stored()[firstSha]?.committed == true }
        assertTrue(first.contentEquals(stored().getValue(firstSha).bytes))

        // 2. The message is saved while the store is down; the commit gives up; the file is
        //    deleted meanwhile; the app restarts
        val second = Random(32).nextBytes(9_000)
        val secondSha = FakeAttachmentStoreServer.sha256(second)
        val f2 = file("second.png", second)
        device.attach(f2)
        until("the upload") { stored()[secondSha] != null && device.transfers.value.isEmpty() }
        server.failWith = 503
        val saved = device.use(f2.path, attachment(f2))
        assertEquals(secondSha, saved.sha256)
        until("the commit to give up") { server.count("POST", secondSha) >= 3 }
        delay(150)
        server.failWith = null
        assertEquals(false, stored().getValue(secondSha).committed)
        assertEquals(true, otherClient.deletePending(secondSha).getOrNull())

        device = newDevice()        // a new process: nothing remembered but the saved message
        val message = Message(id = "q", role = "user", text = "q", attachments = listOf(saved))
        val prepared = device.prepareRequest("u", "c", null, listOf(message), emptyList(), { true }, 5_000)
        assertTrue(prepared.ready, "the next request that includes the message stores the file: ${prepared.missing}")
        assertEquals(true, stored()[secondSha]?.committed)
        assertTrue(second.contentEquals(stored().getValue(secondSha).bytes))
    }

    @Test
    fun `an attachment of another device is trusted to be in the store, and asked about again after the server refused`() = runBlocking {
        val sha = "c".repeat(64)
        val foreign = Attachment(local_file_path = null, file_name = "web.png", mime_type = "image/png", sha256 = sha, size = 3)
        val message = Message(id = "q", role = "user", text = "q", attachments = listOf(foreign))
        assertTrue(device.prepareRequest("u", "c", null, listOf(message), emptyList(), { true }, 1_000).ready)
        assertEquals(0, server.requests.size)

        // This device's own file, known as stored — until the server answers attachments_unsupported
        val bytes = Random(19).nextBytes(3_000)
        val f = file("mine.png", bytes)
        val mine = Message(id = "q2", role = "user", text = "q", attachments = listOf(attachment(f)))
        val prepared = device.prepareRequest("u", "c", null, listOf(mine), emptyList(), { true }, 5_000)
        stored().clear()   // the store lost it
        assertTrue(device.prepareRequest("u", "c", null, prepared.messages, emptyList(), { true }, 5_000).ready)
        assertTrue(stored().isEmpty(), "believed stored: nothing asked")
        device.forgetStored(prepared.messages, emptyList())
        assertTrue(device.prepareRequest("u", "c", null, prepared.messages, emptyList(), { true }, 5_000).ready)
        assertEquals(true, stored()[prepared.messages.single().attachments.single().sha256]?.committed, "uploaded again")
    }

    @Test
    fun `the known stored files survive a restart, per account`() = runBlocking {
        val state = File(dir, "state")
        var account = "acc1"
        device = newDevice(KnownStoredFiles(state) { account })
        val f = file("k.png", Random(20).nextBytes(3_000))
        val message = Message(id = "q", role = "user", text = "q", attachments = listOf(attachment(f)))
        val prepared = device.prepareRequest("u", "c", null, listOf(message), emptyList(), { true }, 5_000)
        val sha = prepared.messages.single().attachments.single().sha256!!

        val restarted = KnownStoredFiles(state) { account }
        assertTrue(restarted.contains(sha))
        account = "acc2"
        assertFalse(restarted.contains(sha), "another account's store")
        assertFalse(File(state, "known_acc1.txt").exists(), "the other account's list is dropped")
        assertNotNull(KnownStoredFiles.accountKey(config))
        assertNull(KnownStoredFiles.accountKey(null))
    }

    // ── Files of other devices ────────────────────────────────────────────────

    @Test
    fun `localize downloads files this device lacks and leaves its own alone`() = runBlocking {
        val remoteBytes = Random(21).nextBytes(6_000)
        val remoteSha = FakeAttachmentStoreServer.sha256(remoteBytes)
        stored()[remoteSha] = FakeAttachmentStoreServer.Stored(remoteBytes, true)
        val own = file("own.png", Random(22).nextBytes(2_000))
        val ownAttachment = attachment(own).copy(sha256 = FakeAttachmentStoreServer.sha256(own.readBytes()))
        val fromWeb = Attachment(local_file_path = null, file_name = "web.pdf", mime_type = "application/pdf", sha256 = remoteSha, size = 6_000)
        val fromPhone = fromWeb.copy(local_file_path = "/data/user/0/other/files/attachments/x_web.pdf")
        val unknown = Attachment(local_file_path = null, file_name = "lost.png", mime_type = "image/png", sha256 = "d".repeat(64))
        val messages = listOf(
            Message(id = "q", role = "user", text = "q", attachments = listOf(ownAttachment, fromWeb, unknown)),
            Message(id = "a", role = "assistant", text = "a", attachments = listOf(fromWeb))
        )

        val (localMessages, localProject) = device.localize(messages, listOf(fromPhone))

        val user = localMessages[0].attachments
        assertEquals(ownAttachment, user[0])
        val cached = File(user[1].local_file_path!!)
        assertTrue(cached.readBytes().contentEquals(remoteBytes))
        assertEquals(remoteSha, cached.name, "the cache copy")
        assertEquals(unknown, user[2], "a file that cannot be had is left as it is")
        assertEquals(fromWeb, localMessages[1].attachments.single(), "assistant attachments are not sent: not fetched")
        assertEquals(cached.path, localProject.single().local_file_path)
        assertEquals(1, server.count("GET", remoteSha), "downloaded once")
    }

    @Test
    fun `python tool files - inputs from the store, outputs saved here with a hash and uploaded`() = runBlocking {
        val remoteBytes = Random(23).nextBytes(4_000)
        val remoteSha = FakeAttachmentStoreServer.sha256(remoteBytes)
        stored()[remoteSha] = FakeAttachmentStoreServer.Stored(remoteBytes, true)
        val files = device.pythonFiles(File(dir, "attachments"))
        val fromWeb = Attachment(local_file_path = null, file_name = "data.csv", mime_type = "text/csv", sha256 = remoteSha)
        val own = file("own.csv", byteArrayOf(1, 2, 3))

        assertTrue(files.isAvailable(fromWeb))
        assertTrue(files.isAvailable(attachment(own, "text/csv")))
        assertFalse(files.isAvailable(Attachment(local_file_path = null, file_name = "x", mime_type = "text/csv")))
        assertTrue(files.inputFile(fromWeb)!!.readBytes().contentEquals(remoteBytes))
        assertEquals(own.path, files.inputFile(attachment(own, "text/csv"))!!.path)

        val out = Random(24).nextBytes(5_000)
        val saved = assertNotNull(files.saveOutput("chart.png", "image/png", out))
        val sha = FakeAttachmentStoreServer.sha256(out)
        assertEquals(sha, saved.sha256)
        assertEquals(5_000L, saved.size)
        val local = File(saved.local_file_path!!)
        assertTrue(local.readBytes().contentEquals(out), "kept on the device as always")
        assertTrue(local.name.endsWith("_chart.png") && local.parentFile.name == "attachments")
        until("the generated file to reach the store") { stored()[sha]?.committed == true }
    }

    @Test
    fun `provider reads file - the server's rule`() {
        val image = Attachment(local_file_path = "/x", file_name = "i.png", mime_type = "image/png")
        val text = Attachment(local_file_path = "/x", file_name = "t.txt", mime_type = "text/plain")
        assertTrue(providerReadsFile("openai", image))
        assertFalse(providerReadsFile("openai", image.copy(file_OPENAI_id = "f")))
        assertTrue(providerReadsFile("openai", text.copy(file_OPENAI_id = "f")), "inlined as text whatever its id")
        assertFalse(providerReadsFile("poe", image.copy(file_POE_url = "u")))
        assertTrue(providerReadsFile("google", image.copy(file_GOOGLE_uri = "{file_URI}")))
        assertFalse(providerReadsFile("google", image.copy(file_GOOGLE_uri = "files/1")))
        assertTrue(providerReadsFile("anthropic", image.copy(file_OPENAI_id = "f", file_POE_url = "u", file_GOOGLE_uri = "g")))
    }
}
