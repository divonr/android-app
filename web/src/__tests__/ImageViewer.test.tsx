/**
 * The in-app image viewer: a thumbnail click opens it, Escape closes it, its actions are in a
 * menu behind the three dots, "save" is a download link, and "forward" hands the image to the
 * chosen chat's draft.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'

vi.mock('../api/client', async () => ({
  chats: { list: vi.fn(), create: vi.fn() },
  files: { upload: vi.fn(), delete: vi.fn() },
}))

import * as clientModule from '../api/client'
import AttachmentView from '../components/chat/AttachmentView'
import { ImageViewerHost, useImageViewerStore } from '../components/chat/ImageViewer'
import { useChatStore } from '../stores/chatStore'
import { peekForwardedFile, clearForwardedFile } from '../utils/forwardedFile'
import type { Attachment, Chat } from '../api/types'

const SHA = 'a'.repeat(64)
const image: Attachment = { file_name: 'cat.png', mime_type: 'image/png', sha256: SHA, size: 3 } as Attachment

function chat(id: string, name: string, datetime: string): Chat {
  return {
    chat_id: id, preview_name: name, systemPrompt: '', messageNodes: [], currentVariantPath: [],
    shareLink: '', shareId: '', messages: [{ role: 'user', text: 'x', datetime }],
  } as unknown as Chat
}

const Where: React.FC = () => <div data-testid="where">{useLocation().pathname}</div>

function renderApp() {
  return render(
    <MemoryRouter initialEntries={['/chat/current']}>
      <Routes>
        <Route path="*" element={<><AttachmentView att={image} /><Where /><ImageViewerHost /></>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(new Blob(['abc'], { type: 'image/png' }))))
  useChatStore.setState({
    history: {
      user_name: 'u',
      groups: [],
      chat_history: [
        chat('current', 'הנוכחית', '2026-01-03T00:00:00Z'),
        chat('old', 'ישנה', '2026-01-01T00:00:00Z'),
        chat('new', 'חדשה', '2026-01-02T00:00:00Z'),
      ],
    },
  })
})

afterEach(() => {
  act(() => useImageViewerStore.getState().close())
  vi.unstubAllGlobals()
})

describe('ImageViewer', () => {
  it('opens on a thumbnail click and closes on Escape', async () => {
    renderApp()
    expect(screen.queryByTestId('image-viewer')).toBeNull()
    fireEvent.click(screen.getByRole('link', { name: /פתיחת התמונה/ }))
    const viewer = await screen.findByTestId('image-viewer')
    expect(viewer).toBeInTheDocument()

    // The actions are hidden until the three dots are clicked
    expect(screen.queryByRole('menuitem', { name: 'שמירה להורדות' })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: 'העברה לשיחה אחרת' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'אפשרויות' }))
    expect(screen.getByRole('menuitem', { name: 'העברה לשיחה אחרת' })).toBeInTheDocument()

    const save = screen.getByRole('menuitem', { name: 'שמירה להורדות' })
    expect(save.getAttribute('href')).toContain('download=1')
    expect(save).toHaveAttribute('download', 'cat.png')
    // jsdom cannot share files: the action is hidden
    expect(screen.queryByRole('menuitem', { name: 'שיתוף' })).toBeNull()

    // Escape closes the menu first, then the viewer
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('menuitem', { name: 'שמירה להורדות' })).toBeNull()
    expect(screen.getByTestId('image-viewer')).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByTestId('image-viewer')).toBeNull())
  })

  it('a click outside the open menu closes the menu only', async () => {
    renderApp()
    fireEvent.click(screen.getByRole('link', { name: /פתיחת התמונה/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'אפשרויות' }))
    expect(screen.getByRole('menu')).toBeInTheDocument()
    fireEvent.click(screen.getByTestId('image-viewer-menu-backdrop'))
    expect(screen.queryByRole('menu')).toBeNull()
    expect(screen.getByTestId('image-viewer')).toBeInTheDocument()
    // The three dots toggle it
    fireEvent.click(screen.getByRole('button', { name: 'אפשרויות' }))
    fireEvent.click(screen.getByRole('button', { name: 'אפשרויות' }))
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('a modified click is left to the browser (new tab)', () => {
    renderApp()
    fireEvent.click(screen.getByRole('link', { name: /פתיחת התמונה/ }), { ctrlKey: true })
    expect(screen.queryByTestId('image-viewer')).toBeNull()
  })

  it('forwards the image to another chat: the other chats, newest first, then that chat opens', async () => {
    renderApp()
    fireEvent.click(screen.getByRole('link', { name: /פתיחת התמונה/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'אפשרויות' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'העברה לשיחה אחרת' }))
    expect(screen.queryByRole('menu')).toBeNull()

    // A full-screen list: new chat, then the chats without the current one
    const picker = screen.getByTestId('chat-picker')
    expect(screen.getByRole('heading', { name: 'העברת התמונה לשיחה' })).toBeInTheDocument()
    const rows = screen.getAllByRole('radio')
    expect(rows.map((r) => r.getAttribute('aria-label'))).toEqual(['שיחה חדשה', 'חדשה', 'ישנה'])
    // Each chat's row is the history page's: title, last message, number of messages
    expect(rows[2]).toHaveTextContent('ישנה')
    expect(rows[2]).toHaveTextContent('x')

    // A click only selects; the confirm button is there once a row is selected
    expect(screen.queryByRole('button', { name: 'אישור' })).toBeNull()
    fireEvent.click(screen.getByRole('radio', { name: 'חדשה' }))
    expect(screen.getByRole('radio', { name: 'חדשה' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('button', { name: 'אישור' })).toBeInTheDocument()
    expect(screen.getByTestId('where')).toHaveTextContent('/chat/current')
    // One row at a time
    fireEvent.click(screen.getByRole('radio', { name: 'ישנה' }))
    expect(screen.getByRole('radio', { name: 'חדשה' })).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByRole('radio', { name: 'ישנה' })).toHaveAttribute('aria-checked', 'true')
    // A second click on the selected row clears it
    fireEvent.click(screen.getByRole('radio', { name: 'ישנה' }))
    expect(screen.getByRole('radio', { name: 'ישנה' })).toHaveAttribute('aria-checked', 'false')
    expect(screen.queryByRole('button', { name: 'אישור' })).toBeNull()
    expect(picker).toBeInTheDocument()

    fireEvent.click(screen.getByRole('radio', { name: 'ישנה' }))
    fireEvent.click(screen.getByRole('button', { name: 'אישור' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/chat/old'))
    expect(screen.queryByTestId('image-viewer')).toBeNull()

    const forwarded = peekForwardedFile('old')!
    expect(forwarded.name).toBe('cat.png')
    expect(forwarded.type).toBe('image/png')
    expect(peekForwardedFile('new')).toBeNull()
    clearForwardedFile(forwarded)
    expect(peekForwardedFile('old')).toBeNull()
  })

  it('forwards to a new chat', async () => {
    vi.mocked(clientModule.chats.create).mockResolvedValue(chat('made', 'שיחה חדשה', '') as never)
    vi.mocked(clientModule.chats.list).mockResolvedValue({ user_name: 'u', groups: [], chat_history: [] } as never)
    renderApp()
    fireEvent.click(screen.getByRole('link', { name: /פתיחת התמונה/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'אפשרויות' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'העברה לשיחה אחרת' }))
    expect(screen.queryByRole('menu')).toBeNull()
    fireEvent.click(screen.getByRole('radio', { name: 'שיחה חדשה' }))
    expect(clientModule.chats.create).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'אישור' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/chat/made'))
    const forwarded = peekForwardedFile('made')!
    expect(forwarded.name).toBe('cat.png')
    clearForwardedFile(forwarded)
  })

  it('the picker\'s back arrow and Escape return to the image without forwarding', async () => {
    renderApp()
    fireEvent.click(screen.getByRole('link', { name: /פתיחת התמונה/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'אפשרויות' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'העברה לשיחה אחרת' }))
    fireEvent.click(screen.getByRole('radio', { name: 'ישנה' }))
    fireEvent.click(screen.getByRole('button', { name: 'Back' }))
    expect(screen.queryByTestId('chat-picker')).toBeNull()
    expect(screen.getByTestId('image-viewer')).toBeInTheDocument()

    // Opened again, nothing is selected; Escape closes the picker only
    fireEvent.click(screen.getByRole('button', { name: 'אפשרויות' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'העברה לשיחה אחרת' }))
    expect(screen.getByRole('radio', { name: 'ישנה' })).toHaveAttribute('aria-checked', 'false')
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByTestId('chat-picker')).toBeNull()
    expect(screen.getByTestId('image-viewer')).toBeInTheDocument()
    expect(screen.getByTestId('where')).toHaveTextContent('/chat/current')
  })
})
