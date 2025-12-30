package com.example.ApI.data.model

import android.net.Uri
import androidx.compose.ui.unit.DpOffset

enum class WebSearchSupport {
    UNSUPPORTED,
    OPTIONAL, 
    REQUIRED
}

enum class TextDirectionMode {
    AUTO,   // Automatically infer based on first character
    RTL,    // Force right-to-left
    LTR     // Force left-to-right
}

data class ChatUiState(
    val currentMessage: String = "",
    // Per-chat loading/streaming state (replaces global flags)
    val loadingChatIds: Set<String> = emptySet(),
    val streamingChatIds: Set<String> = emptySet(),
    val streamingTextByChat: Map<String, String> = emptyMap(),
    // Per-chat thinking state
    val thinkingChatIds: Set<String> = emptySet(),
    val thinkingStartTimeByChat: Map<String, Long> = emptyMap(),
    val streamingThoughtsTextByChat: Map<String, String> = emptyMap(),
    val completedThinkingDurationByChat: Map<String, Float> = emptyMap(),
    val showModelSelector: Boolean = false,
    val showSystemPromptDialog: Boolean = false,
    val showFileSelection: Boolean = false,
    val quickSettingsExpanded: Boolean = false,
    val showChatExportDialog: Boolean = false,
    val chatExportJson: String = "",
    val isChatExportEditable: Boolean = false,
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
    val searchContext: SearchResult? = null, // Context for jumping to search results
    val executingToolCall: ExecutingToolInfo? = null, // Track currently executing tool
    val textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO, // Text direction mode for message bubbles
    val renamingChatIds: Set<String> = emptySet(), // Track chats currently being renamed with AI
    val pendingChatImport: PendingChatImport? = null, // Pending chat JSON file that needs user decision
    // Thinking budget state
    val showThinkingBudgetPopup: Boolean = false,
    val thinkingBudgetPopupAnchor: DpOffset = DpOffset.Zero, // Position anchor for the popup
    val thinkingBudgetValue: ThinkingBudgetValue = ThinkingBudgetValue.None,
    // Temperature state
    val showTemperaturePopup: Boolean = false,
    val temperatureValue: Float? = null // null means use API default (don't send parameter)
) {
    // Helper functions for per-chat state checks
    fun isLoadingChat(chatId: String): Boolean = chatId in loadingChatIds
    fun isStreamingChat(chatId: String): Boolean = chatId in streamingChatIds
    fun getStreamingText(chatId: String): String = streamingTextByChat[chatId] ?: ""

    // Helper functions for thinking state
    fun isThinking(chatId: String): Boolean = chatId in thinkingChatIds
    fun getThinkingStartTime(chatId: String): Long? = thinkingStartTimeByChat[chatId]
    fun getStreamingThoughts(chatId: String): String = streamingThoughtsTextByChat[chatId] ?: ""
    fun getCompletedThinkingDuration(chatId: String): Float? = completedThinkingDurationByChat[chatId]

    // Backward compatibility computed properties for current chat
    val isLoading: Boolean get() = currentChat?.chat_id?.let { it in loadingChatIds } ?: false
    val isStreaming: Boolean get() = currentChat?.chat_id?.let { it in streamingChatIds } ?: false
    val streamingText: String get() = currentChat?.chat_id?.let { streamingTextByChat[it] } ?: ""

    // Helper function for thinking budget type based on current provider/model
    fun getThinkingBudgetType(): ThinkingBudgetType {
        val provider = currentProvider?.provider ?: return ThinkingBudgetType.InDevelopment

        // Try to find the model's thinking config from the provider's model list
        val modelConfig = currentProvider?.models
            ?.find { it.name == currentModel }
            ?.thinkingConfig

        return ThinkingBudgetConfig.getThinkingBudgetType(provider, currentModel, modelConfig)
    }

    // Helper function for temperature config based on current provider/model
    fun getTemperatureConfig(): TemperatureConfig? {
        val provider = currentProvider?.provider ?: return null

        // Try to find the model's temperature config from the provider's model list
        val modelConfig = currentProvider?.models
            ?.find { it.name == currentModel }
            ?.temperatureConfig

        return TemperatureConfigUtils.getTemperatureConfig(provider, currentModel, modelConfig)
    }
}

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

data class ExecutingToolInfo(
    val toolId: String,
    val toolName: String,
    val startTime: String // ISO 8601 format
)

data class PendingChatImport(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val jsonContent: String
)

/**
 * Information about branch variants for a specific node
 * Used by UI to display branch navigation controls
 */
data class BranchInfo(
    val nodeId: String,
    val currentVariantIndex: Int,
    val totalVariants: Int,
    val currentVariantId: String
) {
    val hasPrevious: Boolean get() = currentVariantIndex > 0
    val hasNext: Boolean get() = currentVariantIndex < totalVariants - 1
    val displayText: String get() = "${currentVariantIndex + 1}/$totalVariants"
}

data class ApiKeysUiState(
    val apiKeys: List<ApiKey> = emptyList(),
    val showAddKeyDialog: Boolean = false,
    val isLoading: Boolean = false
)

sealed class Screen {
    object Welcome : Screen()
    object ChatHistory : Screen()
    object Chat : Screen()
    object ApiKeys : Screen()
    object UserSettings : Screen()
    object ChildLock : Screen()
    object Integrations : Screen()
    data class Group(val groupId: String) : Screen()
}


