import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import ChatPage from '../pages/ChatPage'
import * as chatStoreModule from '../stores/chatStore'
import type { Chat } from '../api/types'
import type { StreamCallbacks } from '../api/stream'

// ─── CSS modules mock ─────────────────────────────────────────────────────────

vi.mock('../pages/ChatPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))

vi.mock('../components/Markdown.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))

// ─── Mock modules ─────────────────────────────────────────────────────────────
// Note: we use vi.importActual for api/client so only the functions we test
// need to be overridden. sendStream/resendStream need vi.fn() so we can mock
// implementations per-test using vi.mocked().

vi.mock('../api/stream', async () => ({
  sendStream: vi.fn(() => ({ abort: vi.fn(), done: Promise.resolve() })),
  resendStream: vi.fn(() => ({ abort: vi.fn(), done: Promise.resolve() })),
  parseSseChunk: vi.fn(),
}))

vi.mock('../api/client', async () => ({
  providers: {
    models: () => Promise.resolve([]),
    list: () => Promise.resolve([]),
    refresh: () => Promise.resolve({ ok: true, changed: false }),
  },
  branching: {
    create: vi.fn().mockResolvedValue({}),
    switchVariant: vi.fn().mockResolvedValue({}),
    info: () => Promise.resolve({}),
  },
  skills: {
    list: () => Promise.resolve([]),
    getContent: () => Promise.resolve(''),
    create: vi.fn().mockResolvedValue({}),
    setEnabled: vi.fn().mockResolvedValue({}),
    delete: vi.fn().mockResolvedValue(undefined),
  },
  files: {
    upload: () => Promise.resolve({ file_name: 'test.txt', mime_type: 'text/plain' }),
    delete: () => Promise.resolve(),
  },
  messages: {
    delete: vi.fn().mockResolvedValue({}),
    add: vi.fn().mockResolvedValue({}),
    resend: vi.fn().mockResolvedValue(new Response()),
  },
  chats: {
    list: () => Promise.resolve({ user_name: 'u', chat_history: [], groups: [] }),
    get: () => Promise.resolve(null),
    create: vi.fn().mockResolvedValue({}),
    update: vi.fn().mockResolvedValue({}),
    delete: vi.fn().mockResolvedValue(undefined),
    export: () => Promise.resolve({}),
    import: () => Promise.resolve({}),
    generateTitle: () => Promise.resolve({ title: 'Test' }),
  },
  apiKeys: {
    list: () => Promise.resolve([]),
    create: vi.fn().mockResolvedValue({}),
    update: vi.fn().mockResolvedValue({}),
    delete: vi.fn().mockResolvedValue(undefined),
    toggle: vi.fn().mockResolvedValue({}),
    reorder: vi.fn().mockResolvedValue([]),
  },
  settings: {
    get: () => Promise.resolve({
      current_user: 'u', selected_provider: 'openai', selected_model: 'gpt-4o',
      temperature: 0.7,
      titleGenerationSettings: { enabled: false, provider: 'auto', updateOnExtension: false },
      multiMessageMode: false,
      childLockSettings: { enabled: false, encryptedPassword: '', startTime: '22:00', endTime: '07:00' },
      enabledTools: [], excludedToolIds: [], skipWelcomeScreen: false, starredModels: [],
    }),
    update: vi.fn().mockResolvedValue({}),
  },
  integrations: {
    github: { get: () => Promise.resolve(null), disconnect: vi.fn(), startOAuth: vi.fn() },
    google: { get: () => Promise.resolve(null), disconnect: vi.fn(), startOAuth: vi.fn(), updateServices: vi.fn() },
  },
  customProviders: {
    list: () => Promise.resolve([]),
    create: vi.fn(),
    replace: vi.fn(),
    delete: vi.fn(),
  },
  fullCustomProviders: {
    list: () => Promise.resolve([]),
    create: vi.fn(),
    replace: vi.fn(),
    delete: vi.fn(),
  },
  search: { query: () => Promise.resolve([]) },
  groups: {
    list: () => Promise.resolve([]),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    addChat: vi.fn(),
    removeChat: vi.fn(),
    addAttachment: vi.fn(),
    removeAttachment: vi.fn(),
  },
}))

// ─── Sample chat ──────────────────────────────────────────────────────────────

const SAMPLE_CHAT: Chat = {
  chat_id: 'c1',
  preview_name: 'Test Chat',
  messages: [
    {
      id: 'm1',
      role: 'user',
      text: 'Hello',
      attachments: [],
      datetime: '2024-01-01T10:00:00Z',
      thoughtsStatus: 'NONE',
    },
    {
      id: 'm2',
      role: 'assistant',
      text: 'Hi there! How can I help?',
      attachments: [],
      datetime: '2024-01-01T10:00:05Z',
      model: 'gpt-4o',
      thoughtsStatus: 'NONE',
    },
  ],
  systemPrompt: '',
  group: null,
  messageNodes: [],
  currentVariantPath: [],
  shareLink: '',
  shareId: '',
}

// ─── Import stream after mock so we can use vi.mocked ──────────────────────────

import * as streamModule from '../api/stream'
import * as clientModule from '../api/client'

function renderChatPage(chatId = 'c1') {
  return render(
    <MemoryRouter initialEntries={[`/chat/${chatId}`]}>
      <Routes>
        <Route path="/chat/:id" element={<ChatPage />} />
        <Route path="/" element={<div data-testid="home">Home</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ChatPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    chatStoreModule.useChatStore.setState({
      history: null,
      currentChat: SAMPLE_CHAT,
      loading: false,
      error: null,
      loadHistory: vi.fn(),
      loadChat: vi.fn().mockResolvedValue(undefined),
      updateChat: vi.fn(),
      clearCurrentChat: vi.fn(),
    })
    vi.mocked(streamModule.sendStream).mockReturnValue({ abort: vi.fn(), done: Promise.resolve() })
    vi.mocked(streamModule.resendStream).mockReturnValue({ abort: vi.fn(), done: Promise.resolve() })
  })

  it('renders user and assistant messages', async () => {
    renderChatPage()
    await waitFor(() => {
      expect(screen.getByText('Hello')).toBeInTheDocument()
      expect(screen.getByText(/Hi there/i)).toBeInTheDocument()
    })
  })

  it('shows the chat title in topbar', async () => {
    renderChatPage()
    await waitFor(() => {
      expect(screen.getByText('Test Chat')).toBeInTheDocument()
    })
  })

  it('has a text input and send button', async () => {
    renderChatPage()
    await waitFor(() => {
      expect(screen.getByPlaceholderText(/type a message/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/send message/i)).toBeInTheDocument()
    })
  })

  it('send button is disabled when input is empty', async () => {
    renderChatPage()
    await waitFor(() => {
      expect(screen.getByLabelText(/send message/i)).toBeDisabled()
    })
  })

  it('send button is enabled when input has text', async () => {
    renderChatPage()
    await waitFor(() => {
      screen.getByPlaceholderText(/type a message/i)
    })
    const input = screen.getByPlaceholderText(/type a message/i)
    fireEvent.change(input, { target: { value: 'Test message' } })
    expect(screen.getByLabelText(/send message/i)).not.toBeDisabled()
  })

  it('calls sendStream when send button is clicked', async () => {
    renderChatPage()
    await waitFor(() => {
      screen.getByPlaceholderText(/type a message/i)
    })

    const input = screen.getByPlaceholderText(/type a message/i)
    fireEvent.change(input, { target: { value: 'Hello world' } })
    fireEvent.click(screen.getByLabelText(/send message/i))

    expect(streamModule.sendStream).toHaveBeenCalledWith(
      expect.objectContaining({
        chatId: 'c1',
        messages: expect.arrayContaining([
          expect.objectContaining({ role: 'user', text: 'Hello world' }),
        ]),
      }),
      expect.any(Object),
    )
  })

  it('shows streaming partial text as it arrives', async () => {
    let capturedCallbacks: StreamCallbacks | null = null
    vi.mocked(streamModule.sendStream).mockImplementation((_req, callbacks) => {
      capturedCallbacks = callbacks
      return {
        abort: vi.fn(),
        done: new Promise(() => {}), // never resolves — simulating active stream
      }
    })

    renderChatPage()
    await waitFor(() => {
      screen.getByPlaceholderText(/type a message/i)
    })

    const input = screen.getByPlaceholderText(/type a message/i)
    fireEvent.change(input, { target: { value: 'Tell me a story' } })
    fireEvent.click(screen.getByLabelText(/send message/i))

    await act(async () => {
      capturedCallbacks?.onPartial?.({ text: 'Once upon' })
    })

    await waitFor(() => {
      expect(screen.getByText(/once upon/i)).toBeInTheDocument()
    })

    await act(async () => {
      capturedCallbacks?.onPartial?.({ text: ' a time' })
    })

    await waitFor(() => {
      expect(screen.getByText(/once upon a time/i)).toBeInTheDocument()
    })
  })

  it('shows thinking indicator during thinking phase', async () => {
    let capturedCallbacks: StreamCallbacks | null = null
    vi.mocked(streamModule.sendStream).mockImplementation((_req, callbacks) => {
      capturedCallbacks = callbacks
      return { abort: vi.fn(), done: new Promise(() => {}) }
    })

    renderChatPage()
    await waitFor(() => {
      screen.getByPlaceholderText(/type a message/i)
    })

    const input = screen.getByPlaceholderText(/type a message/i)
    fireEvent.change(input, { target: { value: 'Complex question' } })
    fireEvent.click(screen.getByLabelText(/send message/i))

    await act(async () => {
      capturedCallbacks?.onThinkingStarted?.({})
      capturedCallbacks?.onThinkingPartial?.({ text: 'Let me think' })
    })

    await waitFor(() => {
      // The ThinkingBlock shows "Thinking…" text (the "No thinking" option is also present but different element)
      expect(screen.getByText(/thinking…/i)).toBeInTheDocument()
    })
  })

  it('shows stop button while streaming', async () => {
    vi.mocked(streamModule.sendStream).mockReturnValue({
      abort: vi.fn(),
      done: new Promise(() => {}), // never completes
    })

    renderChatPage()
    await waitFor(() => {
      screen.getByPlaceholderText(/type a message/i)
    })

    const input = screen.getByPlaceholderText(/type a message/i)
    fireEvent.change(input, { target: { value: 'Hello' } })
    fireEvent.click(screen.getByLabelText(/send message/i))

    await waitFor(() => {
      expect(screen.getByLabelText(/stop generation/i)).toBeInTheDocument()
    })
  })

  it('calls abort when stop button is clicked', async () => {
    const abortFn = vi.fn()
    vi.mocked(streamModule.sendStream).mockReturnValue({
      abort: abortFn,
      done: new Promise(() => {}),
    })

    renderChatPage()
    await waitFor(() => {
      screen.getByPlaceholderText(/type a message/i)
    })

    const input = screen.getByPlaceholderText(/type a message/i)
    fireEvent.change(input, { target: { value: 'Hello' } })
    fireEvent.click(screen.getByLabelText(/send message/i))

    await waitFor(() => {
      screen.getByLabelText(/stop generation/i)
    })

    fireEvent.click(screen.getByLabelText(/stop generation/i))
    expect(abortFn).toHaveBeenCalled()
  })

  it('navigates back on back button click', async () => {
    renderChatPage()
    await waitFor(() => {
      screen.getByLabelText(/back to chats/i)
    })

    fireEvent.click(screen.getByLabelText(/back to chats/i))

    await waitFor(() => {
      expect(screen.getByTestId('home')).toBeInTheDocument()
    })
  })
})

describe('ChatPage — tool calls', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    chatStoreModule.useChatStore.setState({
      history: null,
      currentChat: SAMPLE_CHAT,
      loading: false,
      error: null,
      loadHistory: vi.fn(),
      loadChat: vi.fn(),
      updateChat: vi.fn(),
      clearCurrentChat: vi.fn(),
    })
    vi.mocked(streamModule.sendStream).mockReturnValue({ abort: vi.fn(), done: Promise.resolve() })
  })

  it('renders tool call block when tool_call event arrives', async () => {
    let capturedCallbacks: StreamCallbacks | null = null
    vi.mocked(streamModule.sendStream).mockImplementation((_req, callbacks) => {
      capturedCallbacks = callbacks
      return { abort: vi.fn(), done: new Promise(() => {}) }
    })

    renderChatPage()
    await waitFor(() => {
      screen.getByPlaceholderText(/type a message/i)
    })

    const input = screen.getByPlaceholderText(/type a message/i)
    fireEvent.change(input, { target: { value: 'Search the web' } })
    fireEvent.click(screen.getByLabelText(/send message/i))

    await act(async () => {
      capturedCallbacks?.onToolCall?.({
        toolId: 't1',
        toolName: 'web_search',
        parameters: { query: 'test' },
      })
    })

    await waitFor(() => {
      expect(screen.getByText('web_search')).toBeInTheDocument()
    })
  })
})

