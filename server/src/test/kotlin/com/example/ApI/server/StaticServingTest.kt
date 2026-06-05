package com.example.ApI.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P8 — Verifies that the built React SPA is served correctly from the Ktor server.
 *
 * Test scenarios:
 *   1. GET /            → 200 + index.html with marker text
 *   2. GET /chat/xyz    → 200 + index.html (SPA fallback for client-side routes)
 *   3. GET /api/unknown → 401 or 404, NOT the SPA html
 *   4. GET /health      → 200 {"status":"ok"} (existing public route still works)
 *
 * A temp directory with a fake index.html is used so no real frontend build is required.
 */
class StaticServingTest {

    /** Creates a temp dir with a fake index.html containing a known marker. */
    private fun tempStaticDir(): File {
        val dir = makeTempDir("p8-static")
        File(dir, "index.html").writeText(
            "<!doctype html><html><head></head>" +
            "<body><div id=\"root\" data-testmarker=\"spa-root\">SPA TEST MARKER</div></body></html>"
        )
        val assetsDir = File(dir, "assets")
        assetsDir.mkdirs()
        File(assetsDir, "app.js").writeText("// fake JS bundle")
        return dir
    }

    /**
     * Runs a Ktor test application with the static dir injected via [StaticDirTestHook].
     * Uses a fresh isolated temp data dir so nothing touches ~/.llm-api-web.
     */
    private fun withStaticDir(staticDir: File, block: suspend ApplicationTestBuilder.() -> Unit) {
        StaticDirTestHook.override = staticDir
        try {
            testApplication {
                application {
                    module(ServerPlatformStorage(makeTempDir("p8-repo")))
                }
                block()
            }
        } finally {
            StaticDirTestHook.override = null
        }
    }

    @Test
    fun `GET root returns SPA index html with marker`() {
        val staticDir = tempStaticDir()
        withStaticDir(staticDir) {
            val response = client.get("/")
            assertEquals(HttpStatusCode.OK, response.status, "/ should return 200")
            val body = response.bodyAsText()
            assertTrue(body.contains("SPA TEST MARKER"), "/ should return index.html: $body")
        }
    }

    @Test
    fun `GET unknown client route returns SPA index html`() {
        val staticDir = tempStaticDir()
        withStaticDir(staticDir) {
            val response = client.get("/chat/some-chat-id-xyz")
            assertEquals(HttpStatusCode.OK, response.status, "/chat/xyz should return 200")
            val body = response.bodyAsText()
            assertTrue(body.contains("SPA TEST MARKER"), "/chat/xyz should return index.html: $body")
        }
    }

    @Test
    fun `GET unknown api path is not swallowed by SPA fallback`() {
        val staticDir = tempStaticDir()
        withStaticDir(staticDir) {
            val response = client.get("/api/does-not-exist-p8")
            // The /api route is guarded by session auth, so unauthenticated → 401.
            // Either 401 or 404 is correct — the SPA html (200) must NOT be returned.
            assertTrue(
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.NotFound,
                "API paths must not be handled by SPA fallback; got ${response.status}"
            )
            val body = response.bodyAsText()
            assertFalse(body.contains("SPA TEST MARKER"), "API error must not return SPA html: $body")
        }
    }

    @Test
    fun `GET health still returns 200 after static serving added`() {
        val staticDir = tempStaticDir()
        withStaticDir(staticDir) {
            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"ok\""), "/health should still work: $body")
        }
    }
}

private fun makeTempDir(prefix: String): java.io.File =
    java.io.File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").also { it.mkdirs() }
