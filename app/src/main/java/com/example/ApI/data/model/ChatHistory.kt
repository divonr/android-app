package com.example.ApI.data.model

import kotlinx.serialization.Serializable
import com.example.ApI.tools.ToolCallInfo
import java.util.UUID

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
data class MessageBranch(
    val branchId: String = UUID.randomUUID().toString(),
    val content: String,                              // The text content of this message in this branch
    val attachments: List<Attachment> = emptyList(),  // Attachments for this variant
    val continuation: List<Message> = emptyList(),    // Following messages in this branch
    val createdAt: String,                            // ISO 8601 timestamp
    val reason: String                                // "original", "edit", "resend"
)

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),    // Unique message identifier
    val role: String, // "user", "assistant", "system", "tool_call", "tool_response"
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val model: String? = null, // Model name that generated this response (for assistant messages)
    val datetime: String? = null, // ISO 8601 format timestamp when message was sent/received
    val toolCall: ToolCallInfo? = null, // Tool call information if this is a tool call message
    val toolCallId: String? = null, // For providers like OpenAI that require linking tool call results
    val toolResponseCallId: String? = null, // For tool_response messages - links back to the tool_call
    val toolResponseOutput: String? = null, // For tool_response messages - the actual tool output
    // Branching support
    val branches: List<MessageBranch>? = null,        // null = no branches, List = has alternatives
    val activeBranchIndex: Int = 0                    // Which branch is currently active
) {
    // Get the active content (from branch if exists, otherwise from message)
    val activeContent: String get() = branches?.getOrNull(activeBranchIndex)?.content ?: text

    // Get the active attachments
    val activeAttachments: List<Attachment> get() = branches?.getOrNull(activeBranchIndex)?.attachments ?: attachments

    // Get continuation messages from active branch (empty if no branches)
    val activeContinuation: List<Message> get() = branches?.getOrNull(activeBranchIndex)?.continuation ?: emptyList()

    // Check if this message has branches
    val hasBranches: Boolean get() = branches != null && branches.isNotEmpty()

    // Get total number of branches
    val branchCount: Int get() = branches?.size ?: 0

    // Convenience property
    val content: String get() = text

    // Check if this is a tool call message
    val isToolCall: Boolean get() = role == "tool_call" || toolCall != null

    // Check if this is a tool response message
    val isToolResponse: Boolean get() = role == "tool_response" || toolResponseCallId != null
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
