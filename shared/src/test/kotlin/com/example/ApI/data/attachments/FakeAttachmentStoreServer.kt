package com.example.ApI.data.attachments

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Fake of the sync server's attachment store (`PUT/HEAD/GET/DELETE /files/{sha256}`,
 * `POST /files/{sha256}/commit`) on the JDK's HttpServer: per-token files in memory, the same
 * status codes and JSON answers as `sync-server/server.py`, plus switches to misbehave.
 */
class FakeAttachmentStoreServer {
    class Stored(val bytes: ByteArray, @Volatile var committed: Boolean)
    class Recorded(val method: String, val path: String, val authorization: String?)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
    val requests = CopyOnWriteArrayList<Recorded>()

    /** token → that user's files. A token that is not a key here is answered 401. */
    val users = ConcurrentHashMap<String, ConcurrentHashMap<String, Stored>>()

    @Volatile var maxFileBytes = 95L * 1024 * 1024
    /** Serve these bytes instead of the stored ones (a corrupt download). */
    @Volatile var corruptDownloads = false
    /** GET: announce the full length, send half, then drop the connection. */
    @Volatile var dropDownloadsHalfway = false
    /** Delay before a GET is answered. */
    @Volatile var getDelayMs = 0L
    /** When set: a PUT waits for it before its body is read (an upload that makes no progress). */
    @Volatile var putGate: CountDownLatch? = null
    /** When set: a GET sends half the body, then waits for it. */
    @Volatile var getGate: CountDownLatch? = null
    /** Answer every request with this status. */
    @Volatile var failWith: Int? = null

    fun addUser(token: String) { users.putIfAbsent(token, ConcurrentHashMap()) }
    fun files(token: String): MutableMap<String, Stored> = users.getValue(token)
    fun count(method: String, sha: String? = null): Int =
        requests.count { it.method == method && (sha == null || it.path.contains(sha)) }

    fun start() {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (_: Throwable) {
                runCatching { exchange.close() }
            }
        }
        server.start()
    }

    fun stop() {
        putGate?.countDown()
        getGate?.countDown()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod
        val auth = exchange.requestHeaders.getFirst("Authorization")
        requests.add(Recorded(method, path, auth))
        if (method == "PUT") putGate?.await(30, TimeUnit.SECONDS)
        val body = exchange.requestBody.readBytes()

        failWith?.let { return json(exchange, it, """{"detail":"scripted failure"}""") }
        val files = auth?.removePrefix("Bearer ")?.let { users[it] }
            ?: return json(exchange, 401, """{"detail":"Invalid or expired token"}""")

        val parts = path.removePrefix("/files/").split("/")
        val sha = parts[0]
        if (!path.startsWith("/files/") || !Regex("[0-9a-f]{64}").matches(sha)) {
            return json(exchange, 400, """{"detail":"sha256 must be 64 lowercase hex characters"}""")
        }
        val commit = parts.size == 2 && parts[1] == "commit"
        if (parts.size > 2 || (parts.size == 2 && !commit)) return json(exchange, 404, "{}")

        fun state(s: Stored) = if (s.committed) "committed" else "pending"
        when {
            method == "PUT" && !commit -> {
                val existing = files[sha]
                when {
                    existing != null ->
                        json(exchange, 200, """{"sha256":"$sha","size":${existing.bytes.size},"state":"${state(existing)}","existed":true}""")
                    body.size > maxFileBytes -> json(exchange, 413, """{"detail":"too large"}""")
                    sha256(body) != sha -> json(exchange, 422, """{"detail":{"error":"hash_mismatch"}}""")
                    else -> {
                        files[sha] = Stored(body, committed = false)
                        json(exchange, 201, """{"sha256":"$sha","size":${body.size},"state":"pending","existed":false}""")
                    }
                }
            }
            method == "HEAD" && !commit -> {
                val s = files[sha]
                if (s == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.responseHeaders.add("X-File-State", state(s))
                    exchange.responseHeaders.add("Content-Length", s.bytes.size.toString())
                    exchange.sendResponseHeaders(200, -1)
                }
                exchange.close()
            }
            method == "GET" && !commit -> {
                if (getDelayMs > 0) Thread.sleep(getDelayMs)
                val s = files[sha] ?: return json(exchange, 404, """{"detail":"Not found"}""")
                val bytes = if (corruptDownloads) s.bytes.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte() } else s.bytes
                exchange.responseHeaders.add("X-File-State", state(s))
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong().takeIf { it > 0 } ?: -1)
                val out = exchange.responseBody
                val half = bytes.size / 2
                out.write(bytes, 0, half)
                out.flush()
                if (dropDownloadsHalfway) {
                    // Closing before the announced length was sent resets the connection
                    runCatching { exchange.close() }
                    return
                }
                getGate?.await(30, TimeUnit.SECONDS)
                out.write(bytes, half, bytes.size - half)
                out.close()
            }
            method == "POST" && commit -> {
                val s = files[sha] ?: return json(exchange, 404, """{"detail":"Not found"}""")
                s.committed = true
                json(exchange, 200, """{"sha256":"$sha","size":${s.bytes.size},"state":"committed"}""")
            }
            method == "DELETE" && !commit -> {
                val s = files[sha] ?: return json(exchange, 404, """{"detail":"Not found"}""")
                if (s.committed) json(exchange, 200, """{"deleted":false,"state":"committed"}""")
                else {
                    files.remove(sha)
                    json(exchange, 200, """{"deleted":true,"state":null}""")
                }
            }
            else -> json(exchange, 405, "{}")
        }
    }

    private fun json(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
