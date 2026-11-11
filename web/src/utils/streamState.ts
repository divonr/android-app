/**
 * The live state of one streaming reply, and how stream events change it.
 *
 * Shared by the two sources of a streaming reply: the tab's own per-request stream (ChatPage's
 * local state) and the account-level runs stream (runsStore: runs this tab did not start).
 */

import type { RunState } from '../api/types'

export interface StreamToolCall {
  toolId: string
  toolName: string
  parameters: Record<string, unknown>
  result?: string
  success?: boolean
  executing: boolean
}

/** What streamed since the last `messages_added` (or since the start). */
export interface StreamSegment {
  partialText: string
  thinkingText: string
  thinkingOpen: boolean
  thinkingDone: boolean
  thinkingStartTime: number | null
  thinkingDurationSeconds: number | null
  toolCalls: StreamToolCall[]
}

/**
 * A segment the server already saved (`messages_added`), still shown until the chat reload
 * that brings its messages finishes — so the text never disappears in between.
 */
export interface SettledSegment extends StreamSegment {
  id: number
}

export interface StreamState extends StreamSegment {
  streaming: boolean
  settled: SettledSegment[]
}

const EMPTY_SEGMENT: StreamSegment = {
  partialText: '',
  thinkingText: '',
  thinkingOpen: false,
  thinkingDone: false,
  thinkingStartTime: null,
  thinkingDurationSeconds: null,
  toolCalls: [],
}

export const EMPTY_STREAM: StreamState = {
  ...EMPTY_SEGMENT,
  streaming: false,
  settled: [],
}

let segmentSeq = 0
/** A new id for [settleSegment] / [dropSettled]. */
export function nextSegmentId(): number {
  return ++segmentSeq
}

function segmentOf(s: StreamSegment): StreamSegment {
  return {
    partialText: s.partialText,
    thinkingText: s.thinkingText,
    thinkingOpen: s.thinkingOpen,
    thinkingDone: s.thinkingDone,
    thinkingStartTime: s.thinkingStartTime,
    thinkingDurationSeconds: s.thinkingDurationSeconds,
    toolCalls: s.toolCalls,
  }
}

export function isEmptySegment(s: StreamSegment): boolean {
  return !s.partialText && !s.thinkingOpen && s.toolCalls.length === 0
}

/** `messages_added`: what streamed so far is saved; a new segment starts. */
export function settleSegment<S extends StreamState>(s: S, id: number): S {
  if (isEmptySegment(s)) return s
  return { ...s, ...EMPTY_SEGMENT, settled: [...s.settled, { ...segmentOf(s), id }] }
}

/** The reload after `messages_added` number [id] finished: its segment is in the chat now. */
export function dropSettled<S extends StreamState>(s: S, id: number): S {
  if (!s.settled.some((seg) => seg.id <= id)) return s
  return { ...s, settled: s.settled.filter((seg) => seg.id > id) }
}

/** Every segment to render, oldest first (the last one is the live one). */
export function streamSegments(s: StreamState): StreamSegment[] {
  return [...s.settled, s]
}

/** Apply one content event (`partial`, `thinking_*`, `tool_*`); other events change nothing. */
export function reduceStream<S extends StreamState>(
  s: S,
  type: string,
  data: Record<string, unknown>,
): S {
  switch (type) {
    case 'partial':
      return { ...s, partialText: s.partialText + String(data.text ?? '') }
    case 'thinking_started':
      return { ...s, thinkingOpen: true, thinkingDone: false, thinkingStartTime: Date.now() }
    case 'thinking_partial':
      return { ...s, thinkingOpen: true, thinkingText: s.thinkingText + String(data.text ?? '') }
    case 'thinking_complete':
      return {
        ...s,
        thinkingDone: true,
        thinkingDurationSeconds:
          typeof data.durationSeconds === 'number' ? data.durationSeconds : null,
      }
    case 'tool_call':
      return {
        ...s,
        toolCalls: [
          ...s.toolCalls,
          {
            toolId: String(data.toolId ?? ''),
            toolName: String(data.toolName ?? ''),
            parameters: (data.parameters ?? {}) as Record<string, unknown>,
            executing: true,
          },
        ],
      }
    case 'tool_result':
      return {
        ...s,
        toolCalls: s.toolCalls.map((tc) =>
          tc.toolId === data.toolId
            ? { ...tc, result: String(data.output ?? ''), success: !!data.success, executing: false }
            : tc,
        ),
      }
    default:
      return s
  }
}

/**
 * The stream state of a run as a snapshot describes it (text and thinking text since the last
 * `messages_added`; tool calls are not part of a snapshot).
 */
export function streamFromRunState(run: RunState): StreamState {
  const thinkingOpen = run.thinkingActive || !!run.thinkingText
  return {
    ...EMPTY_STREAM,
    streaming: true,
    partialText: run.text ?? '',
    thinkingText: run.thinkingText ?? '',
    thinkingOpen,
    thinkingDone: thinkingOpen && !run.thinkingActive,
    // The real start is unknown: the live timer counts from now
    thinkingStartTime: run.thinkingActive ? Date.now() : null,
  }
}
