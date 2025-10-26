package com.example.ApI.tools.github

import com.example.ApI.data.model.GitHubContent
import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for reading file contents from a GitHub repository
 */
class GitHubReadFileTool(
    private val apiService: GitHubApiService,
    private val accessToken: String,
    private val githubUsername: String
) : Tool {

    override val id: String = "github_read_file"
    override val name: String = "Read GitHub File"
    override val description: String = "Read the contents of a file from a GitHub repository. You are authenticated as GitHub user '$githubUsername'. For your repositories, use '$githubUsername' as the owner. Provide the repository owner, repository name, and file path."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val owner = parameters["owner"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'owner' is required")
            val repo = parameters["repo"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'repo' is required")
            val path = parameters["path"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'path' is required")
            val ref = parameters["ref"]?.jsonPrimitive?.contentOrNull // Optional branch/tag/commit

            // Call GitHub API
            val result = apiService.getContents(accessToken, owner, repo, path, ref)

            result.fold(
                onSuccess = { content ->
                    when (content) {
                        is GitHubContent -> {
                            // Single file
                            val decodedContent = content.getDecodedContent()
                            if (decodedContent != null) {
                                val details = buildJsonObject {
                                    put("owner", owner)
                                    put("repo", repo)
                                    put("path", path)
                                    put("size", content.size)
                                    put("sha", content.sha)
                                    put("url", content.htmlUrl)
                                    ref?.let { put("ref", it) }
                                }
                                ToolExecutionResult.Success(
                                    result = "File contents:\n\n```\n$decodedContent\n```",
                                    details = details
                                )
                            } else {
                                ToolExecutionResult.Error("Failed to decode file contents. File might be binary or too large.")
                            }
                        }
                        is List<*> -> {
                            // Directory listing
                            ToolExecutionResult.Error(
                                "The specified path is a directory, not a file. Use 'github_list_files' to list directory contents.",
                                buildJsonObject {
                                    put("is_directory", true)
                                    put("item_count", (content as List<*>).size)
                                }
                            )
                        }
                        else -> {
                            ToolExecutionResult.Error("Unexpected response type from GitHub API")
                        }
                    }
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to read file: ${error.message}",
                        buildJsonObject {
                            put("owner", owner)
                            put("repo", repo)
                            put("path", path)
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
                        put("description", "The path to the file in the repository (e.g., 'src/Main.kt', 'README.md')")
                    })
                    put("ref", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional: branch name, tag, or commit SHA (defaults to repository's default branch)")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("owner"), JsonPrimitive("repo"), JsonPrimitive("path"))))
            }
        )
    }

    private fun getPoeSpecification(): ToolSpecification {
        return getOpenAISpecification() // Poe uses similar format
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
                        put("description", "The path to the file in the repository (e.g., 'src/Main.kt', 'README.md')")
                    })
                    put("ref", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Optional: branch name, tag, or commit SHA (defaults to repository's default branch)")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("owner"), JsonPrimitive("repo"), JsonPrimitive("path"))))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
