/**
 * The composer's attachments (upload on attach, the upload states, removal → delete, the size
 * limit, leaving the chat) and the project files of a group.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act, within } from '@testing-library/react'
import { MemoryRouter, Routes, Route, useNavigate } from 'react-router-dom'
import ChatPage from '../pages/ChatPage'
import GroupPage from '../pages/GroupPage'
import * as chatStoreModule from '../stores/chatStore'
import { useRunsStore } from '../stores/runsStore'
import { resetUsedAttachments } from '../hooks/useDraftAttachments'
import { MAX_FILE_BYTES } from '../utils/attachments'
import type { Attachment, Chat, ChatGroup } from '../api/types'

vi.mock('../pages/ChatPage.module.css', () => ({ default: new Proxy({}, { get: (_t, prop) => String(prop) }) }))
vi.mock('../pages/GroupPage.module.css', () => ({ default: new Proxy({}, { get: (_t, prop) => String(prop) }) }))
vi.mock('../components/Markdown.module.css', () => ({ default: new Proxy({}, { get: (_t, prop) => String(prop) }) }))

vi.mock('../api/stream', async () => ({
  sendStream: vi.fn(() => ({ abort: vi.fn(), done: Promise.resolve() })),
  resendStream: vi.fn(() => ({ abort: vi.fn(), done: Promise.resolve() })),
  parseSseChunk: vi.fn(),
}))

vi.mock('../api/runs', async () => ({
  cancelRun: vi.fn(() => Promise.resolve({ runId: 'r', status: 'running' })),
  connectRunsStream: vi.fn(() => ({ close: vi.fn() })),
}))

vi.mock('../api/client', async () => ({
  providers: {
    models: () => Promise.resolve([]),
    list: () => Promise.resolve([]),
    detailed: () => Promise.resolve([]),
    refresh: () => Promise.resolve({ ok: true, changed: false }),
  },
  branching: {
    create: vi.fn().mockResolvedValue({ chat: {}, newVariantId: 'nv1' }),
    switchVariant: vi.fn().mockResolvedValue({}),
    info: () => Promise.resolve({}),
  },
  files: {
    upload: vi.fn(),
    delete: vi.fn(() => Promise.resolve({ deleted: true })),
    commit: vi.fn(),
  },
  messages: {
    delete: vi.fn().mockResolvedValue({}),
    add: vi.fn().mockResolvedValue({}),
  },
  chats: {
    list: vi.fn(() => Promise.resolve({ user_name: 'u', chat_history: [], groups: [] })),
    get: () => Promise.resolve(null),
    create: vi.fn().mockResolvedValue({}),
    update: vi.fn().mockResolvedValue({}),
    delete: vi.fn().mockResolvedValue(undefined),
  },
  apiKeys: { list: () => Promise.resolve([]) },
  settings: {
    get: vi.fn(() => Promise.resolve({
      current_user: 'u', selected_provider: 'openai', selected_model: 'gpt-4o',
      multiMessageMode: false, enabledTools: [], excludedToolIds: [], starredModels: [],
    })),
    update: vi.fn().mockResolvedValue({}),
  },
  groups: {
    list: vi.fn(() => Promise.resolve([])),
    update: vi.fn(),
    addAttachment: vi.fn(),
    removeAttachment: vi.fn(),
  },
}))

import * as streamModule from '../api/stream'
import * as clientModule from '../api/client'

const mUpload = vi.mocked(clientModule.files.upload)
const mDelete = vi.mocked(clientModule.files.delete)

const SHA_A = 'a'.repeat(64)
const SHA_B = 'b'.repeat(64)

const SAMPLE_CHAT: Chat = {
  chat_id: 'c1',
  preview_name: 'Test Chat',
  messages: [
    { id: 'm1', role: 'user', text: 'Hello', attachments: [], thoughtsStatus: 'NONE' },
  ],
  systemPrompt: '',
  group: null,
  messageNodes: [],
  currentVariantPath: [],
  shareLink: '',
  shareId: '',
}

/** An upload the test finishes by hand. */
interface PendingUpload {
  file: File
  signal?: AbortSignal
  resolve: (att: Attachment) => void
  reject: (err: unknown) => void
}

let uploads: PendingUpload[] = []

function stored(file: File, sha: string): Attachment {
  return { file_name: file.name, mime_type: file.type || 'application/octet-stream', sha256: sha, size: file.size }
}

