package com.example.ApI.server

import com.example.ApI.server.auth.FakeSyncAuthClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process fake of the sync server (port of the shared tests' `FakeSyncServer`, which the
 * :server test source set can't see) with the CAS semantics of the real `server.py`: per-account
 * blobs chosen by bearer token, `base_version` compare-and-swap with the 409 `version_conflict`
 * body, sha-equal PUTs as idempotent no-ops, strictly increasing versions, `/sync/health` with
 * `"cas": true`.  `/auth/google` accepts the tests' `fake-id-token-{email}` id tokens (username =
 * the sanitized email, like the real server), so the real [com.example.ApI.server.auth.RealSyncAuthClient]
 * can log in against it.  JDK HttpServer on an ephemeral port: tests never reach the live server.
 */
class FakeCasSyncServer {
    data class Blob(val content: String, val updatedAt: Long, val sha: String)

    companion object {
        init {
            // Small responses otherwise wait for Nagle + delayed ACK (~40 ms per request)
            System.setProperty("sun.net.httpserver.nodelay", "true")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val blobs = HashMap<String, HashMap<String, Blob>>()  // user → filename → blob
    private var clock = 1_000_000L
    private val tokens = ConcurrentHashMap<String, String>()        // token → user
    private val revoked = ConcurrentHashMap.newKeySet<String>()
    private val mintCounter = AtomicInteger()

    /** Delay (ms) before answering any authenticated sync request (a hanging sync server). */
    @Volatile
    var syncDelayMs: Long = 0L

    /** Every request as "METHOD path" (for assertions about traffic). */
    val requests = CopyOnWriteArrayList<String>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor: ExecutorService = Executors.newFixedThreadPool(8)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun nextVersion(prev: Long?): Long {
        clock = maxOf(clock + 1, (prev ?: 0L) + 1)
        return clock
    }

    // ── Direct access (test setup / assertions) ───────────────────────────────

    /** Mint a token for [user] (what `/auth/google` does). */
    fun tokenFor(user: String): String {
        val token = "tok-$user-${mintCounter.incrementAndGet()}"
        tokens[token] = user
        return token
    }

    /** Revoke every token of [user] (the next sync call gets a 401). */
    fun revokeAll(user: String) {
        tokens.filterValues { it == user }.keys.forEach { revoked.add(it) }
    }

    fun seed(user: String, filename: String, content: String) = synchronized(lock) {
        val files = blobs.getOrPut(user) { HashMap() }
        files[filename] = Blob(content, nextVersion(files[filename]?.updatedAt), sha256(content))
    }

    fun content(user: String, filename: String): String? = synchronized(lock) { blobs[user]?.get(filename)?.content }

    fun filenames(user: String): Set<String> = synchronized(lock) { blobs[user]?.keys?.toSet() ?: emptySet() }

    fun requestCount(prefix: String): Int = requests.count { it.startsWith(prefix) }

    /** Poll until [predicate] holds for [user]'s [filename] (null content: absent). */
    fun awaitContent(user: String, filename: String, timeoutMs: Long = 10_000, predicate: (String) -> Boolean): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            content(user, filename)?.let { if (predicate(it)) return it }
            Thread.sleep(25)
        }
        return null
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    fun start(): FakeCasSyncServer {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (t: Throwable) {
                respond(exchange, 500, """{"detail":"${t.javaClass.simpleName}"}""")
            }
        }
        server.start()
        return this
    }

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun userOf(exchange: HttpExchange): String? {
        val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim() ?: return null
        if (token.isEmpty() || token in revoked) return null
        return tokens[token]
    }

    private fun metaJson(name: String, b: Blob): String =
        """{"filename":${JsonPrimitive(name)},"updated_at":${b.updatedAt},"sha":"${b.sha}"}"""

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod
        requests.add("$method $path")
        if (path == "/sync/health") return respond(exchange, 200, """{"status":"ok","cas":true}""")
        if (path == "/auth/google" && method == "POST") {
            val body = json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val idToken = body["id_token"]?.jsonPrimitive?.content ?: return respond(exchange, 422, "{}")
            if (!idToken.startsWith("fake-id-token-")) return respond(exchange, 401, """{"detail":"Invalid token"}""")
            val email = FakeSyncAuthClient.parseFakeTokenEmail(idToken)
            val user = FakeSyncAuthClient.sanitizeEmail(email)
            val token = tokenFor(user)
            return respond(exchange, 200, """{"token":"$token","username":"$user","email":${JsonPrimitive(email)}}""")
        }
        val user = userOf(exchange) ?: return respond(exchange, 401, """{"detail":"Unauthorized"}""")
        if (syncDelayMs > 0) Thread.sleep(syncDelayMs)
        when {
            path == "/sync/manifest" -> {
                val body = synchronized(lock) {
                    (blobs[user] ?: emptyMap()).entries.sortedBy { it.key }.joinToString(",", "[", "]") { (n, b) -> metaJson(n, b) }
                }
                respond(exchange, 200, body)
            }
            path.startsWith("/sync/file/") && method == "GET" -> {
                val name = URLDecoder.decode(path.removePrefix("/sync/file/"), "UTF-8")
                val blob = synchronized(lock) { blobs[user]?.get(name) }
                if (blob == null) respond(exchange, 404, """{"detail":"Not found"}""")
                else respond(
                    exchange, 200,
                    """{"filename":${JsonPrimitive(name)},"content":${JsonPrimitive(blob.content)},"updated_at":${blob.updatedAt},"sha":"${blob.sha}"}"""
                )
            }
            path.startsWith("/sync/file/") && method == "PUT" -> {
                val name = URLDecoder.decode(path.removePrefix("/sync/file/"), "UTF-8")
                val body = json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                val content = body["content"]?.jsonPrimitive?.content ?: return respond(exchange, 422, "{}")
                val baseVersion = (body["base_version"] as? JsonPrimitive)?.longOrNull
                val sha = sha256(content)
                val (code, response) = synchronized(lock) {
                    val files = blobs.getOrPut(user) { HashMap() }
                    val cur = files[name]
                    if (cur != null && cur.sha == sha) return@synchronized 200 to metaJson(name, cur).dropLast(1) + ""","cas":true}"""
                    val conflict = when {
                        baseVersion == null -> false
                        baseVersion == 0L -> cur != null
                        else -> cur == null || cur.updatedAt != baseVersion
                    }
                    if (conflict) {
                        val current = cur?.let { metaJson(name, it) } ?: "null"
                        return@synchronized 409 to """{"detail":{"error":"version_conflict","current":$current}}"""
                    }
                    val b = Blob(content, nextVersion(cur?.updatedAt), sha)
                    files[name] = b
                    200 to metaJson(name, b).dropLast(1) + ""","cas":true}"""
                }
                respond(exchange, code, response)
            }
            else -> respond(exchange, 404, """{"detail":"Not found"}""")
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
