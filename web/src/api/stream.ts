/**
 * SSE streaming client for POST /api/chat/send (and resend).
 *
 * EventSource only supports GET, so we use fetch() with a ReadableStream reader.
 * Each SSE frame has the format:
 *
 *   event: <event_type>\n
 *   data: <json>\n
 *   \n
 *
 * Frames are delimited by a blank line (\n\n).  Chunks from the network may
 * arrive at any byte boundary, so the parser accumulates a buffer and processes
 * complete lines one by one.
 *
 * The request is executed by a server-owned "run": the stream starts with a `run` event
 * carrying its id, relays the run's events and ends after its terminal event (`complete`,
 * `error` or `cancelled`).  `: ping` comment frames keep an idle stream alive.  A `usage` event
 * (the token counts of one provider request) only writes a log line.  Closing the
 * response does NOT stop the run (that is POST /api/runs/{runId}/cancel, see ./runs); a stream
 * that breaks before the terminal event is reported through `onStreamLost`, and the run can
 * still be followed on the account-level stream.
 */

import type {
  SseCancelledEvent,
  SseCompleteEvent,
  SseErrorEvent,
  SseMessagesAddedEvent,
  SsePartialEvent,
  SseRunEvent,
  SseThinkingCompleteEvent,
  SseThinkingPartialEvent,
  SseThinkingStartedEvent,
  SseToolCallEvent,
  SseToolResultEvent,
  SseUsageEvent,
  SendMessageRequest,
  ResendMessageRequest,
} from './types'
import { ApiError } from './client'

const BASE_URL = ''

// ---------------------------------------------------------------------------
// Callback interface (P7 will use these directly)
// ---------------------------------------------------------------------------

export interface StreamCallbacks {
  /** First event: the id of the run executing this request */
  onRun?: (event: SseRunEvent) => void
  /** Incremental assistant text delta */
  onPartial?: (event: SsePartialEvent) => void
  /** Model started a thinking phase */
  onThinkingStarted?: (event: SseThinkingStartedEvent) => void
  /** Incremental thinking text delta */
  onThinkingPartial?: (event: SseThinkingPartialEvent) => void
  /** Thinking phase complete with summary */
  onThinkingComplete?: (event: SseThinkingCompleteEvent) => void
  /** Server is about to execute a tool */
  onToolCall?: (event: SseToolCallEvent) => void
  /** Tool execution result */
  onToolResult?: (event: SseToolResultEvent) => void
  /** Stream complete — final message text + persisted message ID */
  onComplete?: (event: SseCompleteEvent) => void
  /** Tool messages persisted mid-stream — client should reload chat history */
  onMessagesAdded?: (event: SseMessagesAddedEvent) => void
  /** The run was stopped through the cancel endpoint (terminal; what it saved is in the chat) */
  onCancelled?: (event: SseCancelledEvent) => void
  /** Server-side or network error */
  onError?: (event: SseErrorEvent) => void
  /**
   * The connection broke (or ended) before the run's terminal event, after its id was known:
   * the run goes on at the server.  Without this callback it is reported through `onError`.
   */
  onStreamLost?: (event: { runId: string; error: string }) => void
}

// ---------------------------------------------------------------------------
// Internal: SSE frame parser
// ---------------------------------------------------------------------------

/**
 * Parse accumulated SSE text into (eventType, jsonData) pairs.
 *
 * Returns:
 *   - `frames`: fully parsed frames ready to dispatch
 *   - `remainder`: bytes that haven't formed a complete frame yet
 *
 * Exported for unit testing.
 */
