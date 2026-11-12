package com.example.ApI.server

import com.example.ApI.server.auth.FakeSyncAuthClient
import com.example.ApI.server.auth.FakeSyncWhoamiClient
import com.example.ApI.server.auth.SyncAuthClient
import com.example.ApI.server.auth.SyncWhoamiClient
import com.example.ApI.server.streaming.ChatEngine
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The application on a real Netty server (ephemeral port on 127.0.0.1) with the test fakes, for
 * tests of long-lived SSE responses: the `testApplication` client only hands a response over
 * once it is complete, so it can neither read a stream that is still open nor disconnect from
 * one.  Clients here are JDK HTTP clients, so a closed stream is a real closed connection.
 */
internal class LiveServer(
    storage: ServerPlatformStorage,
    engine: ChatEngine,
    whoami: SyncWhoamiClient = FakeSyncWhoamiClient(),
    allowedGoogleEmails: Set<String> = emptySet(),
    runPingIntervalMs: Long = 15_000L,
    startRegistry: Boolean = false,
    syncAuthClient: SyncAuthClient = FakeSyncAuthClient(),
    syncServerUrl: String = UNREACHABLE_SYNC_URL,
    pullIntervalSeconds: Long = 3_600L,
    integrationTools: com.example.ApI.server.runs.IntegrationTools = FakeIntegrationTools(),
    googleTokenRefresher: com.example.ApI.server.oauth.GoogleTokenRefresher = FakeGoogleTokenRefresher(),
    // No title by default: the real generator would call a provider
    titleGenerator: TitleGenerator = TitleGenerator { _, _, _ -> "" },
    runRegistry: com.example.ApI.server.runs.RunRegistry = com.example.ApI.server.runs.RunRegistry(),
    // Never a real provider / the real Python executor from a test
    providerFileUploader: com.example.ApI.server.runs.ProviderFileUploader = NO_PROVIDER_UPLOADS,
    pythonExecutorUrl: String = UNREACHABLE_PYTHON_EXECUTOR,
    sandboxKey: String = TEST_SANDBOX_KEY,
    maxFileBytes: Long = com.example.ApI.data.attachments.AttachmentStoreClient.DEFAULT_MAX_FILE_BYTES,
    // 0: any free port
    port: Int = 0
) : AutoCloseable {

    private val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
        module(
            storage,
            oauthExchanger = FakeGoogleLoginExchanger(),
            googleTokenVerifier = FakeGoogleTokenVerifier(),
            syncAuthClient = syncAuthClient,
            startRegistry = startRegistry,
            allowedGoogleEmails = allowedGoogleEmails,
            syncServerUrl = syncServerUrl,
            pullIntervalSeconds = pullIntervalSeconds,
            syncWhoamiClient = whoami,
            runPingIntervalMs = runPingIntervalMs,
            integrationTools = integrationTools,
            googleTokenRefresher = googleTokenRefresher,
            titleGeneratorFactory = { _ -> titleGenerator },
            runRegistry = runRegistry,
            providerFileUploader = providerFileUploader,
            sandbox = com.example.ApI.tools.SandboxConfig(pythonExecutorUrl, sandboxKey),
            maxFileBytes = maxFileBytes,
            chatEngineFactory = { _ -> engine }
        )
    }.start(wait = false)

    val baseUrl: String = "http://127.0.0.1:${runBlocking { server.resolvedConnectors().first().port }}"

    private val clients = mutableListOf<LiveClient>()

    /** A client that sends [token] as its bearer token (null: no credentials). */
    fun client(token: String? = TEST_BEARER): LiveClient = LiveClient(baseUrl, token).also { clients += it }

    /** A client holding the session cookie of a Google login of [email] (what a browser has). */
    fun login(email: String = TEST_USER_EMAIL): LiveClient {
        val client = LiveClient(baseUrl, token = null).also { clients += it }
        GoogleClientIdTestHook.override = "test-client-id"
        val start = try {
            client.raw("GET", "/auth/google/start")
        } finally {
            GoogleClientIdTestHook.override = null
        }
        val location = start.headers().firstValue("Location").orElseThrow { AssertionError("no redirect from /auth/google/start") }
        val state = location.split("&", "?").first { it.startsWith("state=") }.removePrefix("state=")
        val callback = client.raw("GET", "/auth/google/callback?code=fake-id-token-$email&state=$state")
        check(callback.statusCode() == 302 && callback.headers().firstValue("Location").orElse("") == "/") {
            "login failed: ${callback.statusCode()} ${callback.headers().firstValue("Location")}"
        }
        return client
    }

    override fun close() {
        clients.forEach { it.close() }
        server.stop(100, 3_000)
    }
}

internal class LiveResponse(val status: Int, val body: String) {
    fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
}

internal class LiveClient(private val baseUrl: String, private val token: String?) : AutoCloseable {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .cookieHandler(CookieManager())
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val streams = mutableListOf<LiveSse>()

