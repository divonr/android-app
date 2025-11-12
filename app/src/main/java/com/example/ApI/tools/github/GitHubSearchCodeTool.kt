package com.example.ApI.tools.github

import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for searching code across GitHub repositories
 */
class GitHubSearchCodeTool(
    private val apiService: GitHubApiService,
    private val accessToken: String,
    private val githubUsername: String
) : Tool {

    override val id: String = "github_search_code"
    override val name: String = "Search GitHub Code"
    override val description: String = "Search for code across GitHub repositories. You are authenticated as GitHub user '$githubUsername'. You can search by keywords, filter by language, repository, path, and more. Use GitHub search syntax (e.g., 'addClass in:file language:js repo:$githubUsername/myrepo')"

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val query = parameters["query"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'query' is required")
            val perPage = parameters["per_page"]?.jsonPrimitive?.intOrNull ?: 30
            val page = parameters["page"]?.jsonPrimitive?.intOrNull ?: 1

            // Call GitHub API
            val result = apiService.searchCode(accessToken, query, perPage, page)

            result.fold(
                onSuccess = { searchResult ->
                    if (searchResult.items.isEmpty()) {
                        return ToolExecutionResult.Success(
                            result = "No code matches found for query: \"$query\"",
                            details = buildJsonObject {
                                put("query", query)
                                put("total_count", searchResult.totalCount)
                                put("incomplete_results", searchResult.incompleteResults)
                            }
                        )
                    }

                    val formattedResults = buildString {
                        appendLine("Found ${searchResult.totalCount} code matches for: \"$query\"")
                        if (searchResult.incompleteResults) {
                            appendLine("(Results may be incomplete)")
                        }
                        appendLine()
                        appendLine("Showing ${searchResult.items.size} results:")
                        appendLine()

                        searchResult.items.forEachIndexed { index, item ->
                            appendLine("${index + 1}. ${item.repository.fullName}/${item.path}")
                            appendLine("   File: ${item.name}")
                            appendLine("   Score: ${item.score}")
                            appendLine("   URL: ${item.htmlUrl}")
                            appendLine()
                        }

                        if (searchResult.totalCount > searchResult.items.size) {
                            appendLine("Showing ${searchResult.items.size} of ${searchResult.totalCount} total results.")
                            appendLine("Use 'page' parameter to see more results.")
                        }
                    }

                    val details = buildJsonObject {
                        put("query", query)
                        put("total_count", searchResult.totalCount)
                        put("showing_count", searchResult.items.size)
                        put("page", page)
                        put("per_page", perPage)
                        put("incomplete_results", searchResult.incompleteResults)
                        put("results", JsonArray(searchResult.items.map { item ->
                            buildJsonObject {
                                put("repository", item.repository.fullName)
                                put("path", item.path)
                                put("file_name", item.name)
                                put("url", item.htmlUrl)
                                put("score", item.score)
                            }
                        }))
                    }

                    ToolExecutionResult.Success(result = formattedResults, details = details)
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to search code: ${error.message}",
                        buildJsonObject {
                            put("query", query)
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
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Search query using GitHub search syntax. Examples: 'addClass in:file language:js', 'repo:owner/name extension:kt function', 'user:username language:python'. You can filter by: repo:, user:, org:, language:, extension:, path:, filename:, size:>1000, etc.")
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
                put("required", JsonArray(listOf(JsonPrimitive("query"), JsonPrimitive("per_page"), JsonPrimitive("page"))))
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
                    put("query", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Search query using GitHub search syntax. Examples: 'addClass in:file language:js', 'repo:owner/name extension:kt function', 'user:username language:python'. You can filter by: repo:, user:, org:, language:, extension:, path:, filename:, size:>1000, etc.")
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
                put("required", JsonArray(listOf(JsonPrimitive("query"))))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
