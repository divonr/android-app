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
        val currentSettings = deps.repository.loadAppSettings()
        val updatedSettings = deps.repository.updateAppSettings { fresh ->
            fresh.copy(titleGenerationSettings = newSettings)
        }
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
        // Locked load-modify-save
        val finalChatHistory = deps.repository.updateChatHistory(currentUser) { history ->
            history.copy(chat_history = history.chat_history.map { chat ->
                if (chat.chat_id == chatId) chat.copy(preview_name = newTitle) else chat
            })
        }.chat_history
        // The current chat only changes if it is the renamed one
        val state = deps.uiState.value
        val updatedCurrentChat = if (state.currentChat?.chat_id == chatId) {
            finalChatHistory.find { it.chat_id == chatId } ?: state.currentChat
        } else state.currentChat
        deps.updateUiState(state.copy(currentChat = updatedCurrentChat, chatHistory = finalChatHistory))
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
