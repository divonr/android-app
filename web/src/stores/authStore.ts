import { create } from 'zustand'
import { auth } from '../api/client'

interface AuthState {
  authenticated: boolean | null   // null = not yet checked
  checking: boolean

  /** Check /api/session and update state */
  check: () => Promise<void>
  /** POST /login */
  login: (password: string) => Promise<void>
  /** POST /logout */
  logout: () => Promise<void>
  /** Called on 401 events (from the unauthenticated global event) */
  markUnauthenticated: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  authenticated: null,
  checking: false,

  check: async () => {
    set({ checking: true })
    try {
      const res = await auth.session()
      set({ authenticated: res.authenticated, checking: false })
    } catch {
      set({ authenticated: false, checking: false })
    }
  },

  login: async (password: string) => {
    await auth.login(password)
    set({ authenticated: true })
  },

  logout: async () => {
    await auth.logout()
    set({ authenticated: false })
  },

  markUnauthenticated: () => {
    set({ authenticated: false })
  },
}))