    private fun request(method: String, path: String, body: String?, token: String?): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
        return builder.build()
    }

    fun raw(method: String, path: String, body: String? = null): HttpResponse<String> =
        http.send(request(method, path, body, token), HttpResponse.BodyHandlers.ofString())

    /** A request with any body and headers, answered with the raw bytes. */
    fun bytes(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
        if (token != null) builder.header("Authorization", "Bearer $token")
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, if (body != null) HttpRequest.BodyPublishers.ofByteArray(body) else HttpRequest.BodyPublishers.noBody())
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    /** `POST /api/files/upload` of one file (multipart, field `file`). */
    fun upload(fileName: String, mimeType: String, content: ByteArray): LiveResponse {
        val boundary = "----test-boundary-${System.nanoTime()}"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: $mimeType\r\n\r\n"
        val body = head.toByteArray(Charsets.UTF_8) + content + "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val response = bytes("POST", "/api/files/upload", body, mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"))
        return LiveResponse(response.statusCode(), String(response.body(), Charsets.UTF_8))
    }

    fun delete(path: String, body: String? = null): LiveResponse =
        raw("DELETE", path, body).let { LiveResponse(it.statusCode(), it.body()) }

    fun get(path: String): LiveResponse = raw("GET", path).let { LiveResponse(it.statusCode(), it.body()) }

    fun post(path: String, body: String? = null): LiveResponse = raw("POST", path, body).let { LiveResponse(it.statusCode(), it.body()) }

    /** The response headers of a stream that is opened and closed at once. */
    fun streamHeaders(path: String): java.net.http.HttpHeaders {
        val response = http.send(request("GET", path, null, token), HttpResponse.BodyHandlers.ofInputStream())
        response.body().close()
        return response.headers()
    }

    /** Open an SSE response and read it as it comes. */
    fun sse(path: String, method: String = "GET", body: String? = null): LiveSse {
        val response = http.send(request(method, path, body, token), HttpResponse.BodyHandlers.ofInputStream())
        return LiveSse(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), response.body())
            .also { streams += it }
    }

    override fun close() {
        streams.forEach { it.close() }
    }
}

/**
 * An open SSE response: a reader thread parses the frames as they arrive.  [close] disconnects
 * (closes the connection under the server's feet, like an app going away).
 */
internal class LiveSse(val status: Int, val contentType: String, private val input: InputStream) : AutoCloseable {
    private object End

    private val queue = LinkedBlockingQueue<Any>()

    /** Every raw line read so far (to assert on ping comments). */
    val lines = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Every event read so far with the time (`System.nanoTime`) its frame was complete here. */
    val arrivals = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Long>>()

    private val reader = Thread {
        try {
            input.bufferedReader().use { r ->
                var event: String? = null
                var data: String? = null
                while (true) {
                    val line = r.readLine() ?: break
                    lines += line
                    when {
                        line.startsWith("event: ") -> event = line.removePrefix("event: ")
                        line.startsWith("data: ") -> data = line.removePrefix("data: ")
                        line.isEmpty() -> {
                            if (event != null && data != null) {
                                arrivals += (event to System.nanoTime())
                                queue += (event to Json.parseToJsonElement(data).jsonObject)
                            }
                            event = null
                            data = null
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // closed
        } finally {
            queue += End
        }
    }.apply { isDaemon = true; start() }

    /** The next event (comment frames — pings — are skipped), or null when the stream ended. */
    @Suppress("UNCHECKED_CAST")
    fun nextOrNull(timeoutMs: Long = 10_000): Pair<String, JsonObject>? {
        val item = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("no SSE event within ${timeoutMs}ms; read so far: $lines")
        if (item === End) {
            queue += End
            return null
        }
        return item as Pair<String, JsonObject>
    }

    fun next(timeoutMs: Long = 10_000): Pair<String, JsonObject> =
        nextOrNull(timeoutMs) ?: throw AssertionError("SSE stream ended; read so far: $lines")

    /** The data of the next event, which must be of [type]. */
    fun expect(type: String, timeoutMs: Long = 10_000): JsonObject {
        val (event, data) = next(timeoutMs)
        if (event != type) throw AssertionError("expected SSE event '$type' but got '$event' $data; read so far: $lines")
        return data
    }

    /** The next event of [type], skipping others. */
    fun nextOf(type: String, timeoutMs: Long = 10_000): JsonObject {
        while (true) {
            val (event, data) = next(timeoutMs)
            if (event == type) return data
        }
    }

    override fun close() {
        try {
            input.close()
        } catch (_: Exception) {
        }
    }
}

/** Poll until [check] holds. */
internal fun awaitTrue(timeoutMs: Long = 10_000, what: String, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (check()) return
        Thread.sleep(20)
    }
    throw AssertionError("timed out waiting for: $what")
}
