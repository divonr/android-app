package com.example.ApI.tools.google.gmail

import com.example.ApI.data.network.GmailApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for sending an email via Gmail
 */
class GmailSendEmailTool(
    private val apiService: GmailApiService,
    private val googleEmail: String
) : Tool {

    override val id: String = "gmail_send_email"
    override val name: String = "Send Gmail Email"
    override val description: String = "Send an email via Gmail. You are authenticated as '$googleEmail'. Provide the recipient email address, subject, and email body. The email will be sent from your Gmail account."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val to = parameters["to"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'to' is required")
            val subject = parameters["subject"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'subject' is required")
            val body = parameters["body"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'body' is required")

            // Call Gmail API
            val result = apiService.sendEmail(to, subject, body)

            result.fold(
                onSuccess = { message ->
                    val resultText = buildString {
                        appendLine("**Email Sent Successfully**")
                        appendLine()
                        appendLine("**To:** $to")
                        appendLine("**Subject:** $subject")
                        appendLine("**Message ID:** ${message.id}")
                        appendLine()
                        appendLine("The email has been sent from your Gmail account ($googleEmail).")
                    }

                    val details = buildJsonObject {
                        put("messageId", message.id)
                        put("threadId", message.threadId)
                        put("to", to)
                        put("subject", subject)
                        put("from", googleEmail)
                    }

                    ToolExecutionResult.Success(
                        result = resultText,
                        details = details
                    )
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to send email: ${error.message}",
                        buildJsonObject {
                            put("to", to)
                            put("subject", subject)
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
                    put("to", buildJsonObject {
                        put("type", "string")
                        put("description", "Recipient email address")
                    })
                    put("subject", buildJsonObject {
                        put("type", "string")
                        put("description", "Email subject")
                    })
                    put("body", buildJsonObject {
                        put("type", "string")
                        put("description", "Email body (plain text or HTML)")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("to"),
                    JsonPrimitive("subject"),
                    JsonPrimitive("body")
                )))
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
                    put("to", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Recipient email address")
                    })
                    put("subject", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Email subject")
                    })
                    put("body", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Email body (plain text or HTML)")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("to"),
                    JsonPrimitive("subject"),
                    JsonPrimitive("body")
                )))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
