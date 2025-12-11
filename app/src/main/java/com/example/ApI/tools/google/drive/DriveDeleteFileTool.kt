package com.example.ApI.tools.google.drive

import com.example.ApI.data.network.GoogleDriveApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

class DriveDeleteFileTool(
    private val apiService: GoogleDriveApiService,
    private val googleEmail: String
) : Tool {

    override val id = "drive_delete_file"
    override val name = "Delete Drive File"
    override val description = "Delete a file or folder from Google Drive. Authenticated as '$googleEmail'. Provide the file or folder ID. This action is permanent."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            val fileId = parameters["fileId"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'fileId' is required")

            val result = apiService.deleteFile(fileId)

            result.fold(
                onSuccess = {
                    ToolExecutionResult.Success("**File deleted successfully**\n\nFile ID: $fileId has been permanently deleted from Drive.", buildJsonObject {
                        put("fileId", fileId)
                        put("deleted", true)
                    })
                },
                onFailure = { error ->
                    ToolExecutionResult.Error("Failed to delete file: ${error.message}", buildJsonObject { put("fileId", fileId) })
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
                put("fileId", buildJsonObject { put("type", "STRING"); put("description", "The file or folder ID to delete") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("fileId"))))
        })
        else -> ToolSpecification(id, description, buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("fileId", buildJsonObject { put("type", "string"); put("description", "The file or folder ID to delete") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("fileId"))))
        })
    }
}
