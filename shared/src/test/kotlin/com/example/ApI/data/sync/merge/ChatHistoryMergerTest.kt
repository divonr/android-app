package com.example.ApI.data.sync.merge

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.sync.merge.MergeAssert.assertSameContent
import com.example.ApI.data.sync.merge.MergeAssert.assertValid
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatHistoryMergerTest {

    @TempDir
    lateinit var tmp: File

    private val clock = FakeClock()
    private var deviceCount = 0

    private fun device() = Device(File(tmp, "d${deviceCount++}"), clock)

    /** Base built on one device, then copied to two replicas. */
    private fun replicas(build: Device.() -> Unit): Triple<UserChatHistory, Device, Device> {
        val origin = device().apply(build)
        val base = origin.load()
        val l = device().apply { save(base) }
        val r = device().apply { save(base) }
        return Triple(base, l, r)
    }

    private fun merge(base: UserChatHistory?, l: UserChatHistory, r: UserChatHistory): UserChatHistory =
        ChatHistoryMerger.merge(base, l, r).also { assertValid(it) }

    private fun Device.conversation(chatId: String, vararg turns: String) {
        newChat(chatId)
        for (t in turns) {
            send(chatId, "$t?")
            reply(chatId, "$t!")
        }
    }

    // --- chat set -------------------------------------------------------------------

    @Test
    fun `ten vs eleven chats gives eleven`() {
        val (base, l, r) = replicas { (1..10).forEach { conversation("c$it", "hi$it") } }
        r.conversation("c11", "new")
        val m = merge(base, l.load(), r.load())
        assertEquals((1..11).map { "c$it" }, m.chat_history.map { it.chat_id })
        // also without a base
        val m2 = merge(null, l.load(), r.load())
        assertEquals(11, m2.chat_history.size)
    }

    @Test
    fun `extra messages on one side win`() {
        val (base, l, r) = replicas { conversation("c", "a", "b") }
        r.send("c", "c?"); r.reply("c", "c!")
        val m = merge(base, l.load(), r.load())
        assertSameContent(r.load(), m, "remote extension adopted")
        assertEquals(listOf("a?", "a!", "b?", "b!", "c?", "c!"), m.chat_history.single().messages.map { it.text })
        // No base: still the longer side
        val m2 = merge(null, l.load(), r.load())
        assertEquals(6, m2.chat_history.single().messages.size)
    }

    @Test
    fun `both add different chats gives union`() {
        val (base, l, r) = replicas { conversation("c", "a") }
        l.conversation("L", "x")
        r.conversation("R", "y")
        val m = merge(base, l.load(), r.load())
        assertEquals(listOf("c", "L", "R"), m.chat_history.map { it.chat_id })
    }

    @Test
    fun `deletion on one side with other unchanged is deleted`() {
        val (base, l, r) = replicas { conversation("c1", "a"); conversation("c2", "b") }
        r.deleteChat("c1")
        // a mere branch switch / view change on the local side is not a modification
        val m = merge(base, l.load(), r.load())
        assertEquals(listOf("c2"), m.chat_history.map { it.chat_id })
    }

    @Test
    fun `deletion vs modification keeps the chat`() {
        val (base, l, r) = replicas { conversation("c1", "a"); conversation("c2", "b") }
        r.deleteChat("c1")
        l.send("c1", "more?")
        val m = merge(base, l.load(), r.load())
        assertEquals(setOf("c1", "c2"), m.chat_history.map { it.chat_id }.toSet())
        assertTrue("more?" in MergeAssert.textsOf(m.chat_history.single { it.chat_id == "c1" }))

        // rename also counts as a modification
        val (base2, l2, r2) = replicas { conversation("c1", "a") }
        l2.deleteChat("c1")
        r2.rename("c1", "renamed")
        assertEquals("renamed", merge(base2, l2.load(), r2.load()).chat_history.single().preview_name)
    }

    @Test
    fun `duplicate chat ids in one file are united`() {
        val d = device()
        d.conversation("c", "a")
        val chat = d.chat("c")
        val other = device().apply { save(d.load()) }
        other.send("c", "b?")
        val dupHistory = d.load().copy(chat_history = listOf(chat, other.chat("c")))
        val m = merge(null, dupHistory, UserChatHistory("u", emptyList()))
        assertEquals(1, m.chat_history.size)
        assertTrue("b?" in MergeAssert.textsOf(m.chat_history.single()))
    }

    // --- tree -----------------------------------------------------------------------

    @Test
    fun `both continue same chat from same point gives sibling variants in one node`() {
        val (base, l, r) = replicas { conversation("c", "a") }
        l.send("c", "local?"); l.reply("c", "local!")
        r.send("c", "remote?"); r.reply("c", "remote!")
        val m = merge(base, l.load(), r.load())
        val chat = m.chat_history.single()
        assertEquals(2, chat.messageNodes.size, "the two continuations share one node")
        val second = chat.messageNodes.single { it.parentNodeId != null }
        assertEquals(listOf("local?", "remote?"), second.variants.map { it.userMessage.text })
        // local view preserved
        assertEquals(listOf("a?", "a!", "local?", "local!"), chat.messages.map { it.text })
    }

    @Test
    fun `both reply to the same leaf forks the local replies into a sibling variant`() {
        val (base, l, r) = replicas { newChat("c"); send("c", "q") }
        l.reply("c", "local answer")
        r.reply("c", "remote answer")
        val m = merge(base, l.load(), r.load())
        val chat = m.chat_history.single()
        val node = chat.messageNodes.single()
        assertEquals(2, node.variants.size)
        // The remote (server) content keeps the shared variant id; local's moves to the fork
        assertEquals(l.chat("c").currentVariantPath, listOf(node.variants[0].variantId))
        assertEquals(listOf("remote answer"), node.variants[0].responses.map { it.text })
        assertEquals(listOf("local answer"), node.variants[1].responses.map { it.text })
        assertEquals("q", node.variants[1].userMessage.text)
        // local view follows its own content
        assertEquals(listOf("q", "local answer"), chat.messages.map { it.text })
        // deterministic fork id
        val again = ChatHistoryMerger.merge(base, l.load(), r.load())
        assertEquals(m, again)
    }

    @Test
    fun `edits on both sides become variants of the same node`() {
        val (base, l, r) = replicas { conversation("c", "a", "b") }
        l.edit("c", 1, "b-local")
        r.edit("c", 1, "b-remote")
        val m = merge(base, l.load(), r.load())
        val node = m.chat_history.single().messageNodes[1]
        assertEquals(listOf("b?", "b-local", "b-remote"), node.variants.map { it.userMessage.text })
    }

    @Test
    fun `leaf deletion on one side with the other unchanged is applied`() {
        val (base, l, r) = replicas { conversation("c", "a", "b") }
        r.deleteLast("c")
        val m = merge(base, l.load(), r.load())
        assertEquals(listOf("a?", "a!", "b?"), m.chat_history.single().messages.map { it.text })
        // deleting a whole node
        r.deleteLast("c")
        val m2 = merge(base, l.load(), r.load())
        assertEquals(listOf("a?", "a!"), m2.chat_history.single().messages.map { it.text })
    }

    @Test
    fun `deletion vs extension of the same branch keeps the extension`() {
        val (base, l, r) = replicas { conversation("c", "a", "b") }
        r.deleteLast("c"); r.deleteLast("c")   // remove node b
        l.reply("c", "b!!")                     // extend node b
        val m = merge(base, l.load(), r.load())
        assertEquals(listOf("a?", "a!", "b?", "b!", "b!!"), m.chat_history.single().messages.map { it.text })
    }

    @Test
    fun `two roots are folded into one`() {
        val (base, l, r) = replicas { newChat("c") }
        l.send("c", "L"); r.send("c", "R")
        val m = merge(base, l.load(), r.load())
        val chat = m.chat_history.single()
        assertEquals(1, chat.messageNodes.size)
        assertEquals(setOf("L", "R"), chat.messageNodes.single().variants.map { it.userMessage.text }.toSet())
    }

    @Test
    fun `nested fold keeps descendants of both sides`() {
        val (base, l, r) = replicas { conversation("c", "a") }
        l.send("c", "L1"); l.reply("c", "L1!"); l.send("c", "L2")
        r.send("c", "R1"); r.send("c", "R2"); r.reply("c", "R2!")
        val m = merge(base, l.load(), r.load())
        val texts = MergeAssert.texts(m)
        assertTrue(texts.containsAll(listOf("L1", "L1!", "L2", "R1", "R2", "R2!")))
        assertEquals(listOf("a?", "a!", "L1", "L1!", "L2"), m.chat_history.single().messages.map { it.text })
    }

    @Test
    fun `remote path is used when local lacks the chat and local path is kept otherwise`() {
        val (base, l, r) = replicas { conversation("c", "a", "b") }
        r.edit("c", 0, "a2"); r.reply("c", "a2!")
        val m = merge(base, l.load(), r.load())
        // local view stays on the original branch; the new variant is reachable by arrows
        assertEquals(listOf("a?", "a!", "b?", "b!"), m.chat_history.single().messages.map { it.text })
        assertEquals(2, m.chat_history.single().messageNodes.first().variants.size)
        val fresh = merge(null, UserChatHistory("u", emptyList()), r.load())
        assertEquals(listOf("a2", "a2!"), fresh.chat_history.single().messages.map { it.text })
    }

    @Test
    fun `stale path is repaired and extended to the leaf`() {
        val (base, l, r) = replicas { conversation("c", "a") }
        r.send("c", "b?"); r.reply("c", "b!")
        val local = l.load()
        val broken = local.copy(chat_history = local.chat_history.map { it.copy(currentVariantPath = listOf("nope")) })
        val m = merge(base, broken, r.load())
        assertEquals(listOf("a?", "a!", "b?", "b!"), m.chat_history.single().messages.map { it.text })
    }

    // --- legacy -----------------------------------------------------------------------

    @Test
    fun `legacy vs legacy prefix keeps the longer list without converting`() {
        val (base, l, r) = replicas { newLegacyChat("c", "u1", "a1") }
        r.legacyAppend("c", "user", "u2"); r.legacyAppend("c", "assistant", "a2")
        val m = merge(base, l.load(), r.load())
        val chat = m.chat_history.single()
        assertTrue(chat.messageNodes.isEmpty())
        assertEquals(listOf("u1", "a1", "u2", "a2"), chat.messages.map { it.text })
        assertEquals(chat.messages, merge(null, l.load(), r.load()).chat_history.single().messages)
    }

    @Test
    fun `legacy vs legacy divergence becomes a branching chat with both continuations`() {
        val (base, l, r) = replicas { newLegacyChat("c", "u1", "a1") }
        l.legacyAppend("c", "user", "L")
        r.legacyAppend("c", "user", "R")
        val chat = merge(base, l.load(), r.load()).chat_history.single()
        assertEquals(2, chat.messageNodes.size)
        assertEquals(listOf("L", "R"), chat.messageNodes[1].variants.map { it.userMessage.text })
    }

    @Test
    fun `legacy vs branching merges by deterministic ids`() {
        val (base, l, r) = replicas { newLegacyChat("c", "u1", "a1") }
        r.send("c", "u2"); r.reply("c", "a2")   // migrates deterministically, then extends
        val m = merge(base, l.load(), r.load())
        val chat = m.chat_history.single()
        assertEquals(listOf("u1", "a1", "u2", "a2"), chat.messages.map { it.text })
        assertEquals(2, chat.messageNodes.size)
        // and the other way around, with a legacy-side extension as well
        l.legacyAppend("c", "assistant", "a1b")
        val m2 = merge(base, l.load(), r.load())
        assertTrue(MergeAssert.texts(m2).containsAll(listOf("a1b", "u2", "a2")))
    }

    // --- groups -----------------------------------------------------------------------

    @Test
    fun `groups add delete and rename conflicts`() {
        val (base, l, r) = replicas {
            addGroup("g1", "One"); addGroup("g2", "Two"); addGroup("g3", "Three")
            conversation("c", "a"); setChatGroup("c", "g1")
        }
        l.addGroup("gL", "Local")
        r.addGroup("gR", "Remote")
        r.deleteGroup("g1")            // unchanged on local → deleted, chat leaves it
        l.renameGroup("g2", "Two-L")   // rename vs delete → kept
        r.deleteGroup("g2")
        l.renameGroup("g3", "Three-L") // both renamed → local
        r.renameGroup("g3", "Three-R")
        val m = merge(base, l.load(), r.load())
        assertEquals(listOf("g2", "g3", "gL", "gR"), m.groups.map { it.group_id })
        assertEquals("Two-L", m.groups[0].group_name)
        assertEquals("Three-L", m.groups[1].group_name)
        assertNull(m.chat_history.single().group)
    }

    @Test
    fun `deleted group is cleared from chats assigned on the other side`() {
        val (base, l, r) = replicas { addGroup("g", "G"); conversation("c", "a") }
        l.setChatGroup("c", "g")
        r.deleteGroup("g")
        val m = merge(base, l.load(), r.load())
        assertTrue(m.groups.isEmpty())
        assertNull(m.chat_history.single().group)
    }

    // --- scalars / misc ------------------------------------------------------------------

    @Test
    fun `chat scalar fields merge per field`() {
        val (base, l, r) = replicas { conversation("c", "a") }
        l.rename("c", "local name")
        val rh = r.load()
        val remote = rh.copy(chat_history = rh.chat_history.map { it.copy(systemPrompt = "remote prompt") })
        val chat = merge(base, l.load(), remote).chat_history.single()
        assertEquals("local name", chat.preview_name)
        assertEquals("remote prompt", chat.systemPrompt)
    }

    @Test
    fun `no base two way union keeps everything`() {
        val l = device().apply { conversation("shared", "a"); conversation("L", "x") }
        val r = device().apply { save(l.load()); deleteChat("L"); send("shared", "more?"); conversation("R", "y") }
        val m = merge(null, l.load(), r.load())
        assertEquals(listOf("shared", "L", "R"), m.chat_history.map { it.chat_id })
        assertTrue("more?" in MergeAssert.textsOf(m.chat_history.first()))
    }

    @Test
    fun `user name is local`() {
        val m = merge(null, UserChatHistory("me", emptyList()), UserChatHistory("other", emptyList()))
        assertEquals("me", m.user_name)
    }

    @Test
    fun `identity properties`() {
        val (base, l, r) = replicas { conversation("c", "a", "b"); conversation("d", "x") }
        l.send("c", "L"); l.edit("d", 0, "x2")
        r.deleteLast("c")
        val local = l.load()
        val remote = r.load()
        assertSameContent(local, merge(base, local, base), "merge(B,L,B) == L")
        assertSameContent(remote, merge(base, base, remote), "merge(B,B,R) == R")
        assertSameContent(local, merge(base, local, local), "merge(B,X,X) == X")
        val m = merge(base, local, remote)
        assertEquals(m, ChatHistoryMerger.merge(m, m, m), "merge(M,M,M) == M exactly")
        assertEquals(m, ChatHistoryMerger.merge(base, local, remote), "deterministic")
    }

    @Test
    fun `broken trees are repaired without losing messages`() {
        val d = device().apply { conversation("c", "a", "b") }
        val chat = d.chat("c")
        // orphan the second node and duplicate a variant id into it
        val broken = chat.copy(
            messageNodes = chat.messageNodes.mapIndexed { i, n ->
                if (i == 0) n.copy(variants = n.variants.map { it.copy(childNodeId = "missing") }) else n
            } + chat.messageNodes[0].copy(nodeId = "second-root", parentNodeId = null,
                variants = listOf(chat.messageNodes[0].variants[0].copy(userMessage = Message(role = "user", text = "dup"))))
        )
        val h = UserChatHistory("u", listOf(broken))
        val m = merge(null, h, h)
        assertTrue(MergeAssert.texts(m).containsAll(listOf("a?", "a!", "b?", "b!", "dup")))
    }

    @Test
    fun `legacy converter mirrors MessageBranchingManager migration`() {
        val d = device()
        val legacy = Chat(
            chat_id = "c", preview_name = "c",
            messages = listOf(
                Message(role = "assistant", text = "dropped"),
                Message(role = "user", text = "u1"),
                Message(role = "user", text = "u2"),
                Message(role = "assistant", text = "a2"),
                Message(role = "tool_call", text = "t"),
                Message(role = "tool_response", text = "tr"),
                Message(role = "weird", text = "dropped too"),
                Message(id = "", role = "user", text = "u3"),
                Message(role = "assistant", text = "a3"),
            )
        )
        val expected = d.mbm.migrateChatToBranchingStructure(legacy)
        val actual = LegacyChatConverter.toBranching(legacy)
        assertEquals(shape(expected), shape(actual))
        assertEquals(legacy.messages, actual.messages)
        assertEquals(actual, LegacyChatConverter.toBranching(legacy), "deterministic")
        // blank id got a deterministic id
        assertEquals(LegacyChatConverter.fallbackMessageId("c", 7), actual.messageNodes.last().variants.single().userMessage.id)
    }

    /** Tree shape with node/variant ids replaced by positions (message ids kept unless blank). */
    private fun shape(chat: Chat): Any {
        val nodeIndex = chat.messageNodes.withIndex().associate { (i, n) -> n.nodeId to i }
        val variantIndex = HashMap<String, String>()
        chat.messageNodes.forEachIndexed { i, n -> n.variants.forEachIndexed { j, v -> variantIndex[v.variantId] = "$i.$j" } }
        fun m(msg: Message) = Triple(msg.role, msg.text, nodeIndex[msg.nodeId] to variantIndex[msg.variantId])
        return chat.messageNodes.map { n ->
            nodeIndex[n.parentNodeId] to n.variants.map { v ->
                Triple(m(v.userMessage), v.responses.map(::m), nodeIndex[v.childNodeId])
            }
        } to chat.currentVariantPath.map { variantIndex[it] }
    }

    @Test
    fun `large histories merge`() {
        // one long conversation cloned into 60 chats (building each through MBM is slow)
        val seed = device().apply { conversation("c", *Array(15) { "t$it" }) }.chat("c")
        val (base, l, r) = replicas {
            save(UserChatHistory(user, (1..60).map { seed.copy(chat_id = "c$it", preview_name = "c$it") }))
        }
        (1..60 step 3).forEach { l.send("c$it", "L$it") }
        (1..60 step 4).forEach { r.send("c$it", "R$it") }
        val local = l.load()
        val remote = r.load()
        val started = System.nanoTime()
        val m = merge(base, local, remote)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("large merge: ${elapsedMs}ms")
        assertEquals(60, m.chat_history.size)
        assertTrue(MergeAssert.texts(m).containsAll((1..60 step 3).map { "L$it" } + (1..60 step 4).map { "R$it" }))
    }

    @Test
    fun `group merge without base takes non-default remote values`() {
        val l = UserChatHistory("u", emptyList(), listOf(ChatGroup("g", "G")))
        val r = UserChatHistory("u", emptyList(), listOf(ChatGroup("g", "G", system_prompt = "sp", is_project = true)))
        val g = merge(null, l, r).groups.single()
        assertEquals("sp", g.system_prompt)
        assertTrue(g.is_project)
        assertNotNull(g)
    }
}
