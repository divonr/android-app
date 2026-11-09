package com.example.ApI.data.network.providers

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.ApiRequest
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.PROJECT_ID_PLACEHOLDER
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.ResponseFields
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.VERTEX_PROVIDER
import com.example.ApI.data.model.withProjectIdFrom
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [GoogleProvider] against a local fake of the streaming endpoint; no real Google API is called. */
class GoogleProviderTest {

    private class Exchange(val path: String, val query: String?, val apiKeyHeader: String?, val body: JsonObject)

    private lateinit var server: HttpServer
    private val exchanges = CopyOnWriteArrayList<Exchange>()

    /** One SSE body per request, in order; the last repeats. */
    private var replies: List<String> = emptyList()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            exchanges += Exchange(
                exchange.requestURI.path,
                exchange.requestURI.query,
                exchange.requestHeaders.getFirst("x-goog-api-key"),
                Json.parseToJsonElement(body).jsonObject
            )
            val reply = replies[minOf(exchanges.size - 1, replies.size - 1)].toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun provider(name: String) = Provider(
        provider = name,
        models = emptyList(),
        request = ApiRequest(
            request_type = "POST",
            // AI Studio URLs have no project segment
            base_url = "http://127.0.0.1:${server.address.port}/v1/" +
                (if (name == VERTEX_PROVIDER) "projects/$PROJECT_ID_PLACEHOLDER/" else "") +
                "models/{model_name}:generateContent",
            headers = emptyMap()
        ),
        response_important_fields = ResponseFields()
    )

    private class Recorder : StreamingCallback {
        val calls = CopyOnWriteArrayList<ToolCall>()
        val errors = CopyOnWriteArrayList<String>()
        val completed = CopyOnWriteArrayList<String>()
        override fun onPartialResponse(text: String) {}
        override fun onComplete(fullText: String) { completed += fullText }
        override fun onError(error: String) { errors += error }
        override suspend fun onToolCall(toolCall: ToolCall, precedingText: String): ToolExecutionResult {
            calls += toolCall
            return ToolExecutionResult.Success("result of ${toolCall.toolId}")
        }
    }

    private fun send(platform: GooglePlatform, providerName: String, recorder: Recorder) = runBlocking {
        val p = provider(providerName).let {
            if (platform == GooglePlatform.AGENT_PLATFORM) {
                it.withProjectIdFrom(listOf(ApiKey(provider = VERTEX_PROVIDER, key = "FAKE-KEY", projectId = "fake-project")))
            } else it
        }
        GoogleProvider(platform).sendMessage(
            provider = p, modelName = "gemini-fake", messages = listOf(Message(role = "user", text = "hello")),
            systemPrompt = "", apiKey = "FAKE-KEY", webSearchEnabled = false, enabledTools = emptyList(),
            thinkingBudget = ThinkingBudgetValue.None, temperature = null, callback = recorder
        )
    }

    private val parallelCalls = "data: " + """{"candidates":[{"content":{"role":"model","parts":[
        {"functionCall":{"name":"tool_a","args":{"x":1}},"thoughtSignature":"SIG-A"},
        {"functionCall":{"name":"tool_b","args":{"y":2}}}]},"finishReason":"STOP"}]}""".replace("\n", "") + "\n\n"
    private val text = "data: " + """{"candidates":[{"content":{"parts":[{"text":"done"}]},"finishReason":"STOP"}]}""" + "\n\n"
    private val empty = "data: " + """{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}""" + "\n\n"

    private fun assertParallelReplay(responseRole: String) {
        val replay = exchanges[1].body.getValue("contents").jsonArray.map { it.jsonObject }
        // The user turn, one model turn with every call, one turn with every response
        assertEquals(3, replay.size, replay.toString())

        val calls = replay[1].getValue("parts").jsonArray.map { it.jsonObject }
        assertEquals("model", replay[1].getValue("role").jsonPrimitive.content)
        assertEquals(listOf("tool_a", "tool_b"), calls.map { it.getValue("functionCall").jsonObject.getValue("name").jsonPrimitive.content })
        // Only the first call of a parallel step carries the signature, exactly as it came
        assertEquals("SIG-A", calls[0]["thoughtSignature"]?.jsonPrimitive?.content)
        assertNull(calls[1]["thoughtSignature"])

        val responses = replay[2].getValue("parts").jsonArray.map { it.jsonObject.getValue("functionResponse").jsonObject }
        assertEquals(responseRole, replay[2].getValue("role").jsonPrimitive.content)
        assertEquals(listOf("tool_a", "tool_b"), responses.map { it.getValue("name").jsonPrimitive.content })
    }

