package com.example.ApI.data.sync

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
import kotlin.test.assertTrue

/**
 * T4 adversarial review: scenarios against the rewritten SyncEngine that probe data-loss paths
 * outside the happy CAS protocol.
 */
class SyncEngineT4ReviewTest {

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

    private suspend fun pair(): Pair<SimDevice, SimDevice> {
        val web = device("web").apply { signIn("acct"); pull() }
        val app = device("app").apply { signIn("acct"); pull() }
        return app to web
    }

    private fun assertHas(d: SimDevice, vararg texts: String) {
        val missing = texts.toSet() - d.texts()
        assertTrue(missing.isEmpty(), "${d.name} lost $missing")
    }

    /**
     * A sync server that predates T1 ignores `base_version` (pydantic drops the unknown field) and
     * writes unconditionally.  A CAS upload on a stale base would silently clobber the other
     * device's version, and the other device's next 3-way merge would read the clobbered chat as a
     * deliberate deletion and drop it locally too.  The engine stops syncing as soon as it sees a
     * response without `"cas": true` (here: the server is swapped under engines that already
     * confirmed CAS, so each device's first PUT still lands), and no device loses its data; once
     * the server supports CAS again everything converges.
     */
    @Test
    fun `a server without CAS must not turn a stale upload into a silent deletion`(): Unit = runBlocking {
        val (app, web) = pair()
        server.legacyNoCas = true
        web.newChatWith("from web", "w-q1", "w-a1")
        web.flush()
        app.newChatWith("from app", "a-q1", "a-a1")
        app.flush()  // stale base: a CAS server answers 409, the legacy one overwrites

        web.pull()
        app.pull()
        web.pull()

        assertHas(web, "w-q1")
        assertHas(app, "a-q1")
        assertTrue(web.engine.serverLacksCas.value && app.engine.serverLacksCas.value)
        val puts = server.putCount.get()
        app.updateSettings { it.copy(temperature = 0.4) }
        app.sync()
        assertEquals(puts, server.putCount.get(), "uploaded to a server without CAS")

        // The server is upgraded: nothing was lost, both chats reach both devices
        server.legacyNoCas = false
        web.pull(); app.pull(); web.pull()
        assertFalse(web.engine.serverLacksCas.value)
        assertHas(web, "w-q1", "a-q1")
        assertHas(app, "w-q1", "a-q1")
        assertConverged(listOf(app, web), "after the upgrade")
    }

    @Test
    fun `nothing is uploaded to a server that never supported CAS`(): Unit = runBlocking {
        server.legacyNoCas = true
        val app = device("app")
        app.newChatWith("local", "q1", "a1")
        app.signIn("acct")
        app.pull()
        app.newChatWith("more", "q2", "a2")
        app.sync()
        assertEquals(0, server.putCount.get())
        assertTrue(app.engine.serverLacksCas.value)
        assertFalse(app.engine.testConnection())
        assertHas(app, "q1", "q2")
    }

    /**
     * The sync DB restored from a backup (deploy rollback, plan §8 step 1): every blob is back at an
     * older version than the devices' bases.  The engine treats the older copy as "remote changed"
     * and 3-way merges it against the newer base, so everything added after the backup is read as
     * deleted on the server and removed from every device — the devices hold the only copies.
     */
    @Test
    fun `a server restored from an older backup does not delete the devices' newer data`(): Unit = runBlocking {
        val (app, web) = pair()
        web.newChatWith("old", "old-q", "old-a")
        web.sync(); app.pull()
        val backup = server.blob("chat_history_acct.json", "acct")!!

        app.newChatWith("new", "new-q", "new-a")
        app.sync(); web.pull()
        assertHas(web, "new-q")

        // Operator restores sync_data.db from the backup
        server.seed("chat_history_acct.json", backup.content, updatedAt = backup.updatedAt, user = "acct")

        web.pull()
        app.pull()
        quiesce(listOf(app, web), "restore")

        assertHas(web, "old-q", "new-q", "new-a")
        assertHas(app, "old-q", "new-q", "new-a")
        assertConverged(listOf(app, web), "restore")
    }

    /** The same, met by a debounced upload: its CAS PUT gets a 409 carrying the older version. */
    @Test
    fun `an upload that meets a server restored from a backup merges without a base`(): Unit = runBlocking {
        val (app, web) = pair()
        web.newChatWith("old", "old-q", "old-a")
        web.sync(); app.pull()
        val backup = server.blob("chat_history_acct.json", "acct")!!
        app.newChatWith("new", "new-q", "new-a")
        app.sync(); web.pull()

        server.seed("chat_history_acct.json", backup.content, updatedAt = backup.updatedAt, user = "acct")
        web.newChatWith("later", "later-q", "later-a")
        web.flush()

        val onServer = server.content("chat_history_acct.json", "acct")!!
        for (t in listOf("old-q", "new-q", "later-q")) assertTrue(t in onServer, "server copy lacks $t")
        app.pull()
        assertHas(app, "old-q", "new-q", "new-a", "later-q")
        assertHas(web, "old-q", "new-q", "new-a", "later-q")
    }

