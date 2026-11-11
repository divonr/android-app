package com.example.ApI.ui.managers.chat

import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DeleteMessageResult
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch

/**
 * Manages message editing, deletion, and resending operations.
 */
class MessageEditingManager(
    private val deps: ManagerDependencies,
    private val getCurrentDateTimeISO: () -> String,
    private val sendApiRequestForBranch: (Chat) -> Unit
) {

    fun deleteMessage(message: Message) {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: return
        deps.scope.launch {
            val result = deps.repository.deleteMessageFromBranch(currentUser, currentChat.chat_id, message.id)
            when (result) {
                is DeleteMessageResult.Success -> {
                    val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
                    deps.updateUiState(deps.uiState.value.copy(currentChat = result.updatedChat, chatHistory = updatedChatHistory))
                }
                is DeleteMessageResult.CannotDeleteBranchPoint -> {
                    deps.updateUiState(deps.uiState.value.copy(snackbarMessage = result.message))
                }
                is DeleteMessageResult.Error -> {
                    deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Error deleting message: ${result.message}"))
                }
            }
        }
    }

    fun startEditingMessage(message: Message) {
        deps.updateUiState(deps.uiState.value.copy(editingMessage = message, isEditMode = true, currentMessage = message.text))
    }

    fun finishEditingMessage() {
        val editingMessage = deps.uiState.value.editingMessage ?: return
        val currentChat = deps.uiState.value.currentChat ?: return
        val currentUser = deps.appSettings.value.current_user
        val newText = deps.uiState.value.currentMessage.trim()
        if (newText.isEmpty()) return
        if (newText == editingMessage.text) {
            deps.updateUiState(deps.uiState.value.copy(editingMessage = null, isEditMode = false, currentMessage = ""))
            return
        }
        val chatWithBranching = deps.repository.ensureBranchingStructure(currentUser, currentChat.chat_id) ?: return
        val nodeId = deps.repository.findNodeForMessage(chatWithBranching, editingMessage)
        if (nodeId != null) {
            val editedMessage = editingMessage.copy(text = newText, datetime = getCurrentDateTimeISO())
            val result = deps.repository.createBranch(currentUser, currentChat.chat_id, nodeId, editedMessage)
            if (result != null) {
                val (updatedChat, _) = result
                val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
                deps.updateUiState(deps.uiState.value.copy(
                    editingMessage = null, isEditMode = false, currentMessage = "",
                    currentChat = updatedChat, chatHistory = updatedChatHistory
                ))
                return
            }
        }
        val updatedMessage = editingMessage.copy(text = newText)
        val updatedChat = deps.repository.replaceMessageInChat(currentUser, currentChat.chat_id, editingMessage, updatedMessage)
        val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
        deps.updateUiState(deps.uiState.value.copy(
            editingMessage = null, isEditMode = false, currentMessage = "",
            currentChat = updatedChat, chatHistory = updatedChatHistory
        ))
    }

    fun confirmEditAndResend() {
        val editingMessage = deps.uiState.value.editingMessage ?: return
        val currentChat = deps.uiState.value.currentChat ?: return
        val currentUser = deps.appSettings.value.current_user
        val newText = deps.uiState.value.currentMessage.trim()
        if (newText.isEmpty()) return
        val chatWithBranching = deps.repository.ensureBranchingStructure(currentUser, currentChat.chat_id) ?: return
        val nodeId = deps.repository.findNodeForMessage(chatWithBranching, editingMessage)
        if (nodeId != null) {
            val editedMessage = editingMessage.copy(text = newText, datetime = getCurrentDateTimeISO())
            val result = deps.repository.createBranch(currentUser, currentChat.chat_id, nodeId, editedMessage)
            if (result != null) {
                val (updatedChat, _) = result
                val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
                deps.updateUiState(deps.uiState.value.copy(
                    editingMessage = null, isEditMode = false, currentMessage = "",
                    currentChat = updatedChat, chatHistory = updatedChatHistory
                ))
                sendApiRequestForBranch(updatedChat)
                return
            }
        }
        val updatedMessage = editingMessage.copy(text = newText)
        val updatedChat = deps.repository.replaceMessageInChat(currentUser, currentChat.chat_id, editingMessage, updatedMessage) ?: return
        val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
        deps.updateUiState(deps.uiState.value.copy(
            editingMessage = null, isEditMode = false, currentMessage = "",
            currentChat = updatedChat, chatHistory = updatedChatHistory
        ))
        resendFromMessage(updatedMessage)
    }

    fun cancelEditingMessage() {
        deps.updateUiState(deps.uiState.value.copy(editingMessage = null, isEditMode = false, currentMessage = ""))
    }

    fun resendFromMessage(message: Message) {
        val currentChat = deps.uiState.value.currentChat ?: return
        val currentUser = deps.appSettings.value.current_user
        deps.scope.launch {
            val chatWithBranching = deps.repository.ensureBranchingStructure(currentUser, currentChat.chat_id) ?: return@launch
            val nodeId = deps.repository.findNodeForMessage(chatWithBranching, message)
            if (nodeId != null) {
                val resendMessage = message.copy(datetime = getCurrentDateTimeISO())
                val result = deps.repository.createBranch(currentUser, currentChat.chat_id, nodeId, resendMessage)
                if (result != null) {
                    val (updatedChat, _) = result
                    val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
                    deps.updateUiState(deps.uiState.value.copy(currentChat = updatedChat, chatHistory = updatedChatHistory))
                    sendApiRequestForBranch(updatedChat)
                    return@launch
                }
            }
            val deletedChat = deps.repository.deleteMessagesFromPoint(currentUser, currentChat.chat_id, message) ?: return@launch
            val resendUpdatedChat = deps.repository.addMessageToChat(currentUser, deletedChat.chat_id, message)
            val finalChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
            val chatId = resendUpdatedChat!!.chat_id
            deps.updateUiState(deps.uiState.value.copy(
                currentChat = resendUpdatedChat, chatHistory = finalChatHistory,
                loadingChatIds = deps.uiState.value.loadingChatIds + chatId,
                streamingChatIds = deps.uiState.value.streamingChatIds + chatId,
                streamingTextByChat = deps.uiState.value.streamingTextByChat + (chatId to "")
            ))
            sendApiRequestForBranch(resendUpdatedChat)
        }
    }
}
