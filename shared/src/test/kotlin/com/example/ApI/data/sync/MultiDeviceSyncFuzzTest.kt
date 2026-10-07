package com.example.ApI.data.sync

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.sync.SimAssert.assertConverged
import com.example.ApI.data.sync.SimAssert.quiesce
import com.example.ApI.data.sync.merge.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Seeded randomized multi-device simulation: 3 installations of one account apply random
 * operations through the real repositories, interleaved with random pulls, debounced-upload
 * flushes, restarts, pulls racing a local write, and injected faults (failed PUTs, lost PUT
 * responses, spurious 409s, failing manifests).  Then everything syncs to quiescence and:
 *   - all devices hold the same content as the server (modulo view state / device-local keys),
 *     with valid trees;
 *   - every message ever created and not deliberately deleted is present on every device.
 */
class MultiDeviceSyncFuzzTest {

    @TempDir
    lateinit var root: File

    private class Oracle {
        val created = HashSet<String>()
        val deleted = HashSet<String>()
    }

    /** Random user operations on one device; every created text is unique. */
    private class Actor(private val rnd: Random, private val d: SimDevice, private val clock: FakeClock, private val oracle: Oracle) {
        private var counter = 0
        var last = ""
        private fun next(): String = "${d.name}-${counter++}"

        /** Count [text] as created only if the operation wrote it ([result] non-null). */
        private fun created(text: String, result: Any?) {
            if (result != null) oracle.created += text
        }

        private fun msg(role: String, text: String) = Message(role = role, text = text, datetime = clock.now(), model = if (role == "user") null else "m")

        /** Biased towards a couple of chats so devices often touch the same one. */
        private fun randomChat(): Chat? {
            val chats = d.chats().sortedBy { it.preview_name.substringBefore('#') + it.chat_id }
            if (chats.isEmpty()) return null
            return if (rnd.nextInt(10) < 7) chats[rnd.nextInt(minOf(2, chats.size))] else chats.random(rnd)
        }

        /** @return true for an operation that deletes content deliberately. */
        fun step(): Boolean {
            val u = d.user
            val repo = d.repo
            val chat = randomChat()
            val roll = rnd.nextInt(100)
            last = "roll=$roll chat=${chat?.chat_id?.take(8)}"
            when {
                chat == null || roll < 8 -> {
                    val id = repo.createNewChat(u, "chat#${d.name}${counter}").chat_id
                    val text = next()
                    created(text, repo.addUserMessageAsNewNode(u, id, msg("user", text)))
                }
                roll < 36 -> next().let { created(it, repo.addUserMessageAsNewNode(u, chat.chat_id, msg("user", it))) }
                roll < 60 -> if (chat.messages.isNotEmpty()) {
                    next().let { created(it, repo.addResponseToCurrentVariant(u, chat.chat_id, msg("assistant", it))) }
                }
                roll < 68 -> if (chat.currentVariantPath.isNotEmpty()) {
                    val variantId = chat.currentVariantPath[rnd.nextInt(chat.currentVariantPath.size)]
                    val node = chat.messageNodes.firstOrNull { n -> n.variants.any { it.variantId == variantId } }
                    val original = node?.variants?.first { it.variantId == variantId }?.userMessage
                    if (node != null && original != null) {
                        val text = next()
                        created(text, repo.createBranch(u, chat.chat_id, node.nodeId, original.copy(text = text, datetime = clock.now())))
                    }
                }
                roll < 74 -> if (chat.currentVariantPath.isNotEmpty()) {
                    val variantId = chat.currentVariantPath[rnd.nextInt(chat.currentVariantPath.size)]
                    val node = chat.messageNodes.firstOrNull { n -> n.variants.any { it.variantId == variantId } }
                    if (node != null) repo.switchVariant(u, chat.chat_id, node.nodeId, rnd.nextInt(node.variants.size))
                }
                roll < 81 -> {
                    chat.messages.lastOrNull()?.let { repo.deleteMessageFromBranch(u, chat.chat_id, it.id) }
                    return true
                }
                roll < 84 -> {
                    repo.updateChatHistory(u) { h -> h.copy(chat_history = h.chat_history.filter { it.chat_id != chat.chat_id }) }
                    return true
                }
                roll < 88 -> repo.updateChatHistory(u) { h ->
                    h.copy(chat_history = h.chat_history.map { if (it.chat_id == chat.chat_id) it.copy(preview_name = "chat#${next()}") else it })
                }
                roll < 90 -> repo.updateChatSystemPrompt(u, chat.chat_id, "prompt ${counter++}")
                roll < 95 -> {
                    val groups = d.history().groups
                    val group = groups.randomOrNull(rnd)
                    when {
                        group == null || rnd.nextInt(4) == 0 -> repo.createNewGroup(u, "group ${counter++}")
                        rnd.nextInt(4) == 0 -> repo.deleteGroup(u, group.group_id)
                        rnd.nextBoolean() -> repo.renameGroup(u, group.group_id, "group ${counter++}")
                        else -> repo.addChatToGroup(u, chat.chat_id, group.group_id)
                    }
                }
                else -> d.updateSettings { it.copy(temperature = rnd.nextInt(20) / 10.0, multiMessageMode = rnd.nextBoolean()) }
            }
            return false
        }
    }

