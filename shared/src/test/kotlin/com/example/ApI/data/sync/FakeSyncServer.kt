package com.example.ApI.data.sync

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
 * In-memory fake of the sync-server protocol with the CAS semantics of the real `server.py`
 * (T1): per-account blobs (account chosen by bearer token), `base_version` compare-and-swap with
 * the 409 `version_conflict` body, sha-equal PUTs as idempotent no-ops, strictly increasing
 * versions, `?version=N` reads and `/sync/history`.  Backed by the JDK's built-in HttpServer on an
 * ephemeral port (no test-only dependencies).
 *
 * Fault injection for the multi-device tests: failing / response-losing / spuriously conflicting
 * PUTs, failing manifests, delayed GETs and hooks run before a GET / PUT is handled (to make
 * another device or a local write race the request).
 */
class FakeSyncServer {
    data class Blob(val content: String, val updatedAt: Long, val sha: String)

    companion object {
        /** Account of any token that was not minted / registered (keeps simple tests simple). */
        const val DEFAULT_USER = "u"
        private const val HISTORY_KEEP = 30

        init {
            // Small responses otherwise wait for Nagle + delayed ACK (~40 ms per request)
            System.setProperty("sun.net.httpserver.nodelay", "true")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val blobs = HashMap<String, HashMap<String, Blob>>()  // user → filename → blob
    private val history = HashMap<String, ArrayDeque<Blob>>()       // "user/filename" → older versions, newest first
    private var clock = 1_000_000L
    private val tokens = ConcurrentHashMap<String, String>()        // token → user
    private val revoked = ConcurrentHashMap.newKeySet<String>()
    private val mintCounter = AtomicInteger()

    /** Last content PUT per filename (any account) — successful writes only. */
    val putBodies = ConcurrentHashMap<String, String>()

    /** Every request as "METHOD path" (for assertions about traffic). */
    val requests = CopyOnWriteArrayList<String>()
    val putCount = AtomicInteger()

    // ── Fault injection ───────────────────────────────────────────────────────
    /** The next N PUTs fail with 503 without being applied. */
    val failNextPuts = AtomicInteger()
    /** The next N PUTs are applied but answered with 500 (lost response). */
    val loseNextPutResponses = AtomicInteger()
    /** The next N conditional PUTs get a 409 with the current meta even if the base matches. */
    val conflictNextPuts = AtomicInteger()
    /** The next N manifest calls fail with 503. */
    val failNextManifests = AtomicInteger()
    @Volatile var getDelayMs = 0L
    @Volatile var beforeGet: ((user: String, filename: String) -> Unit)? = null
    @Volatile var beforePut: ((user: String, filename: String) -> Unit)? = null
    /** Behave like the pre-T1 server: `base_version` ignored (last write wins), no `"cas"` in responses. */
    @Volatile var legacyNoCas = false

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

    fun revoke(token: String) {
        revoked.add(token)
    }

    fun seed(filename: String, content: String, updatedAt: Long? = null, user: String = DEFAULT_USER) = synchronized(lock) {
        val files = blobs.getOrPut(user) { HashMap() }
        val prev = files[filename]
        val version = updatedAt ?: nextVersion(prev?.updatedAt)
        clock = maxOf(clock, version)
        if (prev != null) pushHistory(user, filename, prev)
        files[filename] = Blob(content, version, sha256(content))
    }

    fun blob(filename: String, user: String = DEFAULT_USER): Blob? = synchronized(lock) { blobs[user]?.get(filename) }

    fun content(filename: String, user: String = DEFAULT_USER): String? = blob(filename, user)?.content

    fun filenames(user: String = DEFAULT_USER): Set<String> = synchronized(lock) { blobs[user]?.keys?.toSet() ?: emptySet() }

    fun versions(filename: String, user: String = DEFAULT_USER): List<Blob> = synchronized(lock) {
        listOfNotNull(blobs[user]?.get(filename)) + (history["$user/$filename"] ?: emptyList())
    }

    private fun pushHistory(user: String, filename: String, blob: Blob) {
        val h = history.getOrPut("$user/$filename") { ArrayDeque() }
        h.addFirst(blob)
        while (h.size > HISTORY_KEEP) h.removeLast()
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    fun start() {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (t: Throwable) {
                respond(exchange, 500, """{"detail":"${t.javaClass.simpleName}"}""")
            }
        }
        server.start()
    }

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun userOf(exchange: HttpExchange): String? {
        val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim() ?: return null
        if (token.isEmpty() || token in revoked) return null
        return tokens[token] ?: DEFAULT_USER
    }

    private fun metaJson(name: String, b: Blob): String =
        """{"filename":${JsonPrimitive(name)},"updated_at":${b.updatedAt},"sha":"${b.sha}"}"""

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod
        requests.add("$method $path")
        if (path == "/sync/health") return respond(exchange, 200, if (legacyNoCas) """{"status":"ok"}""" else """{"status":"ok","cas":true}""")
        if (path == "/auth/google" && method == "POST") {
            val body = json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val idToken = body["id_token"]?.jsonPrimitive?.content ?: return respond(exchange, 422, "{}")
            val user = idToken.removePrefix("google:")
            val token = tokenFor(user)
            return respond(exchange, 200, """{"token":"$token","username":"$user","email":"$user@example.com"}""")
        }
        val user = userOf(exchange) ?: return respond(exchange, 401, """{"detail":"Unauthorized"}""")
        when {
            path == "/sync/manifest" -> {
                if (failNextManifests.getAndUpdate { maxOf(0, it - 1) } > 0) return respond(exchange, 503, "{}")
                val body = synchronized(lock) {
                    (blobs[user] ?: emptyMap()).entries.sortedBy { it.key }.joinToString(",", "[", "]") { (n, b) -> metaJson(n, b) }
                }
                respond(exchange, 200, body)
            }
            path.startsWith("/sync/history/") -> {
                val name = URLDecoder.decode(path.removePrefix("/sync/history/"), "UTF-8")
                val body = synchronized(lock) {
                    val cur = blobs[user]?.get(name)
                    val items = listOfNotNull(cur?.let { metaJson(name, it).dropLast(1) + ""","current":true}""" }) +
                        (history["$user/$name"] ?: emptyList()).map { metaJson(name, it).dropLast(1) + ""","current":false,"replaced_at":0}""" }
                    items.joinToString(",", "[", "]")
                }
                respond(exchange, 200, body)
            }
            path.startsWith("/sync/file/") && method == "GET" -> {
                val name = URLDecoder.decode(path.removePrefix("/sync/file/"), "UTF-8")
                beforeGet?.invoke(user, name)
                if (getDelayMs > 0) Thread.sleep(getDelayMs)
                val version = exchange.requestURI.query?.split("&")?.firstOrNull { it.startsWith("version=") }
                    ?.removePrefix("version=")?.toLongOrNull()
                val blob = synchronized(lock) {
                    val cur = blobs[user]?.get(name)
                    when {
                        version == null -> cur
                        cur?.updatedAt == version -> cur
                        else -> history["$user/$name"]?.firstOrNull { it.updatedAt == version }
                    }
                }
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
                val baseVersion = if (legacyNoCas) null else (body["base_version"] as? JsonPrimitive)?.longOrNull
                beforePut?.invoke(user, name)
                if (failNextPuts.getAndUpdate { maxOf(0, it - 1) } > 0) return respond(exchange, 503, """{"detail":"unavailable"}""")
                val sha = sha256(content)
                var lostResponse = false
                val (code, response) = synchronized(lock) {
                    val files = blobs.getOrPut(user) { HashMap() }
                    val cur = files[name]
                    if (!legacyNoCas && cur != null && cur.sha == sha) return@synchronized 200 to metaJson(name, cur).dropLast(1) + ""","cas":true}"""
                    val spurious = baseVersion != null && conflictNextPuts.getAndUpdate { maxOf(0, it - 1) } > 0
                    val conflict = spurious || when {
                        baseVersion == null -> false
                        baseVersion == 0L -> cur != null
                        else -> cur == null || cur.updatedAt != baseVersion
                    }
                    if (conflict) {
                        val current = cur?.let { metaJson(name, it) } ?: "null"
                        return@synchronized 409 to """{"detail":{"error":"version_conflict","current":$current}}"""
                    }
                    if (cur != null) pushHistory(user, name, cur)
                    val b = Blob(content, nextVersion(cur?.updatedAt), sha)
                    files[name] = b
                    putBodies[name] = content
                    putCount.incrementAndGet()
                    lostResponse = loseNextPutResponses.getAndUpdate { maxOf(0, it - 1) } > 0
                    200 to (if (legacyNoCas) metaJson(name, b) else metaJson(name, b).dropLast(1) + ""","cas":true}""")
                }
                if (lostResponse) respond(exchange, 500, """{"detail":"lost"}""") else respond(exchange, code, response)
            }
            else -> respond(exchange, 404, """{"detail":"Not found"}""")
        }
    }

    /** Wait until a successful upload of [filename] arrives (or timeout). */
    fun awaitPut(filename: String, timeoutMs: Long = 5000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            putBodies[filename]?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
