package com.example.ApI.server

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the remote-sync bootstrap added in the sync follow-up.
 *
 * Config-seeding tests use [SyncConfig.startEngine] = false so no real network
 * connections are opened, but the AppSettings file is written exactly as in
 * production.  Endpoint tests verify auth gating and basic 200 responses.
 *
 * All tests use an isolated temp directory — nothing touches ~/.llm-api-web.
 */
class SyncBootstrapTest {

    private val testPassword = "sync-test-password"
    private val testAuthConfig = AuthConfig(
        password = testPassword,
        sessionSecret = "sync-test-session-secret-that-is-long-enough"
    )

    private fun tempStorage(): ServerPlatformStorage {
        val dir = File(System.getProperty("java.io.tmpdir"), "sync-test-${System.nanoTime()}")
        dir.mkdirs()
        return ServerPlatformStorage(baseDir = dir)
    }

    /**
     * Returns the per-"default"-user storage under the given root storage.
     *
     * After Step 5a, [applySyncConfig] writes into `users/default/` (via
     * [UserRegistry.context]), so verifications must read from the same path.
     */
    private fun userStorage(root: ServerPlatformStorage): ServerPlatformStorage =
        ServerPlatformStorage(baseDir = File(root.baseDir, "users/default"))

    // ── Config-seeding tests ──────────────────────────────────────────────────

    @Test
    fun `sync config is seeded into AppSettings when enabled with token`() = testApplication {
        val storage = tempStorage()
        val syncConfig = SyncConfig(
            enabled = true,
            serverBaseUrl = "http://test-sync:8090",
            authToken = "secret-bearer-token",
            syncUser = "myuser",
            startEngine = false
        )
        application { module(storage, testAuthConfig, syncConfig = syncConfig) }

        // Trigger application startup (module() runs on first client access)
        client.get("/health")

        // Verify via a fresh DataRepository reading the same files (must use per-user path)
        val repo = DataRepository(userStorage(storage))
        val settings = repo.loadAppSettings()
        assertTrue(settings.remoteSync.enabled, "remoteSync.enabled should be true")
        assertEquals("http://test-sync:8090", settings.remoteSync.serverBaseUrl)
        assertEquals("secret-bearer-token", settings.remoteSync.authToken)
        assertEquals("myuser", settings.current_user)
        assertFalse(settings.remoteSync.syncApiKeys, "syncApiKeys must be false (API keys not synced via web)")
    }

    @Test
    fun `syncApiKeys=true in SyncConfig is seeded into AppSettings`() = testApplication {
        val storage = tempStorage()
        val syncConfig = SyncConfig(
            enabled = true,
            serverBaseUrl = "http://test-sync:8090",
            authToken = "secret-bearer-token",
            syncApiKeys = true,
            startEngine = false
        )
        application { module(storage, testAuthConfig, syncConfig = syncConfig) }
        client.get("/health")

        val repo = DataRepository(userStorage(storage))
        val settings = repo.loadAppSettings()
        assertTrue(settings.remoteSync.syncApiKeys, "syncApiKeys should be true when SyncConfig.syncApiKeys=true")
    }

    @Test
    fun `sync user is not overridden when syncUser is null`() = testApplication {
        val storage = tempStorage()
        // Pre-seed the "default" user's settings with a specific current_user value.
        // After Step 5a, applySyncConfig reads/writes via registry.context("default")
        // which uses users/default/ — so we must pre-seed there too.
        DataRepository(userStorage(storage)).saveAppSettings(
            AppSettings(
                current_user = "existinguser",
                selected_provider = "openai",
                selected_model = "gpt-4o"
            )
        )
        val syncConfig = SyncConfig(
            enabled = true,
            serverBaseUrl = "http://test-sync:8090",
            authToken = "secret-bearer-token",
            syncUser = null, // do NOT override current_user
            startEngine = false
        )
        application { module(storage, testAuthConfig, syncConfig = syncConfig) }
        client.get("/health")

        val repo = DataRepository(userStorage(storage))
        val settings = repo.loadAppSettings()
        assertEquals("existinguser", settings.current_user, "current_user should not be overridden when syncUser is null")
        assertTrue(settings.remoteSync.enabled, "remoteSync should still be enabled")
    }

