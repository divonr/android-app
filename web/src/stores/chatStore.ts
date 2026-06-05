import { create } from 'zustand'
import type { Chat, UserChatHistory } from '../api/types'
import { chats as chatsApi } from '../api/client'

interface ChatState {
  history: UserChatHistory | null
  currentChat: Chat | null
  loading: boolean
  error: string | null

  /** Load the full chat history */
  loadHistory: () => Promise<void>
  /** Load a specific chat */
  loadChat: (chatId: string) => Promise<void>
  /** Update a chat in the local store (e.g. after receiving a streaming message) */
  updateChat: (chat: Chat) => void
  /** Clear the current chat */
  clearCurrentChat: () => void
}

export const useChatStore = create<ChatState>((set, get) => ({
  history: null,
  currentChat: null,
  loading: false,
  error: null,

  loadHistory: async () => {
    set({ loading: true, error: null })
    try {
      const history = await chatsApi.list()
      set({ history, loading: false })
    } catch (err) {
      set({
        loading: false,
        error: err instanceof Error ? err.message : 'Failed to load chats',
      })
    }
  },

  loadChat: async (chatId: string) => {
    set({ loading: true, error: null })
    try {
      const chat = await chatsApi.get(chatId)
      set({ currentChat: chat, loading: false })
    } catch (err) {
      set({
        loading: false,
        error: err instanceof Error ? err.message : 'Failed to load chat',
      })
    }
  },

  updateChat: (chat: Chat) => {
    set({ currentChat: chat })
    // Also update in the history list if present
    const { history } = get()
    if (history) {
      const idx = history.chat_history.findIndex(
        (c) => c.chat_id === chat.chat_id,
      )
      if (idx >= 0) {
        const updated = [...history.chat_history]
        updated[idx] = chat
        set({ history: { ...history, chat_history: updated } })
      }
    }
  },

  clearCurrentChat: () => set({ currentChat: null }),
}))
