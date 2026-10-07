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
import org.junit.jupiter.api.Disabled
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
 * T5 review: client integration cases the implementation gets wrong. Failing tests are
 * @Disabled with the defect in the reason so the suite stays green; remove the annotation to
 * reproduce / to check a fix.
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
    @Disabled(
        "T5 bug: with sync off EmptyChatCleanup removes every empty chat, but sign-out now keeps the sync base, so " +
            "the removal of another device's empty chat (still in the base) is uploaded as a deletion on the next " +
            "sign-in and deletes it on every device (plan §5: never delete an empty chat another device created)"
    )
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
    @Disabled(
        "T5 bug: a 2-way (no base) merge of an auth file takes the remote, so a JSON-null disconnect marker on the " +
            "server wipes a connection the device made before its first sync (null should count as absence there, " +
            "like a missing remote file, which keeps the local connection)"
    )
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
    @Disabled(
        "T5 bug: a disconnect racing a reconnect on another device converges to an inconsistent state: the auth " +
            "file merges atomically (both changed → local wins → null) while the settings' githubConnections entry " +
            "merges per key (modified beats deleted → kept), so the Integrations screen (which reads the map) shows " +
            "GitHub connected while isGitHubConnected/tools see no connection"
    )
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
    @Disabled(
        "T5 bug: a streamed reply whose question was deleted elsewhere mid-stream is appended to the last variant " +
            "of the current path (a different question): the anchor's tail is the user message, which is never " +
            "matched, so resolveAnchoredTarget falls back to the current path"
    )
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
    @Disabled(
        "T5 gap: a reply streamed into a chat that another device deleted mid-stream is silently dropped " +
            "(addAnchoredResponse returns null); SyncReload keeps the chat on screen while it streams, so the user " +
            "watches an answer that is never saved anywhere"
    )
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

    @Test
    @Disabled(
        "Remaining unlocked load-modify-save of a synced file: SkillsStorageManager.setSkillEnabled/deleteSkill/" +
            "saveSkillSourceUrl load skills_enabled.json / skills_sources.json and save outside FileLocks, so a " +
            "concurrent write (another writer or a sync merge write) is reverted and the next 3-way merge reads the " +
            "revert as a local change"
    )
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
