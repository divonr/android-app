package com.example.ApI.ui.managers.chat

import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DeleteMessageResult
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch

/**
 * Manages message editing, deletion, and resending operations.
 * Handles local edit state, branching communication, and delegates API calls to MessageSendingManager.
 * Split from MessageOperationsManager - this manager owns edit/delete/resend logic.
 */
class MessageEditingManager(
    private val deps: ManagerDependencies,
    private val getCurrentDateTimeISO: () -> String,
    private val sendApiRequestForBranch: (Chat) -> Unit
) {

    /**
     * Delete a message using branching-aware deletion.
     */
    fun deleteMessage(message: Message) {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: return

        deps.scope.launch {
            // Use branching-aware deletion
            val result = deps.repository.deleteMessageFromBranch(
                currentUser,
                currentChat.chat_id,
                message.id
            )

            when (result) {
                is DeleteMessageResult.Success -> {
                    val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
                    deps.updateUiState(deps.uiState.value.copy(
                        currentChat = result.updatedChat,
                        chatHistory = updatedChatHistory
                    ))
                }
                is DeleteMessageResult.CannotDeleteBranchPoint -> {
                    deps.updateUiState(deps.uiState.value.copy(
                        snackbarMessage = result.message
                    ))
                }
                is DeleteMessageResult.Error -> {
                    deps.updateUiState(deps.uiState.value.copy(
                        snackbarMessage = "שגיאה במחיקת ההודעה: ${result.message}"
                    ))
                }
            }
        }
    }

    /**
     * Start editing a message.
     */
    fun startEditingMessage(message: Message) {
        deps.updateUiState(deps.uiState.value.copy(
            editingMessage = message,
            isEditMode = true,
            currentMessage = message.text
        ))
    }

    /**
     * Finish editing a message - creates a new branch with the edited message (no API call).
     */
    fun finishEditingMessage() {
        val editingMessage = deps.uiState.value.editingMessage ?: return
        val currentChat = deps.uiState.value.currentChat ?: return
        val currentUser = deps.appSettings.value.current_user
        val newText = deps.uiState.value.currentMessage.trim()

        if (newText.isEmpty()) return

        // If text hasn't changed, just exit edit mode
        if (newText == editingMessage.text) {
            deps.updateUiState(deps.uiState.value.copy(
                editingMessage = null,
                isEditMode = false,
                currentMessage = ""
            ))
            return
        }

        // Ensure branching structure exists
        val chatWithBranching = deps.repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
            ?: return

        // Find the node for this message
        val nodeId = deps.repository.findNodeForMessage(chatWithBranching, editingMessage)

        if (nodeId != null) {
            // Create a new branch with the edited message (no responses yet)
            val editedMessage = editingMessage.copy(
                text = newText,
                datetime = getCurrentDateTimeISO()
            )

            val result = deps.repository.createBranch(
                currentUser,
                currentChat.chat_id,
                nodeId,
                editedMessage
            )

            if (result != null) {
                val (updatedChat, _) = result
                val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history

                deps.updateUiState(deps.uiState.value.copy(
                    editingMessage = null,
                    isEditMode = false,
                    currentMessage = "",
                    currentChat = updatedChat,
                    chatHistory = updatedChatHistory
                ))
                return
            }
        }

        // Fallback: if branching fails, use the old behavior
        val updatedMessage = editingMessage.copy(text = newText)
        val updatedChat = deps.repository.replaceMessageInChat(
            currentUser,
            currentChat.chat_id,
            editingMessage,
            updatedMessage
        )

        val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history

        deps.updateUiState(deps.uiState.value.copy(
            editingMessage = null,
            isEditMode = false,
            currentMessage = "",
            currentChat = updatedChat,
            chatHistory = updatedChatHistory
        ))
    }

    /**
     * Confirm the edit and immediately resend from the edited message (creates new branch with API call).
     */
    fun confirmEditAndResend() {
        val editingMessage = deps.uiState.value.editingMessage ?: return
        val currentChat = deps.uiState.value.currentChat ?: return
        val currentUser = deps.appSettings.value.current_user
        val newText = deps.uiState.value.currentMessage.trim()
        if (newText.isEmpty()) return

        // Ensure branching structure exists
        val chatWithBranching = deps.repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
            ?: return

        // Find the node for this message
        val nodeId = deps.repository.findNodeForMessage(chatWithBranching, editingMessage)

        if (nodeId != null) {
            // Create the edited message
            val editedMessage = editingMessage.copy(
                text = newText,
                datetime = getCurrentDateTimeISO()
            )

            // Create a new branch
            val result = deps.repository.createBranch(
                currentUser,
                currentChat.chat_id,
                nodeId,
                editedMessage
            )

            if (result != null) {
                val (updatedChat, _) = result
                val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history

                deps.updateUiState(deps.uiState.value.copy(
                    editingMessage = null,
                    isEditMode = false,
                    currentMessage = "",
                    currentChat = updatedChat,
                    chatHistory = updatedChatHistory
                ))

                // Delegate API call to MessageSendingManager
                sendApiRequestForBranch(updatedChat)
                return
            }
        }

        // Fallback: use old behavior if branching fails
        val updatedMessage = editingMessage.copy(text = newText)
        val updatedChat = deps.repository.replaceMessageInChat(
            currentUser,
            currentChat.chat_id,
            editingMessage,
            updatedMessage
        ) ?: return

        val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
        deps.updateUiState(deps.uiState.value.copy(
            editingMessage = null,
            isEditMode = false,
            currentMessage = "",
            currentChat = updatedChat,
            chatHistory = updatedChatHistory
        ))

        resendFromMessage(updatedMessage)
    }

    /**
     * Cancel editing without saving changes.
     */
    fun cancelEditingMessage() {
        deps.updateUiState(deps.uiState.value.copy(
            editingMessage = null,
            isEditMode = false,
            currentMessage = ""
        ))
    }

    /**
     * Resend from a specific message point - creates a new branch with the same message.
     */
    fun resendFromMessage(message: Message) {
        val currentChat = deps.uiState.value.currentChat ?: return
        val currentUser = deps.appSettings.value.current_user

        deps.scope.launch {
            // Ensure branching structure exists
            val chatWithBranching = deps.repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
                ?: return@launch

            // Find the node for this message
            val nodeId = deps.repository.findNodeForMessage(chatWithBranching, message)

            if (nodeId != null) {
                // Create a new branch with the same message but new timestamp
                val resendMessage = message.copy(
                    datetime = getCurrentDateTimeISO()
                )

                val result = deps.repository.createBranch(
                    currentUser,
                    currentChat.chat_id,
                    nodeId,
                    resendMessage
                )

                if (result != null) {
                    val (updatedChat, _) = result
                    val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history

                    deps.updateUiState(deps.uiState.value.copy(
                        currentChat = updatedChat,
                        chatHistory = updatedChatHistory
                    ))

                    // Delegate API call to MessageSendingManager
                    sendApiRequestForBranch(updatedChat)
                    return@launch
                }
            }

            // Fallback: old behavior if branching fails
            val deletedChat = deps.repository.deleteMessagesFromPoint(
                currentUser,
                currentChat.chat_id,
                message
            ) ?: return@launch

            val resendUpdatedChat = deps.repository.addMessageToChat(
                currentUser,
                deletedChat.chat_id,
                message
            )

            val finalChatHistory = deps.repository.loadChatHistory(currentUser).chat_history

            val chatId = resendUpdatedChat!!.chat_id
            deps.updateUiState(deps.uiState.value.copy(
                currentChat = resendUpdatedChat,
                chatHistory = finalChatHistory,
                loadingChatIds = deps.uiState.value.loadingChatIds + chatId,
                streamingChatIds = deps.uiState.value.streamingChatIds + chatId,
                streamingTextByChat = deps.uiState.value.streamingTextByChat + (chatId to "")
            ))

            // Delegate API call to MessageSendingManager
            sendApiRequestForBranch(resendUpdatedChat)
        }
    }
}
