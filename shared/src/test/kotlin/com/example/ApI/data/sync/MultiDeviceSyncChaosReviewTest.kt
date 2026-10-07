package com.example.ApI.data.sync

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.sync.SimAssert.assertConverged
import com.example.ApI.data.sync.SimAssert.quiesce
import com.example.ApI.data.sync.merge.FakeClock
import com.example.ApI.data.sync.merge.MergeAssert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.fail

/**
 * T4 review fuzz: like [MultiDeviceSyncFuzzTest] but with REAL (short) debounced uploads running
 * in the background, operations on two devices at the same time, concurrent pulls, sign-out /
 * sign-in cycles of the same account and restarts while uploads are in flight.
 *
 * SYNC_CHAOS_SEEDS / SYNC_CHAOS_START select the seeds (default 6 seeds).
 */
class MultiDeviceSyncChaosReviewTest {

    @TempDir
    lateinit var root: File

    private class Oracle {
        val created = HashSet<String>()
        val deleted = HashSet<String>()
        /** Texts that disappeared from a device during a non-deleting op (a concurrent merge removed them). */
        val vanished = HashMap<String, String>()
    }

    private class Actor(private val rnd: Random, private val d: SimDevice, private val clock: FakeClock, private val oracle: Oracle) {
        private var counter = 0
        private fun next(): String = "${d.name}-${counter++}"
        private fun created(text: String, result: Any?) {
            if (result != null) synchronized(oracle) { oracle.created += text }
        }

        private fun msg(role: String, text: String) = Message(role = role, text = text, datetime = clock.now(), model = if (role == "user") null else "m")

        private fun randomChat(): Chat? {
            val chats = d.chats().sortedBy { it.preview_name.substringBefore('#') + it.chat_id }
            if (chats.isEmpty()) return null
            return if (rnd.nextInt(10) < 7) chats[rnd.nextInt(minOf(2, chats.size))] else chats[rnd.nextInt(chats.size)]
        }

        /** @return true for an operation that deletes content deliberately. */
        fun step(): Boolean {
            val u = d.user
            val repo = d.repo
            val chat = randomChat()
            val roll = rnd.nextInt(100)
            when {
                chat == null || roll < 10 -> {
                    val id = repo.createNewChat(u, "chat#${d.name}$counter").chat_id
                    val text = next()
                    created(text, repo.addUserMessageAsNewNode(u, id, msg("user", text)))
                }
                roll < 40 -> next().let { created(it, repo.addUserMessageAsNewNode(u, chat.chat_id, msg("user", it))) }
                roll < 65 -> if (chat.messages.isNotEmpty()) {
                    next().let { created(it, repo.addResponseToCurrentVariant(u, chat.chat_id, msg("assistant", it))) }
                }
                roll < 73 -> if (chat.currentVariantPath.isNotEmpty()) {
                    val variantId = chat.currentVariantPath[rnd.nextInt(chat.currentVariantPath.size)]
                    val node = chat.messageNodes.firstOrNull { n -> n.variants.any { it.variantId == variantId } }
                    val original = node?.variants?.first { it.variantId == variantId }?.userMessage
                    if (node != null && original != null) {
                        val text = next()
                        created(text, repo.createBranch(u, chat.chat_id, node.nodeId, original.copy(text = text, datetime = clock.now())))
                    }
                }
                roll < 78 -> if (chat.currentVariantPath.isNotEmpty()) {
                    val variantId = chat.currentVariantPath[rnd.nextInt(chat.currentVariantPath.size)]
                    val node = chat.messageNodes.firstOrNull { n -> n.variants.any { it.variantId == variantId } }
                    if (node != null) repo.switchVariant(u, chat.chat_id, node.nodeId, rnd.nextInt(node.variants.size))
                }
                roll < 84 -> {
                    chat.messages.lastOrNull()?.let { repo.deleteMessageFromBranch(u, chat.chat_id, it.id) }
                    return true
                }
                roll < 86 -> {
                    repo.updateChatHistory(u) { h -> h.copy(chat_history = h.chat_history.filter { it.chat_id != chat.chat_id }) }
                    return true
                }
                roll < 92 -> repo.updateChatHistory(u) { h ->
                    h.copy(chat_history = h.chat_history.map { if (it.chat_id == chat.chat_id) it.copy(preview_name = "chat#${next()}") else it })
                }
                else -> d.updateSettings { it.copy(temperature = rnd.nextInt(20) / 10.0, multiMessageMode = rnd.nextBoolean()) }
            }
            return false
        }
    }

    private fun serverTexts(server: FakeSyncServer): Set<String> =
        server.content("chat_history_acct.json", "acct")?.let {
            MergeAssert.texts(SimDevice.json.decodeFromString(UserChatHistory.serializer(), it))
        } ?: emptySet()

