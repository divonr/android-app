package com.example.ApI.data.sync

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup

/**
 * What the UI shows after a sync changed local files ([SyncEngine.changeTick]): shared rules of
 * the Android and desktop ViewModels' reload, so both handle a chat or group that another device
 * deleted the same way.
 */
object SyncReload {

    /**
     * The chat to show after the chat list was reloaded as [chats].
     * - The current chat still exists → its fresh copy.
     * - It vanished (deleted elsewhere) while a request is in flight for it ([busyChatIds]: loading
     *   / streaming) → keep showing it as is: the stream's UI state is left alone and the reply is
     *   still saved if the chat comes back (a modification made here beats a remote deletion).
     * - It vanished with an unsent draft ([hasDraft]) → no current chat, so sending starts a new
     *   chat instead of appending the draft to an unrelated one.
     * - Otherwise → the newest chat (the end of the list), or none.
     */
    fun currentChatAfterReload(chats: List<Chat>, current: Chat?, busyChatIds: Set<String>, hasDraft: Boolean): Chat? {
        current ?: return null
        chats.firstOrNull { it.chat_id == current.chat_id }?.let { return it }
        if (current.chat_id in busyChatIds) return current
        return if (hasDraft) null else chats.lastOrNull()
    }

    /** The group to show after a reload (null: it was deleted elsewhere). */
    fun currentGroupAfterReload(groups: List<ChatGroup>, current: ChatGroup?): ChatGroup? =
        current?.let { c -> groups.firstOrNull { it.group_id == c.group_id } }
}
