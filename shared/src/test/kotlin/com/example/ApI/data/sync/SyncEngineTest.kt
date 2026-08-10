package com.example.ApI.data.sync

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.RemoteSyncSettings
import com.example.ApI.util.JsonConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * In-memory fake of the sync-server v2 protocol (manifest / get / put),
 * backed by the JDK's built-in HttpServer — no test-only dependencies.
 */
private class FakeSyncServer {
    data class Blob(val content: String, val updatedAt: Long, val sha: String)

    val blobs = ConcurrentHashMap<String, Blob>()
    val putBodies = ConcurrentHashMap<String, String>()
    private val clock = AtomicLong(1_000_000L)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun nextVersion(): Long =
        clock.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }

    fun seed(filename: String, content: String, updatedAt: Long? = null) {
        blobs[filename] = Blob(content, updatedAt ?: nextVersion(), sha256(content))
    }

    fun start() {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/sync/health" -> respond(exchange, 200, "{}")
                path == "/sync/manifest" -> {
                    val body = blobs.entries.joinToString(",", "[", "]") { (name, b) ->
                        """{"filename":"$name","updated_at":${b.updatedAt},"sha":"${b.sha}"}"""
                    }
                    respond(exchange, 200, body)
                }
                path.startsWith("/sync/file/") && exchange.requestMethod == "GET" -> {
                    val name = URLDecoder.decode(path.removePrefix("/sync/file/"), "UTF-8")
                    val blob = blobs[name]
                    if (blob == null) respond(exchange, 404, """{"detail":"Not found"}""")
                    else respond(
                        exchange, 200,
                        """{"filename":"$name","content":${JsonConfig.prettyPrint.encodeToString(blob.content)},"updated_at":${blob.updatedAt},"sha":"${blob.sha}"}"""
                    )
                }
                path.startsWith("/sync/file/") && exchange.requestMethod == "PUT" -> {
                    val name = URLDecoder.decode(path.removePrefix("/sync/file/"), "UTF-8")
                    val body = exchange.requestBody.readBytes().decodeToString()
                    val content = JsonConfig.prettyPrint.decodeFromString<Map<String, String>>(body).getValue("content")
                    putBodies[name] = content
                    val b = Blob(content, nextVersion(), sha256(content))
                    blobs[name] = b
                    respond(exchange, 200, """{"filename":"$name","updated_at":${b.updatedAt},"sha":"${b.sha}"}""")
                }
                else -> respond(exchange, 404, """{"detail":"Not found"}""")
            }
        }
        server.start()
    }

    fun stop() = server.stop(0)

    /** Wait until the engine's debounced upload for [filename] arrives (or timeout). */
    fun awaitPut(filename: String, timeoutMs: Long = 5000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            putBodies[filename]?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

class SyncEngineTest {

    private val json = JsonConfig.prettyPrint
    private lateinit var tempDir: File
    private lateinit var fake: FakeSyncServer
    private lateinit var syncApiKeysOn: AppSettings

    @BeforeEach
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "sync-engine-test-${System.nanoTime()}")
        tempDir.mkdirs()
        fake = FakeSyncServer()
        fake.start()
        syncApiKeysOn = AppSettings(
            current_user = "u",
            selected_provider = "openai",
            selected_model = "gpt-4o",
            remoteSync = RemoteSyncSettings(
                enabled = true,
                serverBaseUrl = fake.baseUrl,
                authToken = "test-token",
                syncApiKeys = true
            )
        )
    }

    @AfterEach
    fun teardown() {
        fake.stop()
        tempDir.deleteRecursively()
    }

    private fun engine() = SyncEngine(tempDir, json) { syncApiKeysOn }

    private fun keysFile() = File(tempDir, "api_keys_u.json")

    // ── The bug: toggling syncApiKeys on a device whose local file was never
    //    synced must resolve LAST-WRITE-WINS, never silently discarding data. ──

    @Test
    fun `pull does not clobber a newer locally-existing never-synced file - local wins and uploads`() = runBlocking {
        val remoteKeys = """[{"id":"1","provider":"openai","key":"sk-old-remote"}]"""
        val localKeys = """[{"id":"1","provider":"openai","key":"sk-old-remote"},{"id":"2","provider":"google","key":"new-local-key"}]"""
        // Server version is old; the local file (written just now) is newer → local wins
        fake.seed("api_keys_u.json", remoteKeys, updatedAt = 10_000L)
        keysFile().writeText(localKeys)

        engine().pull()

        // Local content untouched (no silent destroy)
        assertEquals(localKeys, keysFile().readText(), "local file must not be overwritten by remote")
        // Dirty flag set → end-of-pull flush uploads local content to the server
        val uploaded = fake.awaitPut("api_keys_u.json")
        assertEquals(localKeys, uploaded, "engine should upload the local (never-synced) version")
    }

    @Test
    fun `pull adopts remote when the local never-synced copy is OLDER - stale device does not overwrite fresh remote`() = runBlocking {
        val remoteKeys = """[{"id":"1","provider":"openai","key":"sk-fresh-remote"}]"""
        val staleLocalKeys = """[{"id":"0","provider":"openai","key":"sk-stale-local"}]"""
        val remoteVersion = System.currentTimeMillis() + 3_600_000L
        fake.seed("api_keys_u.json", remoteKeys, updatedAt = remoteVersion)
        keysFile().writeText(staleLocalKeys)
        // Local file is a day older than the server's version
        keysFile().setLastModified(remoteVersion - 86_400_000L)

        engine().pull()

        assertEquals(remoteKeys, keysFile().readText(), "stale local copy must adopt the fresher remote")
        assertTrue(fake.putBodies.isEmpty(), "stale local copy must not be uploaded")
        assertEquals(
            remoteVersion,
            SyncState(tempDir, json).apply { load() }.baseServerVersion("api_keys_u.json")
        )
    }

    @Test
    fun `pull adopts remote blob when the file does not exist locally`() = runBlocking {
        val remoteKeys = """[{"id":"1","provider":"openai","key":"sk-remote"}]"""
        fake.seed("api_keys_u.json", remoteKeys)
        assertFalse(keysFile().exists())

        engine().pull()

        assertEquals(remoteKeys, keysFile().readText(), "missing local file should adopt remote content")
        assertTrue(fake.putBodies.isEmpty(), "adopting must not trigger any upload")
    }

    @Test
    fun `pull is a quiet no-op when local content already matches remote sha`() = runBlocking {
        val keys = """[{"id":"1","provider":"openai","key":"sk-same"}]"""
        fake.seed("api_keys_u.json", keys, updatedAt = 10_000L)
        keysFile().writeText(keys)

        engine().pull()

        assertEquals(keys, keysFile().readText())
        assertTrue(fake.putBodies.isEmpty(), "identical content must not be re-uploaded")
        // base recorded — next pull skips the sha work entirely
        assertEquals(
            10_000L,
            SyncState(tempDir, json).apply { load() }.baseServerVersion("api_keys_u.json")
        )
    }

    @Test
    fun `app_settings keeps adopt+merge behavior even when it exists locally`() = runBlocking {
        // Server has account-level settings the fresh device should recover
        fake.seed(
            "app_settings.json",
            json.encodeToString(
                AppSettings(current_user = "u", selected_provider = "google", selected_model = "gemini-2.5-pro")
            ),
            updatedAt = 10_000L
        )

        // Local file exists with device-local sync credentials (never synced)
        File(tempDir, "app_settings.json").writeText(json.encodeToString(syncApiKeysOn))

        engine().pull()

        val merged = json.decodeFromString(AppSettings.serializer(), File(tempDir, "app_settings.json").readText())
        assertEquals("google", merged.selected_provider, "remote account settings should be adopted")
        assertEquals("gemini-2.5-pro", merged.selected_model)
        assertTrue(merged.remoteSync.enabled, "device-local remoteSync must survive the merge")
        assertEquals("test-token", merged.remoteSync.authToken)
        assertTrue(merged.remoteSync.syncApiKeys, "local syncApiKeys opt-in must survive the merge")
    }

    @Test
    fun `pull still adopts newer remote versions for previously-synced files`() = runBlocking {
        val oldKeys = """[{"id":"1","provider":"openai","key":"sk-old"}]"""
        val newKeys = """[{"id":"1","provider":"openai","key":"sk-new"}]"""
        fake.seed("api_keys_u.json", oldKeys, updatedAt = 10_000L)
        keysFile().writeText(oldKeys)

        // First pull: identical content just records the base version
        engine().pull()

        // Another device pushes a newer version
        fake.seed("api_keys_u.json", newKeys, updatedAt = 20_000L)
        engine().pull()

        assertEquals(newKeys, keysFile().readText(), "newer remote version should be adopted")
        assertEquals(
            20_000L,
            SyncState(tempDir, json).apply { load() }.baseServerVersion("api_keys_u.json"),
            "base should advance to the new server version"
        )
    }
}
