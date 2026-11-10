/**
 * Cloud runs API: the cancel endpoint and the account-level stream connection
 * (GET /api/runs/stream): snapshot + events, pings, reconnect with backoff, 401, close.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { cancelRun, connectRunsStream } from '../api/runs'
import type { RunStreamEvent } from '../api/runs'
import type { RunState } from '../api/types'

const enc = new TextEncoder()

/** A response body the test feeds by hand. */
function liveBody() {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const stream = new ReadableStream<Uint8Array>({ start(c) { controller = c } })
  return {
    stream,
    send: (text: string) => controller.enqueue(enc.encode(text)),
    end: () => controller.close(),
    fail: () => controller.error(new Error('connection reset')),
  }
}

const ok = (stream: ReadableStream<Uint8Array>) => ({ ok: true, status: 200, body: stream })

async function flush() {
  for (let i = 0; i < 10; i++) await Promise.resolve()
}

describe('cancelRun', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('POSTs keepPartial to the run cancel endpoint with the session cookie', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Type': 'application/json' }),
      json: () => Promise.resolve({ runId: 'r 1', status: 'running' }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(cancelRun('r 1', true)).resolves.toEqual({ runId: 'r 1', status: 'running' })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/runs/r%201/cancel')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('include')
    expect(JSON.parse(init.body)).toEqual({ keepPartial: true })
  })

  it('rejects with the status for an unknown run', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: () => Promise.resolve({ error: 'Run not found' }),
    }))
    await expect(cancelRun('gone', false)).rejects.toMatchObject({ status: 404 })
  })
})

