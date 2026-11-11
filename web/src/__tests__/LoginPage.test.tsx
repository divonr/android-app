/**
 * LoginPage tests — Google-only sign-in flow (Step 6 refactor).
 *
 * Covers:
 *   - Page renders app name + Google sign-in button
 *   - No error shown when there is no ?error param
 *   - Clicking the button navigates to /auth/google/start
 *   - Each of the 5 error codes shows a localized Hebrew message
 *   - Unknown error codes show no error
 */

import React from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import LoginPage from '../pages/LoginPage'

// ---------------------------------------------------------------------------
// CSS module mock
// ---------------------------------------------------------------------------

vi.mock('../pages/LoginPage.module.css', () => ({
  default: new Proxy(
    {},
    { get: (_t, prop) => String(prop) },
  ),
}))

// ---------------------------------------------------------------------------
// Helper: render LoginPage inside a MemoryRouter with the given URL
// ---------------------------------------------------------------------------

function renderLogin(url = '/login') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('LoginPage — Google sign-in', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    // Replace window.location with a plain object so href assignment can be
    // captured (jsdom does not implement navigation).
    vi.stubGlobal('location', { href: '' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders app name heading', () => {
    renderLogin()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('ApI')
  })

  it('renders a Google sign-in button', () => {
    renderLogin()
    expect(screen.getByRole('button', { name: /google/i })).toBeInTheDocument()
  })

  it('does not show an error banner when no ?error param is present', () => {
    renderLogin()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('clicking the sign-in button navigates to /auth/google/start', () => {
    renderLogin()
    fireEvent.click(screen.getByRole('button', { name: /google/i }))
    expect(window.location.href).toBe('/auth/google/start')
  })

  // ---------------------------------------------------------------------------
  // Error-code display
  // ---------------------------------------------------------------------------

  it.each([
    ['invalid_state', 'שגיאת אבטחה'],
    ['token_exchange_failed', 'כשל בהתחברות'],
    ['token_invalid', 'הטוקן אינו תקין'],
    ['not_allowed', 'אינה מורשית'],
    ['sync_unavailable', 'הסנכרון אינו זמין'],
  ] as const)(
    'shows error message for ?error=%s',
    (code, expectedSubstring) => {
      renderLogin(`/login?error=${code}`)
      const alert = screen.getByRole('alert')
      expect(alert).toBeInTheDocument()
      expect(alert.textContent).toContain(expectedSubstring)
    },
  )

  it('shows no error banner for an unrecognised error code', () => {
    renderLogin('/login?error=completely_unknown')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
