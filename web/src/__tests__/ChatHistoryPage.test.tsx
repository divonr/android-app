import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import ChatHistoryPage from '../pages/ChatHistoryPage'
import * as chatStoreModule from '../stores/chatStore'
import type { UserChatHistory } from '../api/types'

// ─── CSS modules mock ─────────────────────────────────────────────────────────

vi.mock('../pages/ChatHistoryPage.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))

// ─── Mock the API client ──────────────────────────────────────────────────────

vi.mock('../api/client', () => ({
  chats: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
  groups: {
    list: vi.fn(),
    create: vi.fn(),
    delete: vi.fn(),
  },
  search: {
    query: vi.fn(),
  },
}))

// ─── Sample data ──────────────────────────────────────────────────────────────

const SAMPLE_HISTORY: UserChatHistory = {
  user_name: 'testuser',
  chat_history: [
    {
      chat_id: 'chat-1',
      preview_name: 'My First Chat',
      messages: [
        {
          id: 'm1', role: 'user', text: 'Hello', attachments: [],
          thoughtsStatus: 'NONE',
        },
      ],
      systemPrompt: '',
      group: null,
      messageNodes: [],
      currentVariantPath: [],
      shareLink: '',
      shareId: '',
    },
    {
      chat_id: 'chat-2',
      preview_name: 'Second Chat',
      messages: [],
      systemPrompt: '',
      group: 'group-1',
      messageNodes: [],
      currentVariantPath: [],
      shareLink: '',
      shareId: '',
    },
  ],
  groups: [
    {
      group_id: 'group-1',
      group_name: 'Work',
      system_prompt: null,
      group_attachments: [],
      is_project: false,
    },
  ],
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<ChatHistoryPage />} />
        <Route path="/chat/:id" element={<div data-testid="chat-page">Chat</div>} />
        <Route path="/groups/:id" element={<div data-testid="group-page">Group</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ChatHistoryPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    // Set up the chat store with our sample history
    chatStoreModule.useChatStore.setState({
      history: SAMPLE_HISTORY,
      currentChat: null,
      loading: false,
      error: null,
      loadHistory: vi.fn().mockResolvedValue(undefined),
      loadChat: vi.fn(),
      updateChat: vi.fn(),
      clearCurrentChat: vi.fn(),
    })
  })

  it('renders the page title', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: /chats/i })).toBeInTheDocument()
  })

  it('shows ungrouped chat in list', () => {
    renderPage()
    expect(screen.getByText('My First Chat')).toBeInTheDocument()
  })

  it('shows group in list', () => {
    renderPage()
    expect(screen.getByText('Work')).toBeInTheDocument()
  })

  it('expands group when clicked', async () => {
    renderPage()
    const groupHeader = screen.getByText('Work')
    fireEvent.click(groupHeader)
    await waitFor(() => {
      expect(screen.getByText('Second Chat')).toBeInTheDocument()
    })
  })

  it('shows new chat dialog on button click', () => {
    renderPage()
    const button = screen.getByRole('button', { name: /new chat/i })
    fireEvent.click(button)
    expect(screen.getByText('New Chat')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Chat name')).toBeInTheDocument()
  })

  it('shows search input', () => {
    renderPage()
    expect(screen.getByPlaceholderText(/search chats/i)).toBeInTheDocument()
  })

  it('navigates to chat when clicked', async () => {
    renderPage()
    const chatRow = screen.getByText('My First Chat')
    fireEvent.click(chatRow)
    await waitFor(() => {
      expect(screen.getByTestId('chat-page')).toBeInTheDocument()
    })
  })

  it('shows loading state', () => {
    chatStoreModule.useChatStore.setState({
      history: null,
      currentChat: null,
      loading: true,
      error: null,
      loadHistory: vi.fn(),
      loadChat: vi.fn(),
      updateChat: vi.fn(),
      clearCurrentChat: vi.fn(),
    })
    renderPage()
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it('creates a new chat and navigates', async () => {
    const { chats: chatsApi } = await import('../api/client')
    vi.mocked(chatsApi.create).mockResolvedValue({
      chat_id: 'chat-new',
      preview_name: 'New Chat Test',
      messages: [],
      systemPrompt: '',
      group: null,
      messageNodes: [],
      currentVariantPath: [],
      shareLink: '',
      shareId: '',
    })

    renderPage()
    fireEvent.click(screen.getByRole('button', { name: /new chat/i }))

    const nameInput = screen.getByPlaceholderText('Chat name')
    fireEvent.change(nameInput, { target: { value: 'New Chat Test' } })

    const createBtn = screen.getByRole('button', { name: /create/i })
    fireEvent.click(createBtn)

    await waitFor(() => {
      expect(chatsApi.create).toHaveBeenCalledWith({
        previewName: 'New Chat Test',
        systemPrompt: '',
        groupId: null,
      })
    })
  })

  it('shows search results', async () => {
    const { search: searchApi } = await import('../api/client')
    vi.mocked(searchApi.query).mockResolvedValue([
      {
        chatId: 'chat-1',
        chatTitle: 'My First Chat',
        matchType: 'CONTENT',
        messageIndex: 0,
        snippet: 'Hello world',
      },
    ])

    renderPage()
    const searchInput = screen.getByPlaceholderText(/search chats/i)
    fireEvent.change(searchInput, { target: { value: 'hello' } })

    // Wait for debounce + results
    await waitFor(
      () => {
        expect(searchApi.query).toHaveBeenCalledWith('hello')
      },
      { timeout: 1000 },
    )
  })
})
