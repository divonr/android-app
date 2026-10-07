package com.example.ApI.data.sync.merge

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.MessageNode
import com.example.ApI.data.model.MessageVariant

/**
 * Converts a legacy (linear `messages`, no `messageNodes`) chat into the branching structure.
 *
 * Mirrors [com.example.ApI.data.repository.MessageBranchingManager.migrateChatToBranchingStructure]
 * exactly (each user message opens a node with one variant, every following
 * assistant/tool_call/tool_response/system message goes into that variant's responses,
 * messages before the first user message and unknown roles are dropped, `messages` itself
 * is left untouched), except that all ids are deterministic: two devices converting the same
 * legacy chat produce identical node/variant ids, so their trees can be merged by id.
 */
object LegacyChatConverter {

    /** Deterministic id for a message that has a blank id, from its position in the chat. */
    fun fallbackMessageId(chatId: String, index: Int): String =
        MergeSupport.nameUuid("$chatId:msg:$index")

    fun nodeIdFor(chatId: String, index: Int, userMessageId: String): String =
        MergeSupport.nameUuid("$chatId:node:$index:$userMessageId")

    fun variantIdFor(chatId: String, index: Int, userMessageId: String): String =
        MergeSupport.nameUuid("$chatId:variant:$index:$userMessageId")

    fun toBranching(chat: Chat): Chat {
        if (chat.hasBranchingStructure) return chat
        if (chat.messages.isEmpty()) return chat

        val nodes = mutableListOf<MessageNode>()
        val variantPath = mutableListOf<String>()
        var currentNodeId: String? = null
        var currentVariant: MessageVariant? = null
        var pendingResponses = mutableListOf<Message>()

        // Replace the variant inside the node that holds it
        fun storeVariant(nodeId: String, variant: MessageVariant) {
            val nodeIndex = nodes.indexOfFirst { it.nodeId == nodeId }
            if (nodeIndex >= 0) {
                val node = nodes[nodeIndex]
                nodes[nodeIndex] = node.copy(
                    variants = node.variants.map { if (it.variantId == variant.variantId) variant else it }
                )
            }
        }

        for ((index, message) in chat.messages.withIndex()) {
            val messageId = message.id.ifBlank { fallbackMessageId(chat.chat_id, index) }
            when (message.role) {
                "user" -> {
                    val newNodeId = nodeIdFor(chat.chat_id, index, messageId)
                    // Close the previous variant, linking it to the new node
                    val previous = currentVariant
                    val previousNodeId = currentNodeId
                    if (previous != null && previousNodeId != null) {
                        storeVariant(
                            previousNodeId,
                            previous.copy(responses = pendingResponses.toList(), childNodeId = newNodeId)
                        )
                    }
                    currentNodeId = newNodeId

                    val variantId = variantIdFor(chat.chat_id, index, messageId)
                    val variant = MessageVariant(
                        variantId = variantId,
                        userMessage = message.copy(id = messageId, nodeId = newNodeId, variantId = variantId),
                        responses = emptyList()
                    )
                    currentVariant = variant
                    variantPath.add(variantId)
                    pendingResponses = mutableListOf()

                    val existingIndex = nodes.indexOfFirst { it.nodeId == newNodeId }
                    if (existingIndex >= 0) {
                        val existing = nodes[existingIndex]
                        nodes[existingIndex] = existing.copy(variants = existing.variants + variant)
                    } else {
                        val parentNodeId = if (nodes.isEmpty()) null else nodes.lastOrNull()?.nodeId
                        nodes.add(MessageNode(nodeId = newNodeId, parentNodeId = parentNodeId, variants = listOf(variant)))
                    }
                }
                "assistant", "tool_call", "tool_response", "system" -> {
                    val nodeId = currentNodeId
                    val variant = currentVariant
                    if (nodeId != null && variant != null) {
                        pendingResponses.add(
                            message.copy(id = messageId, nodeId = nodeId, variantId = variant.variantId)
                        )
                    }
                }
            }
        }

        // Save final variant's responses
        val lastVariant = currentVariant
        val lastNodeId = currentNodeId
        if (lastVariant != null && lastNodeId != null && pendingResponses.isNotEmpty()) {
            storeVariant(lastNodeId, lastVariant.copy(responses = pendingResponses.toList()))
        }

        return chat.copy(messageNodes = nodes, currentVariantPath = variantPath)
    }
}
