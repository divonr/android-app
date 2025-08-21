package com.example.ApI.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UserChatHistory(
    val user_name: String,
    val chat_history: List<Chat>
)

@Serializable
data class Chat(
    val chat_id: String,
    val preview_name: String,
    val messages: List<Message>,
    val systemPrompt: String = ""
)

@Serializable
data class Message(
    val role: String, // "user", "assistant", "system"
    val text: String,
    val attachments: List<Attachment> = emptyList()
)

@Serializable
data class Attachment(
    val local_file_path: String? = null,
    val file_name: String,
    val mime_type: String,
    val file_OPENAI_id: String? = null,
    val file_POE_url: String? = null,
    val file_GOOGLE_uri: String? = null
)
