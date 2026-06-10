package com.example.ApI.server

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.repository.DataRepository
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2 route tests for all read (GET) API endpoints.
 *
 * Each test class creates an isolated temp directory so nothing touches
 * the real ~/.llm-api-web data.
 *
 * Auth helper: [authenticatedClient] logs in and returns a client that
 * carries the session cookie — reusable by P3 and P4 test suites.
 */
class ReadApisTest {

    // ── Test infrastructure ──────────────────────────────────────────────────

    private val testPassword = "p2-test-password"
    private val testAuthConfig = AuthConfig(
        password = testPassword,
        sessionSecret = "p2-test-session-secret-that-is-long-enough"
    )

    /** Creates a fresh temp [ServerPlatformStorage] for each test. */
    private fun tempStorage(): ServerPlatformStorage {
        val dir = File(System.getProperty("java.io.tmpdir"), "p2-test-${System.nanoTime()}")
        dir.mkdirs()
        return ServerPlatformStorage(baseDir = dir)
    }

    /**
     * Seeds repository with baseline data and returns the root storage (for injection
     * into [module]) plus a reference to the per-user repository so tests can query
     * seeded IDs.
     *
     * After Step 5a, password login maps to username "default" and routes look up data
     * via `registry.context("default")` → `users/default/` subdir.  We seed there so
     * the HTTP routes can see the data.
     *
     * Seeded state:
     * - AppSettings: current_user = "default"
     * - 1 Chat with 1 message
     * - 1 ChatGroup
     * - 1 ApiKey (active)
     */
    private fun seededStorage(): Pair<ServerPlatformStorage, DataRepository> {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "p2-test-${System.nanoTime()}")
        baseDir.mkdirs()
        val rootStorage = ServerPlatformStorage(baseDir = baseDir)
        val userDir = File(baseDir, "users/default")
        userDir.mkdirs()
        val userStorage = ServerPlatformStorage(baseDir = userDir)
        val repo = DataRepository(userStorage)

        // Save settings so current_user is "default"
        repo.saveAppSettings(
            AppSettings(
                current_user = "default",
                selected_provider = "openai",
                selected_model = "gpt-4o"
            )
        )

        // Create a chat with a message
        val chat = repo.createNewChat("default", "Hello World Chat")
        repo.addMessageToChat(
            "default", chat.chat_id,
            com.example.ApI.data.model.Message(role = "user", text = "Hello!")
        )

        // Create a group
        repo.createNewGroup("default", "Test Group")

        // Add an API key
        repo.addApiKey(
            "default",
            ApiKey(
                id = "key-1234",
                provider = "openai",
                key = "sk-thisisafakeapikey1234",
                isActive = true,
                customName = "My OpenAI Key"
            )
        )

