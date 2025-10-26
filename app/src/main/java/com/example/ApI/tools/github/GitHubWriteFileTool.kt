package com.example.ApI.tools.github

import android.util.Base64
import com.example.ApI.data.model.GitHubContent
import com.example.ApI.data.model.GitHubCreateUpdateFileRequest
import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for creating or updating a file in a GitHub repository
 */
class GitHubWriteFileTool(
    private val apiService: GitHubApiService,
    private val accessToken: String
) : Tool {

    override val id: String = "github_write_file"
    override val name: String = "Write GitHub File"
    override val description: String = "Create or update a file in a GitHub repository. Provide the repository owner, repository name, file path, content, commit message, and branch. If updating an existing file, the current SHA is automatically retrieved."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val owner = parameters["owner"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'owner' is required")
            val repo = parameters["repo"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'repo' is required")
            val path = parameters["path"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'path' is required")
            val content = parameters["content"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'content' is required")
            val message = parameters["message"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'message' (commit message) is required")
            val branch = parameters["branch"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'branch' is required")

            // Check if file exists to get SHA for update
            val existingFileResult = apiService.getContents(accessToken, owner, repo, path, branch)
            val sha = existingFileResult.fold(
                onSuccess = { existingContent ->
                    when (existingContent) {
                        is GitHubContent -> existingContent.sha
                        else -> null // New file
                    }
                },
                onFailure = { null } // File doesn't exist (404), treat as new file
            )

            // Encode content to base64
            val encodedContent = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)

            // Create request
            val request = GitHubCreateUpdateFileRequest(
                message = message,
                content = encodedContent,
                branch = branch,
                sha = sha // null for new files, required for updates
            )

            // Call GitHub API
            val result = apiService.createOrUpdateFile(accessToken, owner, repo, path, request)

            result.fold(
                onSuccess = { response ->
                    val isUpdate = sha != null
                    val action = if (isUpdate) "updated" else "created"
                    val details = buildJsonObject {
                        put("owner", owner)
                        put("repo", repo)
                        put("path", path)
                        put("branch", branch)
                        put("action", action)
                        put("commit_sha", response.commit.tree.sha)
                        put("commit_message", message)
                    }
                    ToolExecutionResult.Success(
                        result = "File successfully $action at $owner/$repo/$path on branch '$branch'\nCommit message: $message",
                        details = details
                    )
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to write file: ${error.message}",
                        buildJsonObject {
                            put("owner", owner)
                            put("repo", repo)
                            put("path", path)
                            put("branch", branch)
                        }
                    )
                }
            )
        } catch (e: Exception) {
            ToolExecutionResult.Error("Error executing tool: ${e.message}")
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
                    put("owner", buildJsonObject {
                        put("type", "string")
                        put("description", "The repository owner (username or organization)")
                    })
                    put("repo", buildJsonObject {
                        put("type", "string")
                        put("description", "The repository name")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "The path where the file should be created/updated (e.g., 'src/Main.kt')")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The complete content of the file")
                    })
                    put("message", buildJsonObject {
                        put("type", "string")
                        put("description", "The commit message describing the changes")
                    })
                    put("branch", buildJsonObject {
                        put("type", "string")
                        put("description", "The branch where the file should be created/updated")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("owner"),
                    JsonPrimitive("repo"),
                    JsonPrimitive("path"),
                    JsonPrimitive("content"),
                    JsonPrimitive("message"),
                    JsonPrimitive("branch")
                )))
            }
        )
    }

    private fun getPoeSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }

    private fun getGoogleSpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    put("owner", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The repository owner (username or organization)")
                    })
                    put("repo", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The repository name")
                    })
                    put("path", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The path where the file should be created/updated (e.g., 'src/Main.kt')")
                    })
                    put("content", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The complete content of the file")
                    })
                    put("message", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The commit message describing the changes")
                    })
                    put("branch", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The branch where the file should be created/updated")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("owner"),
                    JsonPrimitive("repo"),
                    JsonPrimitive("path"),
                    JsonPrimitive("content"),
                    JsonPrimitive("message"),
                    JsonPrimitive("branch")
                )))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