describe('connectRunsStream', () => {
  let snapshots: RunState[][]
  let events: RunStreamEvent[]
  let disconnects: number
  const handlers = {
    onSnapshot: (runs: RunState[]) => { snapshots.push(runs) },
    onEvent: (e: RunStreamEvent) => { events.push(e) },
    onDisconnect: () => { disconnects++ },
  }

  beforeEach(() => {
    vi.useFakeTimers()
    snapshots = []
    events = []
    disconnects = 0
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('delivers the snapshot, then live events with their run and chat ids; pings are ignored', async () => {
    const body = liveBody()
    const fetchMock = vi.fn().mockResolvedValue(ok(body.stream))
    vi.stubGlobal('fetch', fetchMock)

    const handle = connectRunsStream(handlers)
    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/runs/stream')
    expect(init.credentials).toBe('include')

    body.send('event: snapshot\ndata: {"runs":[{"runId":"r1","chatId":"c1","status":"running"}]}\n\n')
    body.send(': ping\n\n')
    body.send('event: partial\ndata: {"runId":"r1","chatId":"c1","te')
    body.send('xt":"hi"}\n\n: ping\n\nevent: complete\ndata: {"runId":"r1","chatId":"c1","text":"hi","messageId":"m"}\n\n')
    await flush()

    expect(snapshots).toEqual([[{ runId: 'r1', chatId: 'c1', status: 'running' }]])
    expect(events.map((e) => [e.type, e.runId, e.chatId])).toEqual([
      ['partial', 'r1', 'c1'],
      ['complete', 'r1', 'c1'],
    ])
    expect(events[0].data.text).toBe('hi')
    expect(disconnects).toBe(0)
    handle.close()
  })

  it('skips broken frames and events without ids instead of dropping the connection', async () => {
    const body = liveBody()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(body.stream)))
    const handle = connectRunsStream(handlers)
    await flush()
    body.send('event: partial\ndata: {not json\n\n')
    body.send('event: partial\ndata: {"text":"no ids"}\n\n')
    body.send('event: partial\ndata: {"runId":"r1","chatId":"c1","text":"ok"}\n\n')
    await flush()
    expect(events).toHaveLength(1)
    expect(disconnects).toBe(0)
    handle.close()
  })

  it('reconnects with a growing backoff and resets it after a snapshot', async () => {
    const bodies = [liveBody(), liveBody()]
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(ok(bodies[0].stream))
      .mockResolvedValueOnce(ok(bodies[1].stream))
    vi.stubGlobal('fetch', fetchMock)

    const handle = connectRunsStream(handlers, { minBackoffMs: 1000, maxBackoffMs: 30000 })
    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(disconnects).toBe(1)

    await vi.advanceTimersByTimeAsync(999)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMock).toHaveBeenCalledTimes(2) // after 1 s

    await vi.advanceTimersByTimeAsync(1999)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMock).toHaveBeenCalledTimes(3) // after 2 s more

    // Connected: a snapshot resets the backoff; then the server goes away
    bodies[0].send('event: snapshot\ndata: {"runs":[]}\n\n')
    await flush()
    expect(snapshots).toHaveLength(1)
    bodies[0].fail()
    await flush()
    expect(disconnects).toBe(3)
    await vi.advanceTimersByTimeAsync(1000)
    expect(fetchMock).toHaveBeenCalledTimes(4) // 1 s again, not 4 s

    // Every connection starts with its own snapshot
    bodies[1].send('event: snapshot\ndata: {"runs":[]}\n\n')
    await flush()
    expect(snapshots).toHaveLength(2)
    handle.close()
  })

  it('reconnects when the stream ends, and when nothing (not even a ping) arrives', async () => {
    const first = liveBody()
    const second = liveBody()
    const signals: AbortSignal[] = []
    const fetchMock = vi.fn((_url: string, init: RequestInit) => {
      signals.push(init.signal as AbortSignal)
      const body = signals.length === 1 ? first : second
      // Like a real fetch: an abort breaks the body
      init.signal?.addEventListener('abort', () => { try { body.fail() } catch { /* closed */ } })
      return Promise.resolve(ok(body.stream))
    })
    vi.stubGlobal('fetch', fetchMock)

    const handle = connectRunsStream(handlers, { minBackoffMs: 1000, idleTimeoutMs: 45000 })
    await flush()
    first.end()
    await flush()
    expect(disconnects).toBe(1)
    await vi.advanceTimersByTimeAsync(1000)
    expect(fetchMock).toHaveBeenCalledTimes(2)

    // A ping keeps the silent connection; 45 s of nothing drops it
    await vi.advanceTimersByTimeAsync(30000)
    second.send(': ping\n\n')
    await vi.advanceTimersByTimeAsync(30000)
    expect(signals[1].aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(15000)
    expect(signals[1].aborted).toBe(true)
    expect(disconnects).toBe(2)
    handle.close()
  })

  it('stops for good on 401 and tells the app the session is gone', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 401, body: null })
    vi.stubGlobal('fetch', fetchMock)
    const unauth = vi.fn()
    window.addEventListener('api:unauthenticated', unauth)

    connectRunsStream(handlers)
    await flush()
    await vi.advanceTimersByTimeAsync(120000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(unauth).toHaveBeenCalledTimes(1)
    expect(disconnects).toBe(0)
    window.removeEventListener('api:unauthenticated', unauth)
  })

  it('retries after a server error (e.g. the backend restarting)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 502, body: null })
    vi.stubGlobal('fetch', fetchMock)
    const handle = connectRunsStream(handlers, { minBackoffMs: 1000 })
    await flush()
    await vi.advanceTimersByTimeAsync(1000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    handle.close()
  })

  it('close() aborts the connection and never reconnects', async () => {
    const body = liveBody()
    let signal: AbortSignal | undefined
    const fetchMock = vi.fn((_url: string, init: RequestInit) => {
      signal = init.signal as AbortSignal
      init.signal?.addEventListener('abort', () => { try { body.fail() } catch { /* closed */ } })
      return Promise.resolve(ok(body.stream))
    })
    vi.stubGlobal('fetch', fetchMock)

    const handle = connectRunsStream(handlers)
    await flush()
    handle.close()
    await flush()
    expect(signal?.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(120000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(disconnects).toBe(0)

    // Also while waiting to reconnect
    const failing = vi.fn().mockRejectedValue(new Error('offline'))
    vi.stubGlobal('fetch', failing)
    const waiting = connectRunsStream(handlers, { minBackoffMs: 1000 })
    await flush()
    waiting.close()
    await vi.advanceTimersByTimeAsync(120000)
    expect(failing).toHaveBeenCalledTimes(1)
  })
})
