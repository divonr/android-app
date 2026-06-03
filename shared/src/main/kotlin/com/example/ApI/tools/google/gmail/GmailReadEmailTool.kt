package com.example.ApI.tools.google.gmail

import com.example.ApI.data.network.GmailApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for reading an email from Gmail by message ID
 */
class GmailReadEmailTool(
    private val apiService: GmailApiService,
    private val googleEmail: String
) : Tool {

    override val id: String = "gmail_read_email"
    override val name: String = "Read Gmail Email"
    override val description: String = "Read the contents of a specific email from Gmail. You are authenticated as '$googleEmail'. Provide the message ID to read the email details including subject, sender, recipients, date, and body."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract message ID parameter
            val messageId = parameters["messageId"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'messageId' is required")

            // Call Gmail API
            val result = apiService.getEmail(messageId)

            result.fold(
                onSuccess = { message ->
                    val messageText = buildString {
                        appendLine("**Email Message**")
                        appendLine()
                        message.subject?.let { appendLine("**Subject:** $it") }
                        message.from?.let { appendLine("**From:** $it") }
                        appendLine("**To:** ${message.to.joinToString(", ")}")
                        message.date?.let { appendLine("**Date:** $it") }
                        appendLine("**Labels:** ${message.labels.joinToString(", ")}")
                        appendLine("**Status:** ${if (message.isRead) "Read" else "Unread"}")
                        appendLine()
                        appendLine("**Body:**")
                        appendLine(message.body ?: message.snippet ?: "(No content)")
                    }

                    val details = buildJsonObject {
                        put("messageId", message.id)
                        put("threadId", message.threadId)
                        put("subject", message.subject ?: "")
                        put("from", message.from ?: "")
                        put("to", JsonArray(message.to.map { JsonPrimitive(it) }))
                        put("isRead", message.isRead)
                    }

                    ToolExecutionResult.Success(
                        result = messageText,
                        details = details
                    )
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to read email: ${error.message}",
                        buildJsonObject {
                            put("messageId", messageId)
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
                    put("messageId", buildJsonObject {
                        put("type", "string")
                        put("description", "The Gmail message ID of the email to read")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("messageId"))))
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
                    put("messageId", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The Gmail message ID of the email to read")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("messageId"))))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