    @Test
    fun `sync is not applied when disabled`() = testApplication {
        val storage = tempStorage()
        val syncConfig = SyncConfig(enabled = false)
        application { module(storage, testAuthConfig, syncConfig = syncConfig) }
        client.get("/health")

        val repo = DataRepository(userStorage(storage))
        val settings = repo.loadAppSettings()
        assertFalse(settings.remoteSync.enabled, "remoteSync.enabled should remain false when sync is disabled")
    }

    @Test
    fun `sync is not applied when token is blank`() = testApplication {
        val storage = tempStorage()
        val syncConfig = SyncConfig(enabled = true, authToken = "", startEngine = false)
        application { module(storage, testAuthConfig, syncConfig = syncConfig) }
        client.get("/health")

        val repo = DataRepository(userStorage(storage))
        val settings = repo.loadAppSettings()
        // Config must NOT be seeded when the token is missing — guard must fire
        assertFalse(settings.remoteSync.enabled, "remoteSync.enabled must NOT be seeded when SYNC_TOKEN is blank")
    }

    // ── Sync API endpoint auth-gating tests ───────────────────────────────────

    @Test
    fun `GET api sync status returns 401 without session`() = testApplication {
        application { module(tempStorage(), testAuthConfig) }
        val response = client.get("/api/sync/status")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST api sync pull returns 401 without session`() = testApplication {
        application { module(tempStorage(), testAuthConfig) }
        val response = client.post("/api/sync/pull")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ── Sync API endpoint happy-path tests ───────────────────────────────────

    @Test
    fun `GET api sync status returns 200 with correct shape when logged in`() = testApplication {
        val storage = tempStorage()
        // Sync disabled — pullNow is a safe no-op so we don't open sockets
        application { module(storage, testAuthConfig, syncConfig = SyncConfig(enabled = false)) }

        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.get("/api/sync/status")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["enabled"], "response must contain 'enabled'")
        assertNotNull(body["serverBaseUrl"], "response must contain 'serverBaseUrl'")
        assertNotNull(body["lastChangeTick"], "response must contain 'lastChangeTick'")
        // Auth token must NEVER be sent over the wire
        assertNull(body["authToken"], "auth token must NOT appear in the response")
    }

    @Test
    fun `POST api sync pull returns ok true when logged in and sync disabled`() = testApplication {
        val storage = tempStorage()
        // Sync disabled — pullNow is a safe no-op
        application { module(storage, testAuthConfig, syncConfig = SyncConfig(enabled = false)) }

        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.post("/api/sync/pull")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("true"), "body should contain ok:true")
    }

    @Test
    fun `GET api sync status includes reachable field and is null when sync disabled`() = testApplication {
        val storage = tempStorage()
        // Sync disabled — no network probe should be made, reachable must be null/absent or JSON null
        application { module(storage, testAuthConfig, syncConfig = SyncConfig(enabled = false)) }

        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.get("/api/sync/status")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["enabled"], "response must contain 'enabled'")
        assertNotNull(body["serverBaseUrl"], "response must contain 'serverBaseUrl'")
        assertNotNull(body["lastChangeTick"], "response must contain 'lastChangeTick'")
        // When sync is disabled, reachable should be null (JSON null or missing)
        val reachable = body["reachable"]
        assertTrue(
            reachable == null || reachable is JsonNull,
            "reachable must be null when sync is disabled, got: $reachable"
        )
        // Auth token must NEVER be sent over the wire
        assertNull(body["authToken"], "auth token must NOT appear in the response")
    }

    @Test
    fun `GET api sync status reachable is false when sync enabled but remote unreachable`() = testApplication {
        val storage = tempStorage()
        // Sync enabled with a bogus URL — testSyncConnection() will fail, reachable must be false
        val syncConfig = SyncConfig(
            enabled = true,
            serverBaseUrl = "http://127.0.0.1:19999", // nothing listening here
            authToken = "test-token",
            startEngine = false
        )
        application { module(storage, testAuthConfig, syncConfig = syncConfig) }

        val cookieClient = createClient { install(HttpCookies) }
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.get("/api/sync/status")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val reachable = body["reachable"]
        assertNotNull(reachable, "reachable field must be present when sync is enabled")
        assertFalse(
            reachable?.jsonPrimitive?.booleanOrNull == true,
            "reachable should be false when remote is unreachable, got: $reachable"
        )
    }
}