        return rootStorage to repo
    }

    /**
     * Shared helper that logs in and returns a cookie-aware Ktor test client.
     * P3 and P4 tests can import this pattern (or a similar helper) for auth.
     */
    private fun ApplicationTestBuilder.authenticatedClient(
        password: String = testPassword
    ) = createClient {
        install(HttpCookies)
    }.also { client ->
        // Note: we can't call suspend from here; callers must login manually.
        // The login itself is done in each test's setup block.
        Unit
    }

    // ── 401 sanity checks (no session) ───────────────────────────────────────

    @Test
    fun `GET api providers without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val response = client.get("/api/providers")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api settings without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val response = client.get("/api/settings")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api chats without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val response = client.get("/api/chats")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api groups without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val response = client.get("/api/groups")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api keys without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val response = client.get("/api/keys")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api search without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val response = client.get("/api/search?q=hello")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ── GET /api/providers ───────────────────────────────────────────────────

    @Test
    fun `GET api providers with auth returns 200 and array`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.get("/api/providers")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertTrue(body is JsonArray, "Expected JSON array, got: ${response.bodyAsText()}")
    }

    // GET /api/providers/models

    @Test
    fun `GET api providers models with auth returns 200 and flat array`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.get("/api/providers/models")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertTrue(body is JsonArray, "Expected JSON array for /api/providers/models")
        // Each item (if any) must have provider and modelName fields
        (body as JsonArray).forEach { item ->
            assertNotNull(item.jsonObject["provider"], "Missing 'provider' field")
            assertNotNull(item.jsonObject["modelName"], "Missing 'modelName' field")
        }
    }

    // ── GET /api/settings ────────────────────────────────────────────────────

    @Test
    fun `GET api settings returns 200 with current user`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.get("/api/settings")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("default"), "Expected current_user in settings body: $body")
        assertTrue(body.contains("openai"), "Expected selected_provider in settings body: $body")
    }

    // ── GET /api/chats ───────────────────────────────────────────────────────

    @Test
    fun `GET api chats returns 200 with seeded chat`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.get("/api/chats")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Hello World Chat"), "Expected seeded chat name: $body")
        assertTrue(body.contains("chat_history"), "Expected chat_history field: $body")
    }

    // ── GET /api/chats/{chatId} ──────────────────────────────────────────────

    @Test
    fun `GET api chats chatId returns 200 for existing chat`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        // Get the seeded chat ID
        val chatId = repo.loadChatHistory("default").chat_history.first().chat_id
        val response = cookieClient.get("/api/chats/$chatId")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Hello World Chat"), "Expected chat name in response: $body")
        assertTrue(body.contains(chatId), "Expected chatId in response: $body")
    }

    @Test
    fun `GET api chats unknown chatId returns 404`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.get("/api/chats/non-existent-chat-id-xyz")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("error"), "Expected error field in 404 body: $body")
        // The StatusPages plugin may rewrite the body (e.g. "Not found"), or the route
        // itself may return "Chat not found" — both are valid 404 JSON responses.
        assertTrue(
            body.contains("not found", ignoreCase = true) || body.contains("Not found"),
            "Expected 'not found' in error message (case-insensitive): $body"
        )
    }

    // ── GET /api/groups ──────────────────────────────────────────────────────

    @Test
    fun `GET api groups returns 200 with seeded group`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.get("/api/groups")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val arr = Json.parseToJsonElement(body)
        assertTrue(arr is JsonArray, "Expected JSON array for groups: $body")
        assertTrue(body.contains("Test Group"), "Expected seeded group name: $body")
    }

    // ── GET /api/keys — masking ──────────────────────────────────────────────

    @Test
    fun `GET api keys returns 200 with masked secret`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.get("/api/keys")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // Full key must NOT appear
        assertFalse(
            body.contains("sk-thisisafakeapikey1234"),
            "Full API key must not appear in response: $body"
        )
        // Provider and isActive should be present
        assertTrue(body.contains("openai"), "Expected provider in keys response: $body")
        // Masked suffix (last 4 = "1234") should appear
        assertTrue(body.contains("1234"), "Expected masked last-4 in keys response: $body")
    }

    @Test
    fun `GET api keys returns key id provider and isActive`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.get("/api/keys")
        assertEquals(HttpStatusCode.OK, response.status)
        val arr = Json.parseToJsonElement(response.bodyAsText()) as JsonArray
        assertTrue(arr.isNotEmpty(), "Expected at least one key")
        val key = arr[0].jsonObject
        assertNotNull(key["id"], "Missing id field")
        assertNotNull(key["provider"], "Missing provider field")
        assertNotNull(key["isActive"], "Missing isActive field")
        assertEquals("key-1234", key["id"]?.jsonPrimitive?.content)
        assertTrue(key["isActive"]?.jsonPrimitive?.boolean == true)
    }

    // ── GET /api/search ──────────────────────────────────────────────────────

    @Test
    fun `GET api search with blank query returns empty array`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.get("/api/search?q=")
        assertEquals(HttpStatusCode.OK, response.status)
        val arr = Json.parseToJsonElement(response.bodyAsText())
        assertTrue(arr is JsonArray && arr.isEmpty(), "Expected empty array: ${response.bodyAsText()}")
    }

    @Test
    fun `GET api search returns matching results`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        val response = cookieClient.get("/api/search?q=Hello")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val arr = Json.parseToJsonElement(body) as JsonArray
        // Should find the seeded chat (title "Hello World Chat") or message "Hello!"
        assertTrue(arr.isNotEmpty(), "Expected search results for 'Hello': $body")
        val first = arr[0].jsonObject
        assertNotNull(first["chatId"], "Missing chatId in search result")
        assertNotNull(first["chatTitle"], "Missing chatTitle in search result")
        assertNotNull(first["matchType"], "Missing matchType in search result")
    }
}
