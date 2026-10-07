package com.example.ApI.data.repository

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.sync.SyncEngine
import com.example.ApI.util.AppLogger
import com.example.ApI.util.JsonConfig
import java.util.Collections

/**
 * Removal of empty chats (created, never written to) — shared by DataRepository and
 * DesktopRepository.
 *
 * Without a sync base every empty chat is local junk. An empty chat present in the chat
 * history's last synced base ([SyncEngine.baseContent]) may be another device's brand-new chat
 * (its user is about to type), and deleting it here would propagate as a deletion; such chats
 * are kept. This holds while signed out too: sign-out keeps the sync state, so a removal made
 * then would still be uploaded as a deletion on the next sign-in to the same account.
 * Exception: empty chats created by this repository instance ([createdHere]) are this device's
 * own junk even after they were uploaded — their removal syncs as an ordinary deletion, which
 * loses against another device that wrote into the chat meanwhile (modification beats deletion).
 *
 * The base is read inside the chat history's locked step: the sync engine writes a merge and
 * records its base under the same lock, so a chat a pull just brought in is always in the base
 * the cleanup sees.
 */
class EmptyChatCleanup(
    private val chatHistoryManager: ChatHistoryManager,
    private val syncEngine: SyncEngine
) {
    private val created: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Record a chat created by this device (process lifetime). */
    fun createdHere(chatId: String) {
        created += chatId
    }

    /** Remove the removable empty chats of [username]; returns how many were removed. */
    fun cleanup(username: String): Int {
        var removedCount = 0
        chatHistoryManager.updateChatHistory(username) { chatHistory ->
            removedCount = 0
            if (chatHistory.chat_history.none(::isEmpty)) return@updateChatHistory chatHistory
            // A read of another file (no lock taken but the state's monitor): fine inside the transform
            val synced = syncedChatIds(username)
            val kept = chatHistory.chat_history.filter { chat -> !isEmpty(chat) || !removable(chat, synced) }
            removedCount = chatHistory.chat_history.size - kept.size
            if (removedCount > 0) chatHistory.copy(chat_history = kept) else chatHistory
        }
        return removedCount
    }

    private fun isEmpty(chat: Chat) = chat.messages.isEmpty() && chat.messageNodes.isEmpty()

    private fun removable(chat: Chat, synced: Set<String>?): Boolean =
        chat.chat_id in created || (synced != null && chat.chat_id !in synced)

    /** Chat ids in the synced base of [username]'s chat history (empty: no base; null: unreadable). */
    private fun syncedChatIds(username: String): Set<String>? {
        val base = syncEngine.baseContent(chatHistoryManager.chatHistoryFile(username).name) ?: return emptySet()
        return try {
            JsonConfig.prettyPrint.decodeFromString(UserChatHistory.serializer(), base).chat_history.mapTo(HashSet()) { it.chat_id }
        } catch (e: Exception) {
            AppLogger.w("[EmptyChatCleanup] Unreadable chat history base; keeping every empty chat")
            null
        }
    }
}
