/**
 * Attachment store, web side (CLOUD_RUNS_PLAN.md, "Web file routes"): the file routes' client,
 * the URL / kind rules, and how a saved attachment is rendered — by hash (image, download) vs a
 * legacy attachment without one.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import AttachmentView from '../components/chat/AttachmentView'
import MessageBubble from '../components/chat/MessageBubble'
import { files, ApiError, onUnauthenticated } from '../api/client'
import {
  MAX_FILE_BYTES,
  attachmentKind,
  attachmentUrl,
  describeUploadError,
  fileRejection,
  formatFileSize,
  LEGACY_FILE_ONLY_TEXTS,
  messageDisplayText,
  messagePreviewText,
} from '../utils/attachments'
import type { Attachment, Chat, Message } from '../api/types'

vi.mock('../components/Markdown.module.css', () => ({
  default: new Proxy({}, { get: (_t, prop) => String(prop) }),
}))

const SHA = 'a'.repeat(64)
const SHA2 = 'b'.repeat(64)

const image: Attachment = { file_name: 'תמונה 1.png', mime_type: 'image/png', sha256: SHA, size: 2048 }
const pdf: Attachment = { file_name: 'report.pdf', mime_type: 'application/pdf', sha256: SHA2, size: 5 * 1024 * 1024 }
const legacy: Attachment = { file_name: 'old.jpg', mime_type: 'image/jpeg', local_file_path: '/data/user/0/app/files/old.jpg' }

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

// ─── URL and kind rules ───────────────────────────────────────────────────────

describe('attachment utils', () => {
  it('builds the file route URL from the hash, with name and mime as query parameters', () => {
    const url = new URL(attachmentUrl(image)!, 'http://x')
    expect(url.pathname).toBe(`/api/files/${SHA}`)
    expect(url.searchParams.get('name')).toBe('תמונה 1.png')
    expect(url.searchParams.get('mime')).toBe('image/png')
    expect(url.searchParams.has('download')).toBe(false)

    const download = new URL(attachmentUrl(pdf, true)!, 'http://x')
    expect(download.searchParams.get('download')).toBe('1')
    expect(download.searchParams.get('mime')).toBe('application/pdf')
  })

  it('has no URL for an attachment without a (valid) hash', () => {
    expect(attachmentUrl(legacy)).toBeNull()
    expect(attachmentUrl({ ...image, sha256: null })).toBeNull()
    expect(attachmentUrl({ ...image, sha256: '../../etc/passwd' })).toBeNull()
    expect(attachmentUrl({ ...image, sha256: SHA.toUpperCase() })).toBeNull()
  })

  it('shows only the safe image types as images; SVG / HTML are downloads', () => {
    expect(attachmentKind(image)).toBe('image')
    expect(attachmentKind({ ...image, mime_type: 'IMAGE/JPEG; charset=x' })).toBe('image')
    expect(attachmentKind({ ...image, mime_type: 'image/svg+xml' })).toBe('file')
    expect(attachmentKind({ ...image, mime_type: 'text/html' })).toBe('file')
    expect(attachmentKind(pdf)).toBe('file')
    expect(attachmentKind(legacy)).toBe('legacy')
  })

  it('refuses a file over 95 MB, and an empty one, before any request', () => {
    const ok = new File(['x'], 'a.txt')
    const big = new File(['x'], 'big.bin')
    Object.defineProperty(big, 'size', { value: MAX_FILE_BYTES + 1 })
    const atLimit = new File(['x'], 'limit.bin')
    Object.defineProperty(atLimit, 'size', { value: MAX_FILE_BYTES })
    expect(MAX_FILE_BYTES).toBe(95 * 1024 * 1024)
    expect(fileRejection(ok)).toBeNull()
    expect(fileRejection(atLimit)).toBeNull()
    expect(fileRejection(big)).toContain('95MB')
    expect(fileRejection(new File([], 'empty.txt'))).toBeTruthy()
  })

  it('describes upload failures: too large and empty are final, the rest can be retried', () => {
    const tooLarge = describeUploadError(new ApiError(413, { error: 'x', code: 'file_too_large', maxBytes: 1 }))
    expect(tooLarge.retryable).toBe(false)
    expect(tooLarge.message).toContain('95MB')
    expect(describeUploadError(new ApiError(400, { error: 'x', code: 'no_file' })).retryable).toBe(false)

    const store = describeUploadError(new ApiError(502, { error: 'x', code: 'store_unavailable' }))
    expect(store.retryable).toBe(true)
    expect(store.message).toContain('אחסון')
    expect(describeUploadError(new ApiError(401, null)).message).toContain('התחבר')
    expect(describeUploadError(new TypeError('Failed to fetch')).retryable).toBe(true)
  })

  it('formats sizes', () => {
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize(2048)).toBe('2.0 KB')
    expect(formatFileSize(5 * 1024 * 1024)).toBe('5.0 MB')
    expect(formatFileSize(null)).toBe('')
  })
})

// ─── The file routes' client ──────────────────────────────────────────────────

describe('files API client', () => {
  const fetchMock = vi.fn()
  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })
  afterEach(() => { vi.unstubAllGlobals() })

  it('uploads the file as the multipart part `file` and returns the Attachment with its hash', async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, { file_name: 'a.txt', mime_type: 'text/plain', sha256: SHA, size: 1 }))
    const controller = new AbortController()
    const file = new File(['x'], 'a.txt', { type: 'text/plain' })

    const att = await files.upload(file, { signal: controller.signal })

    expect(att).toEqual({ file_name: 'a.txt', mime_type: 'text/plain', sha256: SHA, size: 1 })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/files/upload')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('include')
    expect(init.signal).toBe(controller.signal)
    expect((init.body as FormData).get('file')).toBe(file)
    // The browser sets the multipart boundary itself
    expect(init.headers).toBeUndefined()
  })

  it('rejects with the server error body (413 file_too_large, 502 store_unavailable)', async () => {
    fetchMock.mockResolvedValue(jsonResponse(413, { error: 'too big', code: 'file_too_large', maxBytes: 99614720 }))
    await expect(files.upload(new File(['x'], 'a'))).rejects.toMatchObject({
      status: 413, body: { code: 'file_too_large' },
    })
    fetchMock.mockResolvedValue(jsonResponse(502, { error: 'down', code: 'store_unavailable' }))
    await expect(files.upload(new File(['x'], 'a'))).rejects.toMatchObject({
      status: 502, body: { code: 'store_unavailable' },
    })
  })

  it('an upload answered 401 announces the expired session', async () => {
    const onExpired = vi.fn()
    const off = onUnauthenticated(onExpired)
    fetchMock.mockResolvedValue(jsonResponse(401, { error: 'unauthorized' }))
    await expect(files.upload(new File(['x'], 'a'))).rejects.toMatchObject({ status: 401 })
    expect(onExpired).toHaveBeenCalledTimes(1)
    off()
  })

  it('deletes a pending upload by hash; commit posts to the hash', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { deleted: true }))
    await expect(files.delete(SHA, { keepalive: true })).resolves.toEqual({ deleted: true })
    let [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`/api/files/${SHA}`)
    expect(init.method).toBe('DELETE')
    expect(init.keepalive).toBe(true)
    expect(init.body).toBeUndefined()

    fetchMock.mockResolvedValue(jsonResponse(200, { sha256: SHA, size: 1, state: 'committed' }))
    await files.commit(SHA)
    ;[url, init] = fetchMock.mock.calls[1]
    expect(url).toBe(`/api/files/${SHA}/commit`)
    expect(init.method).toBe('POST')
  })
})

// ─── Rendering ────────────────────────────────────────────────────────────────

describe('AttachmentView', () => {
  it('an image with a hash is shown through the file route, with open and download links', () => {
    render(<AttachmentView att={image} />)
    const view = screen.getByTestId('attachment')
    expect(view).toHaveAttribute('data-attachment-state', 'image')

    const img = screen.getByRole('img', { name: 'תמונה 1.png' })
    const src = new URL(img.getAttribute('src')!, 'http://x')
    expect(src.pathname).toBe(`/api/files/${SHA}`)
    expect(src.searchParams.get('mime')).toBe('image/png')
    expect(src.searchParams.has('download')).toBe(false)

    const open = screen.getByRole('link', { name: /פתיחת התמונה/ })
    expect(open).toHaveAttribute('target', '_blank')
    expect(open.getAttribute('rel')).toContain('noopener')
    const download = screen.getByRole('link', { name: /הורדה/ })
    expect(download.getAttribute('href')).toContain('download=1')
    expect(download).toHaveAttribute('download', 'תמונה 1.png')
  })

  it('any other file with a hash is a download link, never rendered in the page', () => {
    render(<AttachmentView att={pdf} />)
    expect(screen.getByTestId('attachment')).toHaveAttribute('data-attachment-state', 'file')
    expect(screen.queryByRole('img')).toBeNull()
    const link = screen.getByRole('link', { name: /הורדה: report\.pdf/ })
    const href = new URL(link.getAttribute('href')!, 'http://x')
    expect(href.pathname).toBe(`/api/files/${SHA2}`)
    expect(href.searchParams.get('download')).toBe('1')
    expect(href.searchParams.get('name')).toBe('report.pdf')
    expect(link).toHaveAttribute('download', 'report.pdf')
    expect(link).not.toHaveAttribute('target')
    expect(screen.getByText('5.0 MB')).toBeInTheDocument()
  })

  it('SVG and HTML files are downloads even though a browser could render them', () => {
    const { container } = render(
      <>
        <AttachmentView att={{ file_name: 'x.svg', mime_type: 'image/svg+xml', sha256: SHA }} />
        <AttachmentView att={{ file_name: 'x.html', mime_type: 'text/html', sha256: SHA2 }} />
      </>,
    )
    expect(container.querySelector('img, iframe, object, embed')).toBeNull()
    for (const link of screen.getAllByRole('link')) {
      expect(link.getAttribute('href')).toContain('download=1')
      expect(link).toHaveAttribute('download')
    }
  })

  it('a legacy attachment (no hash) shows its name and the device-only hint — no image, no link', () => {
    render(<AttachmentView att={legacy} />)
    expect(screen.getByTestId('attachment')).toHaveAttribute('data-attachment-state', 'legacy')
    expect(screen.getByText('old.jpg')).toBeInTheDocument()
    expect(screen.getByText('זמין רק במכשיר שממנו צורף')).toBeInTheDocument()
    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('an image the store cannot serve falls back to its name instead of a broken image', () => {
    render(<AttachmentView att={image} />)
    fireEvent.error(screen.getByRole('img'))
    expect(screen.getByTestId('attachment')).toHaveAttribute('data-attachment-state', 'unavailable')
    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.getByText('הקובץ אינו זמין כרגע')).toBeInTheDocument()
  })

  describe('lazy loading', () => {
    let observed: { callback: IntersectionObserverCallback; el: Element }[] = []

    beforeEach(() => {
      observed = []
      class FakeObserver {
        constructor(private readonly callback: IntersectionObserverCallback) {}
        observe(el: Element) { observed.push({ callback: this.callback, el }) }
        disconnect() {}
        unobserve() {}
      }
      vi.stubGlobal('IntersectionObserver', FakeObserver)
    })
    afterEach(() => { vi.unstubAllGlobals() })

    it('requests the image only once it comes into view', () => {
      render(<AttachmentView att={image} />)
      expect(screen.queryByRole('img')).toBeNull()
      expect(observed).toHaveLength(1)

      act(() => {
        observed[0].callback(
          [{ isIntersecting: false } as IntersectionObserverEntry],
          {} as IntersectionObserver,
        )
      })
      expect(screen.queryByRole('img')).toBeNull()

      act(() => {
        observed[0].callback(
          [{ isIntersecting: true } as IntersectionObserverEntry],
          {} as IntersectionObserver,
        )
      })
      expect(screen.getByRole('img').getAttribute('src')).toContain(`/api/files/${SHA}`)
    })
  })
})

// ─── In messages ──────────────────────────────────────────────────────────────

describe('MessageBubble attachments', () => {
  const chat: Chat = {
    chat_id: 'c1', preview_name: 'T', messages: [], systemPrompt: '', group: null,
    messageNodes: [], currentVariantPath: [], shareLink: '', shareId: '',
  }
  const noop = () => {}
  const bubble = (msg: Message) => (
    <MessageBubble
      msg={msg}
      chat={chat}
      textDirectionMode="AUTO"
      onEdit={noop}
      onCopy={noop}
      onDelete={noop}
      onRegenerate={noop}
      onBranchSwitch={noop}
    />
  )

  it("shows a user message's attachments: image, file and legacy side by side", () => {
    render(bubble({
      id: 'm1', role: 'user', text: 'see these', attachments: [image, pdf, legacy], thoughtsStatus: 'NONE',
    }))
    const states = screen.getAllByTestId('attachment').map((el) => el.getAttribute('data-attachment-state'))
    expect(states).toEqual(['image', 'file', 'legacy'])
  })

  const chart: Attachment = { file_name: 'chart.png', mime_type: 'image/png', sha256: SHA, size: 100 }
  const csv: Attachment = { file_name: 'data.csv', mime_type: 'text/csv', sha256: SHA2, size: 200 }

  // A message that only carries files: stored without a text now, with a stand-in by older versions
  for (const text of ['', '[קובץ מצורף]', ' [קובץ מצורף]\n', '[Attached file]']) {
    it(`shows the files of an assistant message and no text for them (stored text ${JSON.stringify(text)})`, () => {
      const { container } = render(bubble({
        id: 'm2', role: 'assistant', text, attachments: [chart, csv],
        model: 'gpt-4o', thoughtsStatus: 'NONE',
      }))
      expect(screen.getByRole('img', { name: 'chart.png' }).getAttribute('src')).toContain(`/api/files/${SHA}`)
      const csvLink = screen.getByRole('link', { name: /הורדה: data\.csv/ })
      expect(csvLink.getAttribute('href')).toContain(`/api/files/${SHA2}`)
      expect(csvLink.getAttribute('href')).toContain('download=1')
      expect(screen.queryByTestId('message-text')).toBeNull()
      expect(container.textContent).not.toContain('קובץ מצורף')
      expect(container.textContent).not.toContain('Attached file')
    })

    it(`shows the files of a user message and no text for them (stored text ${JSON.stringify(text)})`, () => {
      const { container } = render(bubble({ id: 'm3', role: 'user', text, attachments: [csv], thoughtsStatus: 'NONE' }))
      expect(screen.getAllByTestId('attachment')).toHaveLength(1)
      expect(screen.queryByTestId('message-text')).toBeNull()
      expect(container.textContent).not.toContain('קובץ מצורף')
    })
  }

  it('keeps a text that follows generated files as it is', () => {
    render(bubble({
      id: 'm4', role: 'assistant', text: 'Here is the chart', attachments: [chart], model: 'gpt-4o', thoughtsStatus: 'NONE',
    }))
    expect(screen.getByTestId('message-text').textContent).toBe('Here is the chart')
    expect(screen.getAllByTestId('attachment')).toHaveLength(1)
  })

  it('shows the words of a stand-in when they are the text of a message without files', () => {
    render(bubble({ id: 'm5', role: 'user', text: '[קובץ מצורף]', attachments: [], thoughtsStatus: 'NONE' }))
    expect(screen.getByTestId('message-text').textContent).toBe('[קובץ מצורף]')
  })
})

describe('the text of a message that only carries files', () => {
  const files: Attachment[] = [
    { file_name: 'chart.png', mime_type: 'image/png', sha256: SHA, size: 100 },
    { file_name: 'data.csv', mime_type: 'text/csv', sha256: SHA2, size: 200 },
  ]

  it('is empty, whichever stand-in an older version stored', () => {
    for (const text of ['', ...LEGACY_FILE_ONLY_TEXTS, ' [קובץ מצורף] ']) {
      expect(messageDisplayText({ role: 'assistant', text, attachments: files })).toBe('')
      expect(messageDisplayText({ role: 'user', text, attachments: files })).toBe('')
    }
    expect(messageDisplayText({ role: 'assistant', text: 'See [קובץ מצורף]', attachments: files })).toBe('See [קובץ מצורף]')
    expect(messageDisplayText({ role: 'user', text: '[קובץ מצורף]', attachments: [] })).toBe('[קובץ מצורף]')
    expect(messageDisplayText({ role: 'tool_response', text: '[Attached file]', attachments: files })).toBe('[Attached file]')
  })

  it('is previewed by the names of its files', () => {
    expect(messagePreviewText({ role: 'assistant', text: '', attachments: files })).toBe('📎 chart.png, data.csv')
    expect(messagePreviewText({ role: 'assistant', text: '[קובץ מצורף]', attachments: files })).toBe('📎 chart.png, data.csv')
    expect(messagePreviewText({ role: 'user', text: 'Look', attachments: files })).toBe('Look')
    expect(messagePreviewText({ role: 'assistant', text: '', attachments: [] })).toBe('')
  })
})
