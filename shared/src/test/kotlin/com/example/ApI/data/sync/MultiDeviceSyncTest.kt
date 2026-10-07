package com.example.ApI.data.sync

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.MessageNode
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.sync.SimAssert.assertConverged
import com.example.ApI.data.sync.SimAssert.quiesce
import com.example.ApI.data.sync.merge.FakeClock
import com.example.ApI.data.sync.merge.MergeAssert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Multi-device simulations (SYNC_MERGE_PLAN.md §0 and scenarios S1–S9): 2–3 installations on
 * separate temp dirs, each with its own real repository + sync engine, sharing one CAS fake
 * server.  "web" plays the Ktor server's user dir (just another device), "app"/"desktop" the
 * clients.
 */
class MultiDeviceSyncTest {

    @TempDir
    lateinit var root: File

    private lateinit var server: FakeSyncServer
    private val clock = FakeClock()
    private val devices = mutableListOf<SimDevice>()

    @BeforeEach
    fun setup() {
        server = FakeSyncServer().apply { start() }
    }

    @AfterEach
    fun teardown() {
        runBlocking { devices.forEach { it.close() } }
        server.stop()
    }

    private fun device(name: String, debounceMs: Long = SimDevice.MANUAL) =
        SimDevice(name, root, server, clock, debounceMs).also { devices += it }

    /** app + web signed into "acct" and synced. */
    private suspend fun pair(): Pair<SimDevice, SimDevice> {
        val web = device("web").apply { signIn("acct"); pull() }
        val app = device("app").apply { signIn("acct"); pull() }
        return app to web
    }

    private fun serverHistory(user: String = "acct"): UserChatHistory =
        SimDevice.json.decodeFromString(UserChatHistory.serializer(), server.content("chat_history_$user.json", user)!!)

    private fun serverSettings(user: String = "acct"): AppSettings =
        SimDevice.json.decodeFromString(AppSettings.serializer(), server.content("app_settings.json", user)!!)

    /** The node holding a variant whose user message is [text]. */
    private fun nodeOf(chat: Chat, text: String): MessageNode =
        chat.messageNodes.first { n -> n.variants.any { it.userMessage.text == text } }

    private fun assertHas(d: SimDevice, vararg texts: String) {
        val missing = texts.toSet() - d.texts()
        assertTrue(missing.isEmpty(), "${d.name} lost $missing")
    }

    // ── S1 / §0.1: web-first account, then the app signs in with local chats ──

    @Test
    fun `S1 web-first account then app sign-in with local default chats - union on both sides`(): Unit = runBlocking {
        val web = device("web")
        web.signIn("acct")
        web.pull()
        web.newChatWith("web chat", "w-q1", "w-a1")
        web.updateSettings { it.copy(temperature = 0.3, selected_model = "web-model") }
        web.sync()
        val webToken = web.settings().remoteSync.authToken

        val app = device("app")
        assertEquals("default", app.user)
        app.newChatWith("app chat 1", "a-q1", "a-a1")
        app.newChatWith("app chat 2", "a-q2")
        app.updateSettings { it.copy(temperature = 0.9, starredModels = emptyList()) }

        app.signIn("acct")
        app.pull()
        quiesce(listOf(app, web), "S1")

        assertEquals("acct", app.user)
        assertFalse(app.file("chat_history_default.json").exists(), "default chats were moved into the account")
        for (d in listOf(app, web)) {
            assertHas(d, "w-q1", "w-a1", "a-q1", "a-a1", "a-q2")
            assertEquals(setOf("web chat", "app chat 1", "app chat 2"), d.chats().map { it.preview_name }.toSet())
        }
        assertEquals("acct", SimDevice.json.decodeFromString(UserChatHistory.serializer(), app.chatFile().readText()).user_name)
        // The account's settings win over the freshly signed-in device's; device-local keys stay
        assertEquals(0.3, app.settings().temperature)
        assertEquals("web-model", app.settings().selected_model)
        assertTrue(app.settings().remoteSync.enabled)
        assertEquals(webToken, web.settings().remoteSync.authToken, "web's credentials untouched")
        assertTrue(app.settings().remoteSync.authToken != webToken)
        assertFalse(server.content("app_settings.json", "acct")!!.contains("authToken"), "credentials never uploaded")
        assertConverged(listOf(app, web), "S1")
    }