    @Test
    fun `AI Studio runs every parallel call and replays them with the signature on the first`() {
        replies = listOf(parallelCalls, text)
        val r = Recorder()
        send(GooglePlatform.AI_STUDIO, "google", r)

        assertEquals(listOf("tool_a", "tool_b"), r.calls.map { it.toolId })
        assertEquals(2, r.calls.map { it.id }.toSet().size, "parallel calls need distinct ids")
        assertEquals("SIG-A", r.calls[0].thoughtSignature)
        assertEquals(listOf("done"), r.completed)
        assertEquals(emptyList(), r.errors)
        assertParallelReplay(responseRole = "function")
    }

    @Test
    fun `Agent Platform keeps replaying a parallel step the same way`() {
        replies = listOf(parallelCalls, text)
        val r = Recorder()
        send(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, r)

        assertEquals(listOf("tool_a", "tool_b"), r.calls.map { it.toolId })
        assertEquals(listOf("done"), r.completed)
        assertParallelReplay(responseRole = "user")
    }

    @Test
    fun `Agent Platform authenticates by header and uses the key's project`() {
        replies = listOf(text)
        val r = Recorder()
        send(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, r)

        assertEquals("/v1/projects/fake-project/models/gemini-fake:streamGenerateContent", exchanges.single().path)
        assertEquals("alt=sse", exchanges.single().query)
        assertEquals("FAKE-KEY", exchanges.single().apiKeyHeader)
        assertEquals(listOf("done"), r.completed)
    }

    @Test
    fun `an empty response ends the request with an error on both platforms`() {
        for ((platform, name) in listOf(GooglePlatform.AI_STUDIO to "google", GooglePlatform.AGENT_PLATFORM to VERTEX_PROVIDER)) {
            exchanges.clear()
            replies = listOf(empty)
            val r = Recorder()
            send(platform, name, r)

            assertEquals(emptyList(), r.completed)
            assertEquals(1, r.errors.size, r.errors.toString())
            assertTrue(r.errors.single().contains("no content"), r.errors.single())
            assertTrue(r.errors.single().contains("STOP"), r.errors.single())
        }
    }

    @Test
    fun `a blocked prompt names the block reason`() {
        replies = listOf("data: " + """{"promptFeedback":{"blockReason":"SAFETY"}}""" + "\n\n")
        val r = Recorder()
        send(GooglePlatform.AI_STUDIO, "google", r)

        assertEquals(emptyList(), r.completed)
        assertTrue(r.errors.single().contains("SAFETY"), r.errors.single())
    }

    @Test
    fun `an empty follow-up after a tool call is not reported as maximum depth`() {
        for ((platform, name) in listOf(GooglePlatform.AI_STUDIO to "google", GooglePlatform.AGENT_PLATFORM to VERTEX_PROVIDER)) {
            exchanges.clear()
            replies = listOf(parallelCalls, empty)
            val r = Recorder()
            send(platform, name, r)

            assertEquals(emptyList(), r.completed)
            assertTrue(r.errors.isNotEmpty())
            assertTrue(r.errors.none { it.contains("Maximum tool call depth") }, r.errors.toString())
            assertTrue(r.errors.last().contains("no content"), r.errors.toString())
        }
    }

    @Test
    fun `an unsigned call after an unsigned one stays its own step (Gemini 2_5)`() {
        val unsignedCall = "data: " + """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"tool_a","args":{}}},{"functionCall":{"name":"tool_b","args":{}}}]},"finishReason":"STOP"}]}""" + "\n\n"
        replies = listOf(unsignedCall, text)
        val r = Recorder()
        send(GooglePlatform.AI_STUDIO, "google", r)

        val replay = exchanges[1].body.getValue("contents").jsonArray.map { it.jsonObject }
        // user, tool_a, its response, tool_b, its response
        assertEquals(listOf("user", "model", "function", "model", "function"), replay.map { it.getValue("role").jsonPrimitive.content })
    }

    @Test
    fun `the project ID comes from the active key and other providers are left alone`() {
        val vertex = provider(VERTEX_PROVIDER)
        val keys = listOf(
            ApiKey(provider = VERTEX_PROVIDER, key = "OLD", isActive = false, projectId = "fake-old"),
            ApiKey(provider = VERTEX_PROVIDER, key = "NEW", projectId = " fake-new ")
        )
        assertTrue(vertex.withProjectIdFrom(keys).request.base_url.contains("/projects/fake-new/"))
        assertEquals(vertex, vertex.withProjectIdFrom(emptyList()))
        val other = provider("google")
        assertEquals(other, other.withProjectIdFrom(keys))
    }
}
