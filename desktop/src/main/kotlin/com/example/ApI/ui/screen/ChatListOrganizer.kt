package com.example.ApI.ui.screen

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup

// Data class to represent either a group or an individual chat
sealed class ChatListItem {
    abstract val timestamp: Long

    data class GroupItem(val group: ChatGroup, val chats: List<Chat>) : ChatListItem() {
        override val timestamp: Long = chats.maxOfOrNull { getLastTimestampOrNull(it) ?: Long.MIN_VALUE } ?: Long.MIN_VALUE
    }

    data class ChatItem(val chat: Chat) : ChatListItem() {
        override val timestamp: Long = getLastTimestampOrNull(chat) ?: Long.MIN_VALUE
    }
}

// Helper: organize and sort all items (groups and individual chats) by most recent activity
fun organizeAndSortAllItems(
    chats: List<Chat>,
    groups: List<ChatGroup>
): List<ChatListItem> {
    val items = mutableListOf<ChatListItem>()

    // Separate chats by group
    val chatsByGroup = chats.groupBy { it.group }

    // Add groups with their chats
    groups.forEach { group ->
        val groupChats = chatsByGroup[group.group_id] ?: emptyList()
        if (groupChats.isNotEmpty()) {
            items.add(ChatListItem.GroupItem(group, groupChats))
        }
    }

    // Add ungrouped chats
    chatsByGroup[null]?.forEach { chat ->
        items.add(ChatListItem.ChatItem(chat))
    }

    // Sort all items by timestamp (descending order - newest first)
    return items.sortedWith { a, b ->
        b.timestamp.compareTo(a.timestamp)
    }
}
