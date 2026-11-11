package com.example.ApI.data.sync.merge

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.sync.merge.MergeAssert.assertValid
import com.example.ApI.data.sync.merge.MergeAssert.contentView
import com.example.ApI.data.sync.merge.MergeAssert.texts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Seeded randomized property tests: replicas start from a common base, apply independent
 * random operation sequences through the real MessageBranchingManager, then merge.
 */
class ChatHistoryMergeFuzzTest {

    @TempDir
    lateinit var tmp: File

    /** Applies random user operations to one replica; every created text is unique. */
    private class Actor(private val rnd: Random, private val device: Device, private val tag: String) {
        private var counter = 0
        private fun next() = "$tag-${counter++}"

        /** Biased towards a few chats so both replicas often touch the same one. */
        private fun randomChat(): Chat? {
            val chats = device.load().chat_history.sortedBy { it.chat_id }
            if (chats.isEmpty()) return null
            return if (rnd.nextInt(10) < 7) chats[rnd.nextInt(minOf(2, chats.size))] else chats.random(rnd)
        }

        fun step() {
            val roll = rnd.nextInt(100)
            val chat = randomChat()
            when {
                chat == null || roll < 10 -> device.newChat("c$tag${counter++}")
                roll < 13 -> device.newLegacyChat("c$tag${counter++}", next(), next(), next())
                roll < 18 -> device.deleteChat(chat.chat_id)
                roll < 23 -> device.rename(chat.chat_id, next())
                roll < 48 -> {
                    if (!chat.hasBranchingStructure && chat.messages.isNotEmpty() && rnd.nextBoolean()) {
                        device.legacyAppend(chat.chat_id, "user", next())
                    } else {
                        device.send(chat.chat_id, next())
                    }
                }
                roll < 68 -> when {
                    !chat.hasBranchingStructure && chat.messages.isNotEmpty() && rnd.nextBoolean() ->
                        device.legacyAppend(chat.chat_id, "assistant", next())
                    // a reply needs something to answer (otherwise the app falls back to a linear append)
                    chat.messages.isNotEmpty() ->
                        device.reply(chat.chat_id, next(), role = if (rnd.nextInt(5) == 0) "tool_call" else "assistant")
                }
                roll < 76 -> if (chat.messages.isNotEmpty()) {
                    val len = maxOf(1, LegacyChatConverter.toBranching(chat).currentVariantPath.size)
                    device.edit(chat.chat_id, rnd.nextInt(len), next())
                }
                roll < 82 -> if (chat.hasBranchingStructure) {
                    val len = chat.currentVariantPath.size
                    if (len > 0) device.switchVariant(chat.chat_id, rnd.nextInt(len), rnd.nextInt(3))
                }
                roll < 90 -> device.deleteLast(chat.chat_id)
                roll < 93 -> device.addGroup("g$tag${counter++}", next())
                else -> {
                    val groups = device.load().groups
                    val group = groups.randomOrNull(rnd)
                    when {
                        group == null -> device.addGroup("g$tag${counter++}", next())
                        rnd.nextInt(3) == 0 -> device.deleteGroup(group.group_id)
                        rnd.nextBoolean() -> device.renameGroup(group.group_id, next())
                        else -> device.setChatGroup(chat.chat_id, group.group_id)
                    }
                }
            }
        }
    }

    /** Content of a chat as the oracle for "was it modified" (view state and group ignored). */
    private fun chatContent(chat: Chat): Any = Triple(
        listOf(chat.preview_name, chat.systemPrompt, chat.shareLink, chat.shareId),
        chat.hasBranchingStructure || chat.messages.isEmpty(),
        ChatTreeRepair.stripNodes(ChatTreeRepair.repair(chat.chat_id, LegacyChatConverter.toBranching(chat).messageNodes))
    )

    private fun checkMerge(seed: Int, base: UserChatHistory?, local: UserChatHistory, remote: UserChatHistory): UserChatHistory {
        val ctx = "seed=$seed"
        val m = try {
            ChatHistoryMerger.merge(base, local, remote)
        } catch (e: Throwable) {
            throw AssertionError("$ctx: merge threw", e)
        }
        try {
            assertValid(m)
        } catch (e: AssertionError) {
            throw AssertionError("$ctx: invalid merge result: ${e.message}", e)
        }
        // No message added on either side since base is lost
        val baseTexts = base?.let(::texts) ?: emptySet()
        val added = (texts(local) - baseTexts) + (texts(remote) - baseTexts)
        val missing = added - texts(m)
        if (missing.isNotEmpty()) fail("$ctx: added messages lost: $missing")
        // A chat deleted on one side and unchanged on the other is gone
        if (base != null) {
            val mergedIds = m.chat_history.map { it.chat_id }.toSet()
            for (b in base.chat_history) {
                val l = local.chat_history.find { it.chat_id == b.chat_id }
                val r = remote.chat_history.find { it.chat_id == b.chat_id }
                val survivor = l ?: r ?: continue
                if ((l == null) != (r == null) && chatContent(survivor) == chatContent(b)) {
                    assertTrue(b.chat_id !in mergedIds, "$ctx: deleted chat ${b.chat_id} resurrected")
                }
            }
        }
        return m
    }

