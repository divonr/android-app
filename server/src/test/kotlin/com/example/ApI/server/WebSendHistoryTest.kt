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
import com.example.ApI.server.streaming.storedRequestMessages
import com.example.ApI.tools.ToolCallInfo
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * POST /api/chat/send to vertex replays the chat's real history: the web posts every message as role,
 * text and attachments only, and the provider must still get the stored messages — a tool call
 * with its arguments, ids, output and thought signature — so that the request starts with what
 * the turn's follow-up requests and a resend sent (the provider's prompt cache is a prefix
 * match).  A list that is not the stored path is sent as the client gave it.
 */
class WebSendHistoryTest {

    /** Records the messages of every request that reaches the provider code. */
    private class RecordingEngine : ChatEngine {
        val requests = CopyOnWriteArrayList<List<Message>>()

        override suspend fun send(
            provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
            username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
            enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
            callback: StreamingCallback
        ) {
            requests += messages
            callback.onPartialResponse("ok")
            callback.onComplete("ok")
        }
    }

    private class Seed(val storage: ServerPlatformStorage, val repo: DataRepository, val chatId: String) {
        fun path(): List<Message> = repo.loadChatHistory(TEST_USERNAME).chat_history.first { it.chat_id == chatId }.messages
    }

    private val toolArguments = buildJsonObject {
        put("timezone", "Asia/Jerusalem")
        put("precision", 1.5)
        // Keys out of order: the arguments must reach the provider as they are stored
        putJsonObject("format") { put("b", 2); put("a", 1) }
    }

