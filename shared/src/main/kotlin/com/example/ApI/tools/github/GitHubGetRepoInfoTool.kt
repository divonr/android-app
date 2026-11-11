package com.example.ApI.tools.github

import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for getting repository information from GitHub
 */
class GitHubGetRepoInfoTool(
    private val apiService: GitHubApiService,
    private val accessToken: String,
    private val githubUsername: String
) : Tool {

    override val id: String = "github_get_repo_info"
    override val name: String = "Get GitHub Repository Info"
    override val description: String = "Get detailed information about a GitHub repository including description, language, stars, forks, open issues, and default branch. You are authenticated as GitHub user '$githubUsername'. For your repositories, use '$githubUsername' as the owner."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val owner = parameters["owner"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'owner' is required")
            val repo = parameters["repo"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'repo' is required")

            // Call GitHub API
            val result = apiService.getRepository(accessToken, owner, repo)

            result.fold(
                onSuccess = { repository ->
                    val formattedInfo = buildString {
                        appendLine("Repository: ${repository.fullName}")
                        appendLine("URL: ${repository.htmlUrl}")
                        appendLine()
                        repository.description?.let {
                            appendLine("Description: $it")
                            appendLine()
                        }
                        appendLine("Details:")
                        appendLine("  - Owner: ${repository.owner.login} (${repository.owner.type})")
                        appendLine("  - Visibility: ${if (repository.private) "Private" else "Public"}")
                        repository.language?.let { appendLine("  - Primary Language: $it") }
                        appendLine("  - Default Branch: ${repository.defaultBranch}")
                        appendLine("  - Stars: ${repository.stargazersCount}")
                        appendLine("  - Forks: ${repository.forksCount}")
                        appendLine("  - Watchers: ${repository.watchersCount}")
                        appendLine("  - Open Issues: ${repository.openIssuesCount}")
                        appendLine("  - Size: ${repository.size} KB")
                        appendLine("  - Is Fork: ${if (repository.fork) "Yes" else "No"}")
                        appendLine()
                        appendLine("Dates:")
                        appendLine("  - Created: ${repository.createdAt}")
                        appendLine("  - Updated: ${repository.updatedAt}")
                        repository.pushedAt?.let { appendLine("  - Last Push: $it") }

                        repository.permissions?.let { perms ->
                            appendLine()
                            appendLine("Your Permissions:")
                            appendLine("  - Admin: ${perms.admin}")
                            appendLine("  - Push: ${perms.push}")
                            appendLine("  - Pull: ${perms.pull}")
                        }
                    }

                    val details = buildJsonObject {
                        put("full_name", repository.fullName)
                        put("owner", repository.owner.login)
                        put("name", repository.name)
                        put("private", repository.private)
                        repository.description?.let { put("description", it) }
                        repository.language?.let { put("language", it) }
                        put("default_branch", repository.defaultBranch)
                        put("stars", repository.stargazersCount)
                        put("forks", repository.forksCount)
                        put("watchers", repository.watchersCount)
                        put("open_issues", repository.openIssuesCount)
                        put("size_kb", repository.size)
                        put("is_fork", repository.fork)
                        put("url", repository.htmlUrl)
                        put("created_at", repository.createdAt)
                        put("updated_at", repository.updatedAt)
                        repository.pushedAt?.let { put("pushed_at", it) }
                    }

                    ToolExecutionResult.Success(result = formattedInfo, details = details)
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to get repository information: ${error.message}",
                        buildJsonObject {
                            put("owner", owner)
                            put("repo", repo)
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
                })
                put("required", JsonArray(listOf(JsonPrimitive("owner"), JsonPrimitive("repo"))))
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
                })
                put("required", JsonArray(listOf(JsonPrimitive("owner"), JsonPrimitive("repo"))))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
