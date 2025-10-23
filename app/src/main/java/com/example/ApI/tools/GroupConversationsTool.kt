package com.example.ApI.tools

import com.example.ApI.data.model.Chat
import com.example.ApI.data.repository.DataRepository
import kotlinx.serialization.json.*

/**
 * Tool that provides access to other conversations in the same group.
 * This tool is dynamically created when a conversation is part of a group.
 *
 * @param repository The data repository to fetch chat history
 * @param username The current username
 * @param currentChatId The ID of the current chat (to exclude it from results)
 * @param groupId The ID of the group (for filtering chats)
 * @param groupName The name of the group (for description)
 */
class GroupConversationsTool(
    private val repository: DataRepository,
    private val username: String,
    private val currentChatId: String,
    private val groupId: String,
    private val groupName: String
) : Tool {
    override val id: String = "get_current_group_conversations"
    override val name: String = "Get Group Conversations"
    override val description: String =
        "If you see this tool, it means that the conversation you are having with the user is part of a conversation group that are related to each other. The group name is \"$groupName\". This tool will return the content of the other conversations in the group, in order to get context."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Get current user's chat history
            val chatHistory = repository.loadChatHistory(username)

            // Find all chats in the same group, excluding the current chat
            val groupChats = chatHistory.chat_history.filter { chat ->
                chat.group == groupId && chat.chat_id != currentChatId
            }

            if (groupChats.isEmpty()) {
                return ToolExecutionResult.Success(
                    "No other conversations found in the group \"$groupName\".",
                    buildJsonObject {
                        put("group_name", groupName)
                        put("conversation_count", 0)
                        put("conversations", JsonArray(emptyList()))
                    }
                )
            }

            // Format the conversations
            val formattedConversations = groupChats.map { chat ->
                buildJsonObject {
                    put("chat_id", chat.chat_id)
                    put("title", chat.preview_name)
                    put("message_count", chat.messages.size)
                    put("messages", JsonArray(
                        chat.messages.map { message ->
                            buildJsonObject {
                                put("role", message.role)
                                put("content", message.text)
                                message.datetime?.let { put("datetime", it) }
                                message.model?.let { put("model", it) }
                                if (message.attachments.isNotEmpty()) {
                                    put("has_attachments", true)
                                    put("attachment_count", message.attachments.size)
                                }
                            }
                        }
                    ))
                    // Include system prompt if present
                    if (chat.systemPrompt.isNotBlank()) {
                        put("system_prompt", chat.systemPrompt)
                    }
                }
            }

            // Create result with full conversation content
            val result = buildString {
                appendLine("Found ${groupChats.size} other conversation(s) in the group \"$groupName\":")
                appendLine()

                groupChats.forEachIndexed { index, chat ->
                    appendLine("=".repeat(80))
                    appendLine("Conversation ${index + 1}: ${chat.preview_name}")
                    appendLine("Chat ID: ${chat.chat_id}")
                    if (chat.systemPrompt.isNotBlank()) {
                        appendLine("System Prompt: ${chat.systemPrompt}")
                    }
                    appendLine("-".repeat(80))
                    appendLine()

                    // Include all messages
                    chat.messages.forEach { message ->
                        appendLine("[${message.role.uppercase()}]")
                        if (message.datetime != null) {
                            appendLine("Time: ${message.datetime}")
                        }
                        if (message.model != null) {
                            appendLine("Model: ${message.model}")
                        }
                        if (message.attachments.isNotEmpty()) {
                            appendLine("Attachments: ${message.attachments.size} file(s)")
                            message.attachments.forEach { attachment ->
                                appendLine("  - ${attachment.file_name} (${attachment.mime_type})")
                            }
                        }
                        appendLine("Content: ${message.text}")
                        appendLine()
                    }
                }
            }.trim()

            val details = buildJsonObject {
                put("group_name", groupName)
                put("conversation_count", groupChats.size)
                put("conversations", JsonArray(formattedConversations))
            }

            ToolExecutionResult.Success(result, details)
        } catch (e: Exception) {
            ToolExecutionResult.Error(
                "Failed to retrieve group conversations: ${e.message}",
                buildJsonObject {
                    put("error_type", e::class.simpleName ?: "Unknown")
                    put("group_name", groupName)
                }
            )
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
                    // This tool doesn't require parameters from the model
                    // All context is provided by the app
                })
                put("required", JsonArray(emptyList()))
            }
        )
    }

    private fun getPoeSpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    // This tool doesn't require parameters from the model
                })
                put("required", JsonArray(emptyList()))
            }
        )
    }

    private fun getGoogleSpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    // This tool doesn't require parameters from the model
                })
                put("required", JsonArray(emptyList()))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    // This tool doesn't require parameters from the model
                })
                put("required", JsonArray(emptyList()))
            }
        )
    }
}
