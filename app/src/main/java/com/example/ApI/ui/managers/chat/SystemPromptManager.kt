package com.example.ApI.ui.managers.chat

import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.SkillMetadata
import com.example.ApI.ui.managers.ManagerDependencies

/**
 * Manages system prompt functionality for chats and projects.
 * Handles system prompt updates, overrides, project prompt merging,
 * and skills catalog injection.
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
     * Merges project system prompt with chat system prompt if applicable,
     * then appends the skills catalog (Level 1 metadata) if any skills are enabled.
     */
    fun getEffectiveSystemPrompt(): String {
        val currentChat = deps.uiState.value.currentChat ?: return buildSkillsCatalog("")
        val projectGroup = getCurrentChatProjectGroup()

        val userPrompt = when {
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

        return buildSkillsCatalog(userPrompt)
    }

    /**
     * Build the skills catalog section and append it to the user's system prompt.
     * The catalog lists all enabled skills with their name and description (Level 1).
     * The LLM uses read_skill / read_skill_file / write_skill_file / edit_skill_file
     * tools to interact with skill content.
     */
    private fun buildSkillsCatalog(userPrompt: String): String {
        val enabledSkills = deps.repository.getEnabledSkillsMetadata()
        if (enabledSkills.isEmpty()) return userPrompt

        val catalog = buildString {
            appendLine("\n\n## Skills")
            appendLine("You have ${enabledSkills.size} skill(s) available. When a skill is relevant, call `read_skill` with its name to load full instructions.")
            appendLine("You can also read additional skill files with `read_skill_file`, update files with `write_skill_file`, or apply targeted edits with `edit_skill_file`.")
            appendLine("If you notice improvements while using a skill, edit it so future conversations benefit.")
            appendLine()
            for ((dirName, metadata) in enabledSkills) {
                appendLine("- **${metadata.name}**: ${metadata.description}")
            }
        }

        return if (userPrompt.isBlank()) {
            catalog.trimStart()
        } else {
            userPrompt + catalog
        }
    }
}
