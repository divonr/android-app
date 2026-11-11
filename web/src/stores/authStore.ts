import { create } from 'zustand'
import { auth } from '../api/client'
import type { MeResponse } from '../api/types'

interface AuthState {
  authenticated: boolean | null   // null = not yet checked
  checking: boolean
  /** Identity of the logged-in user; null until check() succeeds. */
  userInfo: MeResponse | null

  /** Check GET /api/me and update state */
  check: () => Promise<void>
  /** POST /logout — clears session; AuthGuard will redirect to /login */
  logout: () => Promise<void>
  /** Called on 401 events (from the unauthenticated global event) */
  markUnauthenticated: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  authenticated: null,
  checking: false,
  userInfo: null,

  check: async () => {
    set({ checking: true })
    try {
      const me = await auth.session()
      set({ authenticated: true, checking: false, userInfo: me })
    } catch {
      set({ authenticated: false, checking: false, userInfo: null })
    }
  },

  logout: async () => {
    try {
      await auth.logout()
    } finally {
      // Always clear local state so AuthGuard redirects to /login
      set({ authenticated: false, userInfo: null })
    }
  },

  markUnauthenticated: () => {
    set({ authenticated: false, userInfo: null })
  },
}))
