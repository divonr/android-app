/**
 * runsStore: what the account-level stream means for the tab — following foreign runs (other
 * device / this tab before a reload), reloading the chat and the chat list at the right
 * moments, staying passive for the tab's own runs, and taking a run over when asked.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Chat, RunState, UserChatHistory } from '../api/types'

vi.mock('../api/client', async () => ({
  chats: {
    list: vi.fn(),
    get: vi.fn(),
  },
}))
vi.mock('../api/runs', async () => ({
  cancelRun: vi.fn(),
  connectRunsStream: vi.fn(),
}))

import * as clientModule from '../api/client'
import { useChatStore } from '../stores/chatStore'
import { useRunsStore, selectForeignRun } from '../stores/runsStore'

const chat = (id: string, title: string, texts: string[] = []): Chat => ({
  chat_id: id,
  preview_name: title,
  messages: texts.map((text, i) => ({
    id: `${id}-m${i}`, role: i % 2 ? 'assistant' : 'user', text, attachments: [],
    datetime: '2024-01-01T10:00:00Z', thoughtsStatus: 'NONE',
  })) as Chat['messages'],
  systemPrompt: '',
  group: null,
  messageNodes: [],
  currentVariantPath: [],
  shareLink: '',
  shareId: '',
})
const history = (...chats: Chat[]): UserChatHistory => ({ user_name: 'u', chat_history: chats, groups: [] })

const run = (over: Partial<RunState> = {}): RunState => ({
  runId: 'r1', chatId: 'c1', provider: 'openai', modelName: 'gpt-4o', status: 'running',
  text: '', thinkingActive: false, thinkingText: '', error: null, startedAt: 1, ...over,
})
const ev = (type: string, data: Record<string, unknown> = {}, runId = 'r1', chatId = 'c1') =>
  ({ type, runId, chatId, data: { runId, chatId, ...data } })

const get = vi.mocked(clientModule.chats.get)
const list = vi.mocked(clientModule.chats.list)
const runs = () => useRunsStore.getState()
const shown = (chatId = 'c1') => selectForeignRun(chatId)(runs())

async function flush() {
  for (let i = 0; i < 10; i++) await Promise.resolve()
}

describe('runsStore', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    runs().reset()
    useChatStore.setState({
      history: history(chat('c1', 'Old title')),
      currentChat: chat('c1', 'Old title', ['q0']),
      loading: false,
      error: null,
      streaming: false,
    })
    get.mockImplementation((id: string) => Promise.resolve(chat(id, 'Fresh', ['q0', 'a0'])))
    list.mockResolvedValue(history(chat('c1', 'New title'), chat('c2', 'From the phone')))
  })

  it("a run started elsewhere: loads the question and the chat list, then streams", async () => {
    get.mockResolvedValueOnce(chat('c1', 'Old title', ['q0', 'a0', 'question from the phone']))
    runs().applyEvent(ev('run_started', { run: run() }))
    await flush()
    // The user message of the other device is on screen; a new chat would be in the list
    expect(useChatStore.getState().currentChat?.messages.map((m) => m.text)).toContain('question from the phone')
    expect(useChatStore.getState().history?.chat_history.map((c) => c.chat_id)).toEqual(['c1', 'c2'])

    runs().applyEvent(ev('thinking_started'))
    runs().applyEvent(ev('thinking_partial', { text: 'hm' }))
    runs().applyEvent(ev('partial', { text: 'Hel' }))
    runs().applyEvent(ev('partial', { text: 'lo' }))
    expect(shown()).toMatchObject({
      runId: 'r1', streaming: true, partialText: 'Hello', thinkingText: 'hm', thinkingOpen: true, thinkingDone: false,
    })
    runs().applyEvent(ev('thinking_complete', { thoughts: 'hm', durationSeconds: 2, status: 'PRESENT' }))
    runs().applyEvent(ev('tool_call', { toolId: 't', toolName: 'get_date_time', parameters: { a: 1 } }))
    expect(shown()?.toolCalls).toEqual([{ toolId: 't', toolName: 'get_date_time', parameters: { a: 1 }, executing: true }])
    runs().applyEvent(ev('tool_result', { toolId: 't', success: true, output: 'now' }))
    expect(shown()).toMatchObject({ thinkingDone: true, thinkingDurationSeconds: 2 })
    expect(shown()?.toolCalls[0]).toMatchObject({ executing: false, success: true, result: 'now' })
  })

  it('a run of a chat that is not open: no chat reload; it shows when the chat is opened', async () => {
    runs().applyEvent(ev('run_started', { run: run({ chatId: 'c2' }) }, 'r1', 'c2'))
    runs().applyEvent(ev('partial', { text: 'elsewhere' }, 'r1', 'c2'))
    await flush()
    expect(get).not.toHaveBeenCalled()
    expect(list).toHaveBeenCalledTimes(1) // the list is refreshed (a chat created on the phone)
    expect(shown('c1')).toBeNull()
    expect(shown('c2')?.partialText).toBe('elsewhere')
  })

  it('messages_added: reloads the chat, then drops the saved segment; the new segment goes on', async () => {
    let finish: (c: Chat) => void = () => {}
    runs().applyEvent(ev('run_started', { run: run() }))
    await flush()
    runs().applyEvent(ev('partial', { text: 'Let me check.' }))
    runs().applyEvent(ev('tool_call', { toolId: 't', toolName: 'x', parameters: {} }))
    runs().applyEvent(ev('tool_result', { toolId: 't', success: true, output: 'ok' }))

    get.mockClear()
    get.mockImplementationOnce(() => new Promise<Chat>((resolve) => { finish = resolve }))
    runs().applyEvent(ev('messages_added'))
    runs().applyEvent(ev('partial', { text: 'It is noon.' }))
    expect(get).toHaveBeenCalledWith('c1')
    // Until the reload is done the saved part is still shown, before the new text
    expect(shown()?.settled).toHaveLength(1)
    expect(shown()?.settled[0]).toMatchObject({ partialText: 'Let me check.' })
    expect(shown()).toMatchObject({ partialText: 'It is noon.', toolCalls: [] })

    finish(chat('c1', 'Old title', ['q0', 'Let me check.']))
    await flush()
    expect(shown()?.settled).toEqual([])
    expect(shown()?.partialText).toBe('It is noon.')
    expect(useChatStore.getState().currentChat?.messages).toHaveLength(2)
  })

  it.each(['complete', 'cancelled'])('%s: reloads the chat and the list, then clears the run', async (type) => {
    runs().applyEvent(ev('run_started', { run: run() }))
    await flush()
    runs().applyEvent(ev('partial', { text: 'answer' }))
    get.mockClear()
    list.mockClear()
    list.mockResolvedValue(history(chat('c1', 'Generated title')))

    runs().applyEvent(ev(type, type === 'complete' ? { text: 'answer', messageId: 'm' } : {}))
    expect(shown()?.partialText).toBe('answer') // still shown while the reload runs
    await flush()
    expect(get).toHaveBeenCalledWith('c1')
    expect(useChatStore.getState().history?.chat_history[0].preview_name).toBe('Generated title')
    expect(shown()).toBeNull()
    expect(runs().runs).toEqual({})
    expect(runs().errors).toEqual({})
  })

  it('error of a foreign run: clears it and keeps the message for the chat page', async () => {
    runs().applyEvent(ev('run_started', { run: run() }))
    runs().applyEvent(ev('partial', { text: 'so far' }))
    runs().applyEvent(ev('error', { error: 'Rate limited' }))
    expect(shown()).toBeNull()
    expect(runs().errors).toEqual({ c1: 'Rate limited' })
    runs().clearError('c1')
    expect(runs().errors).toEqual({})
  })

  describe('chat_updated (the title, generated after the run ended)', () => {
    it('refreshes the chat list and the open chat\'s name, not its messages', async () => {
      runs().applyEvent(ev('run_started', { run: run() }))
      runs().applyEvent(ev('complete', { text: 'a', messageId: 'm' }))
      await flush()
      vi.clearAllMocks()
      const messages = useChatStore.getState().currentChat?.messages

      runs().applyEvent(ev('chat_updated'))
      await flush()
      expect(list).toHaveBeenCalledTimes(1)
      expect(get).not.toHaveBeenCalled()
      expect(useChatStore.getState().history?.chat_history[0].preview_name).toBe('New title')
      expect(useChatStore.getState().currentChat?.preview_name).toBe('New title')
      expect(useChatStore.getState().currentChat?.messages).toBe(messages)
      expect(runs().runs).toEqual({})
    })

    it('for the tab\'s own run too: its per-request stream ended before the title existed', async () => {
      runs().applyEvent(ev('run_started', { run: run() }))
      runs().claim('r1')
      runs().finishOwn('r1') // the per-request stream delivered `complete`
      await flush()
      vi.clearAllMocks()

      runs().applyEvent(ev('chat_updated'))
      await flush()
      expect(list).toHaveBeenCalledTimes(1)
      expect(useChatStore.getState().currentChat?.preview_name).toBe('New title')
      expect(runs().ownRunIds).toEqual({})
    })

    it('of a chat that is not open: only the list; while another reply streams: the stream is untouched', async () => {
      runs().applyEvent(ev('run_started', { run: run({ runId: 'r2' }) }, 'r2'))
      runs().applyEvent(ev('partial', { text: 'next reply' }, 'r2'))
      await flush()
      vi.clearAllMocks()

      runs().applyEvent(ev('chat_updated', {}, 'r-old', 'c2'))
      runs().applyEvent(ev('chat_updated', {}, 'r1', 'c1'))
      await flush()
      expect(get).not.toHaveBeenCalled()
      expect(useChatStore.getState().history?.chat_history.map((c) => c.preview_name)).toEqual(['New title', 'From the phone'])
      expect(useChatStore.getState().currentChat?.preview_name).toBe('New title')
      expect(shown()?.partialText).toBe('next reply')
    })

    it('a failed read keeps what is shown', async () => {
      list.mockRejectedValueOnce(new Error('offline'))
      runs().applyEvent(ev('chat_updated'))
      await flush()
      expect(useChatStore.getState().history?.chat_history[0].preview_name).toBe('Old title')
      expect(useChatStore.getState().currentChat?.preview_name).toBe('Old title')
    })
  })

  it('events of an unknown run are ignored', async () => {
    runs().applyEvent(ev('partial', { text: 'ghost' }))
    runs().applyEvent(ev('complete', { text: 'ghost', messageId: 'm' }))
    await flush()
    expect(runs().runs).toEqual({})
    expect(get).not.toHaveBeenCalled()
    expect(list).not.toHaveBeenCalled()
  })

  describe('own runs (the tab reads their per-request stream)', () => {
    it('are followed silently: not shown as foreign, no reloads', async () => {
      runs().claim('r1')
      runs().applyEvent(ev('run_started', { run: run() }))
      runs().applyEvent(ev('partial', { text: 'mine' }))
      runs().applyEvent(ev('messages_added'))
      runs().applyEvent(ev('partial', { text: 'more' }))
      await flush()
      expect(shown()).toBeNull()
      expect(runs().runs.r1).toMatchObject({ partialText: 'more', settled: [] })

      runs().applyEvent(ev('complete', { text: 'more', messageId: 'm' }))
      await flush()
      expect(runs().runs).toEqual({})
      expect(get).not.toHaveBeenCalled()
      expect(list).not.toHaveBeenCalled()
    })

    it('claimed after run_started (the account stream was faster): passive from then on', async () => {
      runs().applyEvent(ev('run_started', { run: run() }))
      await flush()
      runs().claim('r1')
      get.mockClear()
      list.mockClear()
      runs().applyEvent(ev('partial', { text: 'mine' }))
      runs().applyEvent(ev('error', { error: 'boom' }))
      await flush()
      expect(runs().errors).toEqual({}) // the per-request stream reports it
      expect(get).not.toHaveBeenCalled()
    })

    it('finishOwn: the late account-level events of the run change nothing', async () => {
      runs().claim('r1')
      runs().applyEvent(ev('run_started', { run: run() }))
      runs().finishOwn('r1')
      expect(runs().ownRunIds).toEqual({})
      runs().applyEvent(ev('partial', { text: 'late' }))
      runs().applyEvent(ev('complete', { text: 'late', messageId: 'm' }))
      await flush()
      expect(runs().runs).toEqual({})
      expect(get).not.toHaveBeenCalled()
    })

    it('release of a running run: it becomes a foreign run with everything streamed so far', async () => {
      runs().claim('r1')
      runs().applyEvent(ev('run_started', { run: run() }))
      runs().applyEvent(ev('partial', { text: 'before the blip' }))
      runs().release('r1', 'c1')
      await flush()
      expect(get).not.toHaveBeenCalled()
      expect(shown()?.partialText).toBe('before the blip')

      runs().applyEvent(ev('complete', { text: 'x', messageId: 'm' }))
      await flush()
      expect(get).toHaveBeenCalledWith('c1')
      expect(shown()).toBeNull()
    })

    it('release of a run that already ended: the chat is re-read at once', async () => {
      runs().claim('r1')
      runs().applyEvent(ev('run_started', { run: run() }))
      runs().applyEvent(ev('complete', { text: 'x', messageId: 'm' }))
      await flush()
      expect(get).not.toHaveBeenCalled()
      runs().release('r1', 'c1')
      await flush()
      expect(get).toHaveBeenCalledWith('c1')
      expect(list).toHaveBeenCalled()
    })
  })

  describe('snapshot', () => {
    it('shows running runs (this tab after a reload) and skips finished ones', async () => {
      runs().applySnapshot([
        run({ runId: 'r1', text: 'half', thinkingText: 'th', thinkingActive: true }),
        run({ runId: 'r2', chatId: 'c2', status: 'complete', text: 'done' }),
        run({ runId: 'r3', chatId: 'c3', status: 'error', error: 'old failure' }),
      ])
      await flush()
      expect(Object.keys(runs().runs)).toEqual(['r1'])
      expect(shown()).toMatchObject({
        partialText: 'half', thinkingText: 'th', thinkingOpen: true, thinkingDone: false, streaming: true,
      })
      expect(shown()?.thinkingStartTime).toEqual(expect.any(Number))
      // An error that ended before this tab was looking is not reported
      expect(runs().errors).toEqual({})
      // The open chat is re-read: its question may be another device's
      expect(get).toHaveBeenCalledWith('c1')
    })

    it('thinking that is over is shown as done', () => {
      runs().applySnapshot([run({ text: 'x', thinkingText: 'th', thinkingActive: false })])
      expect(shown()).toMatchObject({ thinkingOpen: true, thinkingDone: true })
    })

    it('after a reconnect: a run that ended meanwhile is cleared and its chat re-read', async () => {
      runs().applySnapshot([run()])
      runs().applyEvent(ev('partial', { text: 'x' }))
      await flush()
      get.mockClear()
      list.mockClear()

      runs().applySnapshot([run({ status: 'complete', text: 'xyz' })])
      await flush()
      expect(runs().runs).toEqual({})
      expect(get).toHaveBeenCalledWith('c1')
      expect(list).toHaveBeenCalled()
    })

    it('after a reconnect: a run the server forgot is cleared, and a missed error is reported', async () => {
      runs().applySnapshot([run({ runId: 'r1' }), run({ runId: 'r2', chatId: 'c2' })])
      await flush()
      runs().applySnapshot([run({ runId: 'r2', chatId: 'c2', status: 'error', error: 'died' })])
      await flush()
      expect(runs().runs).toEqual({})
      expect(runs().errors).toEqual({ c2: 'died' })
    })

    it('after a reconnect: the text is the snapshot\'s (no duplicated deltas)', () => {
      runs().applySnapshot([run({ text: 'ab' })])
      runs().applyEvent(ev('partial', { text: 'c' }))
      runs().applySnapshot([run({ text: 'abcd' })])
      runs().applyEvent(ev('partial', { text: 'e' }))
      expect(shown()?.partialText).toBe('abcde')
    })

    it('own runs: no reloads, whether running or gone from the snapshot', async () => {
      runs().claim('r1')
      runs().applySnapshot([run()])
      await flush()
      expect(shown()).toBeNull()
      runs().applySnapshot([])
      await flush()
      expect(get).not.toHaveBeenCalled()
      expect(list).not.toHaveBeenCalled()
      expect(runs().ownRunIds).toEqual({ r1: true })
    })
  })
})
