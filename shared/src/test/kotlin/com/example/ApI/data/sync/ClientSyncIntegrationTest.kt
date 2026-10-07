package com.example.ApI.data.sync

import com.example.ApI.data.model.GitHubAuth
import com.example.ApI.data.model.GitHubConnection
import com.example.ApI.data.model.GitHubUser
import com.example.ApI.data.model.Message
import com.example.ApI.data.repository.ExternalConnectionsManager
import com.example.ApI.data.repository.ReplyAnchor
import com.example.ApI.data.sync.SimAssert.assertConverged
import com.example.ApI.data.sync.SimAssert.quiesce
import com.example.ApI.data.sync.merge.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Multi-device behaviour of the client integration (SYNC_MERGE_PLAN.md §5): empty-chat cleanup
 * against the sync base, disconnects that propagate, and settings transforms that don't revert
 * another device's change.
 */
class ClientSyncIntegrationTest {

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

    private suspend fun pair(): Pair<SimDevice, SimDevice> {
        val web = SimDevice("web", root, server, clock).also { devices += it }.apply { signIn("acct"); pull() }
        val app = SimDevice("app", root, server, clock).also { devices += it }.apply { signIn("acct"); pull() }
        return app to web
    }

    @Test
    fun `cleanup keeps another device's new empty chat but removes this device's own junk`(): Unit = runBlocking {
        val (app, web) = pair()
        val keep = app.newChatWith("has content", "q")
        val othersEmpty = web.newChat("web's new chat")  // its user is about to type
        web.sync(); app.sync()
        assertNotNull(app.chatOrNull(othersEmpty))

        val ownSynced = app.newChat("app junk, already uploaded")
        app.sync(); web.sync()
        assertNotNull(web.chatOrNull(ownSynced))
        val ownLocal = app.newChat("app junk, never uploaded")

        assertEquals(2, app.repo.cleanupEmptyChats(app.user))
        assertNotNull(app.chatOrNull(othersEmpty), "an empty chat from the sync base made elsewhere is kept")
        assertNull(app.chatOrNull(ownSynced))
        assertNull(app.chatOrNull(ownLocal))
        assertNotNull(app.chatOrNull(keep))

        quiesce(listOf(app, web), "cleanup")
        assertNotNull(web.chatOrNull(othersEmpty), "the other device's empty chat survives")
        assertNull(web.chatOrNull(ownSynced), "this device's removed junk is deleted everywhere")
        assertConverged(listOf(app, web), "cleanup")
    }

    @Test
    fun `cleanup never deletes a chat another device wrote into meanwhile`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChat("started here")
        app.sync(); web.sync()
        web.send(c, "typed on the web")  // app has not pulled it yet
        web.sync()
        assertEquals(1, app.repo.cleanupEmptyChats(app.user))
        quiesce(listOf(app, web), "modification beats deletion")
        for (d in listOf(app, web)) {
            assertTrue("typed on the web" in d.texts(), "${d.name}: the web's message survives the app's cleanup")
        }
        assertConverged(listOf(app, web), "modification beats deletion")
    }

    @Test
    fun `while signed out cleanup keeps empty chats of the kept sync base`(): Unit = runBlocking {
        // Sign-out keeps the sync state, so removing another device's empty chat now would be
        // uploaded as a deletion at the next sign-in (T5 review). Without any base (sync never
        // used) every empty chat is removed: ClientIntegrationT5ReviewTest.
        val (app, web) = pair()
        val othersEmpty = web.newChat("web's new chat")
        web.sync(); app.sync()
        app.signOut()
        assertEquals(0, app.repo.cleanupEmptyChats(app.user))
        assertNotNull(app.chatOrNull(othersEmpty))
    }

    @Test
    fun `a streamed reply keeps following this device's content across a real sync merge`(): Unit = runBlocking {
        val (app, web) = pair()
        val c = app.newChatWith("c", "q0", "a0")
        app.send(c, "q1")
        app.sync(); web.sync()

        // The app streams an answer to q1 in several saves (text before a tool call, then more)
        val anchor = ReplyAnchor.forRequest(app.chat(c).messages)
        app.repo.addAnchoredResponse(app.user, c, Message(role = "assistant", text = "app part 1"), anchor)
        // Meanwhile the web answered q1 too and synced first
        web.reply(c, "web answer")
        web.sync()
        app.pull()  // diverged responses: the merge forks the app's content away from q1's variant
        app.repo.addAnchoredResponse(app.user, c, Message(role = "assistant", text = "app part 2"), anchor)

        quiesce(listOf(app, web), "pinned stream")
        for (d in listOf(app, web)) {
            val variants = d.chat(c).messageNodes.flatMap { it.variants }.map { v -> v.responses.map { it.text } }
            assertTrue(listOf("app part 1", "app part 2") in variants, "${d.name}: the app's reply stays in one piece: $variants")
            assertTrue(listOf("web answer") in variants, "${d.name}: the web's answer is kept: $variants")
        }
        assertConverged(listOf(app, web), "pinned stream")
    }

    private fun gitHubConnection(token: String) = GitHubConnection(
        auth = GitHubAuth(accessToken = token, scope = "repo", createdAt = 1),
        user = GitHubUser("octo", 1, "n", "a", null, "u", "h", null, null, null, null, null, null, 0, 0, 0, 0, "c", "u"),
        connectedAt = 1
    )

    @Test
    fun `a GitHub disconnect propagates instead of coming back with the next pull`(): Unit = runBlocking {
        val (app, web) = pair()
        app.repo.saveGitHubConnection(app.user, gitHubConnection("tok"))
        app.sync(); web.sync()
        assertTrue(web.repo.isGitHubConnected(web.user))

        app.repo.removeGitHubConnection(app.user)
        app.sync()
        assertFalse(app.repo.isGitHubConnected(app.user), "the disconnect is not undone by the pull")
        web.sync()
        assertFalse(web.repo.isGitHubConnected(web.user), "the disconnect reaches the other device")
        assertEquals(ExternalConnectionsManager.DISCONNECTED, web.file("github_auth_acct.json").readText().trim())
        assertFalse(web.user in web.settings().githubConnections)
        quiesce(listOf(app, web), "disconnect")

        // Reconnecting on the other device wins over the old disconnect
        web.repo.saveGitHubConnection(web.user, gitHubConnection("tok2"))
        web.sync(); app.sync()
        assertEquals("tok2", app.repo.loadGitHubConnection(app.user)?.auth?.accessToken)
    }

    @Test
    fun `a settings transform does not revert another device's settings change`(): Unit = runBlocking {
        val (app, web) = pair()
        val staleOnApp = app.settings()  // what a ViewModel holds in memory
        web.repo.updateAppSettings { it.copy(skipWelcomeScreen = true) }
        web.sync(); app.pull()

        app.repo.updateAppSettings { it.copy(multiMessageMode = true) }
        assertFalse(staleOnApp.skipWelcomeScreen)
        quiesce(listOf(app, web), "settings")
        for (d in listOf(app, web)) {
            assertTrue(d.settings().skipWelcomeScreen, "${d.name}: the web's change survives")
            assertTrue(d.settings().multiMessageMode, "${d.name}: the app's change arrives")
        }
    }
}
