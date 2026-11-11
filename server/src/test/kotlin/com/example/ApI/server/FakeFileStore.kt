package com.example.ApI.server

import com.example.ApI.server.runs.ProviderFileUploader
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Nothing listens there: a Python tool of a test never reaches the real executor. */
const val UNREACHABLE_PYTHON_EXECUTOR = "http://127.0.0.1:9/execute"

/** The default of every server test: no file is ever uploaded to a real provider. */
val NO_PROVIDER_UPLOADS = ProviderFileUploader { _, _, _, _, _ -> null }

/**
 * Fake of the sync server's attachment store (`PUT/HEAD/GET/DELETE /files/{sha256}`,
 * `POST /files/{sha256}/commit`) — a port of the shared tests' `FakeAttachmentStoreServer`, which
 * the :server test source set can't see: per-token files in memory, the same status codes and
 * JSON answers as `sync-server/server.py`.  A token is its own user unless [accounts] maps it
 * (several tokens of one account share its files); [knownTokens], when set, restricts who is let in.
 */
class FakeFileStore {
    class Stored(val bytes: ByteArray, @Volatile var committed: Boolean)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** Every request as "METHOD path". */
    val requests = CopyOnWriteArrayList<String>()

    /** token → account (default: the token itself). */
    val accounts = ConcurrentHashMap<String, String>()

    /** When not null: only these tokens are accepted (others get 401). */
    @Volatile var knownTokens: Set<String>? = null

    /** Answer every request with this status. */
    @Volatile var failWith: Int? = null

    @Volatile var maxFileBytes = 95L * 1024 * 1024

    private val users = ConcurrentHashMap<String, ConcurrentHashMap<String, Stored>>()

    /** The files of [account] (a token, or what [accounts] maps it to). */
    fun files(account: String): MutableMap<String, Stored> = users.getOrPut(account) { ConcurrentHashMap() }

    /** Put [bytes] into [account]'s store (what a device's upload does); returns the hash. */
    fun put(account: String, bytes: ByteArray, committed: Boolean = true): String =
        sha256(bytes).also { files(account)[it] = Stored(bytes, committed) }

    fun count(method: String, sha: String? = null): Int =
        requests.count { it.startsWith("$method ") && (sha == null || it.contains(sha)) }

    fun start(): FakeFileStore {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (_: Throwable) {
                runCatching { exchange.close() }
            }
        }
        server.start()
        return this
    }

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod
        requests.add("$method $path")
        val body = exchange.requestBody.readBytes()

        failWith?.let { return json(exchange, it, """{"detail":"scripted failure"}""") }
        val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrEmpty() || knownTokens?.contains(token) == false) {
            return json(exchange, 401, """{"detail":"Invalid or expired token"}""")
        }
        val files = files(accounts[token] ?: token)

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
                val s = files[sha] ?: return json(exchange, 404, """{"detail":"Not found"}""")
                exchange.responseHeaders.add("X-File-State", state(s))
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, s.bytes.size.toLong().takeIf { it > 0 } ?: -1)
                exchange.responseBody.use { it.write(s.bytes) }
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
        init {
            // Small responses otherwise wait for Nagle + delayed ACK (~40 ms per request)
            System.setProperty("sun.net.httpserver.nodelay", "true")
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Fake of the remote Python executor (`POST /execute`, multipart): records the code and the
 * input files of every call and answers with [logs] and [artifacts] (name → bytes).
 */
class FakePythonExecutor {
    class Call(val code: String, val files: Map<String, ByteArray>)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}/execute"
    val calls = CopyOnWriteArrayList<Call>()

    @Volatile var logs = "ok"
    @Volatile var artifacts: Map<String, ByteArray> = emptyMap()

    fun start(): FakePythonExecutor {
        server.createContext("/execute") { exchange ->
            try {
                val boundary = exchange.requestHeaders.getFirst("Content-Type").substringAfter("boundary=").trim('"')
                val (code, files) = parseMultipart(exchange.requestBody.readBytes(), boundary)
                calls += Call(code, files)
                val encoder = java.util.Base64.getEncoder()
                val artifactsJson = artifacts.entries.joinToString(",") { (name, bytes) ->
                    """{"name":"$name","content_base64":"${encoder.encodeToString(bytes)}"}"""
                }
                val body = """{"status":"success","session_id":"sess-1","logs":"$logs","exit_code":{"StatusCode":0},"artifacts":[$artifactsJson]}"""
                    .toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } catch (_: Throwable) {
                runCatching { exchange.close() }
            }
        }
        server.start()
        return this
    }

    fun stop() = server.stop(0)

    /** The `code` field and the `files` parts (file name → bytes) of a multipart body. */
    private fun parseMultipart(body: ByteArray, boundary: String): Pair<String, Map<String, ByteArray>> {
        // ISO-8859-1 maps every byte to one char: positions in the text are positions in the bytes
        val text = String(body, Charsets.ISO_8859_1)
        var code = ""
        val files = LinkedHashMap<String, ByteArray>()
        for (part in text.split("--$boundary")) {
            val headerEnd = part.indexOf("\r\n\r\n")
            if (headerEnd < 0) continue
            val headers = part.substring(0, headerEnd)
            val content = part.substring(headerEnd + 4).removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
            val name = Regex("name=\"([^\"]*)\"").find(headers)?.groupValues?.get(1) ?: continue
            val fileName = Regex("filename=\"([^\"]*)\"").find(headers)?.groupValues?.get(1)
            when {
                name == "code" -> code = String(content, Charsets.UTF_8)
                name == "files" && fileName != null -> files[fileName] = content
            }
        }
        return code to files
    }
}
