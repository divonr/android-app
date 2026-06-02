package com.example.ApI.ui.managers.streaming

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.TitleGenerationSettings
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch

/**
 * Manages automatic and manual title generation for chats.
 */
class TitleGenerationManager(
    private val deps: ManagerDependencies,
    private val updateAppSettings: (AppSettings) -> Unit
) {

    fun updateTitleGenerationSettings(newSettings: TitleGenerationSettings) {
        val currentSettings = deps.appSettings.value
        val updatedSettings = currentSettings.copy(titleGenerationSettings = newSettings)
        deps.repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)
    }

    fun getAvailableProvidersForTitleGeneration(): List<String> {
        val currentUser = deps.appSettings.value.current_user
        val apiKeys = deps.repository.loadApiKeys(currentUser).filter { it.isActive }.map { it.provider }
        return listOf("openai", "anthropic", "google", "poe", "cohere", "openrouter").filter { apiKeys.contains(it) }
    }

    suspend fun handleTitleGeneration(chat: Chat) {
        val titleGenerationSettings = deps.appSettings.value.titleGenerationSettings
        if (!titleGenerationSettings.enabled) return

        val assistantMessages = chat.messages.filter { it.role == "assistant" }
        val assistantMessageCount = assistantMessages.size

        val shouldGenerateTitle = when {
            assistantMessageCount == 1 -> true
            assistantMessageCount == 3 && titleGenerationSettings.updateOnExtension -> true
            else -> false
        }

        if (!shouldGenerateTitle) return

        try {
            val currentUser = deps.appSettings.value.current_user
            val providerToUse = if (titleGenerationSettings.provider == "auto") null else titleGenerationSettings.provider
            val generatedTitle = deps.repository.generateConversationTitle(
                username = currentUser,
                conversationId = chat.chat_id,
                provider = providerToUse
            )
            if (generatedTitle.isNotBlank() && generatedTitle != "New chat") {
                updateChatPreviewName(chat.chat_id, generatedTitle)
            }
        } catch (e: Exception) {
            println("Title generation failed: ${e.message}")
        }
    }

    suspend fun updateChatPreviewName(chatId: String, newTitle: String) {
        val currentUser = deps.appSettings.value.current_user
        val chatHistory = deps.repository.loadChatHistory(currentUser)
        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.chat_id == chatId) chat.copy(preview_name = newTitle) else chat
        }
        deps.repository.saveChatHistory(chatHistory.copy(chat_history = updatedChats))
        val finalChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
        val updatedCurrentChat = finalChatHistory.find { it.chat_id == chatId }
        deps.updateUiState(deps.uiState.value.copy(currentChat = updatedCurrentChat, chatHistory = finalChatHistory))
    }

    fun renameChatWithAI(chat: Chat) {
        deps.updateUiState(deps.uiState.value.copy(renamingChatIds = deps.uiState.value.renamingChatIds + chat.chat_id))
        deps.scope.launch {
            try {
                val currentUser = deps.appSettings.value.current_user
                val titleGenerationSettings = deps.appSettings.value.titleGenerationSettings
                val providerToUse = if (titleGenerationSettings.provider == "auto") null else titleGenerationSettings.provider
                val generatedTitle = deps.repository.generateConversationTitle(
                    username = currentUser,
                    conversationId = chat.chat_id,
                    provider = providerToUse
                )
                if (generatedTitle.isNotBlank() && generatedTitle != "New chat") {
                    updateChatPreviewName(chat.chat_id, generatedTitle)
                }
            } catch (e: Exception) {
                println("AI rename failed: ${e.message}")
            } finally {
                deps.updateUiState(deps.uiState.value.copy(renamingChatIds = deps.uiState.value.renamingChatIds - chat.chat_id))
            }
        }
    }
}
