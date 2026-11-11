/** useRunsStream: one account-level stream connection per signed-in layout, feeding runsStore. */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { RunsStreamHandlers } from '../api/runs'

const close = vi.fn()
vi.mock('../api/runs', async () => ({
  cancelRun: vi.fn(),
  connectRunsStream: vi.fn(() => ({ close })),
}))
vi.mock('../api/client', async () => ({
  chats: { list: vi.fn().mockResolvedValue(null), get: vi.fn().mockResolvedValue(null) },
}))

import * as runsModule from '../api/runs'
import { useRunsStore } from '../stores/runsStore'
import { useRunsStream } from '../hooks/useRunsStream'

describe('useRunsStream', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useRunsStore.getState().reset()
  })

  it('opens one connection, feeds the store, and closes + forgets everything on unmount', () => {
    const { rerender, unmount } = renderHook(() => useRunsStream())
    rerender()
    expect(runsModule.connectRunsStream).toHaveBeenCalledTimes(1)
    const handlers = vi.mocked(runsModule.connectRunsStream).mock.calls[0][0] as RunsStreamHandlers

    handlers.onSnapshot([{
      runId: 'r1', chatId: 'c1', provider: 'p', modelName: 'm', status: 'running',
      text: 'a', thinkingActive: false, thinkingText: '', error: null, startedAt: 1,
    }])
    handlers.onEvent({ type: 'partial', runId: 'r1', chatId: 'c1', data: { text: 'b' } })
    expect(useRunsStore.getState().runs.r1.partialText).toBe('ab')

    unmount()
    expect(close).toHaveBeenCalledTimes(1)
    expect(useRunsStore.getState().runs).toEqual({})
  })
})