    private suspend fun runSeed(seed: Int) {
        val rnd = Random(seed)
        val clock = FakeClock()
        val server = FakeSyncServer().apply { start() }
        val dir = File(root, "s$seed")
        val devices = (0 until 3).map { SimDevice("d$it", dir, server, clock) }
        try {
            val oracle = Oracle()
            val actors = devices.map { Actor(rnd, it, clock, oracle) }
            devices.forEach { it.signIn("acct"); it.pull() }
            val ctx = "seed=$seed"
            val trace = ArrayList<Pair<String, Set<String>>>()
            fun everywhere(): Set<String> {
                val serverTexts = server.content("chat_history_acct.json", "acct")?.let {
                    com.example.ApI.data.sync.merge.MergeAssert.texts(SimDevice.json.decodeFromString(com.example.ApI.data.model.UserChatHistory.serializer(), it))
                } ?: emptySet()
                return devices.flatMapTo(HashSet(serverTexts)) { it.texts() }
            }

            repeat(40 + rnd.nextInt(50)) { step ->
                val i = rnd.nextInt(devices.size)
                val d = devices[i]
                val roll = rnd.nextInt(100)
                when {
                    roll < 55 -> {
                        val before = d.texts()
                        val deleting = actors[i].step()
                        val removed = before - d.texts()
                        if (deleting) oracle.deleted += removed
                        else if (removed.isNotEmpty()) fail("$ctx step $step: a non-deleting operation on ${d.name} removed $removed")
                    }
                    roll < 70 -> d.pull()
                    roll < 80 -> d.flush()
                    roll < 87 -> when (rnd.nextInt(4)) {
                        0 -> server.failNextPuts.addAndGet(1 + rnd.nextInt(2))
                        1 -> server.conflictNextPuts.addAndGet(1 + rnd.nextInt(3))
                        2 -> server.loseNextPutResponses.addAndGet(1)
                        else -> server.failNextManifests.addAndGet(1)
                    }
                    roll < 95 -> {
                        // A pull racing a local write on the same device
                        server.getDelayMs = rnd.nextLong(5, 25)
                        coroutineScope {
                            val pull = async(Dispatchers.IO) { d.pull() }
                            val before = d.texts()
                            val deleting = actors[i].step()
                            if (deleting) oracle.deleted += before - d.texts()
                            pull.await()
                        }
                        server.getDelayMs = 0
                    }
                    else -> d.restart()
                }
                trace += "step $step ${d.name} roll=$roll ${actors[i].last}" to everywhere()
            }

            server.failNextPuts.set(0)
            server.conflictNextPuts.set(0)
            server.loseNextPutResponses.set(0)
            server.failNextManifests.set(0)
            quiesce(devices, ctx)
            assertConverged(devices, ctx)
            val required = oracle.created - oracle.deleted
            for (d in devices) {
                val missing = required - d.texts()
                if (missing.isNotEmpty()) {
                    val report = missing.joinToString("\n") { t ->
                        val idx = trace.indexOfFirst { t in it.second }
                        val gone = if (idx < 0) null else (idx until trace.size).firstOrNull { t !in trace[it].second }
                        "$t: present from ${trace.getOrNull(idx)?.first}, gone at ${gone?.let { trace[it].first }}\n  " +
                            (gone?.let { g -> trace.subList(maxOf(0, g - 6), g + 1).joinToString("\n  ") { it.first } } ?: "")
                    }
                    fail("$ctx: ${d.name} lost $missing\n$report")
                }
            }
            assertTrue(oracle.created.isNotEmpty())
        } finally {
            devices.forEach { it.close() }
            server.stop()
        }
    }

    /** Deeper sweeps: SYNC_FUZZ_SEEDS=500 [SYNC_FUZZ_START=n] ./gradlew :shared:test --tests '*MultiDeviceSyncFuzzTest' */
    @Test
    fun `random multi-device ops, syncs and faults converge without losing messages`() = runBlocking {
        val seeds = System.getenv("SYNC_FUZZ_SEEDS")?.toIntOrNull() ?: 20
        val start = System.getenv("SYNC_FUZZ_START")?.toIntOrNull() ?: 20_000
        repeat(seeds) { runSeed(start + it) }
    }
}
