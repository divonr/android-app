package com.example.ApI.server

import com.example.ApI.data.repository.DataRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Smoke test: constructs DataRepository against a temp directory
 * and verifies that loadAppSettings() and loadProviders() do not throw.
 * No writes go to the real ~/.llm-api-web during tests.
 */
class RepositorySmokeTest {

    private fun tempStorage(): ServerPlatformStorage {
        val dir = File(System.getProperty("java.io.tmpdir"), "server-smoke-${System.nanoTime()}")
        dir.mkdirs()
        return ServerPlatformStorage(baseDir = dir)
    }

    @Test
    fun `DataRepository boots against temp dir without throwing`() {
        val storage = tempStorage()
        val repo = DataRepository(storage)
        // Should return a default AppSettings — not throw
        val settings = repo.loadAppSettings()
        assertNotNull(settings, "loadAppSettings() should return a non-null AppSettings")
    }

    @Test
    fun `loadProviders returns a list without throwing`() {
        val storage = tempStorage()
        val repo = DataRepository(storage)
        // May return empty list when no providers.json exists — that is fine
        val providers = repo.loadProviders()
        assertNotNull(providers, "loadProviders() should return a non-null list")
    }
}
