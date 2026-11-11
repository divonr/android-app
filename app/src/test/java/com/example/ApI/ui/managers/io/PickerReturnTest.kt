package com.example.ApI.ui.managers.io

import com.example.ApI.data.model.Chat
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerReturnTest {
    private val chats = listOf(Chat("a", "first", emptyList()), Chat("b", "second", emptyList()))

    @Test
    fun staysWhileTheChatScreenIsShowing() {
        assertEquals(PickerReturn.Stay, PickerReturn.of(onChatScreen = true, originChatId = "a", chats = chats))
        // Even when the chat it was launched from is gone: the screen shows the draft
        assertEquals(PickerReturn.Stay, PickerReturn.of(onChatScreen = true, originChatId = "gone", chats = chats))
    }

    @Test
    fun reopensTheChatAfterTheAppStartedOver() {
        assertEquals(PickerReturn.Open(chats[0]), PickerReturn.of(onChatScreen = false, originChatId = "a", chats = chats))
    }

    @Test
    fun startsANewChatWhenTheEmptyChatWasCleanedUp() {
        assertEquals(PickerReturn.NewChat, PickerReturn.of(onChatScreen = false, originChatId = "gone", chats = chats))
        assertEquals(PickerReturn.NewChat, PickerReturn.of(onChatScreen = false, originChatId = null, chats = chats))
    }
}
