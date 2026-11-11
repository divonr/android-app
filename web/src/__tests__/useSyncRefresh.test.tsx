/**
 * Live sync refresh: the web polls GET /api/sync/status (lastChangeTick) while the tab is
 * visible and on focus / visibility changes, and reloads the chat list + open chat when the
 * tick changes — not while a reply streams (then right after it ends).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import type { Chat, UserChatHistory } from '../api/types'

vi.mock('../api/client', async () => ({
  sync: {
    status: vi.fn(),
    pull: vi.fn(),
  },
  chats: {
    list: vi.fn(),
    get: vi.fn(),
  },
}))

import * as clientModule from '../api/client'
import { useChatStore, CHAT_NOT_FOUND } from '../stores/chatStore'
import { useSyncRefresh, SYNC_CHANGED_EVENT, SYNC_POLL_INTERVAL_MS } from '../hooks/useSyncRefresh'

const chat = (id: string, title: string): Chat => ({
  chat_id: id,
  preview_name: title,
  messages: [],
  systemPrompt: '',
  group: null,
  messageNodes: [],
  currentVariantPath: [],
  shareLink: '',
  shareId: '',
})

const history = (...chats: Chat[]): UserChatHistory => ({ user_name: 'u', chat_history: chats, groups: [] })

const status = vi.mocked(clientModule.sync.status)
const list = vi.mocked(clientModule.chats.list)
const get = vi.mocked(clientModule.chats.get)

let tick = 0
let visibility: DocumentVisibilityState = 'visible'

/** Let pending promise callbacks run (fake timers don't advance microtasks by themselves). */
async function flush() {
  await act(async () => {
    for (let i = 0; i < 5; i++) await Promise.resolve()
  })
}

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
  await flush()
}

describe('useSyncRefresh', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    tick = 0
    visibility = 'visible'
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
    status.mockImplementation(async () => ({
      enabled: true, serverBaseUrl: 'http://sync', lastChangeTick: tick, reachable: null,
    }))
    list.mockResolvedValue(history(chat('c1', 'Old'), chat('c2', 'From the phone')))
    get.mockResolvedValue({ ...chat('c1', 'Old'), messages: [] })
    useChatStore.setState({
      history: history(chat('c1', 'Old')),
      currentChat: chat('c1', 'Old'),
      loading: false,
      error: null,
      streaming: false,
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('polls without probing and treats the first tick as the baseline', async () => {
    tick = 7
    renderHook(() => useSyncRefresh())
    await flush()
    expect(status).toHaveBeenCalledWith(false)
    expect(list).not.toHaveBeenCalled()
    expect(get).not.toHaveBeenCalled()

    await advance(SYNC_POLL_INTERVAL_MS)
    expect(status).toHaveBeenCalledTimes(2)
    expect(list).not.toHaveBeenCalled()
  })

  it('reloads the chat list and the open chat when the tick changes', async () => {
    const onChanged = vi.fn()
    window.addEventListener(SYNC_CHANGED_EVENT, onChanged)
    renderHook(() => useSyncRefresh())
    await flush()

    tick = 1
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(list).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('c1')
    expect(onChanged).toHaveBeenCalledTimes(1)
    expect(useChatStore.getState().history?.chat_history.map((c) => c.preview_name)).toEqual(['Old', 'From the phone'])
    expect(useChatStore.getState().loading).toBe(false)
    window.removeEventListener(SYNC_CHANGED_EVENT, onChanged)
  })

  it('waits for a streaming reply to end before reloading', async () => {
    renderHook(() => useSyncRefresh())
    await flush()

    act(() => useChatStore.getState().setStreaming(true))
    tick = 1
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(list).not.toHaveBeenCalled()
    expect(get).not.toHaveBeenCalled()

    act(() => useChatStore.getState().setStreaming(false))
    await flush()
    expect(list).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('c1')
  })

  it('does not poll a hidden tab and checks at once when it becomes visible or focused', async () => {
    renderHook(() => useSyncRefresh())
    await flush()
    expect(status).toHaveBeenCalledTimes(1)

    visibility = 'hidden'
    await advance(SYNC_POLL_INTERVAL_MS * 3)
    expect(status).toHaveBeenCalledTimes(1)

    visibility = 'visible'
    tick = 2
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    await flush()
    expect(status).toHaveBeenCalledTimes(2)
    expect(list).toHaveBeenCalledTimes(1)

    tick = 3
    act(() => { window.dispatchEvent(new Event('focus')) })
    await flush()
    expect(status).toHaveBeenCalledTimes(3)
    expect(list).toHaveBeenCalledTimes(2)
  })

  it('stops polling when unmounted and survives a failing status call', async () => {
    status.mockRejectedValueOnce(new Error('offline'))
    const { unmount } = renderHook(() => useSyncRefresh())
    await flush()
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(status).toHaveBeenCalledTimes(2)
    unmount()
    await advance(SYNC_POLL_INTERVAL_MS * 2)
    expect(status).toHaveBeenCalledTimes(2)
  })
})

describe('chatStore.refreshFromSync', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useChatStore.setState({ history: null, currentChat: null, loading: false, error: null, streaming: false })
  })

  it('reloads only what is loaded', async () => {
    useChatStore.setState({ currentChat: chat('c1', 'Old') })
    get.mockResolvedValue(chat('c1', 'Renamed elsewhere'))
    await useChatStore.getState().refreshFromSync()
    expect(list).not.toHaveBeenCalled()
    expect(useChatStore.getState().currentChat?.preview_name).toBe('Renamed elsewhere')
  })

  it('drops an open chat that was deleted on another device', async () => {
    useChatStore.setState({ currentChat: chat('c1', 'Old') })
    get.mockRejectedValue(Object.assign(new Error('HTTP 404'), { status: 404, body: { error: 'Chat not found' } }))
    await useChatStore.getState().refreshFromSync()
    expect(useChatStore.getState().currentChat).toBeNull()
    expect(useChatStore.getState().error).toBe(CHAT_NOT_FOUND)
  })

  it('keeps the open chat on other errors and when the user moved to another chat', async () => {
    useChatStore.setState({ currentChat: chat('c1', 'Old') })
    get.mockRejectedValueOnce(Object.assign(new Error('HTTP 500'), { status: 500 }))
    await useChatStore.getState().refreshFromSync()
    expect(useChatStore.getState().currentChat?.chat_id).toBe('c1')

    let resolve: (c: Chat) => void = () => {}
    get.mockImplementationOnce(() => new Promise<Chat>((r) => { resolve = r }))
    const pending = useChatStore.getState().refreshFromSync()
    useChatStore.setState({ currentChat: chat('c9', 'Other') })
    resolve(chat('c1', 'Late'))
    await pending
    expect(useChatStore.getState().currentChat?.chat_id).toBe('c9')
  })
})
