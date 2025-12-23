package com.example.ApI.ui.managers.integration

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.ChatUiState
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.tools.GroupConversationsTool
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages tool registration, specification retrieval, and execution.
 * Handles function calling tools for AI interactions.
 * Extracted from ChatViewModel to reduce complexity.
 */
class ToolManager(
    private val repository: DataRepository,
    private val appSettings: StateFlow<AppSettings>,
    private val uiState: StateFlow<ChatUiState>,
    private val updateAppSettings: (AppSettings) -> Unit
) {

    /**
     * Get the list of enabled tool specifications for the current provider.
     * Handles special tools like group_conversations that need context.
     */
    fun getEnabledToolSpecifications(): List<ToolSpecification> {
        val enabledToolIds = appSettings.value.enabledTools
        val toolRegistry = ToolRegistry.getInstance()
        val currentProvider = uiState.value.currentProvider?.provider ?: "openai"

        val specifications = mutableListOf<ToolSpecification>()

        enabledToolIds.forEach { toolId ->
            if (toolId == "get_current_group_conversations") {
                return@forEach
            }

            toolRegistry.getTool(toolId)?.getSpecification(currentProvider)?.let {
                specifications.add(it)
            }
        }

        if (enabledToolIds.contains("get_current_group_conversations")) {
            val currentChat = uiState.value.currentChat
            val groups = uiState.value.groups
            val currentUser = appSettings.value.current_user

            currentChat?.group?.let { groupId ->
                groups.find { it.group_id == groupId }?.let { group ->
                    val groupConversationsTool = GroupConversationsTool(
                        repository = repository,
                        username = currentUser,
                        currentChatId = currentChat.chat_id,
                        groupId = groupId,
                        groupName = group.group_name
                    )

                    specifications.add(groupConversationsTool.getSpecification(currentProvider))
                }
            }
        }

        return specifications
    }

    /**
     * Execute a tool call and return the result.
     * Handles special tools like group_conversations that need dynamic context.
     */
    suspend fun executeToolCall(toolCall: ToolCall): ToolExecutionResult {
        if (toolCall.toolId == "get_current_group_conversations") {
            val currentChat = uiState.value.currentChat
            val groups = uiState.value.groups
            val currentUser = appSettings.value.current_user

            return currentChat?.group?.let { groupId ->
                groups.find { it.group_id == groupId }?.let { group ->
                    val groupConversationsTool = GroupConversationsTool(
                        repository = repository,
                        username = currentUser,
                        currentChatId = currentChat.chat_id,
                        groupId = groupId,
                        groupName = group.group_name
                    )
                    try {
                        groupConversationsTool.execute(toolCall.parameters)
                    } catch (e: Exception) {
                        ToolExecutionResult.Error(
                            "Failed to execute group conversations tool: ${e.message}"
                        )
                    }
                }
            } ?: ToolExecutionResult.Error(
                "Group conversations tool can only be used in a group chat"
            )
        }

        val enabledToolIds = getEnabledToolSpecifications().map { it.name }
        return ToolRegistry.getInstance().executeTool(toolCall, enabledToolIds)
    }

    /**
     * Enable a tool by its ID.
     */
    fun enableTool(toolId: String) {
        val currentSettings = appSettings.value
        if (!currentSettings.enabledTools.contains(toolId)) {
            val updatedSettings = currentSettings.copy(
                enabledTools = currentSettings.enabledTools + toolId
            )
            repository.saveAppSettings(updatedSettings)
            updateAppSettings(updatedSettings)
        }
    }

    /**
     * Disable a tool by its ID.
     */
    fun disableTool(toolId: String) {
        val currentSettings = appSettings.value
        val updatedSettings = currentSettings.copy(
            enabledTools = currentSettings.enabledTools - toolId
        )
        repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)
    }
}
