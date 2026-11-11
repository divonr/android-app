package com.example.ApI.server

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.attachments.UnusedFileDelete
import com.example.ApI.server.attachments.UserAttachments
import com.example.ApI.server.auth.FakeSyncWhoamiClient
import com.example.ApI.server.auth.SyncIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A delete of a pending file against a save that references the same bytes (CLOUD_RUNS_PLAN.md,
 * "Server — A2", Commit): whatever the order, a saved message / project attachment never ends up
 * referencing a file that `DELETE /api/files/{sha256}` removed.
 */
class AttachmentSaveRaceTest {

    private companion object {
        const val ALICE_TOKEN = "fake-sync-token-$TEST_USERNAME"   // what the fake login mints
    }

    private val store = FakeFileStore().start().also {
        it.accounts[ALICE_TOKEN] = TEST_USERNAME
        it.accounts[TEST_BEARER] = TEST_USERNAME
    }

    @AfterTest
    fun tearDown() {
        store.beforeRequest = null
        store.stop()
    }

    private class World(val storage: ServerPlatformStorage, val repo: DataRepository) {
        fun userDir() = File(storage.baseDir, "users/$TEST_USERNAME")
        fun cacheFile(sha: String) = File(userDir(), "files/attachments_cache/$sha")
        fun chatId() = repo.loadChatHistory(TEST_USERNAME).chat_history.first().chat_id
        fun groupId() = repo.loadChatHistory(TEST_USERNAME).groups.first().group_id
        fun savedWith(sha: String): Boolean {
            val history = repo.loadChatHistory(TEST_USERNAME)
            return history.chat_history.any { c -> c.messages.any { m -> m.attachments.any { it.sha256 == sha } } } ||
                history.groups.any { g -> g.group_attachments.any { it.sha256 == sha } }
        }
    }

    private fun world(): World = seededStorageForUser().let { (storage, repo) -> World(storage, repo) }

    private fun live(world: World) = LiveServer(
        world.storage, ReplyEngine(),
        FakeSyncWhoamiClient().apply { identities[TEST_BEARER] = SyncIdentity(TEST_USERNAME, TEST_USER_EMAIL) },
        syncServerUrl = store.baseUrl
    )

    private fun LiveResponse.str(key: String): String = json()[key]!!.jsonPrimitive.content

    private fun LiveClient.uploadOk(name: String, content: String): String {
        val response = upload(name, "text/plain", content.toByteArray())
        assertEquals(201, response.status, response.body)
        return response.str("sha256")
    }

    private fun attachment(name: String, sha: String) = """{"file_name":"$name","mime_type":"text/plain","sha256":"$sha","size":1}"""

    private fun sendBody(chatId: String, id: String, name: String, sha: String) =
        """{"chatId":"$chatId","provider":"openai","modelName":"gpt-4o","messages":[{"role":"user","text":"Q","id":"$id",""" +
            """"attachments":[${attachment(name, sha)}]}]}"""

    /** The file of [sha] is where a saved message needs it: committed in the store, with its bytes. */
    private fun assertKept(world: World, sha: String, content: String) {
        awaitTrue(what = "the commit of $sha") { store.files(TEST_USERNAME)[sha]?.committed == true }
        assertTrue(content.toByteArray().contentEquals(store.files(TEST_USERNAME).getValue(sha).bytes))
        assertTrue(world.cacheFile(sha).isFile, "the server's copy must survive")
    }

    // ── The window between the save and the commit ───────────────────────────

    @Test
    fun `a delete that lands after the save and before the commit does not take the file`() {
        val world = world()
        live(world).use { server ->
            val browser = server.login()
            val otherTab = server.login()
            val content = "bytes of a sent message"
            val sha = browser.uploadOk("sent.txt", content)

            // The store cannot be reached when the message is saved: the commit stays behind
            // (retried after 1 s) while the save goes through
            store.failWith = 503
            val sent = browser.post("/api/chat/send", sendBody(world.chatId(), "race-1", "sent.txt", sha))
            assertEquals(200, sent.status, sent.body)
            assertTrue(world.savedWith(sha), "the message was saved")
            assertFalse(store.files(TEST_USERNAME).getValue(sha).committed, "the commit has not happened yet")

            // The store is back, and another tab removes the same bytes from its draft
            store.failWith = null
            val deleted = otherTab.delete("/api/files/$sha")
            assertEquals(200, deleted.status, deleted.body)
            assertEquals("false", deleted.str("deleted"))
            assertEquals("in_use", deleted.str("state"))

            assertKept(world, sha, content)
            assertEquals(200, browser.get("/api/files/$sha").status)
        }
    }

