package com.example.ApI.server

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Message
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for POST /api/chats/{chatId}/generate-title.
 *
 * A [FakeTitleGenerator] is injected via [titleGeneratorFactory] so no real
 * LLM network calls are made during tests.
 */
class GenerateTitleTest {

    private val testPassword = "gt-test-password"
    private val testAuthConfig = AuthConfig(
        password = testPassword,
        sessionSecret = "gt-test-session-secret-that-is-long-enough"
    )

    private fun tempStorage(): ServerPlatformStorage {
        val dir = File(System.getProperty("java.io.tmpdir"), "gt-test-${System.nanoTime()}")
        dir.mkdirs()
        return ServerPlatformStorage(baseDir = dir)
    }

    private fun seededStorage(withMessages: Boolean = true): Triple<ServerPlatformStorage, DataRepository, String> {
        val storage = tempStorage()
        val repo = DataRepository(storage)
        repo.saveAppSettings(
            AppSettings(
                current_user = "testuser",
                selected_provider = "openai",
                selected_model = "gpt-4o"
            )
        )
        val chat = repo.createNewChat("testuser", "Old Title")
        if (withMessages) {
            repo.addMessageToChat(
                "testuser", chat.chat_id,
                Message(role = "user", text = "What is Kotlin?")
            )
        }
        return Triple(storage, repo, chat.chat_id)
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
        application { module(storage, testAuthConfig) }

        val response = client.post("/api/chats/$chatId/generate-title") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"auto"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST generate-title unknown chatId returns 404`() = testApplication {
        val (storage, _, _) = seededStorage()
        application { module(storage, testAuthConfig, titleGeneratorFactory = { _ -> fakeTitleGenerator }) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.post("/api/chats/non-existent-chat-id/generate-title") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"auto"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("not found", ignoreCase = true))
    }

    @Test
    fun `POST generate-title returns title and persists it`() = testApplication {
        val (storage, repo, chatId) = seededStorage()
        application { module(storage, testAuthConfig, titleGeneratorFactory = { _ -> fakeTitleGenerator }) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.post("/api/chats/$chatId/generate-title") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"auto"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Kotlin Explained", body["title"]?.jsonPrimitive?.content)

        // Verify the title was persisted to storage
        val persistedChat = repo.loadChatHistory("testuser").chat_history.find { it.chat_id == chatId }
        assertNotNull(persistedChat)
        assertEquals("Kotlin Explained", persistedChat!!.preview_name)
    }

    @Test
    fun `POST generate-title with body omitted still works (defaults to auto)`() = testApplication {
        val (storage, _, chatId) = seededStorage()
        application { module(storage, testAuthConfig, titleGeneratorFactory = { _ -> fakeTitleGenerator }) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        // Empty/missing body — route defaults to auto
        val response = cookieClient.post("/api/chats/$chatId/generate-title") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST generate-title generation failure returns 500`() = testApplication {
        val (storage, _, chatId) = seededStorage()
        application { module(storage, testAuthConfig, titleGeneratorFactory = { _ -> failingTitleGenerator }) }
        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.post("/api/chats/$chatId/generate-title") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"auto"}""")
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("failed", ignoreCase = true))
    }
}
