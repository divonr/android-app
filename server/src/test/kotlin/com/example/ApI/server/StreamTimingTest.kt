package com.example.ApI.server

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.auth.FakeSyncWhoamiClient
import com.example.ApI.server.auth.SyncIdentity
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.delay
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A provider that streams [count] deltas ("t0 ", "t1 ", …), one every [intervalMs], then
 * completes.  [emitted] holds the time (`System.nanoTime`) each delta was handed to the run.
 */
internal class SteadyEngine(private val count: Int, private val intervalMs: Long) : ChatEngine {
    val emitted = CopyOnWriteArrayList<Long>()

    override suspend fun send(
        provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
        username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
        callback: StreamingCallback
    ) {
        val text = StringBuilder()
        repeat(count) { i ->
            delay(intervalMs)
            emitted += System.nanoTime()
            callback.onPartialResponse("t$i ")
            text.append("t$i ")
        }
        callback.onComplete(text.toString())
    }
}

/**
 * A reply must reach the client as it is produced: every delta of a steady provider is on the
 * wire right after the run got it, on the web's per-request stream and on the account's stream
 * alike — never held back until the reply (or a larger piece of it) is complete.
 */
class StreamTimingTest {

    private class Seed(val storage: ServerPlatformStorage, val chatId: String)

