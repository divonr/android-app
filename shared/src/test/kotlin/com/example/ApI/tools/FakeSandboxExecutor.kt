package com.example.ApI.tools

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fake of the code executor's v2 endpoint (`POST /v2/execute`, multipart): one set of input
 * files per `chat_id`, the `missing_files` handshake, and a scripted answer for the run itself.
 */
class FakeSandboxExecutor(private val apiKey: String = KEY) {
    class ManifestEntry(val name: String, val size: Long, val sha256: String)

    /** One request as it arrived: [fields] are the text fields, [files] the uploaded bodies by file name. */
    class Call(val key: String?, val fields: Map<String, String>, val files: Map<String, ByteArray>) {
        val code: String get() = fields.getValue("code")
        val chatId: String get() = fields.getValue("chat_id")
        val manifest: List<ManifestEntry>
            get() = Json.parseToJsonElement(fields.getValue("input_manifest")).jsonArray.map {
                val o = it.jsonObject
                ManifestEntry(o["name"]!!.jsonPrimitive.content, o["size"]!!.jsonPrimitive.long, o["sha256"]!!.jsonPrimitive.content)
            }
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}/v2/execute"
    val config: SandboxConfig get() = SandboxConfig(url, apiKey)

    /** Every request, handshake rounds included. */
    val calls = CopyOnWriteArrayList<Call>()

    /** The requests that ran code. */
    val runs = CopyOnWriteArrayList<Call>()

    /** `/app/input` of every chat: name → sha256. */
    val inputs = ConcurrentHashMap<String, MutableMap<String, String>>()

    // The answer of a run
    @Volatile var status = "success"
    @Volatile var stdout = ""
    @Volatile var stderr = ""
    @Volatile var exitCode: Int? = 0
    @Volatile var truncated = false
    @Volatile var message: String? = null
    @Volatile var artifacts: Map<String, ByteArray> = emptyMap()
    /** null: `new` on a chat's first run, not fresh afterwards. */
    @Volatile var freshReason: String? = null

    /** Names reported missing whatever was uploaded (a broken executor). */
    @Volatile var alwaysMissing: List<String> = emptyList()

    /**
     * As the real executor does while code runs: an HTTP 200 answer starts at once and this many
     * single spaces, [heartbeatMillis] apart, come before the JSON document.
     */
    @Volatile var heartbeats = 0
    @Volatile var heartbeatMillis = 0L

    /** Answer every request with this status and body instead. */
    @Volatile var rawAnswer: Pair<Int, String>? = null

    fun start(): FakeSandboxExecutor {
        server.createContext("/v2/execute") { exchange ->
            try {
                val boundary = exchange.requestHeaders.getFirst("Content-Type").substringAfter("boundary=").trim('"')
                val call = parseMultipart(exchange.requestHeaders.getFirst("X-Api-Key"), exchange.requestBody.readBytes(), boundary)
                calls += call
                val (code, body) = rawAnswer ?: answer(call)
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                if (code == 200 && heartbeats > 0) {
                    exchange.sendResponseHeaders(200, 0)   // chunked: the length is not known yet
                    exchange.responseBody.use { out ->
                        repeat(heartbeats) {
                            out.write(' '.code); out.flush()
                            Thread.sleep(heartbeatMillis)
                        }
                        out.write(bytes)
                    }
                } else {
                    exchange.sendResponseHeaders(code, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            } catch (_: Throwable) {
                runCatching { exchange.close() }
            }
        }
        server.start()
        return this
    }

    fun stop() = server.stop(0)

    private fun answer(call: Call): Pair<Int, String> {
        if (call.key != apiKey) return 401 to """{"detail":"bad key"}"""
        val held = inputs.getOrPut(call.chatId) { ConcurrentHashMap() }
        val firstRun = runs.none { it.chatId == call.chatId }
        val missing = call.manifest.filter { entry ->
            val uploaded = call.files[entry.name]
            when {
                entry.name in alwaysMissing -> true
                uploaded != null -> sha256(uploaded) != entry.sha256 || uploaded.size.toLong() != entry.size
                else -> held[entry.name] != entry.sha256
            }
        }.map { it.name }
        if (missing.isNotEmpty()) {
            return 200 to buildJsonObject {
                put("status", "missing_files")
                put("missing", buildJsonArray { missing.forEach { add(JsonPrimitive(it)) } })
            }.toString()
        }
        call.manifest.forEach { held[it.name] = it.sha256 }
        runs += call

        val reason = freshReason ?: if (firstRun) "new" else null
        val encoder = java.util.Base64.getEncoder()
        return 200 to buildJsonObject {
            put("status", status)
            put("exit_code", exitCode)
            put("stdout", stdout)
            put("stderr", stderr)
            put("truncated", truncated)
            message?.let { put("message", it) }
            put("artifacts", buildJsonArray {
                artifacts.forEach { (name, bytes) ->
                    add(buildJsonObject {
                        put("name", name)
                        put("size", bytes.size)
                        put("content_base64", encoder.encodeToString(bytes))
                    })
                }
            })
            put("fresh_container", reason != null)
            reason?.let { put("fresh_reason", it) }
        }.toString()
    }

    private fun parseMultipart(key: String?, body: ByteArray, boundary: String): Call {
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
        return Call(key, fields, files)
    }

    companion object {
        const val KEY = "test-sandbox-key"

        init {
            // Small responses otherwise wait for Nagle + delayed ACK (~40 ms per request)
            System.setProperty("sun.net.httpserver.nodelay", "true")
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
