package com.example.ApI.tools

import com.example.ApI.data.PlatformStorage
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import com.example.ApI.data.attachments.AttachmentHash
import com.example.ApI.data.attachments.FileDigest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Where the tool's files are on a machine that is not the device that attached them (the web
 * server; CLOUD_RUNS_PLAN.md, "Attachment store"). Without it the tool works with the
 * attachments' `local_file_path`, as on the device.
 */
interface PythonToolFiles {
    /** Whether [attachment]'s file can be had here at all, and so belongs to the tool's input files. */
    fun isAvailable(attachment: Attachment): Boolean

    /** The file of [attachment] (fetched if needed), or null when it cannot be had. */
    suspend fun inputFile(attachment: Attachment): File?

    /**
     * Keep a file the code generated and describe it as an attachment — with its hash and size
     * and, off the device, without a `local_file_path`. null: it could not be kept.
     */
    suspend fun saveOutput(fileName: String, mimeType: String, bytes: ByteArray): Attachment?
}

/**
 * Where the remote executor is and the key it is called with. Neither is in the sources: the
 * platform layer passes them in (Android: BuildConfig, from `local.properties` or the build's
 * environment; desktop: a constant generated the same way; the web server: its environment).
 */
data class SandboxConfig(val executorUrl: String, val apiKey: String) {
    /** null when the tool can be used, else the error it reports instead of running anything. */
    val configurationError: String?
        get() = when {
            executorUrl.isBlank() -> "The code sandbox is not configured: this build has no executor URL."
            apiKey.isBlank() -> "The code sandbox is not configured: this build has no API key for the executor " +
                "(sandbox.apiKey in local.properties or SANDBOX_API_KEY in the environment). Tell the user; retrying will not help."
            else -> null
        }

    // The key must not end up in a log line or an error message
    override fun toString(): String = "SandboxConfig(executorUrl=$executorUrl, apiKey=${if (apiKey.isBlank()) "<none>" else "<set>"})"
}

/**
 * The code sandbox (`workspace_sandbox` to the models; its ID is still `python_interpreter`,
 * see [ToolRegistry.PYTHON_INTERPRETER]) - shared implementation, speaking the executor's v2 protocol
 * (`POST /v2/execute`):
 *
 *  - the session is the chat: `chat_id` is the chat's id, and the executor keeps one container
 *    per chat. Without a chat it is `nochat-<id of this install>` (kept in `sandbox_install_id`
 *    under the files dir), so such calls share one container per install;
 *  - every call names ALL the files of the conversation in `input_manifest` (name, size,
 *    SHA-256): the attachments of the chat's messages and of its group / project that can be
 *    had here. The model does not choose files; the executor puts them in `/app/input`. A file
 *    whose content is nowhere this machine can reach (never hashed, not on this machine) is
 *    left out and named in the result; a file that should be reachable but cannot be fetched
 *    when the executor asks for it fails the call, naming the file;
 *  - the first request carries no file bodies. The executor answers `missing_files` with the
 *    names it does not hold yet (or holds with another hash) and the same request is sent once
 *    more with those bodies. A file is therefore uploaded once per container, not per call;
 *  - the tool's result is the run's whole output: nothing is cut on this side.
 *
 * @param files   how input files are found and output files kept (null: the device's own
 *                files, by `local_file_path`).
 * @param sandbox the remote executor and its key (tests point it at a fake).
 */
