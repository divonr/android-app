package com.example.ApI.ui.managers.chat

import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch

/**
 * Manages message branching and variant navigation.
 * Handles switching between different conversation paths and branch structures.
 */
class BranchingManager(
    private val deps: ManagerDependencies
) {

    fun getBranchInfoForMessage(message: Message): BranchInfo? {
        val currentChat = deps.uiState.value.currentChat ?: return null
        return deps.repository.getBranchInfoForMessage(currentChat, message.id)
    }

    fun navigateToNextVariant(nodeId: String) {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: return
        val branchInfo = deps.repository.getBranchInfo(currentChat, nodeId) ?: return
        if (!branchInfo.hasNext) return
        val updatedChat = deps.repository.switchVariant(currentUser, currentChat.chat_id, nodeId, branchInfo.currentVariantIndex + 1) ?: return
        deps.scope.launch {
            val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
            deps.updateUiState(deps.uiState.value.copy(currentChat = updatedChat, chatHistory = updatedChatHistory))
        }
    }

    fun navigateToPreviousVariant(nodeId: String) {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: return
        val branchInfo = deps.repository.getBranchInfo(currentChat, nodeId) ?: return
        if (!branchInfo.hasPrevious) return
        val updatedChat = deps.repository.switchVariant(currentUser, currentChat.chat_id, nodeId, branchInfo.currentVariantIndex - 1) ?: return
        deps.scope.launch {
            val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
            deps.updateUiState(deps.uiState.value.copy(currentChat = updatedChat, chatHistory = updatedChatHistory))
        }
    }

    fun navigateToVariant(nodeId: String, variantIndex: Int) {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: return
        val updatedChat = deps.repository.switchVariant(currentUser, currentChat.chat_id, nodeId, variantIndex) ?: return
        deps.scope.launch {
            val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
            deps.updateUiState(deps.uiState.value.copy(currentChat = updatedChat, chatHistory = updatedChatHistory))
        }
    }

    fun ensureBranchingStructure() {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: return
        if (!currentChat.hasBranchingStructure) {
            val migratedChat = deps.repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
            if (migratedChat != null) {
                deps.scope.launch {
                    val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
                    deps.updateUiState(deps.uiState.value.copy(currentChat = migratedChat, chatHistory = updatedChatHistory))
                }
            }
        }
    }
}
