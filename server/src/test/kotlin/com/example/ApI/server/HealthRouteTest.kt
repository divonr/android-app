package com.example.ApI.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteTest {

    @Test
    fun `GET health returns 200 with status ok`() = testApplication {
        application {
            module(ServerPlatformStorage(createTempDir("server-test")))
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\""), "Response should contain 'status' key: $body")
        assertTrue(body.contains("\"ok\""), "Response should contain 'ok' value: $body")
    }
}

/** Backward-compat shim — Kotlin stdlib deprecated createTempDir but it still works. */
private fun createTempDir(prefix: String): java.io.File =
    java.io.File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").also { it.mkdirs() }