export function parseSseChunk(
  buffer: string,
): { frames: Array<{ event: string; data: string }>; remainder: string } {
  const frames: Array<{ event: string; data: string }> = []

  // Frames are separated by blank lines (\n\n or \r\n\r\n)
  // We split on double-newlines to find complete frames.
  const parts = buffer.split(/\n\n|\r\n\r\n/)

  // The last element may be an incomplete frame — keep as remainder
  const remainder = parts.pop() ?? ''

  for (const part of parts) {
    if (!part.trim()) continue

    let eventType = 'message'
    const dataLines: string[] = []

    for (const rawLine of part.split(/\r?\n/)) {
      if (rawLine.startsWith('event:')) {
        eventType = rawLine.slice('event:'.length).trim()
      } else if (rawLine.startsWith('data:')) {
        dataLines.push(rawLine.slice('data:'.length).trim())
      }
      // Ignore id: and retry: fields, and comment lines (": ping")
    }

    if (dataLines.length > 0) {
      frames.push({ event: eventType, data: dataLines.join('\n') })
    }
  }

  return { frames, remainder }
}

// ---------------------------------------------------------------------------
// Token usage (for checking the provider's prompt cache)
// ---------------------------------------------------------------------------

/**
 * The log line of a `usage` event, in the wording of the server's and Android's logs. A count
 * that is missing or not a number reads as 0; the cached share of the prompt is rounded down.
 *
 * Exported for unit testing.
 */
export function usageLogLine(event: Partial<SseUsageEvent> | null | undefined): string {
  const count = (value: unknown) =>
    typeof value === 'number' && Number.isFinite(value) ? Math.trunc(value) : 0
  const prompt = count(event?.promptTokens)
  const cached = count(event?.cachedTokens)
  const percent = prompt > 0 ? Math.floor((cached * 100) / prompt) : 0
  return (
    `Token usage: prompt=${prompt}, cached=${cached} (${percent}%), ` +
    `output=${count(event?.outputTokens)}, thoughts=${count(event?.thoughtsTokens)}`
  )
}

// ---------------------------------------------------------------------------
// Internal: dispatch a parsed frame to callbacks
// ---------------------------------------------------------------------------

/** What the reader loop needs to know about the frames seen so far. */
interface StreamProgress {
  runId: string | null
  /** A terminal event (complete / error / cancelled) was dispatched */
  ended: boolean
}

function dispatchFrame(
  frame: { event: string; data: string },
  callbacks: StreamCallbacks,
  progress: StreamProgress,
): void {
  let json: unknown
  try {
    json = JSON.parse(frame.data)
  } catch {
    // JSON parse error — emit as error event
    progress.ended = true
    callbacks.onError?.({ error: `Failed to parse SSE frame: ${frame.data}` })
    return
  }
  switch (frame.event) {
    case 'run': {
      const runId = (json as SseRunEvent)?.runId
      if (typeof runId === 'string' && runId) {
        progress.runId = runId
        callbacks.onRun?.({ runId })
      }
      break
    }
    case 'partial':
      callbacks.onPartial?.(json as SsePartialEvent)
      break
    case 'thinking_started':
      callbacks.onThinkingStarted?.(json as SseThinkingStartedEvent)
      break
    case 'thinking_partial':
      callbacks.onThinkingPartial?.(json as SseThinkingPartialEvent)
      break
    case 'thinking_complete':
      callbacks.onThinkingComplete?.(json as SseThinkingCompleteEvent)
      break
    case 'tool_call':
      callbacks.onToolCall?.(json as SseToolCallEvent)
      break
    case 'tool_result':
      callbacks.onToolResult?.(json as SseToolResultEvent)
      break
    case 'complete':
      progress.ended = true
      callbacks.onComplete?.(json as SseCompleteEvent)
      break
    case 'cancelled':
      progress.ended = true
      callbacks.onCancelled?.(json as SseCancelledEvent)
      break
    case 'messages_added':
      callbacks.onMessagesAdded?.(json as SseMessagesAddedEvent)
      break
    case 'usage':
      // No UI: console.log is what the Logs page shows (utils/appLogger)
      console.log(usageLogLine(json as SseUsageEvent))
      break
    case 'error':
      progress.ended = true
      callbacks.onError?.(json as SseErrorEvent)
      break
    default:
      // Unknown event type — ignore silently
      break
  }
}

