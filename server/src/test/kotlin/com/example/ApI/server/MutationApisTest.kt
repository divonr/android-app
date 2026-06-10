package com.example.ApI.server

import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 route tests for all mutation (POST/PATCH/PUT/DELETE) API endpoints.
 *
 * Each test uses a fresh temp dir so nothing touches ~/.llm-api-web.
 * After mutations, we reload via the repository to assert persistence.
 */
class MutationApisTest {

    private val testPassword = "p4-test-password"
    private val testAuthConfig = AuthConfig(
        password = testPassword,
        sessionSecret = "p4-test-session-secret-that-is-long-enough"
    )

    private fun tempStorage(): ServerPlatformStorage {
        val dir = File(System.getProperty("java.io.tmpdir"), "p4-test-${System.nanoTime()}")
        dir.mkdirs()
        return ServerPlatformStorage(baseDir = dir)
    }

    private fun seededStorage(): Pair<ServerPlatformStorage, DataRepository> {
        val storage = tempStorage()
        val repo = DataRepository(storage)
        repo.saveAppSettings(
            AppSettings(
                current_user = "testuser",
                selected_provider = "openai",
                selected_model = "gpt-4o"
            )
        )
        // Create initial chat
        val chat = repo.createNewChat("testuser", "Seed Chat")
        repo.addMessageToChat(
            "testuser", chat.chat_id,
            Message(role = "user", text = "Initial message")
        )
        // Create a group
        repo.createNewGroup("testuser", "Seed Group")
        // Add an API key
        repo.addApiKey(
            "testuser",
            ApiKey(id = "key-seed", provider = "openai", key = "sk-seedkey1234", isActive = true)
        )
        return storage to repo
    }

    /** Login and return a cookie-carrying client. */
    private suspend fun ApplicationTestBuilder.loggedInClient(): io.ktor.client.HttpClient {
        val client = createClient { install(HttpCookies) }
        client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        return client
    }

    // ── 401 without session ──────────────────────────────────────────────────

    @Test
    fun `POST api chats without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val response = client.post("/api/chats") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewName":"Test"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `DELETE api groups without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val response = client.delete("/api/groups/some-id")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ── Chats CRUD ───────────────────────────────────────────────────────────

    @Test
    fun `POST api chats creates new chat and persists`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.post("/api/chats") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewName":"My New Chat","systemPrompt":"Be helpful"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val chatId = body["chat_id"]?.jsonPrimitive?.content
        assertNotNull(chatId, "Expected chat_id in response")
        assertEquals("My New Chat", body["preview_name"]?.jsonPrimitive?.content)

