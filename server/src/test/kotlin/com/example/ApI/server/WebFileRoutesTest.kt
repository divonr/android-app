package com.example.ApI.server

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.auth.FakeSyncWhoamiClient
import com.example.ApI.server.auth.SyncIdentity
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web file routes (CLOUD_RUNS_PLAN.md, "Web file routes"): upload into the user's store,
 * serve by hash (safe content types, Range), commit, delete of a pending upload, the legacy
 * path delete, the size limit, isolation between users, and the commits done by the routes
 * that save a message / project attachment.
 */
class WebFileRoutesTest {

    private companion object {
        const val ALICE_TOKEN = "fake-sync-token-$TEST_USERNAME"   // what the fake login mints
        const val BOB_EMAIL = "bob@example.com"
        const val BOB = "bob_example_com"
        const val BOB_TOKEN = "fake-sync-token-$BOB"
        const val BOB_DEVICE_TOKEN = "bob-device-token"
    }

    private val store = FakeFileStore().start().also {
        // A user's web session and device are the same account of the store
        it.accounts[ALICE_TOKEN] = TEST_USERNAME
        it.accounts[TEST_BEARER] = TEST_USERNAME
        it.accounts[BOB_TOKEN] = BOB
        it.accounts[BOB_DEVICE_TOKEN] = BOB
    }

    @AfterTest
    fun tearDown() = store.stop()

    private class World(val storage: ServerPlatformStorage, val repo: DataRepository) {
        fun userDir(user: String = TEST_USERNAME) = File(storage.baseDir, "users/$user")
        fun cacheFile(sha: String, user: String = TEST_USERNAME) = File(userDir(user), "files/attachments_cache/$sha")
        fun chatId() = repo.loadChatHistory(TEST_USERNAME).chat_history.first().chat_id
    }

    private fun world(): World = seededStorageForUser().let { (storage, repo) -> World(storage, repo) }

    private fun whoami() = FakeSyncWhoamiClient().apply {
        identities[TEST_BEARER] = SyncIdentity(TEST_USERNAME, TEST_USER_EMAIL)
        identities[BOB_DEVICE_TOKEN] = SyncIdentity(BOB, BOB_EMAIL)
    }

    private fun live(world: World, maxFileBytes: Long = 95L * 1024 * 1024) =
        LiveServer(world.storage, ReplyEngine(), whoami(), syncServerUrl = store.baseUrl, maxFileBytes = maxFileBytes)

    private fun LiveResponse.str(key: String): String = json()[key]!!.jsonPrimitive.content

    private fun LiveClient.uploadOk(name: String, mime: String, content: ByteArray): String {
        val response = upload(name, mime, content)
        assertEquals(201, response.status, response.body)
        return response.str("sha256")
    }

    private fun java.net.http.HttpResponse<ByteArray>.header(name: String): String? = headers().firstValue(name).orElse(null)

    // ── Upload ───────────────────────────────────────────────────────────────

    @Test
    fun `upload streams the file into the user's store as pending and answers with its identity`() {
        val world = world()
        live(world).use { server ->
            val browser = server.login()
            val content = "Hello, world! This is a test file.".toByteArray()
            val response = browser.upload("hello.txt", "text/plain", content)
            assertEquals(201, response.status, response.body)

            val sha = FakeFileStore.sha256(content)
            val body = response.json()
            assertEquals("hello.txt", response.str("file_name"))
            assertEquals("text/plain", response.str("mime_type"))
            assertEquals(sha, response.str("sha256"))
            assertEquals(content.size.toLong(), body["size"]!!.jsonPrimitive.long)
            // No server path: it would mean nothing to the user's devices
            assertTrue(body["local_file_path"] == null || body["local_file_path"] == JsonNull)
            assertFalse(response.body.contains(world.storage.baseDir.path))

            val stored = store.files(TEST_USERNAME).getValue(sha)
            assertTrue(content.contentEquals(stored.bytes))
            assertFalse(stored.committed, "a new upload is pending until a message uses it")
            assertTrue(world.cacheFile(sha).isFile, "the server keeps the file in the user's cache")

            // The same file again: no second upload of the bytes
            assertEquals(sha, browser.uploadOk("copy.txt", "text/plain", content))
            assertEquals(1, store.count("PUT", sha))

            // A device's bearer token works on the same routes
            val fromDevice = server.client().uploadOk("phone.bin", "application/octet-stream", byteArrayOf(1, 2, 3))
            assertNotNull(store.files(TEST_USERNAME)[fromDevice])
        }
    }

