package com.example.ApI.server

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.auth.FakeSyncWhoamiClient
import com.example.ApI.server.auth.SyncIdentity
import com.example.ApI.server.runs.ProviderFileUploader
import com.example.ApI.server.runs.RunRegistry
import com.example.ApI.server.runs.RunStatus
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolCallInfo
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage A2 of CLOUD_RUNS_PLAN.md ("Attachment store"): cloud runs with files.  The run resolves
 * the attachments its provider reads from the user's store, gives the provider an in-flight copy
 * with this server's paths, writes back only provider ids (by hash) and never a server path;
 * a path sent by a client is never trusted; the Python tool runs on the server with the store.
 */
class AttachmentStoreRunsTest {

    private val store = FakeFileStore().start().also { it.accounts[TEST_BEARER] = TEST_USERNAME }
    private val executor = FakePythonExecutor().start()

    @AfterTest
    fun tearDown() {
        store.stop()
        executor.stop()
    }

    private class Seed(val storage: ServerPlatformStorage, val repo: DataRepository, val chatId: String) {
        val userDir = File(storage.baseDir, "users/$TEST_USERNAME")
        fun chat() = repo.loadChatHistory(TEST_USERNAME).chat_history.first { it.chat_id == chatId }
        fun path(): List<Message> = chat().messages

        /** The chat history exactly as it is stored (and synced). */
        fun storedJson(): String = File(userDir, "files/llm_data/chat_history_$TEST_USERNAME.json").readText()

        /** No path of this server appears anywhere in the synced chat history. */
        fun assertNoServerPath() {
            val json = storedJson()
            assertFalse(json.contains(storage.baseDir.path), "a server path leaked into the history:\n$json")
            assertFalse(json.contains("attachments_cache"), "a cache path leaked into the history:\n$json")
        }
    }

