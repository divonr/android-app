/**
 * T6 review: a live sync refresh must never replace the open chat while a reply streams into
 * it. useSyncRefresh only defers STARTING a refresh during a stream; a refresh already in
 * flight when the stream starts still applies its (older) snapshot when it resolves.
 */
import { describe, it, expect, vi } from 'vitest'
import type { Chat } from '../api/types'

vi.mock('../api/client', async () => ({
  chats: {
    list: vi.fn(),
    get: vi.fn(),
  },
}))

import * as clientModule from '../api/client'
import { useChatStore } from '../stores/chatStore'

const chat = (id: string, texts: string[]): Chat => ({
  chat_id: id,
  preview_name: 'Chat',
  messages: texts.map((text, i) => ({ id: `m${i}`, role: i % 2 ? 'assistant' : 'user', text, attachments: [] })),
  systemPrompt: '',
  group: null,
  messageNodes: [],
  currentVariantPath: [],
  shareLink: '',
  shareId: '',
}) as unknown as Chat

describe('refreshFromSync vs a stream that starts while it is in flight', () => {
  // Regression (T6 review): refreshFromSync used to apply its response unconditionally (only the
  // chat id was checked), so a snapshot read before the stream's saves (user message / tool
  // messages reloaded by ChatPage on `messages_added`) overwrote the newer chat mid-stream.
  it('does not overwrite the open chat with a snapshot older than the stream', async () => {
    let resolveGet: (c: Chat) => void = () => {}
    vi.mocked(clientModule.chats.get).mockImplementation(
      () => new Promise<Chat>((resolve) => { resolveGet = resolve }),
    )
    useChatStore.setState({
      history: null,
      currentChat: chat('c1', ['q1', 'a1']),
      loading: false,
      error: null,
      streaming: false,
    })

    // The poll saw a new tick while idle: the refresh starts
    const refresh = useChatStore.getState().refreshFromSync()
    // The user sends; the stream starts and ChatPage reloads the chat after a mid-stream save
    useChatStore.getState().setStreaming(true)
    useChatStore.getState().updateChat(chat('c1', ['q1', 'a1', 'q2', 'tool output']))
    // The refresh's GET (read before the send) answers now
    resolveGet(chat('c1', ['q1', 'a1']))
    await refresh

    expect(useChatStore.getState().currentChat?.messages.map((m) => m.text))
      .toEqual(['q1', 'a1', 'q2', 'tool output'])
  })

  it('re-reads the chat list when it changed while the refresh ran, instead of applying the older answer', async () => {
    const list = (texts: string[][]) => ({
      user_name: 'u',
      chat_history: texts.map((t, i) => chat(`c${i}`, t)),
    }) as unknown as import('../api/types').UserChatHistory
    let calls = 0
    let resolveFirst: (h: ReturnType<typeof list>) => void = () => {}
    vi.mocked(clientModule.chats.list).mockImplementation(() => {
      calls++
      if (calls === 1) return new Promise((resolve) => { resolveFirst = resolve })
      return Promise.resolve(list([['q1', 'a1', 'q2', 'a2'], ['pulled']]))
    })
    useChatStore.setState({
      history: list([['q1', 'a1']]),
      currentChat: null,
      loading: false,
      error: null,
      streaming: false,
    })

    const refresh = useChatStore.getState().refreshFromSync()
    // The open chat's entry is updated (stream reload) while the list request runs
    useChatStore.setState({ history: list([['q1', 'a1', 'q2', 'a2']]) })
    resolveFirst(list([['q1', 'a1'], ['pulled']]))
    await refresh

    expect(calls).toBe(2)
    expect(useChatStore.getState().history?.chat_history.map((c) => c.messages.map((m) => m.text)))
      .toEqual([['q1', 'a1', 'q2', 'a2'], ['pulled']])
  })
})
