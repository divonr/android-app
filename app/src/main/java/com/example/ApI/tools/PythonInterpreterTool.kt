package com.example.ApI.tools

import android.content.Context
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
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
Execute Python code with pandas, numpy, matplotlib, scipy, seaborn.

**File Access:**
- Specify files in 'files_to_include' parameter (list of filenames)
- Access files at /data/{filename}: `pd.read_csv('/data/sales.csv')`
- $filesList

**Output:**
- Use print() for text output
- Use plt.savefig('chart.png') to generate images
- Generated files are returned as attachments

**Limits:** 60 second timeout, 256MB memory, no network access.
        """.trimIndent()
    }

    companion object {
        const val CLOUD_FUNCTION_URL = "https://python-executor-926212364522.us-central1.run.app/"
        const val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10MB
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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

        // Resolve and encode files
        val filesPayload = resolveAndEncodeFiles(filesToInclude)
        AppLogger.i("[PythonTool] Resolved ${filesPayload.size} files for upload")

        // Build request
        val requestBody = buildJsonObject {
            put("code", code)
            put("files", buildJsonArray {
                filesPayload.forEach { file ->
                    add(buildJsonObject {
                        put("name", file.name)
                        put("content", file.base64Content)
                        put("mime_type", file.mimeType)
                    })
                }
            })
        }

        val bodyString = requestBody.toString()
        AppLogger.i("[PythonTool] Request body size: ${bodyString.length} chars")
        AppLogger.i("[PythonTool] Sending POST to: $CLOUD_FUNCTION_URL")

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(CLOUD_FUNCTION_URL)
                    .post(bodyString.toRequestBody("application/json".toMediaType()))
                    .build()

                AppLogger.i("[PythonTool] Executing HTTP request...")
                val response = client.newCall(request).execute()
                AppLogger.i("[PythonTool] Response code: ${response.code}")
                AppLogger.i("[PythonTool] Response message: ${response.message}")

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

        return if (json["success"]?.jsonPrimitive?.booleanOrNull == true) {
            val stdout = json["stdout"]?.jsonPrimitive?.contentOrNull ?: ""
            val stderr = json["stderr"]?.jsonPrimitive?.contentOrNull ?: ""
            val outputFilesJson = json["output_files"]?.jsonArray ?: JsonArray(emptyList())

            // Save output files
            val savedAttachments = saveOutputFiles(outputFilesJson)

            // Build result text
            val resultText = buildString {
                if (stdout.isNotBlank()) {
                    appendLine("**Output:**")
                    appendLine("```")
                    appendLine(stdout.trim())
                    appendLine("```")
                }
                if (stderr.isNotBlank()) {
                    appendLine("\n**Stderr:**")
                    appendLine("```")
                    appendLine(stderr.trim())
                    appendLine("```")
                }
                if (savedAttachments.isNotEmpty()) {
                    appendLine("\n**Generated Files:**")
                    savedAttachments.forEach { appendLine("- ${it.file_name}") }
                }
            }

            // Build details with output files info for UI
            val details = buildJsonObject {
                put("stdout", stdout)
                put("stderr", stderr)
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

            ToolExecutionResult.Success(
                resultText.ifBlank { "Code executed successfully (no output)" },
                details
            )
        } else {
            val error = json["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
            val traceback = json["traceback"]?.jsonPrimitive?.contentOrNull

            val errorText = buildString {
                appendLine("**Python Error:**")
                appendLine("```")
                appendLine(error)
                if (!traceback.isNullOrBlank()) {
                    appendLine()
                    appendLine(traceback)
                }
                appendLine("```")
            }

            ToolExecutionResult.Error(errorText)
        }
    }

    private fun getAvailableFiles(): List<Attachment> {
        val files = mutableListOf<Attachment>()

        // Files from current chat messages
        currentChat?.messages?.forEach { message ->
            message.attachments.filter { it.local_file_path != null }.forEach { files.add(it) }
        }

        // Files from group/project
        currentGroup?.group_attachments?.filter { it.local_file_path != null }?.forEach {
            files.add(it)
        }

        return files.distinctBy { it.file_name }
    }

    private fun resolveAndEncodeFiles(fileNames: List<String>): List<EncodedFile> {
        val availableFiles = getAvailableFiles()
        return fileNames.mapNotNull { fileName ->
            availableFiles.find { it.file_name.equals(fileName, ignoreCase = true) }
                ?.let { attachment ->
                    try {
                        val file = File(attachment.local_file_path!!)
                        if (file.exists() && file.length() <= MAX_FILE_SIZE) {
                            EncodedFile(
                                name = attachment.file_name,
                                base64Content = Base64.getEncoder().encodeToString(file.readBytes()),
                                mimeType = attachment.mime_type
                            )
                        } else null
                    } catch (e: Exception) { null }
                }
        }
    }

    private fun saveOutputFiles(outputFiles: JsonArray): List<Attachment> {
        val attachmentsDir = File(context.filesDir, "attachments")
        if (!attachmentsDir.exists()) attachmentsDir.mkdirs()

        return outputFiles.mapNotNull { fileJson ->
            try {
                val obj = fileJson.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"

                val bytes = Base64.getDecoder().decode(content)
                val localFile = File(attachmentsDir, "${UUID.randomUUID()}_$name")
                localFile.writeBytes(bytes)

                Attachment(
                    local_file_path = localFile.absolutePath,
                    file_name = name,
                    mime_type = mimeType
                )
            } catch (e: Exception) { null }
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
                        put("description", "Filenames from conversation to include (available at /data/)")
                    })
                })
                put("required", buildJsonArray { add("code") })
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
                        put("description", "Filenames from conversation to include (available at /data/)")
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
                        put("description", "Filenames from conversation to include (available at /data/)")
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
                        put("description", "Filenames from conversation to include (available at /data/)")
                    })
                })
                put("required", buildJsonArray { add("code") })
            }
        )
    }

    private data class EncodedFile(
        val name: String,
        val base64Content: String,
        val mimeType: String
    )
}
