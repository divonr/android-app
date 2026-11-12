package com.example.ApI.data.network.providers

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.ApiRequest
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.PROJECT_ID_PLACEHOLDER
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.ResponseFields
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.VERTEX_PROVIDER
import com.example.ApI.data.model.withProjectIdFrom
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A provider hands every delta of the upstream stream on as it arrives: against a local fake
 * that sends one chunk every [INTERVAL_MS], no delta waits for a later one (or for the end).
 */
class ProviderStreamTimingTest {

    private companion object {
        const val COUNT = 30
        const val INTERVAL_MS = 50L
    }

    private lateinit var server: HttpServer
    private val sent = CopyOnWriteArrayList<Long>()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            // Length 0: chunked, like the real endpoint
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                repeat(COUNT) { i ->
                    Thread.sleep(INTERVAL_MS)
                    val finish = if (i == COUNT - 1) ""","finishReason":"STOP"""" else ""
                    val chunk = """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"t$i "}]}$finish}]}""" + "\r\n\r\n"
                    sent += System.nanoTime()
                    out.write(chunk.toByteArray())
                    out.flush()
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun lags(platform: GooglePlatform, providerName: String): List<Long> {
        val received = CopyOnWriteArrayList<Long>()
        val callback = object : StreamingCallback {
            override fun onPartialResponse(text: String) { received += System.nanoTime() }
            override fun onComplete(fullText: String) {}
            override fun onError(error: String) = throw AssertionError(error)
        }
        val provider = Provider(
            provider = providerName,
            models = emptyList(),
            request = ApiRequest(
                request_type = "POST",
                base_url = "http://127.0.0.1:${server.address.port}/v1/" +
                    (if (providerName == VERTEX_PROVIDER) "projects/$PROJECT_ID_PLACEHOLDER/" else "") +
                    "models/{model_name}:generateContent",
                headers = emptyMap()
            ),
            response_important_fields = ResponseFields()
        ).withProjectIdFrom(listOf(ApiKey(provider = VERTEX_PROVIDER, key = "FAKE-KEY", projectId = "fake-project")))
        runBlocking {
            GoogleProvider(platform).sendMessage(
                provider = provider, modelName = "gemini-fake", messages = listOf(Message(role = "user", text = "hello")),
                systemPrompt = "", apiKey = "FAKE-KEY", webSearchEnabled = false, enabledTools = emptyList(),
                thinkingBudget = ThinkingBudgetValue.None, temperature = null, callback = callback
            )
        }
        assertEquals(COUNT, received.size, "every chunk is a delta of its own")
        return received.indices.map { (received[it] - sent[it]) / 1_000_000 }
    }

    @Test
    fun `Agent Platform deltas arrive as the upstream sends them`() {
        val lags = lags(GooglePlatform.AGENT_PLATFORM, VERTEX_PROVIDER)
        println("[ProviderStreamTimingTest] vertex delta lag ms: $lags")
        // 1.5 s of streaming: a response read only at its end would lag by about that much
        assertTrue(lags.max() < 700, "a delta was handed on late: $lags")
    }

    @Test
    fun `AI Studio deltas arrive as the upstream sends them`() {
        val lags = lags(GooglePlatform.AI_STUDIO, "google")
        println("[ProviderStreamTimingTest] google delta lag ms: $lags")
        assertTrue(lags.max() < 700, "a delta was handed on late: $lags")
    }
}
