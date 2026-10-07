import { create } from 'zustand'
import type { Chat, UserChatHistory } from '../api/types'
import { chats as chatsApi } from '../api/client'

/** A 404 ApiError (checked structurally: tests mock the client module). */
function isNotFound(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { status?: unknown }).status === 404
}

interface ChatState {
  history: UserChatHistory | null
  currentChat: Chat | null
  loading: boolean
  error: string | null
  /** True while a reply streams into the open chat (sync refreshes wait for its end). */
  streaming: boolean

  /** Load the full chat history */
  loadHistory: () => Promise<void>
  /** Load a specific chat */
  loadChat: (chatId: string) => Promise<void>
  /** Update a chat in the local store (e.g. after receiving a streaming message) */
  updateChat: (chat: Chat) => void
  /** Clear the current chat */
  clearCurrentChat: () => void
  setStreaming: (streaming: boolean) => void
  /**
   * Silently re-read what a server-side sync pull may have changed: the chat list (if loaded)
   * and the open chat (if any). No loading flag (no spinner); a chat deleted elsewhere is
   * dropped with error 'Chat not found'.
   */
  refreshFromSync: () => Promise<void>
}

export const CHAT_NOT_FOUND = 'Chat not found'

export const useChatStore = create<ChatState>((set, get) => ({
  history: null,
  currentChat: null,
  loading: false,
  error: null,
  streaming: false,

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
        error: isNotFound(err)
          ? CHAT_NOT_FOUND
          : err instanceof Error ? err.message : 'Failed to load chat',
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

  setStreaming: (streaming: boolean) => set({ streaming }),

  refreshFromSync: async () => {
    const { history, currentChat } = get()
    const tasks: Promise<void>[] = []
    if (history) {
      tasks.push(
        chatsApi.list().then(
          (fresh) => set({ history: fresh }),
          () => { /* keep the list; the next change retries */ },
        ),
      )
    }
    if (currentChat) {
      const chatId = currentChat.chat_id
      tasks.push(
        chatsApi.get(chatId).then(
          (fresh) => {
            // Only if the user is still on that chat
            if (get().currentChat?.chat_id === chatId) set({ currentChat: fresh })
          },
          (err) => {
            if (isNotFound(err) && get().currentChat?.chat_id === chatId) {
              set({ currentChat: null, error: CHAT_NOT_FOUND })
            }
          },
        ),
      )
    }
    await Promise.all(tasks)
  },
}))
