package com.example.ApI.data.sync

import com.example.ApI.data.sync.SimAssert.assertConverged
import com.example.ApI.data.sync.SimAssert.quiesce
import com.example.ApI.data.sync.merge.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
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
     * writes unconditionally.  The client never checks the `"cas": true` marker, so a CAS upload on
     * a stale base silently clobbers the other device's version, and the other device's next 3-way
     * merge then reads the clobbered chat as a deliberate deletion and drops it locally too.
     */
    @Disabled("T4 review: fails — client never checks the server's \"cas\" marker")
    @Test
    fun `a server without CAS must not turn a stale upload into a silent deletion`() = runBlocking {
        val (app, web) = pair()
        server.legacyNoCas = true
        web.newChatWith("from web", "w-q1", "w-a1")
        web.flush()
        app.newChatWith("from app", "a-q1", "a-a1")
        app.flush()  // stale base: a CAS server answers 409, the legacy one overwrites

        web.pull()
        app.pull()
        web.pull()

        assertHas(web, "w-q1", "a-q1")
        assertHas(app, "w-q1", "a-q1")
    }

    /**
     * The sync DB restored from a backup (deploy rollback, plan §8 step 1): every blob is back at an
     * older version than the devices' bases.  The engine treats the older copy as "remote changed"
     * and 3-way merges it against the newer base, so everything added after the backup is read as
     * deleted on the server and removed from every device — the devices hold the only copies.
     */
    @Disabled("T4 review: fails — an older server version is merged as remote deletions")
    @Test
    fun `a server restored from an older backup does not delete the devices' newer data`() = runBlocking {
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

    /**
     * Renaming the local user while signed in (desktop `updateUsername`) is taken for an account
     * switch: the state is reset and app_settings "adopts" the server copy, reverting a settings
     * change the device had not uploaded yet.
     */
    @Disabled("T4 review: fails — a local rename is treated as an account switch")
    @Test
    fun `renaming the local user while signed in does not revert an unsynced settings change`() = runBlocking {
        val (app, _) = pair()
        app.updateSettings { it.copy(temperature = 0.5) }
        app.sync()
        app.updateSettings { it.copy(temperature = 1.7) }   // not uploaded yet (manual debounce)
        app.updateSettings { it.copy(current_user = "renamed") }
        app.pull()
        assertEquals(1.7, app.settings().temperature, "the local settings change was reverted by the adopt-remote reset")
    }

    /**
     * Plan §4 "Fail-safe: any exception leaves files untouched".  A remote copy this client can't
     * decode makes [com.example.ApI.data.sync.merge.SyncFileMerger.mergeFile] fall back to the
     * local text, and the engine then CAS-uploads that over the remote copy (base_version = R's
     * version, so the PUT succeeds): last-write-wins over content it could not read.
     */
    @Disabled("T4 review: fails — an unreadable remote is overwritten by local")
    @Test
    fun `an undecodable remote copy is not overwritten by the local copy`() = runBlocking {
        val (app, web) = pair()
        web.newChatWith("c", "q1", "a1")
        web.sync(); app.pull()
        val unreadable = """{"user_name":"acct","chat_history":"written by a client this version can't read"}"""
        server.seed("chat_history_acct.json", unreadable, user = "acct")

        app.pull()

        assertEquals(unreadable, server.content("chat_history_acct.json", "acct"), "the remote copy was overwritten without being merged")
    }

    /**
     * The "adopt remote" mark set at an account switch stays on a global file that neither side had
     * yet.  A change the user makes to that file later (long after the switch) is then discarded
     * wholesale as soon as the account's copy shows up.
     */
    @Disabled("T4 review: fails — the adopt-remote mark outlives the switch")
    @Test
    fun `a stale adopt-remote mark does not discard a local change made after the switch`() = runBlocking {
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
}
