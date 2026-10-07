package com.example.ApI.data.sync.merge

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.sync.merge.MergeAssert.assertValid
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Adversarial review scenarios (T2 review): realistic sync round trips where the device that
 * uploaded first later pulls the other device's merge. Each test is a regression test for a
 * confirmed (and fixed) merge bug.
 */
class ChatHistoryMergeReviewTest {

    @TempDir
    lateinit var tmp: File

    private val clock = FakeClock()
    private var deviceCount = 0

    private fun device() = Device(File(tmp, "d${deviceCount++}"), clock)

    private fun replicas(build: Device.() -> Unit): Triple<UserChatHistory, Device, Device> {
        val origin = device().apply(build)
        val base = origin.load()
        val l = device().apply { save(base) }
        val r = device().apply { save(base) }
        return Triple(base, l, r)
    }

    private fun merge(base: UserChatHistory?, l: UserChatHistory, r: UserChatHistory): UserChatHistory =
        ChatHistoryMerger.merge(base, l, r).also { assertValid(it) }

    /**
     * Sibling variants with identical user text + response texts where one of them is a leaf =
     * duplicated content (the leaf adds nothing). Identical siblings with two different
     * continuations are legitimate branches (e.g. one device deleted the reply that made them
     * differ, then continued).
     */
    private fun duplicateVariants(chat: Chat): List<String> = chat.messageNodes.flatMap { n ->
        n.variants.groupBy { v -> (listOf(v.userMessage.text) + v.responses.map { it.text }).joinToString("|") }
            .filterValues { vs -> vs.size > 1 && vs.any { it.childNodeId == null } }.keys
    }

    @Test
    fun `merge against a stale base (lost PUT response) does not duplicate the remote reply`() {
        val (base, a, b) = replicas { newChat("c"); send("c", "q") }
        a.reply("c", "A answer")
        b.reply("c", "B answer")
        // Device A syncs first and uploads M
        val m = merge(base, a.load(), b.load())
        assertEquals(emptyList(), duplicateVariants(m.chat_history.single()), "first merge")
        a.save(m)
        // Device B pulls M: base = its last synced state, local = its own reply
        val m2 = merge(base, b.load(), m)
        val dups = duplicateVariants(m2.chat_history.single())
        if (dups.isNotEmpty()) fail("second device duplicated sibling variants: $dups; variants=" +
            m2.chat_history.single().messageNodes.single().variants.map { v -> v.responses.map { it.text } })
    }

    @Test
    fun `realistic sync - the device that uploaded first keeps its own reply in view`() {
        val (s0, a, b) = replicas { newChat("c"); send("c", "q") }
        a.reply("c", "A answer")
        b.reply("c", "B answer")
        val s1 = b.load()                     // B uploads first
        val m = merge(s0, a.load(), s1)       // A merges and uploads M
        val adopted = merge(s1, b.load(), m)  // B pulls M (B unchanged since its upload → adopts M)
        // Nobody touched B's variant, yet its content (and B's current view) became A's answer
        assertEquals(listOf("q", "B answer"), adopted.chat_history.single().messages.map { it.text })
    }

    @Test
    fun `fold round trip does not duplicate`() {
        val (base, a, b) = replicas { newChat("c"); send("c", "q"); reply("c", "a") }
        a.send("c", "A next")
        b.send("c", "B next")
        val m = merge(base, a.load(), b.load())
        val m2 = merge(base, b.load(), m)
        assertEquals(emptyList(), duplicateVariants(m2.chat_history.single()))
    }

    @Test
    fun `group kept by rename keeps its chats when the deleting side cleared them`() {
        val (base, l, r) = replicas {
            addGroup("g", "G"); newChat("c"); send("c", "q"); setChatGroup("c", "g")
        }
        l.deleteGroup("g")        // also clears c.group on local (GroupProjectManager behavior)
        r.renameGroup("g", "G2")  // modification beats deletion → group kept
        val m = merge(base, l.load(), r.load())
        assertEquals(listOf("g"), m.groups.map { it.group_id })
        // Plan §3: clearing `group` on chats happens only if the deletion wins
        assertEquals("g", m.chat_history.single().group, "chat detached from a group that survived")
    }

    @Test
    fun `group kept by rename keeps its chats when the remote side deleted it`() {
        val (base, l, r) = replicas {
            addGroup("g", "G"); newChat("c"); send("c", "q"); setChatGroup("c", "g")
        }
        l.renameGroup("g", "G2")
        r.deleteGroup("g")
        val m = merge(base, l.load(), r.load())
        assertEquals(listOf("G2"), m.groups.map { it.group_name })
        assertEquals("g", m.chat_history.single().group)
    }

    @Test
    fun `response deleted locally while the remote continued after it is kept`() {
        val (base, l, r) = replicas { newChat("c"); send("c", "q"); reply("c", "a1") }
        l.deleteLast("c")
        r.send("c", "follow-up to a1")
        val m = merge(base, l.load(), r.load())
        assertEquals(listOf("q", "a1", "follow-up to a1"), m.chat_history.single().messages.map { it.text })
    }

