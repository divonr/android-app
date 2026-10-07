package com.example.ApI.data.repository

import com.example.ApI.data.model.*
import com.example.ApI.data.sync.merge.LegacyChatConverter
import com.example.ApI.util.AppLogger
import java.util.UUID

/**
 * Manages message branching and variant operations for conversations.
 * Handles migration from linear to branching structure, creating branches,
 * switching variants, and deleting messages.
 */
class MessageBranchingManager(
    private val chatHistoryManager: ChatHistoryManager
) {
    companion object {
        private const val TAG = "MessageBranchingManager"
    }

    /**
     * Migrate a chat from linear structure to branching structure.
     * Each user message becomes a node with a single variant.
     *
     * Deterministic (see [LegacyChatConverter]): every device migrating the same legacy chat
     * produces the same node/variant ids, so sync can merge the resulting trees by id.
     */
    fun migrateChatToBranchingStructure(chat: Chat): Chat = LegacyChatConverter.toBranching(chat)

    /**
     * Ensure chat has branching structure, migrating if necessary.
     */
    fun ensureBranchingStructure(username: String, chatId: String): Chat? =
        modifyBranchingChat(username, chatId) { chat -> null to chat }

    /**
     * Run [block] on chat [chatId], migrated to the branching structure first, as one
     * load-modify-save of the chat history under its file lock (so nothing written in between,
     * e.g. by streaming or sync, is lost). [block] returns the updated chat (null: keep the chat
     * as is, persisting only the migration if one happened) and the operation's result.
     * Returns null when the chat does not exist.
     */
    private fun <R> modifyBranchingChat(
        username: String,
        chatId: String,
        block: (Chat) -> Pair<Chat?, R>
    ): R? = chatHistoryManager.modifyChatHistory(username) { history ->
        val original = history.chat_history.find { it.chat_id == chatId }
            ?: return@modifyChatHistory history to null
        val chat = migrateChatToBranchingStructure(original)
        val (updated, result) = block(chat)
        replaceChat(history, chatId, original, updated ?: chat) to result
    }

    private fun replaceChat(history: UserChatHistory, chatId: String, original: Chat, updated: Chat): UserChatHistory =
        if (updated == original) {
            history
        } else {
            history.copy(chat_history = history.chat_history.map { if (it.chat_id == chatId) updated else it })
        }

    /**
     * Create a new branch (variant) at a specific node.
     * Used when user edits or resends a message.
     *
     * @param username The current user
     * @param chatId The chat ID
     * @param nodeId The node where to create the branch
     * @param newUserMessage The new/edited user message
     * @return Pair of updated Chat and the new variantId, or null if failed
     */
    fun createBranch(
        username: String,
        chatId: String,
        nodeId: String,
        newUserMessage: Message
    ): Pair<Chat, String>? = modifyBranchingChat(username, chatId) { chat ->
        val nodeIndex = chat.messageNodes.indexOfFirst { it.nodeId == nodeId }
        if (nodeIndex == -1) return@modifyBranchingChat null to null

        val node = chat.messageNodes[nodeIndex]
        val newVariantId = UUID.randomUUID().toString()

        // Create new variant with the new user message
        val messageWithRefs = newUserMessage.copy(
            id = if (newUserMessage.id.isBlank()) UUID.randomUUID().toString() else newUserMessage.id,
            nodeId = nodeId,
            variantId = newVariantId
        )
        val newVariant = MessageVariant(
            variantId = newVariantId,
            userMessage = messageWithRefs,
            responses = emptyList()
        )

        // Update node with new variant
        val updatedNode = node.copy(variants = node.variants + newVariant)
        val updatedNodes = chat.messageNodes.toMutableList()
        updatedNodes[nodeIndex] = updatedNode

        // Update variant path to use the new variant from this point
        val nodePositionInPath = chat.currentVariantPath.indexOfFirst { variantId ->
            node.variants.any { it.variantId == variantId }
        }
        val newPath = if (nodePositionInPath >= 0) {
            chat.currentVariantPath.take(nodePositionInPath) + newVariantId
        } else {
            chat.currentVariantPath + newVariantId
        }

        // Rebuild messages list for the new path
        val newMessages = buildMessagesFromPath(updatedNodes, newPath)

        val updatedChat = chat.copy(
            messageNodes = updatedNodes,
            currentVariantPath = newPath,
            messages = newMessages
        )

        updatedChat to Pair(updatedChat, newVariantId)
    }

    /**
     * Add a response message to the current variant of a node.
     * Used when receiving assistant responses.
     *
     * @param targetVariantId The variant the response belongs to (the variant of the user
     *        message the request was sent for). When given, the response is appended to that
     *        variant wherever it is in the tree, even if the current path moved elsewhere in the
     *        meantime (variant switch, sync merge). If it no longer exists, falls back to the
     *        last variant of the current path. Null: the last variant of the current path.
     * @param expectedTailId The id of the message the caller expects at the end of the target
     *        variant (its last response, or its user message when it has none), i.e. the message
     *        the response follows. A sync merge that found this device's responses diverging from
     *        another device's keeps the other device's content under the variant id and moves this
     *        device's content to a fork in the same node (ChatHistoryMerger); when the target's
     *        last response is not [expectedTailId], the response goes to the sibling variant whose
     *        responses end with it (the fork), so it still follows this device's content. When the
     *        target is gone, any variant of the chat whose responses end with it is used.
     */
    fun addResponseToCurrentVariant(
        username: String,
        chatId: String,
        response: Message,
        targetVariantId: String? = null,
        expectedTailId: String? = null
    ): Chat? = chatHistoryManager.modifyChatHistory(username) { history ->
        val original = history.chat_history.find { it.chat_id == chatId }
            ?: return@modifyChatHistory history to null
        val chat = migrateChatToBranchingStructure(original)

        fun locate(variantId: String): Pair<Int, Int>? {
            for ((nodeIndex, node) in chat.messageNodes.withIndex()) {
                val variantIndex = node.variants.indexOfFirst { it.variantId == variantId }
                if (variantIndex >= 0) return nodeIndex to variantIndex
            }
            return null
        }

        var target = targetVariantId?.let { locate(it) }
        if (expectedTailId != null) {
            target = resolveAnchoredTarget(chat, target, expectedTailId)
        }
        if (targetVariantId != null && target == null) {
            AppLogger.w("[$TAG] addResponseToCurrentVariant: variant $targetVariantId not found in chat $chatId, using the current path")
        }

        if (target == null) {
            // Fallback if no variant path yet (shouldn't happen but just in case)
            if (chat.currentVariantPath.isEmpty()) {
                val updatedChat = chat.copy(messages = chat.messages + response)
                val otherChats = history.chat_history.filter { it.chat_id != chatId }
                return@modifyChatHistory history.copy(chat_history = otherChats + updatedChat) to updatedChat
            }
            target = locate(chat.currentVariantPath.last())
        }

        // Variant not found: persist the migration (if any) but drop the response, as before
        val (targetNodeIndex, targetVariantIndex) = target
            ?: return@modifyChatHistory replaceChat(history, chatId, original, chat) to null

        val node = chat.messageNodes[targetNodeIndex]
        val variant = node.variants[targetVariantIndex]

        // Add response with references
        val responseWithRefs = response.copy(
            id = if (response.id.isBlank()) UUID.randomUUID().toString() else response.id,
            nodeId = node.nodeId,
            variantId = variant.variantId
        )
        val updatedVariant = variant.copy(responses = variant.responses + responseWithRefs)

        // Update node
        val updatedVariants = node.variants.toMutableList()
        updatedVariants[targetVariantIndex] = updatedVariant
        val updatedNode = node.copy(variants = updatedVariants)

        // Update nodes list
        val updatedNodes = chat.messageNodes.toMutableList()
        updatedNodes[targetNodeIndex] = updatedNode

        // Rebuild messages list
        val newMessages = buildMessagesFromPath(updatedNodes, chat.currentVariantPath)

        val updatedChat = chat.copy(
            messageNodes = updatedNodes,
            messages = newMessages
        )

        replaceChat(history, chatId, original, updatedChat) to updatedChat
    }

    /** The id of the message a new response of [variant] follows. */
    private fun tailId(variant: MessageVariant): String = variant.responses.lastOrNull()?.id ?: variant.userMessage.id

    /**
     * Where a response that must follow message [expectedTailId] goes, given the pinned
     * [target] (node index, variant index; null: the pinned variant is gone): the target if its
     * tail is that message, else a variant whose RESPONSES end with it — a sibling in the
     * target's node (a merge fork of this device's content), or any variant of the chat when
     * the target is gone. Only response ids are matched for other variants: sibling variants
     * share user message ids by design (an edit keeps the original's id). No match → [target].
     */
    private fun resolveAnchoredTarget(chat: Chat, target: Pair<Int, Int>?, expectedTailId: String): Pair<Int, Int>? {
        fun endsWithResponse(v: MessageVariant) = v.responses.lastOrNull()?.id == expectedTailId
        if (target != null) {
            val (nodeIndex, variantIndex) = target
            val node = chat.messageNodes[nodeIndex]
            if (tailId(node.variants[variantIndex]) == expectedTailId) return target
            val sibling = node.variants.indexOfFirst(::endsWithResponse)
            if (sibling >= 0) {
                AppLogger.i("[$TAG] addResponseToCurrentVariant: variant ${node.variants[variantIndex].variantId} no longer ends with " +
                    "$expectedTailId (sync merge); using its sibling ${node.variants[sibling].variantId}")
                return nodeIndex to sibling
            }
            return target
        }
        for ((nodeIndex, node) in chat.messageNodes.withIndex()) {
            val variantIndex = node.variants.indexOfFirst(::endsWithResponse)
            if (variantIndex >= 0) return nodeIndex to variantIndex
        }
        return null
    }

    /**
     * Save a response of a streamed request at its [anchor] (see [ReplyAnchor]) and move the
     * anchor to the saved message, so the request's next response (after a tool call, say)
     * follows it in the same variant — the fork, if a merge moved this device's content there.
     */
    fun addAnchoredResponse(username: String, chatId: String, response: Message, anchor: ReplyAnchor): Chat? {
        val withId = if (response.id.isBlank()) response.copy(id = UUID.randomUUID().toString()) else response
        val (variantId, tail) = anchor.current()
        val chat = addResponseToCurrentVariant(username, chatId, withId, variantId, tail) ?: return null
        val savedIn = chat.messageNodes.asSequence().flatMap { it.variants.asSequence() }
            .firstOrNull { v -> v.responses.any { it.id == withId.id } }
        anchor.moveTo(savedIn?.variantId ?: variantId, withId.id)
        return chat
    }

    /**
     * Switch to a different variant at a specific node.
     *
     * @param username The current user
     * @param chatId The chat ID
     * @param nodeId The node where to switch variants
     * @param variantIndex The index of the variant to switch to (0-based)
     * @return Updated chat, or null if failed
     */
    fun switchVariant(
        username: String,
        chatId: String,
        nodeId: String,
        variantIndex: Int
    ): Chat? = modifyBranchingChat(username, chatId) { chat ->
        val node = chat.messageNodes.find { it.nodeId == nodeId } ?: return@modifyBranchingChat null to null
        val variant = node.getVariant(variantIndex) ?: return@modifyBranchingChat null to null

        // Find position in path where this node's variant is
        val pathIndex = chat.currentVariantPath.indexOfFirst { variantId ->
            node.variants.any { it.variantId == variantId }
        }

        if (pathIndex == -1) return@modifyBranchingChat null to null

        // Build new path: keep everything before this node, then add the new variant
        val newPath = chat.currentVariantPath.take(pathIndex).toMutableList()
        newPath.add(variant.variantId)

        // Follow the chain of child nodes from this variant
        var currentVariant = variant
        while (currentVariant.childNodeId != null) {
            val childNode = chat.messageNodes.find { it.nodeId == currentVariant.childNodeId }
            if (childNode != null && childNode.variants.isNotEmpty()) {
                // First, try to find a variant in the child that has further continuations
                // that match our old path
                var bestVariant: MessageVariant? = null

                // Check if we had a variant from this child in our old path
                val oldVariantInChild = chat.currentVariantPath.find { variantId ->
                    childNode.variants.any { it.variantId == variantId }
                }

                if (oldVariantInChild != null) {
                    bestVariant = childNode.getVariantById(oldVariantInChild)
                }

                // If no old variant found, use the first one
                val childVariant = bestVariant ?: childNode.variants.first()
                newPath.add(childVariant.variantId)
                currentVariant = childVariant
            } else {
                break
            }
        }

        // Rebuild messages list for new path
        val newMessages = buildMessagesFromPath(chat.messageNodes, newPath)

        val updatedChat = chat.copy(
            currentVariantPath = newPath,
            messages = newMessages
        )

        updatedChat to updatedChat
    }

    /**
     * Get branch info for a specific node.
     * Returns information about available variants for UI display.
     */
    fun getBranchInfo(chat: Chat, nodeId: String): BranchInfo? {
        if (!chat.hasBranchingStructure) return null

        val node = chat.messageNodes.find { it.nodeId == nodeId } ?: return null
        if (node.variants.size <= 1) return null  // No branching if only one variant

        // Find current variant for this node from path
        val currentVariantId = chat.currentVariantPath.find { variantId ->
            node.variants.any { it.variantId == variantId }
        } ?: return null

        val currentIndex = node.getVariantIndex(currentVariantId)
        if (currentIndex == -1) return null

        return BranchInfo(
            nodeId = nodeId,
            currentVariantIndex = currentIndex,
            totalVariants = node.variants.size,
            currentVariantId = currentVariantId
        )
    }

    /**
     * Get branch info for a message by its ID.
     */
    fun getBranchInfoForMessage(chat: Chat, messageId: String): BranchInfo? {
        if (!chat.hasBranchingStructure) return null

        // Find the message and its node
        for (node in chat.messageNodes) {
            for (variant in node.variants) {
                if (variant.userMessage.id == messageId) {
                    return getBranchInfo(chat, node.nodeId)
                }
            }
        }
        return null
    }

    /**
     * Build a flat messages list from the branching structure following a specific path.
     */
    private fun buildMessagesFromPath(
        nodes: List<MessageNode>,
        variantPath: List<String>
    ): List<Message> {
        val messages = mutableListOf<Message>()

        for (variantId in variantPath) {
            // Find the node containing this variant
            for (node in nodes) {
                val variant = node.getVariantById(variantId)
                if (variant != null) {
                    messages.add(variant.userMessage)
                    messages.addAll(variant.responses)
                    break
                }
            }
        }

        return messages
    }

    /**
     * Create a new node and variant for a new user message.
     * Used when continuing a conversation normally.
     */
    fun addUserMessageAsNewNode(
        username: String,
        chatId: String,
        userMessage: Message
    ): Chat? = modifyBranchingChat(username, chatId) { migratedChat ->
        var chat = migratedChat

        val newNodeId = UUID.randomUUID().toString()
        val newVariantId = UUID.randomUUID().toString()

        // If this is the first message (no nodes yet), create initial structure
        if (chat.messageNodes.isEmpty()) {
            val messageWithRefs = userMessage.copy(
                id = if (userMessage.id.isBlank()) UUID.randomUUID().toString() else userMessage.id,
                nodeId = newNodeId,
                variantId = newVariantId
            )

            val newVariant = MessageVariant(
                variantId = newVariantId,
                userMessage = messageWithRefs,
                responses = emptyList()
            )

            val newNode = MessageNode(
                nodeId = newNodeId,
                parentNodeId = null,
                variants = listOf(newVariant)
            )

            val updatedChat = chat.copy(
                messageNodes = listOf(newNode),
                currentVariantPath = listOf(newVariantId),
                messages = listOf(messageWithRefs)
            )

            return@modifyBranchingChat updatedChat to updatedChat
        }

        // Find the last node in current path - we need to find where to attach the new message
        var lastVariantInPath: MessageVariant? = null
        var lastNodeInPath: MessageNode? = null

        if (chat.currentVariantPath.isNotEmpty()) {
            val lastVariantId = chat.currentVariantPath.last()

            // Find the node and variant
            for (node in chat.messageNodes) {
                val variant = node.getVariantById(lastVariantId)
                if (variant != null) {
                    lastVariantInPath = variant
                    lastNodeInPath = node
                    break
                }
            }
        }

        // If the last variant already has a child, we need to follow the chain to the actual end
        // and add the message there as a new variant (fork point)
        if (lastVariantInPath?.childNodeId != null) {
            // Follow the chain to find the actual last node
            var currentVariant = lastVariantInPath
            var actualLastNode: MessageNode? = lastNodeInPath
            var actualLastVariant: MessageVariant? = lastVariantInPath

            while (currentVariant?.childNodeId != null) {
                val childNode = chat.messageNodes.find { it.nodeId == currentVariant?.childNodeId }
                if (childNode != null && childNode.variants.isNotEmpty()) {
                    // Find the variant in path, or use first
                    val variantInPath = chat.currentVariantPath.find { variantId ->
                        childNode.variants.any { it.variantId == variantId }
                    }
                    val childVariant = if (variantInPath != null) {
                        childNode.getVariantById(variantInPath)
                    } else {
                        childNode.variants.first()
                    }
                    if (childVariant != null) {
                        actualLastNode = childNode
                        actualLastVariant = childVariant
                        currentVariant = childVariant
                    } else {
                        break
                    }
                } else {
                    break
                }
            }

            // Now actualLastVariant is the real last variant, update it
            lastVariantInPath = actualLastVariant
            lastNodeInPath = actualLastNode
        }

        // Determine parent node ID
        val parentNodeId = lastNodeInPath?.nodeId

        // Update the last variant to point to the new node (if it doesn't have a child yet)
        if (lastVariantInPath != null && lastVariantInPath.childNodeId == null) {
            val updatedNodes = chat.messageNodes.map { node ->
                val variantIndex = node.variants.indexOfFirst { it.variantId == lastVariantInPath.variantId }
                if (variantIndex >= 0) {
                    val updatedVariant = node.variants[variantIndex].copy(childNodeId = newNodeId)
                    val updatedVariants = node.variants.toMutableList()
                    updatedVariants[variantIndex] = updatedVariant
                    node.copy(variants = updatedVariants)
                } else {
                    node
                }
            }
            chat = chat.copy(messageNodes = updatedNodes)
        }

        // Create message with references
        val messageWithRefs = userMessage.copy(
            id = if (userMessage.id.isBlank()) UUID.randomUUID().toString() else userMessage.id,
            nodeId = newNodeId,
            variantId = newVariantId
        )

        // Create new variant and node
        val newVariant = MessageVariant(
            variantId = newVariantId,
            userMessage = messageWithRefs,
            responses = emptyList()
        )

        val newNode = MessageNode(
            nodeId = newNodeId,
            parentNodeId = parentNodeId,
            variants = listOf(newVariant)
        )

        // Update chat
        val updatedNodes = chat.messageNodes + newNode
        val updatedPath = chat.currentVariantPath + newVariantId
        val newMessages = buildMessagesFromPath(updatedNodes, updatedPath)

        val updatedChat = chat.copy(
            messageNodes = updatedNodes,
            currentVariantPath = updatedPath,
            messages = newMessages
        )

        updatedChat to updatedChat
    }

    /**
     * Find the node ID for a given message (by matching text and role).
     * Used when user wants to edit/resend a specific message.
     */
    fun findNodeForMessage(chat: Chat, message: Message): String? {
        if (!chat.hasBranchingStructure) return null

        for (node in chat.messageNodes) {
            for (variant in node.variants) {
                // Match by ID if available, otherwise by content
                if (variant.userMessage.id == message.id ||
                    (variant.userMessage.text == message.text &&
                     variant.userMessage.role == message.role &&
                     variant.userMessage.datetime == message.datetime)) {
                    return node.nodeId
                }
            }
        }
        return null
    }

    /**
     * Delete a message from the branching structure.
     * Handles different cases:
     * - Simple message (no branching) - just delete it
     * - Branch point with no children - delete the entire branch
     * - Branch point with children - show error message
     */
    fun deleteMessageFromBranch(
        username: String,
        chatId: String,
        messageId: String
    ): DeleteMessageResult = chatHistoryManager.modifyChatHistory(username) { chatHistory ->
        val chat = chatHistory.chat_history.find { it.chat_id == chatId }
            ?: return@modifyChatHistory chatHistory to DeleteMessageResult.Error("Chat not found")

        // If no branching structure, use simple deletion
        if (!chat.hasBranchingStructure || chat.messageNodes.isEmpty()) {
            val updatedMessages = chat.messages.filter { it.id != messageId }
            val updatedChat = chat.copy(messages = updatedMessages)

            val updatedChats = chatHistory.chat_history.map { c ->
                if (c.chat_id == chatId) updatedChat else c
            }
            return@modifyChatHistory chatHistory.copy(chat_history = updatedChats) to DeleteMessageResult.Success(updatedChat)
        }

        // Find the message in the branching structure
        // IMPORTANT: We need to find the variant that's in currentVariantPath, not just any variant
        var targetNode: MessageNode? = null
        var targetVariant: MessageVariant? = null
        var isUserMessage = false

        for (node in chat.messageNodes) {
            // First, find if this node has a variant in the current path
            val variantInPath = chat.currentVariantPath.find { variantId ->
                node.variants.any { it.variantId == variantId }
            }

            // Prefer the variant in path, otherwise check all variants
            val variantsToCheck = if (variantInPath != null) {
                // Only check the variant that's in the current path
                listOfNotNull(node.getVariantById(variantInPath))
            } else {
                node.variants
            }

            for (variant in variantsToCheck) {
                // Check if it's the user message
                if (variant.userMessage.id == messageId) {
                    targetNode = node
                    targetVariant = variant
                    isUserMessage = true
                    break
                }
                // Check if it's one of the responses
                val responseIndex = variant.responses.indexOfFirst { it.id == messageId }
                if (responseIndex >= 0) {
                    targetNode = node
                    targetVariant = variant
                    isUserMessage = false
                    break
                }
            }
            if (targetNode != null) break
        }

        if (targetNode == null || targetVariant == null) {
            // Message not found in branching structure, try simple deletion
            val updatedMessages = chat.messages.filter { it.id != messageId }
            val updatedChat = chat.copy(messages = updatedMessages)

            val updatedChats = chatHistory.chat_history.map { c ->
                if (c.chat_id == chatId) updatedChat else c
            }
            return@modifyChatHistory chatHistory.copy(chat_history = updatedChats) to DeleteMessageResult.Success(updatedChat)
        }

        val totalVariants = targetNode.variants.size

        // Check if this is a branch point (multiple variants at this node)
        val isBranchPoint = totalVariants > 1

        if (isUserMessage) {
            // Deleting a user message

            // Check if there are messages/children after this in the current branch
            val hasResponsesAfter = targetVariant.responses.isNotEmpty()

            // Check if child node actually exists and has content
            val childNode = if (targetVariant.childNodeId != null) {
                chat.messageNodes.find { it.nodeId == targetVariant.childNodeId }
            } else null
            val hasChildrenAfter = childNode != null && childNode.variants.isNotEmpty()

            val hasMessagesAfter = hasResponsesAfter || hasChildrenAfter

            if (isBranchPoint) {
                // This is a branch point with multiple variants
                if (hasMessagesAfter) {
                    return@modifyChatHistory chatHistory to DeleteMessageResult.CannotDeleteBranchPoint(
                        "לא ניתן למחוק הודעת התפצלות שיש אחריה הודעות נוספות. למחיקת ההודעה, מחקו קודם כל את ההודעות שאחריה."
                    )
                }

                // No messages after - delete this entire variant/branch
                val currentVariantIndex = targetNode.variants.indexOf(targetVariant)
                val updatedVariants = targetNode.variants.toMutableList()
                updatedVariants.removeAt(currentVariantIndex)

                // Update the node with remaining variants
                val updatedNode = targetNode.copy(variants = updatedVariants)

                // Update messageNodes
                val updatedNodes = chat.messageNodes.map { node ->
                    if (node.nodeId == targetNode.nodeId) updatedNode else node
                }.filter { it.variants.isNotEmpty() } // Remove empty nodes

                // Update currentVariantPath to point to another variant
                val newVariantPath = chat.currentVariantPath.toMutableList()
                val pathIndex = newVariantPath.indexOf(targetVariant.variantId)
                if (pathIndex >= 0) {
                    // Replace with another variant from this node (prefer previous, then next)
                    val newVariantIndex = if (currentVariantIndex > 0) currentVariantIndex - 1 else 0
                    val newVariant = updatedVariants.getOrNull(newVariantIndex)
                    if (newVariant != null) {
                        newVariantPath[pathIndex] = newVariant.variantId
                    } else {
                        // No variants left, remove from path
                        newVariantPath.removeAt(pathIndex)
                    }
                }

                // Rebuild messages list based on new path
                val rebuiltMessages = rebuildMessagesFromPath(
                    chat.copy(messageNodes = updatedNodes),
                    newVariantPath
                )

                val updatedChat = chat.copy(
                    messageNodes = updatedNodes,
                    currentVariantPath = newVariantPath,
                    messages = rebuiltMessages
                )

                val updatedChats = chatHistory.chat_history.map { c ->
                    if (c.chat_id == chatId) updatedChat else c
                }
                return@modifyChatHistory chatHistory.copy(chat_history = updatedChats) to DeleteMessageResult.Success(updatedChat)

            } else {
                // Single variant at this node
                if (hasMessagesAfter) {
                    return@modifyChatHistory chatHistory to DeleteMessageResult.CannotDeleteBranchPoint(
                        "לא ניתן למחוק הודעה שיש אחריה הודעות נוספות. למחיקת ההודעה, מחקו קודם כל את ההודעות שאחריה."
                    )
                }

                // Remove this node entirely
                val updatedNodes = chat.messageNodes.filter { it.nodeId != targetNode.nodeId }

                // Update parent's childNodeId to null
                val nodesWithUpdatedParent = updatedNodes.map { node ->
                    val updatedVariantsInNode = node.variants.map { variant ->
                        if (variant.childNodeId == targetNode.nodeId) {
                            variant.copy(childNodeId = null)
                        } else {
                            variant
                        }
                    }
                    node.copy(variants = updatedVariantsInNode)
                }

                // Update path - remove this variant
                val newVariantPath = chat.currentVariantPath.filter { it != targetVariant.variantId }

                val rebuiltMessages = rebuildMessagesFromPath(
                    chat.copy(messageNodes = nodesWithUpdatedParent),
                    newVariantPath
                )

                val updatedChat = chat.copy(
                    messageNodes = nodesWithUpdatedParent,
                    currentVariantPath = newVariantPath,
                    messages = rebuiltMessages
                )

                val updatedChats = chatHistory.chat_history.map { c ->
                    if (c.chat_id == chatId) updatedChat else c
                }
                return@modifyChatHistory chatHistory.copy(chat_history = updatedChats) to DeleteMessageResult.Success(updatedChat)
            }

        } else {
            // Deleting an assistant response
            val messageIndex = targetVariant.responses.indexOfFirst { it.id == messageId }

            // Check if there are messages after this response
            val hasMoreResponses = messageIndex < targetVariant.responses.size - 1

            // Check if child node actually exists and has content
            val childNodeForResponse = if (targetVariant.childNodeId != null) {
                chat.messageNodes.find { it.nodeId == targetVariant.childNodeId }
            } else null
            val hasChildren = childNodeForResponse != null && childNodeForResponse.variants.isNotEmpty()

            if (hasMoreResponses || hasChildren) {
                return@modifyChatHistory chatHistory to DeleteMessageResult.CannotDeleteBranchPoint(
                    "לא ניתן למחוק הודעה שיש אחריה הודעות נוספות. למחיקת ההודעה, מחקו קודם כל את ההודעות שאחריה."
                )
            }

            // Delete this response
            val updatedResponses = targetVariant.responses.filter { it.id != messageId }
            val updatedVariant = targetVariant.copy(responses = updatedResponses)

            val updatedVariantsForNode = targetNode.variants.map { variant ->
                if (variant.variantId == targetVariant.variantId) updatedVariant else variant
            }

            val updatedNode = targetNode.copy(variants = updatedVariantsForNode)
            val updatedNodes = chat.messageNodes.map { node ->
                if (node.nodeId == targetNode.nodeId) updatedNode else node
            }

            val rebuiltMessages = rebuildMessagesFromPath(
                chat.copy(messageNodes = updatedNodes),
                chat.currentVariantPath
            )

            val updatedChat = chat.copy(
                messageNodes = updatedNodes,
                messages = rebuiltMessages
            )

            val updatedChats = chatHistory.chat_history.map { c ->
                if (c.chat_id == chatId) updatedChat else c
            }
            return@modifyChatHistory chatHistory.copy(chat_history = updatedChats) to DeleteMessageResult.Success(updatedChat)
        }
    }

    /**
     * Rebuild the flat messages list from the branching structure based on a variant path
     */
    private fun rebuildMessagesFromPath(chat: Chat, variantPath: List<String>): List<Message> {
        if (chat.messageNodes.isEmpty()) return emptyList()

        val messages = mutableListOf<Message>()

        // Find root node (no parent)
        var currentNode = chat.messageNodes.find { it.parentNodeId == null }

        while (currentNode != null) {
            // Find variant in path or use first
            val variantInPath = variantPath.find { variantId ->
                currentNode!!.variants.any { it.variantId == variantId }
            }

            val currentVariant = if (variantInPath != null) {
                currentNode.getVariantById(variantInPath)
            } else {
                currentNode.variants.firstOrNull()
            }

            if (currentVariant == null) break

            // Add user message
            messages.add(currentVariant.userMessage)

            // Add responses
            messages.addAll(currentVariant.responses)

            // Move to child node
            val childNodeId = currentVariant.childNodeId
            currentNode = if (childNodeId != null) {
                chat.messageNodes.find { it.nodeId == childNodeId }
            } else {
                null
            }
        }

        return messages
    }
}

/**
 * Result of attempting to delete a message from a branching structure.
 */
sealed class DeleteMessageResult {
    data class Success(val updatedChat: Chat) : DeleteMessageResult()
    data class CannotDeleteBranchPoint(val message: String) : DeleteMessageResult()
    data class Error(val message: String) : DeleteMessageResult()
}
