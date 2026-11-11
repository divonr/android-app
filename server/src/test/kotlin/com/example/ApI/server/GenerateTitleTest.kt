package com.example.ApI.server

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Message
import com.example.ApI.data.repository.DataRepository
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
 * Tests for POST /api/chats/{chatId}/generate-title.
 *
 * A [FakeTitleGenerator] is injected via [titleGeneratorFactory] so no real
 * LLM network calls are made during tests.  Auth uses [googleLogin].
 */
class GenerateTitleTest {

    private fun seededStorage(withMessages: Boolean = true): Triple<ServerPlatformStorage, DataRepository, String> {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "gt-test-${System.nanoTime()}")
        baseDir.mkdirs()
        val rootStorage = ServerPlatformStorage(baseDir = baseDir)
        val userDir = File(baseDir, "users/$TEST_USERNAME")
        userDir.mkdirs()
        val userStorage = ServerPlatformStorage(baseDir = userDir)
        val repo = DataRepository(userStorage)
        repo.saveAppSettings(
            AppSettings(
                current_user = TEST_USERNAME,
                selected_provider = "openai",
                selected_model = "gpt-4o"
            )
        )
        val chat = repo.createNewChat(TEST_USERNAME, "Old Title")
        if (withMessages) {
            repo.addMessageToChat(
                TEST_USERNAME, chat.chat_id,
                Message(role = "user", text = "What is Kotlin?")
            )
        }
        return Triple(rootStorage, repo, chat.chat_id)
    }

    /** Fake that returns a deterministic title without LLM calls. */
    private val fakeTitleGenerator = TitleGenerator { _, _, _ -> "Kotlin Explained" }

    /** Fake that always throws to simulate generation failure. */
    private val failingTitleGenerator = TitleGenerator { _, _, _ ->
        throw Exception("LLM unavailable")
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `POST generate-title without auth returns 401`() = testApplication {
        val (storage, _, chatId) = seededStorage()
        startWithFakeGoogleAuth(storage)

        val response = client.post("/api/chats/$chatId/generate-title") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"auto"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST generate-title unknown chatId returns 404`() = testApplication {
        val (storage, _, _) = seededStorage()
        startWithFakeGoogleAuth(storage, titleGeneratorFactory = { _ -> fakeTitleGenerator })
        val c = googleLogin(TEST_USER_EMAIL)

        val response = c.post("/api/chats/non-existent-chat-id/generate-title") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"auto"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("not found", ignoreCase = true))
    }

    @Test
    fun `POST generate-title returns title and persists it`() = testApplication {
        val (storage, repo, chatId) = seededStorage()
        startWithFakeGoogleAuth(storage, titleGeneratorFactory = { _ -> fakeTitleGenerator })
        val c = googleLogin(TEST_USER_EMAIL)

        val response = c.post("/api/chats/$chatId/generate-title") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"auto"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Kotlin Explained", body["title"]?.jsonPrimitive?.content)

        // Verify the title was persisted to storage
        val persistedChat = repo.loadChatHistory(TEST_USERNAME).chat_history.find { it.chat_id == chatId }
        assertNotNull(persistedChat)
        assertEquals("Kotlin Explained", persistedChat!!.preview_name)
    }

    @Test
    fun `POST generate-title with body omitted still works (defaults to auto)`() = testApplication {
        val (storage, _, chatId) = seededStorage()
        startWithFakeGoogleAuth(storage, titleGeneratorFactory = { _ -> fakeTitleGenerator })
        val c = googleLogin(TEST_USER_EMAIL)

        // Empty/missing body — route defaults to auto
        val response = c.post("/api/chats/$chatId/generate-title") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST generate-title generation failure returns 500`() = testApplication {
        val (storage, _, chatId) = seededStorage()
        startWithFakeGoogleAuth(storage, titleGeneratorFactory = { _ -> failingTitleGenerator })
        val c = googleLogin(TEST_USER_EMAIL)

        val response = c.post("/api/chats/$chatId/generate-title") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"auto"}""")
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("failed", ignoreCase = true))
    }
}
