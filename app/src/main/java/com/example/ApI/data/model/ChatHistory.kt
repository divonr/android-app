package com.example.ApI.data.model

import kotlinx.serialization.Serializable
import com.example.ApI.tools.ToolCallInfo

@Serializable
data class UserChatHistory(
    val user_name: String,
    val chat_history: List<Chat>,
    val groups: List<ChatGroup> = emptyList()
)

@Serializable
data class Chat(
    val chat_id: String,
    val preview_name: String,
    val messages: List<Message>,
    val systemPrompt: String = "",
    val group: String? = null
) {
    // Convenience properties
    val title: String get() = preview_name
    // Keep timestamp for compatibility, but do not fake current time when missing
    val timestamp: Long? get() = messages.lastOrNull()?.datetime?.let {
        try {
            java.time.Instant.parse(it).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
    val model: String get() = messages.lastOrNull { it.role == "assistant" }?.model ?: "gpt-4o"
    val id: String get() = chat_id
}

@Serializable
data class Message(
    val role: String, // "user", "assistant", "system", "tool_call"
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val model: String? = null, // Model name that generated this response (for assistant messages)
    val datetime: String? = null, // ISO 8601 format timestamp when message was sent/received
    val toolCall: ToolCallInfo? = null // Tool call information if this is a tool call message
) {
    // Convenience property
    val content: String get() = text
    
    // Check if this is a tool call message
    val isToolCall: Boolean get() = role == "tool_call" || toolCall != null
}

@Serializable
data class Attachment(
    val local_file_path: String? = null,
    val file_name: String,
    val mime_type: String,
    val file_OPENAI_id: String? = null,
    val file_POE_url: String? = null,
    val file_GOOGLE_uri: String? = null
)

@Serializable
data class ChatGroup(
    val group_id: String,
    val group_name: String,
    val system_prompt: String? = null,
    val group_attachments: List<Attachment> = emptyList(),
    val is_project: Boolean = false
)
