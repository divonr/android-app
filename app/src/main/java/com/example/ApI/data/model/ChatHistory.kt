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
    val group: String? = null,
    val is_branch: Boolean = false
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
data class MessageBranch(
    val branch_chat_id: String,
    val is_active: Boolean
)

@Serializable
data class Message(
    val role: String, // "user", "assistant", "system", "tool_call", "tool_response"
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val model: String? = null, // Model name that generated this response (for assistant messages)
    val datetime: String? = null, // ISO 8601 format timestamp when message was sent/received
    val toolCall: ToolCallInfo? = null, // Tool call information if this is a tool call message
    val toolCallId: String? = null, // For providers like OpenAI that require linking tool call results
    val toolResponseCallId: String? = null, // For tool_response messages - links back to the tool_call
    val toolResponseOutput: String? = null, // For tool_response messages - the actual tool output
    val branches: List<MessageBranch> = emptyList() // List of branch chat IDs created from editing/resending this message
) {
    // Convenience property
    val content: String get() = text

    // Check if this is a tool call message
    val isToolCall: Boolean get() = role == "tool_call" || toolCall != null

    // Check if this is a tool response message
    val isToolResponse: Boolean get() = role == "tool_response" || toolResponseCallId != null

    // Check if this message has branches
    val hasBranches: Boolean get() = branches.isNotEmpty()

    // Get the active branch
    val activeBranch: MessageBranch? get() = branches.find { it.is_active }
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
