/**
 * useRunsStream — keeps the tab's ONE connection to the account-level runs stream
 * (GET /api/runs/stream) open while a signed-in page is mounted, and feeds runsStore with it.
 *
 * That is what makes a reply stream live in this tab when the request was sent from another
 * device (or from this tab before a reload). The connection re-opens with backoff; every new
 * connection starts with a snapshot, which the store treats as the truth.
 */

import { useEffect } from 'react'
import { connectRunsStream } from '../api/runs'
import { useRunsStore } from '../stores/runsStore'

export function useRunsStream(): void {
  useEffect(() => {
    const handle = connectRunsStream({
      onSnapshot: (runs) => useRunsStore.getState().applySnapshot(runs),
      onEvent: (event) => useRunsStore.getState().applyEvent(event),
    })
    return () => {
      handle.close()
      // Signed out / unmounted: nothing of the previous session may be shown again
      useRunsStore.getState().reset()
    }
  }, [])
}
