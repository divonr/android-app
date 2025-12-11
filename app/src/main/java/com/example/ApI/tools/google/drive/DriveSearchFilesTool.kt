package com.example.ApI.tools.google.drive

import com.example.ApI.data.network.GoogleDriveApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

class DriveSearchFilesTool(
    private val apiService: GoogleDriveApiService,
    private val googleEmail: String
) : Tool {

    override val id = "drive_search_files"
    override val name = "Search Drive Files"
    override val description = "Search files in Google Drive by name or content. Authenticated as '$googleEmail'. Provide search query and optionally max results (default 20, max 100)."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            val query = parameters["query"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'query' is required")
            val maxResults = parameters["maxResults"]?.jsonPrimitive?.intOrNull ?: 20

            val result = apiService.searchFiles(query, maxResults.coerceIn(1, 100))

            result.fold(
                onSuccess = { files ->
                    if (files.isEmpty()) {
                        ToolExecutionResult.Success("No files found matching query: \"$query\"", buildJsonObject { put("count", 0) })
                    } else {
                        val resultText = buildString {
                            appendLine("**Found ${files.size} file(s) matching \"$query\"**\n")
                            files.forEach { file ->
                                appendLine("**${file.name}** ${if (file.isFolder) "[Folder]" else ""}")
                                appendLine("  ID: ${file.id}")
                                file.webViewLink?.let { appendLine("  Link: $it") }
                                appendLine()
                            }
                        }
                        ToolExecutionResult.Success(resultText, buildJsonObject {
                            put("count", files.size)
                            put("query", query)
                        })
                    }
                },
                onFailure = { error ->
                    ToolExecutionResult.Error("Failed to search: ${error.message}", buildJsonObject { put("query", query) })
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
                put("query", buildJsonObject { put("type", "STRING"); put("description", "Search query") })
                put("maxResults", buildJsonObject { put("type", "INTEGER"); put("description", "Max results (default 20)"); put("default", 20) })
            })
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
        })
        else -> ToolSpecification(id, description, buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject { put("type", "string"); put("description", "Search query") })
                put("maxResults", buildJsonObject { put("type", "integer"); put("description", "Max results (default 20)"); put("default", 20) })
            })
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
        })
    }
}