    @Test
    fun `deleted response and continuation lose against a reply made below them`() {
        val (base, l, r) = replicas { newChat("c"); send("c", "q"); reply("c", "a1"); send("c", "f1") }
        l.reply("c", "f1 answer")
        r.deleteLast("c"); r.deleteLast("c")  // f1, then a1
        val m = merge(base, l.load(), r.load())
        val chat = m.chat_history.single()
        assertEquals(listOf("q", "a1", "f1", "f1 answer"), chat.messages.map { it.text })
        assertEquals(listOf("f1" to "a1"), predecessors(chat).filter { it.first == "f1" })
    }

    @Test
    fun `reply appended on one side while the other continued after the old reply forks`() {
        val (base, l, r) = replicas { newChat("c"); send("c", "q"); reply("c", "a1") }
        l.send("c", "follow-up to a1")
        r.reply("c", "a2")
        for ((name, m) in listOf("l-r" to merge(base, l.load(), r.load()), "r-l" to merge(base, r.load(), l.load()))) {
            val chat = m.chat_history.single()
            assertEquals(listOf("follow-up to a1" to "a1"), predecessors(chat).filter { it.first == "follow-up to a1" }, name)
            assertEquals(setOf("q", "a1", "a2", "follow-up to a1"), MergeAssert.textsOf(chat), name)
            assertEquals(emptyList(), duplicateVariants(chat), name)
        }
        // Each device keeps its own view
        assertEquals(listOf("q", "a1", "follow-up to a1"),
            merge(base, l.load(), r.load()).chat_history.single().messages.map { it.text })
        assertEquals(listOf("q", "a1", "a2"),
            merge(base, r.load(), l.load()).chat_history.single().messages.map { it.text })
    }

    @Test
    fun `a third device pulling both merges sees no variant content change`() {
        val (s, a, b) = replicas { newChat("c"); send("c", "q") }
        val c = device().apply { save(s) }
        a.reply("c", "A answer")
        b.reply("c", "B answer")
        c.reply("c", "C answer")
        val s1 = b.load()                    // B uploads first
        val s2 = merge(s, a.load(), s1)      // A merges, uploads
        val s3 = merge(s, c.load(), s2)      // C merges, uploads
        // Every variant id already on the server keeps its content in each later version
        fun contents(h: UserChatHistory) = h.chat_history.single().messageNodes.flatMap { it.variants }
            .associate { it.variantId to it.responses.map { m -> m.text } }
        for ((older, newer) in listOf(s1 to s2, s2 to s3)) {
            val n = contents(newer)
            for ((id, texts) in contents(older)) assertEquals(texts, n[id], "variant $id changed content")
        }
        assertEquals(3, s3.chat_history.single().messageNodes.single().variants.size)
        assertEquals(emptyList(), duplicateVariants(s3.chat_history.single()))
    }

    @Test
    fun `fork origin reduced to the fork's content by a deletion is dropped`() {
        val (s, a, b) = replicas { newChat("c"); send("c", "q"); reply("c", "a1") }
        a.send("c", "f")
        b.reply("c", "a2")
        val s1 = b.load()                     // B uploads first
        val m = merge(s, a.load(), s1)        // A: V=[q|a1,a2], fork=[q|a1]->f
        a.save(m); b.save(m)                  // both in sync with M
        b.switchVariant("c", 0, 0)
        b.deleteLast("c")                     // B views V and deletes a2: V == fork content, leaf
        assertEquals(listOf("q", "a1"), b.chat("c").messages.map { it.text })
        a.reply("c", "f answer")              // A continues in the fork (its view)
        val m2 = merge(m, b.load(), a.load())
        val chat = m2.chat_history.single()
        assertEquals(1, chat.messageNodes.first { it.parentNodeId == null }.variants.size, "redundant leaf twin kept")
        assertEquals(listOf("q", "a1", "f", "f answer"), chat.messages.map { it.text })
    }

    /** Text of the message preceding each message occurrence in the tree (null for the first message). */
    private fun predecessors(chat: Chat): List<Pair<String, String?>> {
        val ownerOf = HashMap<String, com.example.ApI.data.model.MessageVariant>()
        chat.messageNodes.forEach { n -> n.variants.forEach { v -> v.childNodeId?.let { ownerOf[it] = v } } }
        val out = mutableListOf<Pair<String, String?>>()
        for (n in chat.messageNodes) for (v in n.variants) {
            out += v.userMessage.text to ownerOf[n.nodeId]?.allMessages?.last()?.text
            v.responses.forEachIndexed { i, m -> out += m.text to (if (i == 0) v.userMessage.text else v.responses[i - 1].text) }
        }
        return out
    }