describe('ChatPage — branch navigation', () => {
  const BRANCHED_CHAT: Chat = {
    ...SAMPLE_CHAT,
    messageNodes: [
      {
        nodeId: 'node-1',
        parentNodeId: null,
        variants: [
          {
            variantId: 'v1',
            userMessage: { id: 'm1', role: 'user', text: 'Hello', attachments: [], thoughtsStatus: 'NONE' },
            responses: [],
          },
          {
            variantId: 'v2',
            userMessage: { id: 'm3', role: 'user', text: 'Hello v2', attachments: [], thoughtsStatus: 'NONE' },
            responses: [],
          },
        ],
      },
    ],
    messages: [
      {
        id: 'm1',
        role: 'user',
        text: 'Hello',
        attachments: [],
        thoughtsStatus: 'NONE',
        nodeId: 'node-1',
        variantId: 'v1',
      },
    ],
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(streamModule.sendStream).mockReturnValue({ abort: vi.fn(), done: Promise.resolve() })
  })

  it('renders branch navigator when node has multiple variants', async () => {
    chatStoreModule.useChatStore.setState({
      history: null,
      currentChat: BRANCHED_CHAT,
      loading: false,
      error: null,
      loadHistory: vi.fn(),
      loadChat: vi.fn(),
      updateChat: vi.fn(),
      clearCurrentChat: vi.fn(),
    })

    renderChatPage()
    await waitFor(() => {
      // Should show variant counter like "1/2"
      expect(screen.getByText('1/2')).toBeInTheDocument()
    })
  })

  it('calls switchVariant when navigation arrow is clicked', async () => {
    vi.mocked(clientModule.branching.switchVariant).mockResolvedValue(SAMPLE_CHAT)

    chatStoreModule.useChatStore.setState({
      history: null,
      currentChat: BRANCHED_CHAT,
      loading: false,
      error: null,
      loadHistory: vi.fn(),
      loadChat: vi.fn(),
      updateChat: vi.fn(),
      clearCurrentChat: vi.fn(),
    })

    renderChatPage()
    await waitFor(() => {
      screen.getByText('1/2')
    })

    const nextBtn = screen.getByLabelText(/next variant/i)
    fireEvent.click(nextBtn)

    await waitFor(() => {
      expect(clientModule.branching.switchVariant).toHaveBeenCalledWith('c1', 'node-1', 1)
    })
  })
})
