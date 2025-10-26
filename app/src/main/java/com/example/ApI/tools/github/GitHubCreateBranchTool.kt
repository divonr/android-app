package com.example.ApI.tools.github

import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for creating a new branch in a GitHub repository
 */
class GitHubCreateBranchTool(
    private val apiService: GitHubApiService,
    private val accessToken: String,
    private val githubUsername: String
) : Tool {

    override val id: String = "github_create_branch"
    override val name: String = "Create GitHub Branch"
    override val description: String = "Create a new branch in a GitHub repository. You are authenticated as GitHub user '$githubUsername'. For your repositories, use '$githubUsername' as the owner. Provide the repository owner, repository name, new branch name, and source branch/commit SHA to branch from."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val owner = parameters["owner"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'owner' is required")
            val repo = parameters["repo"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'repo' is required")
            val branchName = parameters["branch_name"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'branch_name' is required")
            val fromRef = parameters["from_ref"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'from_ref' (source branch or commit SHA) is required")

            // Get the SHA of the source branch/commit
            val fromBranchResult = apiService.getBranch(accessToken, owner, repo, fromRef)

            fromBranchResult.fold(
                onSuccess = { fromBranch ->
                    val fromSha = fromBranch.commit.sha

                    // Create new branch
                    val result = apiService.createBranch(accessToken, owner, repo, branchName, fromSha)

                    result.fold(
                        onSuccess = { reference ->
                            val details = buildJsonObject {
                                put("owner", owner)
                                put("repo", repo)
                                put("branch_name", branchName)
                                put("from_ref", fromRef)
                                put("from_sha", fromSha)
                                put("ref", reference.ref)
                                put("url", reference.url)
                            }
                            ToolExecutionResult.Success(
                                result = "Successfully created branch '$branchName' in $owner/$repo from $fromRef (SHA: $fromSha)",
                                details = details
                            )
                        },
                        onFailure = { error ->
                            ToolExecutionResult.Error(
                                "Failed to create branch: ${error.message}",
                                buildJsonObject {
                                    put("owner", owner)
                                    put("repo", repo)
                                    put("branch_name", branchName)
                                    put("from_ref", fromRef)
                                }
                            )
                        }
                    )
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to get source branch '$fromRef': ${error.message}",
                        buildJsonObject {
                            put("owner", owner)
                            put("repo", repo)
                            put("from_ref", fromRef)
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
                    put("branch_name", buildJsonObject {
                        put("type", "string")
                        put("description", "The name for the new branch")
                    })
                    put("from_ref", buildJsonObject {
                        put("type", "string")
                        put("description", "The source branch name or commit SHA to create the branch from (e.g., 'main', 'develop', or a commit SHA)")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("owner"),
                    JsonPrimitive("repo"),
                    JsonPrimitive("branch_name"),
                    JsonPrimitive("from_ref")
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
                    put("branch_name", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The name for the new branch")
                    })
                    put("from_ref", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The source branch name or commit SHA to create the branch from (e.g., 'main', 'develop', or a commit SHA)")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("owner"),
                    JsonPrimitive("repo"),
                    JsonPrimitive("branch_name"),
                    JsonPrimitive("from_ref")
                )))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
