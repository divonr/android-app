package com.example.ApI.ui.managers.chat

import com.example.ApI.data.model.ChatGroup
import com.example.ApI.ui.managers.ManagerDependencies

/**
 * Manages system prompt functionality for chats and projects.
 * Handles system prompt updates, overrides, and project prompt merging.
 * Extracted from ChatViewModel to reduce complexity.
 */
class SystemPromptManager(
    private val deps: ManagerDependencies
) {

    /**
     * Update the system prompt for the current chat.
     * Creates a new chat if no current chat exists.
     */
    fun updateSystemPrompt(prompt: String) {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat

        if (currentChat != null) {
            // Update system prompt for current chat
            deps.repository.updateChatSystemPrompt(currentUser, currentChat.chat_id, prompt)

            // Update the current chat in UI state
            val updatedChat = currentChat.copy(systemPrompt = prompt)
            val updatedChatHistory = deps.uiState.value.chatHistory.map { chat ->
                if (chat.chat_id == currentChat.chat_id) updatedChat else chat
            }

            deps.updateUiState(
                deps.uiState.value.copy(
                    currentChat = updatedChat,
                    systemPrompt = prompt,
                    chatHistory = updatedChatHistory,
                    showSystemPromptDialog = false
                )
            )
        } else {
            // If no current chat, create a new one with the system prompt
            val newChat = deps.repository.createNewChat(currentUser, "שיחה חדשה", prompt)
            val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history

            deps.updateUiState(
                deps.uiState.value.copy(
                    currentChat = newChat,
                    systemPrompt = prompt,
                    chatHistory = updatedChatHistory,
                    showSystemPromptDialog = false
                )
            )
        }
    }

    /**
     * Show the system prompt dialog.
     */
    fun showSystemPromptDialog() {
        deps.updateUiState(deps.uiState.value.copy(showSystemPromptDialog = true))
    }

    /**
     * Hide the system prompt dialog.
     */
    fun hideSystemPromptDialog() {
        deps.updateUiState(deps.uiState.value.copy(showSystemPromptDialog = false))
    }

    /**
     * Toggle the system prompt override setting.
     * When enabled, chat system prompt is appended to project system prompt.
     */
    fun toggleSystemPromptOverride() {
        deps.updateUiState(
            deps.uiState.value.copy(
                systemPromptOverrideEnabled = !deps.uiState.value.systemPromptOverrideEnabled
            )
        )
    }

    /**
     * Set the system prompt override setting explicitly.
     */
    fun setSystemPromptOverride(enabled: Boolean) {
        deps.updateUiState(
            deps.uiState.value.copy(
                systemPromptOverrideEnabled = enabled
            )
        )
    }

    /**
     * Get the project group for the current chat, if any.
     * Returns null if the chat is not in a project group.
     */
    fun getCurrentChatProjectGroup(): ChatGroup? {
        val currentChat = deps.uiState.value.currentChat
        val groups = deps.uiState.value.groups

        return currentChat?.group?.let { groupId ->
            groups.find { it.group_id == groupId && it.is_project }
        }
    }

    /**
     * Get the effective system prompt for the current chat.
     * Merges project system prompt with chat system prompt if applicable.
     */
    fun getEffectiveSystemPrompt(): String {
        val currentChat = deps.uiState.value.currentChat ?: return ""
        val projectGroup = getCurrentChatProjectGroup()

        return when {
            projectGroup != null -> {
                val projectPrompt = projectGroup.system_prompt ?: ""
                val chatPrompt = currentChat.systemPrompt

                when {
                    deps.uiState.value.systemPromptOverrideEnabled && chatPrompt.isNotEmpty() ->
                        "$projectPrompt\n\n$chatPrompt"
                    else -> projectPrompt
                }
            }
            else -> currentChat.systemPrompt
        }
    }
}
