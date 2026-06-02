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
 */
class ChatContextMenuManager(
    private val deps: ManagerDependencies,
    private val navigateToScreen: (Screen) -> Unit,
    private val updateChatPreviewName: suspend (String, String) -> Unit
) {

    fun showChatContextMenu(chat: Chat, position: DpOffset) {
        deps.updateUiState(deps.uiState.value.copy(chatContextMenu = ChatContextMenuState(chat, position)))
    }

    fun hideChatContextMenu() {
        deps.updateUiState(deps.uiState.value.copy(chatContextMenu = null))
    }

    fun showRenameDialog(chat: Chat) {
        deps.updateUiState(deps.uiState.value.copy(showRenameDialog = chat, chatContextMenu = null))
    }

    fun hideRenameDialog() {
        deps.updateUiState(deps.uiState.value.copy(showRenameDialog = null))
    }

    fun renameChat(chat: Chat, newName: String) {
        if (newName.isBlank()) return
        deps.scope.launch {
            updateChatPreviewName(chat.chat_id, newName.trim())
            hideRenameDialog()
        }
    }

    fun showDeleteConfirmation(chat: Chat) {
        deps.updateUiState(deps.uiState.value.copy(showDeleteConfirmation = chat, chatContextMenu = null))
    }

    fun hideDeleteConfirmation() {
        deps.updateUiState(deps.uiState.value.copy(showDeleteConfirmation = null))
    }

    fun deleteChat(chat: Chat) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val chatHistory = deps.repository.loadChatHistory(currentUser)
            val updatedChats = chatHistory.chat_history.filter { it.chat_id != chat.chat_id }
            val updatedHistory = chatHistory.copy(chat_history = updatedChats)
            deps.repository.saveChatHistory(updatedHistory)
            val finalChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
            val newCurrentChat = if (deps.uiState.value.currentChat?.chat_id == chat.chat_id) {
                finalChatHistory.lastOrNull()
            } else {
                deps.uiState.value.currentChat
            }
            deps.updateUiState(deps.uiState.value.copy(
                chatHistory = finalChatHistory,
                groups = chatHistory.groups,
                currentChat = newCurrentChat,
                showDeleteConfirmation = null
            ))
        }
    }

    fun showDeleteChatConfirmation() {
        deps.updateUiState(deps.uiState.value.copy(showDeleteChatConfirmation = deps.uiState.value.currentChat))
    }

    fun hideDeleteChatConfirmation() {
        deps.updateUiState(deps.uiState.value.copy(showDeleteChatConfirmation = null))
    }

    fun deleteCurrentChat() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val currentUser = deps.appSettings.value.current_user
        deps.scope.launch {
            val chatHistory = deps.repository.loadChatHistory(currentUser)
            val updatedChats = chatHistory.chat_history.filter { it.chat_id != currentChat.chat_id }
            val updatedHistory = chatHistory.copy(chat_history = updatedChats)
            deps.repository.saveChatHistory(updatedHistory)
            val finalChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
            deps.updateUiState(deps.uiState.value.copy(
                chatHistory = finalChatHistory,
                currentChat = null,
                systemPrompt = "",
                showDeleteChatConfirmation = null
            ))
            navigateToScreen(Screen.ChatHistory)
        }
    }

    fun toggleWebSearch() {
        val currentSupport = deps.uiState.value.webSearchSupport
        val currentProvider = deps.uiState.value.currentProvider?.provider ?: ""
        val currentModel = deps.uiState.value.currentModel
        when (currentSupport) {
            WebSearchSupport.REQUIRED -> {
                deps.updateUiState(deps.uiState.value.copy(
                    snackbarMessage = "Cannot disable web search for model $currentModel via provider $currentProvider"
                ))
            }
            WebSearchSupport.OPTIONAL -> {
                deps.updateUiState(deps.uiState.value.copy(webSearchEnabled = !deps.uiState.value.webSearchEnabled))
            }
            WebSearchSupport.UNSUPPORTED -> { /* do nothing */ }
        }
    }
}
