package com.example.ApI.server

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.data.sync.merge.ChatHistoryMerger
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.tools.ToolSpecification
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Web edits must change the message TREE (the authoritative structure every sync merge
 * rebuilds `messages` from), with Android's semantics: delete = branch-aware delete,
 * regenerate / resend = a new variant in the same node, edit = branch + reply into it,
 * multi-message mode = new nodes; streamed replies are pinned to the variant they answer.
 */
class WebTreeEditsTest {

    private fun seeded(): Triple<ServerPlatformStorage, DataRepository, String> {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "tree-edits-${System.nanoTime()}")
        val userDir = File(baseDir, "users/$TEST_USERNAME").also { it.mkdirs() }
        val repo = DataRepository(ServerPlatformStorage(baseDir = userDir))
        repo.saveAppSettings(com.example.ApI.data.model.AppSettings(current_user = TEST_USERNAME, selected_provider = "openai", selected_model = "gpt-4o"))
        val chat = repo.createNewChat(TEST_USERNAME, "Tree chat")
        return Triple(ServerPlatformStorage(baseDir = baseDir), repo, chat.chat_id)
    }

    /** q1 → a1 → q2 → a2 as a branching chat (what the app / web send produce). */
    private fun DataRepository.seedConversation(chatId: String): List<Message> {
        val q1 = Message(id = "q1", role = "user", text = "Question 1")
        val a1 = Message(id = "a1", role = "assistant", text = "Answer 1")
        val q2 = Message(id = "q2", role = "user", text = "Question 2")
        val a2 = Message(id = "a2", role = "assistant", text = "Answer 2")
        addUserMessageAsNewNode(TEST_USERNAME, chatId, q1)
        addResponseToCurrentVariant(TEST_USERNAME, chatId, a1)
        addUserMessageAsNewNode(TEST_USERNAME, chatId, q2)
        addResponseToCurrentVariant(TEST_USERNAME, chatId, a2)
        return listOf(q1, a1, q2, a2)
    }

    private fun DataRepository.chat(chatId: String): Chat =
        loadChatHistory(TEST_USERNAME).chat_history.first { it.chat_id == chatId }

    /** Engine that replies [reply]; [during] runs mid-stream (e.g. to move the current path). */
    private class ScriptedEngine(
        private val reply: String = "Resent",
        private val during: (suspend () -> Unit)? = null,
        private val withToolCall: Boolean = false
    ) : ChatEngine {
        val received = CopyOnWriteArrayList<List<Message>>()
        override suspend fun send(
            provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
            username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
            enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
            callback: StreamingCallback
        ) {
            received.add(messages)
            callback.onPartialResponse(reply)
            during?.invoke()
            if (withToolCall) {
                callback.onSaveToolMessages(
                    Message(role = "tool_call", text = "calling", toolCallId = "t1"),
                    Message(role = "tool_response", text = "tool output"),
                    precedingText = "Let me check"
                )
            }
            callback.onComplete(reply)
        }
    }

    private val resendBody = """{"provider":"openai","modelName":"gpt-4o","systemPrompt":"","webSearchEnabled":false,"enabledToolIds":[],"thinkingBudget":"none","temperature":null}"""

    private fun sendBody(chatId: String, text: String, persist: Boolean = true) = buildJsonObject {
        put("chatId", chatId); put("provider", "openai"); put("modelName", "gpt-4o")
        putJsonArray("messages") { addJsonObject { put("role", "user"); put("text", text) } }
        put("persistUserMessage", persist)
    }.toString()

    private fun Chat.variantsOfNodeWith(messageId: String) =
        messageNodes.first { n -> n.variants.any { v -> v.userMessage.id == messageId || v.responses.any { it.id == messageId } } }.variants

    /** A merge (what every sync does) must not bring an edit back: the tree is the truth. */
    private fun assertSurvivesMerge(before: Chat, after: Chat) {
        val base = com.example.ApI.data.model.UserChatHistory(TEST_USERNAME, listOf(before))
        val local = base.copy(chat_history = listOf(after))
        val merged = ChatHistoryMerger.merge(base, local, base).chat_history.single()
        assertEquals(after.messages.map { it.id to it.text }, merged.messages.map { it.id to it.text },
            "a sync merge (local edit vs unchanged remote) must keep the web edit")
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    fun `web delete removes the message from the tree and a merge keeps it deleted`() = testApplication {
        val (storage, repo, chatId) = seeded()
        repo.seedConversation(chatId)
        val before = repo.chat(chatId)
        startWithFakeGoogleAuth(storage)
        val c = googleLogin()

        val response = c.delete("/api/chats/$chatId/messages/a2")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        val after = repo.chat(chatId)
        val allIds = after.messageNodes.flatMap { n -> n.variants.flatMap { v -> listOf(v.userMessage.id) + v.responses.map { it.id } } }
        assertFalse("a2" in allIds, "a2 must be gone from the tree, not only from messages")
        assertEquals(listOf("q1", "a1", "q2"), after.messages.map { it.id })
        assertSurvivesMerge(before, after)
    }

    @Test
    fun `web delete of a message followed by others is refused like on Android`() = testApplication {
        val (storage, repo, chatId) = seeded()
        repo.seedConversation(chatId)
        startWithFakeGoogleAuth(storage)
        val c = googleLogin()

        val response = c.delete("/api/chats/$chatId/messages/q1")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(listOf("q1", "a1", "q2", "a2"), repo.chat(chatId).messages.map { it.id })
        // The /branch alias behaves the same
        assertEquals(HttpStatusCode.BadRequest, c.delete("/api/chats/$chatId/branch/messages/a1").status)
        assertEquals(HttpStatusCode.NotFound, c.delete("/api/chats/nope/messages/q1").status)
    }

    // ── Regenerate / resend ───────────────────────────────────────────────────

    @Test
    fun `web regenerate creates a sibling variant in the same node and never a node`() = testApplication {
        val (storage, repo, chatId) = seeded()
        repo.seedConversation(chatId)
        val before = repo.chat(chatId)
        val engine = ScriptedEngine("Answer 2b")
        startWithFakeGoogleAuth(storage, chatEngineFactory = { engine })
        val c = googleLogin()

        val response = c.post("/api/chats/$chatId/messages/a2/resend") {
            contentType(ContentType.Application.Json); setBody(resendBody)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("event: complete"))

        val after = repo.chat(chatId)
        assertEquals(before.messageNodes.size, after.messageNodes.size, "regenerate must not add nodes")
        val variants = after.variantsOfNodeWith("q2")
        assertEquals(2, variants.size)
        assertEquals(listOf("a2"), variants[0].responses.map { it.id }, "the original answer stays in its variant")
        assertEquals("user", variants[1].userMessage.role, "an assistant message is never a user message")
        assertEquals("Question 2", variants[1].userMessage.text)
        assertEquals(listOf("Answer 2b"), variants[1].responses.map { it.text })
        assertEquals(listOf("Question 1", "Answer 1", "Question 2", "Answer 2b"), after.messages.map { it.text })
        // The model got the path up to the question, not the old answer
        assertEquals(listOf("Question 1", "Answer 1", "Question 2"), engine.received.single().map { it.text })
        assertSurvivesMerge(before, after)
    }

    @Test
    fun `web resend of an earlier user message branches at its node`() = testApplication {
        val (storage, repo, chatId) = seeded()
        repo.seedConversation(chatId)
        val before = repo.chat(chatId)
        startWithFakeGoogleAuth(storage, chatEngineFactory = { ScriptedEngine("Answer 1b") })
        val c = googleLogin()

        val response = c.post("/api/chats/$chatId/messages/q1/resend") {
            contentType(ContentType.Application.Json); setBody(resendBody)
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val after = repo.chat(chatId)
        assertEquals(2, after.messageNodes.size)
        val variants = after.variantsOfNodeWith("q1")
        assertEquals(2, variants.size)
        assertNotNull(variants[0].childNodeId, "the original continuation stays reachable")
        assertNull(variants[1].childNodeId)
        assertEquals(listOf("Question 1", "Answer 1b"), after.messages.map { it.text })
        assertSurvivesMerge(before, after)
    }

    @Test
    fun `web resend of a legacy chat migrates it and branches`() = testApplication {
        val (storage, repo, chatId) = seeded()
        repo.addMessageToChat(TEST_USERNAME, chatId, Message(id = "lq", role = "user", text = "Legacy Q"))
        repo.addMessageToChat(TEST_USERNAME, chatId, Message(id = "la", role = "assistant", text = "Legacy A"))
        startWithFakeGoogleAuth(storage, chatEngineFactory = { ScriptedEngine("New A") })
        val c = googleLogin()

        val response = c.post("/api/chats/$chatId/messages/la/resend") {
            contentType(ContentType.Application.Json); setBody(resendBody)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val after = repo.chat(chatId)
        assertEquals(1, after.messageNodes.size)
        assertEquals(listOf(listOf("la"), listOf()), after.messageNodes.single().variants.map { v -> v.responses.filter { it.text != "New A" }.map { it.id } })
        assertEquals(listOf("Legacy Q", "New A"), after.messages.map { it.text })
    }

    @Test
    fun `resend of an unknown message is 404`() = testApplication {
        val (storage, repo, chatId) = seeded()
        repo.seedConversation(chatId)
        startWithFakeGoogleAuth(storage, chatEngineFactory = { ScriptedEngine() })
        val c = googleLogin()
        val response = c.post("/api/chats/$chatId/messages/nope/resend") {
            contentType(ContentType.Application.Json); setBody(resendBody)
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── Edit ──────────────────────────────────────────────────────────────────

    @Test
    fun `web edit creates a branch and the resend by variant id replies into it`() = testApplication {
        val (storage, repo, chatId) = seeded()
        repo.seedConversation(chatId)
        val q2Node = repo.chat(chatId).messages.first { it.id == "q2" }.nodeId!!
        val engine = ScriptedEngine("Edited answer")
        startWithFakeGoogleAuth(storage, chatEngineFactory = { engine })
        val c = googleLogin()

        // What ChatPage.handleEditSubmit does
        val branch = c.post("/api/chats/$chatId/branch") {
            contentType(ContentType.Application.Json)
            setBody("""{"nodeId":"$q2Node","newUserMessage":{"id":"q2","role":"user","text":"Question 2 edited"}}""")
        }
        assertEquals(HttpStatusCode.Created, branch.status, branch.bodyAsText())
        val newVariantId = Json.parseToJsonElement(branch.bodyAsText()).jsonObject["newVariantId"]!!.jsonPrimitive.content

        val response = c.post("/api/chats/$chatId/messages/$newVariantId/resend") {
            contentType(ContentType.Application.Json); setBody(resendBody)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("event: complete"))

        val after = repo.chat(chatId)
        assertEquals(2, after.messageNodes.size)
        val variants = after.variantsOfNodeWith("q2")
        assertEquals(2, variants.size, "the edit's variant is answered, no extra variant")
        assertEquals(newVariantId, variants[1].variantId)
        assertEquals(listOf("Edited answer"), variants[1].responses.map { it.text })
        assertEquals(listOf("Question 1", "Answer 1", "Question 2 edited", "Edited answer"), after.messages.map { it.text })
        assertEquals(listOf("Question 1", "Answer 1", "Question 2 edited"), engine.received.single().map { it.text })
    }

    @Test
    fun `web edit of a legacy chat message finds its node by message id`() = testApplication {
        val (storage, repo, chatId) = seeded()
        repo.addMessageToChat(TEST_USERNAME, chatId, Message(id = "lq", role = "user", text = "Legacy Q"))
        repo.addMessageToChat(TEST_USERNAME, chatId, Message(id = "la", role = "assistant", text = "Legacy A"))
        startWithFakeGoogleAuth(storage, chatEngineFactory = { ScriptedEngine("Edited A") })
        val c = googleLogin()

        // A legacy message has no nodeId: the web sends the message id instead
        val branch = c.post("/api/chats/$chatId/branch") {
            contentType(ContentType.Application.Json)
            setBody("""{"nodeId":"lq","newUserMessage":{"role":"user","text":"Legacy Q edited"}}""")
        }
        assertEquals(HttpStatusCode.Created, branch.status, branch.bodyAsText())
        val newVariantId = Json.parseToJsonElement(branch.bodyAsText()).jsonObject["newVariantId"]!!.jsonPrimitive.content
        val response = c.post("/api/chats/$chatId/messages/$newVariantId/resend") {
            contentType(ContentType.Application.Json); setBody(resendBody)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("Legacy Q edited", "Edited A"), repo.chat(chatId).messages.map { it.text })
    }

    // ── Multi-message mode ────────────────────────────────────────────────────

    @Test
    fun `multi-message adds tree nodes and the batch reply goes after the last one`() = testApplication {
        val (storage, repo, chatId) = seeded()
        val engine = ScriptedEngine("Batch reply")
        startWithFakeGoogleAuth(storage, chatEngineFactory = { engine })
        val c = googleLogin()

        for (text in listOf("part 1", "part 2")) {
            val r = c.post("/api/chats/$chatId/messages") {
                contentType(ContentType.Application.Json); setBody("""{"role":"user","text":"$text"}""")
            }
            assertEquals(HttpStatusCode.Created, r.status)
            val saved = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            assertNotNull(saved["variantId"]?.jsonPrimitive?.contentOrNull, "the saved message is in the tree")
        }
        val mid = repo.chat(chatId)
        assertEquals(2, mid.messageNodes.size, "each buffered message is a node")
        assertEquals(listOf("part 1", "part 2"), mid.messages.map { it.text })

        val response = c.post("/api/chat/send") {
            contentType(ContentType.Application.Json); setBody(sendBody(chatId, "part 2", persist = false))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val after = repo.chat(chatId)
        assertEquals(2, after.messageNodes.size)
        assertEquals(listOf("part 1", "part 2", "Batch reply"), after.messages.map { it.text })
        val lastVariant = after.messageNodes.first { n -> n.variants.any { it.userMessage.text == "part 2" } }.variants.single()
        assertEquals(listOf("Batch reply"), lastVariant.responses.map { it.text })
        assertSurvivesMerge(mid, after)
    }

    // ── Pinned replies ────────────────────────────────────────────────────────

    @Test
    fun `a streamed reply and its tool messages stay with the question when the path moves mid-stream`() = testApplication {
        val (storage, repo, chatId) = seeded()
        repo.seedConversation(chatId)
        val q1Node = repo.chat(chatId).messages.first { it.id == "q1" }.nodeId!!
        // Mid-stream another edit (another tab / a sync merge) moves the current path to a new
        // branch at the first node
        val engine = ScriptedEngine("Answer 3", withToolCall = true, during = {
            repo.createBranch(TEST_USERNAME, chatId, q1Node, Message(role = "user", text = "Other branch"))
        })
        startWithFakeGoogleAuth(storage, chatEngineFactory = { engine })
        val c = googleLogin()

        val response = c.post("/api/chat/send") {
            contentType(ContentType.Application.Json); setBody(sendBody(chatId, "Question 3"))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val after = repo.chat(chatId)
        val q3Variant = after.messageNodes.flatMap { it.variants }.single { it.userMessage.text == "Question 3" }
        assertEquals(listOf("Let me check", "calling", "tool output", "Answer 3"), q3Variant.responses.map { it.text },
            "every save of the request goes to the question's variant, in order")
        val otherBranch = after.messageNodes.flatMap { it.variants }.single { it.userMessage.text == "Other branch" }
        assertTrue(otherBranch.responses.isEmpty(), "nothing leaks into the branch that became current")
    }
}
