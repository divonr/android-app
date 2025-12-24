package com.example.ApI.data.repository

import android.os.Environment
import com.example.ApI.data.model.*
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
    private val json: Json
) {
    fun loadChatHistory(username: String): UserChatHistory {
        val file = File(internalDir, "chat_history_$username.json")
        return if (file.exists()) {
            try {
                val content = file.readText()
                json.decodeFromString<UserChatHistory>(content)
            } catch (e: Exception) {
                android.util.Log.e("ChatHistoryManager", "Failed to load chat history", e)
                UserChatHistory(username, emptyList(), emptyList())
            }
        } else {
            android.util.Log.e("ChatHistoryManager", "Failed to load chat history, file doesn't exist")
            UserChatHistory(username, emptyList(), emptyList())
        }
    }

    fun saveChatHistory(chatHistory: UserChatHistory) {
        val file = File(internalDir, "chat_history_${chatHistory.user_name}.json")
        try {
            file.writeText(json.encodeToString(chatHistory))
        } catch (e: IOException) {
            // Handle error
        }
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
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val exportFile = File(downloadsDir, "${chatId}.json")
            exportFile.writeText(content)
            exportFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun addMessageToChat(username: String, chatId: String, message: Message): Chat? {
        val chatHistory = loadChatHistory(username)
        val targetChat = chatHistory.chat_history.find { it.chat_id == chatId }?.copy(
            messages = chatHistory.chat_history.find { it.chat_id == chatId }?.messages.orEmpty() + message
        )

        val otherChats = chatHistory.chat_history.filter { it.chat_id != chatId }
        val updatedHistory = chatHistory.copy(
            chat_history = if (targetChat != null) {
                otherChats + targetChat // Add the updated chat at the end
            } else {
                chatHistory.chat_history
            }
        )

        saveChatHistory(updatedHistory)
        return targetChat
    }

    fun createNewChat(username: String, previewName: String, systemPrompt: String = ""): Chat {
        val chatId = UUID.randomUUID().toString()
        val newChat = Chat(
            chat_id = chatId,
            preview_name = previewName,
            messages = emptyList(),
            systemPrompt = systemPrompt
        )

        val chatHistory = loadChatHistory(username)
        val updatedHistory = chatHistory.copy(
            chat_history = chatHistory.chat_history + newChat
        )
        saveChatHistory(updatedHistory)

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

        val chatHistory = loadChatHistory(username)
        val updatedHistory = chatHistory.copy(
            chat_history = chatHistory.chat_history + newChat
        )
        saveChatHistory(updatedHistory)

        return newChat
    }

    fun updateChatSystemPrompt(username: String, chatId: String, systemPrompt: String): Chat? {
        val chatHistory = loadChatHistory(username)
        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.chat_id == chatId) {
                chat.copy(systemPrompt = systemPrompt)
            } else {
                chat
            }
        }

        val updatedHistory = chatHistory.copy(chat_history = updatedChats)
        saveChatHistory(updatedHistory)

        return updatedChats.find { it.chat_id == chatId }
    }

    fun replaceMessageInChat(username: String, chatId: String, oldMessage: Message, newMessage: Message): Chat? {
        val chatHistory = loadChatHistory(username)
        val targetChat = chatHistory.chat_history.find { it.chat_id == chatId } ?: return null

        val updatedMessages = targetChat.messages.map { message ->
            if (message == oldMessage) newMessage else message
        }

        val updatedChat = targetChat.copy(messages = updatedMessages)
        val otherChats = chatHistory.chat_history.filter { it.chat_id != chatId }

        val updatedHistory = chatHistory.copy(
            chat_history = otherChats + updatedChat
        )

        saveChatHistory(updatedHistory)
        return updatedChat
    }

    fun deleteMessagesFromPoint(username: String, chatId: String, fromMessage: Message): Chat? {
        val chatHistory = loadChatHistory(username)
        val targetChat = chatHistory.chat_history.find { it.chat_id == chatId } ?: return null

        // Find the index of the message to delete from
        val messageIndex = targetChat.messages.indexOf(fromMessage)
        if (messageIndex == -1) return targetChat // Message not found

        // Keep only messages before this index
        val updatedMessages = targetChat.messages.take(messageIndex)

        val updatedChat = targetChat.copy(messages = updatedMessages)
        val otherChats = chatHistory.chat_history.filter { it.chat_id != chatId }

        val updatedHistory = chatHistory.copy(
            chat_history = otherChats + updatedChat
        )

        saveChatHistory(updatedHistory)
        return updatedChat
    }

    fun updateChatWithNewAttachments(username: String, chatId: String, updatedMessages: List<Message>) {
        try {
            val chatHistory = loadChatHistory(username)
            val updatedChats = chatHistory.chat_history.map { chat ->
                if (chat.chat_id == chatId) {
                    chat.copy(messages = updatedMessages)
                } else {
                    chat
                }
            }
            val updatedHistory = chatHistory.copy(chat_history = updatedChats)
            saveChatHistory(updatedHistory)
        } catch (e: Exception) {
            println("Failed to update chat with new file IDs: ${e.message}")
        }
    }

    fun exportChatHistory(username: String): String? {
        val chatHistory = loadChatHistory(username)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val exportFile = File(downloadsDir, "chat_history_${username}_${System.currentTimeMillis()}.json")

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
            saveChatHistory(sanitized)
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

            // Load current chat history
            val currentHistory = loadChatHistory(targetUsername)

            // Add the imported chat to history
            val updatedHistory = currentHistory.copy(
                chat_history = currentHistory.chat_history + sanitizedChat
            )

            saveChatHistory(updatedHistory)
            sanitizedChat.chat_id
        } catch (e: Exception) {
            println("Failed to import chat: ${e.message}")
            null
        }
    }
}
