import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
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

  it('renders page title', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /settings/i })).toBeInTheDocument()
    })
  })

  it('shows current username', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByDisplayValue('alice')).toBeInTheDocument()
    })
  })

  it('shows selected provider and model', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByDisplayValue('openai')).toBeInTheDocument()
      expect(screen.getByDisplayValue('gpt-4o')).toBeInTheDocument()
    })
  })

  it('calls settings.update when save is clicked after change', async () => {
    renderPage()
    await waitFor(() => {
      screen.getByDisplayValue('alice')
    })

    // Make a change
    const usernameInput = screen.getByDisplayValue('alice')
    fireEvent.change(usernameInput, { target: { value: 'bob' } })

    // Click save
    const saveBtn = screen.getByRole('button', { name: /save changes/i })
    fireEvent.click(saveBtn)

    await waitFor(() => {
      expect(clientModule.settings.update).toHaveBeenCalledWith(
        expect.objectContaining({ current_user: 'bob' }),
      )
    })
  })

  it('save button is disabled when no changes made', async () => {
    renderPage()
    await waitFor(() => {
      screen.getByDisplayValue('alice')
    })
    const saveBtn = screen.getByRole('button', { name: /save changes/i })
    expect(saveBtn).toBeDisabled()
  })

  it('shows child lock section', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /child lock/i })).toBeInTheDocument()
    })
  })

  it('shows title generation section', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /title generation/i })).toBeInTheDocument()
    })
  })
})
