/**
 * runsStore — the account's running runs, as the account-level stream (GET /api/runs/stream)
 * describes them: every LLM request of the account, whoever started it (this tab, another tab,
 * the phone).
 *
 * The store follows EVERY run, so that its state is ready the moment this tab needs it. What a
 * run means for the tab depends on who consumes it:
 *
 *  - "own" runs ([claim]): this tab is reading the run's per-request stream (ChatPage shows its
 *    local stream state and reloads the chat itself). The store only keeps the run up to date
 *    and has no side effects for it — the two streams never both act on one run.
 *  - every other run ("foreign": another device, another tab, this tab before a page reload, a
 *    chat the tab navigated away from, a per-request stream that broke): ChatPage shows the
 *    store's state as the streaming bubble of the run's chat, and the store reloads the open
 *    chat / the chat list when the run starts, saves messages and ends.
 *
 * `chat_updated` is not about a run's state: after a run ended (own or foreign) the server
 * generated the chat's title. It only refreshes the chat list and the open chat's name.
 */

import { create } from 'zustand'
import type { RunState } from '../api/types'
import type { RunStreamEvent } from '../api/runs'
import type { StreamState } from '../utils/streamState'
import {
  dropSettled,
  nextSegmentId,
  reduceStream,
  settleSegment,
  streamFromRunState,
} from '../utils/streamState'
import { useChatStore } from './chatStore'

export interface LiveRun extends StreamState {
  runId: string
  chatId: string
}

interface RunsStoreState {
  /** Running runs, by run id */
  runs: Record<string, LiveRun>
  /** Runs whose per-request stream this tab is consuming */
  ownRunIds: Record<string, true>
  /** chatId → error of a foreign run, until the chat's page shows it */
  errors: Record<string, string>

  /** A `snapshot`: the truth about which runs are running right now. */
  applySnapshot: (runs: RunState[]) => void
  /** A live event of the account-level stream. */
  applyEvent: (event: RunStreamEvent) => void

  /** This tab reads [runId]'s per-request stream: the store stays passive for it. */
  claim: (runId: string) => void
  /** The per-request stream delivered the run's terminal event. */
  finishOwn: (runId: string) => void
  /**
   * This tab stopped reading [runId]'s per-request stream before the run ended (the stream
   * broke, or the user left the chat): the run is followed here from now on. If the run is
   * already over, the chat is re-read right away.
   */
  release: (runId: string, chatId: string) => void

  clearError: (chatId: string) => void
  reset: () => void
}

function liveRun(run: RunState): LiveRun {
  return { ...streamFromRunState(run), runId: run.runId, chatId: run.chatId }
}

function without<T>(record: Record<string, T>, key: string): Record<string, T> {
  if (!(key in record)) return record
  const next = { ...record }
  delete next[key]
  return next
}

const TERMINAL = new Set(['complete', 'error', 'cancelled'])

/** Re-read what a foreign run changed: the open chat (if it is [chatId]) and the chat list. */
function reloadFor(chatId: string): Promise<unknown> {
  const chat = useChatStore.getState()
  return Promise.all([chat.refreshChat(chatId), chat.refreshHistory()])
}

export const useRunsStore = create<RunsStoreState>((set, get) => {
  const updateRun = (runId: string, change: (run: LiveRun) => LiveRun) => {
    const run = get().runs[runId]
    if (!run) return
    const next = change(run)
    if (next !== run) set({ runs: { ...get().runs, [runId]: next } })
  }
  const removeRun = (runId: string) => set({ runs: without(get().runs, runId) })
  const isOwn = (runId: string) => !!get().ownRunIds[runId]

  return {
    runs: {},
    ownRunIds: {},
    errors: {},

    applySnapshot: (snapshot) => {
      const before = get().runs
      const byId = new Map(snapshot.map((r) => [r.runId, r]))
      const runs: Record<string, LiveRun> = {}
      for (const run of snapshot) {
        if (run.status === 'running') runs[run.runId] = liveRun(run)
      }

      // Runs that ended while the stream was away
      const errors = { ...get().errors }
      const changedChats = new Set<string>()
      for (const old of Object.values(before)) {
        if (runs[old.runId] || isOwn(old.runId)) continue
        changedChats.add(old.chatId)
        const now = byId.get(old.runId)
        if (now?.status === 'error' && now.error) errors[old.chatId] = now.error
      }
      // Running runs: events (a `messages_added`, the run's start) may have been missed, and
      // the question of another device's run may not be in the loaded chat
      for (const run of Object.values(runs)) {
        if (!isOwn(run.runId)) changedChats.add(run.chatId)
      }

      set({ runs, errors })
      if (changedChats.size > 0) {
        const chat = useChatStore.getState()
        for (const chatId of changedChats) void chat.refreshChat(chatId)
        void chat.refreshHistory()
      }
    },

    applyEvent: (event) => {
      const { type, runId, chatId, data } = event

      if (type === 'run_started') {
        const run = data.run as RunState | undefined
        if (!run || typeof run.runId !== 'string') return
        set({ runs: { ...get().runs, [runId]: liveRun(run) } })
        // The question (or the whole chat) may be another device's: show it
        if (!isOwn(runId)) void reloadFor(chatId)
        return
      }

      if (type === 'chat_updated') {
        // The title arrives after the run's terminal event (the run may be forgotten here by
        // now, and the tab's own per-request stream has ended): for own and foreign runs alike
        void useChatStore.getState().refreshTitle(chatId)
        return
      }

      // Every run is announced by the snapshot or by run_started; anything else is the tail
      // of a run this tab already saw ending on its own stream
      if (!get().runs[runId]) return

      if (type === 'messages_added') {
        const segment = nextSegmentId()
        updateRun(runId, (run) => settleSegment(run, segment))
        const done = () => updateRun(runId, (run) => dropSettled(run, segment))
        // The saved part stays on screen until the reload that brings it as messages
        if (isOwn(runId)) done()
        else void useChatStore.getState().refreshChat(chatId).then(done)
        return
      }

      if (TERMINAL.has(type)) {
        if (isOwn(runId)) {
          removeRun(runId)
          return
        }
        if (type === 'error') {
          const message = typeof data.error === 'string' && data.error ? data.error : 'Error'
          set({ runs: without(get().runs, runId), errors: { ...get().errors, [chatId]: message } })
          void reloadFor(chatId)
          return
        }
        // Keep the streamed text until the saved reply is loaded
        void reloadFor(chatId).then(() => removeRun(runId))
        return
      }

      updateRun(runId, (run) => reduceStream(run, type, data))
    },

    claim: (runId) => set({ ownRunIds: { ...get().ownRunIds, [runId]: true } }),

    finishOwn: (runId) =>
      set({ ownRunIds: without(get().ownRunIds, runId), runs: without(get().runs, runId) }),

    release: (runId, chatId) => {
      set({ ownRunIds: without(get().ownRunIds, runId) })
      if (!get().runs[runId]) void reloadFor(chatId)
    },

    clearError: (chatId) => set({ errors: without(get().errors, chatId) }),

    reset: () => set({ runs: {}, ownRunIds: {}, errors: {} }),
  }
})

/** The run to show as [chatId]'s streaming bubble: a running run the tab is not reading itself. */
export function selectForeignRun(chatId: string | undefined) {
  return (state: RunsStoreState): LiveRun | null => {
    if (!chatId) return null
    for (const run of Object.values(state.runs)) {
      if (run.chatId === chatId && !state.ownRunIds[run.runId]) return run
    }
    return null
  }
}
