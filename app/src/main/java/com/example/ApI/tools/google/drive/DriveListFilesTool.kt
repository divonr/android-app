package com.example.ApI.tools.google.drive

import com.example.ApI.data.network.GoogleDriveApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

class DriveListFilesTool(
    private val apiService: GoogleDriveApiService,
    private val googleEmail: String
) : Tool {

    override val id = "drive_list_files"
    override val name = "List Drive Files"
    override val description = "List files and folders in Google Drive. Authenticated as '$googleEmail'. Optionally provide folder ID (null for root), query filter, and max results (default 20, max 100)."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            val folderId = parameters["folderId"]?.jsonPrimitive?.contentOrNull
            val query = parameters["query"]?.jsonPrimitive?.contentOrNull
            val maxResults = parameters["maxResults"]?.jsonPrimitive?.intOrNull ?: 20

            val result = apiService.listFiles(folderId, query, maxResults.coerceIn(1, 100))

            result.fold(
                onSuccess = { files ->
                    if (files.isEmpty()) {
                        ToolExecutionResult.Success("No files found.", buildJsonObject { put("count", 0) })
                    } else {
                        val resultText = buildString {
                            appendLine("**Drive Files (${files.size})**\n")
                            files.forEach { file ->
                                appendLine("**${file.name}** ${if (file.isFolder) "[Folder]" else ""}")
                                appendLine("  ID: ${file.id}")
                                appendLine("  Type: ${file.mimeType}")
                                file.size?.let { appendLine("  Size: $it bytes") }
                                file.modifiedTime?.let { appendLine("  Modified: $it") }
                                file.webViewLink?.let { appendLine("  Link: $it") }
                                appendLine()
                            }
                        }
                        ToolExecutionResult.Success(resultText, buildJsonObject {
                            put("count", files.size)
                            put("files", JsonArray(files.map { f -> buildJsonObject {
                                put("id", f.id)
                                put("name", f.name)
                                put("isFolder", f.isFolder)
                            }}))
                        })
                    }
                },
                onFailure = { error ->
                    ToolExecutionResult.Error("Failed to list files: ${error.message}")
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
                put("folderId", buildJsonObject { put("type", "STRING"); put("description", "Folder ID (null for root)") })
                put("query", buildJsonObject { put("type", "STRING"); put("description", "Additional query filter") })
                put("maxResults", buildJsonObject { put("type", "INTEGER"); put("description", "Max results (default 20)"); put("default", 20) })
            })
        })
        else -> ToolSpecification(id, description, buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("folderId", buildJsonObject { put("type", "string"); put("description", "Folder ID (null for root)") })
                put("query", buildJsonObject { put("type", "string"); put("description", "Additional query filter") })
                put("maxResults", buildJsonObject { put("type", "integer"); put("description", "Max results (default 20)"); put("default", 20) })
            })
        })
    }
}