    @Test
    fun `upload without a file is a 400 and a file over the limit a clear 413 that leaves nothing behind`() {
        val world = world()
        live(world, maxFileBytes = 1_000).use { server ->
            val browser = server.login()

            val empty = browser.upload("empty.txt", "text/plain", ByteArray(0))
            assertEquals(400, empty.status, empty.body)
            assertEquals("no_file", empty.str("code"))

            val big = browser.upload("big.bin", "application/octet-stream", ByteArray(5_000) { 7 })
            assertEquals(413, big.status, big.body)
            assertEquals("file_too_large", big.str("code"))
            assertEquals(1_000L, big.json()["maxBytes"]!!.jsonPrimitive.long)
            assertTrue(big.str("error").contains("maximum allowed size"), big.body)
            // Far over the limit: refused by its announced length, before it is read
            assertEquals(413, browser.upload("huge.bin", "application/octet-stream", ByteArray(3 * 1024 * 1024)).status)

            assertEquals(0, store.count("PUT"))
            val leftovers = File(world.userDir(), "files/attachments_cache").listFiles().orEmpty()
            assertTrue(leftovers.isEmpty(), "left behind: ${leftovers.toList()}")

            // Exactly at the limit is fine
            assertEquals(201, browser.upload("ok.bin", "application/octet-stream", ByteArray(1_000) { 7 }).status)
        }
    }

    @Test
    fun `an upload the store cannot take is an error and is not kept`() {
        val world = world()
        live(world).use { server ->
            val browser = server.login()
            store.failWith = 503
            val response = browser.upload("a.txt", "text/plain", "abc".toByteArray())
            assertEquals(502, response.status, response.body)
            assertEquals("store_unavailable", response.str("code"))
            assertFalse(world.cacheFile(FakeFileStore.sha256("abc".toByteArray())).exists())
        }
    }

    // ── Auth and isolation ───────────────────────────────────────────────────

    @Test
    fun `every file route needs authentication`() {
        val world = world()
        live(world).use { server ->
            val anonymous = server.client(token = null)
            val sha = "ab".repeat(32)
            assertEquals(401, anonymous.upload("a.txt", "text/plain", "abc".toByteArray()).status)
            assertEquals(401, anonymous.get("/api/files/$sha").status)
            assertEquals(401, anonymous.post("/api/files/$sha/commit").status)
            assertEquals(401, anonymous.delete("/api/files/$sha").status)
            assertEquals(401, anonymous.delete("/api/files", """{"sha256":"$sha"}""").status)
            assertEquals(401, server.client(token = "not-a-token").get("/api/files/$sha").status)
            assertEquals(0, store.requests.size)
        }
    }

