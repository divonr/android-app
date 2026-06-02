package com.example.ApI.tools

import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.desktop.DesktopRepository
import kotlinx.serialization.json.*

/**
 * Tool that provides access to other conversations in the same group.
 * Desktop version: accepts DesktopRepository instead of Android DataRepository.
 */
class GroupConversationsTool(
    private val repository: DesktopRepository,
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
            val chatHistory = repository.loadChatHistory(username)
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
                    if (chat.systemPrompt.isNotBlank()) put("system_prompt", chat.systemPrompt)
                }
            }

            val result = buildString {
                appendLine("Found ${groupChats.size} other conversation(s) in the group \"$groupName\":")
                appendLine()
                groupChats.forEachIndexed { index, chat ->
                    appendLine("=".repeat(80))
                    appendLine("Conversation ${index + 1}: ${chat.preview_name}")
                    appendLine("Chat ID: ${chat.chat_id}")
                    if (chat.systemPrompt.isNotBlank()) appendLine("System Prompt: ${chat.systemPrompt}")
                    appendLine("-".repeat(80))
                    appendLine()
                    chat.messages.forEach { message ->
                        appendLine("[${message.role.uppercase()}]")
                        message.datetime?.let { appendLine("Time: $it") }
                        message.model?.let { appendLine("Model: $it") }
                        if (message.attachments.isNotEmpty()) {
                            appendLine("Attachments: ${message.attachments.size} file(s)")
                            message.attachments.forEach { appendLine("  - ${it.file_name} (${it.mime_type})") }
                        }
                        appendLine("Content: ${message.text}")
                        appendLine()
                    }
                }
            }.trim()

            ToolExecutionResult.Success(result, buildJsonObject {
                put("group_name", groupName)
                put("conversation_count", groupChats.size)
                put("conversations", JsonArray(formattedConversations))
            })
        } catch (e: Exception) {
            ToolExecutionResult.Error("Failed to retrieve group conversations: ${e.message}", buildJsonObject {
                put("error_type", e::class.simpleName ?: "Unknown")
                put("group_name", groupName)
            })
        }
    }

    override fun getSpecification(provider: String): ToolSpecification {
        val params = buildJsonObject {
            put("type", if (provider == "google") "OBJECT" else "object")
            put("properties", buildJsonObject { })
            put("required", JsonArray(emptyList()))
        }
        return ToolSpecification(name = id, description = description, parameters = params)
    }
}
