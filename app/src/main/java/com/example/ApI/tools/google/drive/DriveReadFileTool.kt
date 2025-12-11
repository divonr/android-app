package com.example.ApI.tools.google.drive

import com.example.ApI.data.network.GoogleDriveApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

class DriveReadFileTool(
    private val apiService: GoogleDriveApiService,
    private val googleEmail: String
) : Tool {

    override val id = "drive_read_file"
    override val name = "Read Drive File"
    override val description = "Read content of a file from Google Drive. Authenticated as '$googleEmail'. Provide the file ID."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            val fileId = parameters["fileId"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'fileId' is required")

            val result = apiService.readFileContent(fileId)

            result.fold(
                onSuccess = { content ->
                    ToolExecutionResult.Success("**File Content:**\n\n```\n$content\n```", buildJsonObject {
                        put("fileId", fileId)
                        put("length", content.length)
                    })
                },
                onFailure = { error ->
                    ToolExecutionResult.Error("Failed to read file: ${error.message}", buildJsonObject { put("fileId", fileId) })
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
                put("fileId", buildJsonObject { put("type", "STRING"); put("description", "The file ID") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("fileId"))))
        })
        else -> ToolSpecification(id, description, buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("fileId", buildJsonObject { put("type", "string"); put("description", "The file ID") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("fileId"))))
        })
    }
}
