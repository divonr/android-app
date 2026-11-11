import { describe, it, expect, vi, beforeEach } from 'vitest'
import { parseSseChunk, sendStream, resendStream } from '../api/stream'
import type { StreamCallbacks } from '../api/stream'
import type { ResendMessageRequest } from '../api/types'

// ---------------------------------------------------------------------------
// parseSseChunk — unit tests
// ---------------------------------------------------------------------------

describe('parseSseChunk', () => {
  it('parses a single complete frame', () => {
    const input = 'event: partial\ndata: {"text":"hello"}\n\n'
    const { frames, remainder } = parseSseChunk(input)
    expect(frames).toHaveLength(1)
    expect(frames[0]).toEqual({ event: 'partial', data: '{"text":"hello"}' })
    expect(remainder).toBe('')
  })

  it('keeps incomplete frame as remainder', () => {
    const input = 'event: partial\ndata: {"text":"hel'
    const { frames, remainder } = parseSseChunk(input)
    expect(frames).toHaveLength(0)
    expect(remainder).toBe(input)
  })

  it('handles chunk boundary splitting a frame', () => {
    const chunk1 = 'event: partial\ndata: {"text":'
    const chunk2 = '"world"}\n\n'
    const { frames: f1, remainder: r1 } = parseSseChunk(chunk1)
    expect(f1).toHaveLength(0)

    const { frames: f2, remainder: r2 } = parseSseChunk(r1 + chunk2)
    expect(f2).toHaveLength(1)
    expect(f2[0]).toEqual({ event: 'partial', data: '{"text":"world"}' })
    expect(r2).toBe('')
  })

  it('parses multiple frames in one chunk', () => {
    const input =
      'event: partial\ndata: {"text":"a"}\n\n' +
      'event: partial\ndata: {"text":"b"}\n\n'
    const { frames } = parseSseChunk(input)
    expect(frames).toHaveLength(2)
    expect(frames[0].data).toBe('{"text":"a"}')
    expect(frames[1].data).toBe('{"text":"b"}')
  })

  it('parses complete event', () => {
    const input =
      'event: complete\ndata: {"text":"final","messageId":"msg-1"}\n\n'
    const { frames } = parseSseChunk(input)
    expect(frames[0].event).toBe('complete')
    expect(frames[0].data).toContain('msg-1')
  })

  it('parses thinking_started event with empty data', () => {
    const input = 'event: thinking_started\ndata: {}\n\n'
    const { frames } = parseSseChunk(input)
    expect(frames[0].event).toBe('thinking_started')
    expect(frames[0].data).toBe('{}')
  })

  it('parses error event', () => {
    const input = 'event: error\ndata: {"error":"something went wrong"}\n\n'
    const { frames } = parseSseChunk(input)
    expect(frames[0].event).toBe('error')
    expect(frames[0].data).toContain('something went wrong')
  })

  it('handles tool_call event with parameters', () => {
    const payload = JSON.stringify({
      toolId: 't1',
      toolName: 'web_search',
      parameters: { query: 'test' },
    })
    const input = `event: tool_call\ndata: ${payload}\n\n`
    const { frames } = parseSseChunk(input)
    expect(frames[0].event).toBe('tool_call')
    const parsed = JSON.parse(frames[0].data)
    expect(parsed.toolName).toBe('web_search')
  })

  it('parses messages_added event', () => {
    const input = 'event: messages_added\ndata: {}\n\n'
    const { frames } = parseSseChunk(input)
    expect(frames).toHaveLength(1)
    expect(frames[0].event).toBe('messages_added')
    expect(frames[0].data).toBe('{}')
  })

  it('handles \\r\\n line endings', () => {
    const input = 'event: partial\r\ndata: {"text":"cr-lf"}\r\n\r\n'
    const { frames } = parseSseChunk(input)
    expect(frames).toHaveLength(1)
    expect(frames[0].event).toBe('partial')
    expect(JSON.parse(frames[0].data).text).toBe('cr-lf')
  })

  it('accumulates partial frames across 3 splits', () => {
    const full = 'event: complete\ndata: {"text":"full","messageId":"id42"}\n\n'
    const third = Math.floor(full.length / 3)

    const c1 = full.slice(0, third)
    const c2 = full.slice(third, third * 2)
    const c3 = full.slice(third * 2)

    const { frames: f1, remainder: r1 } = parseSseChunk(c1)
    expect(f1).toHaveLength(0)

    const { frames: f2, remainder: r2 } = parseSseChunk(r1 + c2)
    expect(f2).toHaveLength(0)

    const { frames: f3 } = parseSseChunk(r2 + c3)
    expect(f3).toHaveLength(1)
    expect(f3[0].event).toBe('complete')
    expect(JSON.parse(f3[0].data).messageId).toBe('id42')
  })
})

// ---------------------------------------------------------------------------
// sendStream — fetch mock tests
// ---------------------------------------------------------------------------