    @Test
    fun `S1b app chats reach a web account that has none yet`(): Unit = runBlocking {
        val web = device("web").apply { signIn("acct"); pull() }
        val app = device("app")
        app.newChatWith("app chat", "a-q1", "a-a1")
        app.signIn("acct")
        app.pull()
        web.pull()
        assertHas(web, "a-q1", "a-a1")
        assertConverged(listOf(app, web), "S1b")
    }

    // ── S2 / §0.2: one side extends the other ─────────────────────────────────

    @Test
    fun `S2 one-sided extension wins in both directions`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = web.newChatWith("c", "q1", "a1")
        web.sync()
        app.sync()
        assertHas(app, "q1", "a1")

        web.send(c, "q2")
        web.reply(c, "a2")
        web.sync()
        val puts = server.putCount.get()
        app.sync()
        assertHas(app, "q2", "a2")
        assertEquals(puts, server.putCount.get(), "the extended side's copy is adopted, nothing uploaded")
        assertEquals(listOf("q1", "a1", "q2", "a2"), app.chat(c).messages.map { it.text }, "the extension is on the visible path")

        app.send(c, "q3")
        app.reply(c, "a3")
        app.sync()
        web.sync()
        assertEquals(listOf("q1", "a1", "q2", "a2", "q3", "a3"), web.chat(c).messages.map { it.text })
        assertEquals(3, web.chat(c).messageNodes.size, "a linear chat stays linear")
        assertConverged(listOf(app, web), "S2")
    }

    // ── S3: different chats added concurrently ────────────────────────────────

    @Test
    fun `S3 concurrent different chats are unioned`(): Unit = runBlocking {
        val (app, web) = pair()
        val desktop = device("desktop").apply { signIn("acct"); pull() }
        app.newChatWith("from app", "x1", "x2")
        web.newChatWith("from web", "y1")
        desktop.newChatWith("from desktop", "z1")
        app.sync()
        web.sync()
        desktop.sync()
        quiesce(listOf(app, web, desktop), "S3")
        for (d in listOf(app, web, desktop)) {
            assertEquals(setOf("from app", "from web", "from desktop"), d.chats().map { it.preview_name }.toSet())
        }
        assertConverged(listOf(app, web, desktop), "S3")
    }

    // ── S4 / §0.2: same chat, same point → sibling variants ──────────────────

    @Test
    fun `S4 different continuations of the same chat become sibling variants on every device`(): Unit = runBlocking {
        val (app, web) = pair()
        val desktop = device("desktop").apply { signIn("acct"); pull() }
        val c = app.newChatWith("c", "q1", "a1")
        app.sync(); web.sync(); desktop.sync()

        app.send(c, "app-q2"); app.reply(c, "app-a2")
        web.send(c, "web-q2"); web.reply(c, "web-a2")
        desktop.send(c, "desk-q2")
        app.sync(); web.sync(); desktop.sync()
        quiesce(listOf(app, web, desktop), "S4")

        for (d in listOf(app, web, desktop)) {
            val chat = d.chat(c)
            val node = nodeOf(chat, "app-q2")
            assertEquals(setOf("app-q2", "web-q2", "desk-q2"), node.variants.map { it.userMessage.text }.toSet(), "${d.name}: siblings in one node")
            assertEquals(nodeOf(chat, "q1").nodeId, node.parentNodeId)
            assertEquals(listOf("app-a2"), node.variants.first { it.userMessage.text == "app-q2" }.responses.map { it.text })
            assertEquals(listOf("web-a2"), node.variants.first { it.userMessage.text == "web-q2" }.responses.map { it.text })
            assertEquals(3, d.repo.getBranchInfo(chat, node.nodeId)!!.totalVariants, "${d.name}: branch arrows")
        }
        // Each device keeps looking at its own continuation (view state)
        assertEquals("app-a2", app.chat(c).messages.last().text)
        assertEquals("web-a2", web.chat(c).messages.last().text)
        assertConverged(listOf(app, web, desktop), "S4")
    }

    @Test
    fun `S4b different replies to the same question are both kept`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "q1")
        app.sync(); web.sync()
        app.reply(c, "app-answer")
        web.reply(c, "web-answer")
        app.sync(); web.sync(); app.sync()
        quiesce(listOf(app, web), "S4b")
        for (d in listOf(app, web)) {
            assertHas(d, "q1", "app-answer", "web-answer")
            MergeAssert.assertValidChat(d.chat(c))
        }
        assertConverged(listOf(app, web), "S4b")
    }

    // ── S5: deletions ─────────────────────────────────────────────────────────

    @Test
    fun `S5 deletions - unchanged elsewhere wins, modification beats deletion`(): Unit = runBlocking {
        val (app, web) = pair()
        val x = app.newChatWith("x", "x1")
        val y = app.newChatWith("y", "y1")
        val z = app.newChatWith("z", "z1")
        val w = app.newChatWith("w", "w1", "w2")
        val g = app.newGroup("G")
        val inGroup = app.newChatWith("grouped", "g1")
        app.addToGroup(inGroup, g)
        app.sync(); web.sync()

        app.deleteChat(x)                       // other side unchanged → gone
        app.deleteChat(y); web.send(y, "y2")    // other side modified → kept
        app.deleteChat(z); web.newChatWith("new on web", "n1")
        app.deleteLast(w)                       // delete a message, other side unchanged
        web.deleteGroup(g)
        app.sync(); web.sync(); app.sync()
        quiesce(listOf(app, web), "S5")

        for (d in listOf(app, web)) {
            assertNull(d.chatOrNull(x), "${d.name}: deleted chat resurrected")
            assertNull(d.chatOrNull(z), "${d.name}: deleted chat resurrected")
            assertEquals(listOf("y1", "y2"), d.chat(y).messages.map { it.text }, "${d.name}: modified chat must survive its deletion")
            assertNotNull(d.chats().firstOrNull { it.preview_name == "new on web" })
            assertEquals(listOf("w1"), d.chat(w).messages.map { it.text }, "${d.name}: deleted message came back")
            assertTrue(d.history().groups.none { it.group_id == g })
            assertNull(d.chat(inGroup).group)
        }
        assertConverged(listOf(app, web), "S5")
    }

    // ── S6: long offline on both sides ────────────────────────────────────────

    @Test
    fun `S6 long offline on both sides, with failing uploads, loses nothing`(): Unit = runBlocking {
        val (app, web) = pair()
        val shared = app.newChatWith("shared", "s1", "s2")
        app.sync(); web.sync()

        val appTexts = mutableListOf<String>()
        val webTexts = mutableListOf<String>()
        val appChat = app.newChat("app offline")
        repeat(10) { i ->
            app.send(appChat, "app-off-q$i").also { appTexts += "app-off-q$i" }
            app.reply(appChat, "app-off-a$i").also { appTexts += "app-off-a$i" }
            if (i % 3 == 0) {
                app.send(shared, "app-shared-$i"); appTexts += "app-shared-$i"
                server.failNextPuts.set(1_000)  // the app is offline: its uploads fail
                app.flush()
                server.failNextPuts.set(0)
            }
        }
        repeat(10) { i ->
            web.send(shared, "web-shared-q$i"); web.reply(shared, "web-shared-a$i")
            webTexts += listOf("web-shared-q$i", "web-shared-a$i")
            if (i == 5) web.rename(shared, "renamed on web")
            web.sync()
        }
        assertFalse(server.content("chat_history_acct.json", "acct")!!.contains("app-off-q0"), "app was offline")

        app.sync()
        web.sync()
        quiesce(listOf(app, web), "S6")
        for (d in listOf(app, web)) assertHas(d, *(appTexts + webTexts).toTypedArray())
        assertEquals("renamed on web", app.chat(shared).preview_name)
        assertConverged(listOf(app, web), "S6")
    }

    // ── S7: titles, groups, system prompts, settings ──────────────────────────

    @Test
    fun `S7 titles groups system prompts and settings edited on different sides all survive`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "c1")
        val d = app.newChatWith("d", "d1")
        val g = web.newGroup("G")
        app.sync(); web.sync(); app.sync()

        app.rename(c, "title from app")
        web.setSystemPrompt(c, "prompt from web")
        app.addToGroup(d, g)
        web.renameGroup(g, "G renamed")
        val g2 = app.newGroup("G2 from app")
        app.updateSettings { it.copy(temperature = 0.4) }
        web.updateSettings { it.copy(selected_model = "model-from-web") }
        app.sync(); web.sync(); app.sync()
        quiesce(listOf(app, web), "S7")

        for (dev in listOf(app, web)) {
            val chat = dev.chat(c)
            assertEquals("title from app", chat.preview_name)
            assertEquals("prompt from web", chat.systemPrompt)
            assertEquals(g, dev.chat(d).group)
            assertEquals("G renamed", dev.history().groups.first { it.group_id == g }.group_name)
            assertTrue(dev.history().groups.any { it.group_id == g2 })
            assertEquals(0.4, dev.settings().temperature)
            assertEquals("model-from-web", dev.settings().selected_model)
        }

        // Same field edited on both sides: they converge on one value
        app.rename(c, "A")
        web.rename(c, "W")
        app.sync(); web.sync()
        quiesce(listOf(app, web), "S7 conflict")
        assertEquals(app.chat(c).preview_name, web.chat(c).preview_name)
        assertConverged(listOf(app, web), "S7")
    }

    // ── S8: restarts, reinstall, sign-out/in, account switch ─────────────────

    @Test
    fun `S8a a pending upload lost by a restart is uploaded after it`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "q1")
        app.sync()
        app.send(c, "written just before the process died")  // upload still pending (debounce)
        app.restart()
        app.pull()
        web.sync()
        assertHas(web, "written just before the process died")
    }

    @Test
    fun `S8b sync state lost with local changes - nothing is lost`(): Unit = runBlocking {
        val (app, web) = pair()
        val x = app.newChatWith("x", "x1")
        val y = app.newChatWith("y", "y1")
        app.sync(); web.sync()

        web.deleteChat(x)
        web.newChatWith("new on web", "n1")
        web.sync()
        app.send(y, "app-y2")
        app.close()
        app.file("sync_state.json").delete()
        app.file("sync_base").deleteRecursively()
        app.restart()
        app.pull()
        web.sync()
        quiesce(listOf(app, web), "S8b")

        for (d in listOf(app, web)) assertHas(d, "y1", "app-y2", "n1")
        assertConverged(listOf(app, web), "S8b")
    }

    @Test
    fun `S8c base snapshots lost but the state kept - a remote deletion still applies`(): Unit = runBlocking {
        val (app, web) = pair()
        val x = app.newChatWith("x", "x1")
        app.newChatWith("y", "y1")
        app.sync(); web.sync()
        web.deleteChat(x)
        web.sync()
        app.file("sync_base").deleteRecursively()
        app.pull()
        assertNull(app.chatOrNull(x), "local unchanged since the base: it is the base, the deletion applies")
        assertConverged(listOf(app, web), "S8c")
    }

    @Test
    fun `S8d reinstall without local data gets everything and uploads nothing destructive`(): Unit = runBlocking {
        val (app, web) = pair()
        app.newChatWith("c", "q1", "a1")
        web.newChatWith("d", "q2")
        app.sync(); web.sync(); app.sync()
        val before = serverHistory()

        app.close()
        devices.remove(app)
        app.filesDir.deleteRecursively()
        val fresh = device("app")
        fresh.signIn("acct")
        fresh.pull()
        assertHas(fresh, "q1", "a1", "q2")
        MergeAssert.assertSameContent(before, serverHistory(), "a reinstall must not change the account's chats")
        assertConverged(listOf(fresh, web), "S8d")
    }

    @Test
    fun `S8e sign-out, local edits, sign-in again - both sides' edits merge`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "q1")
        val d = app.newChatWith("d", "q2")
        app.sync(); web.sync()

        app.signOut()
        assertFalse(app.file("sync_base").exists(), "sign-out drops the base snapshots")
        assertEquals("", app.engine.syncStateAccount())
        app.send(c, "edited while signed out")
        app.sync()  // sync is off: nothing happens
        web.send(d, "web meanwhile")
        web.sync()
        assertFalse(server.content("chat_history_acct.json", "acct")!!.contains("edited while signed out"))

        app.signIn("acct")
        app.pull()
        web.sync()
        quiesce(listOf(app, web), "S8e")
        for (dev in listOf(app, web)) assertHas(dev, "edited while signed out", "web meanwhile")
        assertConverged(listOf(app, web), "S8e")
    }

    @Test
    fun `S8f account switch never leaks one account's data into the other`(): Unit = runBlocking {
        val webA = device("webA").apply { signIn("alice"); pull() }
        webA.newChatWith("alice chat", "alice-q")
        webA.updateSettings { it.copy(temperature = 0.1, selected_model = "alice-model") }
        webA.sync()
        val webB = device("webB").apply { signIn("bob"); pull() }
        webB.newChatWith("bob chat", "bob-q")
        webB.updateSettings { it.copy(temperature = 0.7, selected_model = "bob-model") }
        webB.sync()

        val app = device("app")
        app.signIn("alice")
        app.pull()
        assertHas(app, "alice-q")
        app.newChatWith("alice from app", "alice-app-q")
        app.sync()

        app.signIn("bob")
        app.pull()
        assertEquals("bob", app.user)
        assertEquals(setOf("bob chat"), app.chats().map { it.preview_name }.toSet())
        assertTrue(app.file("chat_history_alice.json").exists(), "alice's data stays local")
        webB.sync()
        for (text in listOf("alice-q", "alice-app-q")) {
            assertFalse(server.content("chat_history_bob.json", "bob")!!.contains(text), "alice's chat leaked into bob's account")
            assertFalse(text in webB.texts())
        }
        // Account-global settings: bob's copy is adopted, not merged with alice's
        assertEquals(0.7, app.settings().temperature)
        assertEquals("bob-model", app.settings().selected_model)
        assertEquals("bob-model", serverSettings("bob").selected_model)
        assertEquals("alice-model", serverSettings("alice").selected_model)

        // And back: alice's chats (including the one made on the app) are intact
        app.signIn("alice")
        app.pull()
        assertEquals("alice", app.user)
        assertHas(app, "alice-q", "alice-app-q")
        assertFalse("bob-q" in app.texts())
        assertFalse(server.content("chat_history_alice.json", "alice")!!.contains("bob-q"))
        assertEquals("alice-model", app.settings().selected_model)
    }

    // ── S9: a local write racing a pull ───────────────────────────────────────

    @Test
    fun `S9 a local write landing while a pull fetches the remote copy is not lost`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "q1", "a1")
        app.sync(); web.sync()
        web.send(c, "web-q2")
        web.sync()

        val fired = AtomicBoolean(false)
        server.beforeGet = { _, name ->
            // While the app's engine waits for the GET, the app saves a message
            if (name.startsWith("chat_history") && fired.compareAndSet(false, true)) app.send(c, "app-q2-during-pull")
        }
        app.pull()
        server.beforeGet = null
        assertTrue(fired.get())
        assertHas(app, "web-q2", "app-q2-during-pull")
        app.sync(); web.sync()
        assertHas(web, "web-q2", "app-q2-during-pull")
        assertConverged(listOf(app, web), "S9")
    }

    @Test
    fun `S9b a pinned streaming reply lands in its variant although a merge arrived meanwhile`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "q1", "a1")
        app.sync(); web.sync()
        app.send(c, "app-q2")
        val variant = app.chat(c).currentVariantPath.last()
        web.send(c, "web-q2"); web.reply(c, "web-a2")
        web.sync()
        app.pull()  // merge arrives while "streaming"
        app.reply(c, "app-a2", targetVariantId = variant)
        app.sync(); web.sync()
        for (d in listOf(app, web)) {
            val v = d.chat(c).messageNodes.flatMap { it.variants }.first { it.variantId == variant }
            assertEquals(listOf("app-a2"), v.responses.map { it.text })
        }
        assertConverged(listOf(app, web), "S9b")
    }

    @Test
    fun `S9c concurrent local writes, automatic uploads and pulls on several devices lose nothing`(): Unit = runBlocking {
        val app = device("app", debounceMs = 20).apply { signIn("acct"); pull() }
        val web = device("web", debounceMs = 20).apply { signIn("acct"); pull() }
        val c = app.newChatWith("c", "q0")
        app.sync(); web.sync()
        server.getDelayMs = 15

        val written = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val jobs = listOf(app, web).flatMap { d ->
            listOf(
                async(Dispatchers.IO) {
                    val own = d.newChat("${d.name} chat")
                    repeat(15) { i ->
                        d.send(own, "${d.name}-own-$i").also { written += "${d.name}-own-$i" }
                        if (i % 2 == 0) d.reply(c, "${d.name}-c-$i").also { written += "${d.name}-c-$i" }
                        delay(5)
                    }
                },
                async(Dispatchers.IO) { repeat(10) { d.pull(); delay(10) } }
            )
        }
        jobs.awaitAll()
        server.getDelayMs = 0
        quiesce(listOf(app, web), "S9c")
        for (d in listOf(app, web)) assertHas(d, *written.toTypedArray())
        assertConverged(listOf(app, web), "S9c")
    }
}
