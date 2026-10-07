package com.example.ApI.data.repository

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.MessageNode
import com.example.ApI.data.model.MessageVariant
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.util.AtomicFiles
import com.example.ApI.util.FileLocks
import com.example.ApI.util.JsonConfig
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Disabled
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
import kotlin.test.assertTrue

/** Adversarial review tests for T3 storage hardening. */
class StorageHardeningReviewTest {

    @TempDir
    lateinit var dir: File

    private val json = JsonConfig.prettyPrint

    // ── corrupt file preservation ─────────────────────────────────────────────

    @Test
    @Disabled("T3 review: preserveCorruptFile re-encodes the decoded text, invalid UTF-8 bytes are replaced")
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
    @Disabled("T3 review: updates to an unreadable chat history are written and synced (T4 must block this)")
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
        assertTrue(hooked.isEmpty(), "sync hook fired with a history built from an unreadable file: " +
            json.decodeFromString<UserChatHistory>(hooked.single()).chat_history.map { it.preview_name })
    }

    @Test
    @Disabled("T3 review: updateChatWithNewAttachments replaces messages with a stale caller snapshot")
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

    // ── nested updates (reentrancy) ───────────────────────────────────────────

    @Test
    @Disabled("T3 review: nested modifyChatHistory on the same file is overwritten by the outer write")
    fun `nested update inside a transform is not lost`() {
        val m = ChatHistoryManager(dir, json)
        m.updateChatHistory("u") { outer ->
            // A locked operation calling another locked operation on the same file (reentrant)
            m.createNewChat("u", "inner")
            outer.copy(chat_history = outer.chat_history + Chat(chat_id = "outer", preview_name = "outer", messages = emptyList()))
        }
        val names = m.loadChatHistory("u").chat_history.map { it.preview_name }
        assertTrue("inner" in names, "inner update lost: $names")
        assertTrue("outer" in names, "outer update lost: $names")
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
