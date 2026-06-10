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
 * Tests for POST /api/chats/{chatId}/messages/{messageId}/resend SSE endpoint.
 *
 * Uses [FakeChatEngine] instances (same approach as [StreamingSendTest]) so no
 * real LLM network calls are made.
 */
class ResendStreamTest {

    private val testPassword = "resend-test-password"
    private val testAuthConfig = AuthConfig(
        password = testPassword,
        sessionSecret = "resend-test-session-secret-that-is-long-enough"
    )

    private fun seededStorage(): Triple<ServerPlatformStorage, DataRepository, String> {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "resend-test-${System.nanoTime()}")
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
            callback.onPartialResponse("Re")
            callback.onPartialResponse("sent")
            callback.onComplete("Resent")
        }
    }

    private fun buildResendBody(
        provider: String = "openai",
        model: String = "gpt-4o",
        thinkingBudget: String = "none"
    ) = """
        {
            "provider": "$provider",
            "modelName": "$model",
            "systemPrompt": "",
            "webSearchEnabled": false,
            "enabledToolIds": [],
            "thinkingBudget": "$thinkingBudget",
            "temperature": null
        }
    """.trimIndent()

    private fun parseSseEvents(body: String): List<Pair<String, JsonElement>> {
        val events = mutableListOf<Pair<String, JsonElement>>()
        var currentEvent: String? = null
        var currentData: String? = null
        for (line in body.lines()) {
            when {
                line.startsWith("event: ") -> currentEvent = line.removePrefix("event: ").trim()
                line.startsWith("data: ") -> currentData = line.removePrefix("data: ").trim()
                line.isBlank() && currentEvent != null && currentData != null -> {
                    val dataJson = try { Json.parseToJsonElement(currentData!!) } catch (_: Exception) { JsonPrimitive(currentData!!) }
                    events.add(currentEvent!! to dataJson)
                    currentEvent = null
                    currentData = null
                }
            }
        }
        return events
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `POST resend without auth returns 401`() = testApplication {
        val (storage, _, _) = seededStorage()
        application { module(storage, testAuthConfig) }

        val response = client.post("/api/chats/some-chat/messages/some-msg/resend") {
            contentType(ContentType.Application.Json)
            setBody(buildResendBody())
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST resend unknown chat returns 404`() = testApplication {
        val (storage, _, _) = seededStorage()
        application { module(storage, testAuthConfig) { _ -> fakeEngineSimple } }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.post("/api/chats/does-not-exist/messages/msg-1/resend") {
            contentType(ContentType.Application.Json)
            setBody(buildResendBody())
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("not found", ignoreCase = true))
    }

    @Test
    fun `POST resend unknown message returns 404`() = testApplication {
        val (storage, _, chatId) = seededStorage()
        application { module(storage, testAuthConfig) { _ -> fakeEngineSimple } }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.post("/api/chats/$chatId/messages/non-existent-msg/resend") {
            contentType(ContentType.Application.Json)
            setBody(buildResendBody())
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("not found", ignoreCase = true))
    }

    @Test
    fun `POST resend happy path streams SSE and returns complete event`() = testApplication {
        val (storage, repo, chatId) = seededStorage()

        // Seed the chat with a user message so we have something to resend
        val userMsg = Message(id = "msg-user-1", role = "user", text = "Hello again")
        repo.addMessageToChat("default", chatId, userMsg)

        application { module(storage, testAuthConfig) { _ -> fakeEngineSimple } }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.post("/api/chats/$chatId/messages/${userMsg.id}/resend") {
            contentType(ContentType.Application.Json)
            setBody(buildResendBody())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val ct = response.contentType()
        assertNotNull(ct)
        assertTrue(ct!!.match(ContentType.parse("text/event-stream")))

        val events = parseSseEvents(response.bodyAsText())
        val eventTypes = events.map { it.first }

        // Should have partial events and a complete event
        assertTrue(eventTypes.contains("partial"), "Expected partial events: $eventTypes")
        assertTrue(eventTypes.contains("complete"), "Expected complete event: $eventTypes")

        val completeEvent = events.last { it.first == "complete" }.second.jsonObject
        assertEquals("Resent", completeEvent["text"]?.jsonPrimitive?.content)
        assertNotNull(completeEvent["messageId"])
    }

    @Test
    fun `POST resend deletes subsequent messages and re-adds user message`() = testApplication {
        val (storage, repo, chatId) = seededStorage()

        // Seed: user → assistant → user messages
        val userMsg1 = Message(id = "msg-1", role = "user", text = "Question 1")
        repo.addMessageToChat("default", chatId, userMsg1)
        val assistantMsg = Message(id = "msg-2", role = "assistant", text = "Answer 1")
        repo.addMessageToChat("default", chatId, assistantMsg)
        val userMsg2 = Message(id = "msg-3", role = "user", text = "Question 2")
        repo.addMessageToChat("default", chatId, userMsg2)

        application { module(storage, testAuthConfig) { _ -> fakeEngineSimple } }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        // Resend from the assistant message (msg-2)
        cookieClient.post("/api/chats/$chatId/messages/msg-2/resend") {
            contentType(ContentType.Application.Json)
            setBody(buildResendBody())
        }

        // After resend: msg-1 (user) should remain; msg-2 (assistant) and msg-3 (user) were
        // deleted; msg-2 was re-added as a new user node; new assistant "Resent" was added
        val chatAfter = repo.loadChatHistory("default").chat_history.find { it.chat_id == chatId }
        assertNotNull(chatAfter)
        val msgs = chatAfter!!.messages
        // msg-1 (user) survives; the resent assistant message ("Resent") is the new assistant
        assertTrue(msgs.any { it.role == "user" && it.text == "Question 1" }, "Question 1 should survive")
        assertTrue(msgs.any { it.role == "assistant" && it.text == "Resent" }, "New assistant message should be present")
        // The old msg-3 question 2 should be gone
        assertTrue(msgs.none { it.text == "Question 2" }, "Question 2 should have been deleted")
    }
}
