package com.example.ApI.data.model

import android.net.Uri

enum class WebSearchSupport {
    UNSUPPORTED,
    OPTIONAL, 
    REQUIRED
}

data class ChatUiState(
    val currentMessage: String = "",
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val streamingText: String = "",
    val showProviderSelector: Boolean = false,
    val showModelSelector: Boolean = false,
    val showSystemPromptDialog: Boolean = false,
    val showChatHistory: Boolean = false,
    val showFileSelection: Boolean = false,
    val selectedFiles: List<SelectedFile> = emptyList(),
    val currentChat: Chat? = null,
    val availableProviders: List<Provider> = emptyList(),
    val currentProvider: Provider? = null,
    val currentModel: String = "gpt-4o",
    val systemPrompt: String = "",
    val chatHistory: List<Chat> = emptyList(),
    val chatContextMenu: ChatContextMenuState? = null,
    val showDeleteConfirmation: Chat? = null,
    val showRenameDialog: Chat? = null,
    val snackbarMessage: String? = null,
    val editingMessage: Message? = null,
    val isEditMode: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val webSearchSupport: WebSearchSupport = WebSearchSupport.UNSUPPORTED,
    val showReplyButton: Boolean = false
)

data class ChatContextMenuState(
    val chat: Chat,
    val position: androidx.compose.ui.unit.DpOffset
)

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val localPath: String? = null
)

data class ApiKeysUiState(
    val apiKeys: List<ApiKey> = emptyList(),
    val showAddKeyDialog: Boolean = false,
    val isLoading: Boolean = false
)

sealed class Screen {
    object Chat : Screen()
    object ApiKeys : Screen()
    object UserSettings : Screen()
}