    private suspend fun runSeed(seed: Int) {
        val rnd = Random(seed)
        val clock = FakeClock()
        val server = FakeSyncServer().apply { start() }
        val dir = File(root, "c$seed")
        val devices = (0 until 3).map { SimDevice("d$it", dir, server, clock, debounceMs = 5L + rnd.nextLong(30)) }
        val signedIn = BooleanArray(3) { true }
        val ctx = "chaos seed=$seed"
        val log = ArrayList<String>()
        try {
            val oracle = Oracle()
            val actors = devices.map { Actor(rnd, it, clock, oracle) }
            devices.forEach { it.signIn("acct"); it.pull() }

            fun op(i: Int) {
                val d = devices[i]
                val before = d.texts()
                val deleting = actors[i].step()
                val removed = before - d.texts()
                // (No "a non-deleting op removed texts" check: background merges run concurrently and
                // may legitimately apply another device's deletion in between.)
                synchronized(oracle) {
                    if (deleting) oracle.deleted += removed
                    else removed.forEach { oracle.vanished.putIfAbsent(it, "${d.name} at step ${log.size - 1}") }
                }
            }

            repeat(50 + rnd.nextInt(50)) { step ->
                val i = rnd.nextInt(devices.size)
                val d = devices[i]
                val roll = rnd.nextInt(100)
                log += "step $step ${d.name} roll=$roll"
                when {
                    roll < 45 -> op(i)
                    roll < 55 -> {
                        // Two devices write at the same time (each with its own background uploads)
                        val j = (i + 1 + rnd.nextInt(devices.size - 1)) % devices.size
                        coroutineScope {
                            listOf(async(Dispatchers.IO) { op(i) }, async(Dispatchers.IO) { op(j) }).awaitAll()
                        }
                    }
                    roll < 65 -> coroutineScope {
                        // Concurrent pulls on every device while one device writes
                        server.getDelayMs = rnd.nextLong(1, 15)
                        val pulls = devices.map { dev -> async(Dispatchers.IO) { dev.pull() } }
                        op(i)
                        pulls.awaitAll()
                        server.getDelayMs = 0
                    }
                    roll < 72 -> d.pull()
                    roll < 76 -> delay(rnd.nextLong(5, 60))  // let background uploads run
                    roll < 84 -> when (rnd.nextInt(4)) {
                        0 -> server.failNextPuts.addAndGet(1 + rnd.nextInt(2))
                        1 -> server.conflictNextPuts.addAndGet(1 + rnd.nextInt(3))
                        2 -> server.loseNextPutResponses.addAndGet(1)
                        else -> server.failNextManifests.addAndGet(1)
                    }
                    roll < 90 -> {
                        // Sign out / back in to the same account (2-way merge on return)
                        if (signedIn[i]) { d.signOut(); signedIn[i] = false } else { d.signIn("acct"); d.pull(); signedIn[i] = true }
                    }
                    else -> {
                        // Restart, possibly while a debounced upload is in flight
                        if (rnd.nextBoolean()) delay(rnd.nextLong(1, 20))
                        d.restart()
                    }
                }
            }

            server.failNextPuts.set(0)
            server.conflictNextPuts.set(0)
            server.loseNextPutResponses.set(0)
            server.failNextManifests.set(0)
            for (i in devices.indices) if (!signedIn[i]) { devices[i].signIn("acct"); signedIn[i] = true }
            delay(100)
            quiesce(devices, ctx, maxRounds = 12)
            delay(100)
            quiesce(devices, ctx, maxRounds = 12)
            assertConverged(devices, ctx)
            // A text removed by a merge although no device ever deleted it: lost at least transiently
            val unexplained = oracle.vanished.filterKeys { it !in oracle.deleted }
            if (unexplained.isNotEmpty()) fail("$ctx: removed by a merge without any deletion: $unexplained\n${log.joinToString("\n")}")
            val required = oracle.created - oracle.deleted
            for (d in devices) {
                val missing = required - d.texts()
                if (missing.isNotEmpty()) fail("$ctx: ${d.name} lost $missing (server has ${missing.filter { it in serverTexts(server) }})\n${log.takeLast(15).joinToString("\n")}")
            }
        } finally {
            devices.forEach { it.close() }
            server.stop()
        }
    }

    @Test
    fun `chaos - background uploads, concurrent devices, sign-out cycles and restarts converge without loss`(): Unit = runBlocking {
        val seeds = System.getenv("SYNC_CHAOS_SEEDS")?.toIntOrNull() ?: 6
        val start = System.getenv("SYNC_CHAOS_START")?.toIntOrNull() ?: 40_000
        val failures = ArrayList<String>()
        repeat(seeds) {
            try {
                runSeed(start + it)
            } catch (e: AssertionError) {
                failures += (e.message ?: e.toString()).take(1500)
                if (failures.size >= 5) return@repeat
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} failing seeds:\n" + failures.joinToString("\n\n"))
    }
}