    @Test
    fun `one user's files cannot be read, committed or deleted by another`() {
        val world = world()
        live(world).use { server ->
            val alice = server.login()
            val content = "alice's private notes".toByteArray()
            val sha = alice.uploadOk("notes.txt", "text/plain", content)

            // Bob, by cookie and by a device token: the hash is not a capability
            for (bob in listOf(server.login(BOB_EMAIL), server.client(BOB_DEVICE_TOKEN))) {
                val read = bob.bytes("GET", "/api/files/$sha")
                assertEquals(404, read.statusCode())
                assertFalse(String(read.body()).contains("private notes"))
                assertEquals(404, bob.post("/api/files/$sha/commit").status)
                val deleted = bob.delete("/api/files/$sha")
                assertEquals(200, deleted.status, deleted.body)
                assertEquals("false", deleted.str("deleted"))
                val legacy = bob.delete("/api/files", """{"filePath":"${world.cacheFile(sha).path}"}""")
                assertEquals(404, legacy.status, legacy.body)
            }
            assertTrue(store.files(BOB).isEmpty())
            assertFalse(world.cacheFile(sha, BOB).exists())

            // Alice still has her file, untouched and still pending
            val mine = alice.bytes("GET", "/api/files/$sha")
            assertEquals(200, mine.statusCode())
            assertTrue(content.contentEquals(mine.body()))
            assertFalse(store.files(TEST_USERNAME).getValue(sha).committed)

            // The same bytes uploaded by Bob are Bob's own copy
            val bob = server.login(BOB_EMAIL)
            assertEquals(sha, bob.uploadOk("same.txt", "text/plain", content))
            assertEquals(200, bob.delete("/api/files/$sha").let { assertEquals("true", it.str("deleted")); it.status })
            assertEquals(200, alice.bytes("GET", "/api/files/$sha").statusCode())
            assertNotNull(store.files(TEST_USERNAME)[sha])
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────

    @Test
    fun `a file is served with a safe content type, nosniff and the right disposition`() {
        val world = world()
        live(world).use { server ->
            val browser = server.login()
            val html = "<html><script>alert(document.cookie)</script></html>".toByteArray()
            val sha = browser.uploadOk("page.html", "text/html", html)

            fun get(query: String) = browser.bytes("GET", "/api/files/$sha$query").also { response ->
                assertEquals(200, response.statusCode())
                assertTrue(html.contentEquals(response.body()))
                assertEquals("nosniff", response.header("X-Content-Type-Options"))
                assertEquals("default-src 'none'; sandbox", response.header("Content-Security-Policy"))
            }

            // Anything a browser could run as a page of this origin is a download of opaque bytes
            for (mime in listOf("text/html", "image/svg%2Bxml", "application/xhtml%2Bxml", "text/xml", "application/javascript", "", "TEXT/HTML; charset=utf-8".replace(" ", "%20"))) {
                val response = get("?mime=$mime&name=page.html")
                assertEquals("application/octet-stream", response.header("Content-Type"), mime)
                assertTrue(response.header("Content-Disposition")!!.startsWith("attachment"), mime)
            }
            assertTrue(get("").header("Content-Disposition")!!.startsWith("attachment"))

            // Only the allowlisted image types are shown in place — as that type, never sniffed
            for (mime in listOf("image/png", "image/jpeg", "image/gif", "image/webp")) {
                val response = get("?mime=$mime&name=photo")
                assertEquals(mime, response.header("Content-Type"))
                assertTrue(response.header("Content-Disposition")!!.startsWith("inline"), mime)
            }
            assertTrue(get("?mime=image/png&download=1").header("Content-Disposition")!!.startsWith("attachment"))

            // Types a viewer / player needs keep their type, but as a download
            val pdf = get("?mime=application/pdf&name=doc.pdf")
            assertEquals("application/pdf", pdf.header("Content-Type"))
            assertTrue(pdf.header("Content-Disposition")!!.startsWith("attachment"))
            assertTrue(get("?mime=text/plain").header("Content-Type")!!.startsWith("text/plain"))

            // The name cannot break out of the header
            val named = get("?mime=text/html&name=" + java.net.URLEncoder.encode("a\"b\r\nX-Evil: 1/../שלום.html", Charsets.UTF_8))
            val disposition = named.header("Content-Disposition")!!
            assertFalse(disposition.contains("\r") || disposition.contains("\n"))
            assertNull(named.header("X-Evil"))
            assertTrue(disposition.startsWith("attachment; filename=\""), disposition)
            assertTrue(disposition.contains("filename*=UTF-8''"), disposition)

            assertEquals(400, browser.get("/api/files/not-a-hash").status)
            assertEquals(404, browser.get("/api/files/${"cd".repeat(32)}").status)
        }
    }

    @Test
    fun `a file that only the store has is fetched on demand and ranges are served`() {
        val world = world()
        live(world).use { server ->
            val content = "0123456789abcdef".toByteArray()
            // Attached on the phone: in the store, not on this server
            val sha = store.put(TEST_USERNAME, content)
            val browser = server.login()
            assertFalse(world.cacheFile(sha).exists())

            val whole = browser.bytes("GET", "/api/files/$sha?mime=image/png")
            assertEquals(200, whole.statusCode())
            assertTrue(content.contentEquals(whole.body()))
            assertEquals("bytes", whole.header("Accept-Ranges"))
            assertEquals("16", whole.header("Content-Length"))
            assertTrue(world.cacheFile(sha).isFile)
            browser.bytes("GET", "/api/files/$sha")
            assertEquals(1, store.count("GET", sha), "the second read is served from the user's cache")

            fun range(spec: String) = browser.bytes("GET", "/api/files/$sha", headers = mapOf("Range" to spec))
            val part = range("bytes=2-5")
            assertEquals(206, part.statusCode())
            assertEquals("2345", String(part.body()))
            assertEquals("bytes 2-5/16", part.header("Content-Range"))
            assertEquals("abcdef", String(range("bytes=10-").body()))
            assertEquals("def", String(range("bytes=-3").body()))
            assertEquals("bytes 13-15/16", range("bytes=-3").header("Content-Range"))
            assertEquals("cdef", String(range("bytes=12-999").body()))
            val beyond = range("bytes=16-20")
            assertEquals(416, beyond.statusCode())
            assertEquals("bytes */16", beyond.header("Content-Range"))
            // Several ranges are not supported: the whole file
            assertEquals(200, range("bytes=0-1,4-5").statusCode())
        }
    }

    // ── Commit and delete ────────────────────────────────────────────────────

    @Test
    fun `commit marks the file as used, is idempotent and uploads again a file the store lost`() {
        val world = world()
        live(world).use { server ->
            val browser = server.login()
            val content = "to be committed".toByteArray()
            val sha = browser.uploadOk("c.txt", "text/plain", content)

            repeat(2) {
                val committed = browser.post("/api/files/$sha/commit")
                assertEquals(200, committed.status, committed.body)
                assertEquals("committed", committed.str("state"))
                assertEquals(sha, committed.str("sha256"))
                assertEquals(content.size.toLong(), committed.json()["size"]!!.jsonPrimitive.long)
            }
            assertTrue(store.files(TEST_USERNAME).getValue(sha).committed)

            // The store lost the file (deleted while pending by another draft): the server has the bytes
            store.files(TEST_USERNAME).remove(sha)
            val again = browser.post("/api/files/$sha/commit")
            assertEquals(200, again.status, again.body)
            val restored = store.files(TEST_USERNAME).getValue(sha)
            assertTrue(restored.committed)
            assertTrue(content.contentEquals(restored.bytes))

            // Neither the store nor the server has it
            val unknown = browser.post("/api/files/${"ef".repeat(32)}/commit")
            assertEquals(404, unknown.status, unknown.body)
            assertEquals(400, browser.post("/api/files/XYZ/commit").status)
        }
    }

    @Test
    fun `delete removes a pending upload and never a committed file`() {
        val world = world()
        live(world).use { server ->
            val browser = server.login()
            val pending = browser.uploadOk("draft.txt", "text/plain", "a draft attachment".toByteArray())
            val used = browser.uploadOk("used.txt", "text/plain", "an attachment of a message".toByteArray())
            assertEquals(200, browser.post("/api/files/$used/commit").status)

            val deleted = browser.delete("/api/files/$pending")
            assertEquals(200, deleted.status, deleted.body)
            assertEquals("true", deleted.str("deleted"))
            assertNull(store.files(TEST_USERNAME)[pending])
            assertFalse(world.cacheFile(pending).exists())
            assertEquals(404, browser.get("/api/files/$pending").status)
            // Again: nothing to delete
            assertEquals("false", browser.delete("/api/files/$pending").str("deleted"))

            val kept = browser.delete("/api/files/$used")
            assertEquals(200, kept.status, kept.body)
            assertEquals("false", kept.str("deleted"))
            assertEquals("committed", kept.str("state"))
            assertTrue(store.files(TEST_USERNAME).getValue(used).committed)
            assertTrue(world.cacheFile(used).isFile)
            assertEquals(200, browser.get("/api/files/$used").status)

            // The legacy route with a hash does the same
            val legacyPending = browser.uploadOk("d2.txt", "text/plain", "another draft".toByteArray())
            assertEquals("true", browser.delete("/api/files", """{"sha256":"$legacyPending"}""").str("deleted"))
            assertEquals("false", browser.delete("/api/files", """{"sha256":"$used"}""").str("deleted"))
            assertEquals(400, browser.delete("/api/files", """{"sha256":"nope"}""").status)
            assertEquals(400, browser.delete("/api/files", "{}").status)
        }
    }

    @Test
    fun `the legacy path delete only ever deletes the user's own unused pre-store upload`() {
        val world = world()
        val outside = File(System.getProperty("java.io.tmpdir"), "victim-${System.nanoTime()}.txt").apply { writeText("keep me") }
        val keys = File(world.userDir(), "files/llm_data/api_keys_$TEST_USERNAME.json")
        val bobsUpload = File(world.userDir(BOB), "files/llm_data/attachments/bob.txt").apply { parentFile.mkdirs(); writeText("bob's") }
        val own = File(world.repo.saveFileLocally("to-delete.txt", "delete me".toByteArray())!!)
        val inUse = File(world.repo.saveFileLocally("in-use.txt", "used".toByteArray())!!)
        world.repo.addAttachmentToGroup(
            TEST_USERNAME, world.repo.loadChatHistory(TEST_USERNAME).groups.first().group_id,
            Attachment(local_file_path = inUse.path, file_name = "in-use.txt", mime_type = "text/plain")
        )
        live(world).use { server ->
            val browser = server.login()
            fun delete(file: File) = browser.delete("/api/files", """{"filePath":"${file.path}"}""")

            assertTrue(keys.isFile)
            for (victim in listOf(outside, keys, bobsUpload, File(own.parentFile, "../../../../../../${outside.name}"))) {
                assertEquals(404, delete(victim).status, victim.path)
                assertTrue(victim.exists(), "${victim.path} must survive")
            }
            assertEquals(409, delete(inUse).status)
            assertTrue(inUse.exists())

            assertEquals(204, delete(own).status)
            assertFalse(own.exists())
            assertEquals(404, delete(own).status)
        }
    }

    // ── Whoever saves a message / project attachment commits its files ───────

    @Test
    fun `the routes that save a message or a project attachment commit the referenced files`() {
        val world = world()
        live(world).use { server ->
            val browser = server.login()
            val chatId = world.chatId()
            val groupId = world.repo.loadChatHistory(TEST_USERNAME).groups.first().group_id
            fun upload(name: String) = browser.uploadOk(name, "text/plain", "content of $name".toByteArray())
            fun attachment(name: String, sha: String) = """{"file_name":"$name","mime_type":"text/plain","sha256":"$sha","size":1}"""
            fun awaitCommitted(sha: String, what: String) =
                awaitTrue(what = "$what to commit its file") { store.files(TEST_USERNAME)[sha]?.committed == true }

            // POST /api/chat/send
            val sent = upload("sent.txt")
            val sendBody = """{"chatId":"$chatId","provider":"openai","modelName":"gpt-4o","messages":[{"role":"user","text":"Q2","id":"q2",""" +
                """"attachments":[${attachment("sent.txt", sent)}]}]}"""
            browser.sse("/api/chat/send", method = "POST", body = sendBody).use { it.nextOf("complete") }
            awaitCommitted(sent, "the send route")

            // POST /api/chats/{id}/messages (multi-message mode)
            val added = upload("added.txt")
            val response = browser.post("/api/chats/$chatId/messages", """{"role":"user","text":"more","attachments":[${attachment("added.txt", added)}]}""")
            assertEquals(201, response.status, response.body)
            awaitCommitted(added, "the add-message route")

            // POST /api/groups/{id}/attachments
            val project = upload("project.txt")
            assertEquals(201, browser.post("/api/groups/$groupId/attachments", attachment("project.txt", project)).status)
            awaitCommitted(project, "the group attachment route")

            // A file the store lost before the message was saved is uploaded again from the server's copy
            val lost = upload("lost.txt")
            store.files(TEST_USERNAME).remove(lost)
            assertEquals(201, browser.post("/api/chats/$chatId/messages", """{"role":"user","text":"x","attachments":[${attachment("lost.txt", lost)}]}""").status)
            awaitCommitted(lost, "the add-message route (after a re-upload)")

            // The saved attachment is what the web sent: a hash, no server path
            val stored = world.repo.loadChatHistory(TEST_USERNAME).chat_history.first { it.chat_id == chatId }
                .messages.first { it.id == "q2" }.attachments.single()
            assertEquals(Attachment(file_name = "sent.txt", mime_type = "text/plain", sha256 = sent, size = 1), stored)

            // An upload that was never used stays pending
            val unused = upload("unused.txt")
            Thread.sleep(200)
            assertFalse(store.files(TEST_USERNAME).getValue(unused).committed)
        }
    }

    @Test
    fun `a commit that cannot reach the store is retried`() {
        val world = world()
        val storage = ServerPlatformStorage(world.userDir())
        world.repo.updateAppSettings {
            it.copy(remoteSync = it.remoteSync.copy(enabled = true, serverBaseUrl = store.baseUrl, authToken = ALICE_TOKEN))
        }
        val attachments = com.example.ApI.server.attachments.UserAttachments(
            TEST_USERNAME, storage, world.repo, commitRetryDelaysMs = listOf(50L, 50L)
        )
        try {
            val sha = store.put(TEST_USERNAME, "pending file".toByteArray(), committed = false)
            store.failWith = 503
            val jobs = attachments.commitInBackground(
                listOf(Attachment(file_name = "f", mime_type = "text/plain", sha256 = sha), Attachment(file_name = "no-hash", mime_type = "text/plain"))
            )
            assertEquals(1, jobs.size, "only attachments with a hash have a file to commit")
            awaitTrue(what = "the first attempt") { store.count("POST", sha) >= 1 }
            store.failWith = null
            awaitTrue(what = "the retried commit") { store.files(TEST_USERNAME).getValue(sha).committed }
        } finally {
            attachments.close()
        }
    }
}
