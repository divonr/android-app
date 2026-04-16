package com.example.ApI.tools

import android.content.Context
import android.webkit.MimeTypeMap
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.ApI.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class PythonInterpreterTool(
    private val context: Context,
    private val currentChat: Chat?,
    private val currentGroup: ChatGroup?
) : Tool {

    override val id = "python_interpreter"
    override val name = "Python Code Interpreter"
    override val description: String
        get() = buildDescription()

    private fun buildDescription(): String {
        val availableFiles = getAvailableFiles().map { it.file_name }
        val filesList = if (availableFiles.isEmpty()) {
            "No files currently available in conversation."
        } else {
            "Available files: ${availableFiles.joinToString(", ")}"
        }

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
        const val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10MB
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(330, TimeUnit.SECONDS) // 5.5 min to allow for 5 min server timeout
        .writeTimeout(60, TimeUnit.SECONDS) // allow time to upload files
        .build()

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        AppLogger.i("[PythonTool] execute() called with parameters: ${parameters.toString().take(500)}")

        val code = parameters["code"]?.jsonPrimitive?.contentOrNull
        if (code == null) {
            AppLogger.e("[PythonTool] 'code' parameter is missing or null")
            return ToolExecutionResult.Error("Parameter 'code' is required")
        }
        AppLogger.i("[PythonTool] Code length: ${code.length} chars")

        val filesToInclude = parameters["files_to_include"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        AppLogger.i("[PythonTool] Files to include: $filesToInclude")

        // Resolve files for upload
        val resolvedFiles = resolveFiles(filesToInclude)
        AppLogger.i("[PythonTool] Resolved ${resolvedFiles.size} files for upload")

        // Build multipart form-data request
        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("code", code)

        resolvedFiles.forEach { file ->
            val mediaType = file.mimeType.toMediaType()
            multipartBuilder.addFormDataPart(
                "files",
                file.name,
                file.bytes.toRequestBody(mediaType)
            )
        }

        val requestBody = multipartBuilder.build()
        AppLogger.i("[PythonTool] Sending POST to: $SERVER_URL")

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(SERVER_URL)
                    .header("X-Api-Key", API_KEY)
                    .post(requestBody)
                    .build()

                AppLogger.i("[PythonTool] Executing HTTP request...")
                val response = client.newCall(request).execute()
                AppLogger.i("[PythonTool] Response code: ${response.code}")
                AppLogger.i("[PythonTool] Response message: ${response.message}")

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No response body"
                    AppLogger.e("[PythonTool] HTTP error ${response.code}: $errorBody")
                    return@withContext ToolExecutionResult.Error("Server error ${response.code}: ${errorBody.take(500)}")
                }

                val responseBody = response.body?.string()
                if (responseBody == null) {
                    AppLogger.e("[PythonTool] Response body is null")
                    return@withContext ToolExecutionResult.Error("Empty response from Python executor")
                }
                AppLogger.i("[PythonTool] Response body length: ${responseBody.length} chars")
                AppLogger.i("[PythonTool] Response body preview: ${responseBody.take(1000)}")

                parseResponse(responseBody)
            } catch (e: Exception) {
                AppLogger.e("[PythonTool] Exception: ${e::class.java.simpleName}: ${e.message}")
                AppLogger.e("[PythonTool] Stack trace: ${e.stackTraceToString().take(1000)}")
                ToolExecutionResult.Error("Failed to execute Python code: ${e::class.java.simpleName}: ${e.message ?: "no message"}")
            }
        }
    }

    private fun parseResponse(responseBody: String): ToolExecutionResult {
        val json = Json.parseToJsonElement(responseBody).jsonObject

        val status = json["status"]?.jsonPrimitive?.contentOrNull ?: "error"

        if (status == "timeout") {
            return ToolExecutionResult.Error("Python execution timed out (5 minute limit exceeded)")
        }

        if (status == "error") {
            val message = json["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown server error"
            return ToolExecutionResult.Error("Server error: $message")
        }

        // status == "success"
        val logs = json["logs"]?.jsonPrimitive?.contentOrNull ?: ""
        val exitCodeObj = json["exit_code"]?.jsonObject
        val exitCode = exitCodeObj?.get("StatusCode")?.jsonPrimitive?.intOrNull ?: 0
        val artifactsJson = json["artifacts"]?.jsonArray ?: JsonArray(emptyList())

        // Save output files (artifacts)
        val savedAttachments = saveOutputFiles(artifactsJson)

        // Build result text
        val resultText = buildString {
            if (logs.isNotBlank()) {
                appendLine("**Output:**")
                appendLine("```")
                appendLine(logs.trim())
                appendLine("```")
            }
            if (exitCode != 0) {
                appendLine("\n**Exit code:** $exitCode")
            }
            if (savedAttachments.isNotEmpty()) {
                appendLine("\n**Generated Files:**")
                savedAttachments.forEach { appendLine("- ${it.file_name}") }
            }
        }

        // Build details with output files info for UI and downstream consumers
        val details = buildJsonObject {
            put("stdout", logs)
            put("stderr", "")
            put("exit_code", exitCode)
            put("output_files", buildJsonArray {
                savedAttachments.forEach { att ->
                    add(buildJsonObject {
                        put("file_name", att.file_name)
                        put("mime_type", att.mime_type)
                        put("local_file_path", att.local_file_path)
                    })
                }
            })
        }

        return if (exitCode != 0 && logs.isNotBlank()) {
            // Non-zero exit code but we have logs - treat as error with output
            ToolExecutionResult.Error(resultText.ifBlank { "Code execution failed with exit code $exitCode" }, details)
        } else {
            ToolExecutionResult.Success(
                resultText.ifBlank { "Code executed successfully (no output)" },
                details
            )
        }
    }

    private fun getAvailableFiles(): List<Attachment> {
        val files = mutableListOf<Attachment>()

        AppLogger.i("[PythonTool] Fetching available files. currentChat messages count: ${currentChat?.messages?.size ?: 0}")

        // Files from current chat messages
        currentChat?.messages?.forEach { message ->
            message.attachments.filter { it.local_file_path != null }.forEach { 
                AppLogger.i("[PythonTool] Found chat attachment: ${it.file_name} at ${it.local_file_path}")
                files.add(it) 
            }
        }

        // Files from group/project
        currentGroup?.group_attachments?.filter { it.local_file_path != null }?.forEach {
            AppLogger.i("[PythonTool] Found group attachment: ${it.file_name} at ${it.local_file_path}")
            files.add(it)
        }

        val distinct = files.distinctBy { it.file_name }
        AppLogger.i("[PythonTool] Total distinct available files: ${distinct.size}. Names: ${distinct.map { it.file_name }}")
        return distinct
    }

    private fun resolveFiles(fileNames: List<String>): List<ResolvedFile> {
        val availableFiles = getAvailableFiles()
        return fileNames.mapNotNull { fileName ->
            val cleanFileName = fileName.substringAfterLast("/")
            AppLogger.i("[PythonTool] Trying to resolve requested file: '$fileName' (Cleaned: '$cleanFileName')")
            availableFiles.find { it.file_name.equals(cleanFileName, ignoreCase = true) }
                ?.let { attachment ->
                    AppLogger.i("[PythonTool] Found match in availableFiles for '$cleanFileName'. local_path: ${attachment.local_file_path}")
                    try {
                        val file = File(attachment.local_file_path!!)
                        if (!file.exists()) {
                            AppLogger.e("[PythonTool] File DOES NOT EXIST at path: ${file.absolutePath}")
                            return@let null
                        }
                        if (file.length() > MAX_FILE_SIZE) {
                            AppLogger.e("[PythonTool] File too large: ${file.length()} bytes > MAX($MAX_FILE_SIZE)")
                            return@let null
                        }
                        
                        AppLogger.i("[PythonTool] Successfully resolved and read file: '$cleanFileName' (${file.length()} bytes)")
                        ResolvedFile(
                            name = attachment.file_name,
                            bytes = file.readBytes(),
                            mimeType = attachment.mime_type
                        )
                    } catch (e: Exception) { 
                        AppLogger.e("[PythonTool] Exception while reading file '$cleanFileName': ${e.message}")
                        null 
                    }
                } ?: run {
                    AppLogger.e("[PythonTool] Could NOT find match for '$cleanFileName' in availableFiles.")
                    null
                }
        }
    }

    private fun saveOutputFiles(artifactsJson: JsonArray): List<Attachment> {
        val attachmentsDir = File(context.filesDir, "attachments")
        if (!attachmentsDir.exists()) attachmentsDir.mkdirs()

        return artifactsJson.mapNotNull { fileJson ->
            try {
                val obj = fileJson.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val contentBase64 = obj["content_base64"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                // Detect MIME type from file extension
                val extension = name.substringAfterLast('.', "").lowercase()
                val mimeType = getMimeTypeFromExtension(extension)

                val bytes = Base64.getDecoder().decode(contentBase64)
                val localFile = File(attachmentsDir, "${UUID.randomUUID()}_$name")
                localFile.writeBytes(bytes)

                AppLogger.i("[PythonTool] Saved output file: $name (${bytes.size} bytes, $mimeType)")

                Attachment(
                    local_file_path = localFile.absolutePath,
                    file_name = name,
                    mime_type = mimeType
                )
            } catch (e: Exception) {
                AppLogger.e("[PythonTool] Failed to save output file: ${e.message}")
                null
            }
        }
    }

    private fun getMimeTypeFromExtension(extension: String): String {
        // Try Android's built-in MIME type map first
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }

        // Fallback for common types not always in MimeTypeMap
        return when (extension) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "csv" -> "text/csv"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "ppt" -> "application/vnd.ms-powerpoint"
            "json" -> "application/json"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "xml" -> "application/xml"
            "zip" -> "application/zip"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    override fun getSpecification(provider: String): ToolSpecification {
        return when (provider) {
            "openai" -> getOpenAISpecification()
            "poe" -> getPoeSpecification()
            "google" -> getGoogleSpecification()
            else -> getDefaultSpecification()
        }
    }

    private fun getOpenAISpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("code", buildJsonObject {
                        put("type", "string")
                        put("description", "Python code to execute")
                    })
                    put("files_to_include", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Exact filenames from conversation to include. They will be placed in the ./data/ directory.")
                    })
                })
                put("required", buildJsonArray { add("code"); add("files_to_include") })
            }
        )
    }

    private fun getPoeSpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("code", buildJsonObject {
                        put("type", "string")
                        put("description", "Python code to execute")
                    })
                    put("files_to_include", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Exact filenames from conversation to include. They will be placed in the ./data/ directory.")
                    })
                })
                put("required", buildJsonArray { add("code") })
            }
        )
    }

    private fun getGoogleSpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    put("code", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Python code to execute")
                    })
                    put("files_to_include", buildJsonObject {
                        put("type", "ARRAY")
                        put("items", buildJsonObject { put("type", "STRING") })
                        put("description", "Exact filenames from conversation to include. They will be placed in the ./data/ directory.")
                    })
                })
                put("required", buildJsonArray { add("code") })
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("code", buildJsonObject {
                        put("type", "string")
                        put("description", "Python code to execute")
                    })
                    put("files_to_include", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Exact filenames from conversation to include. They will be placed in the ./data/ directory.")
                    })
                })
                put("required", buildJsonArray { add("code") })
            }
        )
    }

    private data class ResolvedFile(
        val name: String,
        val bytes: ByteArray,
        val mimeType: String
    )
}
