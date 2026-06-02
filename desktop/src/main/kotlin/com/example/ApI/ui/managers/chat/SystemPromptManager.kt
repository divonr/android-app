package com.example.ApI.ui.managers.chat

import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.SkillMetadata
import com.example.ApI.ui.managers.ManagerDependencies

/**
 * Manages system prompt functionality for chats and projects.
 */
class SystemPromptManager(
    private val deps: ManagerDependencies
) {

    fun updateSystemPrompt(prompt: String) {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat

        if (currentChat != null) {
            deps.repository.updateChatSystemPrompt(currentUser, currentChat.chat_id, prompt)
            val updatedChat = currentChat.copy(systemPrompt = prompt)
            val updatedChatHistory = deps.uiState.value.chatHistory.map { chat ->
                if (chat.chat_id == currentChat.chat_id) updatedChat else chat
            }
            deps.updateUiState(deps.uiState.value.copy(
                currentChat = updatedChat,
                systemPrompt = prompt,
                chatHistory = updatedChatHistory,
                showSystemPromptDialog = false
            ))
        } else {
            val newChat = deps.repository.createNewChat(currentUser, "New chat", prompt)
            val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history
            deps.updateUiState(deps.uiState.value.copy(
                currentChat = newChat,
                systemPrompt = prompt,
                chatHistory = updatedChatHistory,
                showSystemPromptDialog = false
            ))
        }
    }

    fun showSystemPromptDialog() {
        deps.updateUiState(deps.uiState.value.copy(showSystemPromptDialog = true))
    }

    fun hideSystemPromptDialog() {
        deps.updateUiState(deps.uiState.value.copy(showSystemPromptDialog = false))
    }

    fun toggleSystemPromptOverride() {
        deps.updateUiState(deps.uiState.value.copy(systemPromptOverrideEnabled = !deps.uiState.value.systemPromptOverrideEnabled))
    }

    fun setSystemPromptOverride(enabled: Boolean) {
        deps.updateUiState(deps.uiState.value.copy(systemPromptOverrideEnabled = enabled))
    }

    fun getCurrentChatProjectGroup(): ChatGroup? {
        val currentChat = deps.uiState.value.currentChat
        val groups = deps.uiState.value.groups
        return currentChat?.group?.let { groupId ->
            groups.find { it.group_id == groupId && it.is_project }
        }
    }

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

    private fun buildSkillsCatalog(userPrompt: String): String {
        val enabledSkills = deps.repository.getEnabledSkillsMetadata()
        if (enabledSkills.isEmpty()) return userPrompt

        val catalog = buildString {
            appendLine("\n\n## Skills")
            appendLine("You have ${enabledSkills.size} skill(s) available. When a skill is relevant, call `read_skill` with its name to load full instructions.")
            appendLine("You can also read additional skill files with `read_skill_file`, update files with `write_skill_file`, or apply targeted edits with `edit_skill_file`.")
            appendLine("If you notice improvements while using a skill, edit it so future conversations benefit.")
            appendLine()
            for ((_, metadata) in enabledSkills) {
                appendLine("- **${metadata.name}**: ${metadata.description}")
            }
        }

        return if (userPrompt.isBlank()) catalog.trimStart() else userPrompt + catalog
    }
}
