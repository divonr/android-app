package com.example.ApI.server

import com.example.ApI.data.PlatformStorage
import java.io.File

/**
 * PlatformStorage implementation for the Ktor web server.
 *
 * @param baseDir  Root directory for all server-side data.
 *                 Defaults to `~/.llm-api-web` in production;
 *                 tests pass a temp directory to stay isolated.
 */
class ServerPlatformStorage(
    baseDir: File = File(System.getProperty("user.home"), ".llm-api-web")
) : PlatformStorage {

    override val filesDir: File = File(baseDir, "files").also { it.mkdirs() }

    /** Web server has no concept of a separate downloads folder. */
    override val downloadsDir: File? = null
}
