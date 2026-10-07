package com.example.ApI.data.sync

import com.example.ApI.data.model.GitHubAuth
import com.example.ApI.data.model.GitHubConnection
import com.example.ApI.data.model.GitHubUser
import com.example.ApI.data.model.Message
import com.example.ApI.data.repository.ReplyAnchor
import com.example.ApI.data.repository.SkillsStorageManager
import com.example.ApI.data.sync.SimAssert.quiesce
import com.example.ApI.data.sync.merge.FakeClock
import com.example.ApI.util.JsonConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T5 review: client integration cases the first T5 implementation got wrong (regression tests
 * for the review fixes).
 */
class ClientIntegrationT5ReviewTest {

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

    private fun device(name: String) = SimDevice(name, root, server, clock).also { devices += it }

    private suspend fun pair(): Pair<SimDevice, SimDevice> {
        val web = device("web").apply { signIn("acct"); pull() }
        val app = device("app").apply { signIn("acct"); pull() }
        return app to web
    }

    private fun gitHubConnection(token: String) = GitHubConnection(
        auth = GitHubAuth(accessToken = token, scope = "repo", createdAt = 1),
        user = GitHubUser("octo", 1, "n", "a", null, "u", "h", null, null, null, null, null, null, 0, 0, 0, 0, "c", "u"),
        connectedAt = 1
    )

    @Test
    fun `cleanup while signed out does not delete another device's empty chat after re-sign-in`(): Unit = runBlocking {
        val (app, web) = pair()
        app.newChatWith("content", "q")
        val othersEmpty = web.newChat("web's new chat")  // its user is about to type
        web.sync(); app.sync()
        assertNotNull(app.chatOrNull(othersEmpty))

        app.signOut()
        app.repo.cleanupEmptyChats(app.user)  // e.g. the chat history screen opened while signed out
        app.signIn("acct")
        quiesce(listOf(app, web), "re-sign-in after signed-out cleanup")
        assertNotNull(web.chatOrNull(othersEmpty), "web: its own new chat must not be deleted by the app's local cleanup")
    }

    @Test
    fun `a disconnect marker on the server does not wipe a connection made on a device that never synced the file`(): Unit = runBlocking {
        val web = device("web").apply { signIn("acct"); pull() }
        web.repo.saveGitHubConnection(web.user, gitHubConnection("tok-web"))
        web.sync()
        web.repo.removeGitHubConnection(web.user)
        web.sync()
        assertEquals("null", server.content("github_auth_acct.json", "acct")?.trim(), "precondition: the server holds the marker")

        // A fresh device connects GitHub, then signs in (its default data moves into the account)
        val app = device("app")
        app.repo.saveGitHubConnection(app.user, gitHubConnection("tok-app"))
        assertTrue(app.repo.isGitHubConnected(app.user))
        app.signIn("acct")
        app.pull()
        assertEquals(
            "tok-app", app.repo.loadGitHubConnection(app.user)?.auth?.accessToken,
            "the connection made on this device must survive its first sync"
        )
    }

    @Test
    fun `a disconnect racing a reconnect leaves the auth file and the settings entry consistent`(): Unit = runBlocking {
        val (app, web) = pair()
        app.repo.saveGitHubConnection(app.user, gitHubConnection("tok1"))
        app.sync(); web.sync()
        Thread.sleep(5)  // distinct lastUsed

        web.repo.saveGitHubConnection(web.user, gitHubConnection("tok2"))  // reconnect, uploaded first
        web.sync()
        app.repo.removeGitHubConnection(app.user)  // concurrent disconnect, before pulling
        app.sync()
        quiesce(listOf(app, web), "disconnect vs reconnect")

        for (d in listOf(app, web)) {
            assertEquals(
                d.user in d.settings().githubConnections, d.repo.isGitHubConnected(d.user),
                "${d.name}: settings entry (shown as connected in the UI) and auth file disagree " +
                    "(file: ${d.file("github_auth_acct.json").readText().take(40)})"
            )
        }
    }

