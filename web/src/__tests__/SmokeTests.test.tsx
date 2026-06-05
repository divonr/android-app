/**
 * Smoke tests — render each screen with mocked deps and verify no crash.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'

// ─── CSS module mocks ─────────────────────────────────────────────────────────

vi.mock('../pages/ChatHistoryPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))
vi.mock('../pages/ChatPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))
vi.mock('../pages/KeysPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))
vi.mock('../pages/SettingsPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))
vi.mock('../pages/SkillsPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))
vi.mock('../pages/IntegrationsPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))
vi.mock('../pages/GroupPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))
vi.mock('../components/Markdown.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))

// ─── Mock all API calls ───────────────────────────────────────────────────────

vi.mock('../api/client', () => ({
  chats: {
    list: vi.fn().mockResolvedValue({ user_name: 'u', chat_history: [], groups: [] }),
    get: vi.fn().mockResolvedValue(null),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
  groups: {
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    addChat: vi.fn(),
    removeChat: vi.fn(),
    addAttachment: vi.fn(),
    removeAttachment: vi.fn(),
  },
  search: { query: vi.fn().mockResolvedValue([]) },
  providers: { models: vi.fn().mockResolvedValue([]) },
  branching: { create: vi.fn(), switchVariant: vi.fn() },
  skills: { list: vi.fn().mockResolvedValue([]), getContent: vi.fn(), setEnabled: vi.fn(), create: vi.fn(), delete: vi.fn() },
  files: { upload: vi.fn() },
  messages: { delete: vi.fn() },
  apiKeys: {
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    toggle: vi.fn(),
    reorder: vi.fn(),
  },
  customProviders: {
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn(),
    replace: vi.fn(),
    delete: vi.fn(),
  },
  fullCustomProviders: {
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn(),
    replace: vi.fn(),
    delete: vi.fn(),
  },
  settings: {
    get: vi.fn().mockResolvedValue({
      current_user: 'u',
      selected_provider: 'openai',
      selected_model: 'gpt-4o',
      temperature: 0.7,
      titleGenerationSettings: { enabled: false, provider: 'auto', updateOnExtension: false },
      multiMessageMode: false,
      childLockSettings: { enabled: false, encryptedPassword: '', startTime: '22:00', endTime: '07:00' },
      enabledTools: [],
      excludedToolIds: [],
      skipWelcomeScreen: false,
      starredModels: [],
    }),
    update: vi.fn(),
  },
  integrations: {
    github: { get: vi.fn().mockResolvedValue(null), disconnect: vi.fn(), startOAuth: vi.fn() },
    google: { get: vi.fn().mockResolvedValue(null), disconnect: vi.fn(), startOAuth: vi.fn(), updateServices: vi.fn() },
  },
}))

vi.mock('../api/stream', () => ({
  sendStream: vi.fn().mockReturnValue({ abort: vi.fn(), done: Promise.resolve() }),
  resendStream: vi.fn().mockReturnValue({ abort: vi.fn(), done: Promise.resolve() }),
}))

// ─── Zustand store mocks ──────────────────────────────────────────────────────

import * as chatStoreModule from '../stores/chatStore'

const EMPTY_CHAT_STORE = {
  history: { user_name: 'u', chat_history: [], groups: [] },
  currentChat: null,
  loading: false,
  error: null,
  loadHistory: vi.fn().mockResolvedValue(undefined),
  loadChat: vi.fn().mockResolvedValue(undefined),
  updateChat: vi.fn(),
  clearCurrentChat: vi.fn(),
}

beforeEach(() => {
  chatStoreModule.useChatStore.setState(EMPTY_CHAT_STORE)
  vi.clearAllMocks()
})

// ─── Page imports ─────────────────────────────────────────────────────────────

import ChatHistoryPage from '../pages/ChatHistoryPage'
import KeysPage from '../pages/KeysPage'
import SettingsPage from '../pages/SettingsPage'
import SkillsPage from '../pages/SkillsPage'
import IntegrationsPage from '../pages/IntegrationsPage'
import GroupPage from '../pages/GroupPage'
import ChatPage from '../pages/ChatPage'

// ─── Tests ────────────────────────────────────────────────────────────────────

describe('Smoke tests — all screens render without crash', () => {
  it('ChatHistoryPage renders', () => {
    const { container } = render(
      <MemoryRouter><ChatHistoryPage /></MemoryRouter>,
    )
    expect(container).toBeTruthy()
  })

  it('KeysPage renders and shows title', async () => {
    render(<MemoryRouter><KeysPage /></MemoryRouter>)
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /api keys/i })).toBeInTheDocument()
    })
  })

  it('SettingsPage renders and shows title', async () => {
    render(<MemoryRouter><SettingsPage /></MemoryRouter>)
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /settings/i })).toBeInTheDocument()
    })
  })

  it('SkillsPage renders and shows title', async () => {
    render(<MemoryRouter><SkillsPage /></MemoryRouter>)
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /skills/i })).toBeInTheDocument()
    })
  })

  it('IntegrationsPage renders and shows title', async () => {
    render(<MemoryRouter><IntegrationsPage /></MemoryRouter>)
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /integrations/i })).toBeInTheDocument()
    })
  })

  it('ChatPage renders (chat not found state)', () => {
    chatStoreModule.useChatStore.setState({ ...EMPTY_CHAT_STORE, currentChat: null })
    render(
      <MemoryRouter initialEntries={['/chat/c1']}>
        <Routes>
          <Route path="/chat/:id" element={<ChatPage />} />
        </Routes>
      </MemoryRouter>,
    )
    // Should show loading or not found message
    expect(screen.getByText(/loading|not found/i)).toBeInTheDocument()
  })

  it('GroupPage renders (group not found state)', async () => {
    render(
      <MemoryRouter initialEntries={['/groups/g1']}>
        <Routes>
          <Route path="/groups/:id" element={<GroupPage />} />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => {
      expect(screen.getByText(/loading|not found/i)).toBeInTheDocument()
    })
  })
})
