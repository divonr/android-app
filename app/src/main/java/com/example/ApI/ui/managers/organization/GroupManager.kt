package com.example.ApI.ui.managers.organization

import android.net.Uri
import androidx.compose.ui.unit.DpOffset
import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Manages all group-related operations for the ChatViewModel.
 * Extracted from ChatViewModel to reduce complexity and improve maintainability.
 */
class GroupManager(
    private val deps: ManagerDependencies,
    private val navigateToScreen: (Screen) -> Unit
) {

    /**
     * Create a new group with the given name.
     * If there's a pending chat, it will be added to the new group.
     */
    fun createNewGroup(groupName: String) {
        if (groupName.isBlank()) return

        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val newGroup = deps.repository.createNewGroup(currentUser, groupName.trim())

            // If there's a pending chat, add it to the new group
            val pendingChat = deps.uiState.value.pendingChatForGroup
            if (pendingChat != null) {
                deps.repository.addChatToGroup(currentUser, pendingChat.chat_id, newGroup.group_id)
            }

            // Update UI state with new group
            val chatHistory = deps.repository.loadChatHistory(currentUser)
            deps.updateUiState(
                deps.uiState.value.copy(
                    groups = chatHistory.groups,
                    chatHistory = chatHistory.chat_history,
                    expandedGroups = deps.uiState.value.expandedGroups + newGroup.group_id,
                    pendingChatForGroup = null
                )
            )

            hideGroupDialog()
        }
    }

    /**
     * Add a chat to an existing group.
     */
    fun addChatToGroup(chatId: String, groupId: String) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val success = deps.repository.addChatToGroup(currentUser, chatId, groupId)

            if (success) {
                // Update UI state
                val chatHistory = deps.repository.loadChatHistory(currentUser)
                deps.updateUiState(
                    deps.uiState.value.copy(
                        chatHistory = chatHistory.chat_history,
                        groups = chatHistory.groups
                    )
                )

                hideChatContextMenu()
            } else {
                deps.updateUiState(
                    deps.uiState.value.copy(
                        snackbarMessage = "שגיאה בהוספת השיחה לקבוצה"
                    )
                )
            }
        }
    }

    /**
     * Remove a chat from its group.
     */
    fun removeChatFromGroup(chatId: String) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val success = deps.repository.removeChatFromGroup(currentUser, chatId)

            if (success) {
                // Update UI state
                val chatHistory = deps.repository.loadChatHistory(currentUser)
                deps.updateUiState(
                    deps.uiState.value.copy(
                        chatHistory = chatHistory.chat_history,
                        groups = chatHistory.groups
                    )
                )

                hideChatContextMenu()
            }
        }
    }

    /**
     * Toggle the expansion state of a group in the UI.
     */
    fun toggleGroupExpansion(groupId: String) {
        val currentExpanded = deps.uiState.value.expandedGroups
        val newExpanded = if (currentExpanded.contains(groupId)) {
            currentExpanded - groupId
        } else {
            currentExpanded + groupId
        }

        deps.updateUiState(deps.uiState.value.copy(expandedGroups = newExpanded))
    }

    /**
     * Show the group creation/selection dialog.
     * @param chat Optional chat to add to the group after creation
     */
    fun showGroupDialog(chat: Chat? = null) {
        deps.updateUiState(
            deps.uiState.value.copy(
                showGroupDialog = true,
                pendingChatForGroup = chat
            )
        )
    }

    /**
     * Hide the group creation/selection dialog.
     */
    fun hideGroupDialog() {
        deps.updateUiState(
            deps.uiState.value.copy(
                showGroupDialog = false,
                pendingChatForGroup = null
            )
        )
    }

    /**
     * Refresh both chat history and groups from deps.repository.
     */
    fun refreshChatHistoryAndGroups() {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val chatHistory = deps.repository.loadChatHistory(currentUser)
            deps.updateUiState(
                deps.uiState.value.copy(
                    chatHistory = chatHistory.chat_history,
                    groups = chatHistory.groups
                )
            )
        }
    }

    /**
     * Toggle a group's project status (regular group vs project).
     */
    fun toggleGroupProjectStatus(groupId: String) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user

            // Toggle the status stored on disk (not the UI copy, which may be stale after a sync)
            val history = deps.repository.updateChatHistory(currentUser) { h ->
                h.copy(groups = h.groups.map { group ->
                    if (group.group_id == groupId) group.copy(is_project = !group.is_project) else group
                })
            }

            // Update UI state
            showGroupsFrom(history)
        }
    }

    /**
     * Add a file to a project group's attachments.
     */
    fun addFileToProject(groupId: String, uri: Uri, fileName: String, mimeType: String) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user

            try {
                // Copy file to internal storage
                val inputStream: InputStream? = deps.context.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val fileData = stream.readBytes()
                    val localPath = deps.repository.saveFileLocally(fileName, fileData)

                    if (localPath != null) {
                        val attachment = Attachment(
                            local_file_path = localPath,
                            file_name = fileName,
                            mime_type = mimeType
                        )

                        deps.repository.addAttachmentToGroup(currentUser, groupId, attachment)

                        // Update UI state from the stored groups
                        showGroupsFrom(deps.repository.loadChatHistory(currentUser))
                    }
                }
            } catch (e: Exception) {
                deps.updateUiState(
                    deps.uiState.value.copy(
                        snackbarMessage = "שגיאה בהעלאת הקובץ: ${e.message}"
                    )
                )
            }
        }
    }

    /**
     * Remove a file from a project group's attachments.
     */
    fun removeFileFromProject(groupId: String, attachmentIndex: Int) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user

            // Get the attachment before removing it (to delete the file)
            val chatHistory = deps.repository.loadChatHistory(currentUser)
            val group = chatHistory.groups.find { it.group_id == groupId }
            val attachmentToRemove = group?.group_attachments?.getOrNull(attachmentIndex)

            // Remove from JSON registry
            deps.repository.removeAttachmentFromGroup(currentUser, groupId, attachmentIndex)

            // Delete the actual file from internal storage
            attachmentToRemove?.local_file_path?.let { path ->
                deps.repository.deleteFile(path)
            }

            // Update UI state from the stored groups
            showGroupsFrom(deps.repository.loadChatHistory(currentUser))
        }
    }

    /**
     * Navigate to a group's screen and set it as the current group.
     */
    fun navigateToGroup(groupId: String) {
        val group = deps.uiState.value.groups.find { it.group_id == groupId }
        if (group != null) {
            deps.updateUiState(
                deps.uiState.value.copy(
                    currentGroup = group,
                    systemPrompt = group.system_prompt ?: ""
                )
            )
            navigateToScreen(Screen.Group(groupId))
        }
    }

    /**
     * Update a group's system prompt (project instructions).
     */
    fun updateGroupSystemPrompt(systemPrompt: String) {
        val currentGroup = deps.uiState.value.currentGroup ?: return
        val currentUser = deps.appSettings.value.current_user

        // Update the group's system prompt in the stored chat history (locked load-modify-save;
        // never write the UI's copy of the groups, which may predate a sync)
        val updatedGroups = deps.repository.updateChatHistory(currentUser) { history ->
            history.copy(groups = history.groups.map { group ->
                if (group.group_id == currentGroup.group_id) group.copy(system_prompt = systemPrompt) else group
            })
        }.groups

        // Update UI state
        deps.updateUiState(
            deps.uiState.value.copy(
                groups = updatedGroups,
                currentGroup = updatedGroups.find { it.group_id == currentGroup.group_id },
                systemPrompt = systemPrompt,
                showSystemPromptDialog = false
            )
        )
    }

    // ==================== Group Context Menu Functions ====================

    /**
     * Show the deps.context menu for a group.
     */
    fun showGroupContextMenu(group: ChatGroup, position: DpOffset) {
        deps.updateUiState(
            deps.uiState.value.copy(
                groupContextMenu = GroupContextMenuState(group, position)
            )
        )
    }

    /**
     * Hide the group deps.context menu.
     */
    fun hideGroupContextMenu() {
        deps.updateUiState(
            deps.uiState.value.copy(
                groupContextMenu = null
            )
        )
    }

    /**
     * Show the rename dialog for a group.
     */
    fun showGroupRenameDialog(group: ChatGroup) {
        deps.updateUiState(
            deps.uiState.value.copy(
                showGroupRenameDialog = group,
                groupContextMenu = null
            )
        )
    }

    /**
     * Hide the group rename dialog.
     */
    fun hideGroupRenameDialog() {
        deps.updateUiState(
            deps.uiState.value.copy(
                showGroupRenameDialog = null
            )
        )
    }

    /**
     * Rename a group.
     */
    fun renameGroup(group: ChatGroup, newName: String) {
        if (newName.isBlank()) return

        val currentUser = deps.appSettings.value.current_user
        val success = deps.repository.renameGroup(currentUser, group.group_id, newName.trim())

        if (success) {
            // Update local state from the stored groups
            showGroupsFrom(deps.repository.loadChatHistory(currentUser))
            deps.updateUiState(deps.uiState.value.copy(showGroupRenameDialog = null))
        } else {
            deps.updateUiState(
                deps.uiState.value.copy(
                    showGroupRenameDialog = null
                )
            )
        }
    }

    /**
     * Convert a regular group into a project group.
     */
    fun makeGroupProject(group: ChatGroup) {
        val currentUser = deps.appSettings.value.current_user
        val success = deps.repository.updateGroupProjectStatus(currentUser, group.group_id, true)

        if (success) {
            // Update local state from the stored groups
            showGroupsFrom(deps.repository.loadChatHistory(currentUser))

            // Navigate to group screen with project mode enabled
            navigateToGroup(group.group_id)
        }
    }

    /**
     * Create a new conversation within a group.
     */
    fun createNewConversationInGroup(group: ChatGroup) {
        val currentUser = deps.appSettings.value.current_user
        val newChat = deps.repository.createNewChatInGroup(currentUser, "שיחה חדשה", group.group_id)

        // Update local state
        deps.updateUiState(
            deps.uiState.value.copy(
                chatHistory = deps.repository.loadChatHistory(currentUser).chat_history,
                currentChat = newChat
            )
        )

        // Navigate to the new chat
        navigateToScreen(Screen.Chat)
    }

    /**
     * Show the delete confirmation dialog for a group.
     */
    fun showGroupDeleteConfirmation(group: ChatGroup) {
        deps.updateUiState(
            deps.uiState.value.copy(
                showDeleteGroupConfirmation = group,
                groupContextMenu = null
            )
        )
    }

    /**
     * Hide the group delete confirmation dialog.
     */
    fun hideGroupDeleteConfirmation() {
        deps.updateUiState(
            deps.uiState.value.copy(
                showDeleteGroupConfirmation = null
            )
        )
    }

    /**
     * Delete a group and unassign all chats from it.
     * Note: Screen navigation is handled by the caller (ChatViewModel).
     */
    fun deleteGroup(group: ChatGroup) {
        val currentUser = deps.appSettings.value.current_user
        val success = deps.repository.deleteGroup(currentUser, group.group_id)

        if (success) {
            // Update local state from the stored history (group removed, chats unassigned)
            val history = deps.repository.loadChatHistory(currentUser)

            deps.updateUiState(
                deps.uiState.value.copy(
                    groups = history.groups,
                    chatHistory = history.chat_history,
                    currentGroup = if (deps.uiState.value.currentGroup?.group_id == group.group_id) null else deps.uiState.value.currentGroup,
                    showDeleteGroupConfirmation = null
                )
            )
        } else {
            deps.updateUiState(
                deps.uiState.value.copy(
                    showDeleteGroupConfirmation = null
                )
            )
        }
    }

    // ==================== Helper Functions ====================

    /** Show the groups (and chats) of [history], keeping the current group in step. */
    private fun showGroupsFrom(history: UserChatHistory) {
        val state = deps.uiState.value
        deps.updateUiState(
            state.copy(
                groups = history.groups,
                chatHistory = history.chat_history,
                currentGroup = state.currentGroup?.let { current ->
                    history.groups.find { it.group_id == current.group_id } ?: current
                }
            )
        )
    }

    /**
     * Hide the chat deps.context menu (used when adding chat to group).
     */
    private fun hideChatContextMenu() {
        deps.updateUiState(
            deps.uiState.value.copy(
                chatContextMenu = null
            )
        )
    }
}
