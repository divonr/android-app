package com.example.ApI.data.repository

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.MessageNode
import com.example.ApI.data.model.MessageVariant
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.util.AtomicFiles
import com.example.ApI.util.FileLocks
import com.example.ApI.util.JsonConfig
import com.example.ApI.util.SyncHolds
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** Adversarial review tests for T3 storage hardening. */
class StorageHardeningReviewTest {

    @TempDir
    lateinit var dir: File

    private val json = JsonConfig.prettyPrint

    // ── corrupt file preservation ─────────────────────────────────────────────

    @Test
    fun `corrupt file with invalid UTF-8 is preserved byte for byte`() {
        val file = File(dir, "chat_history_u.json")
        // A torn/garbled file: valid JSON prefix followed by bytes that are not UTF-8
        val original = "{\"user_name\":\"u\",\"chat_history\":[".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte(), 0xFE.toByte())
        file.writeBytes(original)
        ChatHistoryManager(dir, json).loadChatHistory("u")
        val copy = dir.listFiles()!!.single { it.name.startsWith("chat_history_u.json.corrupt-") }
        assertTrue(copy.readBytes().contentEquals(original), "preserved copy differs from the original bytes")
    }

    @Test
    fun `a change to an unreadable file is not handed to sync as the new state`() {
        // Corrupt file with 3 chats' worth of data: the next update writes a 1-chat history and
        // calls the sync hook, so the upload (LWW today, a 3-way "deleted" diff after T4) spreads
        // the loss to the account. The preserved .corrupt copy only exists on this device.
        val file = File(dir, "chat_history_u.json")
        val good = json.encodeToString(UserChatHistory("u", List(3) { Chat(chat_id = "c$it", preview_name = "p$it", messages = emptyList()) }))
        file.writeText(good.dropLast(5))
        val hooked = mutableListOf<String>()
        val m = ChatHistoryManager(dir, json) { f -> hooked.add(f.readText()) }
        m.createNewChat("u", "new")
        if (hooked.isNotEmpty()) fail("sync hook fired with a history built from an unreadable file: " +
            json.decodeFromString<UserChatHistory>(hooked.single()).chat_history.map { it.preview_name })
        // The change itself is kept locally, and the file is held for the sync engine
        assertEquals(listOf("new"), m.loadChatHistory("u").chat_history.map { it.preview_name })
        assertTrue(SyncHolds.isHeld(file))

        // Later writes (the file is readable again) stay off the sync hook while the hold lasts
        m.createNewChat("u", "second")
        m.saveChatHistory("u", m.loadChatHistory("u"))
        MessageBranchingManager(m).addUserMessageAsNewNode("u", m.loadChatHistory("u").chat_history.first().chat_id,
            Message(id = "q", role = "user", text = "q"))
        assertTrue(hooked.isEmpty(), "sync hook fired while the file is held")

        // Once the sync engine released the hold, writes sync again
        SyncHolds.release(file)
        m.createNewChat("u", "third")
        assertEquals(1, hooked.size)
    }

    @Test
    fun `a corrupt file detected by a plain load holds sync of snapshot saves`() {
        val file = File(dir, "chat_history_u.json")
        file.writeText("{\"user_name\":\"u\",\"chat_history\":[")
        val hooked = mutableListOf<String>()
        val m = ChatHistoryManager(dir, json) { f -> hooked.add(f.name) }
        // UI-style: load (empty), edit the snapshot, save it back in full
        val snapshot = m.loadChatHistory("u")
        m.saveChatHistory("u", snapshot.copy(chat_history = listOf(Chat(chat_id = "x", preview_name = "x", messages = emptyList()))))
        assertTrue(hooked.isEmpty(), "snapshot save of a history loaded from an unreadable file was synced")
        assertTrue(SyncHolds.isHeld(file))
    }

    @Test
    fun `attachment rewrite does not drop messages saved after the snapshot`() {
        val m = ChatHistoryManager(dir, json)
        val mbm = MessageBranchingManager(m)
        val chatId = m.createNewChat("u", "c").chat_id
        val snapshot = mbm.addUserMessageAsNewNode("u", chatId, Message(id = "q", role = "user", text = "q"))!!.messages
        // While DataRepository.sendMessage uploads files, a reply (or sync) lands in the chat
        mbm.addResponseToCurrentVariant("u", chatId, Message(id = "a", role = "assistant", text = "a"))
        // ...then the upload finishes and rewrites messages from the pre-upload snapshot
        m.updateChatWithNewAttachments("u", chatId, snapshot.map { it.copy(text = it.text) })
        val chat = m.loadChatHistory("u").chat_history.single()
        assertEquals(listOf("q", "a"), chat.messages.map { it.id }, "messages lost by the snapshot rewrite")
    }

    @Test
    fun `attachment rewrite updates the tree as well as messages`() {
        val m = ChatHistoryManager(dir, json)
        val mbm = MessageBranchingManager(m)
        val chatId = m.createNewChat("u", "c").chat_id
        val old = Attachment(local_file_path = "/f", file_name = "f", mime_type = "text/plain", file_OPENAI_id = "old-id")
        val snapshot = mbm.addUserMessageAsNewNode("u", chatId, Message(id = "q", role = "user", text = "q", attachments = listOf(old)))!!.messages
        mbm.addResponseToCurrentVariant("u", chatId, Message(id = "a", role = "assistant", text = "a"))
        val reuploaded = snapshot.map { it.copy(attachments = it.attachments.map { a -> a.copy(file_OPENAI_id = "new-id") }) }
        m.updateChatWithNewAttachments("u", chatId, reuploaded)
        val chat = m.loadChatHistory("u").chat_history.single()
        assertEquals(listOf("new-id"), chat.messages.single { it.id == "q" }.attachments.map { it.file_OPENAI_id })
        assertEquals(listOf("new-id"), chat.messageNodes.single().variants.single().userMessage.attachments.map { it.file_OPENAI_id })
        // The next tree operation rebuilds messages from the tree and keeps the new ids
        val after = mbm.addResponseToCurrentVariant("u", chatId, Message(id = "b", role = "assistant", text = "b"))!!
        assertEquals(listOf("q", "a", "b"), after.messages.map { it.id })
        assertEquals(listOf("new-id"), after.messages.first().attachments.map { it.file_OPENAI_id })
    }

    @Test
    fun `user migration carries the sync hold along with the renamed chat history`() {
        File(dir, "app_settings.json").writeText(json.encodeToString(
            com.example.ApI.data.model.AppSettings(current_user = "default", selected_provider = "openai", selected_model = "m")))
        val old = File(dir, "chat_history_default.json")
        old.writeText("{garbage")
        ChatHistoryManager(dir, json).loadChatHistory("default")
        assertTrue(SyncHolds.isHeld(old))
        UserMigration.migrateToAccount(dir, json, "acct")
        assertTrue(SyncHolds.isHeld(File(dir, "chat_history_acct.json")))
        assertTrue(!SyncHolds.isHeld(old))
    }

    // ── nested updates (reentrancy) ───────────────────────────────────────────

    @Test
    fun `nested update inside a transform fails loudly instead of being lost`() {
        val m = ChatHistoryManager(dir, json)
        m.createNewChat("u", "before")
        // A locked operation calling another locked write on the same file (reentrant lock): the
        // outer result was computed before the inner write, so it would silently overwrite it
        assertFailsWith<IllegalStateException> {
            m.updateChatHistory("u") { outer ->
                m.createNewChat("u", "inner")
                outer.copy(chat_history = outer.chat_history + Chat(chat_id = "outer", preview_name = "outer", messages = emptyList()))
            }
        }
        assertFailsWith<IllegalStateException> {
            m.updateChatHistory("u") { outer -> m.saveChatHistory("u", outer); outer }
        }
        assertEquals(listOf("before"), m.loadChatHistory("u").chat_history.map { it.preview_name })

        // Reads inside a transform, other users' files and an outer FileLocks section are fine
        m.updateChatHistory("u") { outer ->
            m.loadChatHistory("u")
            m.createNewChat("other", "o")
            outer.copy(chat_history = outer.chat_history + Chat(chat_id = "outer", preview_name = "outer", messages = emptyList()))
        }
        FileLocks.withLock(m.chatHistoryFile("u")) { m.createNewChat("u", "locked") }
        assertEquals(listOf("before", "outer", "locked"), m.loadChatHistory("u").chat_history.map { it.preview_name })
        assertEquals(listOf("o"), m.loadChatHistory("other").chat_history.map { it.preview_name })

        // The guard is cleared after a failed transform
        runCatching { m.updateChatHistory("u") { error("boom") } }
        m.createNewChat("u", "after")
        assertEquals("after", m.loadChatHistory("u").chat_history.last().preview_name)
    }

    // ── sync hook vs. lock ────────────────────────────────────────────────────

    @Test
    fun `chat history hook never runs with the file lock held`() {
        val heldDuringHook = AtomicBoolean(false)
        lateinit var m: ChatHistoryManager
        m = ChatHistoryManager(dir, json) { f -> if (FileLocks.lockFor(f).isHeldByCurrentThread) heldDuringHook.set(true) }
        val mbm = MessageBranchingManager(m)
        val gpm = GroupProjectManager(m)
        val chatId = m.createNewChat("u", "c").chat_id
        val c1 = mbm.addUserMessageAsNewNode("u", chatId, Message(id = "q", role = "user", text = "q"))!!
        mbm.addResponseToCurrentVariant("u", chatId, Message(id = "a", role = "assistant", text = "a"))
        mbm.createBranch("u", chatId, c1.messageNodes.single().nodeId, Message(id = "q2", role = "user", text = "q2"))
        mbm.switchVariant("u", chatId, c1.messageNodes.single().nodeId, 0)
        gpm.createNewGroup("u", "g")
        m.saveChatHistory("u", m.loadChatHistory("u"))
        assertTrue(!heldDuringHook.get(), "hook ran while the chat history lock was held")
    }

    // ── two managers over different spellings of the same dir ───────────────

    @Test
    fun `managers over a symlinked path share the lock`() {
        val real = File(dir, "real").apply { mkdirs() }
        val link = File(dir, "link")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        val m1 = ChatHistoryManager(real, json)
        val m2 = ChatHistoryManager(File(link, "../link"), json)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) { t ->
            pool.execute {
                start.await()
                repeat(25) { i -> (if (t % 2 == 0) m1 else m2).createNewChat("u", "c-$t-$i") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(100, m1.loadChatHistory("u").chat_history.size)
    }

    // ── atomic writes vs unlocked readers ─────────────────────────────────────

    @Test
    fun `unlocked readers never see a partial file`() {
        val f = File(dir, "big.json")
        val a = "a".repeat(2_000_000)
        val b = "b".repeat(1_000_000)
        AtomicFiles.write(f, a)
        val stop = AtomicBoolean(false)
        val bad = AtomicBoolean(false)
        val reader = Thread {
            while (!stop.get()) {
                val t = f.readText()
                if (t != a && t != b) bad.set(true)
            }
        }
        reader.start()
        repeat(40) { i -> AtomicFiles.write(f, if (i % 2 == 0) b else a) }
        stop.set(true)
        reader.join()
        assertTrue(!bad.get())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    // ── deterministic migration is structurally the pre-T3 migration ──────────

    /** Verbatim copy of the pre-T3 MessageBranchingManager.migrateChatToBranchingStructure (random ids). */
    private fun oldMigrate(chat: Chat): Chat {
        if (chat.hasBranchingStructure) return chat
        if (chat.messages.isEmpty()) return chat
        val nodes = mutableListOf<MessageNode>()
        val variantPath = mutableListOf<String>()
        var currentNodeId: String? = null
        var currentVariant: MessageVariant? = null
        var pendingResponses = mutableListOf<Message>()
        for (message in chat.messages) {
            when (message.role) {
                "user" -> {
                    if (currentVariant != null && currentNodeId != null) {
                        val newNodeId = UUID.randomUUID().toString()
                        val updatedVariant = currentVariant.copy(responses = pendingResponses.toList(), childNodeId = newNodeId)
                        val node = nodes.find { it.nodeId == currentNodeId }
                        if (node != null) {
                            val nodeIndex = nodes.indexOf(node)
                            nodes[nodeIndex] = node.copy(variants = node.variants.map { if (it.variantId == updatedVariant.variantId) updatedVariant else it })
                        }
                        currentNodeId = newNodeId
                    } else {
                        currentNodeId = UUID.randomUUID().toString()
                    }
                    val variantId = UUID.randomUUID().toString()
                    val userMessageWithRefs = message.copy(
                        id = if (message.id.isBlank()) UUID.randomUUID().toString() else message.id,
                        nodeId = currentNodeId, variantId = variantId
                    )
                    currentVariant = MessageVariant(variantId = variantId, userMessage = userMessageWithRefs, responses = emptyList())
                    variantPath.add(variantId)
                    pendingResponses = mutableListOf()
                    val existingNode = nodes.find { it.nodeId == currentNodeId }
                    if (existingNode != null) {
                        val nodeIndex = nodes.indexOf(existingNode)
                        nodes[nodeIndex] = existingNode.copy(variants = existingNode.variants + currentVariant)
                    } else {
                        val parentNodeId = if (nodes.isEmpty()) null else nodes.lastOrNull()?.nodeId
                        nodes.add(MessageNode(nodeId = currentNodeId, parentNodeId = parentNodeId, variants = listOf(currentVariant)))
                    }
                }
                "assistant", "tool_call", "tool_response", "system" -> {
                    if (currentNodeId != null && currentVariant != null) {
                        pendingResponses.add(message.copy(
                            id = if (message.id.isBlank()) UUID.randomUUID().toString() else message.id,
                            nodeId = currentNodeId, variantId = currentVariant.variantId
                        ))
                    }
                }
            }
        }
        if (currentVariant != null && currentNodeId != null && pendingResponses.isNotEmpty()) {
            val updatedVariant = currentVariant.copy(responses = pendingResponses.toList())
            val node = nodes.find { it.nodeId == currentNodeId }
            if (node != null) {
                val nodeIndex = nodes.indexOf(node)
                nodes[nodeIndex] = node.copy(variants = node.variants.map { if (it.variantId == updatedVariant.variantId) updatedVariant else it })
            }
        }
        return chat.copy(messageNodes = nodes, currentVariantPath = variantPath)
    }

    /** Rename every generated id (node/variant/blank message ids) by order of first appearance. */
    private fun canonical(chat: Chat, sourceIds: Set<String>): Chat {
        val map = LinkedHashMap<String, String>()
        fun c(id: String?): String? = id?.let { if (it in sourceIds) it else map.getOrPut(it) { "#${map.size}" } }
        fun m(msg: Message) = msg.copy(id = c(msg.id)!!, nodeId = c(msg.nodeId), variantId = c(msg.variantId))
        val nodes = chat.messageNodes.map { n ->
            val nodeId = c(n.nodeId)!!
            n.copy(nodeId = nodeId, parentNodeId = c(n.parentNodeId), variants = n.variants.map { v ->
                v.copy(variantId = c(v.variantId)!!, userMessage = m(v.userMessage), responses = v.responses.map(::m), childNodeId = c(v.childNodeId))
            })
        }
        return chat.copy(messageNodes = nodes, currentVariantPath = chat.currentVariantPath.map { c(it)!! })
    }

    @Test
    fun `deterministic migration is isomorphic to the old migration`() {
        val roles = listOf("user", "user", "assistant", "assistant", "tool_call", "tool_response", "system", "weird")
        val mbm = MessageBranchingManager(ChatHistoryManager(dir, json))
        repeat(500) { seed ->
            val rnd = Random(seed)
            val messages = List(rnd.nextInt(0, 12)) { i ->
                val id = when (rnd.nextInt(5)) { 0 -> ""; 1 -> "dup"; else -> "m$i" }
                Message(id = id, role = roles[rnd.nextInt(roles.size)], text = "t$i")
            }
            val chat = Chat(chat_id = "c$seed", preview_name = "p", messages = messages)
            val sourceIds = messages.map { it.id }.filter { it.isNotBlank() }.toSet()
            val expected = canonical(oldMigrate(chat), sourceIds)
            val actual = canonical(mbm.migrateChatToBranchingStructure(chat), sourceIds)
            assertEquals(expected, actual, "seed $seed")
        }
    }
}
