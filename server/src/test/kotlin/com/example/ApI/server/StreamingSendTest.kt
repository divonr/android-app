package com.example.ApI.server

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.ThoughtsStatus
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 3 tests for POST /api/chat/send SSE streaming.
 *
 * All tests use a [FakeChatEngine] that drives the [StreamingCallback] with a
 * scripted sequence — no real LLM network calls.  The test data dir is an isolated
 * temp directory, keeping tests hermetic.
 */
class StreamingSendTest {

    // ── Test infrastructure ──────────────────────────────────────────────────

    private val testPassword = "p3-test-password"
    private val testAuthConfig = AuthConfig(
        password = testPassword,
        sessionSecret = "p3-test-session-secret-that-is-long-enough"
    )

    /**
     * Seeds the "default" user's data in `users/default/` and returns
     * (rootStorage, perUserRepo, chatId).
     *
     * After Step 5a password login sets username = "default" and routes resolve
     * data via `registry.context("default")` → `users/default/`.  Seeding there
     * ensures the HTTP routes can see the test data.
     */
    private fun seededStorage(): Triple<ServerPlatformStorage, DataRepository, String> {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "p3-test-${System.nanoTime()}")
        baseDir.mkdirs()
        val rootStorage = ServerPlatformStorage(baseDir = baseDir)
        val userDir = File(baseDir, "users/default")
        userDir.mkdirs()
        val userStorage = ServerPlatformStorage(baseDir = userDir)
        val repo = DataRepository(userStorage)
        repo.saveAppSettings(
            AppSettings(
                current_user = "default",
                selected_provider = "fake",
                selected_model = "fake-model"
            )
        )
        val chat = repo.createNewChat("default", "Test Chat")
        return Triple(rootStorage, repo, chat.chat_id)
    }

    // ── FakeChatEngine implementations ───────────────────────────────────────

    /**
     * A [ChatEngine] that scripts a deterministic sequence:
     *   thinking_started → thinking_partial("deep thoughts") → thinking_complete
     *     → partial("Hel") → partial("lo") → complete("Hello")
     */
    private val fakeEngineSimple = object : ChatEngine {
        override suspend fun send(
            provider: Provider,
            modelName: String,
            messages: List<Message>,
            systemPrompt: String,
            username: String,
            chatId: String,
            projectAttachments: List<Attachment>,
            webSearchEnabled: Boolean,
            enabledTools: List<ToolSpecification>,
            thinkingBudget: ThinkingBudgetValue,
            temperature: Float?,
            callback: StreamingCallback
        ) {
            callback.onThinkingStarted()
            callback.onThinkingPartial("deep thoughts")
            callback.onThinkingComplete("deep thoughts", 1.5f, ThoughtsStatus.PRESENT)
            callback.onPartialResponse("Hel")
            callback.onPartialResponse("lo")
            callback.onComplete("Hello")
        }
    }

    /**
     * A [ChatEngine] that scripts a tool call:
     *   partial("Before") → onToolCall(date_time) → onSaveToolMessages → partial("After") → complete("BeforeAfter")
     *
     * NOTE: we use the built-in "date_time" tool (registered by default in ToolRegistry.init)
     * which doesn't need external auth.  However, in tests enabledToolIds is empty by default
     * so executeTool will return an error — that's fine, the SSE event still fires.
     */
    private val fakeEngineWithToolCall = object : ChatEngine {
        override suspend fun send(
            provider: Provider,
            modelName: String,
            messages: List<Message>,
            systemPrompt: String,
            username: String,
            chatId: String,
            projectAttachments: List<Attachment>,
            webSearchEnabled: Boolean,
            enabledTools: List<ToolSpecification>,
            thinkingBudget: ThinkingBudgetValue,
            temperature: Float?,
            callback: StreamingCallback
        ) {
            callback.onPartialResponse("Before")

            // Simulate a tool call
            val toolCall = ToolCall(
                id = "call-1",
                toolId = "date_time",
                parameters = buildJsonObject {},
                provider = "fake"
            )
            // Build fake tool messages (mirrors how providers create them)
            val toolCallMsg = Message(
                role = "tool_call",
                text = "date_time()",
                toolCall = com.example.ApI.tools.ToolCallInfo(
                    toolId = "date_time",
                    toolName = "date_time",
                    parameters = buildJsonObject {},
                    result = ToolExecutionResult.Success("2024-01-01T00:00:00Z"),
                    timestamp = java.time.Instant.now().toString()
                )
            )
            val toolResultMsg = Message(
                role = "tool_response",
                text = "2024-01-01T00:00:00Z",
                toolResponseCallId = "call-1",
                toolResponseOutput = "2024-01-01T00:00:00Z"
            )

            // onToolCall emits tool_call + tool_result SSE events
            callback.onToolCall(toolCall, "Before")
            callback.onSaveToolMessages(toolCallMsg, toolResultMsg, "Before")

            callback.onPartialResponse("After")
            callback.onComplete("BeforeAfter")
        }
    }

    /**
     * A [ChatEngine] that immediately calls [onError].
     */
    private val fakeEngineError = object : ChatEngine {
        override suspend fun send(
            provider: Provider,
            modelName: String,
            messages: List<Message>,
            systemPrompt: String,
            username: String,
            chatId: String,
            projectAttachments: List<Attachment>,
            webSearchEnabled: Boolean,
            enabledTools: List<ToolSpecification>,
            thinkingBudget: ThinkingBudgetValue,
            temperature: Float?,
            callback: StreamingCallback
        ) {
            callback.onError("Something went wrong")
        }
    }

    // ── Fake Provider registration helper ────────────────────────────────────

    /**
     * Returns a synthetic [Provider] named "fake" so the route resolver finds it.
     * We override [DataRepository.loadProviders] by using the real repo but ensuring
     * at least one provider named "fake" is present.  The simplest approach is to use
     * a subclass that overrides the provider lookup — but since DataRepository isn't
     * open, we install a custom provider config in the repository.
     *
     * Actually the cleanest path is: just seed a real OpenAI-compatible custom provider
     * OR register a provider called "fake" via full custom providers.  But even simpler:
     * the [SendRoute] resolves the provider from [loadProviders].  If the test request
     * specifies provider="fake" and we want to test routing without 400, we should inject
     * a custom provider.  However, the FakeChatEngine ignores the provider entirely.
     *
     * Simplest approach: use provider="openai" in the request body — openai is always
     * in the built-in providers list loaded from providers.json in the shared module.
     */
    private fun buildSendBody(
        chatId: String,
        provider: String = "openai",
        model: String = "gpt-4o",
        userMessage: String = "Hello",
        thinkingBudget: String = "none"
    ) = """
        {
            "chatId": "$chatId",
            "provider": "$provider",
            "modelName": "$model",
            "messages": [{"role":"user","text":"$userMessage","id":"msg-1"}],
            "systemPrompt": "",
            "webSearchEnabled": false,
            "enabledToolIds": [],
            "thinkingBudget": "$thinkingBudget",
            "temperature": null,
            "projectAttachments": []
        }
    """.trimIndent()

    // ── Helpers for SSE parsing ───────────────────────────────────────────────

    /** Parse an SSE stream body into a list of (event, data) pairs. */
    private fun parseSseEvents(body: String): List<Pair<String, JsonElement>> {
        val events = mutableListOf<Pair<String, JsonElement>>()
        var currentEvent: String? = null
        var currentData: String? = null

        for (line in body.lines()) {
            when {
                line.startsWith("event: ") -> currentEvent = line.removePrefix("event: ").trim()
                line.startsWith("data: ") -> currentData = line.removePrefix("data: ").trim()
                line.isBlank() && currentEvent != null && currentData != null -> {
                    val dataJson = try {
                        Json.parseToJsonElement(currentData!!)
                    } catch (e: Exception) {
                        JsonPrimitive(currentData!!)
                    }
                    events.add(currentEvent!! to dataJson)
                    currentEvent = null
                    currentData = null
                }
            }
        }
        return events
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `POST api chat send without auth returns 401`() = testApplication {
        val (storage, _, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val response = client.post("/api/chat/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"x","provider":"openai","modelName":"gpt-4o","messages":[]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST api chat send with unknown provider returns 400`() = testApplication {
        val (storage, _, chatId) = seededStorage()
        application { module(storage, testAuthConfig) { repo -> fakeEngineSimple } }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.post("/api/chat/send") {
            contentType(ContentType.Application.Json)
            setBody(buildSendBody(chatId, provider = "does_not_exist"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Unknown provider"))
    }

    @Test
    fun `POST api chat send streams correct SSE event sequence`() = testApplication {
        val (storage, repo, chatId) = seededStorage()
        application { module(storage, testAuthConfig) { _ -> fakeEngineSimple } }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.post("/api/chat/send") {
            contentType(ContentType.Application.Json)
            setBody(buildSendBody(chatId))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = response.contentType()
        assertNotNull(contentType)
        assertTrue(
            contentType!!.match(ContentType.parse("text/event-stream")),
            "Expected text/event-stream, got $contentType"
        )

        val body = response.bodyAsText()
        val events = parseSseEvents(body)

        // Assert event types in order
        val eventTypes = events.map { it.first }
        assertTrue(eventTypes.contains("thinking_started"), "Expected thinking_started: $eventTypes")
        assertTrue(eventTypes.contains("thinking_partial"), "Expected thinking_partial: $eventTypes")
        assertTrue(eventTypes.contains("thinking_complete"), "Expected thinking_complete: $eventTypes")

        val partialEvents = events.filter { it.first == "partial" }
        assertEquals(2, partialEvents.size, "Expected 2 partial events: $eventTypes")
        assertEquals("Hel", partialEvents[0].second.jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("lo", partialEvents[1].second.jsonObject["text"]?.jsonPrimitive?.content)

        assertTrue(eventTypes.contains("complete"), "Expected complete event: $eventTypes")
        val completeEvent = events.last { it.first == "complete" }
        assertEquals("Hello", completeEvent.second.jsonObject["text"]?.jsonPrimitive?.content)
        assertNotNull(completeEvent.second.jsonObject["messageId"])

        // Verify order: thinking_started → thinking_partial → thinking_complete → partial × 2 → complete
        val thinkingStartedIdx = eventTypes.indexOf("thinking_started")
        val thinkingPartialIdx = eventTypes.indexOf("thinking_partial")
        val thinkingCompleteIdx = eventTypes.indexOf("thinking_complete")
        val firstPartialIdx = eventTypes.indexOfFirst { it == "partial" }
        val completeIdx = eventTypes.lastIndexOf("complete")

        assertTrue(thinkingStartedIdx < thinkingPartialIdx, "thinking_started must precede thinking_partial")
        assertTrue(thinkingPartialIdx < thinkingCompleteIdx, "thinking_partial must precede thinking_complete")
        assertTrue(thinkingCompleteIdx < firstPartialIdx, "thinking_complete must precede first partial")
        assertTrue(firstPartialIdx < completeIdx, "partial must precede complete")
    }

    @Test
    fun `POST api chat send thinking_complete has correct fields`() = testApplication {
        val (storage, _, chatId) = seededStorage()
        application { module(storage, testAuthConfig) { _ -> fakeEngineSimple } }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.post("/api/chat/send") {
            contentType(ContentType.Application.Json)
            setBody(buildSendBody(chatId))
        }
        val events = parseSseEvents(response.bodyAsText())
        val tc = events.first { it.first == "thinking_complete" }.second.jsonObject
        assertEquals("deep thoughts", tc["thoughts"]?.jsonPrimitive?.content)
        assertEquals("PRESENT", tc["status"]?.jsonPrimitive?.content)
        assertNotNull(tc["durationSeconds"])
    }

    @Test
    fun `POST api chat send persists assistant message after complete`() = testApplication {
        val (storage, repo, chatId) = seededStorage()
        application { module(storage, testAuthConfig) { _ -> fakeEngineSimple } }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        cookieClient.post("/api/chat/send") {
            contentType(ContentType.Application.Json)
            setBody(buildSendBody(chatId))
        }

        // Load chat and verify assistant message was persisted
        val chat = repo.loadChatHistory("default").chat_history.find { it.chat_id == chatId }
        assertNotNull(chat, "Chat should still exist")
        val assistantMessages = chat!!.messages.filter { it.role == "assistant" }
        assertTrue(assistantMessages.isNotEmpty(), "Expected at least one assistant message to be persisted")
        assertEquals("Hello", assistantMessages.last().text, "Expected persisted text = 'Hello'")
    }

    @Test
    fun `POST api chat send tool call emits tool_call and tool_result events`() = testApplication {
        val (storage, repo, chatId) = seededStorage()
        application { module(storage, testAuthConfig) { _ -> fakeEngineWithToolCall } }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.post("/api/chat/send") {
            contentType(ContentType.Application.Json)
            setBody(buildSendBody(chatId))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val events = parseSseEvents(response.bodyAsText())
        val eventTypes = events.map { it.first }

        assertTrue(eventTypes.contains("tool_call"), "Expected tool_call event: $eventTypes")
        assertTrue(eventTypes.contains("tool_result"), "Expected tool_result event: $eventTypes")
        assertTrue(eventTypes.contains("complete"), "Expected complete event: $eventTypes")

        // Verify tool_call shape
        val toolCallEvent = events.first { it.first == "tool_call" }.second.jsonObject
        assertNotNull(toolCallEvent["toolId"])
        assertNotNull(toolCallEvent["toolName"])
        assertNotNull(toolCallEvent["parameters"])

        // Verify tool_result shape
        val toolResultEvent = events.first { it.first == "tool_result" }.second.jsonObject
        assertNotNull(toolResultEvent["toolId"])
        assertNotNull(toolResultEvent["success"])
        assertNotNull(toolResultEvent["output"])

        // Verify order: tool_call before tool_result
        assertTrue(
            eventTypes.indexOf("tool_call") < eventTypes.indexOf("tool_result"),
            "tool_call must come before tool_result"
        )

        // Verify tool messages persisted (onSaveToolMessages was called)
        val chat = repo.loadChatHistory("default").chat_history.find { it.chat_id == chatId }
        val allMessages = chat?.messages ?: emptyList()
        // Should have the user message + tool_call message + tool_response + assistant
        assertTrue(allMessages.any { it.role == "tool_call" }, "Expected tool_call message in history")
        assertTrue(allMessages.any { it.role == "tool_response" }, "Expected tool_response message in history")
    }

    @Test
    fun `POST api chat send error fires error event`() = testApplication {
        val (storage, _, chatId) = seededStorage()
        application { module(storage, testAuthConfig) { _ -> fakeEngineError } }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.post("/api/chat/send") {
            contentType(ContentType.Application.Json)
            setBody(buildSendBody(chatId))
        }
        assertEquals(HttpStatusCode.OK, response.status)  // SSE stream was opened

        val events = parseSseEvents(response.bodyAsText())
        val eventTypes = events.map { it.first }
        assertTrue(eventTypes.contains("error"), "Expected error event: $eventTypes")

        val errorEvent = events.first { it.first == "error" }.second.jsonObject
        assertNotNull(errorEvent["error"])
        assertEquals("Something went wrong", errorEvent["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST api chat send user message is persisted before streaming`() = testApplication {
        val (storage, repo, chatId) = seededStorage()
        application { module(storage, testAuthConfig) { _ -> fakeEngineSimple } }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        cookieClient.post("/api/chat/send") {
            contentType(ContentType.Application.Json)
            setBody(buildSendBody(chatId, userMessage = "Tell me something"))
        }

        val chat = repo.loadChatHistory("default").chat_history.find { it.chat_id == chatId }
        assertNotNull(chat)
        val userMessages = chat!!.messages.filter { it.role == "user" }
        assertTrue(userMessages.isNotEmpty(), "Expected user message to be persisted")
        assertEquals("Tell me something", userMessages.last().text)
    }
}