    /** A chat ending in the question "Q1", after [turns] earlier question / answer pairs. */
    private fun seed(turns: Int = 0): Seed {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "stream-timing-${System.nanoTime()}").apply { mkdirs() }
        val repo = DataRepository(ServerPlatformStorage(File(baseDir, "users/$TEST_USERNAME")))
        repo.saveAppSettings(AppSettings(current_user = TEST_USERNAME, selected_provider = "openai", selected_model = "gpt-4o"))
        repo.addApiKey(TEST_USERNAME, ApiKey(id = "k1", provider = "openai", key = "sk-test-key", isActive = true))
        val chat = repo.createNewChat(TEST_USERNAME, "Streaming chat")
        repeat(turns) { i ->
            repo.addUserMessageAsNewNode(TEST_USERNAME, chat.chat_id, Message(role = "user", text = "Earlier question $i"))
            repo.addResponseToCurrentVariant(TEST_USERNAME, chat.chat_id, Message(role = "assistant", text = LONG_ANSWER, model = "gpt-4o"))
        }
        repo.addUserMessageAsNewNode(TEST_USERNAME, chat.chat_id, Message(role = "user", text = "Q1"))
        return Seed(ServerPlatformStorage(baseDir), chat.chat_id)
    }

    /** An answer like the ones of a real chat: prose, a list, a table, code and maths. */
    private val LONG_ANSWER = """
        ## Overview

        Here is a **longer answer** with `inline code`, a [link](https://example.com) and some prose that
        goes on for a while so that the paragraph wraps over several lines in the bubble.

        1. First point, with *emphasis*
        2. Second point
        3. Third point

        | Name | Value | Note |
        |------|-------|------|
        | alpha | 1 | first |
        | beta | 2 | second |
        | gamma | 3 | third |

        ```kotlin
        fun fibonacci(n: Int): Long {
            var a = 0L
            var b = 1L
            repeat(n) { val next = a + b; a = b; b = next }
            return a
        }
        ```

        The closed form is ${'$'}F_n = \frac{\varphi^n - \psi^n}{\sqrt{5}}${'$'} and

        ${'$'}${'$'}\sum_{i=1}^{n} F_i = F_{n+2} - 1${'$'}${'$'}

        ```python
        def fibonacci(n):
            a, b = 0, 1
            for _ in range(n):
                a, b = b, a + b
            return a
        ```
    """.trimIndent()

    private fun whoami() = FakeSyncWhoamiClient().apply {
        identities[TEST_BEARER] = SyncIdentity(TEST_USERNAME, TEST_USER_EMAIL)
    }

    private fun sendBody(chatId: String) =
        """{"chatId":"$chatId","provider":"openai","modelName":"gpt-4o","messages":[{"role":"user","text":"Q2","id":"q2"}]}"""

    /** How long after the provider produced each delta the client had it, in milliseconds. */
    private fun lags(engine: SteadyEngine, stream: LiveSse): List<Long> {
        val partials = stream.arrivals.filter { it.first == "partial" }.map { it.second }
        assertEquals(engine.emitted.size, partials.size, "every delta is an event of its own")
        return partials.indices.map { (partials[it] - engine.emitted[it]) / 1_000_000 }
    }

    @Test
    fun `the web's send stream delivers every delta as the provider produces it`() {
        val seed = seed()
        val engine = SteadyEngine(count = 30, intervalMs = 50)
        LiveServer(seed.storage, engine, whoami()).use { server ->
            val stream = server.login(TEST_USER_EMAIL).sse("/api/chat/send", method = "POST", body = sendBody(seed.chatId))
            assertEquals(200, stream.status)
            stream.nextOf("complete", timeoutMs = 20_000)

            val lags = lags(engine, stream)
            println("[StreamTimingTest] /api/chat/send delta lag ms: $lags")
            // 1.5 s of streaming: a response held back until its end would lag by about that much
            assertTrue(lags.max() < 700, "a delta reached the client late: $lags")
        }
    }

    @Test
    fun `the account stream delivers every delta as the provider produces it`() {
        val seed = seed()
        val engine = SteadyEngine(count = 30, intervalMs = 50)
        LiveServer(seed.storage, engine, whoami()).use { server ->
            val browser = server.login(TEST_USER_EMAIL)
            val account = browser.sse("/api/runs/stream")
            account.expect("snapshot")
            val own = browser.sse("/api/chat/send", method = "POST", body = sendBody(seed.chatId))
            account.nextOf("complete", timeoutMs = 20_000)
            own.close()

            val lags = lags(engine, account)
            println("[StreamTimingTest] /api/runs/stream delta lag ms: $lags")
            assertTrue(lags.max() < 700, "a delta reached the client late: $lags")
        }
    }

    @Test
    fun `stream responses tell proxies not to buffer or transform them`() {
        val seed = seed()
        LiveServer(seed.storage, SteadyEngine(count = 1, intervalMs = 1), whoami()).use { server ->
            val headers = server.login(TEST_USER_EMAIL).streamHeaders("/api/runs/stream")
            assertTrue(headers.firstValue("Content-Type").orElse("").startsWith("text/event-stream"))
            assertEquals("no-cache, no-transform", headers.firstValue("Cache-Control").orElse(""))
            assertEquals("no", headers.firstValue("X-Accel-Buffering").orElse(""))
            assertTrue(headers.firstValue("Content-Encoding").isEmpty, "a stream is never compressed")
        }
    }

    /**
     * Not a test: the application with a steady fake provider and the built web client, for
     * measuring a reply's way to the screen in a real browser.  Skipped unless asked for:
     *
     *     STREAM_DEV_PORT=18591 WEB_STATIC_DIR=$PWD/web/dist ./gradlew :server:test --tests '*StreamTimingTest*'
     *
     * Sign in by opening `/auth/google/start` and then
     * `/auth/google/callback?code=fake-id-token-<email>&state=<state>` with the `state` of the
     * redirect.  Runs until the file named by STREAM_DEV_STOP_FILE exists (at most
     * STREAM_DEV_MINUTES, default 10).  STREAM_DEV_INTERVAL_MS / STREAM_DEV_COUNT set the pace
     * of the reply, STREAM_DEV_TURNS the number of earlier question / answer pairs in the chat.
     */
    @Test
    fun `dev server with a steady fake provider`() {
        val port = System.getenv("STREAM_DEV_PORT")?.toIntOrNull()
        assumeTrue("STREAM_DEV_PORT not set", port != null)
        val seed = seed(turns = System.getenv("STREAM_DEV_TURNS")?.toIntOrNull() ?: 0)
        val engine = SteadyEngine(
            count = System.getenv("STREAM_DEV_COUNT")?.toIntOrNull() ?: 60,
            intervalMs = System.getenv("STREAM_DEV_INTERVAL_MS")?.toLongOrNull() ?: 50
        )
        val stopFile = System.getenv("STREAM_DEV_STOP_FILE")?.let { File(it) }
        val deadline = System.currentTimeMillis() + (System.getenv("STREAM_DEV_MINUTES")?.toLongOrNull() ?: 10) * 60_000
        GoogleClientIdTestHook.override = "test-client-id"
        try {
            LiveServer(seed.storage, engine, whoami(), port = port!!).use { server ->
                println("[StreamTimingTest] dev server at ${server.baseUrl}, chat ${seed.chatId}")
                while (System.currentTimeMillis() < deadline && stopFile?.exists() != true) Thread.sleep(500)
            }
        } finally {
            GoogleClientIdTestHook.override = null
        }
    }
}
