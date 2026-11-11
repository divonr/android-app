package com.example.ApI.tools.google.drive

import com.example.ApI.data.network.GoogleDriveApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

class DriveCreateFolderTool(
    private val apiService: GoogleDriveApiService,
    private val googleEmail: String
) : Tool {

    override val id = "drive_create_folder"
    override val name = "Create Drive Folder"
    override val description = "Create a new folder in Google Drive. Authenticated as '$googleEmail'. Provide folder name and optionally parent folder ID."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            val name = parameters["name"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'name' is required")
            val parentId = parameters["parentId"]?.jsonPrimitive?.contentOrNull

            val result = apiService.createFolder(name, parentId)

            result.fold(
                onSuccess = { folder ->
                    ToolExecutionResult.Success(buildString {
                        appendLine("**Folder Created Successfully**\n")
                        appendLine("**Name:** ${folder.name}")
                        appendLine("**Folder ID:** ${folder.id}")
                        folder.webViewLink?.let { appendLine("**Link:** $it") }
                    }, buildJsonObject {
                        put("folderId", folder.id)
                        put("name", folder.name)
                    })
                },
                onFailure = { error ->
                    ToolExecutionResult.Error("Failed to create folder: ${error.message}", buildJsonObject { put("name", name) })
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
                put("name", buildJsonObject { put("type", "STRING"); put("description", "Folder name") })
                put("parentId", buildJsonObject { put("type", "STRING"); put("description", "Parent folder ID (optional)") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("name"))))
        })
        else -> ToolSpecification(id, description, buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Folder name") })
                put("parentId", buildJsonObject { put("type", "string"); put("description", "Parent folder ID (optional)") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("name"))))
        })
    }
}
