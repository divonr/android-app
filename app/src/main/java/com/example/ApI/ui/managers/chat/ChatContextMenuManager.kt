package com.example.ApI.ui.managers.chat

import androidx.compose.ui.unit.DpOffset
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatContextMenuState
import com.example.ApI.data.model.Screen
import com.example.ApI.data.model.WebSearchSupport
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch

/**
 * Manages chat context menu operations.
 * Handles showing/hiding context menus, rename dialogs, delete confirmations,
 * and chat-level operations like delete and web search toggle.
 * Extracted from ChatViewModel to reduce complexity.
 */
class ChatContextMenuManager(
    private val deps: ManagerDependencies,
    private val navigateToScreen: (Screen) -> Unit,
    private val updateChatPreviewName: suspend (String, String) -> Unit
) {

    // ==================== Context Menu ====================

    /**
     * Show the context menu for a chat at the specified position.
     */
    fun showChatContextMenu(chat: Chat, position: DpOffset) {
        deps.updateUiState(
            deps.uiState.value.copy(
                chatContextMenu = ChatContextMenuState(chat, position)
            )
        )
    }

    /**
     * Hide the chat context menu.
     */
    fun hideChatContextMenu() {
        deps.updateUiState(
            deps.uiState.value.copy(
                chatContextMenu = null
            )
        )
    }

    // ==================== Rename Dialog ====================

    /**
     * Show the rename dialog for a chat.
     */
    fun showRenameDialog(chat: Chat) {
        deps.updateUiState(
            deps.uiState.value.copy(
                showRenameDialog = chat,
                chatContextMenu = null
            )
        )
    }

    /**
     * Hide the rename dialog.
     */
    fun hideRenameDialog() {
        deps.updateUiState(
            deps.uiState.value.copy(
                showRenameDialog = null
            )
        )
    }

    /**
     * Rename a chat with the given new name.
     */
    fun renameChat(chat: Chat, newName: String) {
        if (newName.isBlank()) return

        deps.scope.launch {
            updateChatPreviewName(chat.chat_id, newName.trim())
            hideRenameDialog()
        }
    }

    // ==================== Delete Confirmation (from context menu) ====================

    /**
     * Show delete confirmation for a chat (from context menu).
     */
    fun showDeleteConfirmation(chat: Chat) {
        deps.updateUiState(
            deps.uiState.value.copy(
                showDeleteConfirmation = chat,
                chatContextMenu = null
            )
        )
    }

    /**
     * Hide delete confirmation (from context menu).
     */
    fun hideDeleteConfirmation() {
        deps.updateUiState(
            deps.uiState.value.copy(
                showDeleteConfirmation = null
            )
        )
    }

    /**
     * Delete a chat (from context menu confirmation).
     */
    fun deleteChat(chat: Chat) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val chatHistory = deps.repository.loadChatHistory(currentUser)

            // Remove the chat from history
            val updatedChats = chatHistory.chat_history.filter { it.chat_id != chat.chat_id }
            val updatedHistory = chatHistory.copy(chat_history = updatedChats)

            // Save updated history
            deps.repository.saveChatHistory(updatedHistory)

            // Update UI
            val finalChatHistory = deps.repository.loadChatHistory(currentUser).chat_history

            // If we're deleting the current chat, switch to the most recent one or null
            val newCurrentChat = if (deps.uiState.value.currentChat?.chat_id == chat.chat_id) {
                finalChatHistory.lastOrNull()
            } else {
                deps.uiState.value.currentChat
            }

            deps.updateUiState(
                deps.uiState.value.copy(
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
        deps.updateUiState(
            deps.uiState.value.copy(
                showDeleteChatConfirmation = deps.uiState.value.currentChat
            )
        )
    }

    /**
     * Hide delete confirmation for the current chat.
     */
    fun hideDeleteChatConfirmation() {
        deps.updateUiState(
            deps.uiState.value.copy(
                showDeleteChatConfirmation = null
            )
        )
    }

    /**
     * Delete the current chat and navigate to chat history.
     */
    fun deleteCurrentChat() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val currentUser = deps.appSettings.value.current_user

        deps.scope.launch {
            val chatHistory = deps.repository.loadChatHistory(currentUser)

            // Remove the chat from history
            val updatedChats = chatHistory.chat_history.filter { it.chat_id != currentChat.chat_id }
            val updatedHistory = chatHistory.copy(chat_history = updatedChats)

            // Save updated history
            deps.repository.saveChatHistory(updatedHistory)

            // Update UI
            val finalChatHistory = deps.repository.loadChatHistory(currentUser).chat_history

            deps.updateUiState(
                deps.uiState.value.copy(
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
        val currentSupport = deps.uiState.value.webSearchSupport
        val currentProvider = deps.uiState.value.currentProvider?.provider ?: ""
        val currentModel = deps.uiState.value.currentModel

        when (currentSupport) {
            WebSearchSupport.REQUIRED -> {
                // Show message that it cannot be disabled
                deps.updateUiState(
                    deps.uiState.value.copy(
                        snackbarMessage = "לא ניתן לכבות את החיבור לאינטרנט עבור מודל $currentModel דרך הספק $currentProvider"
                    )
                )
            }
            WebSearchSupport.OPTIONAL -> {
                // Toggle the state
                deps.updateUiState(
                    deps.uiState.value.copy(
                        webSearchEnabled = !deps.uiState.value.webSearchEnabled
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
