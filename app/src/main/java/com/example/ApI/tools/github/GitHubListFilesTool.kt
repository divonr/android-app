package com.example.ApI.tools.github

import com.example.ApI.data.model.GitHubContent
import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for listing files and directories in a GitHub repository
 */
class GitHubListFilesTool(
    private val apiService: GitHubApiService,
    private val accessToken: String,
    private val githubUsername: String
) : Tool {

    override val id: String = "github_list_files"
    override val name: String = "List GitHub Files"
    override val description: String = "List files and directories in a GitHub repository path. You are authenticated as GitHub user '$githubUsername'. For your repositories, use '$githubUsername' as the owner. Provide the repository owner, repository name, and optional path (defaults to root)."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val owner = parameters["owner"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'owner' is required")
            val repo = parameters["repo"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'repo' is required")
            val path = parameters["path"]?.jsonPrimitive?.contentOrNull ?: "" // Default to root
            val ref = parameters["ref"]?.jsonPrimitive?.contentOrNull // Optional branch/tag/commit

            // Call GitHub API
            val result = apiService.getContents(accessToken, owner, repo, path, ref)

            result.fold(
                onSuccess = { content ->
                    when (content) {
                        is List<*> -> {
                            // Directory listing
                            val items = (content as List<GitHubContent>)

                            if (items.isEmpty()) {
                                return ToolExecutionResult.Success(
                                    result = "Directory is empty.",
                                    details = buildJsonObject {
                                        put("owner", owner)
                                        put("repo", repo)
                                        put("path", path)
                                        put("item_count", 0)
                                    }
                                )
                            }

                            val formattedList = buildString {
                                appendLine("Contents of $owner/$repo${if (path.isNotEmpty()) "/$path" else ""}:")
                                appendLine()

                                val directories = items.filter { it.type == "dir" }.sortedBy { it.name }
                                val files = items.filter { it.type == "file" }.sortedBy { it.name }

                                if (directories.isNotEmpty()) {
                                    appendLine("Directories:")
                                    directories.forEach { dir ->
                                        appendLine("  📁 ${dir.name}/")
                                    }
                                    appendLine()
                                }

                                if (files.isNotEmpty()) {
                                    appendLine("Files:")
                                    files.forEach { file ->
                                        val sizeKB = file.size / 1024.0
                                        val sizeStr = if (sizeKB < 1) "${file.size} B" else "%.1f KB".format(sizeKB)
                                        appendLine("  📄 ${file.name} ($sizeStr)")
                                    }
                                }
                            }

                            val details = buildJsonObject {
                                put("owner", owner)
                                put("repo", repo)
                                put("path", path)
                                put("item_count", items.size)
                                put("directory_count", items.count { it.type == "dir" })
                                put("file_count", items.count { it.type == "file" })
                                put("items", JsonArray(items.map { item ->
                                    buildJsonObject {
                                        put("name", item.name)
                                        put("type", item.type)
                                        put("size", item.size)
                                        put("path", item.path)
                                    }
                                }))
                                ref?.let { put("ref", it) }
                            }

                            ToolExecutionResult.Success(result = formattedList, details = details)
                        }
                        is GitHubContent -> {
                            // Single file (path specified a file, not directory)
                            ToolExecutionResult.Error(
                                "The specified path is a file, not a directory. Use 'github_read_file' to read file contents.",
                                buildJsonObject {
                                    put("is_file", true)
                                    put("file_name", content.name)
                                    put("file_size", content.size)
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
                        "Failed to list files: ${error.message}",
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
                        put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
                        put("description", "Optional: the directory path to list (defaults to root directory)")
                    })
                    put("ref", buildJsonObject {
                        put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
                        put("description", "Optional: branch name, tag, or commit SHA (defaults to repository's default branch)")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("owner"), JsonPrimitive("repo"), JsonPrimitive("path"), JsonPrimitive("ref"))))
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
                        put("description", "Optional: the directory path to list (defaults to root directory)")
                    })
                    put("ref", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Optional: branch name, tag, or commit SHA (defaults to repository's default branch)")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("owner"), JsonPrimitive("repo"))))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
