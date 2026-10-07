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
  // T6 bug: refreshFromSync applies its response unconditionally (only the chat id is checked),
  // so a snapshot read before the stream's saves (user message / tool messages reloaded by
  // ChatPage on `messages_added`) overwrites the newer chat mid-stream.
  it.skip('does not overwrite the open chat with a snapshot older than the stream', async () => {
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
})
