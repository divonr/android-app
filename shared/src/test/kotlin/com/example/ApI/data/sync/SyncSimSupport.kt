package com.example.ApI.data.sync

import com.example.ApI.data.PlatformStorage
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.RemoteSyncSettings
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.data.repository.DeleteMessageResult
import com.example.ApI.data.repository.LocalStorageManager
import com.example.ApI.data.sync.merge.FakeClock
import com.example.ApI.data.sync.merge.MergeAssert
import com.example.ApI.data.sync.merge.SyncFileMerger
import com.example.ApI.util.JsonConfig
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * One simulated installation (phone, desktop or the web server's user dir): a temp data dir
 * driven through a real [DataRepository] (so the real ChatHistoryManager / MessageBranchingManager
 * / LocalStorageManager write the files and call the sync hook) and the process engine of that
 * dir, configured for tests: debounced uploads only run when flushed ([MANUAL]) unless a
 * debounce is given, and no automatic retry pulls.
 */
class SimDevice(
    val name: String,
    root: File,
    val server: FakeSyncServer,
    private val clock: FakeClock = FakeClock(),
    private val debounceMs: Long = MANUAL,
    private val retryMs: Long = 0L
) {
    companion object {
        /** "Never" in test time: uploads wait for [flush] / a pull. */
        const val MANUAL = 3_600_000L
        val json = JsonConfig.prettyPrint
    }

    val filesDir = File(root, name)
    val dir = File(filesDir, "llm_data").apply { mkdirs() }
    lateinit var repo: DataRepository
        private set
    val engine: SyncEngine get() = repo.syncEngine

    init {
        // A fresh install: local user "default", sync off, the fake server configured
        if (!File(dir, "app_settings.json").exists()) {
            LocalStorageManager(dir, json).saveAppSettings(
                AppSettings(
                    current_user = "default",
                    selected_provider = "openai",
                    selected_model = "gpt-4o",
                    remoteSync = RemoteSyncSettings(serverBaseUrl = server.baseUrl)
                )
            )
        }
        open()
    }

    private fun open() {
        // Register the test-configured engine first: the repository's forDir then returns it
        SyncEngine.forDir(dir, json, debounceMs, retryMs) { LocalStorageManager(dir, json).loadAppSettings() }
        repo = DataRepository(object : PlatformStorage {
            override val filesDir: File = this@SimDevice.filesDir
            override val downloadsDir: File? = null
        })
    }

    /** Process restart: the engine (pending uploads, in-memory state) is gone; files stay. */
    suspend fun restart() {
        engine.closeAndJoin()
        open()
    }

    suspend fun close() = engine.closeAndJoin()

    // ── Sync ─────────────────────────────────────────────────────────────────

    suspend fun signIn(account: String) {
        repo.signInToSync(GoogleIdentity("google:$account", "$account@example.com")).getOrThrow()
    }

    fun signOut() = repo.signOutOfSync()

    suspend fun pull() = engine.pull()

    /** Run the debounced uploads now. */
    suspend fun flush() = engine.flushPendingUploads()

    suspend fun sync() {
        flush()
        pull()
    }

    // ── State ────────────────────────────────────────────────────────────────

    val user: String get() = repo.loadAppSettings().current_user
    fun settings(): AppSettings = repo.loadAppSettings()
    fun history(): UserChatHistory = repo.loadChatHistory(user)
    fun chats(): List<Chat> = history().chat_history
    fun chat(chatId: String): Chat = chats().first { it.chat_id == chatId }
    fun chatOrNull(chatId: String): Chat? = chats().firstOrNull { it.chat_id == chatId }
    fun chatByName(name: String): Chat = chats().first { it.preview_name == name }
    fun file(name: String) = File(dir, name)
    fun chatFile() = file("chat_history_$user.json")
    fun texts(): Set<String> = MergeAssert.texts(history())

    // ── Operations (all through the real repository) ─────────────────────────

    private fun msg(role: String, text: String) = Message(role = role, text = text, datetime = clock.now(), model = if (role == "user") null else "m")

    fun newChat(name: String): String = repo.createNewChat(user, name).chat_id

    /** A chat with a first exchange. */
    fun newChatWith(name: String, vararg texts: String): String {
        val id = newChat(name)
        texts.forEachIndexed { i, t -> if (i % 2 == 0) send(id, t) else reply(id, t) }
        return id
    }

    fun send(chatId: String, text: String) {
        repo.addUserMessageAsNewNode(user, chatId, msg("user", text)) ?: fail("$name: send to $chatId failed")
    }

    fun reply(chatId: String, text: String, targetVariantId: String? = null) {
        repo.addResponseToCurrentVariant(user, chatId, msg("assistant", text), targetVariantId) ?: fail("$name: reply to $chatId failed")
    }

    /** Edit the user message at [pathIndex] of the current path (a new sibling variant). */
    fun edit(chatId: String, pathIndex: Int, text: String): Boolean {
        val chat = chat(chatId)
        val variantId = chat.currentVariantPath.getOrNull(pathIndex) ?: return false
        val node = chat.messageNodes.firstOrNull { n -> n.variants.any { it.variantId == variantId } } ?: return false
        val original = node.variants.first { it.variantId == variantId }.userMessage
        return repo.createBranch(user, chatId, node.nodeId, original.copy(text = text, datetime = clock.now())) != null
    }

    fun switchVariant(chatId: String, pathIndex: Int, variantIndex: Int): Boolean {
        val chat = chat(chatId)
        val variantId = chat.currentVariantPath.getOrNull(pathIndex) ?: return false
        val node = chat.messageNodes.firstOrNull { n -> n.variants.any { it.variantId == variantId } } ?: return false
        return repo.switchVariant(user, chatId, node.nodeId, variantIndex) != null
    }

    fun deleteLast(chatId: String): Boolean {
        val last = chat(chatId).messages.lastOrNull() ?: return false
        return repo.deleteMessageFromBranch(user, chatId, last.id) is DeleteMessageResult.Success
    }

    fun deleteChat(chatId: String) {
        repo.updateChatHistory(user) { h -> h.copy(chat_history = h.chat_history.filter { it.chat_id != chatId }) }
    }

    fun rename(chatId: String, title: String) {
        repo.updateChatHistory(user) { h ->
            h.copy(chat_history = h.chat_history.map { if (it.chat_id == chatId) it.copy(preview_name = title) else it })
        }
    }

    fun setSystemPrompt(chatId: String, prompt: String) {
        repo.updateChatSystemPrompt(user, chatId, prompt)
    }

    fun newGroup(name: String): String = repo.createNewGroup(user, name).group_id
    fun addToGroup(chatId: String, groupId: String) = repo.addChatToGroup(user, chatId, groupId)
    fun renameGroup(groupId: String, name: String) = repo.renameGroup(user, groupId, name)
    fun deleteGroup(groupId: String) = repo.deleteGroup(user, groupId)

    fun updateSettings(transform: (AppSettings) -> AppSettings) = repo.saveAppSettings(transform(repo.loadAppSettings()))
}

object SimAssert {

    /** Every device holds the same chat history (modulo view state) as the server, and valid trees. */
    fun assertConverged(devices: List<SimDevice>, context: String = "") {
        val user = devices.first().user
        val filename = "chat_history_$user.json"
        val serverCopy = devices.first().server.content(filename, user)
        for (d in devices) {
            assertEquals(user, d.user, "$context: ${d.name} is on another account")
            val local = d.chatFile().takeIf { it.exists() }?.readText()
            if (serverCopy == null) {
                assertTrue(local == null || d.chats().isEmpty(), "$context: ${d.name} has chats the server lacks")
                continue
            }
            local ?: fail("$context: ${d.name} has no chat history")
            MergeAssert.assertValid(d.history())
            assertTrue(
                SyncFileMerger.sameContent(filename, local, serverCopy, SimDevice.json),
                "$context: ${d.name} chat history differs from the server's\n--- ${d.name}: ${MergeAssert.contentView(d.history())}\n--- server: ${
                    MergeAssert.contentView(SimDevice.json.decodeFromString(UserChatHistory.serializer(), serverCopy))
                }"
            )
            val settings = d.file("app_settings.json").readText()
            d.server.content("app_settings.json", user)?.let { remote ->
                assertTrue(
                    SyncFileMerger.sameContent("app_settings.json", settings, remote, SimDevice.json),
                    "$context: ${d.name} app_settings differ from the server's"
                )
            }
        }
    }

    /** Sync every device until a whole round changes nothing anywhere (no PUT, no local write). */
    suspend fun quiesce(devices: List<SimDevice>, context: String = "", maxRounds: Int = 8) {
        val server = devices.first().server
        repeat(maxRounds) {
            val puts = server.putCount.get()
            val ticks = devices.map { it.engine.changeTick.value }
            for (d in devices) d.sync()
            if (server.putCount.get() == puts && devices.map { it.engine.changeTick.value } == ticks) return
        }
        fail("$context: no quiescence after $maxRounds rounds (ping-pong?)")
    }
}
