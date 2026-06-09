/**
 * SettingsPage — R5 tests.
 *
 * Tests the Hebrew/RTL UI refactored in R5:
 *   - Section headings (accessible via aria-label + Hebrew text)
 *   - Toggle switches auto-save via settings.update
 *   - Child lock enable opens setup dialog (visual flow)
 *   - Remote sync section renders correctly
 *
 * Query strategy: use heading-scoped aria-label queries to avoid multi-match
 * when the same label text appears in multiple heading levels.
 */

import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import SettingsPage from '../pages/SettingsPage'
import type { AppSettings } from '../api/types'

// ─── CSS module mocks ─────────────────────────────────────────────────────────

vi.mock('../pages/SettingsPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))

// ─── Mock API client ──────────────────────────────────────────────────────────

vi.mock('../api/client', async () => ({
  settings: {
    get: vi.fn().mockResolvedValue({}),
    update: vi.fn().mockResolvedValue({}),
  },
  sync: {
    pull: vi.fn().mockResolvedValue({ ok: true }),
    status: vi.fn().mockResolvedValue({ enabled: false, serverBaseUrl: '', lastChangeTick: 0 }),
  },
}))

// ─── Sample settings ──────────────────────────────────────────────────────────

const SAMPLE_SETTINGS: AppSettings = {
  current_user: 'alice',
  selected_provider: 'openai',
  selected_model: 'gpt-4o',
  temperature: 0.7,
  titleGenerationSettings: {
    enabled: true,
    provider: 'auto',
    updateOnExtension: false,
  },
  multiMessageMode: false,
  childLockSettings: {
    enabled: false,
    encryptedPassword: '',
    startTime: '22:00',
    endTime: '07:00',
  },
  enabledTools: [],
  excludedToolIds: [],
  skipWelcomeScreen: false,
  starredModels: [],
  remoteSync: {
    enabled: false,
    serverBaseUrl: 'https://sync.example.com',
    authToken: '',
    syncApiKeys: false,
  },
}

import * as clientModule from '../api/client'

function renderPage() {
  return render(
    <MemoryRouter>
      <SettingsPage />
    </MemoryRouter>,
  )
}

describe('SettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(clientModule.settings.get).mockResolvedValue(SAMPLE_SETTINGS)
    vi.mocked(clientModule.settings.update).mockResolvedValue(SAMPLE_SETTINGS)
  })

  // ── Section rendering ───────────────────────────────────────────────────────

  it('renders Hebrew page title (Advanced Settings)', async () => {
    renderPage()
    await waitFor(() => {
      // The heading has headingAriaLabel="Advanced Settings"
      expect(screen.getByRole('heading', { name: /advanced settings/i })).toBeInTheDocument()
    })
  })

  it('shows current username', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('alice')).toBeInTheDocument()
    })
  })

  it('shows title generation section heading', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /title generation/i })).toBeInTheDocument()
    })
  })

  it('shows multi-message mode section heading', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /multi-message mode/i })).toBeInTheDocument()
    })
  })

  it('shows child lock section heading', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /child lock/i })).toBeInTheDocument()
    })
  })

  it('shows remote sync section heading', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /remote sync/i })).toBeInTheDocument()
    })
  })

  // ── Auto-save on toggle ─────────────────────────────────────────────────────

  it('toggling multi-message mode calls settings.update immediately', async () => {
    renderPage()
    // Wait for page to load
    await waitFor(() => screen.getByRole('heading', { name: /multi-message mode/i }))

    // Find the multi-message mode checkbox (it's currently unchecked = false)
    // The heading h2 has aria-label="Multi-Message Mode" and the checkbox is nearby
    const heading = screen.getByRole('heading', { name: /multi-message mode/i })
    // Walk up to the card div, then find the checkbox input
    const card = heading.closest('div')!.parentElement!
    const toggle = card.querySelector('input[type="checkbox"]') as HTMLInputElement

    expect(toggle).toBeTruthy()
    expect(toggle.checked).toBe(false)

    await act(async () => {
      fireEvent.click(toggle)
    })

    await waitFor(() => {
      expect(clientModule.settings.update).toHaveBeenCalledWith(
        expect.objectContaining({ multiMessageMode: true }),
      )
    })
  })

  it('toggling title generation calls settings.update immediately', async () => {
    renderPage()
    await waitFor(() => screen.getByRole('heading', { name: /title generation/i }))

    const heading = screen.getByRole('heading', { name: /title generation/i })
    const card = heading.closest('div')!.parentElement!
    const toggle = card.querySelector('input[type="checkbox"]') as HTMLInputElement

    expect(toggle.checked).toBe(true) // enabled in sample settings

    await act(async () => {
      fireEvent.click(toggle)
    })

    await waitFor(() => {
      expect(clientModule.settings.update).toHaveBeenCalledWith(
        expect.objectContaining({
          titleGenerationSettings: expect.objectContaining({ enabled: false }),
        }),
      )
    })
  })

  it('toggling remote sync calls settings.update', async () => {
    renderPage()
    await waitFor(() => screen.getByRole('heading', { name: /remote sync/i }))

    const heading = screen.getByRole('heading', { name: /remote sync/i })
    const card = heading.closest('div')!.parentElement!
    const toggle = card.querySelector('input[type="checkbox"]') as HTMLInputElement

    expect(toggle.checked).toBe(false)

    await act(async () => {
      fireEvent.click(toggle)
    })

    await waitFor(() => {
      expect(clientModule.settings.update).toHaveBeenCalledWith(
        expect.objectContaining({
          remoteSync: expect.objectContaining({ enabled: true }),
        }),
      )
    })
  })

  // ── Child lock dialog flow ──────────────────────────────────────────────────

  it('enabling child lock opens setup dialog', async () => {
    renderPage()
    await waitFor(() => screen.getByRole('heading', { name: /child lock/i }))

    const heading = screen.getByRole('heading', { name: /child lock/i })
    const card = heading.closest('div')!.parentElement!
    const toggle = card.querySelector('input[type="checkbox"]') as HTMLInputElement

    expect(toggle.checked).toBe(false)

    fireEvent.click(toggle)

    // Dialog title should appear
    await waitFor(() => {
      expect(screen.getByText('הגדרת נעילת ילדים')).toBeInTheDocument()
    })
  })

  it('child lock setup dialog can be cancelled', async () => {
    renderPage()
    await waitFor(() => screen.getByRole('heading', { name: /child lock/i }))

    const heading = screen.getByRole('heading', { name: /child lock/i })
    const card = heading.closest('div')!.parentElement!
    const toggle = card.querySelector('input[type="checkbox"]') as HTMLInputElement

    fireEvent.click(toggle)

    await waitFor(() => screen.getByText('הגדרת נעילת ילדים'))

    // Find and click cancel button (Hebrew: ביטול)
    const cancelBtn = screen.getByText('ביטול')
    fireEvent.click(cancelBtn)

    await waitFor(() => {
      expect(screen.queryByText('הגדרת נעילת ילדים')).not.toBeInTheDocument()
    })
    // settings.update should NOT have been called
    expect(clientModule.settings.update).not.toHaveBeenCalled()
  })

  // ── Remote sync action buttons ──────────────────────────────────────────────

  it('Sync Now button triggers sync.pull', async () => {
    renderPage()
    await waitFor(() => screen.getByRole('heading', { name: /remote sync/i }))

    // Hebrew text for "סנכרן עכשיו"
    const syncBtn = screen.getByText('סנכרן עכשיו')
    await act(async () => {
      fireEvent.click(syncBtn)
    })

    await waitFor(() => {
      expect(clientModule.sync.pull).toHaveBeenCalled()
    })
  })

  it('Test Connection button triggers sync.status call', async () => {
    renderPage()
    await waitFor(() => screen.getByRole('heading', { name: /remote sync/i }))

    const testBtn = screen.getByText('בדוק חיבור')
    await act(async () => {
      fireEvent.click(testBtn)
    })

    await waitFor(() => {
      expect(clientModule.sync.status).toHaveBeenCalled()
    })
  })
})