// ---------------------------------------------------------------------------
// Internal: stream reader loop
// ---------------------------------------------------------------------------

async function readStream(
  response: Response,
  callbacks: StreamCallbacks,
  signal: AbortSignal,
): Promise<void> {
  if (!response.body) {
    callbacks.onError?.({ error: 'Response body is null' })
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  const progress: StreamProgress = { runId: null, ended: false }
  let buffer = ''

  // The connection is gone but the run has not ended: it goes on at the server
  const lost = (error: string) => {
    if (signal.aborted || progress.ended) return
    progress.ended = true
    if (progress.runId && callbacks.onStreamLost) {
      callbacks.onStreamLost({ runId: progress.runId, error })
    } else {
      callbacks.onError?.({ error })
    }
  }

  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const { frames, remainder } = parseSseChunk(buffer)
      buffer = remainder

      for (const frame of frames) {
        // Nothing of a run follows its terminal event
        if (!progress.ended) dispatchFrame(frame, callbacks, progress)
      }
    }
    // Flush remaining bytes
    if (buffer.trim()) {
      const { frames } = parseSseChunk(buffer + '\n\n')
      for (const frame of frames) {
        if (!progress.ended) dispatchFrame(frame, callbacks, progress)
      }
    }
    lost('Stream ended before the reply was complete')
  } catch (err) {
    lost(err instanceof Error ? err.message : String(err))
  } finally {
    reader.releaseLock()
  }
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

export interface StreamHandle {
  /** Call to cancel the in-flight request */
  abort: () => void
  /** Promise that resolves when the stream ends (complete/error/abort) */
  done: Promise<void>
}

/**
 * Send a message and stream the SSE response.
 * Returns a StreamHandle with an abort() function and a done promise.
 */
export function sendStream(
  request: SendMessageRequest,
  callbacks: StreamCallbacks,
): StreamHandle {
  const controller = new AbortController()

  const done = (async () => {
    let response: Response
    try {
      response = await fetch(`${BASE_URL}/api/chat/send`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
        signal: controller.signal,
      })
    } catch (err) {
      if (controller.signal.aborted) return
      callbacks.onError?.({
        error: err instanceof Error ? err.message : String(err),
      })
      return
    }

    if (!response.ok) {
      let body: unknown
      try {
        body = await response.json()
      } catch {
        body = null
      }
      if (response.status === 401) {
        window.dispatchEvent(new Event('api:unauthenticated'))
      }
      callbacks.onError?.({
        error: (body as { error?: string })?.error ?? `HTTP ${response.status}`,
      })
      throw new ApiError(response.status, body)
    }

    await readStream(response, callbacks, controller.signal)
  })()

  return {
    abort: () => controller.abort(),
    done,
  }
}

/**
 * Resend from a message and stream the SSE response.
 */
export function resendStream(
  chatId: string,
  messageId: string,
  request: ResendMessageRequest,
  callbacks: StreamCallbacks,
): StreamHandle {
  const controller = new AbortController()

  const done = (async () => {
    let response: Response
    try {
      response = await fetch(
        `${BASE_URL}/api/chats/${chatId}/messages/${messageId}/resend`,
        {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(request),
          signal: controller.signal,
        },
      )
    } catch (err) {
      if (controller.signal.aborted) return
      callbacks.onError?.({
        error: err instanceof Error ? err.message : String(err),
      })
      return
    }

    if (!response.ok) {
      let body: unknown
      try {
        body = await response.json()
      } catch {
        body = null
      }
      callbacks.onError?.({
        error: (body as { error?: string })?.error ?? `HTTP ${response.status}`,
      })
      return
    }

    await readStream(response, callbacks, controller.signal)
  })()

  return {
    abort: () => controller.abort(),
    done,
  }
}
