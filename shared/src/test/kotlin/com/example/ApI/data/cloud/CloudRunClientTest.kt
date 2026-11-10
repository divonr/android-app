package com.example.ApI.data.cloud

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.ThoughtsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudRunClientTest {
    private lateinit var server: FakeCloudRunServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeEach
    fun setUp() {
        server = FakeCloudRunServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.stop()
    }

    private fun client(
        token: String = "tok-1",
        startTimeoutMs: Long = 5_000,
        backoff: (Int) -> Long = { 20 },
        streamReadTimeoutMs: Long = 5_000
    ) = CloudRunClient(
        config = { CloudRunConfig(server.baseUrl + "/", token) },
        startTimeoutMs = startTimeoutMs,
        streamReadTimeoutMs = streamReadTimeoutMs,
        backoffMs = backoff
    )

    private fun request(budget: ThinkingBudgetValue = ThinkingBudgetValue.None, temperature: Float? = null) = CloudRunRequest(
        runId = "run-1",
        chatId = "chat-1",
        provider = "openai",
        modelName = "gpt-x",
        messages = listOf(
            Message(id = "m1", role = "user", text = "שלום", nodeId = "n1", variantId = "v1"),
            Message(id = "m2", role = "assistant", text = "hi", model = "gpt-x", variantId = "v1"),
            Message(
                id = "m3", role = "user", text = "again", nodeId = "n2", variantId = "v2",
                attachments = listOf(Attachment(local_file_path = "/data/a.png", file_name = "a.png", mime_type = "image/png", file_OPENAI_id = "file-1"))
            )
        ),
        systemPrompt = "be brief",
        webSearchEnabled = true,
        enabledToolIds = listOf("get_date_time"),
        thinkingBudget = ThinkingBudgetWire.encode(budget),
        temperature = temperature,
        projectAttachments = listOf(Attachment(file_name = "p.pdf", mime_type = "application/pdf"))
    )

    // ── Start ─────────────────────────────────────────────────────────────────

    @Test
    fun `start posts the run with the bearer token and the stored path messages`() = runBlocking {
        val result = client().startRun(request(ThinkingBudgetValue.Effort("xhigh"), temperature = 0.3f))
        assertEquals(CloudStartResult.Accepted("running"), result)

        val sent = server.requests.single()
        assertEquals("POST", sent.method)
        assertEquals("/api/runs", sent.path)
        assertEquals("Bearer tok-1", sent.authorization)

        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals("run-1", body["runId"]!!.jsonPrimitive.content)
        assertEquals("chat-1", body["chatId"]!!.jsonPrimitive.content)
        assertEquals("openai", body["provider"]!!.jsonPrimitive.content)
        assertEquals("gpt-x", body["modelName"]!!.jsonPrimitive.content)
        assertEquals("be brief", body["systemPrompt"]!!.jsonPrimitive.content)
        assertEquals("true", body["webSearchEnabled"]!!.jsonPrimitive.content)
        assertEquals(listOf("get_date_time"), body["enabledToolIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("effort:xhigh", body["thinkingBudget"]!!.jsonPrimitive.content)
        assertEquals(0.3f, body["temperature"]!!.jsonPrimitive.content.toFloat())
        assertEquals("p.pdf", body["projectAttachments"]!!.jsonArray.single().jsonObject["file_name"]!!.jsonPrimitive.content)

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("m1", "m2", "m3"), messages.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("n1", "n2"), messages.filter { it["role"]!!.jsonPrimitive.content == "user" }.map { it["nodeId"]!!.jsonPrimitive.content })
        assertEquals(listOf("v1", "v1", "v2"), messages.map { it["variantId"]!!.jsonPrimitive.content })
        assertEquals("file-1", messages[2]["attachments"]!!.jsonArray.single().jsonObject["file_OPENAI_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the request body decodes back to the same request and a missing temperature is null`() {
        val request = request(ThinkingBudgetValue.Tokens(4096))
        val json = client().encodeRequest(request)
        assertEquals(JsonNull, Json.parseToJsonElement(json).jsonObject["temperature"])
        assertEquals("4096", Json.parseToJsonElement(json).jsonObject["thinkingBudget"]!!.jsonPrimitive.content)
        assertEquals(request, Json { ignoreUnknownKeys = true }.decodeFromString(CloudRunRequest.serializer(), json))
    }

    @Test
    fun `a run id the server already knows is accepted too`() = runBlocking {
        server.startResponse = 200 to """{"runId":"run-1","status":"complete"}"""
        assertEquals(CloudStartResult.Accepted("complete"), client().startRun(request()))
    }

    @Test
    fun `a rejection is an unambiguous fallback that names the code`() = runBlocking {
        server.startResponse = 409 to """{"error":"No active API key for openai on the server","code":"no_api_key"}"""
        val result = assertIs<CloudStartResult.Fallback>(client().startRun(request()))
        assertFalse(result.mayHaveStarted)
        assertTrue("no_api_key" in result.reason, result.reason)
    }

    @Test
    fun `auth and client errors are unambiguous, server errors are not`() = runBlocking {
        for ((code, ambiguous) in listOf(401 to false, 400 to false, 404 to false, 500 to true, 502 to true, 503 to true)) {
            server.startResponse = code to """{"error":"x"}"""
            val result = assertIs<CloudStartResult.Fallback>(client().startRun(request()), "HTTP $code")
            assertEquals(ambiguous, result.mayHaveStarted, "HTTP $code")
        }
    }

    @Test
    fun `a timeout after the request was sent is an ambiguous fallback`() = runBlocking {
        server.startDelayMs = 1_500
        val started = System.currentTimeMillis()
        val result = assertIs<CloudStartResult.Fallback>(client(startTimeoutMs = 300).startRun(request()))
        assertTrue(result.mayHaveStarted)
        assertTrue(System.currentTimeMillis() - started < 1_400, "the short timeout applies")
    }

    @Test
    fun `an unreachable server is an unambiguous fallback`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val client = CloudRunClient(config = { CloudRunConfig("http://127.0.0.1:$closedPort", "t") }, startTimeoutMs = 2_000)
        val result = assertIs<CloudStartResult.Fallback>(client.startRun(request()))
        assertFalse(result.mayHaveStarted)
    }

    @Test
    fun `no configuration or a bad url is an unambiguous fallback without a request`() = runBlocking {
        val none = assertIs<CloudStartResult.Fallback>(CloudRunClient(config = { null }).startRun(request()))
        assertFalse(none.mayHaveStarted)
        val bad = assertIs<CloudStartResult.Fallback>(CloudRunClient(config = { CloudRunConfig("not a url", "t") }).startRun(request()))
        assertFalse(bad.mayHaveStarted)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `failures are classified by whether the request can have reached the server`() {
        assertFalse(CloudRunClient.classifyFailure(ConnectException("refused")).mayHaveStarted)
        assertFalse(CloudRunClient.classifyFailure(UnknownHostException("host")).mayHaveStarted)
        assertTrue(CloudRunClient.classifyFailure(SocketTimeoutException("timeout")).mayHaveStarted)
        assertTrue(CloudRunClient.classifyFailure(java.io.IOException("stream was reset")).mayHaveStarted)
        assertTrue(CloudRunClient.classifyFailure(java.io.InterruptedIOException("timeout")).mayHaveStarted)

        assertIs<CloudStartResult.Accepted>(CloudRunClient.classifyResponse(202, """{"runId":"r","status":"running"}"""))
        assertEquals(CloudStartResult.Accepted("running"), CloudRunClient.classifyResponse(202, "not json"))
        assertFalse(assertIs<CloudStartResult.Fallback>(CloudRunClient.classifyResponse(409, "not json")).mayHaveStarted)
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Test
    fun `cancel posts keepPartial to the run and maps the answers`() = runBlocking {
        assertEquals(CloudCancelResult.OK, client().cancelRun("run 1", keepPartial = true))
        val sent = server.requests.single()
        assertEquals("POST", sent.method)
        assertEquals("/api/runs/run 1/cancel", sent.path)
        assertEquals("Bearer tok-1", sent.authorization)
        assertEquals("true", Json.parseToJsonElement(sent.body).jsonObject["keepPartial"]!!.jsonPrimitive.content)

        client().cancelRun("r", keepPartial = false)
        assertEquals("false", Json.parseToJsonElement(server.requests.last().body).jsonObject["keepPartial"]!!.jsonPrimitive.content)

        server.cancelResponse = 404 to """{"error":"Run not found"}"""
        assertEquals(CloudCancelResult.NOT_FOUND, client().cancelRun("r", keepPartial = false))
        server.cancelResponse = 500 to "{}"
        assertEquals(CloudCancelResult.FAILED, client().cancelRun("r", keepPartial = false))
        assertEquals(CloudCancelResult.FAILED, CloudRunClient(config = { null }).cancelRun("r", keepPartial = false))
    }

    // ── Event stream ──────────────────────────────────────────────────────────

    private class Collected(val job: Job, val events: LinkedBlockingQueue<CloudRunEvent>) {
        fun next(timeoutMs: Long = 5000): CloudRunEvent =
            events.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("No event within ${timeoutMs}ms")
    }

    private fun collect(client: CloudRunClient): Collected {
        val events = LinkedBlockingQueue<CloudRunEvent>()
        val job = scope.launch { client.events().collect { events.put(it) } }
        return Collected(job, events)
    }

    @Test
    fun `the stream delivers the snapshot and the live events, skipping pings and unknown events`() = runBlocking {
        val collected = collect(client())
        val stream = server.awaitStream()
        assertEquals("Bearer tok-1", server.requests.single().authorization)

        stream.event(
            "snapshot",
            """{"runs":[{"runId":"r0","chatId":"c0","provider":"openai","modelName":"m","status":"running","text":"so far","thinkingActive":true,"thinkingText":"hm","error":null,"startedAt":5}]}"""
        )
        val snapshot = assertIs<CloudRunEvent.Snapshot>(collected.next())
        assertEquals(
            CloudRunState("r0", "c0", "openai", "m", "running", "so far", thinkingActive = true, thinkingText = "hm", error = null, startedAt = 5),
            snapshot.runs.single()
        )

        stream.ping()
        stream.event("run_started", """{"run":{"runId":"r1","chatId":"c1","provider":"openai","modelName":"m","status":"running","startedAt":7},"runId":"r1","chatId":"c1"}""")
        stream.event("from_the_future", """{"runId":"r1","chatId":"c1"}""")
        stream.event("thinking_started", """{"runId":"r1","chatId":"c1"}""")
        stream.event("thinking_partial", """{"text":"let me","runId":"r1","chatId":"c1"}""")
        stream.event("thinking_complete", """{"thoughts":"let me see","durationSeconds":1.5,"status":"PRESENT","runId":"r1","chatId":"c1"}""")
        stream.event("partial", """{"text":"Hel","runId":"r1","chatId":"c1"}""")
        stream.ping()
        stream.event("tool_call", """{"toolId":"get_date_time","toolName":"Date","parameters":{},"runId":"r1","chatId":"c1"}""")
        stream.event("tool_result", """{"toolId":"get_date_time","success":true,"output":"now","runId":"r1","chatId":"c1"}""")
        stream.event("messages_added", """{"runId":"r1","chatId":"c1"}""")
        stream.event("partial", """{"text":"lo\nשלום","runId":"r1","chatId":"c1"}""")
        stream.event("complete", """{"text":"Hello","messageId":"msg-9","runId":"r1","chatId":"c1"}""")
        stream.event("error", """{"error":"boom","runId":"r2","chatId":"c2"}""")
        stream.event("cancelled", """{"runId":"r3","chatId":"c3"}""")

        assertEquals("r1", assertIs<CloudRunEvent.RunStarted>(collected.next()).run.runId)
        assertEquals(CloudRunEvent.ThinkingStarted("r1", "c1"), collected.next())
        assertEquals(CloudRunEvent.ThinkingPartial("r1", "c1", "let me"), collected.next())
        assertEquals(CloudRunEvent.ThinkingComplete("r1", "c1", "let me see", 1.5f, ThoughtsStatus.PRESENT), collected.next())
        assertEquals(CloudRunEvent.Partial("r1", "c1", "Hel"), collected.next())
        assertEquals(CloudRunEvent.ToolCall("r1", "c1", "get_date_time", "Date"), collected.next())
        assertEquals(CloudRunEvent.ToolResult("r1", "c1", "get_date_time", true), collected.next())
        assertEquals(CloudRunEvent.MessagesAdded("r1", "c1"), collected.next())
        assertEquals(CloudRunEvent.Partial("r1", "c1", "lo\nשלום"), collected.next())
        assertEquals(CloudRunEvent.Complete("r1", "c1", "Hello", "msg-9"), collected.next())
        assertEquals(CloudRunEvent.Error("r2", "c2", "boom"), collected.next())
        assertEquals(CloudRunEvent.Cancelled("r3", "c3"), collected.next())
        assertNull(collected.events.poll(150, TimeUnit.MILLISECONDS))

        collected.job.cancelAndJoin()
    }

    @Test
    fun `a lost connection is reported and reopened with a new snapshot`() = runBlocking {
        val collected = collect(client())
        val first = server.awaitStream()
        first.event("snapshot", """{"runs":[]}""")
        assertIs<CloudRunEvent.Snapshot>(collected.next())
        first.event("partial", """{"text":"a","runId":"r1","chatId":"c1"}""")
        assertIs<CloudRunEvent.Partial>(collected.next())

        first.close()
        assertEquals(CloudRunEvent.Disconnected, collected.next())

        val second = server.awaitStream()
        second.event("snapshot", """{"runs":[{"runId":"r1","chatId":"c1","status":"running","text":"ab","startedAt":1}]}""")
        assertEquals("ab", assertIs<CloudRunEvent.Snapshot>(collected.next()).runs.single().text)
        assertEquals(2, server.streamCount.get())

        collected.job.cancelAndJoin()
    }

    @Test
    fun `failed connections back off further until a snapshot arrives`() = runBlocking {
        val attempts = CopyOnWriteArrayList<Int>()
        server.failStreamStatus = 401
        server.failNextStreams.set(3)
        val collected = collect(client(backoff = { attempt -> attempts.add(attempt); 20 }))

        repeat(3) { assertEquals(CloudRunEvent.Disconnected, collected.next()) }
        val stream = server.awaitStream()
        stream.event("snapshot", """{"runs":[]}""")
        assertIs<CloudRunEvent.Snapshot>(collected.next())
        assertEquals(listOf(0, 1, 2), attempts.toList())

        // After a good connection the backoff starts over
        stream.close()
        assertEquals(CloudRunEvent.Disconnected, collected.next())
        server.awaitStream()
        assertEquals(listOf(0, 1, 2, 0), attempts.toList())

        collected.job.cancelAndJoin()
    }

    @Test
    fun `a silent connection is dropped and reopened`() = runBlocking {
        val collected = collect(client(streamReadTimeoutMs = 300))
        val first = server.awaitStream()
        first.event("snapshot", """{"runs":[]}""")
        assertIs<CloudRunEvent.Snapshot>(collected.next())
        // No ping: the read times out
        assertEquals(CloudRunEvent.Disconnected, collected.next())
        server.awaitStream()
        collected.job.cancelAndJoin()
    }

    @Test
    fun `cancelling the collection closes the connection and stops reconnecting`() = runBlocking {
        val collected = collect(client())
        val stream = server.awaitStream()
        stream.event("snapshot", """{"runs":[]}""")
        assertIs<CloudRunEvent.Snapshot>(collected.next())
        collected.job.cancelAndJoin()
        Thread.sleep(200)
        assertEquals(1, server.streamCount.get())
    }

    @Test
    fun `without a configuration the stream stays disconnected and retries`() = runBlocking {
        val configured = java.util.concurrent.atomic.AtomicBoolean(false)
        val client = CloudRunClient(
            config = { if (configured.get()) CloudRunConfig(server.baseUrl, "t") else null },
            backoffMs = { 20 }
        )
        val collected = collect(client)
        assertEquals(CloudRunEvent.Disconnected, collected.next())
        assertEquals(0, server.streamCount.get())
        configured.set(true)
        server.awaitStream().event("snapshot", """{"runs":[]}""")
        while (collected.next() !is CloudRunEvent.Snapshot) { /* earlier Disconnected events */ }
        collected.job.cancelAndJoin()
    }

    @Test
    fun `default backoff doubles up to thirty seconds`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..6).map { CloudRunClient.defaultBackoffMs(it) })
    }
}
