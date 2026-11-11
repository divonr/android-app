package com.example.ApI.server

import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2 route tests for all read (GET) API endpoints.
 *
 * Each test creates an isolated temp directory so nothing touches
 * the real ~/.llm-api-web data.
 *
 * Auth: all authenticated tests use [googleLogin] (fake Google login) backed by
 * data seeded under `users/test_example_com/` ([TEST_USERNAME]).
 */
class ReadApisTest {

    // ── Test infrastructure ──────────────────────────────────────────────────

    /**
     * Seeds repository with baseline data for [TEST_USERNAME] and returns the
     * root storage (for injection into [module]) plus a reference to the
     * per-user repository so tests can query seeded IDs.
     *
     * Seeded state:
     * - AppSettings: current_user = [TEST_USERNAME]
     * - 1 Chat with 1 message
     * - 1 ChatGroup
     * - 1 ApiKey (active)
     */
    private fun seededStorage() = seededStorageForUser(TEST_USERNAME)

    // ── 401 sanity checks (no session) ───────────────────────────────────────

    @Test
    fun `GET api providers without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val response = client.get("/api/providers")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api settings without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val response = client.get("/api/settings")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api chats without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val response = client.get("/api/chats")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api groups without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val response = client.get("/api/groups")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api keys without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val response = client.get("/api/keys")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api search without auth returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val response = client.get("/api/search?q=hello")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ── GET /api/providers ───────────────────────────────────────────────────

    @Test
    fun `GET api providers with auth returns 200 and array`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)
        val response = c.get("/api/providers")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertTrue(body is JsonArray, "Expected JSON array, got: ${response.bodyAsText()}")
    }

    // GET /api/providers/models

    @Test
    fun `GET api providers models with auth returns 200 and flat array`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)
        val response = c.get("/api/providers/models")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertTrue(body is JsonArray, "Expected JSON array for /api/providers/models")
        (body as JsonArray).forEach { item ->
            assertNotNull(item.jsonObject["provider"], "Missing 'provider' field")
            assertNotNull(item.jsonObject["modelName"], "Missing 'modelName' field")
        }
    }

    // ── GET /api/settings ────────────────────────────────────────────────────

    @Test
    fun `GET api settings returns 200 with current user`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)
        val response = c.get("/api/settings")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(TEST_USERNAME), "Expected current_user=$TEST_USERNAME in settings: $body")
        assertTrue(body.contains("openai"), "Expected selected_provider in settings: $body")
    }

    // ── GET /api/chats ───────────────────────────────────────────────────────

    @Test
    fun `GET api chats returns 200 with seeded chat`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)
        val response = c.get("/api/chats")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Hello World Chat"), "Expected seeded chat name: $body")
        assertTrue(body.contains("chat_history"), "Expected chat_history field: $body")
    }

    // ── GET /api/chats/{chatId} ──────────────────────────────────────────────

    @Test
    fun `GET api chats chatId returns 200 for existing chat`() = testApplication {
        val (storage, repo) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)
        val chatId = repo.loadChatHistory(TEST_USERNAME).chat_history.first().chat_id
        val response = c.get("/api/chats/$chatId")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Hello World Chat"), "Expected chat name in response: $body")
        assertTrue(body.contains(chatId), "Expected chatId in response: $body")
    }

    @Test
    fun `GET api chats unknown chatId returns 404`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)
        val response = c.get("/api/chats/non-existent-chat-id-xyz")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("error"), "Expected error field in 404 body: $body")
        assertTrue(
            body.contains("not found", ignoreCase = true) || body.contains("Not found"),
            "Expected 'not found' in error message: $body"
        )
    }

    // ── GET /api/groups ──────────────────────────────────────────────────────

    @Test
    fun `GET api groups returns 200 with seeded group`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)
        val response = c.get("/api/groups")
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
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)
        val response = c.get("/api/keys")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertFalse(
            body.contains("sk-thisisafakeapikey1234"),
            "Full API key must not appear in response: $body"
        )
        assertTrue(body.contains("openai"), "Expected provider in keys response: $body")
        assertTrue(body.contains("1234"), "Expected masked last-4 in keys response: $body")
    }

    @Test
    fun `GET api keys returns key id provider and isActive`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)
        val response = c.get("/api/keys")
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
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)
        val response = c.get("/api/search?q=")
        assertEquals(HttpStatusCode.OK, response.status)
        val arr = Json.parseToJsonElement(response.bodyAsText())
        assertTrue(arr is JsonArray && arr.isEmpty(), "Expected empty array: ${response.bodyAsText()}")
    }

    @Test
    fun `GET api search returns matching results`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)
        val response = c.get("/api/search?q=Hello")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val arr = Json.parseToJsonElement(body) as JsonArray
        assertTrue(arr.isNotEmpty(), "Expected search results for 'Hello': $body")
        val first = arr[0].jsonObject
        assertNotNull(first["chatId"], "Missing chatId in search result")
        assertNotNull(first["chatTitle"], "Missing chatTitle in search result")
        assertNotNull(first["matchType"], "Missing matchType in search result")
    }
}