class PythonInterpreterTool(
    private val platformStorage: PlatformStorage,
    private val currentChat: Chat?,
    private val currentGroup: ChatGroup?,
    private val sandbox: SandboxConfig,
    private val files: PythonToolFiles? = null
) : Tool {

    // The ID the settings and the stored calls know the tool by (ToolRegistry.PYTHON_INTERPRETER);
    // the models are told ToolRegistry.WORKSPACE_SANDBOX_NAME
    override val id = ToolRegistry.PYTHON_INTERPRETER
    override val name = "Workspace Sandbox"

    // The same on every request, whatever the chat's files: a provider's prompt cache covers
    // the tool declarations
    override val description: String get() = DESCRIPTION

    /** One entry of `input_manifest`: [name] is the file's name in `/app/input`. */
    class InputFile internal constructor(
        val name: String,
        val attachment: Attachment,
        internal var digest: FileDigest,
        internal var file: File?
    ) {
        val sha256: String get() = digest.sha256
        val size: Long get() = digest.size
    }

    /** [files]: the manifest. [unavailable]: names of conversation files that cannot be had here at all. */
    class InputManifest internal constructor(val files: List<InputFile>, val unavailable: List<String>) {
        fun toJson(): JsonArray = buildJsonArray {
            files.forEach { add(buildJsonObject { put("name", it.name); put("size", it.size); put("sha256", it.sha256) }) }
        }
    }

    /** A conversation file whose bytes could not be had although they should be there. */
    private class InputFileUnavailable(val fileName: String, val reason: String) : Exception()

    companion object {
        /** What the models are told about the tool. Raw text: no indentation is stripped. */
        val DESCRIPTION: String = """
Execute Python 3.11 code inside a persistent, full-featured Debian Linux container (root access) to perform data analysis, complex calculations, web scraping, system tasks (via subprocess/bash), and file/document generation (PDF, Word, Excel, PowerPoint, charts, archives).

ARCHITECTURE & PERSISTENCE:
1. Container vs. Process Lifecycle:
   - FILESYSTEM IS PERSISTENT across tool calls within the active conversation. Files saved in `/app`, installed `pip` packages, and `apt-get` system packages remain available in subsequent calls (Caveat: After two hours of inactivity, the cloud container will terminate and reset).
   - PYTHON MEMORY IS STATELESS between calls. Each tool call executes as a fresh `python3` process. In-memory variables do NOT persist automatically—save intermediate data (e.g., DataFrames, state) to disk (`/app/temp.parquet`, `.pkl`, or `.sqlite`) if needed across steps.
2. Directory Structure & File Export:
   - Working Directory: `/app` (use for intermediate files, subdirectories, and temporary work).
   - Input Files: Any conversation files (user attachments) are placed in `/app/input/{file_name}` (also symlinked at `./data/{file_name}`). If two different files share a name, the later one is stored as `name (2).ext`.
   - Output / User Attachments: ONLY files saved directly inside `/app/output/` (e.g., `/app/output/report.pdf`, `/app/output/chart.png`) will be automatically attached and sent to the user in the chat interface. Do not place intermediate files in `/app/output/`.

ENVIRONMENT & PRE-INSTALLED TOOLS:
- Privileges & Network: Running as `root` (`uid=0`) with full outbound internet access. You can run shell commands via `subprocess.run(...)` and install missing packages via `pip` or `apt-get`.
- Hebrew & RTL Support: Full Hebrew font packages (`fonts-culmus`, `fonts-noto`, `fonts-dejavu`), `python-bidi`, and `arabic-reshaper` are pre-installed.
  * Note for Matplotlib/ReportLab/FPDF2 in Hebrew: Always load a TrueType font from `/usr/share/fonts/truetype/` and apply `bidi.algorithm.get_display(arabic_reshaper.reshape(text))` when rendering static images/PDFs that lack native BiDi shaping, or generate HTML+CSS (`dir="rtl"`) and convert to PDF via `weasyprint` / `wkhtmltopdf` / `libreoffice --headless --convert-to pdf`.
- Pre-installed Stack: pandas, numpy, scipy, scikit-learn, duckdb, matplotlib, seaborn, plotly, openpyxl, python-docx, python-pptx, fpdf2, reportlab, weasyprint, pdfplumber, pypdf, pillow, beautifulsoup4, requests, git, curl, jq, sqlite3, ffmpeg, libreoffice, pandoc.
- Limits: 4 vCPUs, 2GB RAM, 512MB `/dev/shm`, 5-minute timeout per call. Use `print()` to return text output to yourself.
""".trim('\n')

        /**
         * The files a run of the tool generated, as attachments, from the `output_files` of its
         * result's details: each with its `local_file_path` (a file on this device) and / or its
         * `sha256` + `size` (a file in the account's attachment store). What saves the tool's
         * messages attaches them to the next assistant message.
         */
        fun outputAttachments(result: ToolExecutionResult?): List<Attachment> {
            val details = when (result) {
                is ToolExecutionResult.Success -> result.details
                is ToolExecutionResult.Error -> result.details
                null -> null
            }
            val outputFiles = (details?.get("output_files") as? JsonArray) ?: return emptyList()
            return outputFiles.mapNotNull { fileJson ->
                try {
                    val obj = fileJson.jsonObject
                    val fileName = obj["file_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
                    val localPath = obj["local_file_path"]?.jsonPrimitive?.contentOrNull
                    val sha256 = obj["sha256"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { AttachmentHash.isValid(it) }
                    if (localPath == null && sha256 == null) return@mapNotNull null
                    Attachment(
                        local_file_path = localPath,
                        file_name = fileName,
                        mime_type = mimeType,
                        sha256 = sha256,
                        size = if (sha256 != null) obj["size"]?.jsonPrimitive?.longOrNull else null
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

        /**
         * The name a conversation file gets in `/app/input`: a plain file name (no path
         * separators, nothing a multipart header cannot carry as it is).
         */
        fun inputFileName(fileName: String): String {
            val clean = fileName
                .map { c -> if (c == '/' || c == '\\' || c.isISOControl()) '_' else if (c == '"') '\'' else c }
                .joinToString("").trim()
            return if (clean.isEmpty() || clean == "." || clean == "..") "file" else clean
        }

        /** `name (2).ext`, `name (3).ext`…: what a file is called when [name] is taken by another file. */
        private fun numbered(name: String, n: Int): String {
            val dot = name.lastIndexOf('.')
            return if (dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
        }

        private class CachedDigest(val length: Long, val lastModified: Long, val digest: FileDigest)

        /** Hashes of files already hashed in this process, by path; valid while size and time are the same. */
        private val digestCache = ConcurrentHashMap<String, CachedDigest>()

        /** How many files were actually hashed (tests). */
        internal val hashedFiles = java.util.concurrent.atomic.AtomicInteger()

        private suspend fun digestOf(file: File): FileDigest {
            val path = file.absolutePath
            val length = file.length()
            val modified = file.lastModified()
            digestCache[path]?.takeIf { it.length == length && it.lastModified == modified }?.let { return it.digest }
            val digest = AttachmentHash.of(file)
            hashedFiles.incrementAndGet()
            digestCache[path] = CachedDigest(length, modified, digest)
            return digest
        }

        private val json = Json { ignoreUnknownKeys = true }
    }

    // Read: the executor runs code for up to 300 s, then collects the files. Write: per write
    // to the socket, not per upload, so a large file takes as long as it needs.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(420, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** The executor's session key: the chat, or this install when there is no chat. */
    internal fun sessionKey(): String {
        currentChat?.chat_id?.takeIf { it.isNotBlank() }?.let { return it }
        val file = File(platformStorage.filesDir, "sandbox_install_id")
        val saved = try { file.takeIf { it.exists() }?.readText()?.trim() } catch (e: Exception) { null }
        if (!saved.isNullOrBlank()) return "nochat-$saved"
        val created = UUID.randomUUID().toString()
        try { file.writeText(created) } catch (e: Exception) { /* a new one next time */ }
        return "nochat-$created"
    }

    /**
     * Every file of the conversation, as the executor is told about it. Order: the chat's
     * messages, then the group's files. Names: the attachment's own name; the same name with
     * the same content is one file, with another content it is `name (2).ext`, `name (3).ext`…
     * in that order — so a file keeps its name from call to call as the chat grows.
     */
    internal suspend fun inputManifest(): InputManifest {
        val usable: (Attachment) -> Boolean = this.files?.let { hooks -> { a: Attachment -> hooks.isAvailable(a) } }
            ?: { a: Attachment -> a.local_file_path != null }
        val all = currentChat?.messages.orEmpty().flatMap { it.attachments } + currentGroup?.group_attachments.orEmpty()

        val entries = mutableListOf<InputFile>()
        val byName = HashMap<String, InputFile>()
        val unavailable = all.filterNot(usable).map { it.file_name }.toMutableList()
        for (attachment in all.filter(usable)) {
            // The recorded identity is taken as it is (no fetch of a file the executor may already
            // hold); should its file not be had when the executor asks for it, the call fails
            val recorded = attachment.sha256?.takeIf(AttachmentHash::isValid)
                ?.let { sha -> attachment.size?.takeIf { it >= 0 }?.let { FileDigest(sha, it) } }
            var file: File? = null
            val digest = recorded ?: try {
                file = localFile(attachment)
                digestOf(file)
            } catch (e: InputFileUnavailable) {
                // Never hashed and not on this machine: nothing can fetch it
                unavailable += attachment.file_name
                continue
            } catch (e: java.io.IOException) {
                unavailable += attachment.file_name
                continue
            }

            val base = inputFileName(attachment.file_name)
            var name = base
            var n = 2
            var duplicate = false
            while (true) {
                val taken = byName[name] ?: break
                if (taken.sha256 == digest.sha256) { duplicate = true; break }
                name = numbered(base, n++)
            }
            if (duplicate) continue
            InputFile(name, attachment, digest, file).also { entries += it; byName[name] = it }
        }
        return InputManifest(entries, unavailable.distinct().filter { inputFileName(it) !in byName })
    }

    /** The attachment's file on this machine (fetched if needed); fails naming the file. */
    private suspend fun localFile(attachment: Attachment): File {
        val file = try {
            if (files != null) files.inputFile(attachment) else attachment.local_file_path?.let { File(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw InputFileUnavailable(attachment.file_name, e.message ?: e::class.java.simpleName)
        }
        if (file == null || !file.isFile) {
            throw InputFileUnavailable(attachment.file_name, "its content is not on this machine and could not be fetched")
        }
        return file
    }

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        AppLogger.i("[PythonTool] execute() called")
        val code = parameters["code"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'code' is required")
        sandbox.configurationError?.let { return ToolExecutionResult.Error(it) }

        return withContext(Dispatchers.IO) {
            try {
                val manifest = inputManifest()
                val chatId = sessionKey()

                // Handshake: first without bodies, then once with the bodies the executor asked for
                var response = post(code, chatId, manifest, emptyList())
                if (response.status == "missing_files") {
                    val wanted = response.missing
                    val unknown = wanted.filter { name -> manifest.files.none { it.name == name } }
                    if (unknown.isNotEmpty()) {
                        return@withContext ToolExecutionResult.Error(
                            "The sandbox asked for files that are not part of this conversation: ${unknown.joinToString(", ")}. Nothing was run."
                        )
                    }
                    val upload = manifest.files.filter { it.name in wanted }
                    for (entry in upload) {
                        val file = entry.file ?: localFile(entry.attachment).also { entry.file = it }
                        // What is sent must be what the manifest says
                        val actual = try { digestOf(file) } catch (e: java.io.IOException) {
                            throw InputFileUnavailable(entry.attachment.file_name, "it could not be read")
                        }
                        entry.digest = actual
                    }
                    AppLogger.i("[PythonTool] uploading ${upload.size} input file(s) the sandbox does not hold")
                    response = post(code, chatId, manifest, upload)
                    if (response.status == "missing_files") {
                        return@withContext ToolExecutionResult.Error(
                            "The sandbox did not accept these input files after they were uploaded: " +
                                "${response.missing.joinToString(", ")}. Nothing was run."
                        )
                    }
                }
                response.failure?.let { return@withContext ToolExecutionResult.Error(it) }
                toResult(response.body!!, manifest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: InputFileUnavailable) {
                ToolExecutionResult.Error(
                    "The conversation file \"${e.fileName}\" could not be given to the sandbox: ${e.reason}. Nothing was run. " +
                        "Tell the user; the file may need to be attached again."
                )
            } catch (e: java.net.SocketTimeoutException) {
                ToolExecutionResult.Error("The sandbox did not answer in time (${e.message ?: "timeout"}). The code may still have run.")
            } catch (e: Exception) {
                ToolExecutionResult.Error("Failed to execute Python code: ${e::class.java.simpleName}: ${e.message ?: "no message"}")
            }
        }
    }

    /** One answer of the executor: its JSON [body], or why there is none ([failure]). */
    private class ExecutorResponse(val body: JsonObject?, val failure: String?) {
        val status: String? get() = body?.get("status")?.jsonPrimitive?.contentOrNull
        val missing: List<String>
            get() = (body?.get("missing") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }

    private fun post(code: String, chatId: String, manifest: InputManifest, bodies: List<InputFile>): ExecutorResponse {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("code", code)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("input_manifest", manifest.toJson().toString())
        bodies.forEach { entry ->
            val type = entry.attachment.mime_type.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
            multipart.addFormDataPart("files", entry.name, entry.file!!.asRequestBody(type))
        }
        val request = Request.Builder()
            .url(sandbox.executorUrl)
            .header("X-Api-Key", sandbox.apiKey)
            .post(multipart.build())
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return ExecutorResponse(
                    null,
                    when (response.code) {
                        401, 403 -> "The sandbox refused this app's API key (HTTP ${response.code}). Tell the user; retrying will not help."
                        // Cloudflare, in front of the executor for a device: no answer within about 100 s
                        524 -> "The route to the sandbox gave up waiting for the answer (HTTP 524): on this device a call " +
                            "cannot take longer than about 100 seconds. The code was not stopped and may still be running; " +
                            "its output is lost. Split the work into shorter calls, or run a long job in the background " +
                            "(e.g. subprocess.Popen with its output redirected to a file under /app) and read its result in a later call."
                        else -> "Sandbox error: HTTP ${response.code}" + if (text.isBlank()) "" else ": $text"
                    }
                )
            }
            val body = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
                ?: return ExecutorResponse(null, "The sandbox sent an answer that is not JSON" + if (text.isBlank()) " (empty)." else ": $text")
            return ExecutorResponse(body, null)
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** The model's view of a run: everything the code printed, how it ended and what it left. */
    private suspend fun toResult(body: JsonObject, manifest: InputManifest): ToolExecutionResult {
        val status = body.string("status") ?: "error"
        if (status != "success" && status != "timeout") {
            return ToolExecutionResult.Error("Sandbox error: ${body.string("message") ?: "unknown error (status \"$status\")"}")
        }
        val timedOut = status == "timeout"
        val stdout = body.string("stdout").orEmpty()
        val stderr = body.string("stderr").orEmpty()
        val exitCode = (body["exit_code"] as? JsonPrimitive)?.intOrNull
        val truncated = (body["truncated"] as? JsonPrimitive)?.booleanOrNull == true
        val freshReason = body.string("fresh_reason")
            ?.takeIf { (body["fresh_container"] as? JsonPrimitive)?.booleanOrNull == true }

        val (savedAttachments, unsaved) = saveOutputFiles((body["artifacts"] as? JsonArray) ?: JsonArray(emptyList()))

        val sections = mutableListOf<String>()
        when (freshReason) {
            "expired" -> "the previous one was removed after two hours without a call"
            "lost" -> "the previous one is gone"
            else -> null
        }?.let { why ->
            sections += "[Sandbox notice: this call ran in a new container ($why). Files created by earlier calls and " +
                "packages installed with pip or apt-get are gone; the conversation's files are in /app/input again.]"
        }
        if (manifest.unavailable.isNotEmpty()) {
            sections += "[Sandbox notice: these conversation files are NOT in /app/input, because their content is not " +
                "available where this tool runs: ${manifest.unavailable.joinToString(", ")}]"
        }
        val failed = timedOut || (exitCode != null && exitCode != 0)
        // Whole, whatever their length
        if (stdout.isNotBlank()) sections += "**stdout:**\n```\n${stdout.trimEnd('\n', '\r')}\n```"
        if (stderr.isNotBlank()) sections += "**stderr:**\n```\n${stderr.trimEnd('\n', '\r')}\n```"
        if (stdout.isBlank() && stderr.isBlank() && !failed) sections += "Code executed successfully (no output)"
        if (exitCode != null && exitCode != 0) sections += "**Exit code:** $exitCode"
        if (timedOut) {
            sections += "**Timed out:** " + (body.string("message")?.takeIf { it.isNotBlank() }
                ?: "the process was killed after the 5 minute limit; what it printed until then is above.")
        }
        if (truncated) {
            sections += "**Note:** the sandbox truncated this output (more than 10 MB in a stream): its start and its end are above, the middle is missing."
        }
        if (savedAttachments.isNotEmpty()) {
            sections += "**Generated Files:**\n" + savedAttachments.joinToString("\n") { "- ${it.file_name}" }
        }
        if (unsaved.isNotEmpty()) {
            sections += "**Note:** these files in /app/output could not be attached to the chat: ${unsaved.joinToString(", ")}"
        }

        val details = buildJsonObject {
            // The output itself is the result's text: it is not stored a second time
            put("exit_code", exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
            put("timed_out", timedOut)
            put("output_files", buildJsonArray {
                savedAttachments.forEach { att ->
                    add(buildJsonObject {
                        put("file_name", att.file_name)
                        put("mime_type", att.mime_type)
                        // Stored in the synced tool message: only a path of the device itself
                        att.local_file_path?.let { put("local_file_path", it) }
                        att.sha256?.let { put("sha256", it) }
                        att.size?.let { put("size", it) }
                    })
                }
            })
        }
        val text = sections.joinToString("\n\n")
        return if (failed) ToolExecutionResult.Error(text, details) else ToolExecutionResult.Success(text, details)
    }

    /** The artifacts as attachments, and the names of the ones that could not be kept. */
    private suspend fun saveOutputFiles(artifactsJson: JsonArray): Pair<List<Attachment>, List<String>> {
        val attachmentsDir = File(platformStorage.filesDir, "attachments").apply { mkdirs() }
        val unsaved = mutableListOf<String>()
        val saved = artifactsJson.mapNotNull { fileJson ->
            val name = ((fileJson as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            try {
                val contentBase64 = fileJson.jsonObject["content_base64"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null.also { unsaved += name }
                val ext = name.substringAfterLast('.', "").lowercase()
                val mimeType = getMimeType(ext)
                val bytes = Base64.getDecoder().decode(contentBase64)
                if (files != null) return@mapNotNull files.saveOutput(name, mimeType, bytes) ?: null.also { unsaved += name }
                val localFile = File(attachmentsDir, "${UUID.randomUUID()}_${inputFileName(name)}")
                localFile.writeBytes(bytes)
                Attachment(local_file_path = localFile.absolutePath, file_name = name, mime_type = mimeType)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                unsaved += name
                null
            }
        }
        return saved to unsaved
    }

    private fun getMimeType(ext: String) = when (ext) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"; "csv" -> "text/csv"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xls" -> "application/vnd.ms-excel"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "doc" -> "application/msword"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "json" -> "application/json"; "txt" -> "text/plain"; "html", "htm" -> "text/html"
        "md" -> "text/markdown"
        "xml" -> "application/xml"; "zip" -> "application/zip"
        "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    override fun getSpecification(provider: String): ToolSpecification {
        val isGoogle = provider == "google"
        return ToolSpecification(name = ToolRegistry.WORKSPACE_SANDBOX_NAME, description = description, parameters = buildJsonObject {
            put("type", if (isGoogle) "OBJECT" else "object")
            put("properties", buildJsonObject {
                put("code", buildJsonObject { put("type", if (isGoogle) "STRING" else "string"); put("description", "Python code to execute") })
            })
            put("required", buildJsonArray { add("code") })
        })
    }
}
