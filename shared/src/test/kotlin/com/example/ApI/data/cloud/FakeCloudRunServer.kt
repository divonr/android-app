package com.example.ApI.data.cloud

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake of llm-web's cloud-run routes (`POST /api/runs`, `POST /api/runs/{id}/cancel`,
 * `GET /api/runs/stream`) on the JDK's HttpServer (ephemeral port, no test-only dependencies).
 * The answers are scripted by the test; requests are recorded.
 */
class FakeCloudRunServer {
    class Recorded(val method: String, val path: String, val authorization: String?, val body: String)

    /** One connection of the event stream, written by the test. */
    inner class StreamConnection {
        private val queue = LinkedBlockingQueue<String>()

        /** Write raw text into the response. */
        fun raw(text: String) { queue.put(text) }

        fun event(type: String, json: String) = raw("event: $type\ndata: $json\n\n")

        fun ping() = raw(": ping\n\n")

        /** End the response (the client sees the stream end). */
        fun close() { queue.put(CLOSE) }

        internal fun pump(exchange: HttpExchange) {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)  // chunked
            val out = exchange.responseBody
            try {
                while (true) {
                    val next = queue.poll(30, TimeUnit.SECONDS) ?: break
                    if (next === CLOSE) break
                    out.write(next.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (_: Exception) {
                // The client went away
            } finally {
                runCatching { out.close() }
                exchange.close()
            }
        }
    }

    private companion object {
        val CLOSE = String(charArrayOf('c', 'l', 'o', 's', 'e'))

        init {
            System.setProperty("sun.net.httpserver.nodelay", "true")
        }
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
    val requests = CopyOnWriteArrayList<Recorded>()

    /** Answer of `POST /api/runs`: (status, body). */
    @Volatile var startResponse: Pair<Int, String> = 202 to """{"runId":"r","status":"running"}"""
    /** Delay before `POST /api/runs` is answered. */
    @Volatile var startDelayMs = 0L
    /** Answer of the cancel route. */
    @Volatile var cancelResponse: Pair<Int, String> = 200 to """{"runId":"r","status":"running"}"""
    /** Status of the next N stream requests instead of a stream. */
    val failNextStreams = AtomicInteger()
    @Volatile var failStreamStatus = 503

    /** Every stream connection, in the order they were opened. */
    private val streams = LinkedBlockingQueue<StreamConnection>()
    val streamCount = AtomicInteger()

    fun start() {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (_: Throwable) {
                runCatching { respond(exchange, 500, "{}") }
            }
        }
        server.start()
    }

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    /** The next stream connection the client opened (waits for it). */
    fun awaitStream(timeoutMs: Long = 5000): StreamConnection =
        streams.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("The client did not open the event stream")

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod
        val body = exchange.requestBody.readBytes().decodeToString()
        requests.add(Recorded(method, path, exchange.requestHeaders.getFirst("Authorization"), body))
        when {
            method == "POST" && path == "/api/runs" -> {
                if (startDelayMs > 0) Thread.sleep(startDelayMs)
                respond(exchange, startResponse.first, startResponse.second)
            }
            method == "POST" && path.startsWith("/api/runs/") && path.endsWith("/cancel") ->
                respond(exchange, cancelResponse.first, cancelResponse.second)
            method == "GET" && path == "/api/runs/stream" -> {
                streamCount.incrementAndGet()
                if (failNextStreams.getAndUpdate { maxOf(0, it - 1) } > 0) {
                    respond(exchange, failStreamStatus, """{"error":"unauthorized"}""")
                } else {
                    val connection = StreamConnection()
                    streams.put(connection)
                    connection.pump(exchange)
                }
            }
            else -> respond(exchange, 404, "{}")
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
