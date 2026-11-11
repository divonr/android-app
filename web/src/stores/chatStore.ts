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
  /**
   * Silently re-read [chatId] if it is the open chat (a run of another device changed it).
   * No loading flag; a failed read keeps what is shown.
   */
  refreshChat: (chatId: string) => Promise<void>
  /** Silently re-read the chat list, if it is loaded (new chat / new title from a run). */
  refreshHistory: () => Promise<void>
  /**
   * The server renamed [chatId] (the automatic title, generated after a run ended): re-read the
   * chat list (if loaded) and take the new name into the open chat (if it is [chatId]). Only
   * the name of the open chat changes — its messages are left to whatever is streaming into it.
   */
  refreshTitle: (chatId: string) => Promise<void>
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

  refreshChat: async (chatId: string) => {
    if (get().currentChat?.chat_id !== chatId) return
    try {
      const fresh = await chatsApi.get(chatId)
      if (fresh && get().currentChat?.chat_id === chatId) set({ currentChat: fresh })
    } catch {
      // Keep the chat as shown: the next event or sync refresh reads it again
    }
  },

  refreshHistory: async () => {
    if (!get().history) return
    try {
      const fresh = await chatsApi.list()
      if (get().history) set({ history: fresh })
    } catch {
      // Keep the list
    }
  },

  refreshTitle: async (chatId: string) => {
    if (!get().history && get().currentChat?.chat_id !== chatId) return
    try {
      const fresh = await chatsApi.list()
      if (get().history) set({ history: fresh })
      const name = fresh.chat_history.find((c) => c.chat_id === chatId)?.preview_name
      const open = get().currentChat
      if (name !== undefined && open?.chat_id === chatId && open.preview_name !== name) {
        set({ currentChat: { ...open, preview_name: name } })
      }
    } catch {
      // Keep what is shown: the next sync refresh brings the name
    }
  },

  refreshFromSync: async () => {
    const { history, currentChat } = get()
    const tasks: Promise<void>[] = []
    if (history) {
      const fetchList = (since: UserChatHistory | null, retry: boolean): Promise<void> =>
        chatsApi.list().then(
          (fresh) => {
            if (get().history === since) set({ history: fresh })
            // The list changed while the request ran (e.g. a stream's reload of the open chat):
            // read it again rather than overwrite that newer entry with the older answer
            else if (retry) return fetchList(get().history, false)
          },
          () => { /* keep the list; the next change retries */ },
        )
      tasks.push(fetchList(history, true))
    }
    if (currentChat) {
      const chatId = currentChat.chat_id
      // Apply the answer only if nothing touched the open chat while the request ran: a stream
      // that started meanwhile (or any reload of the chat) holds data newer than this read
      const untouched = () => get().currentChat === currentChat && !get().streaming
      tasks.push(
        chatsApi.get(chatId).then(
          (fresh) => {
            if (untouched()) set({ currentChat: fresh })
          },
          (err) => {
            if (isNotFound(err) && untouched()) {
              set({ currentChat: null, error: CHAT_NOT_FOUND })
            }
          },
        ),
      )
    }
    await Promise.all(tasks)
  },
}))
