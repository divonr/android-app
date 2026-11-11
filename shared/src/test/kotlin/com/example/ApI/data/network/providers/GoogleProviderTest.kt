package com.example.ApI.data.network.providers

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.ApiRequest
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.PROJECT_ID_PLACEHOLDER
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.ResponseFields
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.TokenUsage
import com.example.ApI.data.model.VERTEX_PROVIDER
import com.example.ApI.data.model.withProjectIdFrom
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolCallInfo
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.util.AppLogger
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [GoogleProvider] against a local fake of the streaming endpoint; no real Google API is called. */
class GoogleProviderTest {

    private class Exchange(
        val path: String, val query: String?, val apiKeyHeader: String?, val transferEncoding: String?, val body: JsonObject
    )

    @TempDir
    lateinit var dir: File

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
                exchange.requestHeaders.getFirst("Transfer-Encoding"),
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
        val usages = CopyOnWriteArrayList<TokenUsage>()
        var failOnUsage = false
        override fun onUsage(usage: TokenUsage) {
            usages += usage
            if (failOnUsage) throw IllegalStateException("usage consumer failed")
        }
        override fun onPartialResponse(text: String) {}
        override fun onComplete(fullText: String) { completed += fullText }
        override fun onError(error: String) { errors += error }
        override suspend fun onToolCall(toolCall: ToolCall, precedingText: String): ToolExecutionResult {
            calls += toolCall
            return ToolExecutionResult.Success("result of ${toolCall.toolId}")
        }
    }

    private fun send(
        platform: GooglePlatform,
        providerName: String,
        recorder: Recorder,
        messages: List<Message> = listOf(Message(role = "user", text = "hello")),
        tools: List<ToolSpecification> = emptyList(),
        systemPrompt: String = "",
        modelName: String = "gemini-fake",
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None
    ) = runBlocking {
        val p = provider(providerName).let {
            if (platform == GooglePlatform.AGENT_PLATFORM) {
                it.withProjectIdFrom(listOf(ApiKey(provider = VERTEX_PROVIDER, key = "FAKE-KEY", projectId = "fake-project")))
            } else it
        }
        GoogleProvider(platform).sendMessage(
            provider = p, modelName = modelName, messages = messages,
            systemPrompt = systemPrompt, apiKey = "FAKE-KEY", webSearchEnabled = false, enabledTools = tools,
            thinkingBudget = thinkingBudget, temperature = null, callback = recorder
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

    // ---- Agent Platform prompt caching: usage read back, request prefix kept stable ----

    /** A reply as Agent Platform streams it: counts only in the last chunk, the traffic type in all. */
    private fun withUsage(lastChunk: String, usage: String): String =
        "data: " + """{"candidates":[{"content":{"parts":[{"text":"do"}]}}],"usageMetadata":{"trafficType":"ON_DEMAND"}}""" + "\n\n" +
            "data: " + lastChunk.removeSuffix("}") + ""","usageMetadata":$usage}""" + "\n\n"

    private val textChunk = """{"candidates":[{"content":{"parts":[{"text":"ne"}]},"finishReason":"STOP"}]}"""
    private val callChunk = """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"tool_a","args":{}},"thoughtSignature":"SIG-A"}]},"finishReason":"STOP"}]}"""
    private val cacheHit = """{"promptTokenCount":4436,"candidatesTokenCount":372,"totalTokenCount":4808,"cachedContentTokenCount":4043,"thoughtsTokenCount":12,"trafficType":"ON_DEMAND"}"""
    private val cacheMiss = """{"promptTokenCount":100,"candidatesTokenCount":5,"totalTokenCount":105,"trafficType":"ON_DEMAND"}"""

    private fun loggedUsage(modelName: String): List<String> =
        AppLogger.logs.value.map { it.message }.filter { it.startsWith("Vertex token usage: model=$modelName,") }

    @Test
    fun `Agent Platform reports the usage of the final chunk with its cached part`() {
        replies = listOf(withUsage(textChunk, cacheHit))
        val r = Recorder()
        send(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, r, modelName = "gemini-usage-hit")

        assertEquals(listOf(TokenUsage(promptTokens = 4436, cachedTokens = 4043, outputTokens = 372, thoughtsTokens = 12)), r.usages.toList())
        assertEquals(
            listOf("Vertex token usage: model=gemini-usage-hit, prompt=4436, cached=4043 (91%), output=372, thoughts=12"),
            loggedUsage("gemini-usage-hit")
        )
        assertEquals(listOf("done"), r.completed)
        assertEquals(emptyList(), r.errors)
    }

    @Test
    fun `a cache miss has no cached count and is reported as zero`() {
        replies = listOf(withUsage(textChunk, cacheMiss))
        val r = Recorder()
        send(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, r, modelName = "gemini-usage-miss")

        assertEquals(listOf(TokenUsage(promptTokens = 100, cachedTokens = 0, outputTokens = 5, thoughtsTokens = 0)), r.usages.toList())
        assertEquals(
            listOf("Vertex token usage: model=gemini-usage-miss, prompt=100, cached=0 (0%), output=5, thoughts=0"),
            loggedUsage("gemini-usage-miss")
        )
    }

    @Test
    fun `each request of a tool chain reports its own usage`() {
        replies = listOf(withUsage(callChunk, cacheMiss), withUsage(textChunk, cacheHit))
        val r = Recorder()
        send(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, r)

        assertEquals(2, exchanges.size)
        assertEquals(listOf(0, 4043), r.usages.map { it.cachedTokens })
        assertEquals(listOf(100, 4436), r.usages.map { it.promptTokens })
        assertEquals(listOf("done"), r.completed)
    }

    @Test
    fun `AI Studio does not report usage`() {
        replies = listOf(withUsage(callChunk, cacheMiss), withUsage(textChunk, cacheHit))
        val r = Recorder()
        send(GooglePlatform.AI_STUDIO, "google", r, modelName = "gemini-usage-studio")

        assertEquals(emptyList(), r.usages.toList())
        assertEquals(emptyList(), loggedUsage("gemini-usage-studio"))
        assertEquals(listOf("done"), r.completed)
    }

    @Test
    fun `usage is reported for a response that ends in an error and never changes the outcome`() {
        val blocked = """{"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}]}"""
        replies = listOf(withUsage(blocked, cacheMiss))
        val stopped = Recorder()
        send(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, stopped)
        assertEquals(1, stopped.usages.size)
        assertEquals(emptyList(), stopped.completed)
        assertTrue(stopped.errors.single().contains("SAFETY"), stopped.errors.toString())

        // A consumer that throws is the consumer's problem, not the request's
        exchanges.clear()
        replies = listOf(withUsage(textChunk, cacheHit))
        val failing = Recorder().apply { failOnUsage = true }
        send(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, failing)
        assertEquals(1, failing.usages.size)
        assertEquals(listOf("done"), failing.completed)
        assertEquals(emptyList(), failing.errors)
    }

    private fun tool(name: String, description: String = "Does $name", stableDescription: String? = null) = ToolSpecification(
        name = name,
        description = description,
        parameters = buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject { put("x", buildJsonObject { put("type", "STRING") }) })
        },
        stableDescription = stableDescription
    )

    private fun declarations(body: JsonObject): List<JsonObject> =
        body.getValue("tools").jsonArray.single().jsonObject.getValue("functionDeclarations").jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun `function declarations are sorted by name on Agent Platform and keep their order on AI Studio`() {
        replies = listOf(text)
        // Plain String order: upper case sorts before lower case
        val tools = listOf(tool("zeta"), tool("alpha"), tool("Mid"), tool("beta"))

        send(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, Recorder(), tools = tools)
        send(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, Recorder(), tools = tools.reversed())
        send(GooglePlatform.AI_STUDIO, "google", Recorder(), tools = tools)

        assertEquals(listOf("Mid", "alpha", "beta", "zeta"), declarations(exchanges[0].body).map { it.string("name") })
        assertEquals(exchanges[0].body.getValue("tools"), exchanges[1].body.getValue("tools"))
        assertEquals(listOf("zeta", "alpha", "Mid", "beta"), declarations(exchanges[2].body).map { it.string("name") })
    }

    @Test
    fun `Agent Platform declares the stable description and AI Studio the per-request one`() {
        replies = listOf(text)
        val tools = listOf(
            tool("python_interpreter", description = "Runs code. Available files: a.csv", stableDescription = "Runs code. Files are named where attached."),
            tool("get_date_time", description = "Tells the time")
        )

        send(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, Recorder(), tools = tools)
        send(GooglePlatform.AI_STUDIO, "google", Recorder(), tools = tools)

        // A tool without a stable description keeps the one it has
        assertEquals(
            mapOf("get_date_time" to "Tells the time", "python_interpreter" to "Runs code. Files are named where attached."),
            declarations(exchanges[0].body).associate { it.string("name") to it.string("description") }
        )
        assertEquals(
            listOf("Runs code. Available files: a.csv", "Tells the time"),
            declarations(exchanges[1].body).map { it.string("description") }
        )
    }

    private fun attachment(name: String, mimeType: String, content: String): Attachment {
        val file = File(dir, name).apply { writeText(content) }
        return Attachment(local_file_path = file.absolutePath, file_name = name, mime_type = mimeType)
    }

    private fun contents(exchange: Exchange): List<JsonObject> = exchange.body.getValue("contents").jsonArray.map { it.jsonObject }
    private fun parts(content: JsonObject): List<JsonObject> = content.getValue("parts").jsonArray.map { it.jsonObject }

    @Test
    fun `Agent Platform sends a user turn's files first, each named, and the text last`() {
        replies = listOf(text)
        val notes = attachment("notes.txt", "text/plain", "file body")
        val missing = Attachment(local_file_path = File(dir, "gone.pdf").absolutePath, file_name = "gone.pdf", mime_type = "application/pdf")
        send(
            GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, Recorder(),
            messages = listOf(Message(role = "user", text = "What is in the file?", attachments = listOf(notes, missing)))
        )

        val userParts = parts(contents(exchanges.single()).single())
        assertEquals(4, userParts.size, userParts.toString())
        assertEquals("""[Attached file: "notes.txt"]""", userParts[0].string("text"))
        val inline = userParts[1].getValue("inlineData").jsonObject
        assertEquals("text/plain", inline.string("mimeType"))
        assertEquals("file body", String(Base64.getDecoder().decode(inline.string("data"))))
        // A file that is not sent is already named by the note that says so
        assertEquals("""[Attachment "gone.pdf" was not sent: the file is not available on this device]""", userParts[2].string("text"))
        assertEquals("What is in the file?", userParts[3].string("text"))
        // A body with inline files is still streamed, not buffered
        assertEquals("chunked", exchanges.single().transferEncoding)
    }

    @Test
    fun `AI Studio keeps the text first and adds no file names`() {
        replies = listOf(text)
        val uploaded = Attachment(file_name = "notes.txt", mime_type = "text/plain", file_GOOGLE_uri = "https://files.example/abc")
        send(
            GooglePlatform.AI_STUDIO, "google", Recorder(),
            messages = listOf(Message(role = "user", text = "What is in the file?", attachments = listOf(uploaded)))
        )

        val userParts = parts(contents(exchanges.single()).single())
        assertEquals(2, userParts.size, userParts.toString())
        assertEquals("What is in the file?", userParts[0].string("text"))
        assertEquals("https://files.example/abc", userParts[1].getValue("file_data").jsonObject.string("file_uri"))
    }

    /** A chat with a tool step and attachments, ending in a user message with [lastText]. */
    private fun conversation(lastText: String): List<Message> = listOf(
        Message(role = "user", text = "Summarise the attached notes", attachments = listOf(attachment("notes.txt", "text/plain", "n".repeat(500)))),
        Message(role = "assistant", text = "Let me count the lines."),
        Message(
            role = "tool_call", text = "", toolCallId = "call-1",
            toolCall = ToolCallInfo(
                toolId = "python_interpreter", toolName = "python_interpreter",
                parameters = buildJsonObject { put("code", "print(1)") },
                result = ToolExecutionResult.Success("1"), timestamp = "2026-11-11T10:00:00Z", thoughtSignature = "SIG-1"
            )
        ),
        Message(role = "tool_response", text = "1", toolResponseCallId = "call-1", toolResponseOutput = "1"),
        Message(role = "assistant", text = "It has one line."),
        Message(role = "user", text = lastText, attachments = listOf(attachment("chart.png", "image/png", "not really a png")))
    )

    /** Tools as a request would get them: a Python-style description listing [files], in a given order. */
    private fun pythonStyleTools(files: String, order: List<String>): List<ToolSpecification> {
        val all = listOf(
            tool("python_interpreter", description = "Runs code.\nAvailable files: $files", stableDescription = "Runs code.\nFiles are named where attached."),
            tool("get_date_time"),
            tool("alpha_tool")
        ).associateBy { it.name }
        return order.map { all.getValue(it) }
    }

    @Test
    fun `the Agent Platform request prefix survives a new turn, an edited prompt, reordered tools and a changed file list`() {
        replies = listOf(text)
        val system = "You are a careful assistant."
        fun sendChat(messages: List<Message>, tools: List<ToolSpecification>) = send(
            GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER, Recorder(), messages = messages, tools = tools,
            systemPrompt = system, modelName = "gemini-3-fake", thinkingBudget = ThinkingBudgetValue.Effort("low")
        )

        val first = pythonStyleTools("notes.txt", listOf("python_interpreter", "get_date_time", "alpha_tool"))
        val later = pythonStyleTools("notes.txt, chart.png, out.csv", listOf("alpha_tool", "python_interpreter", "get_date_time"))
        assertNotEquals(first.first { it.name == "python_interpreter" }.description, later.first { it.name == "python_interpreter" }.description)

        sendChat(conversation("Now explain why"), first)
        // (i) the same chat, one turn later
        sendChat(conversation("Now explain why") + Message(role = "assistant", text = "Because.") + Message(role = "user", text = "And then?"), later)
        // (ii) the last prompt edited at its end
        sendChat(conversation("Now explain why, in one sentence"), later)

        val (base, nextTurn, edited) = exchanges.map { it.body }
        fun thinkingConfig(body: JsonObject): JsonElement = body.getValue("generationConfig").jsonObject.getValue("thinkingConfig")
        for (body in listOf(nextTurn, edited)) {
            assertEquals(base.getValue("systemInstruction"), body.getValue("systemInstruction"))
            assertEquals(base.getValue("tools"), body.getValue("tools"))
            assertEquals(thinkingConfig(base), thinkingConfig(body))
        }
        assertEquals("LOW", thinkingConfig(base).jsonObject.string("thinkingLevel"))

        val baseContents = contents(exchanges[0])
        // user (files, text), model (text, call), user (response), model, user (file, text)
        assertEquals(listOf("user", "model", "user", "model", "user"), baseContents.map { it.string("role") })

        // (i) everything already sent is the start of the next request
        val nextContents = contents(exchanges[1])
        assertEquals(baseContents.size + 2, nextContents.size)
        assertEquals(baseContents, nextContents.take(baseContents.size))

        // (ii) only the text of the edited turn differs, and it is that turn's last part
        val editedContents = contents(exchanges[2])
        assertEquals(baseContents.dropLast(1), editedContents.dropLast(1))
        val baseLast = parts(baseContents.last())
        val editedLast = parts(editedContents.last())
        assertEquals(3, baseLast.size, baseLast.toString())
        assertEquals("""[Attached file: "chart.png"]""", baseLast[0].string("text"))
        assertTrue("inlineData" in baseLast[1], baseLast[1].keys.toString())
        assertEquals(baseLast.dropLast(1), editedLast.dropLast(1))
        assertEquals("Now explain why", baseLast.last().string("text"))
        assertEquals("Now explain why, in one sentence", editedLast.last().string("text"))
    }

    @Test
    fun `a tool response kept only as text is replayed with that text on Agent Platform`() {
        // What the web's own list keeps of a stored tool step: role and text
        val messages = listOf(
            Message(role = "user", text = "plot it"),
            Message(role = "tool_call", text = ""),
            Message(role = "tool_response", text = "**Generated Files:**\n- chart.png"),
            Message(role = "user", text = "thanks")
        )
        fun result(platform: GooglePlatform, name: String): String {
            exchanges.clear()
            replies = listOf(text)
            send(platform, name, Recorder(), messages = messages)
            return exchanges.single().body.getValue("contents").jsonArray
                .flatMap { it.jsonObject.getValue("parts").jsonArray }
                .firstNotNullOf { it.jsonObject["functionResponse"]?.jsonObject }
                .getValue("response").jsonObject.getValue("result").jsonPrimitive.content
        }

        assertEquals("**Generated Files:**\n- chart.png", result(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER))
        assertEquals("", result(GooglePlatform.AI_STUDIO, "google"))
    }
}
