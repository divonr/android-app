/**
 * KeysPage tests — R4 refactor.
 * All assertions against Hebrew UI strings.
 * API client is fully mocked; router provides useNavigate.
 */

import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import KeysPage from '../pages/KeysPage'
import type { ApiKey, CustomProviderConfig } from '../api/types'
import { t } from '../i18n/he'

// ─── CSS module mock ──────────────────────────────────────────────────────────

vi.mock('../pages/KeysPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))

// ─── Mock API client ──────────────────────────────────────────────────────────

vi.mock('../api/client', async () => ({
  apiKeys: {
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn().mockResolvedValue({}),
    update: vi.fn().mockResolvedValue({}),
    delete: vi.fn().mockResolvedValue(undefined),
    toggle: vi.fn().mockResolvedValue({}),
    reorder: vi.fn().mockResolvedValue([]),
  },
  customProviders: {
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn().mockResolvedValue({}),
    replace: vi.fn().mockResolvedValue({}),
    delete: vi.fn().mockResolvedValue(undefined),
  },
  fullCustomProviders: {
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn().mockResolvedValue({}),
    replace: vi.fn().mockResolvedValue({}),
    delete: vi.fn().mockResolvedValue(undefined),
  },
}))

// ─── Sample data ──────────────────────────────────────────────────────────────

const SAMPLE_KEYS: ApiKey[] = [
  { id: 'k1', provider: 'openai', key: '***abc', isActive: true, customName: 'Work key' },
  { id: 'k2', provider: 'anthropic', key: '***xyz', isActive: false, customName: null },
]

const SAMPLE_CUSTOM: CustomProviderConfig[] = [
  { id: 'cp1', name: 'My Provider', baseUrl: 'https://example.com', apiKey: null, modelNames: ['gpt-x'] },
]

import * as clientModule from '../api/client'

function renderPage() {
  return render(
    <MemoryRouter>
      <KeysPage />
    </MemoryRouter>,
  )
}

// ─── Tests ───────────────────────────────────────────────────────────────────

describe('KeysPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue([])
    vi.mocked(clientModule.customProviders.list).mockResolvedValue([])
    vi.mocked(clientModule.fullCustomProviders.list).mockResolvedValue([])
  })

  // ── Title ─────────────────────────────────────────────────────────────────

  it('renders the page title (accessible name = "API Keys")', async () => {
    renderPage()
    await waitFor(() => {
      // The <h1> has aria-label="API Keys" so role query still works
      expect(screen.getByRole('heading', { name: /api keys/i })).toBeInTheDocument()
    })
  })

  it('shows Hebrew title מפתחות API', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText(t('api_keys'))).toBeInTheDocument()
    })
  })

  // ── Keys list ─────────────────────────────────────────────────────────────

  it('shows list of API keys with provider names', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    renderPage()
    await waitFor(() => {
      // Provider names shown uppercase in cards
      expect(screen.getByText('OPENAI')).toBeInTheDocument()
      expect(screen.getByText('ANTHROPIC')).toBeInTheDocument()
    })
  })

  it('shows masked key values', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('***abc')).toBeInTheDocument()
      expect(screen.getByText('***xyz')).toBeInTheDocument()
    })
  })

  it('shows active/inactive status in Hebrew', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    renderPage()
    await waitFor(() => {
      // k1 isActive=true → פעיל, k2 isActive=false → לא פעיל
      expect(screen.getByText(t('key_status_active'))).toBeInTheDocument()
      expect(screen.getByText(t('key_status_inactive'))).toBeInTheDocument()
    })
  })

  it('shows custom name when present', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Work key')).toBeInTheDocument()
    })
  })

  // ── Add key dialog ────────────────────────────────────────────────────────

  it('opens add key dialog on button click', async () => {
    renderPage()
    await waitFor(() => {
      // The action button has aria-label t('add_api_key') = "הוסף מפתח API"
      expect(screen.getByRole('button', { name: t('add_api_key') })).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: t('add_api_key') }))

    await waitFor(() => {
      // Dialog title appears
      expect(screen.getByRole('dialog')).toBeInTheDocument()
      // Dialog header shows the Hebrew title
      expect(screen.getAllByText(t('add_api_key')).length).toBeGreaterThan(0)
    })
  })

  it('add key flow calls apiKeys.create with correct args', async () => {
    vi.mocked(clientModule.apiKeys.create).mockResolvedValue({
      id: 'k-new', provider: 'openai', key: '***new', isActive: true, customName: null,
    })
    vi.mocked(clientModule.apiKeys.list)
      .mockResolvedValueOnce([])
      .mockResolvedValue([{ id: 'k-new', provider: 'openai', key: '***new', isActive: true, customName: null }])

    renderPage()

    // Open dialog
    await waitFor(() => {
      fireEvent.click(screen.getByRole('button', { name: t('add_api_key') }))
    })

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    // Open provider dropdown and pick openai
    const dropdownTrigger = screen.getByRole('button', { name: /בחר ספק/i })
    fireEvent.click(dropdownTrigger)

    await waitFor(() => {
      // 'OpenAI' comes from t('provider_openai')
      expect(screen.getByText('OpenAI')).toBeInTheDocument()
    })
    fireEvent.click(screen.getByText('OpenAI'))

    // Fill API key
    const keyInput = screen.getByPlaceholderText('sk-…')
    fireEvent.change(keyInput, { target: { value: 'sk-test-key' } })

    // Submit — button label is t('approve') = "אישור"
    fireEvent.click(screen.getByRole('button', { name: t('approve') }))

    await waitFor(() => {
      expect(clientModule.apiKeys.create).toHaveBeenCalledWith(
        expect.objectContaining({
          provider: 'openai',
          key: 'sk-test-key',
          isActive: true,
        }),
      )
    })
  })

  // ── Toggle ────────────────────────────────────────────────────────────────

  it('toggles a key when status pill is clicked', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    vi.mocked(clientModule.apiKeys.toggle).mockResolvedValue({ ...SAMPLE_KEYS[0], isActive: false })

    renderPage()

    await waitFor(() => {
      expect(screen.getByText(t('key_status_active'))).toBeInTheDocument()
    })

    // Click the active status pill (k1)
    fireEvent.click(screen.getByText(t('key_status_active')))

    await waitFor(() => {
      expect(clientModule.apiKeys.toggle).toHaveBeenCalledWith('k1')
    })
  })

  // ── Delete key ────────────────────────────────────────────────────────────

  it('shows delete confirm dialog and calls apiKeys.delete on confirm', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    vi.mocked(clientModule.apiKeys.delete).mockResolvedValue(undefined)

    renderPage()

    await waitFor(() => {
      // Two delete buttons (title="Delete") from KeyCard
      expect(screen.getAllByTitle('Delete')).toHaveLength(2)
    })

    // Click first delete button
    fireEvent.click(screen.getAllByTitle('Delete')[0])

    // Confirm dialog should appear
    await waitFor(() => {
      expect(screen.getByText(t('delete_api_key_title'))).toBeInTheDocument()
    })

    // Click the danger confirm button (label = t('delete') = "מחק")
    fireEvent.click(screen.getByRole('button', { name: t('delete') }))

    await waitFor(() => {
      expect(clientModule.apiKeys.delete).toHaveBeenCalledWith('k1')
    })
  })

  // ── Reorder ───────────────────────────────────────────────────────────────

  it('calls apiKeys.reorder when up/down is clicked', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    vi.mocked(clientModule.apiKeys.reorder).mockResolvedValue(SAMPLE_KEYS)

    renderPage()

    await waitFor(() => {
      // Both keys rendered → reorder buttons present
      expect(screen.getByText('OPENAI')).toBeInTheDocument()
    })

    // Move k1 down (index 0 → 1)
    const downBtns = screen.getAllByTitle(t('reorder_down'))
    fireEvent.click(downBtns[0])

    await waitFor(() => {
      expect(clientModule.apiKeys.reorder).toHaveBeenCalledWith({ fromIndex: 0, toIndex: 1 })
    })
  })

  // ── Custom providers ──────────────────────────────────────────────────────

  it('lists custom providers with name and URL', async () => {
    vi.mocked(clientModule.customProviders.list).mockResolvedValue(SAMPLE_CUSTOM)
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('My Provider')).toBeInTheDocument()
      expect(screen.getByText('https://example.com')).toBeInTheDocument()
    })
  })

  it('add custom provider flow calls customProviders.create', async () => {
    vi.mocked(clientModule.customProviders.create).mockResolvedValue({
      id: 'cp-new', name: 'New Prov', baseUrl: 'https://newprov.io', apiKey: null, modelNames: ['m1'],
    })

    renderPage()

    await waitFor(() => {
      // Section "add" button for custom providers
      const createBtns = screen.getAllByRole('button', { name: t('create') })
      expect(createBtns.length).toBeGreaterThan(0)
      fireEvent.click(createBtns[0])
    })

    // Simple custom provider dialog opens
    await waitFor(() => {
      expect(screen.getByText(t('create_custom_provider'))).toBeInTheDocument()
    })

    // Fill in name
    const nameInput = screen.getByPlaceholderText(/My Local LLM/i)
    fireEvent.change(nameInput, { target: { value: 'New Prov' } })

    // Fill in base URL
    const urlInput = screen.getByPlaceholderText(/api\.example\.com/i)
    fireEvent.change(urlInput, { target: { value: 'https://newprov.io' } })

    // Submit (label t('create') = "צור")
    const submitBtn = screen.getAllByRole('button', { name: t('create') })
    // The last "צור" button is the dialog's submit button
    fireEvent.click(submitBtn[submitBtn.length - 1])

    await waitFor(() => {
      expect(clientModule.customProviders.create).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'New Prov',
          baseUrl: 'https://newprov.io',
        }),
      )
    })
  })
})
