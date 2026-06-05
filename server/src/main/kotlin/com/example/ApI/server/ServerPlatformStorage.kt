package com.example.ApI.server

import com.example.ApI.data.PlatformStorage
import java.io.File

/**
 * PlatformStorage implementation for the Ktor web server.
 *
 * @param baseDir  Root directory for all server-side data.
 *                 Defaults to `~/.llm-api-web` in production;
 *                 tests pass a temp directory to stay isolated.
 *
 * The base directory is resolved in this order:
 *   1. The [baseDir] constructor argument (used by tests).
 *   2. The `LLM_WEB_DATA_DIR` environment variable (used by E2E tests and CI).
 *   3. `~/.llm-api-web` (production default).
 */
class ServerPlatformStorage(
    baseDir: File = resolveBaseDir()
) : PlatformStorage {

    override val filesDir: File = File(baseDir, "files").also { it.mkdirs() }

    /** Web server has no concept of a separate downloads folder. */
    override val downloadsDir: File? = null
}

/**
 * Resolves the data storage base directory.
 *
 * Priority: `LLM_WEB_DATA_DIR` env var > `~/.llm-api-web` default.
 */
fun resolveBaseDir(): File {
    val envVal = System.getenv("LLM_WEB_DATA_DIR")
    return if (!envVal.isNullOrBlank()) File(envVal) else File(System.getProperty("user.home"), ".llm-api-web")
}
