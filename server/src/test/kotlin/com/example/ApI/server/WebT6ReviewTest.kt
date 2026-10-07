package com.example.ApI.server

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.auth.RealSyncAuthClient
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.tools.ToolSpecification
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Ignore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T6 adversarial review: web tree edits on branches that are not the chat's current path,
 * legacy (flat) chats, and the bounded login wait.  Failing cases are @Disabled with the bug.
 */
class WebT6ReviewTest {

    private fun seeded(): Triple<ServerPlatformStorage, DataRepository, String> {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "t6-review-${System.nanoTime()}")
        val userDir = File(baseDir, "users/$TEST_USERNAME").also { it.mkdirs() }
        val repo = DataRepository(ServerPlatformStorage(baseDir = userDir))
        repo.saveAppSettings(com.example.ApI.data.model.AppSettings(current_user = TEST_USERNAME, selected_provider = "openai", selected_model = "gpt-4o"))
        val chat = repo.createNewChat(TEST_USERNAME, "Review chat")
        return Triple(ServerPlatformStorage(baseDir = baseDir), repo, chat.chat_id)
    }

    private fun DataRepository.chat(chatId: String): Chat =
        loadChatHistory(TEST_USERNAME).chat_history.first { it.chat_id == chatId }

    private class RecordingEngine(private val reply: String) : ChatEngine {
        val received = CopyOnWriteArrayList<List<Message>>()
        override suspend fun send(
            provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
            username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
            enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
            callback: StreamingCallback
        ) {
            received.add(messages)
            callback.onPartialResponse(reply)
            callback.onComplete(reply)
        }
    }

    private val resendBody = """{"provider":"openai","modelName":"gpt-4o","systemPrompt":"","webSearchEnabled":false,"enabledToolIds":[],"thinkingBudget":"none","temperature":null}"""

    /**
     * q1 → a1 → q2 → a2 (variant A of the first node), then a second variant B of the first
     * node (q1b → a1b) made current, e.g. by another browser tab of the same web account
     * (the current path is per device, and live refresh only reacts to sync pulls, so the
     * first tab keeps showing branch A with q2/a2).
     */
    private fun DataRepository.seedTwoBranches(chatId: String) {
        addUserMessageAsNewNode(TEST_USERNAME, chatId, Message(id = "q1", role = "user", text = "Question 1"))
        addResponseToCurrentVariant(TEST_USERNAME, chatId, Message(id = "a1", role = "assistant", text = "Answer 1"))
        addUserMessageAsNewNode(TEST_USERNAME, chatId, Message(id = "q2", role = "user", text = "Question 2"))
        addResponseToCurrentVariant(TEST_USERNAME, chatId, Message(id = "a2", role = "assistant", text = "Answer 2"))
        val node1 = chat(chatId).messages.first { it.id == "q1" }.nodeId!!
        createBranch(TEST_USERNAME, chatId, node1, Message(id = "q1b", role = "user", text = "Question 1 other"))
        addResponseToCurrentVariant(TEST_USERNAME, chatId, Message(id = "a1b", role = "assistant", text = "Answer 1 other"))
    }

    /** Every variant of the path is in the child node of the previous one (a root-to-leaf chain). */
    private fun assertPathIsChain(chat: Chat) {
        val variants = chat.messageNodes.flatMap { n -> n.variants.map { it.variantId to (n to it) } }.toMap()
        val path = chat.currentVariantPath
        val first = variants[path.first()]!!.first
        assertEquals(null, first.parentNodeId, "the path starts at the root node: $path")
        for (i in 1 until path.size) {
            val prev = variants[path[i - 1]]!!.second
            val node = variants[path[i]]!!.first
            assertEquals(prev.childNodeId, node.nodeId,
                "path entry $i (${path[i]}) is not in the child node of the previous variant: path=$path, messages=${chat.messages.map { it.text }}")
        }
    }

    // IGNORED: "T6 bug: createBranch on a node off the current path appends the new variant to the current path (MessageBranchingManager.createBranch `currentVariantPath + newVariantId`); the resend route then sends the wrong branch's context to the model and leaves a path that is not a chain"
    @Ignore
    @Test
    fun `regenerate of an answer on a branch that is not current answers in that branch's context`() = testApplication {
        val (storage, repo, chatId) = seeded()
        repo.seedTwoBranches(chatId)
        assertEquals(listOf("Question 1 other", "Answer 1 other"), repo.chat(chatId).messages.map { it.text })
        val engine = RecordingEngine("Answer 2 again")
        startWithFakeGoogleAuth(storage, chatEngineFactory = { engine })
        val c = googleLogin()

        // The first tab still shows branch A and regenerates a2
        val response = c.post("/api/chats/$chatId/messages/a2/resend") {
            contentType(ContentType.Application.Json); setBody(resendBody)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        assertEquals(listOf("Question 1", "Answer 1", "Question 2"), engine.received.single().map { it.text },
            "the model must get the regenerated question's own context (branch A), not the current branch B")
        val after = repo.chat(chatId)
        assertPathIsChain(after)
        assertEquals(listOf("Question 1", "Answer 1", "Question 2", "Answer 2 again"), after.messages.map { it.text })
    }

    // IGNORED: "T6 bug: POST /branch (web edit) on a node off the current path appends the new variant to the current path (createBranch), so the edit's resend sends the other branch's context and the path is not a chain"
    @Ignore
    @Test
    fun `edit of a message on a branch that is not current answers in that branch's context`() = testApplication {
        val (storage, repo, chatId) = seeded()
        repo.seedTwoBranches(chatId)
        val q2Node = repo.chat(chatId).messageNodes.first { n -> n.variants.any { it.userMessage.id == "q2" } }.nodeId
        val engine = RecordingEngine("Edited answer")
        startWithFakeGoogleAuth(storage, chatEngineFactory = { engine })
        val c = googleLogin()

        // What ChatPage.handleEditSubmit does, from the tab still showing branch A
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

        assertEquals(listOf("Question 1", "Answer 1", "Question 2 edited"), engine.received.single().map { it.text })
        assertPathIsChain(repo.chat(chatId))
    }

    // IGNORED: "T6 bug: the web delete of a LEGACY (flat) chat message is not migrated to the tree first: deleteMessageFromBranch's no-tree branch filters only `messages`, so a message followed by others is deleted from the middle (a tree chat refuses with 400) and the edit stays a messages-only write"
    @Ignore
    @Test
    fun `web delete of a legacy chat message followed by others is refused like in a tree chat`() = testApplication {
        val (storage, repo, chatId) = seeded()
        for ((id, role) in listOf("lq1" to "user", "la1" to "assistant", "lq2" to "user", "la2" to "assistant")) {
            repo.addMessageToChat(TEST_USERNAME, chatId, Message(id = id, role = role, text = "text $id"))
        }
        assertTrue(!repo.chat(chatId).hasBranchingStructure, "seeded as a legacy chat")
        startWithFakeGoogleAuth(storage)
        val c = googleLogin()

        val response = c.delete("/api/chats/$chatId/messages/la1")
        assertEquals(HttpStatusCode.BadRequest, response.status,
            "la1 is followed by lq2/la2; got ${response.status}, messages now ${repo.chat(chatId).messages.map { it.id }}")
        assertEquals(listOf("lq1", "la1", "lq2", "la2"), repo.chat(chatId).messages.map { it.id })
    }

    // IGNORED: "T6 bug: the web's flat delete of a middle message of a legacy chat (see above) shifts the deterministic legacy node ids; a sync merge against a device that migrated the chat and continued it DROPS that device's new message (merged = [lq1, lq2, la2], pq3 lost)"
    @Ignore
    @Test
    fun `a legacy chat message deleted on the web stays deleted, once, after a merge with a device that continued the chat`() = testApplication {
        val (storage, repo, chatId) = seeded()
        for ((id, role) in listOf("lq1" to "user", "la1" to "assistant", "lq2" to "user", "la2" to "assistant")) {
            repo.addMessageToChat(TEST_USERNAME, chatId, Message(id = id, role = role, text = "text $id"))
        }
        val base = repo.loadChatHistory(TEST_USERNAME)
        startWithFakeGoogleAuth(storage)
        val c = googleLogin()
        val response = c.delete("/api/chats/$chatId/messages/la1")
        if (response.status != HttpStatusCode.OK) return@testApplication  // refused: nothing to merge
        val local = repo.loadChatHistory(TEST_USERNAME)

        // The phone opened the legacy chat (migrated deterministically) and continued it
        val phone = DataRepository(ServerPlatformStorage(baseDir = File(System.getProperty("java.io.tmpdir"), "t6-phone-${System.nanoTime()}")))
        phone.saveChatHistory(TEST_USERNAME, base)
        phone.addUserMessageAsNewNode(TEST_USERNAME, chatId, Message(id = "pq3", role = "user", text = "phone q3"))
        val remote = phone.loadChatHistory(TEST_USERNAME)

        val merged = com.example.ApI.data.sync.merge.ChatHistoryMerger.merge(base, local, remote)
            .chat_history.single { it.chat_id == chatId }
        val ids = merged.messages.map { it.id }
        assertEquals(ids.distinct(), ids, "no duplicated messages after the merge: $ids")
        assertTrue("la1" !in ids, "the web's delete survives: $ids")
        assertTrue("pq3" in ids, "the phone's continuation survives: $ids")
    }

    // ── Login wait ────────────────────────────────────────────────────────────

    @Test
    fun `login returns within the bounded wait when the sync server hangs`() = testApplication {
        val fake = FakeCasSyncServer().start()
        try {
            val storage = tempStorage()
            startWithFakeGoogleAuth(
                storage,
                startRegistry = true,
                syncAuthClient = RealSyncAuthClient(fake.baseUrl),
                syncServerUrl = fake.baseUrl,
                pullIntervalSeconds = 3_600,
                loginPullTimeoutMs = 500
            )
            startApplication()
            fake.syncDelayMs = 20_000
            val started = System.currentTimeMillis()
            val c = googleLogin("slow.sync@example.com")
            val elapsed = System.currentTimeMillis() - started
            assertTrue(elapsed < 5_000, "login took ${elapsed}ms with a hanging sync server (wait bounded at 500ms)")
            assertEquals(HttpStatusCode.OK, c.get("/api/session").status)
            assertEquals(HttpStatusCode.OK, c.get("/api/chats").status)
        } finally {
            fake.syncDelayMs = 0
            fake.stop()
        }
    }

    @Test
    fun `login works when the sync server is down after the auth exchange`() = testApplication {
        val storage = tempStorage()
        // Fake auth exchange, real registry + engine pointed at a closed port
        startWithFakeGoogleAuth(storage, startRegistry = true, pullIntervalSeconds = 3_600, loginPullTimeoutMs = 2_000)
        startApplication()
        val started = System.currentTimeMillis()
        val c = googleLogin()
        assertTrue(System.currentTimeMillis() - started < 5_000)
        assertEquals(HttpStatusCode.OK, c.get("/api/session").status)
        val created = c.post("/api/chats") {
            contentType(ContentType.Application.Json); setBody("""{"previewName":"Offline chat"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
    }
}
