package com.example.ApI.data.repository

import com.example.ApI.data.PlatformStorage
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.GitHubAuth
import com.example.ApI.data.model.GitHubConnection
import com.example.ApI.data.model.GitHubUser
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.MessageVariant
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.sync.merge.ChatHistoryMerger
import com.example.ApI.util.FileLocks
import com.example.ApI.util.JsonConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Shared APIs used by the Android / desktop integration (SYNC_MERGE_PLAN.md §5): locked
 * app_settings transforms, anchored (pinned) streaming replies across a merge fork, empty-chat
 * cleanup, the synced "disconnected" auth state and the process-wide sync engine.
 */
class ClientIntegrationApiTest {

    @TempDir
    lateinit var dir: File

    private val json = JsonConfig.prettyPrint
    private val repos = mutableListOf<DataRepository>()

    @AfterEach
    fun teardown() {
        runBlocking { repos.map { it.syncEngine }.distinct().forEach { it.closeAndJoin() } }
    }

    private fun repo(): DataRepository = DataRepository(object : PlatformStorage {
        override val filesDir: File = dir
        override val downloadsDir: File? = null
    }).also { repos += it }

    private val dataDir get() = File(dir, "llm_data")

    private fun runConcurrently(threads: Int, perThread: Int, op: (thread: Int, i: Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(threads) { t ->
            pool.execute {
                try {
                    start.await()
                    repeat(perThread) { i -> op(t, i) }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS), "timed out")
        pool.shutdown()
        errors.firstOrNull()?.let { throw it }
    }

    // ── Process-wide sync engine ─────────────────────────────────────────────

    @Test
    fun `every repository over one data dir shares the sync engine`() {
        // Android: the UI's repository and StreamingService's repository
        val ui = repo()
        val service = repo()
        assertSame(ui.syncEngine, service.syncEngine)
    }

    // ── app_settings transforms ──────────────────────────────────────────────

    @Test
    fun `updateAppSettings transforms the settings on disk, not a stale copy`() {
        val storage = LocalStorageManager(dataDir.apply { mkdirs() }, json)
        storage.saveAppSettings(AppSettings("u", "openai", "gpt-4o"))
        val stale = storage.loadAppSettings()
        // Another device's change arrives through a pull meanwhile
        storage.saveAppSettings(stale.copy(starredModels = emptyList(), skipWelcomeScreen = true))

        val result = storage.updateAppSettings { it.copy(multiMessageMode = true) }
        assertTrue(result.multiMessageMode)
        assertTrue(result.skipWelcomeScreen, "the pulled change survives")
        assertEquals(result, storage.loadAppSettings())
    }

    @Test
    fun `concurrent settings transforms lose nothing and notify outside the lock`() {
        val file = File(dataDir.apply { mkdirs() }, "app_settings.json")
        var hooks = 0
        val storage = LocalStorageManager(dataDir, json) { f ->
            assertFalse(FileLocks.lockFor(f).isHeldByCurrentThread, "the sync hook runs after the lock is released")
            synchronized(this) { hooks++ }
        }
        storage.saveAppSettings(AppSettings("u", "openai", "gpt-4o"))
        val before = hooks
        runConcurrently(threads = 6, perThread = 20) { t, i ->
            storage.updateAppSettings { it.copy(enabledTools = it.enabledTools + "tool-$t-$i") }
        }
        assertEquals(120, storage.loadAppSettings().enabledTools.toSet().size)
        assertEquals(120, hooks - before)
        // A no-op transform writes nothing
        val stamp = file.readText()
        storage.updateAppSettings { it }
        assertEquals(120, hooks - before)
        assertEquals(stamp, file.readText())
    }

    @Test
    fun `time-based defaults are always written, so every read sees the same value`() {
        // kotlinx omits a property equal to its default, and a default of currentTimeMillis()
        // equals the value set in the same millisecond: the field then vanished from the file and
        // each decode invented a new timestamp (a merge read that as a change → resurrections)
        repeat(2000) {
            val info = com.example.ApI.data.model.GitHubConnectionInfo(username = "u", githubUsername = "g", connectedAt = 1)
            val settings = AppSettings("u", "openai", "gpt-4o", githubConnections = mapOf("u" to info))
            val text = json.encodeToString(AppSettings.serializer(), settings)
            assertTrue("lastUsed" in text, "lastUsed must always be written")
            val auth = GitHubAuth(accessToken = "t", scope = "s")
            assertTrue("createdAt" in json.encodeToString(GitHubAuth.serializer(), auth))
        }
    }

    // ── Anchored streaming replies ───────────────────────────────────────────

    private fun msg(role: String, text: String) = Message(role = role, text = text, datetime = "2026-01-01T00:00:00Z")

    private fun variantOf(history: UserChatHistory, chatId: String, variantId: String): MessageVariant =
        history.chat_history.first { it.chat_id == chatId }.messageNodes.flatMap { it.variants }.first { it.variantId == variantId }

    private fun variantsEndingWith(history: UserChatHistory, chatId: String, messageId: String): List<MessageVariant> =
        history.chat_history.first { it.chat_id == chatId }.messageNodes.flatMap { it.variants }
            .filter { it.responses.lastOrNull()?.id == messageId }

    @Test
    fun `a streamed reply follows this device's content after a merge forked it`() {
        val r = repo()
        val chatId = r.createNewChat("u", "c").chat_id
        val sent = r.addUserMessageAsNewNode("u", chatId, msg("user", "q"))!!
        val anchor = ReplyAnchor.forRequest(sent.messages)
        val variantId = sent.messages.last().variantId!!
        val base = r.loadChatHistory("u")

        // This device's request saves a first response (e.g. text before a tool call)
        val a1 = msg("assistant", "a1 (this device)")
        r.addAnchoredResponse("u", chatId, a1, anchor)
        assertEquals(variantId to a1.id, anchor.current())
        val local = r.loadChatHistory("u")

        // Meanwhile another device answered the same variant differently; the pull merges
        val remote = base.copy(chat_history = base.chat_history.map { c ->
            if (c.chat_id != chatId) c else c.copy(messageNodes = c.messageNodes.map { n ->
                n.copy(variants = n.variants.map { v ->
                    if (v.variantId == variantId) v.copy(responses = listOf(msg("assistant", "b1 (other device)").copy(nodeId = n.nodeId, variantId = v.variantId))) else v
                })
            })
        })
        val merged = ChatHistoryMerger.merge(base, local, remote)
        r.saveChatHistory("u", merged)
        assertEquals(listOf("b1 (other device)"), variantOf(merged, chatId, variantId).responses.map { it.text },
            "the merge keeps the other device's content under the shared variant id")
        val fork = variantsEndingWith(merged, chatId, a1.id).single()

        // The rest of the stream (pinned to the variant + a1) lands after a1, in the fork
        val a2 = msg("assistant", "a2 (this device)")
        r.addAnchoredResponse("u", chatId, a2, anchor)
        val after = r.loadChatHistory("u")
        assertEquals(listOf("a1 (this device)", "a2 (this device)"), variantOf(after, chatId, fork.variantId).responses.map { it.text })
        assertEquals(listOf("b1 (other device)"), variantOf(after, chatId, variantId).responses.map { it.text })
        assertEquals(fork.variantId to a2.id, anchor.current(), "the anchor follows the saved reply")
    }

    @Test
    fun `an anchored reply whose user message got another device's reply stays in its variant`() {
        val r = repo()
        val chatId = r.createNewChat("u", "c").chat_id
        val sent = r.addUserMessageAsNewNode("u", chatId, msg("user", "q"))!!
        val anchor = ReplyAnchor.forRequest(sent.messages)
        val variantId = sent.messages.last().variantId!!
        val nodeId = sent.messages.last().nodeId!!
        // Another device's reply arrived first (a pure extension: no fork) ...
        r.addResponseToCurrentVariant("u", chatId, msg("assistant", "b1"), variantId)
        // ... and an edit made a sibling sharing the user message id (no responses)
        r.createBranch("u", chatId, nodeId, sent.messages.last().copy(text = "q edited"))

        r.addAnchoredResponse("u", chatId, msg("assistant", "a1"), anchor)
        val history = r.loadChatHistory("u")
        assertEquals(listOf("b1", "a1"), variantOf(history, chatId, variantId).responses.map { it.text },
            "siblings are matched by response ids only, never by the shared user message id")
    }

    @Test
    fun `an anchored reply whose variant is gone goes after its expected message`() {
        val r = repo()
        val chatId = r.createNewChat("u", "c").chat_id
        val sent = r.addUserMessageAsNewNode("u", chatId, msg("user", "q"))!!
        val a1 = msg("assistant", "a1")
        val chat = r.addResponseToCurrentVariant("u", chatId, a1)!!
        val variantId = chat.messages.last().variantId!!
        val anchor = ReplyAnchor("no-such-variant", a1.id)
        r.addAnchoredResponse("u", chatId, msg("assistant", "a2"), anchor)
        assertEquals(listOf("a1", "a2"), variantOf(r.loadChatHistory("u"), chatId, variantId).responses.map { it.text })
        assertEquals(variantId, anchor.current().first)
        assertNotNull(sent)
    }

    // ── Empty chat cleanup (sync off) ────────────────────────────────────────

    @Test
    fun `without sync every empty chat is cleaned up`() {
        val r = repo()
        val empty = r.createNewChat("u", "empty").chat_id
        r.updateChatHistory("u") { h ->
            h.copy(chat_history = h.chat_history + com.example.ApI.data.model.Chat(chat_id = "other", preview_name = "x", messages = emptyList()))
        }
        val full = r.createNewChat("u", "full").chat_id
        r.addUserMessageAsNewNode("u", full, msg("user", "q"))
        assertEquals(2, r.cleanupEmptyChats("u"))
        val ids = r.loadChatHistory("u").chat_history.map { it.chat_id }
        assertEquals(listOf(full), ids)
        assertFalse(empty in ids)
    }

    // ── Disconnected auth files ──────────────────────────────────────────────

    private fun gitHubConnection(token: String) = GitHubConnection(
        auth = GitHubAuth(accessToken = token, scope = "repo", createdAt = 1),
        user = GitHubUser("octo", 1, "n", "a", null, "u", "h", null, null, null, null, null, null, 0, 0, 0, 0, "c", "u"),
        connectedAt = 1
    )

    @Test
    fun `disconnecting writes a synced disconnected state that loaders treat as absent`() {
        val written = mutableListOf<String>()
        val storage = LocalStorageManager(dataDir.apply { mkdirs() }, json) { written += it.name }
        storage.saveAppSettings(AppSettings("u", "openai", "gpt-4o"))
        val connections = ExternalConnectionsManager(dataDir, json, storage) { written += it.name }
        connections.saveGitHubConnection("u", gitHubConnection("t"))
        assertTrue(connections.isGitHubConnected("u"))
        assertTrue("u" in storage.loadAppSettings().githubConnections)

        written.clear()
        connections.removeGitHubConnection("u")
        val file = File(dataDir, "github_auth_u.json")
        assertTrue(file.exists(), "the file is kept (deletions don't sync)")
        assertEquals(ExternalConnectionsManager.DISCONNECTED, file.readText())
        assertNull(connections.loadGitHubConnection("u"))
        assertFalse(connections.isGitHubConnected("u"))
        assertNull(connections.getGitHubApiService("u"))
        assertFalse("u" in storage.loadAppSettings().githubConnections)
        assertTrue("github_auth_u.json" in written, "the disconnect goes through the sync hook")

        // Disconnecting again changes nothing; reconnecting works
        written.clear()
        connections.removeGitHubConnection("u")
        assertFalse("github_auth_u.json" in written)
        connections.saveGitHubConnection("u", gitHubConnection("t2"))
        assertEquals("t2", connections.loadGitHubConnection("u")?.auth?.accessToken)

        // Google Workspace: same marker
        File(dataDir, "google_workspace_auth_u.json").writeText(ExternalConnectionsManager.DISCONNECTED)
        assertNull(connections.loadGoogleWorkspaceConnection("u"))
        assertFalse(connections.isGoogleWorkspaceConnected("u"))
    }
}
