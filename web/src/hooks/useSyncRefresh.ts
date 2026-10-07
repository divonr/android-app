/**
 * useSyncRefresh — live refresh of data the server's sync engine pulled from other devices.
 *
 * The web is one sync device per account: the server pulls the account's files (other devices'
 * chats, replies, settings) in the background. `GET /api/sync/status` reports `lastChangeTick`,
 * which changes whenever such a pull changed the user's files. This hook polls it every
 * [intervalMs] while the tab is visible, and right away when the window gains focus or the tab
 * becomes visible. When the tick changes it reloads the chat list and the open chat (store
 * `refreshFromSync`) and dispatches [SYNC_CHANGED_EVENT] for pages with their own data (groups).
 * While a reply streams into the open chat the reload waits until the stream ends.
 */

import { useEffect } from 'react'
import { sync as syncApi } from '../api/client'
import { useChatStore } from '../stores/chatStore'

export const SYNC_POLL_INTERVAL_MS = 10_000

/** Window event dispatched after a sync pull changed the user's data. */
export const SYNC_CHANGED_EVENT = 'api:sync-changed'

export function useSyncRefresh(intervalMs: number = SYNC_POLL_INTERVAL_MS): void {
  useEffect(() => {
    let lastTick: number | null = null
    let pending = false
    let inFlight = false
    let stopped = false

    const refresh = () => {
      pending = false
      void useChatStore.getState().refreshFromSync()
      window.dispatchEvent(new Event(SYNC_CHANGED_EVENT))
    }

    const check = async () => {
      if (stopped || inFlight || document.visibilityState === 'hidden') return
      inFlight = true
      try {
        const status = await syncApi.status(false)
        if (stopped || typeof status?.lastChangeTick !== 'number') return
        const tick = status.lastChangeTick
        // The first answer is the baseline: what's on screen was loaded after it
        if (lastTick !== null && tick !== lastTick) {
          if (useChatStore.getState().streaming) pending = true
          else refresh()
        }
        lastTick = tick
      } catch {
        // Offline, logged out, server restarting: try again on the next tick
      } finally {
        inFlight = false
      }
    }

    const onVisibility = () => {
      if (document.visibilityState === 'visible') void check()
    }
    const onFocus = () => { void check() }

    void check()
    const timer = setInterval(() => { void check() }, intervalMs)
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisibility)
    // A change seen during a stream is applied when the stream ends
    const unsubscribe = useChatStore.subscribe((state, prev) => {
      if (pending && prev.streaming && !state.streaming) refresh()
    })

    return () => {
      stopped = true
      clearInterval(timer)
      window.removeEventListener('focus', onFocus)
      document.removeEventListener('visibilitychange', onVisibility)
      unsubscribe()
    }
  }, [intervalMs])
}
