import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import LoginPage from '../pages/LoginPage'
import { useAuthStore } from '../stores/authStore'

// ---------------------------------------------------------------------------
// Stub CSS modules (Vitest handles them via moduleNameMapper or identity proxy)
// ---------------------------------------------------------------------------

// We use a simple passthrough so className strings don't throw
vi.mock('../pages/LoginPage.module.css', () => ({
  default: new Proxy(
    {},
    { get: (_t, prop) => String(prop) },
  ),
}))

// ---------------------------------------------------------------------------
// Helper: render LoginPage inside a MemoryRouter with a target /home route
// ---------------------------------------------------------------------------
function renderLogin() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<div data-testid="home">Home</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    // Reset Zustand store between tests
    useAuthStore.setState({ authenticated: null, checking: false })
    vi.restoreAllMocks()
  })

  it('renders a password field and submit button', () => {
    renderLogin()
    // Input has aria-label="password" so getByLabelText(/password/i) finds it
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument()
    // Button shows Hebrew text "כניסה"
    expect(screen.getByRole('button', { name: /כניסה/ })).toBeInTheDocument()
  })

  it('button is disabled when password field is empty', () => {
    renderLogin()
    expect(screen.getByRole('button', { name: /כניסה/ })).toBeDisabled()
  })

  it('calls login and navigates on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: () => Promise.resolve({ ok: true }),
      }),
    )

    renderLogin()
    const input = screen.getByLabelText(/password/i)
    const button = screen.getByRole('button', { name: /כניסה/ })

    fireEvent.change(input, { target: { value: 'correct-password' } })
    expect(button).not.toBeDisabled()

    fireEvent.click(button)

    await waitFor(() => {
      expect(screen.getByTestId('home')).toBeInTheDocument()
    })
  })

  it('shows error message on wrong password (401)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: () => Promise.resolve({ error: 'Invalid password' }),
      }),
    )

    renderLogin()
    const input = screen.getByLabelText(/password/i)
    fireEvent.change(input, { target: { value: 'wrong' } })
    fireEvent.click(screen.getByRole('button', { name: /כניסה/ }))

    await waitFor(() => {
      // Hebrew error message from i18n: 'סיסמה שגויה. אנא נסה שוב.'
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })
})
