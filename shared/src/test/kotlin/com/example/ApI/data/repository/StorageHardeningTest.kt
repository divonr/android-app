package com.example.ApI.data.repository

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.sync.merge.LegacyChatConverter
import com.example.ApI.util.JsonConfig
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageHardeningTest {

    @TempDir
    lateinit var dir: File

    private val json = JsonConfig.prettyPrint
    private val written = mutableListOf<String>()

    private fun manager(d: File = dir) = ChatHistoryManager(d, json) { f -> synchronized(written) { written.add(f.name) } }

    private fun writeRaw(username: String, history: UserChatHistory) =
        File(dir, "chat_history_$username.json").writeText(json.encodeToString(history))

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
        assertTrue(done.await(120, TimeUnit.SECONDS), "timed out")
        pool.shutdown()
        errors.firstOrNull()?.let { throw it }
    }

    // ── updateChatHistory / lost updates ─────────────────────────────────────

    @Test
    fun `concurrent updates from many threads lose nothing`() {
        // Two manager instances over the same dir (like Android's UI + StreamingService repositories)
        val m1 = manager()
        val m2 = manager()
        val mbm1 = MessageBranchingManager(m1)
        val mbm2 = MessageBranchingManager(m2)
        val gpm = GroupProjectManager(m2)
        m1.createNewChat("u", "stream")
        val streamChatId = m1.loadChatHistory("u").chat_history.single().chat_id
        mbm1.addUserMessageAsNewNode("u", streamChatId, Message(role = "user", text = "q"))

        val threads = 8
        val perThread = 15
        runConcurrently(threads, perThread) { t, i ->
            when (t % 4) {
                0 -> m1.createNewChat("u", "chat-$t-$i")
                1 -> m2.updateChatHistory("u") { h ->
                    h.copy(chat_history = h.chat_history + Chat(chat_id = "x-$t-$i", preview_name = "x", messages = emptyList()))
                }
                2 -> (if (i % 2 == 0) mbm1 else mbm2).addResponseToCurrentVariant(
                    "u", streamChatId, Message(id = "r-$t-$i", role = "assistant", text = "a$t.$i")
                )
                else -> gpm.createNewGroup("u", "g-$t-$i")
            }
        }

        val history = manager().loadChatHistory("u")
        val names = history.chat_history.map { it.preview_name }.toSet()
        for (t in 0 until threads) for (i in 0 until perThread) {
            when (t % 4) {
                0 -> assertTrue("chat-$t-$i" in names, "lost chat-$t-$i")
                1 -> assertTrue(history.chat_history.any { it.chat_id == "x-$t-$i" }, "lost x-$t-$i")
                3 -> assertTrue(history.groups.any { it.group_name == "g-$t-$i" }, "lost group g-$t-$i")
            }
        }
        val responses = history.chat_history.single { it.chat_id == streamChatId }
            .messageNodes.single().variants.single().responses.map { it.id }.toSet()
        val expected = (0 until threads).filter { it % 4 == 2 }.flatMap { t -> (0 until perThread).map { "r-$t-$it" } }.toSet()
        assertEquals(expected, responses)
        assertEquals(1 + 2 * 2 * perThread, history.chat_history.size)
    }

    @Test
    fun `an unchanged transform writes nothing`() {
        val m = manager()
        m.createNewChat("u", "a")
        val before = File(dir, "chat_history_u.json").readText()
        written.clear()
        m.updateChatHistory("u") { it }
        GroupProjectManager(m).addChatToGroup("u", "missing", "no-such-group")
        assertTrue(written.isEmpty())
        assertEquals(before, File(dir, "chat_history_u.json").readText())
    }

    // ── user_name normalization (B1) ──────────────────────────────────────────

    @Test
    fun `load returns the file's username and saves never route by stale content`() {
        // A file renamed by sign-in migration still says "default" inside
        writeRaw("alice", UserChatHistory("default", listOf(Chat(chat_id = "c1", preview_name = "old", messages = emptyList())), emptyList()))
        val m = manager()

        val loaded = m.loadChatHistory("alice")
        assertEquals("alice", loaded.user_name)

        m.createNewChat("alice", "new")
        m.saveChatHistory(m.loadChatHistory("alice"))
        GroupProjectManager(m).createNewGroup("alice", "g")

        assertFalse(File(dir, "chat_history_default.json").exists())
        val raw = json.decodeFromString<UserChatHistory>(File(dir, "chat_history_alice.json").readText())
        assertEquals("alice", raw.user_name)
        assertEquals(listOf("old", "new"), raw.chat_history.map { it.preview_name })
        assertEquals(listOf("g"), raw.groups.map { it.group_name })

        // Explicit username wins over the content
        m.saveChatHistory("bob", loaded)
        assertEquals("bob", json.decodeFromString<UserChatHistory>(File(dir, "chat_history_bob.json").readText()).user_name)
    }

    // ── corrupt files ─────────────────────────────────────────────────────────

    @Test
    fun `unreadable file is preserved before it can be overwritten`() {
        val file = File(dir, "chat_history_u.json")
        val garbage = "{\"user_name\":\"u\",\"chat_history\":[{\"chat_id\":"
        file.writeText(garbage)
        val m = manager()

        assertEquals(UserChatHistory("u", emptyList(), emptyList()), m.loadChatHistory("u"))
        m.loadChatHistory("u") // same content: no second copy
        val copies = dir.listFiles()!!.filter { it.name.startsWith("chat_history_u.json.corrupt-") }
        assertEquals(1, copies.size)
        assertEquals(garbage, copies.single().readText())

        // An unchanged transform leaves the corrupt original in place
        m.updateChatHistory("u") { it }
        assertEquals(garbage, file.readText())

        // A real change replaces it, the preserved copy stays
        m.createNewChat("u", "fresh")
        assertEquals(listOf("fresh"), m.loadChatHistory("u").chat_history.map { it.preview_name })
        assertEquals(garbage, copies.single().readText())
    }

    @Test
    fun `missing and empty files are empty histories without corrupt copies`() {
        val m = manager()
        assertEquals(UserChatHistory("u", emptyList(), emptyList()), m.loadChatHistory("u"))
        File(dir, "chat_history_u.json").writeText("")
        assertEquals(UserChatHistory("u", emptyList(), emptyList()), m.loadChatHistory("u"))
        assertTrue(dir.listFiles()!!.none { it.name.contains(".corrupt-") })
    }

    // ── deterministic migration ────────────────────────────────────────────────

    private fun legacyChat() = Chat(
        chat_id = "legacy",
        preview_name = "legacy",
        messages = listOf(
            Message(id = "", role = "user", text = "hi", datetime = "2024-01-01T00:00:00Z"),
            Message(id = "", role = "assistant", text = "hello", datetime = "2024-01-01T00:00:01Z"),
            Message(id = "m3", role = "user", text = "more", datetime = "2024-01-01T00:00:02Z"),
            Message(id = "m4", role = "tool_call", text = "t", datetime = "2024-01-01T00:00:03Z"),
            Message(id = "m5", role = "assistant", text = "done", datetime = "2024-01-01T00:00:04Z"),
        )
    )

    @Test
    fun `migration is identical across two runs and two devices`() {
        val dirA = File(dir, "a").apply { mkdirs() }
        val dirB = File(dir, "b").apply { mkdirs() }
        val chat = legacyChat()
        val results = listOf(dirA, dirB).map { d ->
            val m = manager(d)
            m.saveChatHistory("u", UserChatHistory("u", listOf(chat), emptyList()))
            MessageBranchingManager(m).ensureBranchingStructure("u", "legacy")!!
        }
        assertEquals(results[0], results[1])
        assertEquals(LegacyChatConverter.toBranching(chat), results[0])
        assertEquals(results[0], MessageBranchingManager(manager()).migrateChatToBranchingStructure(chat))
        // Persisted, and the blank ids were filled deterministically
        val stored = manager(dirA).loadChatHistory("u").chat_history.single()
        assertEquals(results[0], stored)
        assertEquals(2, stored.messageNodes.size)
        assertEquals(LegacyChatConverter.fallbackMessageId("legacy", 0), stored.messageNodes[0].variants.single().userMessage.id)
    }

    // ── pinned responses ─────────────────────────────────────────────────────

    @Test
    fun `pinned response lands on its variant after the current path moved`() {
        val m = manager()
        val mbm = MessageBranchingManager(m)
        val chatId = m.createNewChat("u", "c").chat_id
        val first = mbm.addUserMessageAsNewNode("u", chatId, Message(id = "q", role = "user", text = "question"))!!
        val nodeId = first.messageNodes.single().nodeId
        val pinned = first.currentVariantPath.single()

        // While the reply streams, the user edits the message: path switches to a new variant
        val (_, newVariant) = mbm.createBranch("u", chatId, nodeId, Message(id = "q", role = "user", text = "edited"))!!
        assertEquals(listOf(newVariant), m.loadChatHistory("u").chat_history.single().currentVariantPath)

        val saved = mbm.addResponseToCurrentVariant("u", chatId, Message(id = "a1", role = "assistant", text = "answer"), targetVariantId = pinned)!!
        val node = saved.messageNodes.single()
        assertEquals(listOf("a1"), node.getVariantById(pinned)!!.responses.map { it.id })
        assertEquals(pinned, node.getVariantById(pinned)!!.responses.single().variantId)
        assertTrue(node.getVariantById(newVariant)!!.responses.isEmpty())
        assertEquals(listOf(newVariant), saved.currentVariantPath)
        assertEquals(listOf("edited"), saved.messages.map { it.text }) // view untouched
        assertEquals(saved, m.loadChatHistory("u").chat_history.single())

        // Unknown target: falls back to the current path's last variant
        val fallback = mbm.addResponseToCurrentVariant("u", chatId, Message(id = "a2", role = "assistant", text = "x"), targetVariantId = "gone")!!
        assertEquals(listOf("a2"), fallback.messageNodes.single().getVariantById(newVariant)!!.responses.map { it.id })

        // No target: current behavior
        val plain = mbm.addResponseToCurrentVariant("u", chatId, Message(id = "a3", role = "assistant", text = "y"))!!
        assertEquals(listOf("a2", "a3"), plain.messageNodes.single().getVariantById(newVariant)!!.responses.map { it.id })
        assertNull(mbm.addResponseToCurrentVariant("u", "no-such-chat", Message(role = "assistant", text = "z"), pinned))
    }

    @Test
    fun `pinned response reaches a variant deeper in the tree`() {
        val m = manager()
        val mbm = MessageBranchingManager(m)
        val chatId = m.createNewChat("u", "c").chat_id
        mbm.addUserMessageAsNewNode("u", chatId, Message(id = "q1", role = "user", text = "1"))
        val second = mbm.addUserMessageAsNewNode("u", chatId, Message(id = "q2", role = "user", text = "2"))!!
        val pinned = second.currentVariantPath.last()
        val rootNode = second.messageNodes.first { it.parentNodeId == null }
        // Switch to a sibling of the root: the pinned variant is no longer on the path
        mbm.createBranch("u", chatId, rootNode.nodeId, Message(id = "q1", role = "user", text = "1b"))

        val saved = mbm.addResponseToCurrentVariant("u", chatId, Message(id = "a", role = "assistant", text = "a"), pinned)!!
        assertFalse(pinned in saved.currentVariantPath)
        val holder = saved.messageNodes.single { n -> n.variants.any { it.variantId == pinned } }
        assertEquals(listOf("a"), holder.getVariantById(pinned)!!.responses.map { it.id })
        assertEquals(holder.nodeId, holder.getVariantById(pinned)!!.responses.single().nodeId)
    }

    // ── import ────────────────────────────────────────────────────────────────

    @Test
    fun `importing a chat whose id exists assigns a fresh id`() {
        val m = manager()
        val chat = Chat(chat_id = "dup", preview_name = "imported", messages = listOf(Message(role = "user", text = "x")), shareLink = "l", shareId = "s")
        val text = json.encodeToString(chat)
        assertEquals("dup", m.importSingleChat(text, "u"))
        val secondId = m.importSingleChat(text, "u")
        assertNotNull(secondId)
        assertNotEquals("dup", secondId)
        val ids = m.loadChatHistory("u").chat_history.map { it.chat_id }
        assertEquals(listOf("dup", secondId), ids)
        val copy = m.loadChatHistory("u").chat_history.last()
        assertEquals("", copy.shareLink)
        assertEquals("imported", copy.preview_name)
    }

    // ── group operations keep working through the lock ─────────────────────

    @Test
    fun `group operations are applied`() {
        val m = manager()
        val gpm = GroupProjectManager(m)
        val chatId = m.createNewChat("u", "c").chat_id
        val group = gpm.createNewGroup("u", "g")
        assertTrue(gpm.addChatToGroup("u", chatId, group.group_id))
        assertFalse(gpm.addChatToGroup("u", chatId, "missing"))
        gpm.renameGroup("u", group.group_id, "g2")
        gpm.updateGroupProjectStatus("u", group.group_id, true)
        var h = m.loadChatHistory("u")
        assertEquals(ChatGroup(group_id = group.group_id, group_name = "g2", is_project = true), h.groups.single())
        assertEquals(group.group_id, h.chat_history.single().group)
        gpm.deleteGroup("u", group.group_id)
        h = m.loadChatHistory("u")
        assertTrue(h.groups.isEmpty())
        assertNull(h.chat_history.single().group)
    }
}
