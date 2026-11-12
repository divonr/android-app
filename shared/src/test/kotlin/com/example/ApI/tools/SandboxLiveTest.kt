package com.example.ApI.tools

import com.example.ApI.data.PlatformStorage
import com.example.ApI.data.model.Chat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The tool against a real executor, on the route a device takes. Skipped unless asked for:
 *
 *     SANDBOX_LIVE_URL=https://.../v2/execute SANDBOX_LIVE_KEY=... [SANDBOX_LIVE_SECONDS=150] \
 *         ./gradlew :shared:test --tests '*SandboxLiveTest*'
 *
 * The chat is `test-live-<time>`; its container is the executor's to remove (two idle hours).
 */
class SandboxLiveTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `a run longer than a proxy's patience comes back whole`() {
        val url = System.getenv("SANDBOX_LIVE_URL").orEmpty()
        val key = System.getenv("SANDBOX_LIVE_KEY").orEmpty()
        assumeTrue(url.isNotBlank() && key.isNotBlank(), "SANDBOX_LIVE_URL / SANDBOX_LIVE_KEY not set")
        val seconds = System.getenv("SANDBOX_LIVE_SECONDS")?.toIntOrNull() ?: 150
        val chatId = System.getenv("SANDBOX_LIVE_CHAT") ?: "test-live-${System.currentTimeMillis()}"

        val storage = object : PlatformStorage {
            override val filesDir: File = File(dir, "files").apply { mkdirs() }
            override val downloadsDir: File? = null
        }
        val tool = PythonInterpreterTool(
            storage, Chat(chat_id = chatId, preview_name = "live", messages = emptyList()), null, SandboxConfig(url, key)
        )
        val code = """
            import time
            for i in range($seconds // 10):
                print('tick', i, flush=True)
                time.sleep(10)
            open('output/live.txt', 'w').write('made after $seconds s')
            print('finished')
        """.trimIndent()

        val started = System.nanoTime()
        val result = runBlocking { tool.execute(buildJsonObject { put("code", code) }) }
        val took = (System.nanoTime() - started) / 1_000_000_000
        println("[SandboxLiveTest] $url chat=$chatId took ${took}s -> ${result::class.simpleName}")

        assertIs<ToolExecutionResult.Success>(result, result.toString().take(600))
        assertTrue(took >= seconds - 10, "took $took s")
        assertTrue("tick ${seconds / 10 - 1}\nfinished" in result.result, result.result.takeLast(300))
        val file = PythonInterpreterTool.outputAttachments(result).single()
        assertTrue(File(file.local_file_path!!).readText() == "made after $seconds s")
    }
}