    /**
     * Renaming the local user while signed in (desktop `updateUsername`) is taken for an account
     * switch: the state is reset and app_settings "adopts" the server copy, reverting a settings
     * change the device had not uploaded yet.
     */
    @Test
    fun `renaming the local user while signed in does not revert an unsynced settings change`(): Unit = runBlocking {
        val (app, _) = pair()
        app.updateSettings { it.copy(temperature = 0.5) }
        app.sync()
        app.updateSettings { it.copy(temperature = 1.7) }   // not uploaded yet (manual debounce)
        app.updateSettings { it.copy(current_user = "renamed") }
        app.pull()
        assertEquals(1.7, app.settings().temperature, "the local settings change was reverted by the adopt-remote reset")

        // Signing back into the same account switches the local user back, keeping the bases
        app.signIn("acct")
        assertEquals("acct", app.user)
        assertEquals("acct", app.engine.syncStateAccount())
        assertTrue(app.engine.baseContent("app_settings.json") != null, "bases dropped on re-sign-in to the same account")
    }

    /**
     * Plan §4 "Fail-safe: any exception leaves files untouched".  A remote copy this client can't
     * decode makes [com.example.ApI.data.sync.merge.SyncFileMerger.mergeFile] fall back to the
     * local text, and the engine then CAS-uploads that over the remote copy (base_version = R's
     * version, so the PUT succeeds): last-write-wins over content it could not read.
     */
    @Test
    fun `an undecodable remote copy is not overwritten by the local copy`(): Unit = runBlocking {
        val (app, web) = pair()
        web.newChatWith("c", "q1", "a1")
        web.sync(); app.pull()
        val unreadable = """{"user_name":"acct","chat_history":"written by a client this version can't read"}"""
        server.seed("chat_history_acct.json", unreadable, user = "acct")

        val localBefore = app.chatFile().readText()
        app.pull()

        assertEquals(unreadable, server.content("chat_history_acct.json", "acct"), "the remote copy was overwritten without being merged")
        assertEquals(localBefore, app.chatFile().readText(), "the local copy was changed by a failed merge")
    }

    /**
     * The "adopt remote" mark set at an account switch stays on a global file that neither side had
     * yet.  A change the user makes to that file later (long after the switch) is then discarded
     * wholesale as soon as the account's copy shows up.
     */
    @Test
    fun `a stale adopt-remote mark does not discard a local change made after the switch`(): Unit = runBlocking {
        val webB = device("webB").apply { signIn("bob"); pull() }
        val app = device("app")
        app.signIn("alice"); app.pull()
        app.signIn("bob"); app.pull()       // Switched: adopt-remote marked on skills_*.json (absent everywhere)

        val webFile = webB.file("skills_enabled.json")
        webFile.writeText("""{"x": false}""")
        webB.engine.onFileWritten(webFile)
        webB.sync()

        val appFile = app.file("skills_enabled.json")
        appFile.writeText("""{"y": false}""")  // the user disables skill y on the app, after the switch
        app.engine.onFileWritten(appFile)
        app.sync()

        val local = appFile.readText()
        assertTrue("\"y\"" in local, "app's own change after the switch was discarded: $local")
    }

    /**
     * A settings change made between the account switch and the switch's first sync is merged
     * into the new account's settings; only what the device had at the switch gives way.
     */
    @Test
    fun `an account switch adopts the account's settings but keeps changes made after it`(): Unit = runBlocking {
        val webB = device("webB").apply { signIn("bob"); pull() }
        webB.updateSettings { it.copy(temperature = 0.3) }
        webB.sync()
        val app = device("app")
        app.signIn("alice"); app.pull()
        app.updateSettings { it.copy(temperature = 0.9) }
        app.sync()

        server.failNextManifests.set(1_000)                   // the switch's first syncs fail
        app.signIn("bob"); app.pull()
        app.updateSettings { it.copy(multiMessageMode = true) }   // changed after the switch
        server.failNextManifests.set(0)
        app.pull()

        assertEquals(0.3, app.settings().temperature, "the old account's settings leaked into the new one")
        assertTrue(app.settings().multiMessageMode, "a change made after the switch was discarded")
        webB.pull()
        assertTrue(webB.settings().multiMessageMode)
        assertEquals(0.3, webB.settings().temperature)
    }
}