    @Test
    fun `random two replica merges keep invariants and never lose additions`() {
        val iterations = 250
        var conflictNodes = 0
        repeat(iterations) { iter ->
            val seed = 7_000 + iter
            val rnd = Random(seed)
            val clock = FakeClock()
            val dir = File(tmp, "i$iter")
            val origin = Device(File(dir, "o"), clock)
            val baseActor = Actor(rnd, origin, "b")
            repeat(5 + rnd.nextInt(30)) { baseActor.step() }
            val base = origin.load()

            val l = Device(File(dir, "l"), clock).apply { save(base) }
            val r = Device(File(dir, "r"), clock).apply { save(base) }
            val lActor = Actor(rnd, l, "L")
            val rActor = Actor(rnd, r, "R")
            repeat(rnd.nextInt(16)) { lActor.step() }
            repeat(rnd.nextInt(16)) { rActor.step() }
            val local = l.load()
            val remote = r.load()

            val m = checkMerge(seed, base, local, remote)
            val ctx = "seed=$seed"
            conflictNodes += m.chat_history.sumOf { c ->
                c.messageNodes.count { n ->
                    val t = n.variants.flatMap { v -> v.allMessages.map { it.text } }
                    t.any { it.startsWith("L-") } && t.any { it.startsWith("R-") }
                }
            }

            assertEquals(contentView(local), contentView(checkMerge(seed, base, local, base)), "$ctx: merge(B,L,B) != L")
            assertEquals(contentView(remote), contentView(checkMerge(seed, base, base, remote)), "$ctx: merge(B,B,R) != R")
            assertEquals(contentView(local), contentView(checkMerge(seed, base, local, local)), "$ctx: merge(B,X,X) != X")
            assertEquals(m, ChatHistoryMerger.merge(base, local, remote), "$ctx: not deterministic")
            assertEquals(m, ChatHistoryMerger.merge(m, m, m), "$ctx: merge(M,M,M) != M")
            // Adopting the merge on the other device converges
            assertEquals(contentView(m), contentView(checkMerge(seed, local, local, m)), "$ctx: adoption diverged")

            // Without a base: a union that keeps every message of both sides
            val u = checkMerge(seed, null, local, remote)
            val lost = (texts(local) + texts(remote)) - texts(u)
            if (lost.isNotEmpty()) fail("$ctx: 2-way union lost $lost")
        }
        // The generator must actually produce same-node conflicts (folds/forks)
        assertTrue(conflictNodes > iterations / 10, "too few conflicts exercised: $conflictNodes")
    }

    @Test
    fun `random multi device sync converges`() {
        val iterations = 60
        repeat(iterations) { iter ->
            val seed = 9_000 + iter
            val rnd = Random(seed)
            val clock = FakeClock()
            val dir = File(tmp, "m$iter")
            val devices = (0 until 3).map { Device(File(dir, "d$it"), clock) }
            val actors = devices.mapIndexed { i, d -> Actor(rnd, d, "D$i") }
            val bases = arrayOfNulls<UserChatHistory>(3)
            var server: UserChatHistory? = null

            fun sync(i: Int) {
                val local = devices[i].load()
                val s = server
                val result = when {
                    s == null -> local
                    bases[i] == s -> local
                    else -> checkMerge(seed, bases[i], local, s)
                }
                devices[i].save(result)
                server = result
                bases[i] = devices[i].load()
            }

            repeat(20 + rnd.nextInt(40)) {
                val i = rnd.nextInt(3)
                if (rnd.nextInt(10) < 6) actors[i].step() else sync(i)
            }
            repeat(2) { (0 until 3).forEach(::sync) }

            val expected = contentView(server!!)
            devices.forEachIndexed { i, d ->
                assertEquals(expected, contentView(d.load()), "seed=$seed: device $i did not converge")
            }
        }
    }
}
