package com.example.ApI.tools.google.gmail

import com.example.ApI.data.network.GmailApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for searching emails in Gmail
 */
class GmailSearchEmailsTool(
    private val apiService: GmailApiService,
    private val googleEmail: String
) : Tool {

    override val id: String = "gmail_search_emails"
    override val name: String = "Search Gmail Emails"
    override val description: String = "Search for emails in Gmail using Gmail search operators. You are authenticated as '$googleEmail'. Provide a search query using Gmail search syntax (e.g., 'from:example@gmail.com', 'subject:meeting', 'is:unread', 'after:2024/01/01'). Optionally specify maximum number of results (default 20, max 100)."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val query = parameters["query"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'query' is required")
            val maxResults = parameters["maxResults"]?.jsonPrimitive?.intOrNull ?: 20

            // Validate maxResults
            val validMaxResults = maxResults.coerceIn(1, 100)

            // Call Gmail API
            val result = apiService.searchEmails(query, validMaxResults)

            result.fold(
                onSuccess = { messages ->
                    if (messages.isEmpty()) {
                        ToolExecutionResult.Success(
                            result = "No emails found matching query: \"$query\"",
                            details = buildJsonObject {
                                put("query", query)
                                put("count", 0)
                            }
                        )
                    } else {
                        val resultText = buildString {
                            appendLine("**Gmail Search Results**")
                            appendLine()
                            appendLine("Found ${messages.size} email(s) matching query: \"$query\"")
                            appendLine()

                            messages.forEachIndexed { index, message ->
                                appendLine("**${index + 1}. Message ID:** ${message.id}")
                                message.subject?.let { appendLine("   **Subject:** $it") }
                                message.from?.let { appendLine("   **From:** $it") }
                                message.date?.let { appendLine("   **Date:** $it") }
                                message.snippet?.let {
                                    val snippet = it.take(100) + if (it.length > 100) "..." else ""
                                    appendLine("   **Snippet:** $snippet")
                                }
                                appendLine("   **Labels:** ${message.labels.joinToString(", ")}")
                                appendLine("   **Status:** ${if (message.isRead) "Read" else "Unread"}")
                                appendLine()
                            }

                            appendLine()
                            appendLine("Use `gmail_read_email` tool with a message ID to read the full email content.")
                        }

                        val details = buildJsonObject {
                            put("query", query)
                            put("count", messages.size)
                            put("maxResults", validMaxResults)
                            put("messages", JsonArray(messages.map { message ->
                                buildJsonObject {
                                    put("id", message.id)
                                    put("subject", message.subject ?: "")
                                    put("from", message.from ?: "")
                                    put("isRead", message.isRead)
                                }
                            }))
                        }

                        ToolExecutionResult.Success(
                            result = resultText,
                            details = details
                        )
                    }
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to search emails: ${error.message}",
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
                        put("description", "Gmail search query using search operators (e.g., 'from:user@example.com', 'subject:important', 'is:unread', 'newer_than:7d')")
                    })
                    put("maxResults", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of results to return (default 20, max 100)")
                        put("default", 20)
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("query"))))
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
                    put("query", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Gmail search query using search operators (e.g., 'from:user@example.com', 'subject:important', 'is:unread', 'newer_than:7d')")
                    })
                    put("maxResults", buildJsonObject {
                        put("type", "INTEGER")
                        put("description", "Maximum number of results to return (default 20, max 100)")
                        put("default", 20)
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
