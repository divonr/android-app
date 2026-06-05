import { describe, it, expect, vi, beforeEach } from 'vitest'
import { parseSseChunk, sendStream } from '../api/stream'
import type { StreamCallbacks } from '../api/stream'

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
