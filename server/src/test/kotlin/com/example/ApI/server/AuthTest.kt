package com.example.ApI.server

import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests for Phase 1 auth: session cookies, login, logout, and /api guard.
// Password is injected via AuthConfig so tests do not depend on WEB_UI_PASSWORD env var.
class AuthTest {

    private val testPassword = "test-secret-password"
    private val testAuthConfig = AuthConfig(
        password = testPassword,
        sessionSecret = "test-session-secret-that-is-long-enough-for-hmac"
    )
    private val testStorage get() = ServerPlatformStorage(
        java.io.File(System.getProperty("java.io.tmpdir"), "auth-test-${System.nanoTime()}").also { it.mkdirs() }
    )

    // GET /api/session without cookie -> 401

    @Test
    fun `GET api session without cookie returns 401`() = testApplication {
        application { module(testStorage, testAuthConfig) }
        val response = client.get("/api/session")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // POST /login wrong password -> 401

    @Test
    fun `POST login with wrong password returns 401`() = testApplication {
        application { module(testStorage, testAuthConfig) }
        val response = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Invalid password"), "Body: ${response.bodyAsText()}")
    }

    // POST /login correct password -> 200 + Set-Cookie

    @Test
    fun `POST login with correct password returns 200 and sets session cookie`() = testApplication {
        application { module(testStorage, testAuthConfig) }
        val response = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("true"), "Body: ${response.bodyAsText()}")
        val setCookie = response.headers[HttpHeaders.SetCookie]
        assertTrue(setCookie != null && setCookie.contains("llm_web_session"),
            "Expected llm_web_session cookie, got: $setCookie")
    }

    // Login then GET /api/session -> 200 authenticated:true

    @Test
    fun `after login GET api session returns 200 authenticated true`() = testApplication {
        application { module(testStorage, testAuthConfig) }
        val cookieClient = createClient {
            install(HttpCookies)
        }
        val loginResponse = cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)

        val sessionResponse = cookieClient.get("/api/session")
        assertEquals(HttpStatusCode.OK, sessionResponse.status)
        assertTrue(sessionResponse.bodyAsText().contains("authenticated"), "Body: ${sessionResponse.bodyAsText()}")
        assertTrue(sessionResponse.bodyAsText().contains("true"), "Body: ${sessionResponse.bodyAsText()}")
    }

    // Logout invalidates session

    @Test
    fun `logout clears session and subsequent api session call returns 401`() = testApplication {
        application { module(testStorage, testAuthConfig) }
        val cookieClient = createClient {
            install(HttpCookies)
        }
        val loginResponse = cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)

        val beforeLogout = cookieClient.get("/api/session")
        assertEquals(HttpStatusCode.OK, beforeLogout.status)

        val logoutResponse = cookieClient.post("/logout")
        assertEquals(HttpStatusCode.OK, logoutResponse.status)
        assertTrue(logoutResponse.bodyAsText().contains("true"), "Body: ${logoutResponse.bodyAsText()}")

        val afterLogout = cookieClient.get("/api/session")
        assertEquals(HttpStatusCode.Unauthorized, afterLogout.status)
    }

    // GET /health is public (no auth required)

    @Test
    fun `GET health is public and returns 200 without auth`() = testApplication {
        application { module(testStorage, testAuthConfig) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"), "Body: ${response.bodyAsText()}")
    }

    // 401 challenge response has JSON error body

    @Test
    fun `401 on protected route has JSON error body`() = testApplication {
        application { module(testStorage, testAuthConfig) }
        val response = client.get("/api/session")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("error") && body.contains("unauthorized"),
            "Expected JSON error body, got: $body")
    }
}
