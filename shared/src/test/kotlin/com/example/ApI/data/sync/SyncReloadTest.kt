package com.example.ApI.data.sync

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.Message
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SyncReloadTest {

    private fun chat(id: String, vararg texts: String) =
        Chat(chat_id = id, preview_name = id, messages = texts.map { Message(role = "user", text = it) })

    @Test
    fun `the current chat is replaced by its fresh copy`() {
        val fresh = chat("a", "q", "from another device")
        val shown = SyncReload.currentChatAfterReload(listOf(chat("b"), fresh), chat("a", "q"), emptySet(), hasDraft = false)
        assertSame(fresh, shown)
    }

    @Test
    fun `a vanished current chat falls back to the newest chat`() {
        val newest = chat("c")
        assertSame(newest, SyncReload.currentChatAfterReload(listOf(chat("b"), newest), chat("a"), emptySet(), hasDraft = false))
        assertNull(SyncReload.currentChatAfterReload(emptyList(), chat("a"), emptySet(), hasDraft = false))
    }

    @Test
    fun `a vanished chat with an unsent draft falls back to a new chat`() {
        assertNull(SyncReload.currentChatAfterReload(listOf(chat("b")), chat("a"), emptySet(), hasDraft = true))
    }

    @Test
    fun `a vanished chat with a request in flight stays on screen`() {
        val streaming = chat("a", "q")
        assertSame(streaming, SyncReload.currentChatAfterReload(listOf(chat("b")), streaming, setOf("a"), hasDraft = false))
    }

    @Test
    fun `no current chat stays none, groups follow the reload`() {
        assertNull(SyncReload.currentChatAfterReload(listOf(chat("b")), null, emptySet(), hasDraft = false))
        val g = ChatGroup(group_id = "g", group_name = "renamed elsewhere")
        assertEquals(g, SyncReload.currentGroupAfterReload(listOf(g), ChatGroup(group_id = "g", group_name = "old")))
        assertNull(SyncReload.currentGroupAfterReload(emptyList(), ChatGroup(group_id = "g", group_name = "old")))
        assertNull(SyncReload.currentGroupAfterReload(listOf(g), null))
    }
}
