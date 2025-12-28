package com.example.ApI.ui.managers.chat

import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch

/**
 * Manages message branching and variant navigation.
 * Handles switching between different conversation paths and branch structures.
 * Extracted from ChatViewModel to reduce complexity.
 */
class BranchingManager(
    private val deps: ManagerDependencies
) {

    /**
     * Get branch info for a specific message.
     * Returns info about available variants at the node containing this message.
     */
    fun getBranchInfoForMessage(message: Message): BranchInfo? {
        val currentChat = deps.uiState.value.currentChat ?: return null
        return deps.repository.getBranchInfoForMessage(currentChat, message.id)
    }

    /**
     * Navigate to the next variant at a specific node.
     */
    fun navigateToNextVariant(nodeId: String) {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: return

        // Get current branch info
        val branchInfo = deps.repository.getBranchInfo(currentChat, nodeId) ?: return
        if (!branchInfo.hasNext) return

        // Switch to next variant
        val updatedChat = deps.repository.switchVariant(
            currentUser,
            currentChat.chat_id,
            nodeId,
            branchInfo.currentVariantIndex + 1
        ) ?: return

        // Update UI
        deps.scope.launch {
            val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
            deps.updateUiState(
                deps.uiState.value.copy(
                    currentChat = updatedChat,
                    chatHistory = updatedChatHistory
                )
            )
        }
    }

    /**
     * Navigate to the previous variant at a specific node.
     */
    fun navigateToPreviousVariant(nodeId: String) {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: return

        // Get current branch info
        val branchInfo = deps.repository.getBranchInfo(currentChat, nodeId) ?: return
        if (!branchInfo.hasPrevious) return

        // Switch to previous variant
        val updatedChat = deps.repository.switchVariant(
            currentUser,
            currentChat.chat_id,
            nodeId,
            branchInfo.currentVariantIndex - 1
        ) ?: return

        // Update UI
        deps.scope.launch {
            val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
            deps.updateUiState(
                deps.uiState.value.copy(
                    currentChat = updatedChat,
                    chatHistory = updatedChatHistory
                )
            )
        }
    }

    /**
     * Navigate to a specific variant by index at a node.
     */
    fun navigateToVariant(nodeId: String, variantIndex: Int) {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: return

        val updatedChat = deps.repository.switchVariant(
            currentUser,
            currentChat.chat_id,
            nodeId,
            variantIndex
        ) ?: return

        // Update UI
        deps.scope.launch {
            val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
            deps.updateUiState(
                deps.uiState.value.copy(
                    currentChat = updatedChat,
                    chatHistory = updatedChatHistory
                )
            )
        }
    }

    /**
     * Ensure the current chat has branching structure (migrate if needed).
     */
    fun ensureBranchingStructure() {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: return

        if (!currentChat.hasBranchingStructure) {
            val migratedChat = deps.repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
            if (migratedChat != null) {
                deps.scope.launch {
                    val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
                    deps.updateUiState(
                        deps.uiState.value.copy(
                            currentChat = migratedChat,
                            chatHistory = updatedChatHistory
                        )
                    )
                }
            }
        }
    }
}
