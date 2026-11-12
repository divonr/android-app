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
const val UNREACHABLE_PYTHON_EXECUTOR = "http://127.0.0.1:9/v2/execute"

/** The key the tests' executor expects (nothing real). */
const val TEST_SANDBOX_KEY = "test-sandbox-key"

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

    /**
     * Called for every request before it is handled, with "METHOD" (`COMMIT` for the commit
     * route) and the hash: a test blocks in it to hold a request at the store's door.
     */
    @Volatile var beforeRequest: ((method: String, sha256: String) -> Unit)? = null

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

        beforeRequest?.invoke(if (commit) "COMMIT" else method, sha)

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
 * Fake of the code executor's v2 endpoint (`POST /v2/execute`, multipart): keeps the input files
 * of every `chat_id`, asks for the ones a manifest names and it does not hold (`missing_files`),
 * and answers a run with [logs] and [artifacts] (name → bytes).
 */
class FakePythonExecutor {
    /**
     * One run: its code, its session, the files of `/app/input` it ran with ([files]) and the
     * ones that were uploaded for it ([uploaded]).
     */
    class Call(val code: String, val chatId: String, val files: Map<String, ByteArray>, val uploaded: Set<String>)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}/v2/execute"

    /** The runs (a request answered with `missing_files` ran nothing and is not one). */
    val calls = CopyOnWriteArrayList<Call>()

    /** How many requests arrived, handshake rounds included. */
    val requests = java.util.concurrent.atomic.AtomicInteger()

    private val inputs = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, ByteArray>>()

    @Volatile var logs = "ok"
    @Volatile var artifacts: Map<String, ByteArray> = emptyMap()

    fun start(): FakePythonExecutor {
        server.createContext("/v2/execute") { exchange ->
            try {
                requests.incrementAndGet()
                val boundary = exchange.requestHeaders.getFirst("Content-Type").substringAfter("boundary=").trim('"')
                val (fields, files) = parseMultipart(exchange.requestBody.readBytes(), boundary)
                val (status, answer) = if (exchange.requestHeaders.getFirst("X-Api-Key") != TEST_SANDBOX_KEY) {
                    401 to """{"detail":"bad key"}"""
                } else {
                    200 to answer(fields, files)
                }
                val body = answer.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } catch (_: Throwable) {
                runCatching { exchange.close() }
            }
        }
        server.start()
        return this
    }

    fun stop() = server.stop(0)

    private fun answer(fields: Map<String, String>, files: Map<String, ByteArray>): String {
        val chatId = fields.getValue("chat_id")
        val held = inputs.getOrPut(chatId) { java.util.concurrent.ConcurrentHashMap() }
        val manifest = kotlinx.serialization.json.Json.parseToJsonElement(fields["input_manifest"] ?: "[]") as kotlinx.serialization.json.JsonArray
        val wanted = manifest.map {
            val entry = it as kotlinx.serialization.json.JsonObject
            (entry.getValue("name") as kotlinx.serialization.json.JsonPrimitive).content to
                (entry.getValue("sha256") as kotlinx.serialization.json.JsonPrimitive).content
        }
        val missing = wanted.filter { (name, sha) -> (files[name] ?: held[name])?.let { FakeFileStore.sha256(it) } != sha }.map { it.first }
        if (missing.isNotEmpty()) {
            return """{"status":"missing_files","missing":[${missing.joinToString(",") { "\"$it\"" }}]}"""
        }
        val fresh = calls.none { it.chatId == chatId }
        wanted.forEach { (name, _) -> files[name]?.let { held[name] = it } }
        calls += Call(fields.getValue("code"), chatId, LinkedHashMap(held.filterKeys { name -> wanted.any { it.first == name } }), files.keys.toSet())
        val encoder = java.util.Base64.getEncoder()
        val artifactsJson = artifacts.entries.joinToString(",") { (name, bytes) ->
            """{"name":"$name","size":${bytes.size},"content_base64":"${encoder.encodeToString(bytes)}"}"""
        }
        return """{"status":"success","exit_code":0,"stdout":"$logs","stderr":"","truncated":false,"artifacts":[$artifactsJson],""" +
            """"fresh_container":$fresh${if (fresh) ""","fresh_reason":"new"""" else ""}}"""
    }

    /** The text fields and the `files` parts (file name → bytes) of a multipart body. */
    private fun parseMultipart(body: ByteArray, boundary: String): Pair<Map<String, String>, Map<String, ByteArray>> {
        // ISO-8859-1 maps every byte to one char: positions in the text are positions in the bytes
        val text = String(body, Charsets.ISO_8859_1)
        val fields = LinkedHashMap<String, String>()
        val files = LinkedHashMap<String, ByteArray>()
        for (part in text.split("--$boundary")) {
            val headerEnd = part.indexOf("\r\n\r\n")
            if (headerEnd < 0) continue
            val headers = part.substring(0, headerEnd)
            val content = part.substring(headerEnd + 4).removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
            val name = Regex("name=\"([^\"]*)\"").find(headers)?.groupValues?.get(1) ?: continue
            val fileName = Regex("filename=\"([^\"]*)\"").find(headers)?.groupValues?.get(1)
                ?.let { String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }
            if (fileName != null) files[fileName] = content else fields[name] = String(content, Charsets.UTF_8)
        }
        return fields to files
    }
}