    @Test
    fun `realistic sync - continuation sent before pulling the merge stays after its own answer`() {
        val (s, a, b) = replicas { newChat("c"); send("c", "q") }
        a.reply("c", "A answer")
        b.reply("c", "B answer")
        val s1 = b.load()                     // B uploads first (server unchanged since S)
        val m = merge(s, a.load(), s1)        // A pulls S1, merges, uploads M
        a.save(m)
        b.send("c", "B next")                 // B continues before pulling M
        val m2 = merge(s1, b.load(), m)       // B pulls M (B's base = S1, its own upload)
        val pred = predecessors(m2.chat_history.single()).filter { it.first == "B next" }
        assertEquals(listOf<Pair<String, String?>>("B next" to "B answer"), pred,
            "B's follow-up re-parented; B now sees ${m2.chat_history.single().messages.map { it.text }}")
    }

    @Test
    fun `realistic sync - second reply before pulling the merge does not duplicate`() {
        val (s, a, b) = replicas { newChat("c"); send("c", "q") }
        a.reply("c", "A answer")
        b.reply("c", "B answer")
        val s1 = b.load()
        val m = merge(s, a.load(), s1)
        b.reply("c", "B answer 2")
        val m2 = merge(s1, b.load(), m)
        val variants = m2.chat_history.single().messageNodes.single().variants.map { v -> v.responses.map { it.text } }
        assertEquals(2, variants.size, "expected [A answer] and [B answer, B answer 2], got $variants")
    }

    @Test
    fun `response deleted on one side while the other continued after it is kept`() {
        val (base, l, r) = replicas { newChat("c"); send("c", "q"); reply("c", "a1") }
        l.send("c", "follow-up to a1")   // local continues after a1 (new child node)
        r.deleteLast("c")                // remote deletes a1 (a leaf there)
        val m = merge(base, l.load(), r.load())
        val chat = m.chat_history.single()
        // Plan §3.4: deleted only if the other side left it unchanged; local extended after it
        assertEquals(listOf("q", "a1", "follow-up to a1"), chat.messages.map { it.text },
            "follow-up re-parented / a1 dropped")
    }

    @Test
    fun `random two device sync loop keeps every message after its original predecessor`() =
        randomSyncLoop(deviceCount = 2, runs = 300, seedBase = 31_000, ops = 40)

    @Test
    fun `random three device sync loop keeps every message after its original predecessor`() =
        randomSyncLoop(deviceCount = 3, runs = 150, seedBase = 77_000, ops = 60)

    private fun randomSyncLoop(deviceCount: Int, runs: Int, seedBase: Int, ops: Int) {
        val failures = mutableListOf<String>()
        repeat(runs) { iter ->
            val seed = seedBase + iter
            val rnd = kotlin.random.Random(seed)
            val dir = File(tmp, "loop$iter")
            val devices = (0 until deviceCount).map { Device(File(dir, "d$it"), clock) }
            devices[0].newChat("c")
            var server: UserChatHistory? = null
            val bases = arrayOfNulls<UserChatHistory>(deviceCount)
            val expectedPred = HashMap<String, String?>()
            var counter = 0
            var failed = false
            fun check(h: UserChatHistory, what: String) {
                if (failed) return
                val chat = h.chat_history.singleOrNull { it.chat_id == "c" } ?: return
                for ((text, pred) in predecessors(chat)) {
                    if (text in expectedPred && expectedPred[text] != pred) {
                        failed = true
                        failures += "seed=$seed $what: '$text' follows '$pred', expected '${expectedPred[text]}'"
                        return
                    }
                }
                val d = duplicateVariants(chat)
                if (d.isNotEmpty()) { failed = true; failures += "seed=$seed $what: duplicated variants $d" }
            }
            fun sync(i: Int) {
                val local = devices[i].load()
                val srv = server
                val result = if (srv == null || bases[i] == srv) local else merge(bases[i], local, srv)
                devices[i].save(result); server = result; bases[i] = devices[i].load()
                check(result, "after sync of d$i")
            }
            for (i in 0 until deviceCount) sync(i)
            run loop@{ repeat(ops) {
                val i = rnd.nextInt(deviceCount)
                val d = devices[i]
                val chat = d.chat("c")
                when (rnd.nextInt(10)) {
                    0, 1 -> d.send("c", "D$i-${counter++}")
                    2, 3, 4 -> if (chat.messages.isNotEmpty()) d.reply("c", "D$i-${counter++}")
                    5 -> if (chat.messages.isNotEmpty()) d.edit("c", rnd.nextInt(chat.currentVariantPath.size.coerceAtLeast(1)), "D$i-${counter++}")
                    6 -> d.deleteLast("c")
                    else -> sync(i)
                }
                // A local op of the app itself re-parenting a message (pre-existing app behavior,
                // e.g. a reply appended to a non-leaf path end) is not a merge bug: skip the run
                check(d.load(), "after local op on d$i")
                if (failed && failures.last().contains("local op")) { failures.removeAt(failures.size - 1); return@loop }
                // Record the predecessor of each newly created message where it was created
                for ((text, pred) in predecessors(d.chat("c"))) if (text !in expectedPred && text.startsWith("D")) expectedPred[text] = pred
            } }
        }
        if (failures.isNotEmpty()) fail("${failures.size}/$runs sync loops broke threads, e.g.\n${failures.take(5).joinToString("\n")}")
    }
}
