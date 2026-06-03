package com.example.ApI.data.model

import android.net.Uri
import androidx.compose.ui.unit.DpOffset

data class ChatUiState(
    val currentMessage: String = "",
    val loadingChatIds: Set<String> = emptySet(),
    val streamingChatIds: Set<String> = emptySet(),
    val streamingTextByChat: Map<String, String> = emptyMap(),
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
    val isShareLinkLoading: Boolean = false,
    val isShareLinkActive: Boolean = false,
    val showShareLinkMenu: Boolean = false,
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
    val searchContext: SearchResult? = null,
    val executingToolCall: ExecutingToolInfo? = null,
    val textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    val renamingChatIds: Set<String> = emptySet(),
    val pendingChatImport: PendingChatImport? = null,
    val showThinkingBudgetPopup: Boolean = false,
    val thinkingBudgetPopupAnchor: DpOffset = DpOffset.Zero,
    val thinkingBudgetValue: ThinkingBudgetValue = ThinkingBudgetValue.None,
    val showTemperaturePopup: Boolean = false,
    val temperatureValue: Float? = null,
    val showToolToggleDropdown: Boolean = false,
    val excludedToolIds: List<String> = emptyList()
) {
    fun isLoadingChat(chatId: String): Boolean = chatId in loadingChatIds
    fun isStreamingChat(chatId: String): Boolean = chatId in streamingChatIds
    fun getStreamingText(chatId: String): String = streamingTextByChat[chatId] ?: ""
    fun isThinking(chatId: String): Boolean = chatId in thinkingChatIds
    fun getThinkingStartTime(chatId: String): Long? = thinkingStartTimeByChat[chatId]
    fun getStreamingThoughts(chatId: String): String = streamingThoughtsTextByChat[chatId] ?: ""
    fun getCompletedThinkingDuration(chatId: String): Float? = completedThinkingDurationByChat[chatId]
    val isLoading: Boolean get() = currentChat?.chat_id?.let { it in loadingChatIds } ?: false
    val isStreaming: Boolean get() = currentChat?.chat_id?.let { it in streamingChatIds } ?: false
    val streamingText: String get() = currentChat?.chat_id?.let { streamingTextByChat[it] } ?: ""

    fun getThinkingBudgetType(): ThinkingBudgetType {
        val provider = currentProvider?.provider ?: return ThinkingBudgetType.InDevelopment
        val modelConfig = currentProvider?.models?.find { it.name == currentModel }?.thinkingConfig
        return ThinkingBudgetConfig.getThinkingBudgetType(provider, currentModel, modelConfig)
    }

    fun getTemperatureConfig(): TemperatureConfig? {
        val provider = currentProvider?.provider ?: return null
        val modelConfig = currentProvider?.models?.find { it.name == currentModel }?.temperatureConfig
        return TemperatureConfigUtils.getTemperatureConfig(provider, currentModel, modelConfig)
    }
}

data class ChatContextMenuState(
    val chat: Chat,
    val position: DpOffset
)

data class GroupContextMenuState(
    val group: ChatGroup,
    val position: DpOffset
)

/** Desktop: uri is nullable since we use file paths, not Android content URIs. */
data class SelectedFile(
    val uri: Uri?,
    val name: String,
    val mimeType: String,
    val localPath: String? = null
)

/** Desktop: uri is nullable since we use file paths, not Android content URIs. */
data class PendingChatImport(
    val uri: Uri?,
    val fileName: String,
    val mimeType: String,
    val jsonContent: String
)

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
    object Logs : Screen()
    object Skills : Screen()
    data class Group(val groupId: String) : Screen()
    data class SkillEditor(val skillDirectoryName: String) : Screen()
}
