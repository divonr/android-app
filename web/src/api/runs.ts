/**
 * Cloud runs: every LLM request of the account is a server-owned "run".
 *
 *  - `cancelRun`        — POST /api/runs/{runId}/cancel (the stop button).
 *  - `connectRunsStream` — GET /api/runs/stream: ONE account-level SSE connection per tab. Its
 *    first event is a `snapshot` of all runs, then the live events of all runs (started by this
 *    tab, another tab, or the phone); every data object carries `runId` and `chatId`. Besides
 *    the events of a run (up to its terminal `complete` / `error` / `cancelled`) there is
 *    `chat_updated`: after a run ended, the server generated its chat's title. The stream
 *    never ends by itself, so the connection is re-opened (with backoff) whenever it breaks;
 *    each connection starts with a fresh snapshot.
 */

import { request } from './client'
import { parseSseChunk } from './stream'
import type { RunCancelResponse, RunState } from './types'

const BASE_URL = ''

/**
 * Stop a run. `keepPartial` saves the text streamed so far as the reply (the Android stop
 * button's behaviour). The answer only acknowledges the request: the run's `cancelled` event
 * is the confirmation. Rejects with ApiError 404 for a run the server no longer knows.
 */
export function cancelRun(runId: string, keepPartial: boolean): Promise<RunCancelResponse> {
  return request<RunCancelResponse>(`/api/runs/${encodeURIComponent(runId)}/cancel`, {
    method: 'POST',
    body: JSON.stringify({ keepPartial }),
  })
}

/** A live event of the account-level stream (everything but `snapshot`). */
export interface RunStreamEvent {
  type: string
  runId: string
  chatId: string
  data: Record<string, unknown>
}

export interface RunsStreamHandlers {
  /** First event of every connection: all running runs + runs finished in the last minutes */
  onSnapshot: (runs: RunState[]) => void
  onEvent: (event: RunStreamEvent) => void
  /** The connection broke; a new one (with a new snapshot) follows unless closed */
  onDisconnect?: () => void
}

export interface RunsStreamOptions {
  /** First reconnect delay; doubles up to [maxBackoffMs]; reset by a snapshot */
  minBackoffMs?: number
  maxBackoffMs?: number
  /** Reconnect when nothing (not even a ping, sent every 15 s) arrived for this long */
  idleTimeoutMs?: number
}

export interface RunsStreamHandle {
  close: () => void
}

export const RUNS_MIN_BACKOFF_MS = 1_000
export const RUNS_MAX_BACKOFF_MS = 30_000
export const RUNS_IDLE_TIMEOUT_MS = 45_000

export function connectRunsStream(
  handlers: RunsStreamHandlers,
  options: RunsStreamOptions = {},
): RunsStreamHandle {
  const minBackoff = options.minBackoffMs ?? RUNS_MIN_BACKOFF_MS
  const maxBackoff = options.maxBackoffMs ?? RUNS_MAX_BACKOFF_MS
  const idleTimeout = options.idleTimeoutMs ?? RUNS_IDLE_TIMEOUT_MS

  let closed = false
  let controller: AbortController | null = null
  let wake: (() => void) | null = null
  let backoff = minBackoff

  const dispatch = (frame: { event: string; data: string }) => {
    let json: unknown
    try {
      json = JSON.parse(frame.data)
    } catch {
      return // a broken frame is not worth the connection
    }
    if (typeof json !== 'object' || json === null) return
    const data = json as Record<string, unknown>
    if (frame.event === 'snapshot') {
      backoff = minBackoff
      handlers.onSnapshot(Array.isArray(data.runs) ? (data.runs as RunState[]) : [])
      return
    }
    if (typeof data.runId !== 'string' || typeof data.chatId !== 'string') return
    handlers.onEvent({ type: frame.event, runId: data.runId, chatId: data.chatId, data })
  }

  /** One connection: resolves when it ends for any reason. */
  const connectOnce = async () => {
    const ctl = new AbortController()
    controller = ctl
    let idleTimer: ReturnType<typeof setTimeout> | null = null
    const armIdle = () => {
      if (idleTimer) clearTimeout(idleTimer)
      idleTimer = setTimeout(() => ctl.abort(), idleTimeout)
    }
    try {
      armIdle()
      const response = await fetch(`${BASE_URL}/api/runs/stream`, {
        method: 'GET',
        credentials: 'include',
        headers: { Accept: 'text/event-stream' },
        signal: ctl.signal,
      })
      if (response.status === 401) {
        // Logged out: the app redirects to the login page; a new login mounts a new stream
        closed = true
        window.dispatchEvent(new Event('api:unauthenticated'))
        return
      }
      if (!response.ok || !response.body) return

      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      try {
        while (!closed) {
          const { done, value } = await reader.read()
          if (done) break
          armIdle()
          buffer += decoder.decode(value, { stream: true })
          const { frames, remainder } = parseSseChunk(buffer)
          buffer = remainder
          for (const frame of frames) {
            if (!closed) dispatch(frame)
          }
        }
      } finally {
        reader.releaseLock()
      }
    } catch {
      // Network error, abort (close / idle timeout): the loop decides what is next
    } finally {
      if (idleTimer) clearTimeout(idleTimer)
      if (controller === ctl) controller = null
      ctl.abort()
    }
  }

  void (async () => {
    while (!closed) {
      await connectOnce()
      if (closed) break
      handlers.onDisconnect?.()
      const delay = backoff
      backoff = Math.min(backoff * 2, maxBackoff)
      await new Promise<void>((resolve) => {
        const timer = setTimeout(() => { wake = null; resolve() }, delay)
        wake = () => { clearTimeout(timer); wake = null; resolve() }
      })
    }
  })()

  return {
    close: () => {
      closed = true
      controller?.abort()
      wake?.()
    },
  }
}
