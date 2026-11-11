package com.example.ApI.ui.managers.io

import com.example.ApI.data.model.Chat

/**
 * Where the app goes when the camera or the file picker, launched from a chat, comes back with
 * files for the message being written.
 */
sealed interface PickerReturn {
    /** The chat screen is still showing: the files simply join the draft. */
    data object Stay : PickerReturn

    /** The chat screen is gone but the chat is there: open it again. */
    data class Open(val chat: Chat) : PickerReturn

    /** The chat screen and the chat are gone: a new chat takes the draft. */
    data object NewChat : PickerReturn

    companion object {
        /**
         * The chat screen is gone when the app's process was killed while the picker was in
         * front: the app then starts over on the chat list, and a chat that was still empty (the
         * new chat the picker was launched from) is removed by the start-up cleanup of empty
         * chats, like any abandoned one. The files the user came back with must not be left in
         * a draft that no screen shows.
         */
        fun of(onChatScreen: Boolean, originChatId: String?, chats: List<Chat>): PickerReturn {
            if (onChatScreen) return Stay
            val origin = originChatId?.let { id -> chats.firstOrNull { it.chat_id == id } }
            return if (origin != null) Open(origin) else NewChat
        }
    }
}
