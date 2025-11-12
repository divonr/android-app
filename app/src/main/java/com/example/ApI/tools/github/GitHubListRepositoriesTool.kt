package com.example.ApI.tools.github

import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for listing repositories accessible to the authenticated user
 */
class GitHubListRepositoriesTool(
    private val apiService: GitHubApiService,
    private val accessToken: String,
    private val githubUsername: String
) : Tool {

    override val id: String = "github_list_repositories"
    override val name: String = "List GitHub Repositories"
    override val description: String = "List all repositories accessible to the authenticated user '$githubUsername'. You can filter by visibility (all, public, private) and sort by various criteria. This returns YOUR repositories."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val visibility = parameters["visibility"]?.jsonPrimitive?.contentOrNull ?: "all"
            val sort = parameters["sort"]?.jsonPrimitive?.contentOrNull ?: "updated"
            val perPage = parameters["per_page"]?.jsonPrimitive?.intOrNull ?: 30
            val page = parameters["page"]?.jsonPrimitive?.intOrNull ?: 1

            // Call GitHub API
            val result = apiService.listRepositories(accessToken, visibility, sort, perPage, page)

            result.fold(
                onSuccess = { repositories ->
                    if (repositories.isEmpty()) {
                        return ToolExecutionResult.Success(
                            result = "No repositories found.",
                            details = buildJsonObject {
                                put("count", 0)
                                put("visibility", visibility)
                                put("sort", sort)
                            }
                        )
                    }

                    val formattedList = buildString {
                        appendLine("Found ${repositories.size} repositories:")
                        appendLine()

                        repositories.forEachIndexed { index, repo ->
                            appendLine("${index + 1}. ${repo.fullName}")
                            appendLine("   ${if (repo.private) "🔒 Private" else "🌐 Public"}")
                            repo.description?.let { appendLine("   Description: $it") }
                            repo.language?.let { appendLine("   Language: $it") }
                            appendLine("   ⭐ ${repo.stargazersCount} stars | 🍴 ${repo.forksCount} forks | 📝 ${repo.openIssuesCount} issues")
                            appendLine("   Default branch: ${repo.defaultBranch}")
                            appendLine("   URL: ${repo.htmlUrl}")
                            appendLine()
                        }
                    }

                    val details = buildJsonObject {
                        put("count", repositories.size)
                        put("visibility", visibility)
                        put("sort", sort)
                        put("page", page)
                        put("per_page", perPage)
                        put("repositories", JsonArray(repositories.map { repo ->
                            buildJsonObject {
                                put("full_name", repo.fullName)
                                put("name", repo.name)
                                put("owner", repo.owner.login)
                                put("private", repo.private)
                                repo.description?.let { put("description", it) }
                                repo.language?.let { put("language", it) }
                                put("default_branch", repo.defaultBranch)
                                put("stars", repo.stargazersCount)
                                put("forks", repo.forksCount)
                                put("url", repo.htmlUrl)
                            }
                        }))
                    }

                    ToolExecutionResult.Success(result = formattedList, details = details)
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to list repositories: ${error.message}",
                        buildJsonObject {
                            put("visibility", visibility)
                            put("sort", sort)
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
                    put("visibility", buildJsonObject {
                        put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
                        put("enum", JsonArray(listOf(JsonPrimitive("all"), JsonPrimitive("public"), JsonPrimitive("private"))))
                        put("description", "Filter by visibility: 'all', 'public', or 'private' (default: 'all')")
                    })
                    put("sort", buildJsonObject {
                        put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
                        put("enum", JsonArray(listOf(
                            JsonPrimitive("created"),
                            JsonPrimitive("updated"),
                            JsonPrimitive("pushed"),
                            JsonPrimitive("full_name")
                        )))
                        put("description", "Sort repositories by: 'created', 'updated', 'pushed', or 'full_name' (default: 'updated')")
                    })
                    put("per_page", buildJsonObject {
                        put("type", JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null"))))
                        put("description", "Number of results per page (1-100, default: 30)")
                    })
                    put("page", buildJsonObject {
                        put("type", JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null"))))
                        put("description", "Page number for pagination (default: 1)")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("visibility"),
                    JsonPrimitive("sort"),
                    JsonPrimitive("per_page"),
                    JsonPrimitive("page")
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
                    put("visibility", buildJsonObject {
                        put("type", "STRING")
                        put("enum", JsonArray(listOf(JsonPrimitive("all"), JsonPrimitive("public"), JsonPrimitive("private"))))
                        put("description", "Filter by visibility: 'all', 'public', or 'private' (default: 'all')")
                    })
                    put("sort", buildJsonObject {
                        put("type", "STRING")
                        put("enum", JsonArray(listOf(
                            JsonPrimitive("created"),
                            JsonPrimitive("updated"),
                            JsonPrimitive("pushed"),
                            JsonPrimitive("full_name")
                        )))
                        put("description", "Sort repositories by: 'created', 'updated', 'pushed', or 'full_name' (default: 'updated')")
                    })
                    put("per_page", buildJsonObject {
                        put("type", "INTEGER")
                        put("description", "Number of results per page (1-100, default: 30)")
                    })
                    put("page", buildJsonObject {
                        put("type", "INTEGER")
                        put("description", "Page number for pagination (default: 1)")
                    })
                })
                put("required", JsonArray(emptyList()))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
