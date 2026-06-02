package com.example.ApI.ui.managers.organization

import androidx.compose.ui.unit.DpOffset
import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages all group-related operations for the ChatViewModel.
 * Desktop adaptation: uses java.io.File instead of Android Uri/ContentResolver.
 */
class GroupManager(
    private val deps: ManagerDependencies,
    private val navigateToScreen: (Screen) -> Unit
) {

    fun createNewGroup(groupName: String) {
        if (groupName.isBlank()) return
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val newGroup = deps.repository.createNewGroup(currentUser, groupName.trim())
            val pendingChat = deps.uiState.value.pendingChatForGroup
            if (pendingChat != null) {
                deps.repository.addChatToGroup(currentUser, pendingChat.chat_id, newGroup.group_id)
            }
            val chatHistory = deps.repository.loadChatHistory(currentUser)
            deps.updateUiState(deps.uiState.value.copy(
                groups = chatHistory.groups,
                chatHistory = chatHistory.chat_history,
                expandedGroups = deps.uiState.value.expandedGroups + newGroup.group_id,
                pendingChatForGroup = null
            ))
            hideGroupDialog()
        }
    }

    fun addChatToGroup(chatId: String, groupId: String) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val success = deps.repository.addChatToGroup(currentUser, chatId, groupId)
            if (success) {
                val chatHistory = deps.repository.loadChatHistory(currentUser)
                deps.updateUiState(deps.uiState.value.copy(chatHistory = chatHistory.chat_history, groups = chatHistory.groups))
                hideChatContextMenu()
            } else {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Error adding chat to group"))
            }
        }
    }

    fun removeChatFromGroup(chatId: String) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val success = deps.repository.removeChatFromGroup(currentUser, chatId)
            if (success) {
                val chatHistory = deps.repository.loadChatHistory(currentUser)
                deps.updateUiState(deps.uiState.value.copy(chatHistory = chatHistory.chat_history, groups = chatHistory.groups))
                hideChatContextMenu()
            }
        }
    }

    fun toggleGroupExpansion(groupId: String) {
        val currentExpanded = deps.uiState.value.expandedGroups
        val newExpanded = if (currentExpanded.contains(groupId)) currentExpanded - groupId else currentExpanded + groupId
        deps.updateUiState(deps.uiState.value.copy(expandedGroups = newExpanded))
    }

    fun showGroupDialog(chat: Chat? = null) {
        deps.updateUiState(deps.uiState.value.copy(showGroupDialog = true, pendingChatForGroup = chat))
    }

    fun hideGroupDialog() {
        deps.updateUiState(deps.uiState.value.copy(showGroupDialog = false, pendingChatForGroup = null))
    }

    fun refreshChatHistoryAndGroups() {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val chatHistory = deps.repository.loadChatHistory(currentUser)
            deps.updateUiState(deps.uiState.value.copy(chatHistory = chatHistory.chat_history, groups = chatHistory.groups))
        }
    }

    fun toggleGroupProjectStatus(groupId: String) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val currentGroup = deps.uiState.value.groups.find { it.group_id == groupId }
            val newProjectStatus = !(currentGroup?.is_project ?: false)
            deps.repository.updateGroupProjectStatus(currentUser, groupId, newProjectStatus)
            val updatedGroups = deps.uiState.value.groups.map { group ->
                if (group.group_id == groupId) group.copy(is_project = newProjectStatus) else group
            }
            deps.updateUiState(deps.uiState.value.copy(groups = updatedGroups))
            if (deps.uiState.value.currentGroup?.group_id == groupId) {
                deps.updateUiState(deps.uiState.value.copy(currentGroup = updatedGroups.find { it.group_id == groupId }))
            }
        }
    }

    /** Desktop: add file using java.io.File path instead of Android Uri. */
    fun addFileToProject(groupId: String, file: File, mimeType: String) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            try {
                val fileData = withContext(Dispatchers.IO) { file.readBytes() }
                val localPath = deps.repository.saveFileLocally(file.name, fileData)
                if (localPath != null) {
                    val attachment = Attachment(local_file_path = localPath, file_name = file.name, mime_type = mimeType)
                    deps.repository.addAttachmentToGroup(currentUser, groupId, attachment)
                    val updatedGroups = deps.uiState.value.groups.map { group ->
                        if (group.group_id == groupId) group.copy(group_attachments = group.group_attachments + attachment) else group
                    }
                    deps.updateUiState(deps.uiState.value.copy(groups = updatedGroups))
                    if (deps.uiState.value.currentGroup?.group_id == groupId) {
                        deps.updateUiState(deps.uiState.value.copy(currentGroup = updatedGroups.find { it.group_id == groupId }))
                    }
                }
            } catch (e: Exception) {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Error uploading file: ${e.message}"))
            }
        }
    }

    fun removeFileFromProject(groupId: String, attachmentIndex: Int) {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val chatHistory = deps.repository.loadChatHistory(currentUser)
            val group = chatHistory.groups.find { it.group_id == groupId }
            val attachmentToRemove = group?.group_attachments?.getOrNull(attachmentIndex)
            deps.repository.removeAttachmentFromGroup(currentUser, groupId, attachmentIndex)
            attachmentToRemove?.local_file_path?.let { path -> deps.repository.deleteFile(path) }
            val updatedGroups = deps.uiState.value.groups.map { groupItem ->
                if (groupItem.group_id == groupId) {
                    val updatedAttachments = groupItem.group_attachments.toMutableList()
                    if (attachmentIndex >= 0 && attachmentIndex < updatedAttachments.size) updatedAttachments.removeAt(attachmentIndex)
                    groupItem.copy(group_attachments = updatedAttachments)
                } else groupItem
            }
            deps.updateUiState(deps.uiState.value.copy(groups = updatedGroups))
            if (deps.uiState.value.currentGroup?.group_id == groupId) {
                deps.updateUiState(deps.uiState.value.copy(currentGroup = updatedGroups.find { it.group_id == groupId }))
            }
        }
    }

    fun navigateToGroup(groupId: String) {
        val group = deps.uiState.value.groups.find { it.group_id == groupId }
        if (group != null) {
            deps.updateUiState(deps.uiState.value.copy(currentGroup = group, systemPrompt = group.system_prompt ?: ""))
            navigateToScreen(Screen.Group(groupId))
        }
    }

    fun updateGroupSystemPrompt(systemPrompt: String) {
        val currentGroup = deps.uiState.value.currentGroup ?: return
        val currentUser = deps.appSettings.value.current_user
        val updatedGroups = deps.uiState.value.groups.map { group ->
            if (group.group_id == currentGroup.group_id) group.copy(system_prompt = systemPrompt) else group
        }
        val chatHistory = deps.repository.loadChatHistory(currentUser)
        deps.repository.saveChatHistory(chatHistory.copy(groups = updatedGroups))
        deps.updateUiState(deps.uiState.value.copy(
            groups = updatedGroups,
            currentGroup = updatedGroups.find { it.group_id == currentGroup.group_id },
            systemPrompt = systemPrompt,
            showSystemPromptDialog = false
        ))
    }

    fun showGroupContextMenu(group: ChatGroup, position: DpOffset) {
        deps.updateUiState(deps.uiState.value.copy(groupContextMenu = GroupContextMenuState(group, position)))
    }

    fun hideGroupContextMenu() {
        deps.updateUiState(deps.uiState.value.copy(groupContextMenu = null))
    }

    fun showGroupRenameDialog(group: ChatGroup) {
        deps.updateUiState(deps.uiState.value.copy(showGroupRenameDialog = group, groupContextMenu = null))
    }

    fun hideGroupRenameDialog() {
        deps.updateUiState(deps.uiState.value.copy(showGroupRenameDialog = null))
    }

    fun renameGroup(group: ChatGroup, newName: String) {
        if (newName.isBlank()) return
        val currentUser = deps.appSettings.value.current_user
        val success = deps.repository.renameGroup(currentUser, group.group_id, newName.trim())
        if (success) {
            val updatedGroups = deps.uiState.value.groups.map { if (it.group_id == group.group_id) it.copy(group_name = newName.trim()) else it }
            deps.updateUiState(deps.uiState.value.copy(
                groups = updatedGroups,
                currentGroup = if (deps.uiState.value.currentGroup?.group_id == group.group_id) deps.uiState.value.currentGroup?.copy(group_name = newName.trim()) else deps.uiState.value.currentGroup,
                showGroupRenameDialog = null
            ))
        } else {
            deps.updateUiState(deps.uiState.value.copy(showGroupRenameDialog = null))
        }
    }

    fun makeGroupProject(group: ChatGroup) {
        val currentUser = deps.appSettings.value.current_user
        val success = deps.repository.updateGroupProjectStatus(currentUser, group.group_id, true)
        if (success) {
            val updatedGroups = deps.uiState.value.groups.map { if (it.group_id == group.group_id) it.copy(is_project = true) else it }
            deps.updateUiState(deps.uiState.value.copy(
                groups = updatedGroups,
                currentGroup = if (deps.uiState.value.currentGroup?.group_id == group.group_id) deps.uiState.value.currentGroup?.copy(is_project = true) else deps.uiState.value.currentGroup
            ))
            navigateToGroup(group.group_id)
        }
    }

    fun createNewConversationInGroup(group: ChatGroup) {
        val currentUser = deps.appSettings.value.current_user
        val newChat = deps.repository.createNewChatInGroup(currentUser, "New chat", group.group_id)
        val updatedChatHistory = deps.uiState.value.chatHistory + newChat
        deps.updateUiState(deps.uiState.value.copy(chatHistory = updatedChatHistory, currentChat = newChat))
        navigateToScreen(Screen.Chat)
    }

    fun showGroupDeleteConfirmation(group: ChatGroup) {
        deps.updateUiState(deps.uiState.value.copy(showDeleteGroupConfirmation = group, groupContextMenu = null))
    }

    fun hideGroupDeleteConfirmation() {
        deps.updateUiState(deps.uiState.value.copy(showDeleteGroupConfirmation = null))
    }

    fun deleteGroup(group: ChatGroup) {
        val currentUser = deps.appSettings.value.current_user
        val success = deps.repository.deleteGroup(currentUser, group.group_id)
        if (success) {
            val updatedGroups = deps.uiState.value.groups.filter { it.group_id != group.group_id }
            val updatedChatHistory = deps.uiState.value.chatHistory.map { chat ->
                if (chat.group == group.group_id) chat.copy(group = null) else chat
            }
            deps.updateUiState(deps.uiState.value.copy(
                groups = updatedGroups,
                chatHistory = updatedChatHistory,
                currentGroup = if (deps.uiState.value.currentGroup?.group_id == group.group_id) null else deps.uiState.value.currentGroup,
                showDeleteGroupConfirmation = null
            ))
        } else {
            deps.updateUiState(deps.uiState.value.copy(showDeleteGroupConfirmation = null))
        }
    }

    private fun hideChatContextMenu() {
        deps.updateUiState(deps.uiState.value.copy(chatContextMenu = null))
    }
}
