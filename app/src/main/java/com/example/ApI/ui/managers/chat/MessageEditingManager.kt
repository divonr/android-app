package com.example.ApI.ui.managers.chat

import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.data.repository.DeleteMessageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manages message editing, deletion, and resending operations.
 * Handles local edit state, branching communication, and delegates API calls to MessageSendingManager.
 * Split from MessageOperationsManager - this manager owns edit/delete/resend logic.
 */
class MessageEditingManager(
    private val repository: DataRepository,
    private val scope: CoroutineScope,
    private val appSettings: StateFlow<AppSettings>,
    private val uiState: StateFlow<ChatUiState>,
    private val updateUiState: (ChatUiState) -> Unit,
    private val getCurrentDateTimeISO: () -> String,
    private val sendApiRequestForBranch: (Chat) -> Unit
) {

    /**
     * Delete a message using branching-aware deletion.
     */
    fun deleteMessage(message: Message) {
        val currentUser = appSettings.value.current_user
        val currentChat = uiState.value.currentChat ?: return

        scope.launch {
            // Use branching-aware deletion
            val result = repository.deleteMessageFromBranch(
                currentUser,
                currentChat.chat_id,
                message.id
            )

            when (result) {
                is DeleteMessageResult.Success -> {
                    val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
                    updateUiState(uiState.value.copy(
                        currentChat = result.updatedChat,
                        chatHistory = updatedChatHistory
                    ))
                }
                is DeleteMessageResult.CannotDeleteBranchPoint -> {
                    updateUiState(uiState.value.copy(
                        snackbarMessage = result.message
                    ))
                }
                is DeleteMessageResult.Error -> {
                    updateUiState(uiState.value.copy(
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
        updateUiState(uiState.value.copy(
            editingMessage = message,
            isEditMode = true,
            currentMessage = message.text
        ))
    }

    /**
     * Finish editing a message - creates a new branch with the edited message (no API call).
     */
    fun finishEditingMessage() {
        val editingMessage = uiState.value.editingMessage ?: return
        val currentChat = uiState.value.currentChat ?: return
        val currentUser = appSettings.value.current_user
        val newText = uiState.value.currentMessage.trim()

        if (newText.isEmpty()) return

        // If text hasn't changed, just exit edit mode
        if (newText == editingMessage.text) {
            updateUiState(uiState.value.copy(
                editingMessage = null,
                isEditMode = false,
                currentMessage = ""
            ))
            return
        }

        // Ensure branching structure exists
        val chatWithBranching = repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
            ?: return

        // Find the node for this message
        val nodeId = repository.findNodeForMessage(chatWithBranching, editingMessage)

        if (nodeId != null) {
            // Create a new branch with the edited message (no responses yet)
            val editedMessage = editingMessage.copy(
                text = newText,
                datetime = getCurrentDateTimeISO()
            )

            val result = repository.createBranch(
                currentUser,
                currentChat.chat_id,
                nodeId,
                editedMessage
            )

            if (result != null) {
                val (updatedChat, _) = result
                val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history

                updateUiState(uiState.value.copy(
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
        val updatedChat = repository.replaceMessageInChat(
            currentUser,
            currentChat.chat_id,
            editingMessage,
            updatedMessage
        )

        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history

        updateUiState(uiState.value.copy(
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
        val editingMessage = uiState.value.editingMessage ?: return
        val currentChat = uiState.value.currentChat ?: return
        val currentUser = appSettings.value.current_user
        val newText = uiState.value.currentMessage.trim()
        if (newText.isEmpty()) return

        // Ensure branching structure exists
        val chatWithBranching = repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
            ?: return

        // Find the node for this message
        val nodeId = repository.findNodeForMessage(chatWithBranching, editingMessage)

        if (nodeId != null) {
            // Create the edited message
            val editedMessage = editingMessage.copy(
                text = newText,
                datetime = getCurrentDateTimeISO()
            )

            // Create a new branch
            val result = repository.createBranch(
                currentUser,
                currentChat.chat_id,
                nodeId,
                editedMessage
            )

            if (result != null) {
                val (updatedChat, _) = result
                val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history

                updateUiState(uiState.value.copy(
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
        val updatedChat = repository.replaceMessageInChat(
            currentUser,
            currentChat.chat_id,
            editingMessage,
            updatedMessage
        ) ?: return

        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
        updateUiState(uiState.value.copy(
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
        updateUiState(uiState.value.copy(
            editingMessage = null,
            isEditMode = false,
            currentMessage = ""
        ))
    }

    /**
     * Resend from a specific message point - creates a new branch with the same message.
     */
    fun resendFromMessage(message: Message) {
        val currentChat = uiState.value.currentChat ?: return
        val currentUser = appSettings.value.current_user

        scope.launch {
            // Ensure branching structure exists
            val chatWithBranching = repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
                ?: return@launch

            // Find the node for this message
            val nodeId = repository.findNodeForMessage(chatWithBranching, message)

            if (nodeId != null) {
                // Create a new branch with the same message but new timestamp
                val resendMessage = message.copy(
                    datetime = getCurrentDateTimeISO()
                )

                val result = repository.createBranch(
                    currentUser,
                    currentChat.chat_id,
                    nodeId,
                    resendMessage
                )

                if (result != null) {
                    val (updatedChat, _) = result
                    val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history

                    updateUiState(uiState.value.copy(
                        currentChat = updatedChat,
                        chatHistory = updatedChatHistory
                    ))

                    // Delegate API call to MessageSendingManager
                    sendApiRequestForBranch(updatedChat)
                    return@launch
                }
            }

            // Fallback: old behavior if branching fails
            val deletedChat = repository.deleteMessagesFromPoint(
                currentUser,
                currentChat.chat_id,
                message
            ) ?: return@launch

            val resendUpdatedChat = repository.addMessageToChat(
                currentUser,
                deletedChat.chat_id,
                message
            )

            val finalChatHistory = repository.loadChatHistory(currentUser).chat_history

            val chatId = resendUpdatedChat!!.chat_id
            updateUiState(uiState.value.copy(
                currentChat = resendUpdatedChat,
                chatHistory = finalChatHistory,
                loadingChatIds = uiState.value.loadingChatIds + chatId,
                streamingChatIds = uiState.value.streamingChatIds + chatId,
                streamingTextByChat = uiState.value.streamingTextByChat + (chatId to "")
            ))

            // Delegate API call to MessageSendingManager
            sendApiRequestForBranch(resendUpdatedChat)
        }
    }
}