    @Test
    fun `a reply whose question was deleted elsewhere is not attached to another question`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "q0", "a0")
        app.send(c, "q1")
        app.sync(); web.sync()

        // The app starts streaming the answer to q1 (nothing saved yet)
        val anchor = ReplyAnchor.forRequest(app.chat(c).messages)
        // Meanwhile q1 is deleted on the web and the app pulls before the answer is saved
        assertTrue(web.deleteLast(c))
        web.sync(); app.pull()
        assertFalse("q1" in app.texts(), "precondition: the deletion reached the app")

        app.repo.addAnchoredResponse(app.user, c, Message(role = "assistant", text = "answer to q1"), anchor)
        val q0Responses = app.chat(c).messageNodes.flatMap { it.variants }
            .filter { it.userMessage.text == "q0" }.flatMap { v -> v.responses.map { it.text } }
        assertFalse("answer to q1" in q0Responses, "q1's answer must not become a reply to q0: $q0Responses")
    }

    @Test
    fun `a reply streamed into a chat deleted elsewhere mid-stream is not silently dropped`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "q0", "a0")
        app.send(c, "q1")
        app.sync(); web.sync()

        val anchor = ReplyAnchor.forRequest(app.chat(c).messages)
        web.deleteChat(c)
        web.sync(); app.pull()

        val saved = app.repo.addAnchoredResponse(app.user, c, Message(role = "assistant", text = "streamed answer"), anchor)
        assertNotNull(saved, "the streamed answer was dropped")
    }

    // ── Review-fix follow-ups ────────────────────────────────────────────────

    private fun responsesOf(d: SimDevice, chatId: String, question: String): List<String> =
        d.chat(chatId).messageNodes.flatMap { it.variants }.filter { it.userMessage.text == question }
            .flatMap { v -> v.responses.map { it.text } }

    @Test
    fun `a reply whose question was deleted elsewhere restores the question on every device`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "q0", "a0")
        app.send(c, "q1")
        app.sync(); web.sync()

        val anchor = ReplyAnchor.forRequest(app.chat(c).messages)
        assertTrue(web.deleteLast(c))
        web.sync(); app.pull()

        assertNotNull(app.repo.addAnchoredResponse(app.user, c, Message(role = "assistant", text = "part 1"), anchor))
        // The request's next save (after a tool call, say) follows the first one
        assertNotNull(app.repo.addAnchoredResponse(app.user, c, Message(role = "assistant", text = "part 2"), anchor))
        assertEquals(listOf("part 1", "part 2"), responsesOf(app, c, "q1"))
        assertEquals(listOf("q0", "a0", "q1", "part 1", "part 2"), app.chat(c).messages.map { it.text })

        quiesce(listOf(app, web), "restored question")
        SimAssert.assertConverged(listOf(app, web), "restored question")
        assertEquals(listOf("part 1", "part 2"), responsesOf(web, c, "q1"), "the restored question and its answer reach the web")
        assertEquals(listOf("a0"), responsesOf(web, c, "q0"))
    }

    @Test
    fun `a restored question becomes a sibling when the other device continued after deleting it`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "q0", "a0")
        app.send(c, "q1")
        app.sync(); web.sync()

        val anchor = ReplyAnchor.forRequest(app.chat(c).messages)
        assertTrue(web.deleteLast(c))
        web.send(c, "q1-other")
        web.sync(); app.pull()

        assertNotNull(app.repo.addAnchoredResponse(app.user, c, Message(role = "assistant", text = "answer to q1"), anchor))
        assertEquals(listOf("answer to q1"), responsesOf(app, c, "q1"))
        assertEquals(listOf("a0"), responsesOf(app, c, "q0"))
        val q0Child = app.chat(c).messageNodes.flatMap { it.variants }.first { it.userMessage.text == "q0" }.childNodeId
        val siblings = app.chat(c).messageNodes.first { it.nodeId == q0Child }.variants.map { it.userMessage.text }
        assertEquals(setOf("q1-other", "q1"), siblings.toSet(), "both continuations of q0 are kept as variants")
        quiesce(listOf(app, web), "restored sibling")
        SimAssert.assertConverged(listOf(app, web), "restored sibling")
    }

    @Test
    fun `a chat deleted elsewhere mid-stream is restored with the reply and syncs back`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "q0", "a0")
        app.send(c, "q1")
        app.sync(); web.sync()

        val anchor = ReplyAnchor.forRequest(app.chat(c).messages)
        web.deleteChat(c)
        web.sync(); app.pull()
        assertEquals(null, app.chatOrNull(c), "precondition: the deletion reached the app")

        assertNotNull(app.repo.addAnchoredResponse(app.user, c, Message(role = "assistant", text = "streamed answer"), anchor))
        assertEquals(listOf("q0", "a0", "q1", "streamed answer"), app.chat(c).messages.map { it.text })
        quiesce(listOf(app, web), "restored chat")
        SimAssert.assertConverged(listOf(app, web), "restored chat")
        assertEquals(listOf("streamed answer"), responsesOf(web, c, "q1"))
    }

    @Test
    fun `a chat or question deleted on this device mid-stream stays deleted`(): Unit = runBlocking {
        val (app, _) = pair()
        val c1 = app.newChatWith("c1", "q0", "a0")
        app.send(c1, "q1")
        val anchor1 = ReplyAnchor.forRequest(app.chat(c1).messages)
        app.repo.deleteChat(app.user, c1)  // the user deletes the chat while its reply streams
        assertEquals(null, app.repo.addAnchoredResponse(app.user, c1, Message(role = "assistant", text = "late"), anchor1))
        assertEquals(null, app.chatOrNull(c1), "a chat deleted here is not resurrected by its reply")

        val c2 = app.newChatWith("c2", "q0", "a0")
        app.send(c2, "q1")
        val anchor2 = ReplyAnchor.forRequest(app.chat(c2).messages)
        assertTrue(app.deleteLast(c2))  // the user deletes the question while its answer streams
        assertEquals(null, app.repo.addAnchoredResponse(app.user, c2, Message(role = "assistant", text = "late"), anchor2))
        assertEquals(listOf("q0", "a0"), app.chat(c2).messages.map { it.text }, "the reply is neither restored nor attached to q0")
    }

    @Test
    fun `cleanup without sync still removes every empty chat`(): Unit = runBlocking {
        val dev = device("solo")
        val a = dev.newChat("a")
        dev.newChatWith("b", "q")
        dev.restart()  // the empty chat is no longer one this repository instance created
        assertEquals(1, dev.repo.cleanupEmptyChats(dev.user))
        assertEquals(null, dev.chatOrNull(a))
    }

    @Test
    fun `concurrent skill toggles are not lost`() {
        val dir = File(root, "skills-dir").apply { mkdirs() }
        val mgr = SkillsStorageManager(dir, JsonConfig.prettyPrint)
        val threads = 6
        repeat(threads) { assertNotNull(mgr.createSkill("s$it", "d")) }
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        repeat(threads) { t ->
            pool.execute {
                try {
                    start.await()
                    repeat(150) {
                        mgr.setSkillEnabled("s$t", true)
                        mgr.setSkillEnabled("s$t", false)
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertTrue(errors.isEmpty(), "errors: $errors")
        val enabled = mgr.getInstalledSkills().filter { it.isEnabled }.map { it.directoryName }
        assertTrue(enabled.isEmpty(), "every skill's last write was 'disabled'; lost updates left enabled: $enabled")
    }
}