        // Verify persistence
        val saved = repo.loadChatHistory("testuser").chat_history.find { it.chat_id == chatId }
        assertNotNull(saved, "Chat should be persisted")
        assertEquals("My New Chat", saved!!.preview_name)
        assertEquals("Be helpful", saved.systemPrompt)
    }

    @Test
    fun `POST api chats with groupId creates chat in group`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val groupId = repo.loadChatHistory("testuser").groups.first().group_id

        val response = c.post("/api/chats") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewName":"Grouped Chat","groupId":"$groupId"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val chatId = body["chat_id"]?.jsonPrimitive?.content
        assertNotNull(chatId)

        // Verify the chat's group field
        val saved = repo.loadChatHistory("testuser").chat_history.find { it.chat_id == chatId }
        assertEquals(groupId, saved?.group)
    }

    @Test
    fun `PATCH api chats updates preview name`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id

        val response = c.patch("/api/chats/$chatId") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewName":"Renamed Chat"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Renamed Chat", body["preview_name"]?.jsonPrimitive?.content)

        // Verify persistence
        val saved = repo.loadChatHistory("testuser").chat_history.find { it.chat_id == chatId }
        assertEquals("Renamed Chat", saved?.preview_name)
    }

    @Test
    fun `PATCH api chats updates system prompt`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id

        val response = c.patch("/api/chats/$chatId") {
            contentType(ContentType.Application.Json)
            setBody("""{"systemPrompt":"New system prompt"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val saved = repo.loadChatHistory("testuser").chat_history.find { it.chat_id == chatId }
        assertEquals("New system prompt", saved?.systemPrompt)
    }

    @Test
    fun `PATCH api chats unknown chatId returns 404`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.patch("/api/chats/does-not-exist") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewName":"X"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE api chats removes chat and persists`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id

        val response = c.delete("/api/chats/$chatId")
        assertEquals(HttpStatusCode.NoContent, response.status)

        // Verify removed
        val history = repo.loadChatHistory("testuser")
        assertNull(history.chat_history.find { it.chat_id == chatId }, "Chat should be deleted")
    }

    @Test
    fun `DELETE api chats unknown chatId returns 404`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.delete("/api/chats/non-existent-xyz")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST api chats import creates chat and returns chatId`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        // Export an existing chat to use as valid import content
        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id
        val exportedJson = repo.getChatJson("testuser", chatId)
        assertNotNull(exportedJson, "Should be able to export seeded chat")

        val escaped = exportedJson!!.replace("\\", "\\\\").replace("\"", "\\\"")
        val response = c.post("/api/chats/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"content":"$escaped"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["chatId"]?.jsonPrimitive?.content)
    }

    // ── Messages ==========

    @Test
    fun `POST api chats messages adds message to chat`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id
        val beforeCount = repo.loadChatHistory("testuser").chat_history.first { it.chat_id == chatId }.messages.size

        val response = c.post("/api/chats/$chatId/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"role":"user","text":"Hello from API"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)

        val afterCount = repo.loadChatHistory("testuser").chat_history.first { it.chat_id == chatId }.messages.size
        assertEquals(beforeCount + 1, afterCount, "Message count should increase by 1")
    }

    @Test
    fun `DELETE api chats messages deletes from point`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id
        // Add a second message so we have something to delete from
        val msg2 = Message(role = "assistant", text = "Response")
        repo.addMessageToChat("testuser", chatId, msg2)
        val messageId = repo.loadChatHistory("testuser").chat_history.first { it.chat_id == chatId }.messages.last().id

        val response = c.delete("/api/chats/$chatId/messages/$messageId")
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify message deleted
        val chat = repo.loadChatHistory("testuser").chat_history.find { it.chat_id == chatId }
        assertFalse(chat!!.messages.any { it.id == messageId }, "Deleted message should not exist")
    }

    // ── Branching ==========

    @Test
    fun `POST api chats branch creates a new branch`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id

        // Ensure branching structure is set up
        repo.ensureBranchingStructure("testuser", chatId)
        val chat = repo.loadChatHistory("testuser").chat_history.first { it.chat_id == chatId }
        assertTrue(chat.messageNodes.isNotEmpty(), "Chat should have message nodes after ensure")
        val nodeId = chat.messageNodes.first().nodeId

        val response = c.post("/api/chats/$chatId/branch") {
            contentType(ContentType.Application.Json)
            setBody("""{"nodeId":"$nodeId","newUserMessage":{"role":"user","text":"Alternative"}}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["chat"], "Expected 'chat' in branch response")
        assertNotNull(body["newVariantId"], "Expected 'newVariantId' in branch response")
    }

    @Test
    fun `POST api chats nodes switch changes current variant`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id

        // Build a branched chat: ensure structure, then create a branch
        repo.ensureBranchingStructure("testuser", chatId)
        val chat = repo.loadChatHistory("testuser").chat_history.first { it.chat_id == chatId }
        val nodeId = chat.messageNodes.first().nodeId
        val (branchedChat, _) = repo.createBranch("testuser", chatId, nodeId, Message(role = "user", text = "Alt"))!!

        // Node now has 2 variants — switch to variant 0
        val response = c.post("/api/chats/$chatId/nodes/$nodeId/switch") {
            contentType(ContentType.Application.Json)
            setBody("""{"variantIndex":0}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertNotNull(body.jsonObject["chat_id"])
    }

    @Test
    fun `GET api chats nodes branch-info returns branch info`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id
        // Set up branching structure and create a second branch so the node has >= 2 variants
        repo.ensureBranchingStructure("testuser", chatId)
        val chat = repo.loadChatHistory("testuser").chat_history.first { it.chat_id == chatId }
        val nodeId = chat.messageNodes.first().nodeId
        // Create a branch so the node has > 1 variant (getBranchInfo requires >= 2 variants)
        repo.createBranch("testuser", chatId, nodeId, Message(role = "user", text = "Branch alt"))

        val response = c.get("/api/chats/$chatId/nodes/$nodeId/branch-info")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(nodeId, body["nodeId"]?.jsonPrimitive?.content)
        assertNotNull(body["currentVariantIndex"])
        assertNotNull(body["totalVariants"])
        assertEquals(2, body["totalVariants"]?.jsonPrimitive?.int)
        assertNotNull(body["currentVariantId"])
    }

    @Test
    fun `GET api chats nodes branch-info unknown node returns 404`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id
        val response = c.get("/api/chats/$chatId/nodes/no-such-node/branch-info")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── Groups CRUD ──────────────────────────────────────────────────────────

    @Test
    fun `POST api groups creates group and persists`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.post("/api/groups") {
            contentType(ContentType.Application.Json)
            setBody("""{"groupName":"My New Group"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val groupId = body["group_id"]?.jsonPrimitive?.content
        assertNotNull(groupId)
        assertEquals("My New Group", body["group_name"]?.jsonPrimitive?.content)

        // Verify persistence
        val saved = repo.loadChatHistory("testuser").groups.find { it.group_id == groupId }
        assertNotNull(saved, "Group should be persisted")
        assertEquals("My New Group", saved!!.group_name)
    }

    @Test
    fun `PATCH api groups renames group`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val groupId = repo.loadChatHistory("testuser").groups.first().group_id

        val response = c.patch("/api/groups/$groupId") {
            contentType(ContentType.Application.Json)
            setBody("""{"groupName":"Renamed Group"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Renamed Group", body["group_name"]?.jsonPrimitive?.content)

        val saved = repo.loadChatHistory("testuser").groups.find { it.group_id == groupId }
        assertEquals("Renamed Group", saved?.group_name)
    }

    @Test
    fun `PATCH api groups toggles isProject`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val groupId = repo.loadChatHistory("testuser").groups.first().group_id
        val initialStatus = repo.loadChatHistory("testuser").groups.first().is_project

        val response = c.patch("/api/groups/$groupId") {
            contentType(ContentType.Application.Json)
            setBody("""{"isProject":${!initialStatus}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val saved = repo.loadChatHistory("testuser").groups.find { it.group_id == groupId }
        assertEquals(!initialStatus, saved?.is_project)
    }

    @Test
    fun `DELETE api groups removes group`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val groupId = repo.loadChatHistory("testuser").groups.first().group_id

        val response = c.delete("/api/groups/$groupId")
        assertEquals(HttpStatusCode.NoContent, response.status)

        assertNull(repo.loadChatHistory("testuser").groups.find { it.group_id == groupId },
            "Group should be deleted")
    }

    @Test
    fun `POST api groups chats adds chat to group`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val groupId = repo.loadChatHistory("testuser").groups.first().group_id
        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id

        val response = c.post("/api/groups/$groupId/chats/$chatId")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(true, body["ok"]?.jsonPrimitive?.boolean)

        val saved = repo.loadChatHistory("testuser").chat_history.find { it.chat_id == chatId }
        assertEquals(groupId, saved?.group)
    }

    @Test
    fun `DELETE api groups chats removes chat from group`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val groupId = repo.loadChatHistory("testuser").groups.first().group_id
        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id

        // First add chat to group
        repo.addChatToGroup("testuser", chatId, groupId)

        val response = c.delete("/api/groups/$groupId/chats/$chatId")
        assertEquals(HttpStatusCode.OK, response.status)

        val saved = repo.loadChatHistory("testuser").chat_history.find { it.chat_id == chatId }
        assertNull(saved?.group, "Chat should have no group after removal")
    }

    @Test
    fun `POST api groups attachments adds attachment`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val groupId = repo.loadChatHistory("testuser").groups.first().group_id

        val response = c.post("/api/groups/$groupId/attachments") {
            contentType(ContentType.Application.Json)
            setBody("""{"file_name":"doc.pdf","mime_type":"application/pdf"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)

        val saved = repo.loadChatHistory("testuser").groups.find { it.group_id == groupId }
        assertTrue(saved!!.group_attachments.any { it.file_name == "doc.pdf" })
    }

    @Test
    fun `DELETE api groups attachments removes attachment`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val groupId = repo.loadChatHistory("testuser").groups.first().group_id

        // Add an attachment first
        repo.addAttachmentToGroup("testuser", groupId, Attachment(file_name = "to-remove.pdf", mime_type = "application/pdf"))

        val response = c.delete("/api/groups/$groupId/attachments/0")
        assertEquals(HttpStatusCode.OK, response.status)

        val saved = repo.loadChatHistory("testuser").groups.find { it.group_id == groupId }
        assertTrue(saved!!.group_attachments.isEmpty(), "Attachment should be removed")
    }

    // ── API Keys CRUD ────────────────────────────────────────────────────────

    @Test
    fun `POST api keys adds key and returns masked`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.post("/api/keys") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"anthropic","key":"sk-ant-secret-key-abcd","isActive":true}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Full key should NOT appear; last 4 should
        assertFalse(response.bodyAsText().contains("sk-ant-secret-key-abcd"), "Full key must not appear")
        assertTrue(response.bodyAsText().contains("abcd"), "Last 4 of key should appear masked")

        val keyId = body["id"]?.jsonPrimitive?.content
        assertNotNull(keyId)

        // Verify persistence (stored key is the real key)
        val saved = repo.loadApiKeys("testuser").find { it.id == keyId }
        assertNotNull(saved)
        assertEquals("sk-ant-secret-key-abcd", saved!!.key)
        assertEquals("anthropic", saved.provider)
    }

    @Test
    fun `PATCH api keys toggles isActive`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.patch("/api/keys/key-seed") {
            contentType(ContentType.Application.Json)
            setBody("""{"isActive":false}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val saved = repo.loadApiKeys("testuser").find { it.id == "key-seed" }
        assertEquals(false, saved?.isActive)
    }

    @Test
    fun `PATCH api keys unknown keyId returns 404`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.patch("/api/keys/does-not-exist") {
            contentType(ContentType.Application.Json)
            setBody("""{"isActive":false}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE api keys removes key`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.delete("/api/keys/key-seed")
        assertEquals(HttpStatusCode.NoContent, response.status)

        assertNull(repo.loadApiKeys("testuser").find { it.id == "key-seed" }, "Key should be deleted")
    }

    @Test
    fun `POST api keys toggle flips isActive`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val initial = repo.loadApiKeys("testuser").find { it.id == "key-seed" }!!.isActive

        val response = c.post("/api/keys/key-seed/toggle")
        assertEquals(HttpStatusCode.OK, response.status)

        val saved = repo.loadApiKeys("testuser").find { it.id == "key-seed" }
        assertEquals(!initial, saved?.isActive, "isActive should be flipped")
    }

    @Test
    fun `POST api keys reorder reorders keys`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        // Add a second key so we can reorder
        repo.addApiKey("testuser", ApiKey(id = "key-second", provider = "anthropic", key = "sk-ant-xyz"))

        val response = c.post("/api/keys/reorder") {
            contentType(ContentType.Application.Json)
            setBody("""{"fromIndex":0,"toIndex":1}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val arr = Json.parseToJsonElement(response.bodyAsText()) as JsonArray
        assertEquals(2, arr.size)
    }

    @Test
    fun `PATCH api chats updates share link fields`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val chatId = repo.loadChatHistory("testuser").chat_history.first().chat_id
        val response = c.patch("/api/chats/$chatId") {
            contentType(ContentType.Application.Json)
            setBody("""{"shareLink":"https://api-divonr.xyz/viewer/?id=abc#key=deadbeef","shareId":"abc"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val saved = repo.loadChatHistory("testuser").chat_history.first { it.chat_id == chatId }
        assertEquals("https://api-divonr.xyz/viewer/?id=abc#key=deadbeef", saved.shareLink)
        assertEquals("abc", saved.shareId)

        // Clearing works with empty strings
        c.patch("/api/chats/$chatId") {
            contentType(ContentType.Application.Json)
            setBody("""{"shareLink":"","shareId":""}""")
        }
        val cleared = repo.loadChatHistory("testuser").chat_history.first { it.chat_id == chatId }
        assertEquals("", cleared.shareLink)
        assertEquals("", cleared.shareId)
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    @Test
    fun `PATCH api settings updates selected_model`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.patch("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"selected_model":"claude-opus-4-5"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val saved = repo.loadAppSettings()
        assertEquals("claude-opus-4-5", saved.selected_model, "Model should be updated")
        // Other fields not sent should be unchanged
        assertEquals("openai", saved.selected_provider, "Provider should be unchanged")
    }

    @Test
    fun `PATCH api settings updates titleGenerationSettings`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.patch("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"titleGenerationSettings":{"enabled":false,"provider":"openai","updateOnExtension":false}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val saved = repo.loadAppSettings()
        assertEquals(false, saved.titleGenerationSettings.enabled)
        assertEquals("openai", saved.titleGenerationSettings.provider)
        assertEquals(false, saved.titleGenerationSettings.updateOnExtension)
    }

    @Test
    fun `PATCH api settings updates enabledTools and excludedToolIds`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.patch("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabledTools":["get_date_time","python_interpreter"],"excludedToolIds":["python_interpreter"]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val saved = repo.loadAppSettings()
        assertEquals(listOf("get_date_time", "python_interpreter"), saved.enabledTools)
        assertEquals(listOf("python_interpreter"), saved.excludedToolIds)

        // Patching another field must not clobber the tool lists
        c.patch("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"skipWelcomeScreen":true}""")
        }
        assertEquals(listOf("get_date_time", "python_interpreter"), repo.loadAppSettings().enabledTools)
    }

    @Test
    fun `PATCH api settings does not clobber unset fields`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        // Only update one field
        c.patch("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"skipWelcomeScreen":true}""")
        }

        val saved = repo.loadAppSettings()
        // current_user, selected_provider, selected_model must all still be intact
        assertEquals("testuser", saved.current_user)
        assertEquals("openai", saved.selected_provider)
        assertEquals("gpt-4o", saved.selected_model)
        assertEquals(true, saved.skipWelcomeScreen)
    }

    // ── Custom Providers ─────────────────────────────────────────────────────

    @Test
    fun `POST api custom-providers creates provider`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.post("/api/custom-providers") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Local LLM","providerKey":"custom_local","baseUrl":"http://localhost:1234","defaultModel":"local-model"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)

        val saved = repo.loadCustomProviders("testuser")
        assertTrue(saved.any { it.name == "Local LLM" }, "Custom provider should be persisted")
    }

    @Test
    fun `PUT api custom-providers updates provider`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val providerId = "cp-test-id"
        repo.addCustomProvider(
            "testuser",
            CustomProviderConfig(id = providerId, name = "Old Name", providerKey = "custom_old", baseUrl = "http://old:1234", defaultModel = "old-model")
        )

        val response = c.put("/api/custom-providers/$providerId") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$providerId","name":"New Name","providerKey":"custom_old","baseUrl":"http://new:5678","defaultModel":"new-model"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val saved = repo.loadCustomProviders("testuser").find { it.id == providerId }
        assertEquals("New Name", saved?.name)
        assertEquals("http://new:5678", saved?.baseUrl)
    }

    @Test
    fun `DELETE api custom-providers removes provider`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val providerId = "cp-to-delete"
        repo.addCustomProvider(
            "testuser",
            CustomProviderConfig(id = providerId, name = "Delete Me", providerKey = "custom_delete_me", baseUrl = "http://localhost", defaultModel = "m")
        )

        val response = c.delete("/api/custom-providers/$providerId")
        assertEquals(HttpStatusCode.NoContent, response.status)

        assertNull(repo.loadCustomProviders("testuser").find { it.id == providerId })
    }

    @Test
    fun `GET api custom-providers returns empty list initially`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.get("/api/custom-providers")
        assertEquals(HttpStatusCode.OK, response.status)
        val arr = Json.parseToJsonElement(response.bodyAsText())
        assertTrue(arr is JsonArray, "Expected array")
    }

    // ── Full Custom Providers ────────────────────────────────────────────────

    @Test
    fun `POST api full-custom-providers creates provider`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.post("/api/full-custom-providers") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Full Custom","providerKey":"fullcustom_test","baseUrl":"http://fc:9999","defaultModel":"fc-model","bodyTemplate":"{\"model\":\"{model}\",\"messages\":[{prompt}]}"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)

        val saved = repo.loadFullCustomProviders("testuser")
        assertTrue(saved.any { it.name == "Full Custom" }, "Full custom provider should be persisted")
    }

    @Test
    fun `DELETE api full-custom-providers removes provider`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val fcp = FullCustomProviderConfig(
            id = "fcp-to-del",
            name = "Del Me Full",
            providerKey = "fullcustom_del_me",
            baseUrl = "http://x",
            defaultModel = "x",
            bodyTemplate = "{}"
        )
        repo.addFullCustomProvider("testuser", fcp)

        val response = c.delete("/api/full-custom-providers/fcp-to-del")
        assertEquals(HttpStatusCode.NoContent, response.status)

        assertNull(repo.loadFullCustomProviders("testuser").find { it.id == "fcp-to-del" })
    }

    // ── Skills ───────────────────────────────────────────────────────────────

    @Test
    fun `GET api skills returns empty list initially`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.get("/api/skills")
        assertEquals(HttpStatusCode.OK, response.status)
        val arr = Json.parseToJsonElement(response.bodyAsText())
        assertTrue(arr is JsonArray, "Expected JSON array for skills: ${response.bodyAsText()}")
    }

    @Test
    fun `POST api skills creates skill and is listed`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.post("/api/skills") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-skill","description":"A test skill","body":"## Instructions\nDo stuff"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("my-skill", body["directoryName"]?.jsonPrimitive?.content)

        // Verify via GET
        val listResponse = c.get("/api/skills")
        val arr = Json.parseToJsonElement(listResponse.bodyAsText()) as JsonArray
        assertTrue(arr.any { it.jsonObject["directoryName"]?.jsonPrimitive?.content == "my-skill" },
            "Skill should appear in list")

        // Verify via repository
        val skills = repo.getInstalledSkills()
        assertTrue(skills.any { it.directoryName == "my-skill" }, "Skill should be in repo")
    }

    @Test
    fun `PATCH api skills toggles enabled state`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        // Create a skill first
        repo.createSkill("toggle-skill", "Toggle test", "body")

        val response = c.patch("/api/skills/toggle-skill") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify via repository
        val saved = repo.getInstalledSkills().find { it.directoryName == "toggle-skill" }
        assertEquals(false, saved?.isEnabled, "Skill should be disabled")
    }

    @Test
    fun `DELETE api skills removes skill`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        repo.createSkill("del-skill", "Delete test", "")

        val response = c.delete("/api/skills/del-skill")
        assertEquals(HttpStatusCode.NoContent, response.status)

        assertNull(repo.getInstalledSkills().find { it.directoryName == "del-skill" },
            "Skill should be deleted")
    }

    @Test
    fun `GET api skills skillName returns skill content`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        repo.createSkill("content-skill", "Content test", "## My Skill\nDoes things")

        val response = c.get("/api/skills/content-skill")
        assertEquals(HttpStatusCode.OK, response.status)
        // Content should be text/plain
        val ct = response.contentType()
        assertNotNull(ct)
        assertTrue(ct!!.match(ContentType.Text.Plain), "Expected text/plain, got $ct")
    }

    @Test
    fun `GET api skills unknown skill returns 404`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.get("/api/skills/no-such-skill")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PATCH api skills unknown skill returns 404`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.patch("/api/skills/no-such-skill") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":true}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── Skills export (ZIP) ───────────────────────────────────────────────────

    @Test
    fun `GET api skills skillName export returns zip bytes for known skill`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        repo.createSkill("export-skill", "Export test", "## Instructions\nDo things")

        val response = c.get("/api/skills/export-skill/export")
        assertEquals(HttpStatusCode.OK, response.status)
        val ct = response.contentType()
        assertNotNull(ct)
        assertTrue(ct!!.match(ContentType.parse("application/zip")), "Expected application/zip, got $ct")
        val bytes = response.readBytes()
        assertTrue(bytes.isNotEmpty(), "ZIP bytes should not be empty")

        // Verify zip contains SKILL.md entry
        val zis = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
        val entries = mutableListOf<String>()
        var entry = zis.nextEntry
        while (entry != null) {
            entries.add(entry.name)
            zis.closeEntry()
            entry = zis.nextEntry
        }
        zis.close()
        assertTrue(entries.any { it.endsWith("SKILL.md") }, "ZIP should contain SKILL.md, entries: $entries")
    }

    @Test
    fun `GET api skills unknown skill export returns 404`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        val response = c.get("/api/skills/no-such-skill/export")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── Skills import-zip ────────────────────────────────────────────────────

    @Test
    fun `POST api skills import-zip installs skill from valid zip`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        // Build an in-memory ZIP containing SKILL.md
        val skillMdContent = "---\nname: zip-skill\ndescription: Imported from ZIP\n---\n\n# Zip Skill\n\nDoes things."
        val zipBytes = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("zip-skill/SKILL.md"))
                zos.write(skillMdContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }.toByteArray()

        val response = c.post("/api/skills/import-zip") {
            setBody(
                io.ktor.client.request.forms.MultiPartFormDataContent(
                    io.ktor.client.request.forms.formData {
                        append("file", zipBytes, io.ktor.http.Headers.build {
                            append(io.ktor.http.HttpHeaders.ContentDisposition, "filename=\"zip-skill.zip\"")
                            append(io.ktor.http.HttpHeaders.ContentType, "application/zip")
                        })
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("zip-skill", body["directoryName"]?.jsonPrimitive?.content)

        // Verify via repository
        assertTrue(repo.getInstalledSkills().any { it.directoryName == "zip-skill" }, "Skill should be installed")
    }

    @Test
    fun `POST api skills import-zip returns 400 when zip lacks SKILL md`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val c = loggedInClient()

        // Build an in-memory ZIP WITHOUT SKILL.md
        val zipBytes = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("readme.txt"))
                zos.write("no skill here".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }.toByteArray()

        val response = c.post("/api/skills/import-zip") {
            setBody(
                io.ktor.client.request.forms.MultiPartFormDataContent(
                    io.ktor.client.request.forms.formData {
                        append("file", zipBytes, io.ktor.http.Headers.build {
                            append(io.ktor.http.HttpHeaders.ContentDisposition, "filename=\"bad.zip\"")
                            append(io.ktor.http.HttpHeaders.ContentType, "application/zip")
                        })
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
