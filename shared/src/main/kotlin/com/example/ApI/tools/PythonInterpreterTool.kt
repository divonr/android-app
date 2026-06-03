package com.example.ApI.tools

import com.example.ApI.data.PlatformStorage
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Python Interpreter tool - shared implementation.
 * Uses file-based session persistence via PlatformStorage.
 */
class PythonInterpreterTool(
    private val platformStorage: PlatformStorage,
    private val currentChat: Chat?,
    private val currentGroup: ChatGroup?
) : Tool {

    override val id = "python_interpreter"
    override val name = "Python Code Interpreter"
    override val description: String get() = buildDescription()

    private fun buildDescription(): String {
        val availableFiles = getAvailableFiles().map { it.file_name }
        val filesList = if (availableFiles.isEmpty()) "No files currently available in conversation."
        else "Available files: ${availableFiles.joinToString(", ")}"
        return """
Consider using this tool for data processing, analysis, or when generating files (Word, PowerPoint, Excel, PDF, images, etc.) would benefit the user's request.

Packages: pandas, numpy, matplotlib, scipy, seaborn, openpyxl, python-pptx, python-docx, Pillow, fpdf, networkx. Internet access available — pip install additional packages if needed.

Input files: specify EXACT filenames (without paths) in 'files_to_include', read from ./data/{file_name}.
$filesList

Output: print() for text. Save files to working directory (e.g., plt.savefig('chart.png')). Generated files are displayed to the user as message attachments.

Limits: 5 min timeout, 2GB RAM.
        """.trimIndent()
    }

    companion object {
        const val SERVER_URL = "https://api-divonr.xyz/execute"
        const val API_KEY = "Ato01g?4:elop-ef,kq32"
        const val MAX_FILE_SIZE = 10 * 1024 * 1024L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(330, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // File-based session persistence
    private val sessionPrefsFile = File(platformStorage.filesDir, "python_sessions.json")

    private fun loadSessionMap(): MutableMap<String, String> {
        if (!sessionPrefsFile.exists()) return mutableMapOf()
        return try {
            val obj = Json.parseToJsonElement(sessionPrefsFile.readText()).jsonObject
            obj.entries.associate { it.key to it.value.jsonPrimitive.content }.toMutableMap()
        } catch (e: Exception) { mutableMapOf() }
    }

    private fun loadSessionId(chatKey: String): String? = loadSessionMap()[chatKey]

    private fun saveSessionId(chatKey: String, sessionId: String) {
        val map = loadSessionMap()
        map[chatKey] = sessionId
        val jsonObj = buildJsonObject { map.forEach { (k, v) -> put(k, v) } }
        sessionPrefsFile.writeText(jsonObj.toString())
    }

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        AppLogger.i("[PythonTool] execute() called")
        val code = parameters["code"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'code' is required")

        val filesToInclude = parameters["files_to_include"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val resolvedFiles = resolveFiles(filesToInclude)

        val chatKey = currentChat?.id ?: "default_session"
        val savedSessionId = loadSessionId("python_session_$chatKey")

        val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("code", code)
            .addFormDataPart("kill_session", "false")
        if (savedSessionId != null) multipartBuilder.addFormDataPart("session_id", savedSessionId)
        resolvedFiles.forEach { file ->
            multipartBuilder.addFormDataPart("files", file.name, file.bytes.toRequestBody(file.mimeType.toMediaType()))
        }

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(SERVER_URL)
                    .header("X-Api-Key", API_KEY)
                    .post(multipartBuilder.build())
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No response body"
                    return@withContext ToolExecutionResult.Error("Server error ${response.code}: ${errorBody.take(500)}")
                }
                val responseBody = response.body?.string()
                    ?: return@withContext ToolExecutionResult.Error("Empty response from Python executor")
                parseResponse(responseBody, chatKey)
            } catch (e: Exception) {
                ToolExecutionResult.Error("Failed to execute Python code: ${e::class.java.simpleName}: ${e.message ?: "no message"}")
            }
        }
    }

    private fun parseResponse(responseBody: String, chatKey: String): ToolExecutionResult {
        val json = Json.parseToJsonElement(responseBody).jsonObject
        json["session_id"]?.jsonPrimitive?.contentOrNull?.let { sessionId ->
            saveSessionId("python_session_$chatKey", sessionId)
        }
        val status = json["status"]?.jsonPrimitive?.contentOrNull ?: "error"
        if (status == "timeout") return ToolExecutionResult.Error("Python execution timed out (5 minute limit exceeded)")
        if (status == "error") {
            val message = json["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown server error"
            return ToolExecutionResult.Error("Server error: $message")
        }
        val logs = json["logs"]?.jsonPrimitive?.contentOrNull ?: ""
        val exitCode = json["exit_code"]?.jsonObject?.get("StatusCode")?.jsonPrimitive?.intOrNull ?: 0
        val artifactsJson = json["artifacts"]?.jsonArray ?: JsonArray(emptyList())
        val savedAttachments = saveOutputFiles(artifactsJson)
        val resultText = buildString {
            if (logs.isNotBlank()) { appendLine("**Output:**"); appendLine("```"); appendLine(logs.trim()); appendLine("```") }
            if (exitCode != 0) appendLine("\n**Exit code:** $exitCode")
            if (savedAttachments.isNotEmpty()) { appendLine("\n**Generated Files:**"); savedAttachments.forEach { appendLine("- ${it.file_name}") } }
        }
        val details = buildJsonObject {
            put("stdout", logs); put("stderr", ""); put("exit_code", exitCode)
            put("output_files", buildJsonArray {
                savedAttachments.forEach { att -> add(buildJsonObject { put("file_name", att.file_name); put("mime_type", att.mime_type); put("local_file_path", att.local_file_path) }) }
            })
        }
        return if (exitCode != 0 && logs.isNotBlank()) ToolExecutionResult.Error(resultText.ifBlank { "Code execution failed with exit code $exitCode" }, details)
        else ToolExecutionResult.Success(resultText.ifBlank { "Code executed successfully (no output)" }, details)
    }

    private fun getAvailableFiles(): List<Attachment> {
        val files = mutableListOf<Attachment>()
        currentChat?.messages?.forEach { msg -> msg.attachments.filter { it.local_file_path != null }.forEach { files.add(it) } }
        currentGroup?.group_attachments?.filter { it.local_file_path != null }?.forEach { files.add(it) }
        return files.distinctBy { it.file_name }
    }

    private fun resolveFiles(fileNames: List<String>): List<ResolvedFile> {
        val available = getAvailableFiles()
        return fileNames.mapNotNull { fileName ->
            val clean = fileName.substringAfterLast("/")
            available.find { it.file_name.equals(clean, ignoreCase = true) }?.let { att ->
                try {
                    val file = File(att.local_file_path!!)
                    if (!file.exists() || file.length() > MAX_FILE_SIZE) return@let null
                    ResolvedFile(att.file_name, file.readBytes(), att.mime_type)
                } catch (e: Exception) { null }
            }
        }
    }

    private fun saveOutputFiles(artifactsJson: JsonArray): List<Attachment> {
        val attachmentsDir = File(platformStorage.filesDir, "attachments").apply { mkdirs() }
        return artifactsJson.mapNotNull { fileJson ->
            try {
                val obj = fileJson.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val contentBase64 = obj["content_base64"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val ext = name.substringAfterLast('.', "").lowercase()
                val mimeType = getMimeType(ext)
                val bytes = Base64.getDecoder().decode(contentBase64)
                val localFile = File(attachmentsDir, "${UUID.randomUUID()}_$name")
                localFile.writeBytes(bytes)
                Attachment(local_file_path = localFile.absolutePath, file_name = name, mime_type = mimeType)
            } catch (e: Exception) { null }
        }
    }

    private fun getMimeType(ext: String) = when (ext) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"; "csv" -> "text/csv"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xls" -> "application/vnd.ms-excel"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "doc" -> "application/msword"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "json" -> "application/json"; "txt" -> "text/plain"; "html", "htm" -> "text/html"
        "xml" -> "application/xml"; "zip" -> "application/zip"
        "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    override fun getSpecification(provider: String): ToolSpecification {
        val isGoogle = provider == "google"
        return ToolSpecification(name = id, description = description, parameters = buildJsonObject {
            put("type", if (isGoogle) "OBJECT" else "object")
            put("properties", buildJsonObject {
                put("code", buildJsonObject { put("type", if (isGoogle) "STRING" else "string"); put("description", "Python code to execute") })
                put("files_to_include", buildJsonObject {
                    put("type", if (isGoogle) "ARRAY" else "array")
                    put("items", buildJsonObject { put("type", if (isGoogle) "STRING" else "string") })
                    put("description", "Exact filenames from conversation to include. They will be placed in the ./data/ directory.")
                })
            })
            put("required", buildJsonArray { add("code"); if (!isGoogle) add("files_to_include") })
        })
    }

    private data class ResolvedFile(val name: String, val bytes: ByteArray, val mimeType: String)
}
