package com.example.ApI.server

import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.auth.FakeSyncAuthClient
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
 * Full Google login flow tests (deferred from Step 5a).
 *
 * All tests use fake injections ([FakeGoogleTokenVerifier], [FakeGoogleLoginExchanger],
 * [FakeSyncAuthClient]) so no real network calls are made.
 */
class GoogleLoginTest {

    private val authConfig = AuthConfig(
        sessionSecret = "google-login-test-session-secret-that-is-long-enough"
    )

    // ── Full login flow ───────────────────────────────────────────────────────

    @Test
    fun `full login flow creates authenticated session`() = testApplication {
        val storage = tempStorage()
        startWithFakeGoogleAuth(storage)
        val loggedInClient = googleLogin(TEST_USER_EMAIL)

        val sessionResponse = loggedInClient.get("/api/session")
        assertEquals(HttpStatusCode.OK, sessionResponse.status,
            "Expected authenticated session, body: ${sessionResponse.bodyAsText()}")
    }

    @Test
    fun `full login flow creates user dir with seeded AppSettings`() = testApplication {
        val storage = tempStorage()
        startWithFakeGoogleAuth(storage)
        googleLogin(TEST_USER_EMAIL)

        // Verify users/{username}/ directory was created
        val userDir = File(storage.baseDir, "users/$TEST_USERNAME")
        assertTrue(userDir.isDirectory, "users/$TEST_USERNAME/ should exist after login")

        // Verify AppSettings were seeded with sync credentials
        val userStorage = ServerPlatformStorage(userDir)
        val repo = DataRepository(userStorage)
        val settings = repo.loadAppSettings()

        assertTrue(settings.remoteSync.enabled, "remoteSync.enabled should be true")
        assertEquals("fake-sync-token-$TEST_USERNAME", settings.remoteSync.authToken,
            "authToken should equal the fake sync token")
        assertEquals(TEST_USERNAME, settings.current_user,
            "current_user should be the canonical username")
        assertEquals(TEST_USER_EMAIL, settings.remoteSync.accountEmail,
            "accountEmail should be the Google email")
    }

    @Test
    fun `GET api me returns correct username and email`() = testApplication {
        startWithFakeGoogleAuth(tempStorage())
        val loggedInClient = googleLogin(TEST_USER_EMAIL)

        val response = loggedInClient.get("/api/me")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(TEST_USERNAME, body["username"]?.jsonPrimitive?.content,
            "username should be $TEST_USERNAME")
        assertEquals(TEST_USER_EMAIL, body["email"]?.jsonPrimitive?.content,
            "email should be $TEST_USER_EMAIL")
    }

    // ── Allowlist enforcement ─────────────────────────────────────────────────

    @Test
    fun `non-allowlisted email redirects to not_allowed`() = testApplication {
        val storage = tempStorage()
        // Only "allowed@example.com" is allowed; TEST_USER_EMAIL is not listed
        startWithFakeGoogleAuth(storage, allowedGoogleEmails = setOf("allowed@example.com"))

        GoogleClientIdTestHook.override = "test-client-id"
        val cookieClient = createClient { install(HttpCookies); followRedirects = false }
        val startResponse = try { cookieClient.get("/auth/google/start") }
        finally { GoogleClientIdTestHook.override = null }

        val location = startResponse.headers[HttpHeaders.Location]!!
        val state = location.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")

        val callbackResponse = cookieClient.get("/auth/google/callback") {
            parameter("code", "fake-id-token-$TEST_USER_EMAIL")
            parameter("state", state)
        }

        val redirectLocation = callbackResponse.headers[HttpHeaders.Location]
        assertTrue(
            redirectLocation?.contains("not_allowed") == true,
            "Expected redirect to /login?error=not_allowed, got: $redirectLocation"
        )

        // No session should be set — /api/me must return 401
        val meResponse = cookieClient.get("/api/me")
        assertEquals(HttpStatusCode.Unauthorized, meResponse.status)
    }

    @Test
    fun `allowlisted email completes login successfully`() = testApplication {
        startWithFakeGoogleAuth(
            tempStorage(),
            allowedGoogleEmails = setOf(TEST_USER_EMAIL)
        )
        val loggedInClient = googleLogin(TEST_USER_EMAIL)

        val meResponse = loggedInClient.get("/api/me")
        assertEquals(HttpStatusCode.OK, meResponse.status)
        val body = Json.parseToJsonElement(meResponse.bodyAsText()).jsonObject
        assertEquals(TEST_USER_EMAIL, body["email"]?.jsonPrimitive?.content)
    }