    @Test
    fun `a delete during the commit that precedes the save is refused, for every saving route`() {
        val world = world()
        live(world).use { server ->
            val browser = server.login()
            val phone = server.client()          // the same account, by bearer
            val chatId = world.chatId()

            val saves: List<Pair<String, (String) -> LiveResponse>> = listOf(
                "send" to { sha -> browser.post("/api/chat/send", sendBody(chatId, "s-$sha", "f.txt", sha)) },
                "add-message" to { sha ->
                    browser.post("/api/chats/$chatId/messages", """{"role":"user","text":"more","attachments":[${attachment("f.txt", sha)}]}""")
                },
                "group" to { sha -> browser.post("/api/groups/${world.groupId()}/attachments", attachment("f.txt", sha)) }
            )
            for ((route, save) in saves) {
                val content = "bytes saved through $route"
                val sha = browser.uploadOk("f.txt", content)

                // Hold the save's commit at the store's door
                val commitArrived = CountDownLatch(1)
                val letCommitIn = CountDownLatch(1)
                store.beforeRequest = { method, hash ->
                    if (method == "COMMIT" && hash == sha) {
                        commitArrived.countDown()
                        letCommitIn.await(10, TimeUnit.SECONDS)
                    }
                }
                val saving = CompletableFuture.supplyAsync { save(sha) }
                assertTrue(commitArrived.await(10, TimeUnit.SECONDS), "$route: the save commits first")
                assertFalse(world.savedWith(sha), "$route: nothing is saved before the commit")

                // The file is still pending in the store: an unguarded delete would remove it
                val deleted = phone.delete("/api/files/$sha")
                assertEquals(200, deleted.status, deleted.body)
                assertEquals("false", deleted.str("deleted"), route)
                assertEquals("in_use", deleted.str("state"), route)

                letCommitIn.countDown()
                val saved = saving.get(15, TimeUnit.SECONDS)
                assertTrue(saved.status in 200..201, "$route: ${saved.status} ${saved.body}")
                assertTrue(world.savedWith(sha), route)
                assertKept(world, sha, content)
                store.beforeRequest = null
            }
        }
    }

    // ── The delete came first ────────────────────────────────────────────────

    @Test
    fun `a save of a file that was deleted before it is refused and saves nothing`() {
        val world = world()
        live(world).use { server ->
            val browser = server.login()
            val otherTab = server.login()
            val chatId = world.chatId()
            val before = world.repo.loadChatHistory(TEST_USERNAME)

            val saves: List<Pair<String, (String) -> LiveResponse>> = listOf(
                "send" to { sha -> browser.post("/api/chat/send", sendBody(chatId, "gone-$sha", "f.txt", sha)) },
                "add-message" to { sha ->
                    browser.post("/api/chats/$chatId/messages", """{"role":"user","text":"more","attachments":[${attachment("f.txt", sha)}]}""")
                },
                "group" to { sha -> browser.post("/api/groups/${world.groupId()}/attachments", attachment("f.txt", sha)) }
            )
            for ((route, save) in saves) {
                // Two tabs attach the same bytes; one removes them before the other sends
                val sha = browser.uploadOk("f.txt", "bytes removed before $route")
                assertEquals(sha, otherTab.uploadOk("f.txt", "bytes removed before $route"))
                assertEquals("true", otherTab.delete("/api/files/$sha").str("deleted"))

                val refused = save(sha)
                assertEquals(409, refused.status, "$route: ${refused.body}")
                assertEquals("attachment_missing", refused.str("code"), route)
                assertFalse(world.savedWith(sha), "$route must not save a message whose file is gone")
            }
            val after = world.repo.loadChatHistory(TEST_USERNAME)
            assertEquals(before.chat_history.map { it.messages }, after.chat_history.map { it.messages })
            assertEquals(before.groups, after.groups)

            // Attached again, the same message goes through
            val sha = browser.uploadOk("f.txt", "bytes removed before send")
            assertEquals(200, browser.post("/api/chat/send", sendBody(chatId, "again", "f.txt", sha)).status)
            assertKept(world, sha, "bytes removed before send")
        }
    }