    private fun seed(question: Message): Seed {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "store-runs-${System.nanoTime()}").apply { mkdirs() }
        val repo = DataRepository(ServerPlatformStorage(File(baseDir, "users/$TEST_USERNAME")))
        repo.saveAppSettings(AppSettings(current_user = TEST_USERNAME, selected_provider = "openai", selected_model = "gpt-4o"))
        repo.addApiKey(TEST_USERNAME, ApiKey(id = "k1", provider = "openai", key = "sk-test-key", isActive = true))
        val chat = repo.createNewChat(TEST_USERNAME, "Cloud chat")
        repo.addUserMessageAsNewNode(TEST_USERNAME, chat.chat_id, question)
        return Seed(ServerPlatformStorage(baseDir), repo, chat.chat_id)
    }

    private fun whoami() = FakeSyncWhoamiClient().apply {
        identities[TEST_BEARER] = SyncIdentity(TEST_USERNAME, TEST_USER_EMAIL)
    }

    private fun live(
        seed: Seed,
        engine: ChatEngine,
        uploader: ProviderFileUploader = NO_PROVIDER_UPLOADS
    ) = LiveServer(
        seed.storage, engine, whoami(), syncServerUrl = store.baseUrl,
        providerFileUploader = uploader, pythonExecutorUrl = executor.url
    )

    private fun LiveClient.awaitRun(runId: String, status: String = RunStatus.COMPLETE) = awaitTrue(what = "run $runId to be $status") {
        sse("/api/runs/stream").use { stream ->
            stream.expect("snapshot")["runs"]!!.jsonArray.map { it.jsonObject }.any {
                it["runId"]!!.jsonPrimitive.content == runId && it["status"]!!.jsonPrimitive.content == status
            }
        }
    }

    private fun LiveResponse.assertAttachmentsUnsupported(vararg mentions: String) {
        assertEquals(409, status, body)
        assertEquals("attachments_unsupported", json()["code"]!!.jsonPrimitive.content)
        mentions.forEach { assertTrue(body.contains(it), "'$it' expected in: $body") }
    }

    /** What the engine was handed: every attachment and what could be read at its path. */
    private class FileReadingEngine : ChatEngine {
        class Seen(val attachment: Attachment, val content: String?)

        val calls = AtomicInteger()
        val seen = CopyOnWriteArrayList<Seen>()

        fun of(fileName: String): Seen = seen.last { it.attachment.file_name == fileName }

        override suspend fun send(
            provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
            username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
            enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
            callback: StreamingCallback
        ) {
            calls.incrementAndGet()
            (messages.flatMap { it.attachments } + projectAttachments).forEach { a ->
                seen += Seen(a, a.local_file_path?.let { File(it) }?.takeIf { it.isFile }?.readText())
            }
            callback.onPartialResponse("Done")
            callback.onComplete("Done")
        }
    }

    private val devicePath = "/data/user/0/com.example.ApI/files/attachments/notes.txt"

    // ── Files from the store ─────────────────────────────────────────────────

    @Test
    fun `a run whose attachment exists only in the store executes and the engine reads the file`() {
        val sha = store.put(TEST_USERNAME, "notes from the phone".toByteArray())
        val projectSha = store.put(TEST_USERNAME, "project brief".toByteArray())
        val attachment = Attachment(local_file_path = devicePath, file_name = "notes.txt", mime_type = "text/plain", sha256 = sha, size = 20)
        val project = Attachment(local_file_path = "/data/x/brief.txt", file_name = "brief.txt", mime_type = "text/plain", sha256 = projectSha, size = 13)
        val seed = seed(Message(role = "user", text = "Q1", attachments = listOf(attachment)))
        val engine = FileReadingEngine()
        live(seed, engine).use { server ->
            val client = server.client()
            val started = client.post("/api/runs", runBody("run-1", seed.chatId, seed.path(), projectAttachments = listOf(project)))
            assertEquals(202, started.status, started.body)
            client.awaitRun("run-1")

            // The provider's copy points at this server's file of the attachment…
            val seen = engine.of("notes.txt")
            assertEquals("notes from the phone", seen.content)
            assertTrue(File(seen.attachment.local_file_path!!).startsWith(File(seed.userDir, "files/attachments_cache")))
            assertEquals(sha, seen.attachment.sha256)
            assertEquals("project brief", engine.of("brief.txt").content)

            // …and the stored message is exactly what the device saved
            val chat = seed.chat()
            assertEquals(listOf("Q1", "Done"), chat.messages.map { it.text })
            assertEquals(listOf(attachment), chat.messages.first().attachments)
            assertEquals(listOf(attachment), chat.messageNodes.single().variants.single().userMessage.attachments)
            seed.assertNoServerPath()

            // A second run finds the file in the user's cache: no second download
            assertEquals(202, client.post("/api/runs", runBody("run-2", seed.chatId, seed.path().take(1))).status)
            client.awaitRun("run-2")
            assertEquals(1, store.count("GET", sha))
        }
    }

    @Test
    fun `provider ids the server obtains are written back by hash and no server path reaches the history`() {
        val sha = store.put(TEST_USERNAME, "png bytes".toByteArray())
        val groupSha = store.put(TEST_USERNAME, "pdf bytes".toByteArray())
        val image = Attachment(local_file_path = "/data/user/0/app/photo.png", file_name = "photo.png", mime_type = "image/png", sha256 = sha, size = 9)
        val doc = Attachment(local_file_path = "/data/user/0/app/spec.pdf", file_name = "spec.pdf", mime_type = "application/pdf", sha256 = groupSha, size = 9)
        val seed = seed(Message(role = "user", text = "Q1", attachments = listOf(image)))
        val group = seed.repo.createNewGroup(TEST_USERNAME, "Project")
        seed.repo.addChatToGroup(TEST_USERNAME, seed.chatId, group.group_id)
        seed.repo.addAttachmentToGroup(TEST_USERNAME, group.group_id, doc)

        val uploads = CopyOnWriteArrayList<String>()
        // Like the real uploads: the answer carries the path that was uploaded (a server path)
        val uploader = ProviderFileUploader { _, file, name, mime, _ ->
            uploads += "$name=${file.readText()}"
            Attachment(local_file_path = file.absolutePath, file_name = name, mime_type = mime, file_OPENAI_id = "file-srv-$name")
        }
        val engine = FileReadingEngine()
        live(seed, engine, uploader).use { server ->
            val client = server.client()
            val started = client.post("/api/runs", runBody("run-1", seed.chatId, seed.path(), projectAttachments = listOf(doc)))
            assertEquals(202, started.status, started.body)
            client.awaitRun("run-1")

            // The server uploaded the store's bytes and the request went out with the ids
            assertEquals(setOf("photo.png=png bytes", "spec.pdf=pdf bytes"), uploads.toSet())
            assertEquals("file-srv-photo.png", engine.of("photo.png").attachment.file_OPENAI_id)
            assertEquals("file-srv-spec.pdf", engine.of("spec.pdf").attachment.file_OPENAI_id)

            // The ids are on the stored attachments (path and tree; the group) — and nothing else changed
            val expected = image.copy(file_OPENAI_id = "file-srv-photo.png")
            val chat = seed.chat()
            assertEquals(listOf(expected), chat.messages.first().attachments)
            assertEquals(listOf(expected), chat.messageNodes.first().variants.single().userMessage.attachments)
            val storedGroup = seed.repo.loadChatHistory(TEST_USERNAME).groups.single { it.group_id == group.group_id }
            assertEquals(listOf(doc.copy(file_OPENAI_id = "file-srv-spec.pdf")), storedGroup.group_attachments)
            seed.assertNoServerPath()
            assertTrue(seed.storedJson().contains("file-srv-photo.png"))

            // The next request carries the ids: nothing is uploaded (or even fetched) again
            val again = client.post("/api/runs", runBody("run-2", seed.chatId, seed.path().take(1), projectAttachments = storedGroup.group_attachments))
            assertEquals(202, again.status, again.body)
            client.awaitRun("run-2")
            assertEquals(2, uploads.size)
            assertNull(engine.of("photo.png").attachment.local_file_path, "a file that is sent by id gets no path")
        }
    }

    @Test
    fun `ids of a file uploaded for a chat that has not synced yet are recorded once the reply restores it`() {
        val sha = store.put(TEST_USERNAME, "png bytes".toByteArray())
        val image = Attachment(local_file_path = "/data/user/0/app/photo.png", file_name = "photo.png", mime_type = "image/png", sha256 = sha, size = 9)
        val seed = seed(Message(role = "user", text = "Q1", attachments = listOf(image)))
        val path = seed.path()
        // The device created the chat a moment ago: the server copy does not have it yet
        seed.repo.updateChatHistory(TEST_USERNAME) { it.copy(chat_history = emptyList()) }

        val uploader = ProviderFileUploader { _, file, name, mime, _ ->
            Attachment(local_file_path = file.absolutePath, file_name = name, mime_type = mime, file_OPENAI_id = "file-srv-1")
        }
        live(seed, FileReadingEngine(), uploader).use { server ->
            val client = server.client()
            assertEquals(202, client.post("/api/runs", runBody("run-1", seed.chatId, path)).status)
            client.awaitRun("run-1")

            val chat = seed.chat()
            assertEquals(listOf("Q1", "Done"), chat.messages.map { it.text })
            assertEquals(listOf(image.copy(file_OPENAI_id = "file-srv-1")), chat.messages.first().attachments)
            assertEquals(listOf(image.copy(file_OPENAI_id = "file-srv-1")), chat.messageNodes.single().variants.single().userMessage.attachments)
            seed.assertNoServerPath()
        }
    }

    // ── A client's path is never trusted ─────────────────────────────────────

    @Test
    fun `a client-supplied path never makes the server read a file outside the user's attachment directories`() {
        val secret = File(System.getProperty("java.io.tmpdir"), "secret-${System.nanoTime()}.txt").apply { writeText("TOP SECRET") }
        val seed = seed(Message(role = "user", text = "Q1"))
        val otherUsersFile = File(seed.storage.baseDir, "users/bob_example_com/files/attachments_cache/x.txt")
            .apply { parentFile.mkdirs(); writeText("BOB'S FILE") }
        val ownKeys = File(seed.userDir, "files/llm_data/api_keys_$TEST_USERNAME.json")
        assertTrue(ownKeys.isFile)
        // Inside the user's attachment dir by name only: the link's target decides
        val link = File(seed.userDir, "files/llm_data/attachments/link.txt").apply { parentFile.mkdirs() }
        java.nio.file.Files.createSymbolicLink(link.toPath(), secret.toPath())
        val traversal = File(seed.userDir, "files/llm_data/attachments/../../../../../../" + secret.name)

        val uploaded = CopyOnWriteArrayList<String>()
        val uploader = ProviderFileUploader { _, file, _, _, _ -> uploaded += file.path; null }
        val engine = FileReadingEngine()
        live(seed, engine, uploader).use { server ->
            val client = server.client()
            fun question(path: String, sha: String? = null, mime: String = "text/plain") = seed.path().map {
                it.copy(attachments = listOf(Attachment(local_file_path = path, file_name = "evil.txt", mime_type = mime, sha256 = sha)))
            }

            // No hash: the path is all there is, and it is not the user's attachment file → the device runs it
            for ((i, path) in listOf(secret.path, otherUsersFile.path, ownKeys.path, link.path, traversal.path).withIndex()) {
                client.post("/api/runs", runBody("evil-$i", seed.chatId, question(path))).assertAttachmentsUnsupported("evil.txt")
                client.post("/api/runs", runBody("evil-img-$i", seed.chatId, question(path, mime = "image/png")))
                    .assertAttachmentsUnsupported("evil.txt")
                client.post("/api/runs", runBody("evil-prj-$i", seed.chatId, seed.path(), projectAttachments = question(path).first().attachments))
                    .assertAttachmentsUnsupported("evil.txt")
            }
            assertEquals(0, engine.calls.get())
            assertTrue(uploaded.isEmpty())

            // With a hash the store's file is used — never the file at the path
            val sha = store.put(TEST_USERNAME, "the real attachment".toByteArray())
            val ok = client.post("/api/runs", runBody("run-1", seed.chatId, question(secret.path, sha)))
            assertEquals(202, ok.status, ok.body)
            client.awaitRun("run-1")
            val seen = engine.of("evil.txt")
            assertEquals("the real attachment", seen.content)
            assertTrue(seen.attachment.local_file_path!!.endsWith("attachments_cache/$sha"))
            assertEquals(1, uploaded.size)
            assertTrue(uploaded.single().endsWith("attachments_cache/$sha"), "only the store's file is uploaded")
            // A file sent by reference is not read at all: its path is dropped, not followed
            val byId = question(secret.path, mime = "image/png").map { m -> m.copy(attachments = m.attachments.map { it.copy(file_OPENAI_id = "file-1") }) }
            assertEquals(202, client.post("/api/runs", runBody("run-2", seed.chatId, byId)).status)
            client.awaitRun("run-2")
            assertNull(engine.of("evil.txt").attachment.local_file_path)

            // The web's own send has no device to fall back to: the file is simply not sent
            val browser = server.login()
            val body = """{"chatId":"${seed.chatId}","provider":"openai","modelName":"gpt-4o","messages":[{"role":"user","text":"Q2","id":"q2",""" +
                """"attachments":[{"local_file_path":"${secret.path}","file_name":"web-evil.txt","mime_type":"text/plain"}]}]}"""
            browser.sse("/api/chat/send", method = "POST", body = body).use { stream ->
                assertEquals(200, stream.status)
                stream.nextOf("complete")
            }
            val web = engine.of("web-evil.txt")
            assertNull(web.attachment.local_file_path)
            assertNull(web.content)
            assertTrue(engine.seen.none { it.content == "TOP SECRET" || it.content == "BOB'S FILE" })
            assertEquals(1, uploaded.size)
        }
    }

    @Test
    fun `409 only for a needed file that is not on the server and has no hash or is not in the store`() {
        val seed = seed(Message(role = "user", text = "Q1"))
        val legacy = File(seed.userDir, "files/llm_data/attachments/web-upload.txt").apply { parentFile.mkdirs(); writeText("uploaded on the web") }
        val engine = FileReadingEngine()
        live(seed, engine).use { server ->
            val client = server.client()
            fun question(a: Attachment) = seed.path().map { it.copy(attachments = listOf(a)) }
            val before = seed.storedJson()

            // No hash and not on the server
            client.post("/api/runs", runBody("r1", seed.chatId, question(Attachment(local_file_path = devicePath, file_name = "notes.txt", mime_type = "text/plain"))))
                .assertAttachmentsUnsupported("notes.txt", "no hash")
            // A hash the store does not have (the device's upload has not finished)
            val missing = "ab".repeat(32)
            client.post("/api/runs", runBody("r2", seed.chatId, question(Attachment(local_file_path = devicePath, file_name = "notes.txt", mime_type = "text/plain", sha256 = missing))))
                .assertAttachmentsUnsupported("notes.txt", "store does not have it")
            // The store cannot be reached
            store.failWith = 503
            val sha = store.put(TEST_USERNAME, "x".toByteArray())
            client.post("/api/runs", runBody("r3", seed.chatId, question(Attachment(file_name = "notes.txt", mime_type = "text/plain", sha256 = sha))))
                .assertAttachmentsUnsupported("notes.txt")
            store.failWith = null
            assertEquals(0, engine.calls.get())
            assertEquals(before, seed.storedJson(), "a rejected request changes nothing")

            // A file the web saved in the user's own directory before the store existed still runs…
            val own = Attachment(local_file_path = legacy.path, file_name = "web-upload.txt", mime_type = "text/plain")
            seed.repo.updateChatHistory(TEST_USERNAME) { h ->
                h.copy(chat_history = h.chat_history.map { c ->
                    c.copy(
                        messages = c.messages.map { it.copy(attachments = listOf(own)) },
                        messageNodes = c.messageNodes.map { n ->
                            n.copy(variants = n.variants.map { v -> v.copy(userMessage = v.userMessage.copy(attachments = listOf(own))) })
                        }
                    )
                })
            }
            assertEquals(202, client.post("/api/runs", runBody("r4", seed.chatId, seed.path())).status)
            client.awaitRun("r4")
            assertEquals("uploaded on the web", engine.of("web-upload.txt").content)

            // …and is given its identity: hashed, stored, committed, recorded on the stored attachment
            val legacySha = FakeFileStore.sha256("uploaded on the web".toByteArray())
            awaitTrue(what = "the legacy upload to be backfilled") {
                seed.chat().messages.first().attachments.single().sha256 == legacySha
            }
            val stored = seed.chat().messages.first().attachments.single()
            assertEquals(own.copy(sha256 = legacySha, size = 19), stored)
            assertEquals(stored, seed.chat().messageNodes.first().variants.single().userMessage.attachments.single())
            assertTrue(store.files(TEST_USERNAME).getValue(legacySha).committed)
        }
    }

    // ── The Python tool ──────────────────────────────────────────────────────

    @Test
    fun `the python tool takes its input from the store and its generated file is stored, committed and attached by hash`() {
        val csv = "a,b\n1,2\n".toByteArray()
        val csvSha = store.put(TEST_USERNAME, csv)
        val chart = byteArrayOf(1, 2, 3, 4, 5, 0, -1, 13, 10)
        val chartSha = FakeFileStore.sha256(chart)
        executor.logs = "plotted"
        executor.artifacts = mapOf("chart.png" to chart)
        val input = Attachment(local_file_path = "/data/user/0/app/data.csv", file_name = "data.csv", mime_type = "text/csv", sha256 = csvSha, size = csv.size.toLong())
        // The file is already at the provider: the run itself needs no file, the tool does
        val seed = seed(Message(role = "user", text = "Plot it", attachments = listOf(input.copy(file_POE_url = "https://poe/f"))))
        seed.repo.addApiKey(TEST_USERNAME, ApiKey(id = "k2", provider = "poe", key = "poe-key", isActive = true))

        var description = ""
        val results = CopyOnWriteArrayList<ToolExecutionResult>()
        val engine = object : ChatEngine {
            override suspend fun send(
                provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
                username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
                enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
                callback: StreamingCallback
            ) {
                description = enabledTools.single { it.name == ToolRegistry.PYTHON_INTERPRETER }.description
                val parameters = buildJsonObject {
                    put("code", "import pandas")
                    put("files_to_include", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("data.csv")) })
                }
                val call = ToolCall(id = "call-1", toolId = ToolRegistry.PYTHON_INTERPRETER, parameters = parameters, provider = "poe")
                val result = callback.onToolCall(call, "")
                results += result
                callback.onSaveToolMessages(
                    Message(
                        role = "tool_call", text = "python",
                        toolCall = ToolCallInfo(
                            toolId = ToolRegistry.PYTHON_INTERPRETER, toolName = "Python", parameters = parameters,
                            result = result, timestamp = java.time.Instant.now().toString()
                        )
                    ),
                    Message(role = "tool_response", text = "plotted", toolResponseCallId = "call-1", toolResponseOutput = "plotted"),
                    ""
                )
                callback.onPartialResponse("Here is the chart")
                callback.onComplete("Here is the chart")
            }
        }
        live(seed, engine).use { server ->
            val client = server.client()
            val started = client.post(
                "/api/runs",
                runBody("run-1", seed.chatId, seed.path(), provider = "poe", enabledToolIds = listOf(ToolRegistry.PYTHON_INTERPRETER))
            )
            assertEquals(202, started.status, started.body)
            client.awaitRun("run-1")

            // The executor got the store's bytes of the input file
            assertTrue(description.contains("data.csv"), description)
            val call = executor.calls.single()
            assertEquals("import pandas", call.code)
            assertEquals(listOf("data.csv"), call.files.keys.toList())
            assertTrue(csv.contentEquals(call.files.getValue("data.csv")))

            // The generated file is in the user's store, committed
            val stored = store.files(TEST_USERNAME)[chartSha]
            assertNotNull(stored, "the generated file is in the store")
            assertTrue(stored.committed)
            assertTrue(chart.contentEquals(stored.bytes))

            // …and attached, by hash and size and without a path, to the next assistant message
            val messages = seed.chat().messages
            assertEquals(listOf("user", "tool_call", "tool_response", "assistant"), messages.map { it.role })
            val expected = Attachment(file_name = "chart.png", mime_type = "image/png", sha256 = chartSha, size = chart.size.toLong())
            assertEquals(listOf(expected), messages.last().attachments)
            assertEquals("Here is the chart", messages.last().text)
            assertTrue(messages[1].attachments.isEmpty() && messages[2].attachments.isEmpty())

            // The tool's result carries the hash too, and no path of this server is stored anywhere
            val details = (results.single() as ToolExecutionResult.Success).details!!
            val output = details["output_files"]!!.jsonArray.single().jsonObject
            assertEquals(chartSha, output["sha256"]!!.jsonPrimitive.content)
            assertEquals(chart.size.toString(), output["size"]!!.jsonPrimitive.content)
            assertNull(output["local_file_path"])
            seed.assertNoServerPath()
            assertTrue(seed.storedJson().contains(chartSha))

            // The web (or the phone) fetches it by its hash
            val fetched = client.bytes("GET", "/api/files/$chartSha?mime=image/png&name=chart.png")
            assertEquals(200, fetched.statusCode())
            assertTrue(chart.contentEquals(fetched.body()))
        }
    }

    @Test
    fun `generated files of a tool call that is not followed by text get their own message`() {
        val chart = "chart".toByteArray()
        executor.artifacts = mapOf("out.csv" to chart)
        val seed = seed(Message(role = "user", text = "Make a file"))
        val engine = object : ChatEngine {
            override suspend fun send(
                provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
                username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
                enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
                callback: StreamingCallback
            ) {
                repeat(2) { i ->
                    val parameters = buildJsonObject { put("code", "step $i") }
                    val result = callback.onToolCall(
                        ToolCall(id = "call-$i", toolId = ToolRegistry.PYTHON_INTERPRETER, parameters = parameters, provider = "openai"), ""
                    )
                    callback.onSaveToolMessages(
                        Message(
                            role = "tool_call", text = "python",
                            toolCall = ToolCallInfo(ToolRegistry.PYTHON_INTERPRETER, "Python", parameters, result, java.time.Instant.now().toString())
                        ),
                        Message(role = "tool_response", text = "ok", toolResponseCallId = "call-$i", toolResponseOutput = "ok"),
                        ""
                    )
                }
                callback.onComplete("")
            }
        }
        live(seed, engine).use { server ->
            val client = server.client()
            val started = client.post("/api/runs", runBody("run-1", seed.chatId, seed.path(), enabledToolIds = listOf(ToolRegistry.PYTHON_INTERPRETER)))
            assertEquals(202, started.status, started.body)
            client.awaitRun("run-1")

            // Like Android's service: the files wait for the next assistant message; with no text
            // it is a message of its own with the placeholder text
            val messages = seed.chat().messages
            assertEquals(
                listOf("user", "tool_call", "tool_response", "assistant", "tool_call", "tool_response", "assistant"),
                messages.map { it.role }
            )
            val sha = FakeFileStore.sha256(chart)
            for (index in listOf(3, 6)) {
                assertEquals(RunRegistry.ATTACHED_FILE_TEXT, messages[index].text)
                assertEquals(listOf(sha), messages[index].attachments.map { it.sha256 })
                assertEquals(listOf<String?>(null), messages[index].attachments.map { it.local_file_path })
            }
            seed.assertNoServerPath()
        }
    }
}
