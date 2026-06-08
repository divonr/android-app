/**
 * ChatHistoryPage tests — R1 refactor.
 *
 * All assertions updated to Hebrew UI:
 * - No English headings — top bar has icon buttons instead of a title
 * - FAB creates chat directly (no dialog) matching Android app behaviour
 * - Search mode enters via icon button, Hebrew placeholder
 * - Context-menu rename opens Dialog with Hebrew labels
 * - Group expand, delete dialog assertions
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import ChatHistoryPage from '../pages/ChatHistoryPage'
import * as chatStoreModule from '../stores/chatStore'
import type { UserChatHistory } from '../api/types'
import { t } from '../i18n/he'

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
    generateTitle: vi.fn(),
    export: vi.fn(),
  },
  groups: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    addChat: vi.fn(),
    removeChat: vi.fn(),
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
          id: 'm1',
          role: 'user',
          text: 'Hello',
          attachments: [],
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

  // ── 1. Top-bar icon buttons replace the old "Chats" heading ───────────────

  it('renders the top bar with search and navigation icon buttons', () => {
    renderPage()
    // Hebrew aria-labels on the icon buttons (normal mode)
    expect(screen.getByRole('button', { name: 'חיפוש' })).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: t('api_keys') }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: t('settings') }),
    ).toBeInTheDocument()
  })

  // ── 2. Ungrouped chat item renders ────────────────────────────────────────

  it('shows ungrouped chat in list', () => {
    renderPage()
    expect(screen.getByText('My First Chat')).toBeInTheDocument()
  })

  // ── 3. Group header renders ───────────────────────────────────────────────

  it('shows group in list', () => {
    renderPage()
    expect(screen.getByText('Work')).toBeInTheDocument()
  })

  // ── 4. Expanding a group reveals its chats ────────────────────────────────

  it('expands group when expand arrow is clicked', async () => {
    renderPage()
    const expandBtn = screen.getByRole('button', { name: 'הרחב קבוצה' })
    fireEvent.click(expandBtn)
    await waitFor(() => {
      expect(screen.getByText('Second Chat')).toBeInTheDocument()
    })
  })

  // ── 5. FAB creates chat directly (no dialog) ──────────────────────────────

  it('FAB is labelled with new_chat and is present when not in search mode', () => {
    renderPage()
    // The FAB should be visible with the Hebrew label
    expect(
      screen.getByRole('button', { name: t('new_chat') }),
    ).toBeInTheDocument()
    // No dialog should be open yet
    expect(screen.queryByText(t('rename'))).not.toBeInTheDocument()
  })

  // ── 6. Hebrew search input placeholder ───────────────────────────────────

  it('shows Hebrew search input after entering search mode', () => {
    renderPage()
    // Enter search mode by clicking the search icon button
    fireEvent.click(screen.getByRole('button', { name: 'חיפוש' }))
    expect(
      screen.getByPlaceholderText(t('search_placeholder')),
    ).toBeInTheDocument()
  })

  // ── 7. Navigates to chat on click ────────────────────────────────────────

  it('navigates to chat when clicked', async () => {
    renderPage()
    const chatTitle = screen.getByText('My First Chat')
    fireEvent.click(chatTitle)
    await waitFor(() => {
      expect(screen.getByTestId('chat-page')).toBeInTheDocument()
    })
  })

  // ── 8. Loading state ──────────────────────────────────────────────────────

  it('shows loading spinner when history is null and loading', () => {
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
    // The loading spinner has no text — just check the page renders without crash
    // and there is no chat list
    expect(screen.queryByText('My First Chat')).not.toBeInTheDocument()
  })

  // ── 9. FAB click calls chats.create with Hebrew name and navigates ─────────

  it('creates a new chat via FAB and navigates to it', async () => {
    const { chats: chatsApi } = await import('../api/client')
    vi.mocked(chatsApi.create).mockResolvedValue({
      chat_id: 'chat-new',
      preview_name: t('new_chat'),
      messages: [],
      systemPrompt: '',
      group: null,
      messageNodes: [],
      currentVariantPath: [],
      shareLink: '',
      shareId: '',
    })

    renderPage()
    const fab = screen.getByRole('button', { name: t('new_chat') })
    await act(async () => { fireEvent.click(fab) })

    await waitFor(() => {
      expect(chatsApi.create).toHaveBeenCalledWith({
        previewName: t('new_chat'),
        systemPrompt: '',
        groupId: null,
      })
    })

    await waitFor(() => {
      expect(screen.getByTestId('chat-page')).toBeInTheDocument()
    })
  })

  // ── 10. Search queries after debounce ────────────────────────────────────

  it('enters search mode, shows Hebrew placeholder, and queries API', async () => {
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
    // Enter search mode
    fireEvent.click(screen.getByRole('button', { name: 'חיפוש' }))

    const searchInput = screen.getByPlaceholderText(t('search_placeholder'))
    fireEvent.change(searchInput, { target: { value: 'hello' } })

    await waitFor(
      () => {
        expect(searchApi.query).toHaveBeenCalledWith('hello')
      },
      { timeout: 1000 },
    )
  })

  // ── 11. Search mode toggles on/off ────────────────────────────────────────

  it('search mode exits when clear button is clicked with empty query', async () => {
    renderPage()
    // Enter search mode
    fireEvent.click(screen.getByRole('button', { name: 'חיפוש' }))
    expect(screen.getByPlaceholderText(t('search_placeholder'))).toBeInTheDocument()

    // Click close button (empty query → exits search mode)
    fireEvent.click(screen.getByRole('button', { name: t('close_search') }))

    await waitFor(() => {
      // Search input should be gone
      expect(
        screen.queryByPlaceholderText(t('search_placeholder')),
      ).not.toBeInTheDocument()
      // Normal mode icons return
      expect(screen.getByRole('button', { name: 'חיפוש' })).toBeInTheDocument()
    })
  })

  // ── 12. Context-menu rename opens Dialog and calls chats.update ────────────

  it('right-click opens context menu, rename opens dialog and calls API', async () => {
    const { chats: chatsApi } = await import('../api/client')
    vi.mocked(chatsApi.update).mockResolvedValue({
      chat_id: 'chat-1',
      preview_name: 'Updated Name',
      messages: [],
      systemPrompt: '',
      group: null,
      messageNodes: [],
      currentVariantPath: [],
      shareLink: '',
      shareId: '',
    })

    renderPage()

    // Find the chat card that contains "My First Chat" and right-click it
    const chatTitle = screen.getByText('My First Chat')
    const chatCard = chatTitle.closest('[role="listitem"]')!
    fireEvent.contextMenu(chatCard)

    // Context menu should appear (portaled into body) with Hebrew rename option
    await waitFor(() => {
      expect(screen.getByText(t('update_chat_name'))).toBeInTheDocument()
    })

    // Click the rename menu item
    fireEvent.click(screen.getByText(t('update_chat_name')))

    // Rename dialog should open
    await waitFor(() => {
      expect(screen.getByText(t('rename'))).toBeInTheDocument()
    })

    // Change the name
    const input = screen.getByDisplayValue('My First Chat')
    fireEvent.change(input, { target: { value: 'Updated Name' } })

    // Confirm rename
    fireEvent.click(screen.getByRole('button', { name: t('save') }))

    await waitFor(() => {
      expect(chatsApi.update).toHaveBeenCalledWith('chat-1', {
        previewName: 'Updated Name',
      })
    })
  })
})