describe('sendStream callbacks', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  function makeSseBody(frames: string[]): ReadableStream<Uint8Array> {
    const enc = new TextEncoder()
    return new ReadableStream({
      start(controller) {
        for (const f of frames) {
          controller.enqueue(enc.encode(f))
        }
        controller.close()
      },
    })
  }

  it('fires onPartial for each partial event', async () => {
    const sseData =
      'event: partial\ndata: {"text":"hello"}\n\n' +
      'event: partial\ndata: {"text":" world"}\n\n' +
      'event: complete\ndata: {"text":"hello world","messageId":"m1"}\n\n'

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        body: makeSseBody([sseData]),
        headers: new Headers({ 'Content-Type': 'text/event-stream' }),
      }),
    )

    const partials: string[] = []
    let completed = false
    const callbacks: StreamCallbacks = {
      onPartial: (e) => partials.push(e.text),
      onComplete: () => { completed = true },
    }

    const handle = sendStream(
      {
        chatId: 'c1',
        provider: 'openai',
        modelName: 'gpt-4o',
        messages: [{ role: 'user', text: 'hi', attachments: [] }],
        systemPrompt: '',
        webSearchEnabled: false,
        enabledToolIds: [],
        thinkingBudget: 'none',
        temperature: null,
        projectAttachments: [],
      },
      callbacks,
    )

    await handle.done
    expect(partials).toEqual(['hello', ' world'])
    expect(completed).toBe(true)
  })

  it('fires onMessagesAdded for messages_added event', async () => {
    const sseData =
      'event: messages_added\ndata: {}\n\n' +
      'event: complete\ndata: {"text":"done","messageId":"m1"}\n\n'

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        body: makeSseBody([sseData]),
        headers: new Headers({ 'Content-Type': 'text/event-stream' }),
      }),
    )

    let messagesAddedFired = false
    let completed = false
    const callbacks: StreamCallbacks = {
      onMessagesAdded: () => { messagesAddedFired = true },
      onComplete: () => { completed = true },
    }

    const handle = sendStream(
      {
        chatId: 'c1',
        provider: 'openai',
        modelName: 'gpt-4o',
        messages: [],
        systemPrompt: '',
        webSearchEnabled: false,
        enabledToolIds: [],
        thinkingBudget: 'none',
        temperature: null,
        projectAttachments: [],
      },
      callbacks,
    )

    await handle.done
    expect(messagesAddedFired).toBe(true)
    expect(completed).toBe(true)
  })

  it('fires onError when fetch rejects', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new Error('Network failure')),
    )

    const errors: string[] = []
    const handle = sendStream(
      {
        chatId: 'c1',
        provider: 'openai',
        modelName: 'gpt-4o',
        messages: [],
        systemPrompt: '',
        webSearchEnabled: false,
        enabledToolIds: [],
        thinkingBudget: 'none',
        temperature: null,
        projectAttachments: [],
      },
      { onError: (e) => errors.push(e.error) },
    )

    await handle.done
    expect(errors).toHaveLength(1)
    expect(errors[0]).toContain('Network failure')
  })
})

// ---------------------------------------------------------------------------
// Cloud runs: `run` / `cancelled` events, ping comments, a stream that breaks
// ---------------------------------------------------------------------------

describe('parseSseChunk — ping comments', () => {
  it('ignores ": ping" comment frames between events', () => {
    const input =
      ': ping\n\n' +
      'event: partial\ndata: {"text":"a"}\n\n' +
      ': ping\n\n' +
      ': ping\n\n' +
      'event: partial\ndata: {"text":"b"}\n\n'
    const { frames, remainder } = parseSseChunk(input)
    expect(frames.map((f) => f.data)).toEqual(['{"text":"a"}', '{"text":"b"}'])
    expect(remainder).toBe('')
  })

  it('ignores a comment line inside a frame', () => {
    const { frames } = parseSseChunk(': ping\nevent: partial\ndata: {"text":"a"}\n\n')
    expect(frames).toEqual([{ event: 'partial', data: '{"text":"a"}' }])
  })
})

