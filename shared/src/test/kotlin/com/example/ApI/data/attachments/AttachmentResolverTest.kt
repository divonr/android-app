package com.example.ApI.data.attachments

import com.example.ApI.data.PlatformStorage
import com.example.ApI.data.model.Attachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentResolverTest {
    private lateinit var server: FakeAttachmentStoreServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @TempDir
    lateinit var dir: File

    private lateinit var cacheDir: File
    private lateinit var client: AttachmentStoreClient

    @BeforeEach
    fun setUp() {
        server = FakeAttachmentStoreServer()
        server.addUser("tok")
        server.start()
        cacheDir = object : PlatformStorage {
            override val filesDir = File(dir, "files")
            override val downloadsDir: File? = null
        }.attachmentsCacheDir
        client = AttachmentStoreClient({ AttachmentStoreConfig(server.baseUrl, "tok") }, controlTimeoutMs = 5_000, transferIdleTimeoutMs = 5_000)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.stop()
    }

    private fun resolver(trust: (File) -> Boolean = { true }) = AttachmentResolver(cacheDir, client, trust)

    /** Puts [bytes] in the store (as another device would) and returns their hash. */
    private fun inStore(bytes: ByteArray): String {
        val sha = FakeAttachmentStoreServer.sha256(bytes)
        server.files("tok")[sha] = FakeAttachmentStoreServer.Stored(bytes, committed = true)
        return sha
    }

    private fun attachment(path: String?, sha: String?, size: Long? = null) =
        Attachment(local_file_path = path, file_name = "doc.pdf", mime_type = "application/pdf", sha256 = sha, size = size)

    private fun available(r: ResolvedAttachment) = assertIs<ResolvedAttachment.Available>(r, "expected a file, got $r")
    private fun unavailable(r: ResolvedAttachment) = assertIs<ResolvedAttachment.Unavailable>(r, "expected unavailable, got $r")

    @Test
    fun `the cache directory comes from the platform storage`() {
        assertEquals(File(dir, "files/attachments_cache"), cacheDir)
        assertEquals(File(cacheDir, "a".repeat(64)), resolver().cacheFile("a".repeat(64)))
    }

    @Test
    fun `resolution order - local path, then cache, then download`() = runBlocking {
        val bytes = Random(1).nextBytes(60_000)
        val sha = inStore(bytes)
        val local = File(dir, "on-this-device.pdf").apply { writeBytes(bytes) }
        val r = resolver()

        // 1. The local file wins, the store is not asked
        val first = available(r.resolve(attachment(local.path, sha)))
        assertEquals(AttachmentSource.LOCAL, first.source)
        assertEquals(local, first.file)
        assertTrue(server.requests.isEmpty())
        assertFalse(cacheDir.exists(), "nothing was cached")

        // 3. The path of another device: downloaded into the cache under the hash
        val foreign = attachment("/data/user/0/com.example.ApI/files/llm_data/attachments/x_doc.pdf", sha, bytes.size.toLong())
        assertNull(r.resolveLocal(foreign), "not on this device yet")
        val second = available(r.resolve(foreign))
        assertEquals(AttachmentSource.DOWNLOADED, second.source)
        assertEquals(File(cacheDir, sha), second.file)
        assertContentEquals(bytes, second.file.readBytes())
        assertEquals(1, server.count("GET"))

        // 2. From now on the cache answers, also without a local path at all, and offline
        server.failWith = 503
        val third = available(r.resolve(attachment(null, sha)))
        assertEquals(AttachmentSource.CACHE, third.source)
        assertEquals(File(cacheDir, sha), third.file)
        assertEquals(AttachmentSource.CACHE, r.resolveLocal(foreign)!!.source)
        assertEquals(AttachmentSource.CACHE, available(r.resolveHash(sha)).source)
        assertEquals(1, server.count("GET"), "no second download")

        // A local file still wins over the cache
        assertEquals(AttachmentSource.LOCAL, available(r.resolve(attachment(local.path, sha))).source)
        // Only the hash-named file is in the cache directory
        assertEquals(listOf(sha), cacheDir.list()!!.toList())
    }

    @Test
    fun `an attachment without a hash is available only where its file is`() = runBlocking {
        val local = File(dir, "old.pdf").apply { writeText("attached before the store existed") }
        assertEquals(AttachmentSource.LOCAL, available(resolver().resolve(attachment(local.path, null))).source)

        val gone = unavailable(resolver().resolve(attachment("/storage/of/another/device/old.pdf", null)))
        assertEquals(StoreError.NOT_FOUND, gone.error)
        assertEquals(StoreError.NOT_FOUND, unavailable(resolver().resolve(attachment(null, null))).error)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `unavailable when the store does not have the file, cannot be reached, or the hash is invalid`() = runBlocking {
        val r = resolver()
        val unknown = "9".repeat(64)
        assertEquals(StoreError.NOT_FOUND, unavailable(r.resolve(attachment(null, unknown))).error)

        val sha = inStore("x".toByteArray())
        server.failWith = 500
        assertEquals(StoreError.UNAVAILABLE, unavailable(r.resolve(attachment(null, sha))).error)
        server.failWith = null
        // Not remembered as a failure: works once the store is back
        assertEquals(AttachmentSource.DOWNLOADED, available(r.resolve(attachment(null, sha))).source)

        val before = server.requests.size
        assertEquals(StoreError.NOT_FOUND, unavailable(r.resolve(attachment(null, "../../etc/passwd"))).error)
        assertEquals(StoreError.NOT_FOUND, unavailable(r.resolveHash("../../etc/passwd")).error)
        assertEquals(before, server.requests.size)
        assertFalse(File(cacheDir.parentFile, "etc").exists())
    }

    @Test
    fun `a corrupt download is not cached`() = runBlocking {
        val sha = inStore(Random(2).nextBytes(10_000))
        server.corruptDownloads = true
        assertEquals(StoreError.HASH_MISMATCH, unavailable(resolver().resolve(attachment(null, sha))).error)
        assertFalse(File(cacheDir, sha).exists())
        assertTrue(cacheDir.list().isNullOrEmpty(), "no temp file left in the cache")
    }

    @Test
    fun `an untrusted local path is not read - the store copy is used`() = runBlocking {
        val bytes = "the user's real file".toByteArray()
        val sha = inStore(bytes)
        val userDir = File(dir, "users/alice").apply { mkdirs() }
        val secret = File(dir, "server-secret.env").apply { writeText("SECRET=1") }
        val own = File(userDir, "upload.pdf").apply { writeBytes(bytes) }
        val r = resolver(trust = { it.canonicalPath.startsWith(userDir.canonicalPath + File.separator) })

        // A path a client made up, pointing at a file that exists on the server
        val forged = available(r.resolve(attachment(secret.path, sha)))
        assertEquals(AttachmentSource.DOWNLOADED, forged.source)
        assertContentEquals(bytes, forged.file.readBytes())
        // Without a hash such an attachment is simply unavailable
        assertEquals(StoreError.NOT_FOUND, unavailable(r.resolve(attachment(secret.path, null))).error)
        assertNull(r.resolveLocal(attachment(secret.path, null)))
        // The user's own file is fine
        assertEquals(own, available(r.resolve(attachment(own.path, null))).file)
    }

    @Test
    fun `concurrent resolutions of the same hash download it once`() = runBlocking {
        val bytes = Random(3).nextBytes(200_000)
        val sha = inStore(bytes)
        val other = inStore(Random(4).nextBytes(1_000))
        server.getDelayMs = 300
        val r = resolver()

        val results = withTimeout(20_000) {
            (1..24).map { i ->
                scope.async { r.resolve(attachment(if (i % 2 == 0) null else "/not/here/$i", sha)) }
            }.awaitAll()
        }
        assertEquals(1, server.count("GET", sha), "one download for 24 callers")
        results.forEach { result ->
            val file = available(result).file
            assertEquals(File(cacheDir, sha), file)
            assertContentEquals(bytes, file.readBytes())
        }
        assertEquals(1, results.count { available(it).source == AttachmentSource.DOWNLOADED })
        assertEquals(23, results.count { available(it).source == AttachmentSource.CACHE })

        // Another hash is its own download and is not held up by the first
        assertEquals(AttachmentSource.DOWNLOADED, available(r.resolve(attachment(null, other))).source)
        assertEquals(1, server.count("GET", other))
        assertEquals(setOf(sha, other), cacheDir.list()!!.toSet())
    }

    @Test
    fun `a cancelled resolution does not break the ones waiting behind it`() = runBlocking {
        val bytes = Random(5).nextBytes(100_000)
        val sha = inStore(bytes)
        val gate = CountDownLatch(1)
        server.getGate = gate
        val r = resolver()

        val first = scope.async { r.resolve(attachment(null, sha)) }
        withTimeout(5_000) { while (server.count("GET") == 0) delay(10) }
        val second = scope.async { r.resolve(attachment(null, sha)) }
        delay(100)
        withTimeout(5_000) { first.cancelAndJoin() }

        gate.countDown()
        val result = withTimeout(10_000) { second.await() }
        assertContentEquals(bytes, available(result).file.readBytes())
        assertEquals(listOf(sha), cacheDir.list()!!.toList())
    }
}