    @Test
    fun `a save that starts while a delete is in the store waits for it and never references the deleted file`() {
        val world = world()
        live(world).use { server ->
            val browser = server.login()
            val otherTab = server.login()
            val sha = browser.uploadOk("f.txt", "racing bytes")

            val deleteArrived = CountDownLatch(1)
            val letDeleteIn = CountDownLatch(1)
            store.beforeRequest = { method, hash ->
                if (method == "DELETE" && hash == sha) {
                    deleteArrived.countDown()
                    letDeleteIn.await(10, TimeUnit.SECONDS)
                }
            }
            val deleting = CompletableFuture.supplyAsync { otherTab.delete("/api/files/$sha") }
            assertTrue(deleteArrived.await(10, TimeUnit.SECONDS))
            val saving = CompletableFuture.supplyAsync {
                browser.post("/api/chat/send", sendBody(world.chatId(), "late", "f.txt", sha))
            }
            // The save cannot overtake the delete: it has not committed or saved anything
            Thread.sleep(300)
            assertEquals(0, store.count("POST", sha))
            assertFalse(world.savedWith(sha))

            letDeleteIn.countDown()
            assertEquals("true", deleting.get(15, TimeUnit.SECONDS).str("deleted"))
            val refused = saving.get(15, TimeUnit.SECONDS)
            assertEquals(409, refused.status, refused.body)
            assertFalse(world.savedWith(sha))
            assertNull(store.files(TEST_USERNAME)[sha])
        }
    }

    // ── After the commit gave up ─────────────────────────────────────────────

    @Test
    fun `a file whose commit never got through stays protected by the saved message and is committed by the refused delete`() {
        val world = world()
        val storage = ServerPlatformStorage(world.userDir())
        world.repo.updateAppSettings {
            it.copy(remoteSync = it.remoteSync.copy(enabled = true, serverBaseUrl = store.baseUrl, authToken = ALICE_TOKEN))
        }
        val attachments = UserAttachments(TEST_USERNAME, storage, world.repo, commitRetryDelaysMs = listOf(30L, 30L))
        try {
            runBlocking {
                val content = "never committed in time".toByteArray()
                val (file, digest) = attachments.receive(content)
                assertTrue(attachments.store.upload(file, digest).isOk)
                val sha = digest.sha256
                val attachment = Attachment(file_name = "f.txt", mime_type = "text/plain", sha256 = sha, size = digest.size)

                // The store is down for the save and for every retry of the commit
                store.failWith = 503
                val save = attachments.beginSave(listOf(attachment))
                assertTrue(save.missing.isEmpty(), "an unreachable store does not make a file missing")
                world.repo.addUserMessageAsNewNode(
                    TEST_USERNAME, world.chatId(), Message(id = "m-1", role = "user", text = "Q", attachments = listOf(attachment))
                )
                save.end()
                awaitTrue(what = "the commit to give up") { store.count("POST", sha) >= 3 }
                Thread.sleep(150)
                store.failWith = null
                assertFalse(store.files(TEST_USERNAME).getValue(sha).committed)

                // No save is in progress any more: the stored message is what keeps the file
                val outcome = attachments.deleteUnused(sha)
                assertIs<UnusedFileDelete.Kept>(outcome)
                assertEquals("in_use", outcome.state)
                awaitTrue(what = "the commit started by the refused delete") { store.files(TEST_USERNAME)[sha]?.committed == true }
                assertTrue(file.isFile)

                // A file nothing references is still deleted, here and in the store
                val (unusedFile, unused) = attachments.receive("a draft".toByteArray())
                assertTrue(attachments.store.upload(unusedFile, unused).isOk)
                assertIs<UnusedFileDelete.Deleted>(attachments.deleteUnused(unused.sha256))
                assertFalse(unusedFile.exists())
                assertNull(store.files(TEST_USERNAME)[unused.sha256])

                // A save that is given up lets its file go again
                val (draftFile, draft) = attachments.receive("held, then not saved".toByteArray())
                assertTrue(attachments.store.upload(draftFile, draft).isOk)
                store.beforeRequest = { method, hash -> if (method == "COMMIT" && hash == draft.sha256) Thread.sleep(300) }
                val abandoned = attachments.beginSave(listOf(Attachment(file_name = "d", mime_type = "text/plain", sha256 = draft.sha256)))
                abandoned.end()
                // Committed by then (a save committed it), so the store keeps it; nothing is lost either way
                awaitTrue(what = "the hold to end") {
                    val kept = runBlocking { attachments.deleteUnused(draft.sha256) }
                    kept is UnusedFileDelete.Kept && kept.state == "committed"
                }
            }
        } finally {
            attachments.close()
        }
    }
}
