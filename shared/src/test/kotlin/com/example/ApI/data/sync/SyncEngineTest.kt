package com.example.ApI.data.sync

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.repository.ChatHistoryManager
import com.example.ApI.util.SyncHolds
import com.example.ApI.data.model.RemoteSyncSettings
import com.example.ApI.util.JsonConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Single-engine tests of [SyncEngine], [SyncState] and [RemoteStorageClient] against the CAS
 * [FakeSyncServer].  Multi-device scenarios live in [MultiDeviceSyncTest] / [MultiDeviceSyncFuzzTest].
 */
class SyncEngineTest {

    private val json = JsonConfig.prettyPrint
    private lateinit var tempDir: File
    private lateinit var fake: FakeSyncServer
    private lateinit var syncApiKeysOn: AppSettings
    private val engines = mutableListOf<SyncEngine>()

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
        runBlocking { engines.forEach { it.closeAndJoin() } }
        fake.stop()
        tempDir.deleteRecursively()
    }

    private fun engine(debounceMs: Long = SyncEngine.UPLOAD_DEBOUNCE_MS, retryMs: Long = 0L, settings: () -> AppSettings = { syncApiKeysOn }) =
        SyncEngine(tempDir, json, settings, debounceMs, retryMs).also { engines += it }

    private fun keysFile() = File(tempDir, "api_keys_u.json")
    private fun keys(text: String) = json.decodeFromString(ListSerializer(ApiKey.serializer()), text)
    private fun keyIds(text: String) = keys(text).map { it.id }.toSet()
    private fun state() = SyncState(tempDir, json).apply { load() }

    // ── Never-synced local copies: merged with the account's copy (2-way union), never
    //    resolved by file mtime / last-write-wins any more. ──

    @Test
    fun `never-synced local and remote copies are merged and the union is uploaded`() = runBlocking {
        val remoteKeys = """[{"id":"1","provider":"openai","key":"sk-old-remote"}]"""
        val localKeys = """[{"id":"1","provider":"openai","key":"sk-old-remote"},{"id":"2","provider":"google","key":"new-local-key"}]"""
        fake.seed("api_keys_u.json", remoteKeys, updatedAt = 10_000L)
        keysFile().writeText(localKeys)

        engine().pull()

        // Local content kept (no silent destroy)
        assertEquals(setOf("1", "2"), keyIds(keysFile().readText()), "local keys must survive")
        // The union reached the server in the same pull (no debounce involved)
        assertEquals(setOf("1", "2"), keyIds(fake.content("api_keys_u.json")!!), "server must get the local key")
    }

    @Test
    fun `a never-synced local copy is merged whatever its mtime - an older device does not lose its keys`() = runBlocking {
        val remoteKeys = """[{"id":"1","provider":"openai","key":"sk-fresh-remote"}]"""
        val staleLocalKeys = """[{"id":"0","provider":"openai","key":"sk-stale-local"}]"""
        val remoteVersion = System.currentTimeMillis() + 3_600_000L
        fake.seed("api_keys_u.json", remoteKeys, updatedAt = remoteVersion)
        keysFile().writeText(staleLocalKeys)
        keysFile().setLastModified(remoteVersion - 86_400_000L)

        engine().pull()

        assertEquals(setOf("0", "1"), keyIds(keysFile().readText()))
        assertEquals(setOf("0", "1"), keyIds(fake.content("api_keys_u.json")!!))
        val entry = state().entry("api_keys_u.json")!!
        assertEquals(fake.blob("api_keys_u.json")!!.updatedAt, entry.baseServerVersion, "base = the uploaded version")
        assertEquals(fake.blob("api_keys_u.json")!!.sha, entry.baseServerSha)
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

        val e = engine()
        e.pull()

        assertEquals(keys, keysFile().readText())
        assertTrue(fake.putBodies.isEmpty(), "identical content must not be re-uploaded")
        assertEquals(10_000L, state().baseServerVersion("api_keys_u.json"))
        assertEquals(0L, e.changeTick.value, "nothing changed locally")
    }

    @Test
    fun `app_settings - the account's settings win on a first sync and device-local keys survive`() = runBlocking {
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

        engine { readSettings() }.pull()

        val merged = readSettings()
        assertEquals("google", merged.selected_provider, "remote account settings should be adopted")
        assertEquals("gemini-2.5-pro", merged.selected_model)
        assertTrue(merged.remoteSync.enabled, "device-local remoteSync must survive the merge")
        assertEquals("test-token", merged.remoteSync.authToken)
        assertTrue(merged.remoteSync.syncApiKeys, "local syncApiKeys opt-in must survive the merge")
    }

    private fun readSettings(): AppSettings =
        json.decodeFromString(AppSettings.serializer(), File(tempDir, "app_settings.json").readText())

    @Test
    fun `pull still adopts newer remote versions for previously-synced files`() = runBlocking {
        val oldKeys = """[{"id":"1","provider":"openai","key":"sk-old"}]"""
        val newKeys = """[{"id":"1","provider":"openai","key":"sk-new"}]"""
        fake.seed("api_keys_u.json", oldKeys, updatedAt = 10_000L)
        keysFile().writeText(oldKeys)

        val e = engine()
        // First pull: identical content just records the base version
        e.pull()

        // Another device pushes a newer version
        fake.seed("api_keys_u.json", newKeys, updatedAt = 20_000L)
        e.pull()

        assertEquals("sk-new", keys(keysFile().readText()).single().key, "newer remote version should be adopted")
        assertEquals(20_000L, state().baseServerVersion("api_keys_u.json"), "base should advance to the new server version")
        assertTrue(fake.putBodies.isEmpty(), "adopting a newer version must not upload")
        assertEquals(1L, e.changeTick.value)
    }

    // ── Held files: local chat history rebuilt from an unreadable copy ─────────

    private fun chats(vararg names: String) =
        UserChatHistory("u", names.map { Chat(chat_id = it, preview_name = it, messages = emptyList()) })

    @Test
    fun `a held chat history is never uploaded as is - pull merges it into the remote copy`() = runBlocking {
        val remote = json.encodeToString(chats("c0", "c1", "c2"))
        fake.seed("chat_history_u.json", remote, updatedAt = 10_000L)
        // Previously synced, with an upload still pending, then the local file got corrupted
        SyncState(tempDir, json).apply { load(); markPulled("chat_history_u.json", 10_000L); markDirty("chat_history_u.json"); save() }
        val file = File(tempDir, "chat_history_u.json")
        file.writeText(remote.dropLast(5))

        val engine = engine()
        val m = ChatHistoryManager(tempDir, json, onFileWritten = engine::onFileWritten)
        m.createNewChat("u", "new")
        assertTrue(SyncHolds.isHeld(file))
        engine.onFileWritten(file) // even a direct trigger must not upload the held file
        assertNull(fake.awaitPut("chat_history_u.json", timeoutMs = 1500), "held file was uploaded")

        engine.pull()

        val local = json.decodeFromString(UserChatHistory.serializer(), file.readText())
        assertEquals(setOf("c0", "c1", "c2", "new"), local.chat_history.map { it.preview_name }.toSet(),
            "reconcile must be a union: remote chats are not deleted by the rebuilt local copy")
        assertFalse(SyncHolds.isHeld(file), "hold released after reconciling")
        val uploaded = fake.awaitPut("chat_history_u.json")
        assertEquals(setOf("c0", "c1", "c2", "new"),
            json.decodeFromString(UserChatHistory.serializer(), uploaded!!).chat_history.map { it.preview_name }.toSet())
        assertTrue(tempDir.listFiles()!!.any { it.name.startsWith("chat_history_u.json.corrupt-") })
    }

    @Test
    fun `a held file that is still unreadable adopts the remote copy`() = runBlocking {
        val remote = json.encodeToString(chats("c0"))
        fake.seed("chat_history_u.json", remote, updatedAt = 10_000L)
        val file = File(tempDir, "chat_history_u.json")
        file.writeText("{garbage")
        ChatHistoryManager(tempDir, json).loadChatHistory("u")
        assertTrue(SyncHolds.isHeld(file))

        engine().pull()

        assertEquals(remote, file.readText())
        assertFalse(SyncHolds.isHeld(file))
        assertTrue(fake.putBodies.isEmpty(), "adopting the remote copy must not upload")
        assertEquals(1, tempDir.listFiles()!!.count { it.name.startsWith("chat_history_u.json.corrupt-") }, "corrupt bytes kept once")
    }

    @Test
    fun `a held file with no remote copy is released and uploaded`() = runBlocking {
        val file = File(tempDir, "chat_history_u.json")
        file.writeText("{garbage")
        val m = ChatHistoryManager(tempDir, json)
        m.createNewChat("u", "only")
        assertTrue(SyncHolds.isHeld(file))

        engine().pull()

        assertFalse(SyncHolds.isHeld(file))
        val uploaded = fake.awaitPut("chat_history_u.json")
        assertEquals(listOf("only"), json.decodeFromString(UserChatHistory.serializer(), uploaded!!).chat_history.map { it.preview_name })
    }

    @Test
    fun `an unreadable local file without a hold is kept as a corrupt copy before the remote replaces it`() = runBlocking {
        val remote = """[{"id":"1","provider":"openai","key":"sk-remote"}]"""
        fake.seed("api_keys_u.json", remote)
        keysFile().writeText("[{broken")

        engine().pull()

        assertEquals(remote, keysFile().readText())
        val kept = tempDir.listFiles()!!.single { it.name.startsWith("api_keys_u.json.corrupt-") }
        assertEquals("[{broken", kept.readText())
        assertTrue(fake.putBodies.isEmpty())
    }

    // ── Content-based change detection, uploads and failures ─────────────────

    @Test
    fun `a debounced upload is a CAS put on the base version and records the new base`() = runBlocking {
        val e = engine(debounceMs = 50)
        val m = ChatHistoryManager(tempDir, json, onFileWritten = e::onFileWritten)
        m.createNewChat("u", "first")
        val first = fake.awaitPut("chat_history_u.json")
        assertNotNull(first)
        val v1 = fake.blob("chat_history_u.json")!!.updatedAt
        assertEquals(v1, state().baseServerVersion("chat_history_u.json"))

        fake.putBodies.clear()
        m.createNewChat("u", "second")
        assertNotNull(fake.awaitPut("chat_history_u.json"))
        val versions = fake.versions("chat_history_u.json")
        assertEquals(2, versions.size)
        assertTrue(versions[0].updatedAt > v1, "versions strictly increase")
        assertEquals(sha256Hex(File(tempDir, "chat_history_u.json").readText()), state().entry("chat_history_u.json")!!.baseLocalSha)
        assertEquals(File(tempDir, "chat_history_u.json").readText(), File(tempDir, "sync_base/chat_history_u.json").readText(),
            "base snapshot = the uploaded content")
    }

    @Test
    fun `edits made while sync was off are uploaded by the next pull`() = runBlocking {
        var settings = syncApiKeysOn.copy(remoteSync = syncApiKeysOn.remoteSync.copy(enabled = false))
        val e = engine { settings }
        val m = ChatHistoryManager(tempDir, json, onFileWritten = e::onFileWritten)
        m.createNewChat("u", "offline")
        Thread.sleep(900)
        assertTrue(fake.putBodies.isEmpty(), "sync is off")

        settings = syncApiKeysOn
        e.pull()

        assertEquals(listOf("offline"),
            json.decodeFromString(UserChatHistory.serializer(), fake.content("chat_history_u.json")!!).chat_history.map { it.preview_name })
    }

    @Test
    fun `a failed upload keeps the change - the next pull uploads it`() = runBlocking {
        val e = engine(debounceMs = SimDevice.MANUAL)
        val m = ChatHistoryManager(tempDir, json, onFileWritten = e::onFileWritten)
        m.createNewChat("u", "a")
        e.pull()
        m.createNewChat("u", "b")
        fake.failNextPuts.set(1)
        e.flushPendingUploads()
        assertEquals(1, json.decodeFromString(UserChatHistory.serializer(), fake.content("chat_history_u.json")!!).chat_history.size)

        e.pull()

        assertEquals(listOf("a", "b"),
            json.decodeFromString(UserChatHistory.serializer(), fake.content("chat_history_u.json")!!).chat_history.map { it.preview_name })
    }

    @Test
    fun `a lost PUT response is recovered without a duplicate version`() = runBlocking {
        val e = engine(debounceMs = SimDevice.MANUAL)
        val m = ChatHistoryManager(tempDir, json, onFileWritten = e::onFileWritten)
        m.createNewChat("u", "a")
        e.pull()
        m.createNewChat("u", "b")
        fake.loseNextPutResponses.set(1)
        e.flushPendingUploads()  // applied on the server, but the engine saw a 500
        val afterLost = fake.versions("chat_history_u.json").size

        e.pull()

        assertEquals(afterLost, fake.versions("chat_history_u.json").size, "the retry must be a no-op")
        val entry = state().entry("chat_history_u.json")!!
        assertEquals(fake.blob("chat_history_u.json")!!.updatedAt, entry.baseServerVersion)
        assertEquals(sha256Hex(File(tempDir, "chat_history_u.json").readText()), entry.baseLocalSha)
    }

    @Test
    fun `repeated conflicts stop after the attempt limit and the next sync completes`() = runBlocking {
        val e = engine(debounceMs = SimDevice.MANUAL)
        val m = ChatHistoryManager(tempDir, json, onFileWritten = e::onFileWritten)
        m.createNewChat("u", "a")
        e.pull()
        m.createNewChat("u", "b")
        fake.conflictNextPuts.set(100)
        e.pull()  // must terminate
        assertEquals(1, json.decodeFromString(UserChatHistory.serializer(), fake.content("chat_history_u.json")!!).chat_history.size)

        fake.conflictNextPuts.set(0)
        e.pull()
        assertEquals(2, json.decodeFromString(UserChatHistory.serializer(), fake.content("chat_history_u.json")!!).chat_history.size)
    }

    @Test
    fun `app_settings - device-local changes are not uploaded and the stripped upload is not a perpetual change`() = runBlocking {
        File(tempDir, "app_settings.json").writeText(json.encodeToString(syncApiKeysOn))
        val e = engine(debounceMs = SimDevice.MANUAL) { readSettings() }
        e.pull()
        val uploaded = fake.content("app_settings.json")!!
        assertFalse("test-token" in uploaded, "remoteSync must be stripped from the upload")
        val puts = fake.putCount.get()

        // Repeated pulls: nothing to do
        e.pull()
        e.pull()
        assertEquals(puts, fake.putCount.get(), "stripped upload vs local file looked like a change")
        assertEquals(0L, e.changeTick.value)

        // Only device-local keys change → still nothing to upload
        File(tempDir, "app_settings.json").writeText(json.encodeToString(readSettings().copy(
            remoteSync = readSettings().remoteSync.copy(accountEmail = "x@example.com", syncApiKeys = false)
        )))
        e.pull()
        assertEquals(puts, fake.putCount.get())

        // A real change is uploaded
        File(tempDir, "app_settings.json").writeText(json.encodeToString(readSettings().copy(temperature = 0.2)))
        e.pull()
        assertEquals(puts + 1, fake.putCount.get())
        assertEquals(0.2, json.decodeFromString(AppSettings.serializer(), fake.content("app_settings.json")!!).temperature)
        assertEquals("x@example.com", readSettings().remoteSync.accountEmail, "local keys untouched")
    }

    @Test
    fun `changeTick only moves when a local file's content changed`() = runBlocking {
        val e = engine(debounceMs = SimDevice.MANUAL)
        val m = ChatHistoryManager(tempDir, json, onFileWritten = e::onFileWritten)
        m.createNewChat("u", "a")
        e.pull()
        e.pull()
        assertEquals(0L, e.changeTick.value, "uploading local changes is not a local change")
        fake.seed("chat_history_u.json", json.encodeToString(chats("x")))
        e.pull()
        assertEquals(1L, e.changeTick.value)
        e.pull()
        assertEquals(1L, e.changeTick.value)
    }

    @Test
    fun `401 sets needsReauth, stops syncing, and clearReauth resumes`() = runBlocking {
        val token = fake.tokenFor("u")
        var settings = syncApiKeysOn.copy(remoteSync = syncApiKeysOn.remoteSync.copy(authToken = token))
        val e = engine(debounceMs = SimDevice.MANUAL) { settings }
        val m = ChatHistoryManager(tempDir, json, onFileWritten = e::onFileWritten)
        m.createNewChat("u", "a")
        fake.revoke(token)
        e.pull()
        assertTrue(e.needsReauth.value)
        val requests = fake.requests.size
        e.pull()
        assertEquals(requests, fake.requests.size, "no calls while re-auth is needed")

        settings = settings.copy(remoteSync = settings.remoteSync.copy(authToken = fake.tokenFor("u")))
        e.clearReauth()
        e.pull()
        assertFalse(e.needsReauth.value)
        assertNotNull(fake.content("chat_history_u.json"))
    }

    @Test
    fun `a failed sync schedules a retry pull`() = runBlocking {
        val e = engine(debounceMs = SimDevice.MANUAL, retryMs = 200)
        val m = ChatHistoryManager(tempDir, json, onFileWritten = e::onFileWritten)
        m.createNewChat("u", "a")
        fake.failNextManifests.set(1)
        e.pull()
        assertNull(fake.content("chat_history_u.json"))
        assertNotNull(fake.awaitPut("chat_history_u.json", timeoutMs = 3000), "retry pull did not upload")
    }

    // ── Accounts ──────────────────────────────────────────────────────────────

    @Test
    fun `a state of another account is reset with its snapshots`() = runBlocking {
        var settings = syncApiKeysOn
        val e = engine(debounceMs = SimDevice.MANUAL) { settings }
        ChatHistoryManager(tempDir, json).createNewChat("u", "a")
        e.pull()
        assertTrue(File(tempDir, "sync_base/chat_history_u.json").exists())
        assertEquals("u", e.syncStateAccount())

        settings = settings.copy(current_user = "other")
        e.pull()
        assertEquals("other", e.syncStateAccount())
        assertNull(state().entry("chat_history_u.json"), "old account's bases dropped")
        assertFalse(File(tempDir, "sync_base/chat_history_u.json").exists())
    }

    @Test
    fun `a reset drops work that started before it`() {
        val s = SyncState(tempDir, json)
        s.load()
        val gen = s.generation
        s.reset("")
        assertFalse(s.recordBase("f.json", 5, "sha", "lsha", gen))
        assertNull(s.entry("f.json"))
        assertTrue(s.recordBase("f.json", 5, "sha", "lsha", s.generation))
    }

    @Test
    fun `the legacy sync_state format still loads and is upgraded`() = runBlocking {
        File(tempDir, "sync_state.json").writeText(
            """{"files":{"api_keys_u.json":{"baseServerVersion":10000,"dirty":false,"lastLocalWriteAt":5}}}"""
        )
        val old = state()
        assertNull(old.accountUsername())
        assertEquals(10_000L, old.baseServerVersion("api_keys_u.json"))
        assertNull(old.entry("api_keys_u.json")!!.baseLocalSha)

        // Local changed since that version (lost dirty flag), remote unchanged → uploaded
        val remote = """[{"id":"1","provider":"openai","key":"sk-a"}]"""
        fake.seed("api_keys_u.json", remote, updatedAt = 10_000L)
        keysFile().writeText("""[{"id":"1","provider":"openai","key":"sk-a"},{"id":"2","provider":"openai","key":"sk-b"}]""")
        engine().pull()

        assertEquals(setOf("1", "2"), keyIds(fake.content("api_keys_u.json")!!))
        assertEquals("u", state().accountUsername(), "legacy state adopted for the current account, bases kept")
    }

    @Test
    fun `the legacy state with a stale version and no snapshot merges 2-way`() = runBlocking {
        File(tempDir, "sync_state.json").writeText("""{"files":{"api_keys_u.json":{"baseServerVersion":10000}}}""")
        fake.seed("api_keys_u.json", """[{"id":"1","provider":"openai","key":"sk-a"},{"id":"3","provider":"openai","key":"sk-c"}]""", updatedAt = 20_000L)
        keysFile().writeText("""[{"id":"1","provider":"openai","key":"sk-a"},{"id":"2","provider":"openai","key":"sk-b"}]""")
        engine().pull()
        assertEquals(setOf("1", "2", "3"), keyIds(keysFile().readText()))
        assertEquals(setOf("1", "2", "3"), keyIds(fake.content("api_keys_u.json")!!))
    }

    @Test
    fun `one engine per data dir per process`() {
        val a = SyncEngine.forDir(tempDir, json) { syncApiKeysOn }
        val b = SyncEngine.forDir(File(tempDir, "../${tempDir.name}"), json) { syncApiKeysOn }
        try {
            assertSame(a, b)
        } finally {
            a.close()
        }
        val c = SyncEngine.forDir(tempDir, json) { syncApiKeysOn }
        c.close()
        assertTrue(a !== c, "a closed engine is replaced")
    }

    // ── RemoteStorageClient CAS surface ───────────────────────────────────────

    @Test
    fun `client - conditional put, typed conflict, versioned get and history`() = runBlocking {
        val client = RemoteStorageClient(fake.baseUrl, "test-token")
        val m1 = client.put("f.json", "{\"a\":1}", baseVersion = 0)
        val conflict = assertFailsWith<RemoteSyncException.Conflict> { client.put("f.json", "{\"a\":2}", baseVersion = 0) }
        assertEquals(m1.updated_at, conflict.current!!.updated_at)
        assertEquals(m1.sha, conflict.current!!.sha)
        val m2 = client.put("f.json", "{\"a\":2}", baseVersion = m1.updated_at)
        assertTrue(m2.updated_at > m1.updated_at)
        assertFailsWith<RemoteSyncException.Conflict> { client.put("f.json", "{\"a\":3}", baseVersion = m1.updated_at) }
        // sha-equal PUT: no new version, whatever the base
        assertEquals(m2, client.put("f.json", "{\"a\":2}", baseVersion = 1))
        val missing = assertFailsWith<RemoteSyncException.Conflict> { client.put("g.json", "x", baseVersion = 7) }
        assertNull(missing.current)
        // legacy unconditional write
        client.put("f.json", "{\"a\":4}")

        assertEquals("{\"a\":1}", client.get("f.json", version = m1.updated_at)!!.content)
        assertEquals("{\"a\":4}", client.get("f.json")!!.content)
        assertNull(client.get("f.json", version = 1))
        val history = client.history("f.json")
        assertEquals(3, history.size)
        assertTrue(history.first().current)
        assertEquals(listOf(m2.updated_at, m1.updated_at), history.drop(1).map { it.updated_at })
    }
}