function apiError(status: number, code: string) {
  return Object.assign(new Error(`HTTP ${status}`), { status, body: { error: code, code } })
}

function abortError() {
  return Object.assign(new Error('aborted'), { name: 'AbortError' })
}

let goTo: (path: string) => void = () => {}
const Nav: React.FC = () => {
  goTo = useNavigate()
  return null
}

function renderChatPage() {
  return render(
    <MemoryRouter initialEntries={['/chat/c1']}>
      <Nav />
      <Routes>
        <Route path="/chat/:id" element={<ChatPage />} />
        <Route path="/" element={<div data-testid="home">Home</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

function attach(...picked: File[]) {
  fireEvent.change(screen.getByLabelText('Attach file'), { target: { files: picked } })
}

const textFile = (name = 'notes.txt', content = 'hello') => new File([content], name, { type: 'text/plain' })
const drafts = () => screen.queryAllByTestId('draft-attachment')
const sendButton = () => screen.getByRole('button', { name: 'Send message' })
const typeText = (text: string) =>
  fireEvent.change(screen.getByPlaceholderText('הקלד הודעה...'), { target: { value: text } })

beforeEach(() => {
  vi.clearAllMocks()
  uploads = []
  resetUsedAttachments()
  useRunsStore.getState().reset()
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
  mUpload.mockImplementation((file: File, options?: { signal?: AbortSignal }) =>
    new Promise<Attachment>((resolve, reject) => {
      uploads.push({ file, signal: options?.signal, resolve, reject })
    }))
  mDelete.mockImplementation(() => Promise.resolve({ deleted: true }))
  vi.mocked(streamModule.sendStream).mockReturnValue({ abort: vi.fn(), done: Promise.resolve() })
})

// ─── Composer: upload states ──────────────────────────────────────────────────

describe('ChatPage — attaching files', () => {
  it('starts uploading at once and shows the attachment greyed with a spinner; sending waits', async () => {
    renderChatPage()
    typeText('look at this')
    expect(sendButton()).not.toBeDisabled()

    attach(textFile())

    expect(mUpload).toHaveBeenCalledTimes(1)
    expect(uploads[0].file.name).toBe('notes.txt')
    const chip = drafts()[0]
    expect(chip).toHaveAttribute('data-upload-status', 'uploading')
    expect(chip).toHaveAttribute('aria-busy', 'true')
    expect(within(chip).getByRole('progressbar')).toBeInTheDocument()
    expect(within(chip).getByText('notes.txt')).toBeInTheDocument()

    // Not possible to send while the file is on its way — by button or by Enter
    expect(sendButton()).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('ממתין לסיום העלאת הקבצים')
    fireEvent.keyDown(screen.getByPlaceholderText('הקלד הודעה...'), { key: 'Enter' })
    expect(streamModule.sendStream).not.toHaveBeenCalled()

    await act(async () => { uploads[0].resolve(stored(uploads[0].file, SHA_A)) })

    expect(drafts()[0]).toHaveAttribute('data-upload-status', 'ready')
    expect(within(drafts()[0]).queryByRole('progressbar')).toBeNull()
    expect(sendButton()).not.toBeDisabled()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('sends the uploaded Attachment as the server returned it, and does not delete it afterwards', async () => {
    const { unmount } = renderChatPage()
    typeText('with file')
    attach(textFile())
    await act(async () => { uploads[0].resolve(stored(uploads[0].file, SHA_A)) })

    fireEvent.click(sendButton())

    expect(streamModule.sendStream).toHaveBeenCalledTimes(1)
    const req = vi.mocked(streamModule.sendStream).mock.calls[0][0]
    expect(req.messages[req.messages.length - 1]).toEqual({
      role: 'user',
      text: 'with file',
      attachments: [{ file_name: 'notes.txt', mime_type: 'text/plain', sha256: SHA_A, size: 5 }],
    })
    // The draft is empty again; the file now belongs to the message
    expect(drafts()).toHaveLength(0)
    unmount()
    expect(mDelete).not.toHaveBeenCalled()
  })

  it('shows a failed upload as failed (store unavailable) with retry — not an endless spinner', async () => {
    renderChatPage()
    typeText('x')
    attach(textFile())
    await act(async () => { uploads[0].reject(apiError(502, 'store_unavailable')) })

    const chip = drafts()[0]
    expect(chip).toHaveAttribute('data-upload-status', 'failed')
    expect(within(chip).queryByRole('progressbar')).toBeNull()
    expect(within(chip).getByRole('alert')).toHaveTextContent('אחסון הקבצים אינו זמין כרגע')
    // A message must not go out without its file
    expect(sendButton()).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('נסו שוב או הסירו')

    fireEvent.click(within(chip).getByRole('button', { name: 'נסה שוב' }))
    expect(mUpload).toHaveBeenCalledTimes(2)
    expect(uploads[1].file).toBe(uploads[0].file)
    expect(drafts()[0]).toHaveAttribute('data-upload-status', 'uploading')

    await act(async () => { uploads[1].resolve(stored(uploads[1].file, SHA_A)) })
    expect(drafts()[0]).toHaveAttribute('data-upload-status', 'ready')
    expect(sendButton()).not.toBeDisabled()
    expect(mDelete).not.toHaveBeenCalled()
  })

  it('an upload refused as too large by the server (413) fails without a retry; removing it unblocks sending', async () => {
    renderChatPage()
    typeText('x')
    attach(textFile())
    await act(async () => { uploads[0].reject(apiError(413, 'file_too_large')) })

    const chip = drafts()[0]
    expect(chip).toHaveAttribute('data-upload-status', 'failed')
    expect(within(chip).getByRole('alert')).toHaveTextContent('95MB')
    expect(within(chip).queryByRole('button', { name: 'נסה שוב' })).toBeNull()

    fireEvent.click(within(chip).getByRole('button', { name: 'Remove attachment' }))
    expect(drafts()).toHaveLength(0)
    expect(sendButton()).not.toBeDisabled()
    // Nothing was stored, so there is nothing to delete
    expect(mDelete).not.toHaveBeenCalled()
  })

  it('an expired session fails the upload with its own message', async () => {
    renderChatPage()
    attach(textFile())
    await act(async () => { uploads[0].reject(apiError(401, 'unauthorized')) })
    expect(within(drafts()[0]).getByRole('alert')).toHaveTextContent('פג תוקף ההתחברות')
  })

  it('refuses a file over 95 MB before any request, with a clear message', () => {
    renderChatPage()
    const big = textFile('movie.mp4')
    Object.defineProperty(big, 'size', { value: MAX_FILE_BYTES + 1 })

    attach(big)

    expect(mUpload).not.toHaveBeenCalled()
    expect(drafts()).toHaveLength(0)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('movie.mp4')
    expect(alert).toHaveTextContent('95MB')
  })

  it('uploads several picked files in parallel; a refused one does not stop the others', () => {
    renderChatPage()
    const big = textFile('big.bin')
    Object.defineProperty(big, 'size', { value: MAX_FILE_BYTES + 1 })

    attach(textFile('a.txt'), big, textFile('b.txt'))

    expect(uploads.map((u) => u.file.name)).toEqual(['a.txt', 'b.txt'])
    expect(drafts()).toHaveLength(2)
    expect(screen.getByRole('alert')).toHaveTextContent('big.bin')
  })
})

// ─── Composer: removal ────────────────────────────────────────────────────────

describe('ChatPage — removing un-sent attachments', () => {
  const removeButton = (i: number) => within(drafts()[i]).getByRole('button', { name: 'Remove attachment' })

  it('deletes an uploaded file from the server as soon as it is removed', async () => {
    renderChatPage()
    attach(textFile())
    await act(async () => { uploads[0].resolve(stored(uploads[0].file, SHA_A)) })

    fireEvent.click(removeButton(0))

    expect(drafts()).toHaveLength(0)
    expect(mDelete).toHaveBeenCalledTimes(1)
    expect(mDelete.mock.calls[0][0]).toBe(SHA_A)
  })

  it('attach / remove / attach / remove leaves nothing behind', async () => {
    renderChatPage()
    for (let round = 0; round < 2; round++) {
      attach(textFile())
      await act(async () => { uploads[round].resolve(stored(uploads[round].file, SHA_A)) })
      fireEvent.click(removeButton(0))
    }
    expect(mUpload).toHaveBeenCalledTimes(2)
    expect(mDelete.mock.calls.map((c) => c[0])).toEqual([SHA_A, SHA_A])
    expect(drafts()).toHaveLength(0)
  })

  it('removal during the upload aborts the request; nothing to delete when it never completed', async () => {
    renderChatPage()
    attach(textFile())
    expect(uploads[0].signal?.aborted).toBe(false)

    fireEvent.click(removeButton(0))

    expect(drafts()).toHaveLength(0)
    expect(uploads[0].signal?.aborted).toBe(true)
    await act(async () => { uploads[0].reject(abortError()) })
    expect(mDelete).not.toHaveBeenCalled()
    expect(drafts()).toHaveLength(0)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('removal during the upload: when the upload completed anyway, the file is deleted once its hash is known', async () => {
    renderChatPage()
    attach(textFile())
    fireEvent.click(removeButton(0))
    expect(mDelete).not.toHaveBeenCalled()

    await act(async () => { uploads[0].resolve(stored(uploads[0].file, SHA_A)) })

    expect(mDelete).toHaveBeenCalledTimes(1)
    expect(mDelete.mock.calls[0][0]).toBe(SHA_A)
    expect(drafts()).toHaveLength(0)
  })

  it('the same file attached twice: the stored copy is deleted only with the last of them', async () => {
    renderChatPage()
    attach(textFile())
    attach(textFile())
    await act(async () => {
      uploads[0].resolve(stored(uploads[0].file, SHA_A))
      uploads[1].resolve(stored(uploads[1].file, SHA_A))
    })
    expect(drafts()).toHaveLength(2)

    fireEvent.click(removeButton(0))
    expect(drafts()).toHaveLength(1)
    expect(mDelete).not.toHaveBeenCalled()

    fireEvent.click(removeButton(0))
    expect(mDelete).toHaveBeenCalledTimes(1)
    expect(mDelete.mock.calls[0][0]).toBe(SHA_A)
  })

  it('a file that was sent in a message is not deleted when the same file is attached again and removed', async () => {
    renderChatPage()
    typeText('first')
    attach(textFile())
    await act(async () => { uploads[0].resolve(stored(uploads[0].file, SHA_A)) })
    fireEvent.click(sendButton())

    attach(textFile())
    await act(async () => { uploads[1].resolve(stored(uploads[1].file, SHA_A)) })
    fireEvent.click(removeButton(0))

    expect(drafts()).toHaveLength(0)
    expect(mDelete).not.toHaveBeenCalled()
  })

  it('leaving the chat with un-sent attachments deletes them (and stops running uploads)', async () => {
    renderChatPage()
    attach(textFile('done.txt'), textFile('twin.txt'), textFile('running.txt', 'much longer content'))
    await act(async () => {
      uploads[0].resolve(stored(uploads[0].file, SHA_A))
      uploads[1].resolve(stored(uploads[1].file, SHA_A))
    })

    act(() => goTo('/'))

    expect(screen.getByTestId('home')).toBeInTheDocument()
    // One delete per stored file (the two entries share a hash)
    expect(mDelete).toHaveBeenCalledTimes(1)
    expect(mDelete.mock.calls[0][0]).toBe(SHA_A)
    expect(uploads[2].signal?.aborted).toBe(true)

    // The upload that was still running got through after all: deleted too
    await act(async () => { uploads[2].resolve(stored(uploads[2].file, SHA_B)) })
    expect(mDelete.mock.calls.map((c) => c[0])).toEqual([SHA_A, SHA_B])
  })

  it('closing / reloading the tab deletes un-sent uploads with a keepalive request', async () => {
    renderChatPage()
    attach(textFile())
    await act(async () => { uploads[0].resolve(stored(uploads[0].file, SHA_A)) })

    act(() => { window.dispatchEvent(new Event('pagehide')) })

    expect(mDelete).toHaveBeenCalledTimes(1)
    expect(mDelete.mock.calls[0]).toEqual([SHA_A, { keepalive: true }])
  })

  it('a failed delete is not shown to the user (the store sweeps what is left)', async () => {
    mDelete.mockImplementation(() => Promise.reject(apiError(502, 'store_unavailable')))
    renderChatPage()
    attach(textFile())
    await act(async () => { uploads[0].resolve(stored(uploads[0].file, SHA_A)) })
    await act(async () => { fireEvent.click(removeButton(0)) })
    expect(drafts()).toHaveLength(0)
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

// ─── Project files of a group ─────────────────────────────────────────────────

describe('GroupPage — project files', () => {
  const group = (attachments: Attachment[]): ChatGroup => ({
    group_id: 'g1', group_name: 'Project', is_project: true, system_prompt: null, group_attachments: attachments,
  } as ChatGroup)

  const mGroupsList = vi.mocked(clientModule.groups.list)
  const mAddAttachment = vi.mocked(clientModule.groups.addAttachment)

  function renderGroup() {
    return render(
      <MemoryRouter initialEntries={['/groups/g1']}>
        <Routes>
          <Route path="/groups/:id" element={<GroupPage />} />
        </Routes>
      </MemoryRouter>,
    )
  }
  const tiles = () => screen.queryAllByTestId('project-file')
  const pick = (...picked: File[]) =>
    fireEvent.change(screen.getByLabelText('הוסף קובץ', { selector: 'input' }), { target: { files: picked } })

  it('renders files by hash (image shown, other files downloadable) and legacy files as device-only', async () => {
    mGroupsList.mockResolvedValue([group([
      { file_name: 'logo.png', mime_type: 'image/png', sha256: SHA_A, size: 10 },
      { file_name: 'spec.pdf', mime_type: 'application/pdf', sha256: SHA_B, size: 20 },
      { file_name: 'old.docx', mime_type: 'application/msword', local_file_path: '/data/old.docx' },
    ])])
    renderGroup()
    await waitFor(() => expect(tiles()).toHaveLength(3))

    expect(tiles().map((el) => el.getAttribute('data-attachment-state'))).toEqual(['image', 'file', 'legacy'])
    expect(screen.getByRole('img', { name: 'logo.png' }).getAttribute('src')).toContain(`/api/files/${SHA_A}`)
    const download = screen.getByRole('link', { name: /הורדה: spec\.pdf/ })
    expect(download.getAttribute('href')).toContain(`/api/files/${SHA_B}`)
    expect(download.getAttribute('href')).toContain('download=1')
    expect(within(tiles()[2]).getByText('זמין רק במכשיר שממנו צורף')).toBeInTheDocument()
    expect(within(tiles()[2]).queryByRole('link')).toBeNull()
  })

  it('shows a file as uploading, then adds the returned Attachment to the group', async () => {
    mGroupsList.mockResolvedValue([group([])])
    renderGroup()
    await waitFor(() => expect(screen.getByText('Project')).toBeInTheDocument())

    pick(textFile('brief.txt'))
    await waitFor(() => expect(tiles()).toHaveLength(1))
    expect(tiles()[0]).toHaveAttribute('data-attachment-state', 'uploading')
    expect(within(tiles()[0]).getByRole('progressbar')).toBeInTheDocument()

    const att = stored(uploads[0].file, SHA_A)
    mAddAttachment.mockResolvedValue(group([att]))
    await act(async () => { uploads[0].resolve(att) })

    expect(mAddAttachment).toHaveBeenCalledWith('g1', att)
    await waitFor(() => expect(tiles()[0]).toHaveAttribute('data-attachment-state', 'file'))
    expect(tiles()).toHaveLength(1)
    expect(mDelete).not.toHaveBeenCalled()
  })

  it('refuses a file over the limit, reports a failed upload, and deletes an upload the group refused', async () => {
    mGroupsList.mockResolvedValue([group([])])
    renderGroup()
    await waitFor(() => expect(screen.getByText('Project')).toBeInTheDocument())

    const big = textFile('huge.zip')
    Object.defineProperty(big, 'size', { value: MAX_FILE_BYTES + 1 })
    pick(big)
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('95MB'))
    expect(mUpload).not.toHaveBeenCalled()

    pick(textFile('a.txt'))
    await waitFor(() => expect(uploads).toHaveLength(1))
    await act(async () => { uploads[0].reject(apiError(502, 'store_unavailable')) })
    expect(screen.getByRole('alert')).toHaveTextContent('אחסון הקבצים אינו זמין כרגע')
    expect(tiles()).toHaveLength(0)

    mAddAttachment.mockRejectedValue(apiError(404, 'Group not found'))
    pick(textFile('b.txt'))
    await waitFor(() => expect(uploads).toHaveLength(2))
    await act(async () => { uploads[1].resolve(stored(uploads[1].file, SHA_B)) })
    await waitFor(() => expect(mDelete).toHaveBeenCalledTimes(1))
    expect(mDelete.mock.calls[0][0]).toBe(SHA_B)
    expect(tiles()).toHaveLength(0)
  })
})