describe('per-request stream of a run', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  const enc = new TextEncoder()
  /** A body that sends [chunks] and then either closes or breaks with a network error. */
  function body(chunks: string[], breakWith?: Error): ReadableStream<Uint8Array> {
    let i = 0
    return new ReadableStream({
      pull(controller) {
        if (i < chunks.length) controller.enqueue(enc.encode(chunks[i++]))
        else if (breakWith) controller.error(breakWith)
        else controller.close()
      },
    })
  }
  function stubFetch(stream: ReadableStream<Uint8Array>) {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body: stream }))
  }
  const RESEND = { provider: 'openai', modelName: 'gpt-4o' } as ResendMessageRequest

  /** Record every callback as "name:payload". */
  function recorder(withStreamLost = true) {
    const log: string[] = []
    const cbs: StreamCallbacks = {
      onRun: (e) => log.push(`run:${e.runId}`),
      onPartial: (e) => log.push(`partial:${e.text}`),
      onMessagesAdded: () => log.push('messages_added'),
      onComplete: (e) => log.push(`complete:${e.messageId}`),
      onCancelled: () => log.push('cancelled'),
      onError: (e) => log.push(`error:${e.error}`),
    }
    if (withStreamLost) cbs.onStreamLost = (e) => log.push(`lost:${e.runId}:${e.error}`)
    return { log, cbs }
  }

  it('reports the run id first and survives pings', async () => {
    stubFetch(body([
      'event: run\ndata: {"runId":"r1"}\n\n: ping\n\n',
      'event: partial\ndata: {"text":"hi"}\n\n: pi',
      'ng\n\nevent: complete\ndata: {"text":"hi","messageId":"m1"}\n\n',
    ]))
    const { log, cbs } = recorder()
    await resendStream('c1', 'm0', RESEND, cbs).done
    expect(log).toEqual(['run:r1', 'partial:hi', 'complete:m1'])
  })

  it('ends with onCancelled when the run was cancelled (no error)', async () => {
    stubFetch(body([
      'event: run\ndata: {"runId":"r1"}\n\n',
      'event: partial\ndata: {"text":"hi"}\n\n',
      'event: cancelled\ndata: {}\n\n',
    ]))
    const { log, cbs } = recorder()
    await resendStream('c1', 'm0', RESEND, cbs).done
    expect(log).toEqual(['run:r1', 'partial:hi', 'cancelled'])
  })

  it('a network error mid-run is onStreamLost, not onError', async () => {
    stubFetch(body(
      ['event: run\ndata: {"runId":"r1"}\n\n', 'event: partial\ndata: {"text":"hi"}\n\n'],
      new Error('network down'),
    ))
    const { log, cbs } = recorder()
    await resendStream('c1', 'm0', RESEND, cbs).done
    expect(log).toEqual(['run:r1', 'partial:hi', 'lost:r1:network down'])
  })

  it('a stream that just ends before the terminal event is onStreamLost', async () => {
    stubFetch(body(['event: run\ndata: {"runId":"r1"}\n\nevent: partial\ndata: {"text":"hi"}\n\n']))
    const { log, cbs } = recorder()
    await resendStream('c1', 'm0', RESEND, cbs).done
    expect(log.slice(0, 2)).toEqual(['run:r1', 'partial:hi'])
    expect(log[2]).toMatch(/^lost:r1:/)
    expect(log).toHaveLength(3)
  })

  it('a break is onError without an onStreamLost callback, or before the run id is known', async () => {
    stubFetch(body(['event: run\ndata: {"runId":"r1"}\n\n'], new Error('network down')))
    const a = recorder(false)
    await resendStream('c1', 'm0', RESEND, a.cbs).done
    expect(a.log).toEqual(['run:r1', 'error:network down'])

    stubFetch(body([': ping\n\n'], new Error('network down')))
    const b = recorder()
    await resendStream('c1', 'm0', RESEND, b.cbs).done
    expect(b.log).toEqual(['error:network down'])
  })

  it('nothing follows the terminal event: no stream-lost after complete / error', async () => {
    stubFetch(body(
      ['event: run\ndata: {"runId":"r1"}\n\nevent: complete\ndata: {"text":"x","messageId":"m1"}\n\n'],
      new Error('late break'),
    ))
    const a = recorder()
    await resendStream('c1', 'm0', RESEND, a.cbs).done
    expect(a.log).toEqual(['run:r1', 'complete:m1'])

    stubFetch(body([
      'event: run\ndata: {"runId":"r1"}\n\nevent: error\ndata: {"error":"boom"}\n\n' +
      'event: partial\ndata: {"text":"ghost"}\n\n',
    ]))
    const b = recorder()
    await resendStream('c1', 'm0', RESEND, b.cbs).done
    expect(b.log).toEqual(['run:r1', 'error:boom'])
  })

  it('aborting the fetch is silent (it does not stop the run and reports nothing)', async () => {
    let cancelled = false
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(enc.encode('event: run\ndata: {"runId":"r1"}\n\n'))
      },
      cancel() { cancelled = true },
    })
    vi.stubGlobal('fetch', vi.fn((_url: string, init: RequestInit) => {
      // Like a real fetch: aborting the request breaks the body read
      const reader = stream.getReader()
      const signal = init.signal as AbortSignal
      const aborted = new Promise<never>((_, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
      const proxied = { getReader: () => ({ read: () => Promise.race([reader.read(), aborted]), releaseLock: () => {} }) }
      return Promise.resolve({ ok: true, status: 200, body: proxied })
    }))
    const { log, cbs } = recorder()
    const handle = resendStream('c1', 'm0', RESEND, cbs)
    await vi.waitFor(() => expect(log).toEqual(['run:r1']))
    handle.abort()
    await handle.done
    expect(log).toEqual(['run:r1'])
    expect(cancelled).toBe(false)
  })
})
