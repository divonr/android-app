import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import KeysPage from '../pages/KeysPage'
import type { ApiKey } from '../api/types'

// ─── CSS module mocks ─────────────────────────────────────────────────────────

vi.mock('../pages/KeysPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))

// ─── Mock API client ──────────────────────────────────────────────────────────
// Use async factory to avoid vi.fn() issues in factory

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

import * as clientModule from '../api/client'

function renderPage() {
  return render(
    <MemoryRouter>
      <KeysPage />
    </MemoryRouter>,
  )
}

describe('KeysPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue([])
    vi.mocked(clientModule.customProviders.list).mockResolvedValue([])
    vi.mocked(clientModule.fullCustomProviders.list).mockResolvedValue([])
  })

  it('renders the page title', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /api keys/i })).toBeInTheDocument()
    })
  })

  it('shows list of API keys', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('openai')).toBeInTheDocument()
      expect(screen.getByText('anthropic')).toBeInTheDocument()
    })
  })

  it('shows active/inactive status', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Active')).toBeInTheDocument()
      expect(screen.getByText('Inactive')).toBeInTheDocument()
    })
  })

  it('opens add key form', async () => {
    renderPage()
    await waitFor(() => {
      fireEvent.click(screen.getByRole('button', { name: /add key/i }))
    })
    expect(screen.getByText('Add API Key')).toBeInTheDocument()
  })

  it('adds a new key', async () => {
    vi.mocked(clientModule.apiKeys.create).mockResolvedValue({
      id: 'k-new', provider: 'openai', key: '***new', isActive: true, customName: null,
    })

    renderPage()
    await waitFor(() => {
      fireEvent.click(screen.getByRole('button', { name: /add key/i }))
    })

    const keyInput = screen.getByPlaceholderText('sk-…')
    fireEvent.change(keyInput, { target: { value: 'sk-test-key' } })

    // Submit by clicking the Add Key button in the form
    const allBtns = screen.getAllByRole('button')
    const addKeyBtn = allBtns.find(b => b.textContent === 'Add Key')
    expect(addKeyBtn).toBeTruthy()
    fireEvent.click(addKeyBtn!)

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

  it('toggles a key', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    vi.mocked(clientModule.apiKeys.toggle).mockResolvedValue({ ...SAMPLE_KEYS[0], isActive: false })

    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Active')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByText('Active'))
    await waitFor(() => {
      expect(clientModule.apiKeys.toggle).toHaveBeenCalledWith('k1')
    })
  })

  it('deletes a key after confirmation', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    vi.mocked(clientModule.apiKeys.delete).mockResolvedValue(undefined)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))

    renderPage()
    await waitFor(() => {
      expect(screen.getAllByTitle('Delete')).toHaveLength(2)
    })

    fireEvent.click(screen.getAllByTitle('Delete')[0])
    await waitFor(() => {
      expect(clientModule.apiKeys.delete).toHaveBeenCalledWith('k1')
    })
  })

  it('shows custom name when present', async () => {
    vi.mocked(clientModule.apiKeys.list).mockResolvedValue(SAMPLE_KEYS)
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Work key')).toBeInTheDocument()
    })
  })
})
