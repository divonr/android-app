/**
 * Smoke tests — render each screen with mocked deps and verify no crash.
 * Updated for R6: Groups, Skills, Integrations, SkillEditor, Logs, Welcome.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'

// ─── CSS module mocks ─────────────────────────────────────────────────────────

// vi.mock() calls are hoisted above all code; use vi.hoisted() so the factory
// function is available when the hoisted vi.mock() factories execute.
const cssProxy = vi.hoisted(() => () => ({ default: new Proxy({}, { get: (_t, prop) => String(prop) }) }))

vi.mock('../pages/ChatHistoryPage.module.css', cssProxy)
vi.mock('../pages/ChatPage.module.css', cssProxy)
vi.mock('../pages/KeysPage.module.css', cssProxy)
vi.mock('../pages/SettingsPage.module.css', cssProxy)
vi.mock('../pages/SkillsPage.module.css', cssProxy)
vi.mock('../pages/SkillEditorPage.module.css', cssProxy)
vi.mock('../pages/IntegrationsPage.module.css', cssProxy)
vi.mock('../pages/GroupPage.module.css', cssProxy)
vi.mock('../pages/LogsPage.module.css', cssProxy)
vi.mock('../pages/WelcomePage.module.css', cssProxy)
vi.mock('../components/Markdown.module.css', cssProxy)

// ─── Mock appLogger (avoid console intercept side-effects in tests) ───────────

vi.mock('../utils/appLogger', () => ({
  getLogs: vi.fn().mockReturnValue([]),
  subscribe: vi.fn().mockImplementation((fn: (logs: unknown[]) => void) => {
    fn([])
    return () => {}
  }),
  clearLogs: vi.fn(),
  addLog: vi.fn(),
}))

// ─── Mock all API calls ───────────────────────────────────────────────────────

// These constants are referenced inside the vi.mock() factory below, which is
// hoisted to the top of the file by Vitest. They must be initialized via
// vi.hoisted() so they exist when the factory runs.
const { mockGroup, mockSkill } = vi.hoisted(() => ({
  mockGroup: {
    group_id: 'g1',
    group_name: 'Test Group',
    is_project: false,
    system_prompt: null as null,
    group_attachments: [] as unknown[],
  },
  mockSkill: { name: 'test-skill', description: 'A test skill', enabled: false },
}))

vi.mock('../api/client', () => ({
  chats: {
    list: vi.fn().mockResolvedValue({ user_name: 'u', chat_history: [], groups: [] }),
    get: vi.fn().mockResolvedValue(null),
    create: vi.fn().mockResolvedValue({
      chat_id: 'new-chat-1', preview_name: 'שיחה חדשה', messages: [],
      systemPrompt: '', group: null, messageNodes: [], currentVariantPath: [],
      shareLink: '', shareId: '',
    }),
    update: vi.fn(),
    delete: vi.fn(),
  },
  groups: {
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn(),
    update: vi.fn().mockResolvedValue({ ...mockGroup }),
    delete: vi.fn(),
    addChat: vi.fn(),
    removeChat: vi.fn(),
    addAttachment: vi.fn().mockResolvedValue({ ...mockGroup, is_project: true }),
    removeAttachment: vi.fn().mockResolvedValue({ ...mockGroup, is_project: true }),
  },
  search: { query: vi.fn().mockResolvedValue([]) },
  providers: { models: vi.fn().mockResolvedValue([]), list: vi.fn().mockResolvedValue([]) },
  branching: { create: vi.fn(), switchVariant: vi.fn() },
  skills: {
    list: vi.fn().mockResolvedValue([]),
    getContent: vi.fn().mockResolvedValue('# Test\n\nContent'),
    setEnabled: vi.fn().mockResolvedValue({ ...mockSkill, enabled: true }),
    create: vi.fn().mockResolvedValue({ ...mockSkill, enabled: true }),
    delete: vi.fn().mockResolvedValue(undefined),
  },
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
      current_user: 'u', selected_provider: 'openai', selected_model: 'gpt-4o',
      temperature: 0.7,
      titleGenerationSettings: { enabled: false, provider: 'auto', updateOnExtension: false },
      multiMessageMode: false,
      childLockSettings: { enabled: false, encryptedPassword: '', startTime: '22:00', endTime: '07:00' },
      enabledTools: [], excludedToolIds: [], skipWelcomeScreen: false, starredModels: [],
      remoteSync: { enabled: false, serverBaseUrl: 'https://sync.example.com', authToken: '', syncApiKeys: false },
    }),
    update: vi.fn().mockResolvedValue({
      current_user: 'u', selected_provider: 'openai', selected_model: 'gpt-4o',
      temperature: 0.7,
      titleGenerationSettings: { enabled: false, provider: 'auto', updateOnExtension: false },
      multiMessageMode: false,
      childLockSettings: { enabled: false, encryptedPassword: '', startTime: '22:00', endTime: '07:00' },
      enabledTools: ['get_date_time'], excludedToolIds: [], skipWelcomeScreen: false, starredModels: [],
      remoteSync: { enabled: false, serverBaseUrl: '', authToken: '', syncApiKeys: false },
    }),
  },
  sync: {
    pull: vi.fn().mockResolvedValue({ ok: true }),
    status: vi.fn().mockResolvedValue({ enabled: false, serverBaseUrl: '', lastChangeTick: 0 }),
  },
  integrations: {
    github: { get: vi.fn().mockResolvedValue(null), disconnect: vi.fn(), startOAuth: vi.fn() },
    google: {
      get: vi.fn().mockResolvedValue(null),
      disconnect: vi.fn(),
      startOAuth: vi.fn(),
      updateServices: vi.fn(),
    },
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

// Import mock references for per-test overrides
import {
  skills as skillsApi,
  groups as groupsApi,
  settings as settingsApi,
  integrations as integrationsApi,
  chats as chatsApi,
} from '../api/client'

// Type helpers for mocked functions
const mSkillsList = vi.mocked(skillsApi.list)
const mSkillsGetContent = vi.mocked(skillsApi.getContent)
const mSkillsSetEnabled = vi.mocked(skillsApi.setEnabled)
const mGroupsList = vi.mocked(groupsApi.list)
const mGroupsUpdate = vi.mocked(groupsApi.update)
const mChatsList = vi.mocked(chatsApi.list)
const mSettingsUpdate = vi.mocked(settingsApi.update)
const mGithubStartOAuth = vi.mocked(integrationsApi.github.startOAuth)
const mSettingsGet = vi.mocked(settingsApi.get)

beforeEach(() => {
  chatStoreModule.useChatStore.setState(EMPTY_CHAT_STORE)
  vi.clearAllMocks()

  // Re-establish default resolved values after clearAllMocks
  mSkillsList.mockResolvedValue([])
  mSkillsGetContent.mockResolvedValue('# Test\n\nContent')
  mSkillsSetEnabled.mockResolvedValue({ ...mockSkill, enabled: true })
  vi.mocked(skillsApi.create).mockResolvedValue({ ...mockSkill, enabled: true })
  vi.mocked(skillsApi.delete).mockResolvedValue(undefined)

  mGroupsList.mockResolvedValue([])
  mGroupsUpdate.mockResolvedValue({ ...mockGroup })
  vi.mocked(groupsApi.addAttachment).mockResolvedValue({ ...mockGroup, is_project: true })
  vi.mocked(groupsApi.removeAttachment).mockResolvedValue({ ...mockGroup })

  mChatsList.mockResolvedValue({ user_name: 'u', chat_history: [], groups: [] })

  mSettingsGet.mockResolvedValue({
    current_user: 'u', selected_provider: 'openai', selected_model: 'gpt-4o', temperature: 0.7,
    titleGenerationSettings: { enabled: false, provider: 'auto', updateOnExtension: false },
    multiMessageMode: false,
    childLockSettings: { enabled: false, encryptedPassword: '', startTime: '22:00', endTime: '07:00' },
    enabledTools: [], excludedToolIds: [], skipWelcomeScreen: false, starredModels: [],
    remoteSync: { enabled: false, serverBaseUrl: '', authToken: '', syncApiKeys: false },
  })
  mSettingsUpdate.mockResolvedValue({
    current_user: 'u', selected_provider: 'openai', selected_model: 'gpt-4o', temperature: 0.7,
    titleGenerationSettings: { enabled: false, provider: 'auto', updateOnExtension: false },
    multiMessageMode: false,
    childLockSettings: { enabled: false, encryptedPassword: '', startTime: '22:00', endTime: '07:00' },
    enabledTools: ['get_date_time'], excludedToolIds: [], skipWelcomeScreen: false, starredModels: [],
    remoteSync: { enabled: false, serverBaseUrl: '', authToken: '', syncApiKeys: false },
  })
  vi.mocked(integrationsApi.github.get).mockResolvedValue(null)
  vi.mocked(integrationsApi.google.get).mockResolvedValue(null)
  mGithubStartOAuth.mockReturnValue(undefined)
  vi.mocked(integrationsApi.google.startOAuth).mockReturnValue(undefined)
})

// ─── Page imports ─────────────────────────────────────────────────────────────

import ChatHistoryPage from '../pages/ChatHistoryPage'
import KeysPage from '../pages/KeysPage'
import SettingsPage from '../pages/SettingsPage'
import SkillsPage from '../pages/SkillsPage'
import SkillEditorPage from '../pages/SkillEditorPage'
import IntegrationsPage from '../pages/IntegrationsPage'
import GroupPage from '../pages/GroupPage'
import ChatPage from '../pages/ChatPage'
import LogsPage from '../pages/LogsPage'
import WelcomePage from '../pages/WelcomePage'

// ─── Smoke tests ──────────────────────────────────────────────────────────────

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
      expect(screen.getByRole('heading', { name: /advanced settings/i })).toBeInTheDocument()
    })
  })

  it('SkillsPage renders and shows Skills heading', async () => {
    render(<MemoryRouter><SkillsPage /></MemoryRouter>)
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /skills/i })).toBeInTheDocument()
    })
  })

  it('IntegrationsPage renders and shows Integrations heading', async () => {
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
    expect(screen.getByText(/loading|not found/i)).toBeInTheDocument()
  })

  it('GroupPage renders (group not found: loading indicator or not-found status)', async () => {
    render(
      <MemoryRouter initialEntries={['/groups/g1']}>
        <Routes>
          <Route path="/groups/:id" element={<GroupPage />} />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => {
      // Accept either: loading text OR the role="status" not-found element
      const loadingEl = screen.queryByText(/loading/i)
      const statusEl = screen.queryByRole('status')
      expect(loadingEl ?? statusEl).toBeTruthy()
    })
  })

  it('LogsPage renders with Logs heading', () => {
    render(<MemoryRouter><LogsPage /></MemoryRouter>)
    expect(screen.getByRole('heading', { name: /logs/i })).toBeInTheDocument()
  })

  it('WelcomePage renders with welcome content and navigation', () => {
    render(<MemoryRouter><WelcomePage /></MemoryRouter>)
    expect(screen.getByText(/ברוכים/)).toBeInTheDocument()
    expect(screen.getByText(/יש לי מפתח/)).toBeInTheDocument()
    expect(screen.getByText(/אל תציגו מסך זה/)).toBeInTheDocument()
  })
})

// ─── R6 focused interaction tests ────────────────────────────────────────────

describe('R6 — GroupPage with valid group', () => {
  it('renders group name in heading', async () => {
    mGroupsList.mockResolvedValue([
      { group_id: 'g1', group_name: 'My Group', is_project: false, system_prompt: null, group_attachments: [] }
    ])
    render(
      <MemoryRouter initialEntries={['/groups/g1']}>
        <Routes>
          <Route path="/groups/:id" element={<GroupPage />} />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => {
      expect(screen.getByText('My Group')).toBeInTheDocument()
    })
  })

  it('project mode toggle calls groups.update with isProject=true', async () => {
    mGroupsList.mockResolvedValue([
      { group_id: 'g1', group_name: 'Test Group', is_project: false, system_prompt: null, group_attachments: [] }
    ])
    render(
      <MemoryRouter initialEntries={['/groups/g1']}>
        <Routes>
          <Route path="/groups/:id" element={<GroupPage />} />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText('Test Group')).toBeInTheDocument())
    // Toggle project mode
    const projectToggle = screen.getByLabelText(/פרויקט/)
    fireEvent.click(projectToggle)
    await waitFor(() => {
      expect(mGroupsUpdate).toHaveBeenCalledWith('g1', { isProject: true })
    })
  })
})

describe('R6 — SkillsPage interactions', () => {
  it('shows empty state when no skills installed', async () => {
    render(<MemoryRouter><SkillsPage /></MemoryRouter>)
    await waitFor(() => {
      expect(screen.getByText(/אין סקילים/)).toBeInTheDocument()
    })
  })

  it('shows skill card when skills are loaded', async () => {
    mSkillsList.mockResolvedValue([{ name: 'my-skill', description: 'Does stuff', enabled: true }])
    render(<MemoryRouter><SkillsPage /></MemoryRouter>)
    await waitFor(() => {
      expect(screen.getByText('my-skill')).toBeInTheDocument()
      expect(screen.getByText('Does stuff')).toBeInTheDocument()
    })
  })

  it('enable toggle calls skills.setEnabled with correct args', async () => {
    mSkillsList.mockResolvedValue([{ name: 'test-skill', description: 'Test', enabled: false }])
    render(<MemoryRouter><SkillsPage /></MemoryRouter>)
    await waitFor(() => expect(screen.getByText('test-skill')).toBeInTheDocument())
    const toggle = screen.getByRole('checkbox', { name: /enable test-skill/i })
    fireEvent.click(toggle)
    await waitFor(() => {
      expect(mSkillsSetEnabled).toHaveBeenCalledWith('test-skill', true)
    })
  })

  it('disable toggle calls skills.setEnabled with false', async () => {
    mSkillsList.mockResolvedValue([{ name: 'test-skill', description: 'Test', enabled: true }])
    mSkillsSetEnabled.mockResolvedValue({ name: 'test-skill', description: 'Test', enabled: false })
    render(<MemoryRouter><SkillsPage /></MemoryRouter>)
    await waitFor(() => expect(screen.getByText('test-skill')).toBeInTheDocument())
    const toggle = screen.getByRole('checkbox', { name: /disable test-skill/i })
    fireEvent.click(toggle)
    await waitFor(() => {
      expect(mSkillsSetEnabled).toHaveBeenCalledWith('test-skill', false)
    })
  })
})

describe('R6 — SkillEditorPage', () => {
  it('renders skill name and editor after loading', async () => {
    mSkillsList.mockResolvedValue([{ name: 'my-skill', description: 'A skill', enabled: true }])
    mSkillsGetContent.mockResolvedValue('# My Skill\n\nContent here')
    render(
      <MemoryRouter initialEntries={['/skills/my-skill/edit']}>
        <Routes>
          <Route path="/skills/:name/edit" element={<SkillEditorPage />} />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /skill editor/i })).toBeInTheDocument()
    })
    // The textarea should have the skill content
    await waitFor(() => {
      const textarea = screen.getByRole('textbox', { name: /skill body editor/i })
      expect(textarea).toBeInTheDocument()
    })
  })
})

describe('R6 — IntegrationsPage interactions', () => {
  it('renders tools section with tool toggles', async () => {
    render(<MemoryRouter><IntegrationsPage /></MemoryRouter>)
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /integrations/i })).toBeInTheDocument()
      // Date/time tool toggle present
      expect(screen.getByLabelText(/תאריך ושעה/)).toBeInTheDocument()
    })
  })

  it('tool toggle calls settings.update with enabledTools', async () => {
    render(<MemoryRouter><IntegrationsPage /></MemoryRouter>)
    await waitFor(() => expect(screen.getByRole('heading', { name: /integrations/i })).toBeInTheDocument())
    const datetimeToggle = screen.getByLabelText(/תאריך ושעה/)
    fireEvent.click(datetimeToggle)
    await waitFor(() => {
      expect(mSettingsUpdate).toHaveBeenCalledWith(
        expect.objectContaining({ enabledTools: expect.arrayContaining(['get_date_time']) })
      )
    })
  })

  it('GitHub connect calls startOAuth', async () => {
    render(<MemoryRouter><IntegrationsPage /></MemoryRouter>)
    await waitFor(() => expect(screen.getByRole('heading', { name: /integrations/i })).toBeInTheDocument())
    const githubToggle = screen.getByLabelText(/חיבור ל-GitHub/)
    fireEvent.click(githubToggle)
    expect(mGithubStartOAuth).toHaveBeenCalled()
  })

  it('shows success message on ?github=connected return', async () => {
    render(
      <MemoryRouter initialEntries={['/integrations?github=connected']}>
        <Routes>
          <Route path="/integrations" element={<IntegrationsPage />} />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => {
      expect(screen.getByText(/GitHub חובר/)).toBeInTheDocument()
    })
  })
})