    /**
     * A chat with one finished tool turn, saved the way a run saves it: question, the text before
     * the call (with its thoughts), the signed tool call, its response, the answer.
     */
    private fun seed(): Seed {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "web-history-test-${System.nanoTime()}").apply { mkdirs() }
        val repo = DataRepository(ServerPlatformStorage(File(baseDir, "users/$TEST_USERNAME")))
        repo.saveAppSettings(AppSettings(current_user = TEST_USERNAME, selected_provider = "openai", selected_model = "gpt-4o"))
        val chatId = repo.createNewChat(TEST_USERNAME, "Tool chat").chat_id
        assertNotNull(repo.addUserMessageAsNewNode(TEST_USERNAME, chatId, Message(role = "user", text = "What time is it?")))
        val responses = listOf(
            Message(
                role = "assistant", text = "Let me check.", model = "gemini-3-fake", thoughts = "I need the clock",
                thinkingDurationSeconds = 1.5f, thoughtsStatus = ThoughtsStatus.PRESENT
            ),
            Message(
                role = "tool_call", text = "", toolCallId = "call-1",
                toolCall = ToolCallInfo(
                    toolId = "get_date_time", toolName = "get_date_time", parameters = toolArguments,
                    result = ToolExecutionResult.Success("12:00"), timestamp = "2026-11-11T10:00:00Z",
                    thoughtSignature = "sig-abc"
                )
            ),
            Message(role = "tool_response", text = "12:00", toolResponseCallId = "call-1", toolResponseOutput = "12:00"),
            Message(role = "assistant", text = "It is 12:00.", model = "gemini-3-fake")
        )
        for (response in responses) assertNotNull(repo.addResponseToCurrentVariant(TEST_USERNAME, chatId, response))
        return Seed(ServerPlatformStorage(baseDir), repo, chatId)
    }

    /** What the web keeps of a message when it sends (ChatPage.tsx): role, text, attachments. */
    private fun asTheWebSends(message: JsonObject): JsonObject = buildJsonObject {
        put("role", message["role"]!!)
        put("text", message["text"]!!)
        put("attachments", message["attachments"] ?: JsonArray(emptyList()))
    }

    /** The chat as the web loaded it (GET /api/chats/{id}), reduced to what the web sends. */
    private suspend fun HttpClient.loadedHistory(chatId: String): List<JsonObject> {
        val chat = Json.parseToJsonElement(get("/api/chats/$chatId").bodyAsText()).jsonObject
        return chat["messages"]!!.jsonArray.map { asTheWebSends(it.jsonObject) }
    }

    private fun userMessage(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("text", text)
        put("attachments", JsonArray(emptyList()))
    }

    private suspend fun HttpClient.send(
        chatId: String, messages: List<JsonObject>, persistUserMessage: Boolean = true,
        provider: String = "vertex", modelName: String = "gemini-3.5-flash"
    ): HttpResponse =
        post("/api/chat/send") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("chatId", chatId)
                put("provider", provider)
                put("modelName", modelName)
                put("messages", JsonArray(messages))
                put("systemPrompt", "")
                put("webSearchEnabled", false)
                put("enabledToolIds", JsonArray(emptyList()))
                put("thinkingBudget", "none")
                put("temperature", JsonNull)
                put("projectAttachments", JsonArray(emptyList()))
                if (!persistUserMessage) put("persistUserMessage", false)
            }.toString())
        }

    @Test
    fun `a web send reaches the provider with the stored tool call, response and signature`() = testApplication {
        val seed = seed()
        val storedBefore = seed.path()
        val engine = RecordingEngine()
        startWithFakeGoogleAuth(seed.storage, chatEngineFactory = { _ -> engine })
        val browser = googleLogin(TEST_USER_EMAIL)

        val history = browser.loadedHistory(seed.chatId)
        assertEquals(listOf("user", "assistant", "tool_call", "tool_response", "assistant"), history.map { it["role"]!!.jsonPrimitive.content })
        assertTrue(history.all { it.keys == setOf("role", "text", "attachments") }, "the web sends nothing else of a message")
        val response = browser.send(seed.chatId, history + userMessage("Thanks"))
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("event: complete" in response.bodyAsText())

        val sent = engine.requests.single()
        // The history is the stored one, message for message (ids, thoughts, tool data)
        assertEquals(storedBefore, sent.dropLast(1))

        val call = sent[2]
        assertEquals("call-1", call.toolCallId)
        val info = assertNotNull(call.toolCall)
        assertEquals("get_date_time", info.toolId)
        assertEquals(toolArguments, info.parameters)
        assertEquals(storedBefore[2].toolCall!!.parameters.toString(), info.parameters.toString(), "the arguments as they are stored")
        assertEquals("""{"timezone":"Asia/Jerusalem","precision":1.5,"format":{"b":2,"a":1}}""", info.parameters.toString())
        assertEquals(ToolExecutionResult.Success("12:00"), info.result)
        assertEquals("sig-abc", info.thoughtSignature)

        val toolResponse = sent[3]
        assertEquals("call-1", toolResponse.toolResponseCallId)
        assertEquals("12:00", toolResponse.toolResponseOutput)

        // The new question is the message that was saved, and the reply follows it
        val storedAfter = seed.path()
        assertEquals(listOf("What time is it?", "Let me check.", "", "12:00", "It is 12:00.", "Thanks", "ok"), storedAfter.map { it.text })
        assertEquals(storedAfter[5], sent.last())
        assertNotNull(sent.last().variantId)
    }

    @Test
    fun `the reply request of multi-message mode replays the stored history too and saves no message`() = testApplication {
        val seed = seed()
        val engine = RecordingEngine()
        startWithFakeGoogleAuth(seed.storage, chatEngineFactory = { _ -> engine })
        val browser = googleLogin(TEST_USER_EMAIL)

        // Two buffered messages, saved without a request to the model
        for (text in listOf("One more thing", "and another")) {
            val added = browser.post("/api/chats/${seed.chatId}/messages") {
                contentType(ContentType.Application.Json)
                setBody(userMessage(text).toString())
            }
            assertEquals(HttpStatusCode.Created, added.status)
        }
        val stored = seed.path()
        assertEquals(7, stored.size)

        val response = browser.send(seed.chatId, browser.loadedHistory(seed.chatId), persistUserMessage = false)
        assertEquals(HttpStatusCode.OK, response.status)

        assertEquals(stored, engine.requests.single())
        assertEquals("sig-abc", engine.requests.single()[2].toolCall?.thoughtSignature)
        assertEquals(stored.map { it.id } + seed.path().last().id, seed.path().map { it.id }, "only the reply was added")
        assertEquals("ok", seed.path().last().text)
    }

    @Test
    fun `another provider keeps getting the web's own list`() = testApplication {
        val seed = seed()
        val engine = RecordingEngine()
        startWithFakeGoogleAuth(seed.storage, chatEngineFactory = { _ -> engine })
        val browser = googleLogin(TEST_USER_EMAIL)

        val history = browser.loadedHistory(seed.chatId)
        val response = browser.send(seed.chatId, history + userMessage("Thanks"), provider = "openai", modelName = "gpt-4o")
        assertEquals(HttpStatusCode.OK, response.status)

        val sent = engine.requests.single()
        assertEquals(listOf("What time is it?", "Let me check.", "", "12:00", "It is 12:00.", "Thanks"), sent.map { it.text })
        for (message in sent) {
            assertNull(message.toolCall)
            assertNull(message.toolResponseOutput)
            assertNull(message.thoughts)
        }
    }

    @Test
    fun `a list that is not the stored path is sent as the client gave it`() = testApplication {
        val seed = seed()
        val engine = RecordingEngine()
        startWithFakeGoogleAuth(seed.storage, chatEngineFactory = { _ -> engine })
        val browser = googleLogin(TEST_USER_EMAIL)
        val history = browser.loadedHistory(seed.chatId)

        fun JsonObject.withId(id: String) = JsonObject(this + ("id" to JsonPrimitive(id)))
        fun List<JsonObject>.numbered(prefix: String) = mapIndexed { i, m -> m.withId("$prefix-$i") }

        fun assertSentAsGiven(given: List<JsonObject>, sent: List<Message>) {
            assertEquals(given.map { it["id"]!!.jsonPrimitive.content }, sent.map { it.id })
            assertEquals(given.map { it["role"]!!.jsonPrimitive.content }, sent.map { it.role })
            assertEquals(given.map { it["text"]!!.jsonPrimitive.content }, sent.map { it.text })
            for (message in sent) {
                assertNull(message.toolCall)
                assertNull(message.toolCallId)
                assertNull(message.toolResponseCallId)
                assertNull(message.toolResponseOutput)
                assertNull(message.thoughts)
                assertNull(message.variantId)
            }
        }

        // The same length, one other text: the client shows another branch of the chat
        val otherBranch = (history.dropLast(1) + buildJsonObject {
            put("role", "assistant")
            put("text", "It is noon.")
            put("attachments", JsonArray(emptyList()))
        } + userMessage("Thanks")).numbered("a")
        assertEquals(HttpStatusCode.OK, browser.send(seed.chatId, otherBranch).status)
        assertSentAsGiven(otherBranch, engine.requests[0])

        // An older state of the chat: the replies saved since are missing from the client's list
        val stale = (history + userMessage("Thanks") + userMessage("Hello?")).numbered("b")
        assertEquals(HttpStatusCode.OK, browser.send(seed.chatId, stale).status)
        assertSentAsGiven(stale, engine.requests[1])

        // A list of the client's own, in multi-message mode
        val adHoc = listOf(userMessage("Only this")).numbered("c")
        assertEquals(HttpStatusCode.OK, browser.send(seed.chatId, adHoc, persistUserMessage = false).status)
        assertSentAsGiven(adHoc, engine.requests[2])

        // The route saved what it always saved: the two questions and the three replies
        assertEquals(
            listOf("What time is it?", "Let me check.", "", "12:00", "It is 12:00.", "Thanks", "ok", "Hello?", "ok", "ok"),
            seed.path().map { it.text }
        )
    }

    @Test
    fun `the stored messages replace the client's only when roles, texts and file names all agree`() {
        fun file(name: String, uri: String? = null) = Attachment(file_name = name, mime_type = "application/pdf", file_GOOGLE_uri = uri)
        val stored = listOf(
            Message(id = "s1", role = "user", text = "Read this", attachments = listOf(file("a.pdf", uri = "files/1"))),
            Message(id = "s2", role = "assistant", text = "Done")
        )
        fun client(vararg messages: Message) = messages.toList()

        // The web's copy of the same path (other ids, an attachment loaded before its provider id was recorded)
        assertSame(stored, storedRequestMessages(client(
            Message(role = "user", text = "Read this", attachments = listOf(file("a.pdf"))),
            Message(role = "assistant", text = "Done")
        ), stored))
        assertSame(stored, storedRequestMessages(stored, stored))
        assertEquals(emptyList(), storedRequestMessages(emptyList(), emptyList()))

        val differing = listOf(
            // Another file under the same text
            client(Message(role = "user", text = "Read this", attachments = listOf(file("b.pdf"))), Message(role = "assistant", text = "Done")),
            // No file
            client(Message(role = "user", text = "Read this"), Message(role = "assistant", text = "Done")),
            // Another role, another text
            client(Message(role = "user", text = "Read this", attachments = listOf(file("a.pdf"))), Message(role = "tool_response", text = "Done")),
            client(Message(role = "user", text = "Read this", attachments = listOf(file("a.pdf"))), Message(role = "assistant", text = "Done.")),
            // Shorter, longer, empty
            client(Message(role = "user", text = "Read this", attachments = listOf(file("a.pdf")))),
            stored + Message(role = "user", text = "More"),
            emptyList()
        )
        for (list in differing) assertSame(list, storedRequestMessages(list, stored))
    }
}
