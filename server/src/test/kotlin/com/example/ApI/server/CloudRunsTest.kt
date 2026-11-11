package com.example.ApI.server

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.ThoughtsStatus
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.data.repository.ReplyAnchor
import com.example.ApI.server.auth.FakeSyncWhoamiClient
import com.example.ApI.server.auth.SyncIdentity
import com.example.ApI.server.runs.RunRegistry
import com.example.ApI.server.runs.RunRequest
import com.example.ApI.server.runs.RunSpec
import com.example.ApI.server.runs.RunStatus
import com.example.ApI.server.runs.RunTools
import com.example.ApI.server.runs.attachmentNeedsFile
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolCallInfo
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Shared helpers of the cloud-run tests ─────────────────────────────────────

/**
 * Streams "Hel", waits for [gate], streams "lo" and completes with "Hello".  [started] completes
 * once "Hel" was streamed.
 */
internal class GatedEngine : ChatEngine {
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    val calls = AtomicInteger()

    fun awaitStarted() = runBlocking { withTimeout(10_000) { started.await() } }
    fun release() { gate.complete(Unit) }

    @Volatile
    var lastMessages: List<Message> = emptyList()

    override suspend fun send(
        provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
        username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
        callback: StreamingCallback
    ) {
        calls.incrementAndGet()
        lastMessages = messages
        callback.onPartialResponse("Hel")
        started.complete(Unit)
        gate.await()
        callback.onPartialResponse("lo")
        callback.onComplete("Hello")
    }
}

/** Answers at once with [reply]. */
internal class ReplyEngine(private val reply: String = "Cloud reply") : ChatEngine {
    val calls = AtomicInteger()

    override suspend fun send(
        provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
        username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
        callback: StreamingCallback
    ) {
        calls.incrementAndGet()
        callback.onPartialResponse(reply)
        callback.onComplete(reply)
    }
}

internal const val TEST_BEARER = "device-sync-token"

internal fun runBody(
    runId: String,
    chatId: String,
    messages: List<Message>,
    provider: String = "openai",
    enabledToolIds: List<String> = emptyList(),
    projectAttachments: List<Attachment> = emptyList()
): String = Json.encodeToString(
    RunRequest.serializer(),
    RunRequest(
        runId = runId, chatId = chatId, provider = provider, modelName = "gpt-4o", messages = messages,
        enabledToolIds = enabledToolIds, projectAttachments = projectAttachments
    )
)

/**
 * Cloud runs (CLOUD_RUNS_PLAN.md, stage S1): bearer authentication, POST /api/runs,
 * GET /api/runs/stream, POST /api/runs/{id}/cancel, the run registry, and the web's send route
 * on top of it.  Fake [ChatEngine]s and a fake whoami client, on a real local server
 * ([LiveServer]) so that streams can be read while open and connections really closed.
 */
class CloudRunsTest {

    private class Seed(val storage: ServerPlatformStorage, val repo: DataRepository, val chatId: String) {
        /** The chat's path (what a client sends as `messages`). */
        fun path(): List<Message> = chat().messages
        fun chat() = repo.loadChatHistory(TEST_USERNAME).chat_history.first { it.chat_id == chatId }
        fun texts(): List<String> = chat().messages.map { it.text }
        fun userDir(): File = File(storage.baseDir, "users/$TEST_USERNAME")
    }

