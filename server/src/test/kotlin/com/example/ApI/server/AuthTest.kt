package com.example.ApI.server

import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the session/auth layer after Step 5b (Google-only login).
 *
 * No password login exists.  A session is only valid when minted by the Google
 * login callback.  All tests use the [FakeGoogleTokenVerifier] / [FakeGoogleLoginExchanger] /
 * [FakeSyncAuthClient] injection pattern from [TestAuth.kt].
 */
class AuthTest {

    private val authConfig = AuthConfig(
        sessionSecret = "test-session-secret-that-is-long-enough-for-hmac"
    )

    // ── 401 without session ──────────────────────────────────────────────────

    @Test
    fun `GET api session without cookie returns 401`() = testApplication {
        application { module(tempStorage(), authConfig) }
        val response = client.get("/api/session")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api chats without cookie returns 401`() = testApplication {
        application { module(tempStorage(), authConfig) }
        val response = client.get("/api/chats")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `401 on protected route has JSON error body`() = testApplication {
        application { module(tempStorage(), authConfig) }
        val response = client.get("/api/session")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("error") && body.contains("unauthorized"),
            "Expected JSON error body with 'error' and 'unauthorized', got: $body")
    }

    // ── Health is public ─────────────────────────────────────────────────────

    @Test
    fun `GET health is public and returns 200 without auth`() = testApplication {
        application { module(tempStorage(), authConfig) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"), "Body: ${response.bodyAsText()}")
    }

    // ── Google login callback — invalid params ───────────────────────────────

    @Test
    fun `GET auth google callback with missing params returns 400`() = testApplication {
        application { module(tempStorage(), authConfig) }
        val response = client.get("/auth/google/callback")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET auth google callback with blank state returns 400`() = testApplication {
        application { module(tempStorage(), authConfig) }
        val response = client.get("/auth/google/callback") {
            parameter("code", "some-code")
            // no state
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET auth google callback with wrong state redirects to invalid_state`() = testApplication {
        startWithFakeGoogleAuth(tempStorage())
        val cookieClient = createClient { install(HttpCookies); followRedirects = false }
        val response = cookieClient.get("/auth/google/callback") {
            parameter("code", "fake-id-token-$TEST_USER_EMAIL")
            parameter("state", "totally-wrong-state")
        }
        // Should redirect to /login?error=invalid_state (no valid session state in cookie)
        val location = response.headers[HttpHeaders.Location]
        assertTrue(
            location?.contains("invalid_state") == true,
            "Expected redirect to /login?error=invalid_state, got Location: $location"
        )
    }

    // ── Session lifecycle ────────────────────────────────────────────────────

    @Test
    fun `after Google login GET api session returns 200`() = testApplication {
        startWithFakeGoogleAuth(tempStorage())
        val loggedInClient = googleLogin(TEST_USER_EMAIL)

        val sessionResponse = loggedInClient.get("/api/session")
        assertEquals(HttpStatusCode.OK, sessionResponse.status)
        assertTrue(sessionResponse.bodyAsText().contains("authenticated"),
            "Body: ${sessionResponse.bodyAsText()}")
    }

    @Test
    fun `logout clears session and subsequent api call returns 401`() = testApplication {
        startWithFakeGoogleAuth(tempStorage())
        val loggedInClient = googleLogin(TEST_USER_EMAIL)

        // Verify authenticated
        val beforeLogout = loggedInClient.get("/api/session")
        assertEquals(HttpStatusCode.OK, beforeLogout.status)

        // Logout
        val logoutResponse = loggedInClient.post("/logout")
        assertEquals(HttpStatusCode.OK, logoutResponse.status)
        assertTrue(logoutResponse.bodyAsText().contains("true"),
            "Body: ${logoutResponse.bodyAsText()}")

        // Verify session is gone
        val afterLogout = loggedInClient.get("/api/session")
        assertEquals(HttpStatusCode.Unauthorized, afterLogout.status)
    }

    @Test
    fun `POST login route no longer exists`() = testApplication {
        application { module(tempStorage(), authConfig) }
        val response = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"any-password"}""")
        }
        // /login has been removed — the SPA serves index.html for unknown GET routes,
        // but POST /login is not handled → 404 or 405
        assertTrue(
            response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed,
            "POST /login should no longer exist, got ${response.status}"
        )
    }
}
