package com.example.ApI.ui.managers.chat

import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manages message branching and variant navigation.
 * Handles switching between different conversation paths and branch structures.
 * Extracted from ChatViewModel to reduce complexity.
 */
class BranchingManager(
    private val repository: DataRepository,
    private val scope: CoroutineScope,
    private val appSettings: StateFlow<AppSettings>,
    private val uiState: StateFlow<ChatUiState>,
    private val updateUiState: (ChatUiState) -> Unit
) {

    /**
     * Get branch info for a specific message.
     * Returns info about available variants at the node containing this message.
     */
    fun getBranchInfoForMessage(message: Message): BranchInfo? {
        val currentChat = uiState.value.currentChat ?: return null
        return repository.getBranchInfoForMessage(currentChat, message.id)
    }

    /**
     * Navigate to the next variant at a specific node.
     */
    fun navigateToNextVariant(nodeId: String) {
        val currentUser = appSettings.value.current_user
        val currentChat = uiState.value.currentChat ?: return

        // Get current branch info
        val branchInfo = repository.getBranchInfo(currentChat, nodeId) ?: return
        if (!branchInfo.hasNext) return

        // Switch to next variant
        val updatedChat = repository.switchVariant(
            currentUser,
            currentChat.chat_id,
            nodeId,
            branchInfo.currentVariantIndex + 1
        ) ?: return

        // Update UI
        scope.launch {
            val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
            updateUiState(
                uiState.value.copy(
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
        val currentUser = appSettings.value.current_user
        val currentChat = uiState.value.currentChat ?: return

        // Get current branch info
        val branchInfo = repository.getBranchInfo(currentChat, nodeId) ?: return
        if (!branchInfo.hasPrevious) return

        // Switch to previous variant
        val updatedChat = repository.switchVariant(
            currentUser,
            currentChat.chat_id,
            nodeId,
            branchInfo.currentVariantIndex - 1
        ) ?: return

        // Update UI
        scope.launch {
            val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
            updateUiState(
                uiState.value.copy(
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
        val currentUser = appSettings.value.current_user
        val currentChat = uiState.value.currentChat ?: return

        val updatedChat = repository.switchVariant(
            currentUser,
            currentChat.chat_id,
            nodeId,
            variantIndex
        ) ?: return

        // Update UI
        scope.launch {
            val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
            updateUiState(
                uiState.value.copy(
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
        val currentUser = appSettings.value.current_user
        val currentChat = uiState.value.currentChat ?: return

        if (!currentChat.hasBranchingStructure) {
            val migratedChat = repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
            if (migratedChat != null) {
                scope.launch {
                    val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
                    updateUiState(
                        uiState.value.copy(
                            currentChat = migratedChat,
                            chatHistory = updatedChatHistory
                        )
                    )
                }
            }
        }
    }
}
