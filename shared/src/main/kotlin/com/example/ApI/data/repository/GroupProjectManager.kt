package com.example.ApI.data.repository

import com.example.ApI.data.model.*
import java.util.UUID

/**
 * Manages group and project operations: creating, deleting, renaming groups,
 * adding/removing chats from groups, and project attachments.
 *
 * Every operation is a single [ChatHistoryManager.updateChatHistory] step (load + modify + save
 * under the chat history file lock).
 */
class GroupProjectManager(
    private val chatHistoryManager: ChatHistoryManager
) {
    fun createNewGroup(username: String, groupName: String): ChatGroup {
        val groupId = UUID.randomUUID().toString()
        val newGroup = ChatGroup(
            group_id = groupId,
            group_name = groupName
        )

        chatHistoryManager.updateChatHistory(username) { it.copy(groups = it.groups + newGroup) }

        return newGroup
    }

    fun addChatToGroup(username: String, chatId: String, groupId: String): Boolean =
        chatHistoryManager.modifyChatHistory(username) { chatHistory ->
            // Check if group exists
            val groupExists = chatHistory.groups.any { it.group_id == groupId }
            if (!groupExists) return@modifyChatHistory chatHistory to false

            val updatedChats = chatHistory.chat_history.map { chat ->
                if (chat.chat_id == chatId) chat.copy(group = groupId) else chat
            }
            chatHistory.copy(chat_history = updatedChats) to true
        }

    fun removeChatFromGroup(username: String, chatId: String): Boolean {
        chatHistoryManager.updateChatHistory(username) { chatHistory ->
            chatHistory.copy(chat_history = chatHistory.chat_history.map { chat ->
                if (chat.chat_id == chatId) chat.copy(group = null) else chat
            })
        }
        return true
    }

    fun deleteGroup(username: String, groupId: String): Boolean {
        chatHistoryManager.updateChatHistory(username) { chatHistory ->
            // Remove all chats from this group
            val updatedChats = chatHistory.chat_history.map { chat ->
                if (chat.group == groupId) chat.copy(group = null) else chat
            }

            // Remove the group
            val updatedGroups = chatHistory.groups.filter { it.group_id != groupId }

            chatHistory.copy(
                chat_history = updatedChats,
                groups = updatedGroups
            )
        }
        return true
    }

    fun renameGroup(username: String, groupId: String, newName: String): Boolean {
        updateGroup(username, groupId) { it.copy(group_name = newName) }
        return true
    }

    fun updateGroupProjectStatus(username: String, groupId: String, isProject: Boolean): Boolean {
        updateGroup(username, groupId) { it.copy(is_project = isProject) }
        return true
    }

    fun addAttachmentToGroup(username: String, groupId: String, attachment: Attachment): Boolean {
        updateGroup(username, groupId) { it.copy(group_attachments = it.group_attachments + attachment) }
        return true
    }

    fun removeAttachmentFromGroup(username: String, groupId: String, attachmentIndex: Int): Boolean {
        updateGroup(username, groupId) { group ->
            if (attachmentIndex >= 0 && attachmentIndex < group.group_attachments.size) {
                val updatedAttachments = group.group_attachments.toMutableList()
                updatedAttachments.removeAt(attachmentIndex)
                group.copy(group_attachments = updatedAttachments)
            } else {
                group
            }
        }
        return true
    }

    private fun updateGroup(username: String, groupId: String, transform: (ChatGroup) -> ChatGroup) {
        chatHistoryManager.updateChatHistory(username) { chatHistory ->
            chatHistory.copy(groups = chatHistory.groups.map { group ->
                if (group.group_id == groupId) transform(group) else group
            })
        }
    }
}