    // ── Sync server unavailable ───────────────────────────────────────────────

    @Test
    fun `SyncAuthClient returning null redirects to sync_unavailable`() = testApplication {
        startWithFakeGoogleAuth(
            tempStorage(),
            syncAuthClient = FakeSyncAuthClient(alwaysNull = true)
        )

        GoogleClientIdTestHook.override = "test-client-id"
        val cookieClient = createClient { install(HttpCookies); followRedirects = false }
        val startResponse = try { cookieClient.get("/auth/google/start") }
        finally { GoogleClientIdTestHook.override = null }

        val location = startResponse.headers[HttpHeaders.Location]!!
        val state = location.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")

        val callbackResponse = cookieClient.get("/auth/google/callback") {
            parameter("code", "fake-id-token-$TEST_USER_EMAIL")
            parameter("state", state)
        }

        val redirectLocation = callbackResponse.headers[HttpHeaders.Location]
        assertTrue(
            redirectLocation?.contains("sync_unavailable") == true,
            "Expected redirect to /login?error=sync_unavailable, got: $redirectLocation"
        )

        // No authenticated session
        val meResponse = cookieClient.get("/api/me")
        assertEquals(HttpStatusCode.Unauthorized, meResponse.status)
    }

    // ── Two users → two separate dirs ─────────────────────────────────────────

    @Test
    fun `two different users get two separate user dirs`() = testApplication {
        val storage = tempStorage()
        startWithFakeGoogleAuth(storage)

        val userAEmail = "user.a@example.com"
        val userBEmail = "user.b@example.com"

        googleLogin(userAEmail)
        googleLogin(userBEmail)

        val expectedUsernameA = "user_a_example_com"
        val expectedUsernameB = "user_b_example_com"

        val userADir = File(storage.baseDir, "users/$expectedUsernameA")
        val userBDir = File(storage.baseDir, "users/$expectedUsernameB")

        assertTrue(userADir.isDirectory, "User A dir ($expectedUsernameA) should exist")
        assertTrue(userBDir.isDirectory, "User B dir ($expectedUsernameB) should exist")
    }

    @Test
    fun `GET api me for two users returns their respective usernames`() = testApplication {
        val storage = tempStorage()
        startWithFakeGoogleAuth(storage)

        val userAEmail = "alice@example.com"
        val userBEmail = "bob@example.com"

        val clientA = googleLogin(userAEmail)
        val clientB = googleLogin(userBEmail)

        val meA = Json.parseToJsonElement(clientA.get("/api/me").bodyAsText()).jsonObject
        val meB = Json.parseToJsonElement(clientB.get("/api/me").bodyAsText()).jsonObject

        assertEquals("alice_example_com", meA["username"]?.jsonPrimitive?.content)
        assertEquals(userAEmail, meA["email"]?.jsonPrimitive?.content)
        assertEquals("bob_example_com", meB["username"]?.jsonPrimitive?.content)
        assertEquals(userBEmail, meB["email"]?.jsonPrimitive?.content)
    }

    // ── Callback error paths ──────────────────────────────────────────────────

    @Test
    fun `callback with invalid id_token (verifier returns null) redirects to token_invalid`() = testApplication {
        startWithFakeGoogleAuth(tempStorage())

        GoogleClientIdTestHook.override = "test-client-id"
        val cookieClient = createClient { install(HttpCookies); followRedirects = false }
        val startResponse = try { cookieClient.get("/auth/google/start") }
        finally { GoogleClientIdTestHook.override = null }

        val location = startResponse.headers[HttpHeaders.Location]!!
        val state = location.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")

        // Pass a token that does NOT start with "fake-id-token-" so the verifier returns null
        val callbackResponse = cookieClient.get("/auth/google/callback") {
            parameter("code", "not-a-fake-token")
            parameter("state", state)
        }

        val redirectLocation = callbackResponse.headers[HttpHeaders.Location]
        assertTrue(
            redirectLocation?.contains("token_invalid") == true,
            "Expected redirect to /login?error=token_invalid, got: $redirectLocation"
        )
    }

    // ── Unauthenticated start page ────────────────────────────────────────────

    @Test
    fun `GET auth google start without GOOGLE_OAUTH_CLIENT_ID returns 503`() = testApplication {
        // No GoogleClientIdTestHook set, env var not set in test → 503
        application { module(tempStorage(), authConfig) }
        val response = client.get("/auth/google/start")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status,
            "Should be 503 when GOOGLE_OAUTH_CLIENT_ID is not configured")
    }
}
