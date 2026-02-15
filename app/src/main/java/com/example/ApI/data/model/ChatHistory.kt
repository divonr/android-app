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
    val messages: List<Message>,  // Current path messages (for backward compatibility and display)
    val systemPrompt: String = "",
    val group: String? = null,
    // New branching structure
    val messageNodes: List<MessageNode> = emptyList(),  // All nodes in the conversation tree
    val currentVariantPath: List<String> = emptyList(),  // List of variantIds representing current path
    val shareLink: String = "",  // Full share link URL (including #key=...)
    val shareId: String = ""  // Share UUID on the server
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
    
    /**
     * Check if this chat has been migrated to the new branching structure
     */
    val hasBranchingStructure: Boolean get() = messageNodes.isNotEmpty()
}

/**
 * Represents a node in the conversation tree.
 * Each node contains one or more variants of a user message and its responses.
 */
@Serializable
data class MessageNode(
    val nodeId: String = UUID.randomUUID().toString(),
    val parentNodeId: String? = null,  // null for the first node in conversation
    val variants: List<MessageVariant> = emptyList()
) {
    /**
     * Get variant by index
     */
    fun getVariant(index: Int): MessageVariant? = variants.getOrNull(index)
    
    /**
     * Get variant by ID
     */
    fun getVariantById(variantId: String): MessageVariant? = variants.find { it.variantId == variantId }
    
    /**
     * Get variant index by ID
     */
    fun getVariantIndex(variantId: String): Int = variants.indexOfFirst { it.variantId == variantId }
}

/**
 * Represents a single variant (branch) at a node.
 * Contains the user message and all responses (including tool calls).
 */
@Serializable
data class MessageVariant(
    val variantId: String = UUID.randomUUID().toString(),
    val userMessage: Message,  // The user's message for this variant
    val responses: List<Message> = emptyList(),  // Assistant responses, tool calls, etc.
    val childNodeId: String? = null  // Points to the next node if conversation continues
) {
    /**
     * Check if this variant has any responses
     */
    val hasResponses: Boolean get() = responses.isNotEmpty()
    
    /**
     * Check if this variant has a continuation (child node)
     */
    val hasContinuation: Boolean get() = childNodeId != null
    
    /**
     * Get all messages in this variant (user + responses)
     */
    val allMessages: List<Message> get() = listOf(userMessage) + responses
}

@Serializable
enum class ThoughtsStatus {
    NONE,        // Model did not think (no thoughts area shown)
    PRESENT,     // Thoughts content is available (show expandable with content)
    UNAVAILABLE  // Model thought but content not available (show with timer only)
}

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),  // Unique identifier for the message
    val role: String, // "user", "assistant", "system", "tool_call", "tool_response"
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val model: String? = null, // Model name that generated this response (for assistant messages)
    val datetime: String? = null, // ISO 8601 format timestamp when message was sent/received
    val toolCall: ToolCallInfo? = null, // Tool call information if this is a tool call message
    val toolCallId: String? = null, // For providers like OpenAI that require linking tool call results
    val toolResponseCallId: String? = null, // For tool_response messages - links back to the tool_call
    val toolResponseOutput: String? = null, // For tool_response messages - the actual tool output
    val nodeId: String? = null,  // Reference to the node this message belongs to (for branching)
    val variantId: String? = null,  // Reference to the variant this message belongs to (for branching)
    val thoughts: String? = null,  // Model's thinking/reasoning process (for models that support it)
    val thinkingDurationSeconds: Float? = null,  // Duration of thinking phase in seconds
    val thoughtsStatus: ThoughtsStatus = ThoughtsStatus.NONE,  // Status of thoughts for UI display
    val thoughtsSignature: String? = null  // Cryptographic signature for thinking blocks (Anthropic)
) {
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
