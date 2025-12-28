package com.example.ApI.ui.managers.streaming

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.TitleGenerationSettings
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch

/**
 * Manages automatic and manual title generation for chats.
 * Handles AI-powered title generation and title settings.
 * Extracted from ChatViewModel to reduce complexity.
 */
class TitleGenerationManager(
    private val deps: ManagerDependencies,
    private val updateAppSettings: (AppSettings) -> Unit
) {

    /**
     * Update title generation settings.
     */
    fun updateTitleGenerationSettings(newSettings: TitleGenerationSettings) {
        val currentSettings = deps.appSettings.value
        val updatedSettings = currentSettings.copy(titleGenerationSettings = newSettings)

        deps.repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)
    }

    /**
     * Get available providers for title generation based on active API keys.
     */
    fun getAvailableProvidersForTitleGeneration(): List<String> {
        val currentUser = deps.appSettings.value.current_user
        val apiKeys = deps.repository.loadApiKeys(currentUser)
            .filter { it.isActive }
            .map { it.provider }

        return listOf("openai", "anthropic", "google", "poe", "cohere", "openrouter").filter { provider ->
            apiKeys.contains(provider)
        }
    }

    /**
     * Handle automatic title generation after a response.
     * Generates title after first response, and optionally after third response.
     */
    suspend fun handleTitleGeneration(chat: Chat) {
        val titleGenerationSettings = deps.appSettings.value.titleGenerationSettings

        // Skip if title generation is disabled
        if (!titleGenerationSettings.enabled) return

        // Count assistant messages in this conversation
        val assistantMessages = chat.messages.filter { it.role == "assistant" }
        val assistantMessageCount = assistantMessages.size

        val shouldGenerateTitle = when {
            // First model response - always generate title
            assistantMessageCount == 1 -> true
            // Third model response and update on extension is enabled
            assistantMessageCount == 3 && titleGenerationSettings.updateOnExtension -> true
            else -> false
        }

        if (!shouldGenerateTitle) return

        try {
            val currentUser = deps.appSettings.value.current_user
            val providerToUse = if (titleGenerationSettings.provider == "auto") null else titleGenerationSettings.provider

            // Generate title using our helper function
            val generatedTitle = deps.repository.generateConversationTitle(
                username = currentUser,
                conversationId = chat.chat_id,
                provider = providerToUse
            )

            // Update the conversation title if we got a valid response
            if (generatedTitle.isNotBlank() && generatedTitle != "שיחה חדשה") {
                updateChatPreviewName(chat.chat_id, generatedTitle)
            }

        } catch (e: Exception) {
            // If title generation fails, just continue without updating the title
            println("Title generation failed: ${e.message}")
        }
    }

    /**
     * Update the preview name (title) of a chat.
     */
    suspend fun updateChatPreviewName(chatId: String, newTitle: String) {
        val currentUser = deps.appSettings.value.current_user
        val chatHistory = deps.repository.loadChatHistory(currentUser)

        // Update the chat with the new preview name
        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.chat_id == chatId) {
                chat.copy(preview_name = newTitle)
            } else {
                chat
            }
        }

        val updatedHistory = chatHistory.copy(chat_history = updatedChats)
        deps.repository.saveChatHistory(updatedHistory)

        // Update UI state
        val finalChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
        val updatedCurrentChat = finalChatHistory.find { it.chat_id == chatId }

        deps.updateUiState(
            deps.uiState.value.copy(
                currentChat = updatedCurrentChat,
                chatHistory = finalChatHistory
            )
        )
    }

    /**
     * Rename a chat using AI-generated title.
     * Called from context menu.
     */
    fun renameChatWithAI(chat: Chat) {
        // Add chat to renaming set to show loading indicator
        deps.updateUiState(
            deps.uiState.value.copy(
                renamingChatIds = deps.uiState.value.renamingChatIds + chat.chat_id
            )
        )

        deps.scope.launch {
            try {
                val currentUser = deps.appSettings.value.current_user
                val titleGenerationSettings = deps.appSettings.value.titleGenerationSettings
                val providerToUse = if (titleGenerationSettings.provider == "auto") null else titleGenerationSettings.provider

                // Generate title using our helper function
                val generatedTitle = deps.repository.generateConversationTitle(
                    username = currentUser,
                    conversationId = chat.chat_id,
                    provider = providerToUse
                )

                // Update the conversation title if we got a valid response
                if (generatedTitle.isNotBlank() && generatedTitle != "שיחה חדשה") {
                    updateChatPreviewName(chat.chat_id, generatedTitle)
                }

            } catch (e: Exception) {
                // If title generation fails, just log the error
                println("AI rename failed: ${e.message}")
            } finally {
                // Remove chat from renaming set
                deps.updateUiState(
                    deps.uiState.value.copy(
                        renamingChatIds = deps.uiState.value.renamingChatIds - chat.chat_id
                    )
                )
            }
        }
    }
}