    /** [TEST_USERNAME] with an active openai key and one chat holding the question "Q1". */
    private fun seed(question: Message = Message(role = "user", text = "Q1")): Seed {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "runs-test-${System.nanoTime()}").apply { mkdirs() }
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
        whoami: FakeSyncWhoamiClient = whoami(),
        allowedGoogleEmails: Set<String> = emptySet(),
        runPingIntervalMs: Long = 15_000L
    ) = LiveServer(seed.storage, engine, whoami, allowedGoogleEmails, runPingIntervalMs)

    private fun LiveClient.startRun(body: String): LiveResponse = post("/api/runs", body)

    private fun LiveClient.cancelRun(runId: String, keepPartial: Boolean): LiveResponse =
        post("/api/runs/$runId/cancel", """{"keepPartial":$keepPartial}""")

    /** The snapshot a new subscriber of the user-level stream gets. */
    private fun LiveClient.snapshot(): List<JsonObject> = sse("/api/runs/stream").use { stream ->
        assertEquals(200, stream.status)
        stream.expect("snapshot")["runs"]!!.jsonArray.map { it.jsonObject }
    }

    private fun LiveClient.awaitRunStatus(runId: String, status: String) =
        awaitTrue(what = "run $runId to be $status") {
            snapshot().any { it["runId"]!!.jsonPrimitive.content == runId && it["status"]!!.jsonPrimitive.content == status }
        }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun LiveResponse.rejectionCode(): String {
        assertEquals(409, status, body)
        return json().str("code")
    }

    // ── Bearer authentication ────────────────────────────────────────────────

    @Test
    fun `a valid sync bearer token authenticates api calls as its owner and is cached`() {
        val seed = seed()
        val whoami = whoami()
        live(seed, ReplyEngine(), whoami).use { server ->
            val client = server.client()
            val me = client.get("/api/me")
            assertEquals(200, me.status, me.body)
            assertEquals(TEST_USERNAME, me.json().str("username"))
            assertEquals(TEST_USER_EMAIL, me.json().str("email"))

            // The user's data, through the same per-user context as a cookie session
            val chats = client.get("/api/chats")
            assertEquals(200, chats.status)
            assertTrue(chats.body.contains("Cloud chat"))

            assertEquals(1, whoami.calls.size, "the token's identity is cached: ${whoami.calls.size} whoami calls")
        }
    }

    @Test
    fun `an unknown bearer token or no credentials at all get 401`() {
        val seed = seed()
        live(seed, ReplyEngine()).use { server ->
            val bad = server.client("not-a-token").get("/api/me")
            assertEquals(401, bad.status)
            assertTrue(bad.body.contains("unauthorized"))
            assertEquals(401, server.client(null).get("/api/me").status)
            assertEquals(401, server.client("not-a-token").post("/api/runs", "{}").status)
            assertEquals(401, server.client(null).get("/api/runs/stream").status)
            assertEquals(401, server.client(null).post("/api/runs/x/cancel").status)
        }
    }

    @Test
    fun `an Authorization header that is not a bearer token gets 401`() = testApplication {
        val seed = seed()
        startWithFakeGoogleAuth(seed.storage, syncWhoamiClient = whoami())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { header(HttpHeaders.Authorization, "Basic abc") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { header(HttpHeaders.Authorization, "Bearer ") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/me") { header(HttpHeaders.Authorization, "Bearer $TEST_BEARER") }.status)
    }

    @Test
    fun `a bearer token of an account outside the allowed emails gets 401`() {
        val seed = seed()
        val whoami = whoami().apply { identities["other-token"] = SyncIdentity("other_example_com", "other@example.com") }
        live(seed, ReplyEngine(), whoami, allowedGoogleEmails = setOf(TEST_USER_EMAIL)).use { server ->
            assertEquals(200, server.client().get("/api/me").status)
            assertEquals(401, server.client("other-token").get("/api/me").status)
            assertFalse(File(seed.storage.baseDir, "users/other_example_com").exists(), "no data dir for a rejected account")
        }
    }

    @Test
    fun `a session cookie still authenticates and wins over a bearer header`() = testApplication {
        val seed = seed()
        startWithFakeGoogleAuth(seed.storage, syncWhoamiClient = whoami())
        val c = googleLogin(TEST_USER_EMAIL)
        assertEquals(HttpStatusCode.OK, c.get("/api/me").status)
        assertEquals(HttpStatusCode.OK, c.get("/api/me") { header(HttpHeaders.Authorization, "Bearer not-a-token") }.status)
    }

    // ── POST /api/runs ───────────────────────────────────────────────────────

    @Test
    fun `starting a run is idempotent by run id`() {
        val seed = seed()
        val engine = GatedEngine()
        live(seed, engine).use { server ->
            val client = server.client()
            val body = runBody("run-1", seed.chatId, seed.path())

            val first = client.startRun(body)
            assertEquals(202, first.status, first.body)
            assertEquals("""{"runId":"run-1","status":"running"}""", first.body)
            engine.awaitStarted()

            val retry = client.startRun(body)
            assertEquals(200, retry.status)
            assertEquals("""{"runId":"run-1","status":"running"}""", retry.body)

            engine.release()
            client.awaitRunStatus("run-1", RunStatus.COMPLETE)

            // Also after it finished: never a second run, never a second reply
            val late = client.startRun(body)
            assertEquals(200, late.status)
            assertEquals("""{"runId":"run-1","status":"complete"}""", late.body)
            assertEquals(1, engine.calls.get())
            assertEquals(listOf("Q1", "Hello"), seed.texts())
        }
    }

    @Test
    fun `a run saves no user message and answers the request's last message`() {
        val seed = seed()
        val engine = GatedEngine().apply { release() }
        live(seed, engine).use { server ->
            val client = server.client()
            assertEquals(202, client.startRun(runBody("run-1", seed.chatId, seed.path())).status)
            client.awaitRunStatus("run-1", RunStatus.COMPLETE)

            assertEquals(listOf("Q1"), engine.lastMessages.map { it.text })
            val chat = seed.chat()
            assertEquals(listOf("user", "assistant"), chat.messages.map { it.role }, "no second user message")
            assertEquals("gpt-4o", chat.messages.last().model)
            assertEquals(1, chat.messageNodes.size)
            assertEquals(listOf("Hello"), chat.messageNodes.single().variants.single().responses.map { it.text })
        }
    }

    @Test
    fun `a malformed run request is a 400`() {
        val seed = seed()
        live(seed, ReplyEngine()).use { server ->
            val client = server.client()
            assertEquals(400, client.startRun("{}").status)
            assertEquals(400, client.startRun(runBody("", seed.chatId, seed.path())).status)
            assertEquals(400, client.startRun(runBody("run-1", seed.chatId, emptyList())).status)
        }
    }

    // ── 409: the cloud cannot run it ─────────────────────────────────────────

    @Test
    fun `409 unknown_provider`() {
        val seed = seed()
        val engine = ReplyEngine()
        live(seed, engine).use { server ->
            val client = server.client()
            val r = client.startRun(runBody("run-1", seed.chatId, seed.path(), provider = "does_not_exist"))
            assertEquals("unknown_provider", r.rejectionCode())
            assertTrue(r.json().str("error").isNotBlank())
            assertEquals(0, engine.calls.get())
            assertTrue(client.snapshot().isEmpty(), "a rejected request leaves no run")
        }
    }

    @Test
    fun `409 no_api_key when the server copy has no active key of the provider`() {
        val seed = seed()
        seed.repo.addApiKey(TEST_USERNAME, ApiKey(id = "k2", provider = "anthropic", key = "sk-ant", isActive = false))
        val engine = ReplyEngine()
        live(seed, engine).use { server ->
            val client = server.client()
            assertEquals("no_api_key", client.startRun(runBody("run-1", seed.chatId, seed.path(), provider = "anthropic")).rejectionCode())
            assertEquals("no_api_key", client.startRun(runBody("run-2", seed.chatId, seed.path(), provider = "google")).rejectionCode())
            assertEquals(0, engine.calls.get())
        }
    }

    @Test
    fun `409 unsupported_tools for a tool the server cannot execute`() {
        val seed = seed()
        val engine = ReplyEngine()
        live(seed, engine).use { server ->
            val client = server.client()
            val rejected = client.startRun(
                runBody("run-1", seed.chatId, seed.path(), enabledToolIds = listOf("get_date_time", "github_read_file"))
            )
            assertEquals("unsupported_tools", rejected.rejectionCode())
            assertTrue(rejected.body.contains("github_read_file"))
            assertEquals(0, engine.calls.get())

            // A tool the server has is fine
            val ok = client.startRun(runBody("run-2", seed.chatId, seed.path(), enabledToolIds = listOf("get_date_time")))
            assertEquals(202, ok.status, ok.body)
        }
    }

    @Test
    fun `409 attachments_unsupported for a device file and the history is left untouched`() {
        val deviceImage = Attachment(local_file_path = "/data/user/0/app/files/photo.png", file_name = "photo.png", mime_type = "image/png")
        val seed = seed(Message(role = "user", text = "Q1", attachments = listOf(deviceImage)))
        val engine = ReplyEngine()
        live(seed, engine).use { server ->
            val client = server.client()
            val before = seed.chat()

            val rejected = client.startRun(runBody("run-1", seed.chatId, seed.path()))
            assertEquals("attachments_unsupported", rejected.rejectionCode())
            assertTrue(rejected.body.contains("photo.png"))

            // A project attachment counts too
            val plain = seed.path().map { it.copy(attachments = emptyList()) }
            val project = client.startRun(runBody("run-2", seed.chatId, plain, projectAttachments = listOf(deviceImage)))
            assertEquals("attachments_unsupported", project.rejectionCode())

            assertEquals(0, engine.calls.get())
            assertEquals(before, seed.chat())
        }
    }

    @Test
    fun `a device attachment that already has the provider's file id runs in the cloud and is never rewritten`() {
        val uploaded = Attachment(
            local_file_path = "/data/user/0/app/files/photo.png", file_name = "photo.png",
            mime_type = "image/png", file_OPENAI_id = "file-abc"
        )
        val seed = seed(Message(role = "user", text = "Q1", attachments = listOf(uploaded)))
        live(seed, ReplyEngine()).use { server ->
            val client = server.client()
            val ok = client.startRun(runBody("run-1", seed.chatId, seed.path()))
            assertEquals(202, ok.status, ok.body)
            client.awaitRunStatus("run-1", RunStatus.COMPLETE)

            val chat = seed.chat()
            assertEquals(listOf("Q1", "Cloud reply"), chat.messages.map { it.text })
            assertEquals(listOf(uploaded), chat.messages.first().attachments, "the device's attachment is saved as it was")
            assertEquals(listOf(uploaded), chat.messageNodes.single().variants.single().userMessage.attachments)
        }
    }

    @Test
    fun `which attachments need their file follows what each provider reads`() {
        fun att(mime: String = "image/png", openai: String? = null, poe: String? = null, google: String? = null) =
            Attachment(local_file_path = "/data/user/0/app/files/x", file_name = "f", mime_type = mime,
                file_OPENAI_id = openai, file_POE_url = poe, file_GOOGLE_uri = google)

        // openai: images / PDFs go by file id, everything else is read and inlined
        assertFalse(attachmentNeedsFile("openai", att(openai = "file-1")))
        assertFalse(attachmentNeedsFile("openai", att(mime = "application/pdf", openai = "file-1")))
        assertTrue(attachmentNeedsFile("openai", att()))
        assertTrue(attachmentNeedsFile("openai", att(mime = "text/plain", openai = "file-1")))
        // poe / google: by uploaded url / uri
        assertFalse(attachmentNeedsFile("poe", att(poe = "https://poe/file")))
        assertTrue(attachmentNeedsFile("poe", att(openai = "file-1")))
        assertFalse(attachmentNeedsFile("google", att(google = "https://files/abc")))
        assertTrue(attachmentNeedsFile("google", att(google = "{file_URI}")))
        // providers that inline the bytes always need the file
        for (p in listOf("anthropic", "vertex", "cohere", "openrouter", "my_custom")) {
            assertTrue(attachmentNeedsFile(p, att(openai = "file-1", poe = "u", google = "g")), p)
        }
    }

    // ── The run outlives its watchers ────────────────────────────────────────

    @Test
    fun `a run survives the disconnect of its only subscriber and saves its reply`() {
        val seed = seed()
        val engine = GatedEngine()
        live(seed, engine).use { server ->
            val client = server.client()
            val stream = client.sse("/api/runs/stream")
            stream.expect("snapshot")
            assertEquals(202, client.startRun(runBody("run-1", seed.chatId, seed.path())).status)
            stream.expect("run_started")
            assertEquals("Hel", stream.expect("partial").str("text"))

            // The app goes away mid-stream
            stream.close()
            Thread.sleep(200)
            assertEquals(listOf("Q1"), seed.texts(), "nothing saved yet")

            engine.release()
            awaitTrue(what = "the reply to be saved") { seed.texts() == listOf("Q1", "Hello") }
            client.awaitRunStatus("run-1", RunStatus.COMPLETE)
        }
    }

    @Test
    fun `closing the response of the web's send does not stop its run`() {
        val seed = seed()
        val engine = GatedEngine()
        live(seed, engine).use { server ->
            val browser = server.login(TEST_USER_EMAIL)
            val sendBody = """{"chatId":"${seed.chatId}","provider":"openai","modelName":"gpt-4o","messages":[{"role":"user","text":"Q2","id":"q2"}]}"""

            val stream = browser.sse("/api/chat/send", method = "POST", body = sendBody)
            assertEquals(200, stream.status)
            assertTrue(stream.contentType.startsWith("text/event-stream"), stream.contentType)
            val runId = stream.expect("run").str("runId")  // the first event names the run
            assertTrue(runId.isNotBlank())
            assertEquals("Hel", stream.expect("partial").str("text"))

            // The browser tab is closed mid-stream
            stream.close()
            Thread.sleep(200)
            assertEquals(listOf("Q1", "Q2"), seed.texts())

            engine.release()
            awaitTrue(what = "the reply to be saved") { seed.texts() == listOf("Q1", "Q2", "Hello") }
            // The same run is visible on the user-level stream (of a device, here)
            server.client().awaitRunStatus(runId, RunStatus.COMPLETE)
        }
    }

    // ── GET /api/runs/stream ─────────────────────────────────────────────────

    @Test
    fun `a subscriber that connects mid-run gets the accumulated text in the snapshot then the live events`() {
        val seed = seed()
        val engine = GatedEngine()
        live(seed, engine).use { server ->
            val client = server.client()
            assertTrue(client.snapshot().isEmpty())

            assertEquals(202, client.startRun(runBody("run-1", seed.chatId, seed.path())).status)
            engine.awaitStarted()

            val stream = client.sse("/api/runs/stream")
            assertEquals(200, stream.status)
            assertTrue(stream.contentType.startsWith("text/event-stream"), stream.contentType)
            val run = stream.expect("snapshot")["runs"]!!.jsonArray.single().jsonObject
            assertEquals("run-1", run.str("runId"))
            assertEquals(seed.chatId, run.str("chatId"))
            assertEquals("openai", run.str("provider"))
            assertEquals("gpt-4o", run.str("modelName"))
            assertEquals("running", run.str("status"))
            assertEquals("Hel", run.str("text"), "what streamed before the subscriber connected")
            assertEquals(false, run["thinkingActive"]!!.jsonPrimitive.boolean)
            assertEquals("", run.str("thinkingText"))
            assertEquals(JsonNull, run["error"])
            assertTrue(run["startedAt"]!!.jsonPrimitive.long > 0)

            engine.release()
            // No event duplicated or lost between the snapshot and the live events
            val partial = stream.expect("partial")
            assertEquals("lo", partial.str("text"))
            assertEquals("run-1", partial.str("runId"))
            assertEquals(seed.chatId, partial.str("chatId"))

            val complete = stream.expect("complete")
            assertEquals("Hello", complete.str("text"))
            assertEquals("run-1", complete.str("runId"))
            // Emitted after the save: the reply is in the history, under the id the event names
            assertEquals(seed.chat().messages.last().id, complete.str("messageId"))

            // A finished run stays in the snapshot (for 10 minutes)
            val finished = client.snapshot().single()
            assertEquals("complete", finished.str("status"))
        }
    }

    @Test
    fun `two subscribers both receive a run's live events from run_started on`() {
        val seed = seed()
        val engine = GatedEngine()
        live(seed, engine).use { server ->
            // A device (bearer) and a browser tab (cookie) of the same account
            val device = server.client()
            val browser = server.login(TEST_USER_EMAIL)
            val streams = listOf(device.sse("/api/runs/stream"), browser.sse("/api/runs/stream"))
            streams.forEach { assertTrue(it.expect("snapshot")["runs"]!!.jsonArray.isEmpty()) }

            assertEquals(202, device.startRun(runBody("run-1", seed.chatId, seed.path())).status)
            engine.awaitStarted()
            engine.release()

            for (stream in streams) {
                val started = stream.expect("run_started")
                assertEquals("run-1", started["run"]!!.jsonObject.str("runId"))
                assertEquals("running", started["run"]!!.jsonObject.str("status"))
                assertEquals("run-1", started.str("runId"))
                assertEquals(seed.chatId, started.str("chatId"))
                assertEquals("Hel", stream.expect("partial").str("text"))
                assertEquals("lo", stream.expect("partial").str("text"))
                assertEquals("Hello", stream.expect("complete").str("text"))
            }
        }
    }

    @Test
    fun `thinking and tool events are streamed and messages_added comes after the tool messages are saved`() {
        val seed = seed()
        val gate = CompletableDeferred<Unit>()
        val engine = object : ChatEngine {
            override suspend fun send(
                provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
                username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
                enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
                callback: StreamingCallback
            ) {
                callback.onThinkingStarted()
                callback.onThinkingPartial("hmm")
                callback.onThinkingComplete("hmm", 1.5f, ThoughtsStatus.PRESENT)
                callback.onPartialResponse("Before")
                val call = ToolCall(id = "call-1", toolId = "get_date_time", parameters = buildJsonObject {}, provider = "openai")
                val result = callback.onToolCall(call, "Before")
                callback.onSaveToolMessages(
                    Message(
                        role = "tool_call", text = "date_time()",
                        toolCall = ToolCallInfo(
                            toolId = "date_time", toolName = "date_time", parameters = buildJsonObject {},
                            result = result, timestamp = java.time.Instant.now().toString()
                        )
                    ),
                    Message(role = "tool_response", text = "now", toolResponseCallId = "call-1", toolResponseOutput = "now"),
                    "Before"
                )
                gate.await()
                callback.onPartialResponse("After")
                callback.onComplete("After")
            }
        }
        live(seed, engine).use { server ->
            val client = server.client()
            val stream = client.sse("/api/runs/stream")
            stream.expect("snapshot")
            val started = client.startRun(runBody("run-1", seed.chatId, seed.path(), enabledToolIds = listOf("get_date_time")))
            assertEquals(202, started.status, started.body)

            stream.expect("run_started")
            stream.expect("thinking_started")
            assertEquals("hmm", stream.expect("thinking_partial").str("text"))
            val thinking = stream.expect("thinking_complete")
            assertEquals("hmm", thinking.str("thoughts"))
            assertEquals("PRESENT", thinking.str("status"))
            assertEquals(1.5, thinking["durationSeconds"]!!.jsonPrimitive.double)
            assertEquals("Before", stream.expect("partial").str("text"))
            val toolCall = stream.expect("tool_call")
            assertEquals("get_date_time", toolCall.str("toolId"))
            assertNotNull(toolCall["toolName"])
            assertNotNull(toolCall["parameters"])
            val toolResult = stream.expect("tool_result")
            assertEquals("get_date_time", toolResult.str("toolId"))
            assertEquals(true, toolResult["success"]!!.jsonPrimitive.boolean)
            assertNotNull(toolResult["output"])

            // messages_added carries nothing but the ids, and comes after the save: the preceding
            // text (with its thoughts) and the tool messages are in the history
            assertEquals(setOf("runId", "chatId"), stream.expect("messages_added").keys)
            val saved = seed.chat().messages
            assertEquals(listOf("user", "assistant", "tool_call", "tool_response"), saved.map { it.role })
            assertEquals("Before", saved[1].text)
            assertEquals("hmm", saved[1].thoughts)
            assertEquals(ThoughtsStatus.PRESENT, saved[1].thoughtsStatus)

            // …and the accumulated text starts over: a new subscriber sees only what is unsaved
            val midRun = client.snapshot().single()
            assertEquals("running", midRun.str("status"))
            assertEquals("", midRun.str("text"))
            assertEquals("", midRun.str("thinkingText"))

            gate.complete(Unit)
            assertEquals("After", stream.expect("partial").str("text"))
            assertEquals("After", stream.expect("complete").str("text"))
        }
        val finalMessages = seed.chat().messages
        assertEquals(listOf("Q1", "Before", "date_time()", "now", "After"), finalMessages.map { it.text })
        assertNull(finalMessages.last().thoughts, "the thoughts belong to the message they preceded")
    }

    @Test
    fun `an engine error ends the run with an error event and saves nothing`() {
        val seed = seed()
        val engine = object : ChatEngine {
            override suspend fun send(
                provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
                username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
                enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
                callback: StreamingCallback
            ) {
                callback.onPartialResponse("partial text")
                callback.onError("Provider exploded")
            }
        }
        live(seed, engine).use { server ->
            val client = server.client()
            val stream = client.sse("/api/runs/stream")
            stream.expect("snapshot")
            client.startRun(runBody("run-1", seed.chatId, seed.path()))
            assertEquals("Provider exploded", stream.nextOf("error").str("error"))

            val run = client.snapshot().single()
            assertEquals("error", run.str("status"))
            assertEquals("Provider exploded", run.str("error"))
            assertEquals(listOf("Q1"), seed.texts())
        }
    }

    @Test
    fun `an engine that throws or returns without an outcome ends the run with an error`() {
        val seed = seed()
        val engine = object : ChatEngine {
            override suspend fun send(
                provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
                username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
                enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
                callback: StreamingCallback
            ) {
                if (messages.last().text == "Q1") throw IllegalStateException("boom")
            }
        }
        live(seed, engine).use { server ->
            val client = server.client()
            client.startRun(runBody("run-1", seed.chatId, seed.path()))
            client.awaitRunStatus("run-1", RunStatus.ERROR)
            client.startRun(runBody("run-2", seed.chatId, seed.path().map { it.copy(text = "other") }))
            client.awaitRunStatus("run-2", RunStatus.ERROR)
            val errors = client.snapshot().associate { it.str("runId") to it.str("error") }
            assertEquals("boom", errors["run-1"])
            assertTrue(errors["run-2"]!!.isNotBlank())
            assertEquals(listOf("Q1"), seed.texts())
        }
    }

    @Test
    fun `an idle stream sends ping comments`() {
        val seed = seed()
        live(seed, ReplyEngine(), runPingIntervalMs = 50).use { server ->
            val stream = server.client().sse("/api/runs/stream")
            stream.expect("snapshot")
            awaitTrue(what = "two pings") { stream.lines.count { it == ": ping" } >= 2 }
            assertTrue(stream.lines.first().startsWith("event: snapshot"), "the snapshot comes first: ${stream.lines}")
        }
    }

    // ── POST /api/runs/{runId}/cancel ────────────────────────────────────────

    @Test
    fun `cancel with keepPartial saves the text streamed so far as the reply`() {
        val seed = seed()
        val engine = GatedEngine()
        live(seed, engine).use { server ->
            val client = server.client()
            val stream = client.sse("/api/runs/stream")
            stream.expect("snapshot")
            client.startRun(runBody("run-1", seed.chatId, seed.path()))
            assertEquals("Hel", stream.nextOf("partial").str("text"))

            val cancel = client.cancelRun("run-1", keepPartial = true)
            assertEquals(200, cancel.status, cancel.body)
            assertEquals("run-1", cancel.json().str("runId"))

            assertEquals(setOf("runId", "chatId"), stream.expect("cancelled").keys)
            // Emitted after the save
            assertEquals(listOf("Q1", "Hel"), seed.texts())
            val saved = seed.chat().messages.last()
            assertEquals("assistant", saved.role)
            assertEquals("gpt-4o", saved.model)
            assertEquals("cancelled", client.snapshot().single().str("status"))

            // Cancelling again changes nothing, and whatever the provider still sends is dropped
            assertEquals(200, client.cancelRun("run-1", keepPartial = false).status)
            engine.release()
            Thread.sleep(300)
            assertEquals(listOf("Q1", "Hel"), seed.texts())
            assertEquals("cancelled", client.snapshot().single().str("status"))
        }
    }

    @Test
    fun `cancel without keepPartial drops the text streamed so far`() {
        val seed = seed()
        val engine = GatedEngine()
        live(seed, engine).use { server ->
            val client = server.client()
            val stream = client.sse("/api/runs/stream")
            stream.expect("snapshot")
            client.startRun(runBody("run-1", seed.chatId, seed.path()))
            assertEquals("Hel", stream.nextOf("partial").str("text"))

            // An empty body means keepPartial = false
            val cancel = client.post("/api/runs/run-1/cancel")
            assertEquals(200, cancel.status, cancel.body)
            stream.expect("cancelled")

            engine.release()
            Thread.sleep(300)
            assertEquals(listOf("Q1"), seed.texts())
            assertEquals("cancelled", client.snapshot().single().str("status"))
        }
    }

    @Test
    fun `a cancelled run stops a provider that keeps streaming without suspending`() {
        val seed = seed()
        val chunks = AtomicInteger()
        val finished = CompletableDeferred<Throwable?>()
        // Like the real providers: a blocking read loop that never checks for cancellation
        val engine = object : ChatEngine {
            override suspend fun send(
                provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
                username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
                enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
                callback: StreamingCallback
            ) {
                try {
                    repeat(2_000) {
                        Thread.sleep(5)
                        callback.onPartialResponse("x")
                        chunks.incrementAndGet()
                    }
                    callback.onComplete("never")
                    finished.complete(null)
                } catch (t: Throwable) {
                    finished.complete(t)
                    throw t
                }
            }
        }
        live(seed, engine).use { server ->
            val client = server.client()
            val stream = client.sse("/api/runs/stream")
            stream.expect("snapshot")
            client.startRun(runBody("run-1", seed.chatId, seed.path()))
            stream.nextOf("partial")
            assertEquals(200, client.cancelRun("run-1", keepPartial = true).status)
            stream.nextOf("cancelled")

            val stoppedBy = runBlocking { withTimeout(5_000) { finished.await() } }
            assertTrue(stoppedBy is kotlinx.coroutines.CancellationException, "the provider loop was stopped: $stoppedBy")
            assertTrue(chunks.get() < 2_000)
            val saved = seed.texts()
            assertEquals(2, saved.size)
            assertTrue(saved.last().isNotEmpty() && saved.last().all { it == 'x' }, "the partial text was kept: ${saved.last().take(20)}")
            assertTrue(runCatching { stream.next(300) }.isFailure, "nothing after cancelled")
        }
    }

    @Test
    fun `cancel is 404 for an unknown run and 200 without effect for a finished one`() {
        val seed = seed()
        live(seed, ReplyEngine()).use { server ->
            val client = server.client()
            assertEquals(404, client.cancelRun("nope", keepPartial = true).status)

            client.startRun(runBody("run-1", seed.chatId, seed.path()))
            client.awaitRunStatus("run-1", RunStatus.COMPLETE)
            val cancel = client.cancelRun("run-1", keepPartial = true)
            assertEquals(200, cancel.status)
            assertEquals("""{"runId":"run-1","status":"complete"}""", cancel.body)
            assertEquals(listOf("Q1", "Cloud reply"), seed.texts())
            assertEquals("complete", client.snapshot().single().str("status"))
        }
    }

    @Test
    fun `a run of the web's send route is cancelled through its run id and its stream ends with cancelled`() {
        val seed = seed()
        val engine = GatedEngine()
        live(seed, engine).use { server ->
            val browser = server.login(TEST_USER_EMAIL)
            val sendBody = """{"chatId":"${seed.chatId}","provider":"openai","modelName":"gpt-4o","messages":[{"role":"user","text":"Q2","id":"q2"}]}"""
            val stream = browser.sse("/api/chat/send", method = "POST", body = sendBody)
            val runId = stream.expect("run").str("runId")
            // The per-request stream keeps its wire format: no ids in the data
            assertEquals(setOf("text"), stream.expect("partial").keys)

            assertEquals(200, browser.post("/api/runs/$runId/cancel", """{"keepPartial":true}""").status)
            assertEquals(JsonObject(emptyMap()), stream.expect("cancelled"))
            assertNull(stream.nextOrNull(), "the stream ends after the terminal event")
            assertEquals(listOf("Q1", "Q2", "Hel"), seed.texts())
        }
    }

    @Test
    fun `the web's send stream starts with the run event and keeps its wire format`() = testApplication {
        val seed = seed()
        val engine = GatedEngine().apply { release() }
        startWithFakeGoogleAuth(seed.storage, chatEngineFactory = { _ -> engine })
        val c = googleLogin(TEST_USER_EMAIL)
        val response = c.post("/api/chat/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"${seed.chatId}","provider":"openai","modelName":"gpt-4o","messages":[{"role":"user","text":"Q2","id":"q2"}]}""")
        }
        val frames = response.bodyAsText().trim().split("\n\n").map { frame ->
            val lines = frame.lines()
            lines[0].removePrefix("event: ") to Json.parseToJsonElement(lines[1].removePrefix("data: ")).jsonObject
        }
        assertEquals(listOf("run", "partial", "partial", "complete"), frames.map { it.first })
        assertEquals(setOf("runId"), frames[0].second.keys)
        assertEquals(buildJsonObject { put("text", "Hel") }, frames[1].second)
        assertEquals(setOf("text", "messageId"), frames[3].second.keys)
        assertEquals("Hello", frames[3].second.str("text"))
        assertEquals(seed.chat().messages.last().id, frames[3].second.str("messageId"))
    }

    @Test
    fun `runs of one user are invisible to another`() {
        val seed = seed()
        val engine = GatedEngine()
        val whoami = whoami().apply { identities["bob-token"] = SyncIdentity("bob_example_com", "bob@example.com") }
        live(seed, engine, whoami).use { server ->
            val alice = server.client()
            val bob = server.client("bob-token")
            val bobStream = bob.sse("/api/runs/stream")
            assertTrue(bobStream.expect("snapshot")["runs"]!!.jsonArray.isEmpty())

            alice.startRun(runBody("run-1", seed.chatId, seed.path()))
            engine.awaitStarted()
            assertTrue(bob.snapshot().isEmpty())
            assertEquals(404, bob.cancelRun("run-1", keepPartial = false).status)

            engine.release()
            alice.awaitRunStatus("run-1", RunStatus.COMPLETE)
            assertTrue(runCatching { bobStream.next(300) }.isFailure, "no event of another user's run")
        }
    }

    // ── RunRegistry: retention, shutdown ─────────────────────────────────────

    private fun registryContext(engine: ChatEngine): Pair<UserContext, Seed> {
        val seed = seed()
        val storage = ServerPlatformStorage(seed.userDir())
        val ctx = UserContext(TEST_USERNAME, storage, seed.repo, engine, TitleGenerator { _, _, _ -> "" })
        return ctx to seed
    }

    private fun spec(runId: String, seed: Seed): RunSpec {
        val provider = seed.repo.loadProviders().first { it.provider == "openai" }
        val messages = seed.path()
        return RunSpec(
            runId = runId, chatId = seed.chatId, provider = provider, modelName = "gpt-4o", messages = messages,
            systemPrompt = "", webSearchEnabled = false, enabledToolIds = emptyList(), tools = RunTools.builtIn(),
            thinkingBudget = ThinkingBudgetValue.None, temperature = null, projectAttachments = emptyList(),
            anchor = ReplyAnchor.forRequest(messages)
        )
    }

    @Test
    fun `a finished run is kept for the retention time and then forgotten`() = runBlocking {
        var clock = 1_000_000L
        val registry = RunRegistry(retentionMs = 600_000, now = { clock })
        val engine = ReplyEngine()
        val (ctx, seed) = registryContext(engine)

        val (state, started) = registry.start(ctx, spec("run-1", seed))
        assertTrue(started)
        assertEquals(RunStatus.RUNNING, state.status)
        awaitTrue(what = "the run to complete") { registry.find(TEST_USERNAME, "run-1")?.status == RunStatus.COMPLETE }

        clock += 599_000
        assertEquals(RunStatus.COMPLETE, registry.find(TEST_USERNAME, "run-1")?.status)
        assertFalse(registry.start(ctx, spec("run-1", seed)).second, "still known: not started again")
        val kept = registry.subscribe(TEST_USERNAME)
        assertEquals(listOf("run-1"), kept.snapshot.map { it.runId })
        kept.close()

        clock += 2_000
        assertNull(registry.find(TEST_USERNAME, "run-1"))
        val gone = registry.subscribe(TEST_USERNAME)
        assertTrue(gone.snapshot.isEmpty())
        gone.close()
        assertNull(registry.cancel(TEST_USERNAME, "run-1", keepPartial = true))
        assertEquals(1, engine.calls.get())
        registry.shutdown()
    }

    @Test
    fun `a running run is never forgotten however long it takes`() = runBlocking {
        var clock = 1_000_000L
        val registry = RunRegistry(retentionMs = 600_000, now = { clock })
        val engine = GatedEngine()
        val (ctx, seed) = registryContext(engine)
        registry.start(ctx, spec("run-1", seed))
        engine.awaitStarted()
        clock += 3_600_000
        assertEquals(RunStatus.RUNNING, registry.find(TEST_USERNAME, "run-1")?.status)
        engine.release()
        awaitTrue(what = "the run to complete") { registry.find(TEST_USERNAME, "run-1")?.status == RunStatus.COMPLETE }
        registry.shutdown()
    }

    @Test
    fun `shutdown stops the running runs keeping what streamed and ends the subscriptions`() = runBlocking {
        val registry = RunRegistry()
        val engine = GatedEngine()
        val (ctx, seed) = registryContext(engine)
        val subscription = registry.subscribe(TEST_USERNAME)
        registry.start(ctx, spec("run-1", seed))
        engine.awaitStarted()

        registry.shutdown()

        assertEquals(listOf("Q1", "Hel"), seed.texts(), "the partial reply is saved before the server stops")
        val events = mutableListOf<String>()
        for (event in subscription.events) events += event.type  // ends: the channel was closed
        assertEquals(listOf("run_started", "partial", "cancelled"), events)
        assertEquals(RunStatus.CANCELLED, registry.find(TEST_USERNAME, "run-1")?.status)
        assertTrue(runCatching { registry.start(ctx, spec("run-2", seed)) }.isFailure, "no run starts after shutdown")
    }

    @Test
    fun `a reply whose chat was deleted on the server meanwhile is dropped and the run still completes`() = runBlocking {
        val registry = RunRegistry()
        val engine = GatedEngine()
        val (ctx, seed) = registryContext(engine)
        registry.start(ctx, spec("run-1", seed))
        engine.awaitStarted()
        seed.repo.deleteChat(TEST_USERNAME, seed.chatId)
        engine.release()
        awaitTrue(what = "the run to complete") { registry.find(TEST_USERNAME, "run-1")?.status == RunStatus.COMPLETE }
        assertTrue(seed.repo.loadChatHistory(TEST_USERNAME).chat_history.none { it.chat_id == seed.chatId })
        registry.shutdown()
    }
}
