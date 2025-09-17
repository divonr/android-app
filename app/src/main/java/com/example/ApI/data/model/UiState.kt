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
    val groups: List<ChatGroup> = emptyList(),
    val currentGroup: ChatGroup? = null,
    val expandedGroups: Set<String> = emptySet(),
    val chatContextMenu: ChatContextMenuState? = null,
    val groupContextMenu: GroupContextMenuState? = null,
    val showDeleteConfirmation: Chat? = null,
    val showRenameDialog: Chat? = null,
    val showGroupRenameDialog: ChatGroup? = null,
    val showGroupDialog: Boolean = false,
    val showDeleteChatConfirmation: Chat? = null,
    val showDeleteGroupConfirmation: ChatGroup? = null,
    val pendingChatForGroup: Chat? = null,
    val snackbarMessage: String? = null,
    val editingMessage: Message? = null,
    val isEditMode: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val webSearchSupport: WebSearchSupport = WebSearchSupport.UNSUPPORTED,
    val showReplyButton: Boolean = false,
    val systemPromptOverrideEnabled: Boolean = false,
    val searchMode: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val searchContext: SearchResult? = null // Context for jumping to search results
)

data class ChatContextMenuState(
    val chat: Chat,
    val position: androidx.compose.ui.unit.DpOffset
)

data class GroupContextMenuState(
    val group: ChatGroup,
    val position: androidx.compose.ui.unit.DpOffset
)

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val localPath: String? = null
)

data class SearchResult(
    val chat: Chat,
    val searchQuery: String,
    val matchType: SearchMatchType,
    val messageIndex: Int = -1, // Index of the message that matched (for content matches)
    val highlightRanges: List<IntRange> = emptyList() // Character ranges to highlight
)

enum class SearchMatchType {
    TITLE,          // Match found in chat title
    CONTENT,        // Match found in message content
    FILE_NAME       // Match found in attachment file name
}

data class ApiKeysUiState(
    val apiKeys: List<ApiKey> = emptyList(),
    val showAddKeyDialog: Boolean = false,
    val isLoading: Boolean = false
)

sealed class Screen {
    object ChatHistory : Screen()
    object Chat : Screen()
    object ApiKeys : Screen()
    object UserSettings : Screen()
    object ChildLock : Screen()
    object Integrations : Screen()
    data class Group(val groupId: String) : Screen()
}
