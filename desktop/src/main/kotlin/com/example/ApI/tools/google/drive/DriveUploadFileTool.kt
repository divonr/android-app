package com.example.ApI.tools.google.drive

import android.util.Base64
import com.example.ApI.data.network.GoogleDriveApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

class DriveUploadFileTool(
    private val apiService: GoogleDriveApiService,
    private val googleEmail: String
) : Tool {

    override val id = "drive_upload_file"
    override val name = "Upload Drive File"
    override val description = "Upload a file to Google Drive. Authenticated as '$googleEmail'. Provide file name, content (as text or base64), mime type, and optionally parent folder ID."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            val name = parameters["name"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'name' is required")
            val content = parameters["content"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'content' is required")
            val mimeType = parameters["mimeType"]?.jsonPrimitive?.contentOrNull ?: "text/plain"
            val parentId = parameters["parentId"]?.jsonPrimitive?.contentOrNull
            val isBase64 = parameters["isBase64"]?.jsonPrimitive?.booleanOrNull ?: false

            val contentBytes = if (isBase64) {
                Base64.decode(content, Base64.DEFAULT)
            } else {
                content.toByteArray()
            }

            val result = apiService.uploadFile(name, mimeType, contentBytes, parentId)

            result.fold(
                onSuccess = { file ->
                    ToolExecutionResult.Success(buildString {
                        appendLine("**File Uploaded Successfully**\n")
                        appendLine("**Name:** ${file.name}")
                        appendLine("**File ID:** ${file.id}")
                        appendLine("**Type:** ${file.mimeType}")
                        file.size?.let { appendLine("**Size:** $it bytes") }
                        file.webViewLink?.let { appendLine("**Link:** $it") }
                    }, buildJsonObject {
                        put("fileId", file.id)
                        put("name", file.name)
                    })
                },
                onFailure = { error ->
                    ToolExecutionResult.Error("Failed to upload: ${error.message}", buildJsonObject { put("name", name) })
                }
            )
        } catch (e: Exception) {
            ToolExecutionResult.Error("Error: ${e.message}")
        }
    }

    override fun getSpecification(provider: String) = when (provider) {
        "google" -> ToolSpecification(id, description, buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("name", buildJsonObject { put("type", "STRING"); put("description", "File name") })
                put("content", buildJsonObject { put("type", "STRING"); put("description", "File content (text or base64)") })
                put("mimeType", buildJsonObject { put("type", "STRING"); put("description", "MIME type (default text/plain)"); put("default", "text/plain") })
                put("parentId", buildJsonObject { put("type", "STRING"); put("description", "Parent folder ID (optional)") })
                put("isBase64", buildJsonObject { put("type", "BOOLEAN"); put("description", "Is content base64 encoded (default false)"); put("default", false) })
            })
            put("required", JsonArray(listOf(JsonPrimitive("name"), JsonPrimitive("content"))))
        })
        else -> ToolSpecification(id, description, buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "File name") })
                put("content", buildJsonObject { put("type", "string"); put("description", "File content (text or base64)") })
                put("mimeType", buildJsonObject { put("type", "string"); put("description", "MIME type (default text/plain)"); put("default", "text/plain") })
                put("parentId", buildJsonObject { put("type", "string"); put("description", "Parent folder ID (optional)") })
                put("isBase64", buildJsonObject { put("type", "boolean"); put("description", "Is content base64 encoded (default false)"); put("default", false) })
            })
            put("required", JsonArray(listOf(JsonPrimitive("name"), JsonPrimitive("content"))))
        })
    }
}
