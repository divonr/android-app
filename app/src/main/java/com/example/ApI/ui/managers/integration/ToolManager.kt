package com.example.ApI.ui.managers.integration

import com.example.ApI.data.model.AppSettings
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.tools.GroupConversationsTool
import com.example.ApI.tools.PythonInterpreterTool
import com.example.ApI.ui.managers.ManagerDependencies

/**
 * Manages tool registration, specification retrieval, and execution.
 * Handles function calling tools for AI interactions.
 * Extracted from ChatViewModel to reduce complexity.
 */
class ToolManager(
    private val deps: ManagerDependencies,
    private val updateAppSettings: (AppSettings) -> Unit
) {

    /**
     * Get the list of enabled tool specifications for the current provider.
     * Handles special tools like group_conversations that need context.
     * Filters out tools excluded via the chat screen shortcut.
     */
    fun getEnabledToolSpecifications(): List<ToolSpecification> {
        val baseEnabledTools = deps.appSettings.value.enabledTools.toMutableList()
        
        // Add group conversations tool if in a group
        if (deps.uiState.value.currentChat?.group != null) {
            if (!baseEnabledTools.contains("get_current_group_conversations")) {
                baseEnabledTools.add("get_current_group_conversations")
            }
        }

        val excludedTools = deps.uiState.value.excludedToolIds

        // Final list of enabled tools = base enabled tools - excluded tools
        val enabledToolIds = baseEnabledTools.filter { it !in excludedTools }

        val toolRegistry = ToolRegistry.getInstance()
        val currentProvider = deps.uiState.value.currentProvider?.provider ?: "openai"

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
            val currentChat = deps.uiState.value.currentChat
            val groups = deps.uiState.value.groups
            val currentUser = deps.appSettings.value.current_user

            currentChat?.group?.let { groupId ->
                groups.find { it.group_id == groupId }?.let { group ->
                    val groupConversationsTool = GroupConversationsTool(
                        repository = deps.repository,
                        username = currentUser,
                        currentChatId = currentChat.chat_id,
                        groupId = groupId,
                        groupName = group.group_name
                    )

                    specifications.add(groupConversationsTool.getSpecification(currentProvider))
                }
            }
        }

        if (enabledToolIds.contains(ToolRegistry.PYTHON_INTERPRETER)) {
            val currentChat = deps.uiState.value.currentChat
            val currentGroup = currentChat?.group?.let { groupId ->
                deps.uiState.value.groups.find { it.group_id == groupId }
            }

            val pythonTool = PythonInterpreterTool(
                context = deps.context,
                currentChat = currentChat,
                currentGroup = currentGroup
            )
            specifications.add(pythonTool.getSpecification(currentProvider))
        }

        return specifications
    }

    /**
     * Execute a tool call and return the result.
     * Handles special tools like group_conversations that need dynamic context.
     */
    suspend fun executeToolCall(toolCall: ToolCall): ToolExecutionResult {
        if (toolCall.toolId == "get_current_group_conversations") {
            val currentChat = deps.uiState.value.currentChat
            val groups = deps.uiState.value.groups
            val currentUser = deps.appSettings.value.current_user

            return currentChat?.group?.let { groupId ->
                groups.find { it.group_id == groupId }?.let { group ->
                    val groupConversationsTool = GroupConversationsTool(
                        repository = deps.repository,
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

        if (toolCall.toolId == ToolRegistry.PYTHON_INTERPRETER) {
            val currentChat = deps.uiState.value.currentChat
            val currentGroup = currentChat?.group?.let { groupId ->
                deps.uiState.value.groups.find { it.group_id == groupId }
            }

            val pythonTool = PythonInterpreterTool(
                context = deps.context,
                currentChat = currentChat,
                currentGroup = currentGroup
            )
            return pythonTool.execute(toolCall.parameters)
        }

        val enabledToolIds = getEnabledToolSpecifications().map { it.name }
        return ToolRegistry.getInstance().executeTool(toolCall, enabledToolIds)
    }

    /**
     * Enable a tool by its ID.
     */
    fun enableTool(toolId: String) {
        val currentSettings = deps.appSettings.value
        if (!currentSettings.enabledTools.contains(toolId)) {
            val updatedSettings = currentSettings.copy(
                enabledTools = currentSettings.enabledTools + toolId
            )
            deps.repository.saveAppSettings(updatedSettings)
            updateAppSettings(updatedSettings)
        }
    }

    /**
     * Disable a tool by its ID.
     */
    fun disableTool(toolId: String) {
        val currentSettings = deps.appSettings.value
        val updatedSettings = currentSettings.copy(
            enabledTools = currentSettings.enabledTools - toolId
        )
        deps.repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)
    }
}
