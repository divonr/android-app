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
 */

import type {
  SseCompleteEvent,
  SseErrorEvent,
  SseMessagesAddedEvent,
  SsePartialEvent,
  SseThinkingCompleteEvent,
  SseThinkingPartialEvent,
  SseThinkingStartedEvent,
  SseToolCallEvent,
  SseToolResultEvent,
  SendMessageRequest,
  ResendMessageRequest,
} from './types'
import { ApiError } from './client'

const BASE_URL = ''

// ---------------------------------------------------------------------------
// Callback interface (P7 will use these directly)
// ---------------------------------------------------------------------------

export interface StreamCallbacks {
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
  /** Server-side or network error */
  onError?: (event: SseErrorEvent) => void
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
      // Ignore id: and retry: fields
    }

    if (dataLines.length > 0) {
      frames.push({ event: eventType, data: dataLines.join('\n') })
    }
  }

  return { frames, remainder }
}

// ---------------------------------------------------------------------------
// Internal: dispatch a parsed frame to callbacks
// ---------------------------------------------------------------------------

function dispatchFrame(
  frame: { event: string; data: string },
  callbacks: StreamCallbacks,
): void {
  try {
    const json = JSON.parse(frame.data)
    switch (frame.event) {
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
        callbacks.onComplete?.(json as SseCompleteEvent)
        break
      case 'messages_added':
        callbacks.onMessagesAdded?.(json as SseMessagesAddedEvent)
        break
      case 'error':
        callbacks.onError?.(json as SseErrorEvent)
        break
      default:
        // Unknown event type — ignore silently
        break
    }
  } catch {
    // JSON parse error — emit as error event
    callbacks.onError?.({ error: `Failed to parse SSE frame: ${frame.data}` })
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
  let buffer = ''

  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const { frames, remainder } = parseSseChunk(buffer)
      buffer = remainder

      for (const frame of frames) {
        dispatchFrame(frame, callbacks)
      }
    }
    // Flush remaining bytes
    if (buffer.trim()) {
      const { frames } = parseSseChunk(buffer + '\n\n')
      for (const frame of frames) {
        dispatchFrame(frame, callbacks)
      }
    }
  } catch (err) {
    if (signal.aborted) return
    callbacks.onError?.({
      error: err instanceof Error ? err.message : String(err),
    })
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
