package com.example.ApI.data.repository

import com.example.ApI.data.model.*
import com.example.ApI.util.AppLogger
import com.example.ApI.util.AtomicFiles
import com.example.ApI.util.FileLocks
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Manages chat history operations: loading, saving, CRUD operations,
 * and import/export functionality.
 */
class ChatHistoryManager(
    private val internalDir: File,
    private val json: Json,
    private val downloadsDir: File? = null,
    private val onFileWritten: (java.io.File) -> Unit = {}
) {
    companion object {
        private const val TAG = "ChatHistoryManager"
        private const val PARSE_RETRY_DELAY_MS = 50L
    }

    fun chatHistoryFile(username: String): File = File(internalDir, "chat_history_$username.json")

    /**
     * Load the chat history of [username]. The returned `user_name` is always [username]
     * (the file name is authoritative, never the content).
     *
     * A missing or empty file is an empty history. A file that cannot be parsed is read once more
     * (in case another writer was mid-write); if it still fails a copy is preserved as
     * `chat_history_<user>.json.corrupt-<timestamp>` before an empty history is returned, so the
     * next save never silently destroys the only copy.
     */
    fun loadChatHistory(username: String): UserChatHistory {
        val file = chatHistoryFile(username)
        return FileLocks.withLock(file) { readChatHistory(file, username) }
    }

    private fun readChatHistory(file: File, username: String): UserChatHistory {
        val empty = UserChatHistory(username, emptyList(), emptyList())
        if (!file.exists()) {
            AppLogger.d("[$TAG] No chat history file for $username yet")
            return empty
        }
        var lastError: Exception? = null
        var content = ""
        for (attempt in 0..1) {
            if (attempt > 0) Thread.sleep(PARSE_RETRY_DELAY_MS)
            try {
                content = file.readText()
                if (content.isBlank()) return empty
                return json.decodeFromString<UserChatHistory>(content).copy(user_name = username)
            } catch (e: Exception) {
                lastError = e
            }
        }
        AppLogger.e("[$TAG] Failed to load chat history of $username; treating it as empty", lastError ?: Exception("unknown"))
        preserveCorruptFile(file, content)
        return empty
    }

    /** Keep a copy of an unreadable chat history file (once per distinct content). */
    private fun preserveCorruptFile(file: File, content: String) {
        try {
            val bytes = if (content.isNotEmpty()) content.toByteArray(Charsets.UTF_8) else file.readBytes()
            val prefix = "${file.name}.corrupt-"
            val alreadyKept = file.parentFile?.listFiles()?.any { other ->
                other.name.startsWith(prefix) && other.length() == bytes.size.toLong() && other.readBytes().contentEquals(bytes)
            } ?: false
            if (alreadyKept) return
            val copy = File(file.parentFile, "$prefix${System.currentTimeMillis()}")
            AtomicFiles.writeBytes(copy, bytes)
            AppLogger.w("[$TAG] Preserved unreadable ${file.name} as ${copy.name}")
        } catch (e: Exception) {
            AppLogger.e("[$TAG] Could not preserve unreadable ${file.name}", e)
        }
    }

    /**
     * Write [chatHistory] to the file of [username] (atomically, under the file lock), storing
     * `user_name = username`, then notify the sync engine.
     */
    fun saveChatHistory(username: String, chatHistory: UserChatHistory) {
        if (username.isBlank()) {
            AppLogger.e("[$TAG] saveChatHistory: blank username, not saving")
            return
        }
        val file = chatHistoryFile(username)
        try {
            AtomicFiles.write(file, json.encodeToString(chatHistory.copy(user_name = username)))
        } catch (e: IOException) {
            AppLogger.e("[$TAG] Failed to save chat history of $username", e)
            return
        }
        onFileWritten(file)
    }

    /**
     * Save to the file named by `chatHistory.user_name`. Safe for histories obtained from
     * [loadChatHistory] (which always sets it to the file's username); prefer
     * [saveChatHistory] with an explicit username, or better [updateChatHistory].
     */
    fun saveChatHistory(chatHistory: UserChatHistory) = saveChatHistory(chatHistory.user_name, chatHistory)

    /**
     * Load + [transform] + save the chat history of [username] as one step under the file lock,
     * so concurrent updates (UI, streaming, sync) never lose each other's changes.
     * [transform] must be quick and side-effect free (it runs with the lock held and must not
     * call into other files' locks). Returning an unchanged (equal) history writes nothing.
     * The sync hook is called after the lock is released.
     *
     * @return the history as saved (or as loaded, when nothing changed)
     */
    fun updateChatHistory(username: String, transform: (UserChatHistory) -> UserChatHistory): UserChatHistory =
        modifyChatHistory(username) { history -> transform(history).let { it to it } }

    /**
     * Like [updateChatHistory], but [block] also returns a result for the caller.
     * Returning the loaded history unchanged writes nothing.
     */
    fun <R> modifyChatHistory(username: String, block: (UserChatHistory) -> Pair<UserChatHistory, R>): R {
        val file = chatHistoryFile(username)
        var written = false
        val result = FileLocks.withLock(file) {
            val current = readChatHistory(file, username)
            val (updated, value) = block(current)
            if (updated !== current && updated != current) {
                try {
                    AtomicFiles.write(file, json.encodeToString(updated.copy(user_name = username)))
                    written = true
                } catch (e: IOException) {
                    AppLogger.e("[$TAG] Failed to save chat history of $username", e)
                }
            }
            value
        }
        if (written) onFileWritten(file)
        return result
    }

    fun getChatJson(username: String, chatId: String): String? {
        return try {
            val chat = loadChatHistory(username).chat_history.find { it.chat_id == chatId }
            chat?.let { json.encodeToString(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun saveChatJsonToDownloads(chatId: String, content: String): String? {
        val dir = downloadsDir ?: return null
        return try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val exportFile = File(dir, "${chatId}.json")
            exportFile.writeText(content)
            exportFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun addMessageToChat(username: String, chatId: String, message: Message): Chat? =
        modifyChatHistory(username) { chatHistory ->
            val targetChat = chatHistory.chat_history.find { it.chat_id == chatId }?.let {
                it.copy(messages = it.messages + message)
            } ?: return@modifyChatHistory chatHistory to null

            val otherChats = chatHistory.chat_history.filter { it.chat_id != chatId }
            // Add the updated chat at the end
            chatHistory.copy(chat_history = otherChats + targetChat) to targetChat
        }

    fun createNewChat(username: String, previewName: String, systemPrompt: String = ""): Chat {
        val chatId = UUID.randomUUID().toString()
        val newChat = Chat(
            chat_id = chatId,
            preview_name = previewName,
            messages = emptyList(),
            systemPrompt = systemPrompt
        )

        updateChatHistory(username) { it.copy(chat_history = it.chat_history + newChat) }

        return newChat
    }

    fun createNewChatInGroup(username: String, previewName: String, groupId: String, systemPrompt: String = ""): Chat {
        val chatId = UUID.randomUUID().toString()
        val newChat = Chat(
            chat_id = chatId,
            preview_name = previewName,
            messages = emptyList(),
            systemPrompt = systemPrompt,
            group = groupId
        )

        updateChatHistory(username) { it.copy(chat_history = it.chat_history + newChat) }

        return newChat
    }

    fun updateChatSystemPrompt(username: String, chatId: String, systemPrompt: String): Chat? =
        updateChatHistory(username) { chatHistory ->
            chatHistory.copy(chat_history = chatHistory.chat_history.map { chat ->
                if (chat.chat_id == chatId) chat.copy(systemPrompt = systemPrompt) else chat
            })
        }.chat_history.find { it.chat_id == chatId }

    fun replaceMessageInChat(username: String, chatId: String, oldMessage: Message, newMessage: Message): Chat? =
        modifyChatHistory(username) { chatHistory ->
            val targetChat = chatHistory.chat_history.find { it.chat_id == chatId }
                ?: return@modifyChatHistory chatHistory to null

            val updatedMessages = targetChat.messages.map { message ->
                if (message == oldMessage) newMessage else message
            }

            val updatedChat = targetChat.copy(messages = updatedMessages)
            val otherChats = chatHistory.chat_history.filter { it.chat_id != chatId }

            chatHistory.copy(chat_history = otherChats + updatedChat) to updatedChat
        }

    fun deleteMessagesFromPoint(username: String, chatId: String, fromMessage: Message): Chat? =
        modifyChatHistory(username) { chatHistory ->
            val targetChat = chatHistory.chat_history.find { it.chat_id == chatId }
                ?: return@modifyChatHistory chatHistory to null

            // Find the index of the message to delete from
            val messageIndex = targetChat.messages.indexOf(fromMessage)
            if (messageIndex == -1) return@modifyChatHistory chatHistory to targetChat // Message not found

            // Keep only messages before this index
            val updatedChat = targetChat.copy(messages = targetChat.messages.take(messageIndex))
            val otherChats = chatHistory.chat_history.filter { it.chat_id != chatId }

            chatHistory.copy(chat_history = otherChats + updatedChat) to updatedChat
        }

    fun updateChatWithNewAttachments(username: String, chatId: String, updatedMessages: List<Message>) {
        try {
            updateChatHistory(username) { chatHistory ->
                chatHistory.copy(chat_history = chatHistory.chat_history.map { chat ->
                    if (chat.chat_id == chatId) chat.copy(messages = updatedMessages) else chat
                })
            }
        } catch (e: Exception) {
            AppLogger.e("[$TAG] Failed to update chat with new file IDs", e)
        }
    }

    fun exportChatHistory(username: String): String? {
        val chatHistory = loadChatHistory(username)
        val dir = downloadsDir ?: return null
        val exportFile = File(dir, "chat_history_${username}_${System.currentTimeMillis()}.json")

        return try {
            exportFile.writeText(json.encodeToString(chatHistory))
            exportFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Import chat history from raw JSON bytes. The JSON must conform to internal
     * UserChatHistory schema. All attachment references are stripped.
     */
    fun importChatHistoryJson(raw: ByteArray, targetUsername: String) {
        try {
            val text = raw.toString(Charsets.UTF_8)
            val imported = json.decodeFromString<UserChatHistory>(text)
            // Sanitize: drop attachments info (local and remote) for every message
            val sanitizedChats = imported.chat_history.map { chat ->
                val sanitizedMessages = chat.messages.map { msg ->
                    msg.copy(
                        attachments = emptyList()
                    )
                }
                chat.copy(messages = sanitizedMessages)
            }
            val sanitized = imported.copy(user_name = targetUsername, chat_history = sanitizedChats)
            saveChatHistory(targetUsername, sanitized)
        } catch (e: Exception) {
            // Ignore invalid import
        }
    }

    /**
     * Validates if JSON content represents a valid chat export.
     * Returns true if the JSON can be parsed as Chat or UserChatHistory.
     */
    fun validateChatJson(jsonContent: String): Boolean {
        return try {
            // Try parsing as a single Chat first
            json.decodeFromString<Chat>(jsonContent)
            true
        } catch (e: Exception) {
            try {
                // Try parsing as UserChatHistory
                json.decodeFromString<UserChatHistory>(jsonContent)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * Import a single chat from JSON content. Sanitizes attachments.
     * Returns the imported chat ID on success, null on failure.
     */
    fun importSingleChat(jsonContent: String, targetUsername: String): String? {
        return try {
            // Try parsing as a single Chat first
            val chat = try {
                json.decodeFromString<Chat>(jsonContent)
            } catch (e: Exception) {
                // If not a single chat, try as UserChatHistory and take first chat
                val history = json.decodeFromString<UserChatHistory>(jsonContent)
                history.chat_history.firstOrNull() ?: return null
            }

            // Sanitize: remove attachments
            val sanitizedMessages = chat.messages.map { msg ->
                msg.copy(attachments = emptyList())
            }
            val sanitizedChat = chat.copy(messages = sanitizedMessages)

            // Add the imported chat to history, under a fresh chat_id if that id is already taken
            // (duplicate chat_ids in one file would be merged into one chat by sync)
            modifyChatHistory(targetUsername) { currentHistory ->
                val chatToAdd = if (currentHistory.chat_history.any { it.chat_id == sanitizedChat.chat_id }) {
                    sanitizedChat.copy(chat_id = UUID.randomUUID().toString(), shareLink = "", shareId = "")
                } else {
                    sanitizedChat
                }
                currentHistory.copy(chat_history = currentHistory.chat_history + chatToAdd) to chatToAdd.chat_id
            }
        } catch (e: Exception) {
            AppLogger.e("[$TAG] Failed to import chat", e)
            null
        }
    }

    /**
     * Update the share link fields for a specific chat.
     * Pass empty strings to clear the link.
     */
    fun updateChatShareLink(username: String, chatId: String, shareLink: String, shareId: String): Chat? =
        updateChatHistory(username) { chatHistory ->
            chatHistory.copy(chat_history = chatHistory.chat_history.map { chat ->
                if (chat.chat_id == chatId) chat.copy(shareLink = shareLink, shareId = shareId) else chat
            })
        }.chat_history.find { it.chat_id == chatId }
}
