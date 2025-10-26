package com.example.ApI.tools.github

import com.example.ApI.data.model.GitHubCreatePullRequestRequest
import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for creating a pull request in a GitHub repository
 */
class GitHubCreatePRTool(
    private val apiService: GitHubApiService,
    private val accessToken: String
) : Tool {

    override val id: String = "github_create_pr"
    override val name: String = "Create GitHub Pull Request"
    override val description: String = "Create a pull request in a GitHub repository. Provide the repository owner, repository name, title, optional description, head branch (source), and base branch (target)."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val owner = parameters["owner"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'owner' is required")
            val repo = parameters["repo"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'repo' is required")
            val title = parameters["title"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'title' is required")
            val body = parameters["body"]?.jsonPrimitive?.contentOrNull // Optional description
            val head = parameters["head"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'head' (source branch) is required")
            val base = parameters["base"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'base' (target branch) is required")
            val draft = parameters["draft"]?.jsonPrimitive?.booleanOrNull ?: false

            // Create request
            val request = GitHubCreatePullRequestRequest(
                title = title,
                body = body,
                head = head,
                base = base,
                draft = draft
            )

            // Call GitHub API
            val result = apiService.createPullRequest(accessToken, owner, repo, request)

            result.fold(
                onSuccess = { pr ->
                    val details = buildJsonObject {
                        put("owner", owner)
                        put("repo", repo)
                        put("pr_number", pr.number)
                        put("title", pr.title)
                        put("state", pr.state)
                        put("draft", pr.draft)
                        put("head", pr.head.ref)
                        put("base", pr.base.ref)
                        put("url", pr.htmlUrl)
                        put("created_at", pr.createdAt)
                    }
                    ToolExecutionResult.Success(
                        result = buildString {
                            appendLine("Successfully created pull request #${pr.number} in $owner/$repo")
                            appendLine("Title: ${pr.title}")
                            appendLine("From: ${pr.head.ref} → ${pr.base.ref}")
                            if (pr.draft) appendLine("Status: Draft")
                            appendLine("URL: ${pr.htmlUrl}")
                        },
                        details = details
                    )
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to create pull request: ${error.message}",
                        buildJsonObject {
                            put("owner", owner)
                            put("repo", repo)
                            put("head", head)
                            put("base", base)
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
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "The title of the pull request")
                    })
                    put("body", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional: the description/body of the pull request (supports markdown)")
                    })
                    put("head", buildJsonObject {
                        put("type", "string")
                        put("description", "The name of the branch where your changes are (source branch). For cross-repository PRs use 'username:branch'")
                    })
                    put("base", buildJsonObject {
                        put("type", "string")
                        put("description", "The name of the branch you want to merge into (target branch, usually 'main' or 'develop')")
                    })
                    put("draft", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Optional: whether to create as a draft PR (default: false)")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("owner"),
                    JsonPrimitive("repo"),
                    JsonPrimitive("title"),
                    JsonPrimitive("head"),
                    JsonPrimitive("base")
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
                    put("title", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The title of the pull request")
                    })
                    put("body", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Optional: the description/body of the pull request (supports markdown)")
                    })
                    put("head", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The name of the branch where your changes are (source branch). For cross-repository PRs use 'username:branch'")
                    })
                    put("base", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The name of the branch you want to merge into (target branch, usually 'main' or 'develop')")
                    })
                    put("draft", buildJsonObject {
                        put("type", "BOOLEAN")
                        put("description", "Optional: whether to create as a draft PR (default: false)")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("owner"),
                    JsonPrimitive("repo"),
                    JsonPrimitive("title"),
                    JsonPrimitive("head"),
                    JsonPrimitive("base")
                )))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
