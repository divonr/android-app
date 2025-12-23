package com.example.ApI.ui.managers.chat

import androidx.compose.ui.unit.DpOffset
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatContextMenuState
import com.example.ApI.data.model.ChatUiState
import com.example.ApI.data.model.Screen
import com.example.ApI.data.model.WebSearchSupport
import com.example.ApI.data.repository.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manages chat context menu operations.
 * Handles showing/hiding context menus, rename dialogs, delete confirmations,
 * and chat-level operations like delete and web search toggle.
 * Extracted from ChatViewModel to reduce complexity.
 */
class ChatContextMenuManager(
    private val repository: DataRepository,
    private val scope: CoroutineScope,
    private val appSettings: StateFlow<AppSettings>,
    private val uiState: StateFlow<ChatUiState>,
    private val updateUiState: (ChatUiState) -> Unit,
    private val navigateToScreen: (Screen) -> Unit,
    private val updateChatPreviewName: suspend (String, String) -> Unit
) {

    // ==================== Context Menu ====================

    /**
     * Show the context menu for a chat at the specified position.
     */
    fun showChatContextMenu(chat: Chat, position: DpOffset) {
        updateUiState(
            uiState.value.copy(
                chatContextMenu = ChatContextMenuState(chat, position)
            )
        )
    }

    /**
     * Hide the chat context menu.
     */
    fun hideChatContextMenu() {
        updateUiState(
            uiState.value.copy(
                chatContextMenu = null
            )
        )
    }

    // ==================== Rename Dialog ====================

    /**
     * Show the rename dialog for a chat.
     */
    fun showRenameDialog(chat: Chat) {
        updateUiState(
            uiState.value.copy(
                showRenameDialog = chat,
                chatContextMenu = null
            )
        )
    }

    /**
     * Hide the rename dialog.
     */
    fun hideRenameDialog() {
        updateUiState(
            uiState.value.copy(
                showRenameDialog = null
            )
        )
    }

    /**
     * Rename a chat with the given new name.
     */
    fun renameChat(chat: Chat, newName: String) {
        if (newName.isBlank()) return

        scope.launch {
            updateChatPreviewName(chat.chat_id, newName.trim())
            hideRenameDialog()
        }
    }

    // ==================== Delete Confirmation (from context menu) ====================

    /**
     * Show delete confirmation for a chat (from context menu).
     */
    fun showDeleteConfirmation(chat: Chat) {
        updateUiState(
            uiState.value.copy(
                showDeleteConfirmation = chat,
                chatContextMenu = null
            )
        )
    }

    /**
     * Hide delete confirmation (from context menu).
     */
    fun hideDeleteConfirmation() {
        updateUiState(
            uiState.value.copy(
                showDeleteConfirmation = null
            )
        )
    }

    /**
     * Delete a chat (from context menu confirmation).
     */
    fun deleteChat(chat: Chat) {
        scope.launch {
            val currentUser = appSettings.value.current_user
            val chatHistory = repository.loadChatHistory(currentUser)

            // Remove the chat from history
            val updatedChats = chatHistory.chat_history.filter { it.chat_id != chat.chat_id }
            val updatedHistory = chatHistory.copy(chat_history = updatedChats)

            // Save updated history
            repository.saveChatHistory(updatedHistory)

            // Update UI
            val finalChatHistory = repository.loadChatHistory(currentUser).chat_history

            // If we're deleting the current chat, switch to the most recent one or null
            val newCurrentChat = if (uiState.value.currentChat?.chat_id == chat.chat_id) {
                finalChatHistory.lastOrNull()
            } else {
                uiState.value.currentChat
            }

            updateUiState(
                uiState.value.copy(
                    chatHistory = finalChatHistory,
                    groups = chatHistory.groups,
                    currentChat = newCurrentChat,
                    showDeleteConfirmation = null
                )
            )
        }
    }

    // ==================== Delete Current Chat Confirmation (from chat screen) ====================

    /**
     * Show delete confirmation for the current chat (from chat screen).
     */
    fun showDeleteChatConfirmation() {
        updateUiState(
            uiState.value.copy(
                showDeleteChatConfirmation = uiState.value.currentChat
            )
        )
    }

    /**
     * Hide delete confirmation for the current chat.
     */
    fun hideDeleteChatConfirmation() {
        updateUiState(
            uiState.value.copy(
                showDeleteChatConfirmation = null
            )
        )
    }

    /**
     * Delete the current chat and navigate to chat history.
     */
    fun deleteCurrentChat() {
        val currentChat = uiState.value.currentChat ?: return
        val currentUser = appSettings.value.current_user

        scope.launch {
            val chatHistory = repository.loadChatHistory(currentUser)

            // Remove the chat from history
            val updatedChats = chatHistory.chat_history.filter { it.chat_id != currentChat.chat_id }
            val updatedHistory = chatHistory.copy(chat_history = updatedChats)

            // Save updated history
            repository.saveChatHistory(updatedHistory)

            // Update UI
            val finalChatHistory = repository.loadChatHistory(currentUser).chat_history

            updateUiState(
                uiState.value.copy(
                    chatHistory = finalChatHistory,
                    currentChat = null,
                    systemPrompt = "",
                    showDeleteChatConfirmation = null
                )
            )

            // Always navigate back to chat history screen when deleting current chat
            navigateToScreen(Screen.ChatHistory)
        }
    }

    // ==================== Web Search Toggle ====================

    /**
     * Toggle web search for the current model.
     * Shows a message if web search cannot be disabled (required by model).
     */
    fun toggleWebSearch() {
        val currentSupport = uiState.value.webSearchSupport
        val currentProvider = uiState.value.currentProvider?.provider ?: ""
        val currentModel = uiState.value.currentModel

        when (currentSupport) {
            WebSearchSupport.REQUIRED -> {
                // Show message that it cannot be disabled
                updateUiState(
                    uiState.value.copy(
                        snackbarMessage = "לא ניתן לכבות את החיבור לאינטרנט עבור מודל $currentModel דרך הספק $currentProvider"
                    )
                )
            }
            WebSearchSupport.OPTIONAL -> {
                // Toggle the state
                updateUiState(
                    uiState.value.copy(
                        webSearchEnabled = !uiState.value.webSearchEnabled
                    )
                )
            }
            WebSearchSupport.UNSUPPORTED -> {
                // This shouldn't happen as the icon should be hidden, but just in case
                // Do nothing
            }
        }
    }
}
