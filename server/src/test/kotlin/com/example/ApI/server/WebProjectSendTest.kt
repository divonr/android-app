package com.example.ApI.server

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.auth.FakeSyncWhoamiClient
import com.example.ApI.server.auth.SyncIdentity
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.tools.ToolSpecification
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web's send / resend in a chat of a project (CLOUD_RUNS_PLAN.md, "Existing web routes"):
 * the server takes the project's files and instructions from its own copy of the chat's group,
 * as Android does on the device, whatever the web client put into the request.
 */
class WebProjectSendTest {

    private companion object {
        const val ALICE_TOKEN = "fake-sync-token-$TEST_USERNAME"
    }

    private class Request(val systemPrompt: String, val projectAttachments: List<Attachment>, val projectFiles: List<String?>)

    /** Records what reaches the provider code: the prompt, the project attachments and their files' text. */
    private class RecordingEngine : ChatEngine {
        val requests = CopyOnWriteArrayList<Request>()

        override suspend fun send(
            provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
            username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
            enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
            callback: StreamingCallback
        ) {
            requests += Request(
                systemPrompt, projectAttachments,
                projectAttachments.map { a -> a.local_file_path?.let { File(it).takeIf(File::isFile)?.readText() } }
            )
            callback.onPartialResponse("ok")
            callback.onComplete("ok")
        }
    }

    private val store = FakeFileStore().start().also {
        it.accounts[ALICE_TOKEN] = TEST_USERNAME
        it.accounts[TEST_BEARER] = TEST_USERNAME
    }

    @AfterTest
    fun tearDown() = store.stop()

    private class World(val storage: ServerPlatformStorage, val repo: DataRepository) {
        val chatId: String = repo.loadChatHistory(TEST_USERNAME).chat_history.first().chat_id
        val groupId: String = repo.loadChatHistory(TEST_USERNAME).groups.first().group_id
    }

    private fun world(): World = seededStorageForUser().let { (storage, repo) -> World(storage, repo) }

    private fun live(world: World, engine: ChatEngine) = LiveServer(
        world.storage, engine,
        FakeSyncWhoamiClient().apply { identities[TEST_BEARER] = SyncIdentity(TEST_USERNAME, TEST_USER_EMAIL) },
        syncServerUrl = store.baseUrl
    )

    private fun sendBody(chatId: String, id: String, systemPrompt: String = "", extra: String = "") =
        """{"chatId":"$chatId","provider":"anthropic","modelName":"claude","systemPrompt":"$systemPrompt",""" +
            """"messages":[{"role":"user","text":"Q","id":"$id"}],"projectAttachments":[]$extra}"""

    private fun resendBody(systemPrompt: String = "") = """{"provider":"anthropic","modelName":"claude","systemPrompt":"$systemPrompt"}"""

    /** The seeded group gets a file (in the store, as a device left it) and instructions. */
    private fun World.fillGroup(): Attachment {
        val sha = store.put(TEST_USERNAME, "the project's notes".toByteArray())
        val file = Attachment(file_name = "notes.txt", mime_type = "text/plain", sha256 = sha, size = 19)
        assertTrue(repo.addAttachmentToGroup(TEST_USERNAME, groupId, file))
        repo.updateChatHistory(TEST_USERNAME) { history ->
            history.copy(groups = history.groups.map { if (it.group_id == groupId) it.copy(system_prompt = "Project instructions") else it })
        }
        assertTrue(repo.addChatToGroup(TEST_USERNAME, chatId, groupId))
        return file
    }

    @Test
    fun `a web send and resend in a project chat carry the project's files and instructions`() {
        val world = world()
        val file = world.fillGroup()
        assertTrue(world.repo.updateGroupProjectStatus(TEST_USERNAME, world.groupId, true))
        val engine = RecordingEngine()
        live(world, engine).use { server ->
            val browser = server.login()

            // The web sends an empty list and only the chat's own prompt
            val sent = browser.post("/api/chat/send", sendBody(world.chatId, "p-1"))
            assertEquals(200, sent.status, sent.body)
            val first = engine.requests.single()
            assertEquals(listOf(file.sha256), first.projectAttachments.map { it.sha256 })
            assertEquals(listOf("notes.txt"), first.projectAttachments.map { it.file_name })
            assertEquals(listOf<String?>("the project's notes"), first.projectFiles, "the file itself reaches the provider code")
            assertEquals("Project instructions", first.systemPrompt)

            // Resend (the web cannot send project files there at all), with a prompt of the chat
            val resent = browser.post("/api/chats/${world.chatId}/messages/p-1/resend", resendBody("Chat prompt"))
            assertEquals(200, resent.status, resent.body)
            val second = engine.requests[1]
            assertEquals(listOf(file.sha256), second.projectAttachments.map { it.sha256 })
            assertEquals(listOf<String?>("the project's notes"), second.projectFiles)
            assertEquals("Project instructions\n\nChat prompt", second.systemPrompt)

            // Multi-message mode's reply request (nothing persisted by it) is a send like any other
            val reply = browser.post("/api/chat/send", sendBody(world.chatId, "p-1", extra = ""","persistUserMessage":false"""))
            assertEquals(200, reply.status, reply.body)
            assertEquals(listOf(file.sha256), engine.requests[2].projectAttachments.map { it.sha256 })

            // The stored group is not changed by the request's copy (no server path is saved)
            val stored = world.repo.loadChatHistory(TEST_USERNAME).groups.single().group_attachments.single()
            assertEquals(null, stored.local_file_path)
            assertEquals(file.sha256, stored.sha256)
        }
    }

    @Test
    fun `a plain group and a chat outside any group add nothing`() {
        val world = world()
        world.fillGroup()                      // a group with files, but not a project
        val engine = RecordingEngine()
        live(world, engine).use { server ->
            val browser = server.login()
            assertEquals(200, browser.post("/api/chat/send", sendBody(world.chatId, "g-1", "Chat prompt")).status)
            assertEquals(200, browser.post("/api/chats/${world.chatId}/messages/g-1/resend", resendBody("Chat prompt")).status)
            for (request in engine.requests) {
                assertTrue(request.projectAttachments.isEmpty(), "a folder is not a project")
                assertEquals("Chat prompt", request.systemPrompt)
            }
            assertEquals(2, engine.requests.size)

            // Outside a project the request's own list is still what is sent
            val other = store.put(TEST_USERNAME, "sent by the client".toByteArray())
            val explicit = ""","projectAttachments":[{"file_name":"c.txt","mime_type":"text/plain","sha256":"$other","size":18}]"""
            val body = sendBody(world.chatId, "g-2").replace(""","projectAttachments":[]""", explicit)
            assertEquals(200, browser.post("/api/chat/send", body).status)
            assertEquals(listOf(other), engine.requests[2].projectAttachments.map { it.sha256 })
        }
    }

    @Test
    fun `a device's run keeps the project attachments it sent`() {
        val world = world()
        world.fillGroup()
        assertTrue(world.repo.updateGroupProjectStatus(TEST_USERNAME, world.groupId, true))
        val engine = RecordingEngine()
        live(world, engine).use { server ->
            val device = server.client()
            val path = world.repo.loadChatHistory(TEST_USERNAME).chat_history.first().messages
            // Android builds the list (and the prompt) itself: an empty list stays empty
            val started = device.post("/api/runs", runBody("run-p", world.chatId, path))
            assertEquals(202, started.status, started.body)
            awaitTrue(what = "the run") { engine.requests.size == 1 }
            assertTrue(engine.requests.single().projectAttachments.isEmpty())
            assertEquals("", engine.requests.single().systemPrompt)
        }
    }
}
