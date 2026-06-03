package com.example.ApI.data.repository

import com.example.ApI.data.model.*
import java.util.UUID

/**
 * Manages group and project operations: creating, deleting, renaming groups,
 * adding/removing chats from groups, and project attachments.
 */
class GroupProjectManager(
    private val chatHistoryManager: ChatHistoryManager
) {
    fun createNewGroup(username: String, groupName: String): ChatGroup {
        val chatHistory = chatHistoryManager.loadChatHistory(username)
        val groupId = UUID.randomUUID().toString()
        val newGroup = ChatGroup(
            group_id = groupId,
            group_name = groupName
        )

        val updatedHistory = chatHistory.copy(groups = chatHistory.groups + newGroup)
        chatHistoryManager.saveChatHistory(updatedHistory)

        return newGroup
    }

    fun addChatToGroup(username: String, chatId: String, groupId: String): Boolean {
        val chatHistory = chatHistoryManager.loadChatHistory(username)

        // Check if group exists
        val groupExists = chatHistory.groups.any { it.group_id == groupId }
        if (!groupExists) return false

        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.chat_id == chatId) {
                chat.copy(group = groupId)
            } else {
                chat
            }
        }

        val updatedHistory = chatHistory.copy(chat_history = updatedChats)
        chatHistoryManager.saveChatHistory(updatedHistory)

        return true
    }

    fun removeChatFromGroup(username: String, chatId: String): Boolean {
        val chatHistory = chatHistoryManager.loadChatHistory(username)

        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.chat_id == chatId) {
                chat.copy(group = null)
            } else {
                chat
            }
        }

        val updatedHistory = chatHistory.copy(chat_history = updatedChats)
        chatHistoryManager.saveChatHistory(updatedHistory)

        return true
    }

    fun deleteGroup(username: String, groupId: String): Boolean {
        val chatHistory = chatHistoryManager.loadChatHistory(username)

        // Remove all chats from this group
        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.group == groupId) {
                chat.copy(group = null)
            } else {
                chat
            }
        }

        // Remove the group
        val updatedGroups = chatHistory.groups.filter { it.group_id != groupId }

        val updatedHistory = chatHistory.copy(
            chat_history = updatedChats,
            groups = updatedGroups
        )
        chatHistoryManager.saveChatHistory(updatedHistory)

        return true
    }

    fun renameGroup(username: String, groupId: String, newName: String): Boolean {
        val chatHistory = chatHistoryManager.loadChatHistory(username)

        val updatedGroups = chatHistory.groups.map { group ->
            if (group.group_id == groupId) {
                group.copy(group_name = newName)
            } else {
                group
            }
        }

        val updatedHistory = chatHistory.copy(groups = updatedGroups)
        chatHistoryManager.saveChatHistory(updatedHistory)

        return true
    }

    fun updateGroupProjectStatus(username: String, groupId: String, isProject: Boolean): Boolean {
        val chatHistory = chatHistoryManager.loadChatHistory(username)

        val updatedGroups = chatHistory.groups.map { group ->
            if (group.group_id == groupId) {
                group.copy(is_project = isProject)
            } else {
                group
            }
        }

        val updatedHistory = chatHistory.copy(groups = updatedGroups)
        chatHistoryManager.saveChatHistory(updatedHistory)

        return true
    }

    fun addAttachmentToGroup(username: String, groupId: String, attachment: Attachment): Boolean {
        val chatHistory = chatHistoryManager.loadChatHistory(username)

        val updatedGroups = chatHistory.groups.map { group ->
            if (group.group_id == groupId) {
                group.copy(group_attachments = group.group_attachments + attachment)
            } else {
                group
            }
        }

        val updatedHistory = chatHistory.copy(groups = updatedGroups)
        chatHistoryManager.saveChatHistory(updatedHistory)

        return true
    }

    fun removeAttachmentFromGroup(username: String, groupId: String, attachmentIndex: Int): Boolean {
        val chatHistory = chatHistoryManager.loadChatHistory(username)

        val updatedGroups = chatHistory.groups.map { group ->
            if (group.group_id == groupId && attachmentIndex >= 0 && attachmentIndex < group.group_attachments.size) {
                val updatedAttachments = group.group_attachments.toMutableList()
                updatedAttachments.removeAt(attachmentIndex)
                group.copy(group_attachments = updatedAttachments)
            } else {
                group
            }
        }

        val updatedHistory = chatHistory.copy(groups = updatedGroups)
        chatHistoryManager.saveChatHistory(updatedHistory)

        return true
    }
}
