package com.example.ApI.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per-user data isolation tests.
 *
 * Verifies that user A's session cannot read user B's chats, keys, or any other
 * per-user data — and vice versa.
 */
class IsolationTest {

    private val userAEmail = "alice@example.com"
    private val userBEmail = "bob@example.com"

    // ── Chat isolation ────────────────────────────────────────────────────────

    @Test
    fun `user B cannot see user A's chats in list`() = testApplication {
        startWithFakeGoogleAuth(tempStorage())
        val clientA = googleLogin(userAEmail)
        val clientB = googleLogin(userBEmail)

        // A creates a chat
        clientA.post("/api/chats") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewName":"Alice Secret Chat"}""")
        }

        // B lists chats — should not contain A's chat
        val bChatsResponse = clientB.get("/api/chats")
        assertEquals(HttpStatusCode.OK, bChatsResponse.status)
        assertFalse(
            bChatsResponse.bodyAsText().contains("Alice Secret Chat"),
            "User B should not see User A's chats. Body: ${bChatsResponse.bodyAsText()}"
        )
    }

    @Test
    fun `user B cannot GET user A's chat by id returns 404`() = testApplication {
        startWithFakeGoogleAuth(tempStorage())
        val clientA = googleLogin(userAEmail)
        val clientB = googleLogin(userBEmail)

        // A creates a chat
        val createResponse = clientA.post("/api/chats") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewName":"Alice's Private Chat"}""")
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val chatId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["chat_id"]?.jsonPrimitive?.content
            ?: error("No chat_id in response")

        // B tries to get A's chat by ID
        val bGetResponse = clientB.get("/api/chats/$chatId")
        assertEquals(HttpStatusCode.NotFound, bGetResponse.status,
            "User B should get 404 for User A's chat id")
    }

    @Test
    fun `user A still sees its chat after user B logs in`() = testApplication {
        startWithFakeGoogleAuth(tempStorage())
        val clientA = googleLogin(userAEmail)
        val clientB = googleLogin(userBEmail)

        // A creates a chat
        val createResponse = clientA.post("/api/chats") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewName":"A's Persistent Chat"}""")
        }
        val chatId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["chat_id"]?.jsonPrimitive?.content!!

        // A can still retrieve it after B has also logged in
        val aGetResponse = clientA.get("/api/chats/$chatId")
        assertEquals(HttpStatusCode.OK, aGetResponse.status,
            "User A should still see its own chat")
        assertTrue(
            aGetResponse.bodyAsText().contains("A's Persistent Chat"),
            "User A's chat content should be intact"
        )
    }

    // ── API key isolation ─────────────────────────────────────────────────────

    @Test
    fun `user B has empty keys list when user A has keys`() = testApplication {
        startWithFakeGoogleAuth(tempStorage())
        val clientA = googleLogin(userAEmail)
        val clientB = googleLogin(userBEmail)

        // A adds an API key
        val addResponse = clientA.post("/api/keys") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"openai","key":"sk-alice-secret-key-xyz","isActive":true}""")
        }
        assertEquals(HttpStatusCode.Created, addResponse.status)

        // B's keys list should be empty
        val bKeysResponse = clientB.get("/api/keys")
        assertEquals(HttpStatusCode.OK, bKeysResponse.status)
        val arr = Json.parseToJsonElement(bKeysResponse.bodyAsText()) as JsonArray
        assertTrue(arr.isEmpty(),
            "User B should have an empty keys list, got: ${bKeysResponse.bodyAsText()}")
    }

    // ── Chats list isolation (empty for new user) ─────────────────────────────

    @Test
    fun `new user B starts with empty chat history`() = testApplication {
        startWithFakeGoogleAuth(tempStorage())
        val clientA = googleLogin(userAEmail)
        val clientB = googleLogin(userBEmail)

        // A creates several chats
        repeat(3) { i ->
            clientA.post("/api/chats") {
                contentType(ContentType.Application.Json)
                setBody("""{"previewName":"Alice Chat $i"}""")
            }
        }

        // B's chat history should be empty (no chats)
        val bChats = clientB.get("/api/chats")
        val history = Json.parseToJsonElement(bChats.bodyAsText()).jsonObject
        val chatArray = history["chat_history"] as? JsonArray ?: JsonArray(emptyList())
        assertTrue(chatArray.isEmpty(),
            "User B should have no chats, got: ${bChats.bodyAsText()}")
    }

    // ── Mutation isolation ────────────────────────────────────────────────────

    @Test
    fun `user B cannot delete user A's chat`() = testApplication {
        startWithFakeGoogleAuth(tempStorage())
        val clientA = googleLogin(userAEmail)
        val clientB = googleLogin(userBEmail)

        // A creates a chat
        val createResponse = clientA.post("/api/chats") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewName":"A's Chat To Protect"}""")
        }
        val chatId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["chat_id"]?.jsonPrimitive?.content!!

        // B tries to delete A's chat
        val bDeleteResponse = clientB.delete("/api/chats/$chatId")
        assertEquals(HttpStatusCode.NotFound, bDeleteResponse.status,
            "User B should not be able to delete User A's chat")

        // A's chat still exists
        val aGetResponse = clientA.get("/api/chats/$chatId")
        assertEquals(HttpStatusCode.OK, aGetResponse.status,
            "User A's chat should still exist after B's failed delete attempt")
    }
}
