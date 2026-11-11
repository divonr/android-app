/**
 * ImageViewer — an image of a conversation, full screen inside the app (mirrors Android's
 * ImageViewer.kt): dark background, close button / Escape, click to zoom, and a menu behind
 * the three dots at the top that
 *  - shares the image with other apps (Web Share API with files; hidden where the browser
 *    cannot share files — "save" covers it there),
 *  - saves it (a browser download through the file route),
 *  - forwards it to another chat: a chat picker (ChatPicker.tsx), then that chat opens with the image attached
 *    to its draft, ready to send (see utils/forwardedFile.ts).
 *
 * Any image thumbnail opens it with `openImageOnClick`; `ImageViewerHost` (in AppLayout) shows it.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import ReactDOM from 'react-dom'
import { useMatch, useNavigate } from 'react-router-dom'
import { create } from 'zustand'
import { MdClose, MdFileDownload, MdForward, MdMoreVert, MdShare } from '../../ui/icons'
import type { Attachment, Chat, UserChatHistory } from '../../api/types'
import { chats as chatsApi } from '../../api/client'
import { useChatStore } from '../../stores/chatStore'
import { markAttachmentsUsed } from '../../hooks/useDraftAttachments'
import { attachmentUrl, isAbortError } from '../../utils/attachments'
import { setForwardedFile } from '../../utils/forwardedFile'
import ChatPicker from './ChatPicker'
import { t } from '../../i18n/he'

// ─── Which image is open ──────────────────────────────────────────────────────

interface ImageViewerState {
  attachment: Attachment | null
  open: (attachment: Attachment) => void
  close: () => void
}

export const useImageViewerStore = create<ImageViewerState>((set) => ({
  attachment: null,
  open: (attachment) => set({ attachment }),
  close: () => set({ attachment: null }),
}))

/**
 * Click handler of an image thumbnail link: a plain click opens the viewer; a modified one
 * (ctrl / middle click, …) is left to the browser — the link opens the image in a new tab.
 */
export function openImageOnClick(e: React.MouseEvent, att: Attachment): void {
  if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
  if (!attachmentUrl(att)) return
  e.preventDefault()
  useImageViewerStore.getState().open(att)
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Whether this browser can hand a file of this type to other apps. */
function canShareFiles(mime: string): boolean {
  if (typeof navigator === 'undefined' || typeof navigator.share !== 'function') return false
  if (typeof navigator.canShare !== 'function' || typeof File === 'undefined') return false
  try {
    return navigator.canShare({ files: [new File([''], 'image', { type: mime })] })
  } catch {
    return false
  }
}

const roundButton: React.CSSProperties = {
  width: 44,
  height: 44,
  borderRadius: '50%',
  border: 'none',
  background: 'rgba(0,0,0,0.55)',
  color: '#fff',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  cursor: 'pointer',
  padding: 0,
  textDecoration: 'none',
}

// ─── Viewer ───────────────────────────────────────────────────────────────────

interface ImageViewerProps {
  att: Attachment
  onClose: () => void
}

const ImageViewer: React.FC<ImageViewerProps> = ({ att, onClose }) => {
  const navigate = useNavigate()
  const currentChatId = useMatch('/chat/:id')?.params.id
  const src = attachmentUrl(att)!
  const [zoomed, setZoomed] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const storeHistory = useChatStore((s) => s.history)
  const [loadedHistory, setLoadedHistory] = useState<UserChatHistory | null>(null)
  const history = storeHistory ?? loadedHistory
  const shareable = useMemo(() => canShareFiles(att.mime_type), [att.mime_type])

  // The image's bytes as a File, for share / forward. Fetched when the viewer opens (the
  // browser has just loaded the same URL for the thumbnail): sharing must start right at the
  // click, with the file already in hand.
  const filePromise = useRef<Promise<File> | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const loadFile = useCallback((): Promise<File> => {
    if (!filePromise.current) {
      filePromise.current = fetch(src, { credentials: 'include' })
        .then((res) => {
          if (!res.ok) throw new Error(`HTTP ${res.status}`)
          return res.blob()
        })
        .then((blob) => new File([blob], att.file_name || 'image', { type: att.mime_type || blob.type }))
      filePromise.current.catch(() => { filePromise.current = null })
    }
    return filePromise.current
  }, [src, att.file_name, att.mime_type])

  useEffect(() => {
    let live = true
    loadFile().then((f) => { if (live) setFile(f) }, () => {})
    return () => { live = false }
  }, [loadFile])

  // Escape closes what is on top first: the chat picker, then the menu, then the viewer
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      if (pickerOpen) setPickerOpen(false)
      else if (menuOpen) setMenuOpen(false)
      else onClose()
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [pickerOpen, menuOpen, onClose])

  // The page behind does not scroll while the viewer is open
  useEffect(() => {
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = previous }
  }, [])

  const handleShare = () => {
    if (!file) return
    setMenuOpen(false)
    setError(null)
    navigator.share({ files: [file], title: file.name }).catch((err) => {
      // Dismissing the share sheet is not a failure
      if (!isAbortError(err)) setError(t('image_viewer_share_failed'))
    })
  }

  const openPicker = () => {
    setMenuOpen(false)
    setError(null)
    setPickerOpen(true)
    // The chat list is in the store only once the history page was visited
    if (!storeHistory && !loadedHistory) {
      chatsApi.list().then(setLoadedHistory, () => setError(t('image_viewer_forward_failed')))
    }
  }

  /** Forward to [target] — null: a new chat. */
  const handleForward = async (target: Chat | null) => {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      const forwarded = await loadFile()
      const chatId = target
        ? target.chat_id
        : (await chatsApi.create({ previewName: t('new_chat'), systemPrompt: '', groupId: null })).chat_id
      if (!target) void useChatStore.getState().refreshHistory()
      // The file is already in the store, in a saved message: the new draft re-uploads it (the
      // store keeps one copy per hash) and must never delete that copy when it is dropped
      markAttachmentsUsed([att])
      setForwardedFile(chatId, forwarded)
      onClose()
      navigate(`/chat/${chatId}`)
    } catch {
      setError(t('image_viewer_forward_failed'))
      setBusy(false)
    }
  }

  const targets = useMemo(
    () => (history?.chat_history ?? []).filter((c) => c.chat_id !== currentChatId),
    [history, currentChatId],
  )

  return ReactDOM.createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-label={att.file_name}
      data-testid="image-viewer"
      style={{ position: 'fixed', inset: 0, zIndex: 10000, background: '#000' }}
    >
      {/* The image: fitted to the screen; a click zooms in (scroll to pan) and back out */}
      <div
        onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
        style={{
          position: 'absolute',
          inset: 0,
          overflow: zoomed ? 'auto' : 'hidden',
          display: 'flex',
          // Zoomed: start-aligned, or the scroll could not reach the overflowing edge
          alignItems: zoomed ? 'flex-start' : 'center',
          justifyContent: zoomed ? 'flex-start' : 'center',
          direction: 'ltr',
        }}
      >
        <img
          src={src}
          alt={att.file_name}
          title={t('image_viewer_zoom')}
          draggable={false}
          onClick={() => setZoomed((z) => !z)}
          style={
            zoomed
              ? { display: 'block', width: '250%', maxWidth: 'none', flexShrink: 0, cursor: 'zoom-out' }
              : { display: 'block', maxWidth: '100%', maxHeight: '100%', objectFit: 'contain', cursor: 'zoom-in' }
          }
        />
      </div>

      <button
        type="button"
        onClick={onClose}
        title={t('image_viewer_close')}
        aria-label={t('image_viewer_close')}
        style={{ ...roundButton, position: 'absolute', top: 12, insetInlineStart: 12 }}
      >
        <MdClose size={24} />
      </button>

      {/* While the menu is open, a click anywhere else only closes it */}
      {menuOpen && (
        <div
          data-testid="image-viewer-menu-backdrop"
          onClick={() => setMenuOpen(false)}
          style={{ position: 'absolute', inset: 0 }}
        />
      )}

      {/* The menu, behind the three dots */}
      <div
        style={{
          position: 'absolute',
          insetInlineEnd: 12,
          top: 12,
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
        }}
      >
        <button
          type="button"
          onClick={() => setMenuOpen((open) => !open)}
          title={t('image_viewer_menu')}
          aria-label={t('image_viewer_menu')}
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          style={roundButton}
        >
          <MdMoreVert size={24} />
        </button>
        {menuOpen && (
          <div role="menu" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {shareable && (
              <button
                type="button"
                role="menuitem"
                onClick={handleShare}
                disabled={!file}
                title={t('image_viewer_share')}
                aria-label={t('image_viewer_share')}
                style={{ ...roundButton, opacity: file ? 1 : 0.5 }}
              >
                <MdShare size={22} />
              </button>
            )}
            <a
              role="menuitem"
              href={attachmentUrl(att, true)!}
              download={att.file_name}
              // Closed only after the click: the browser starts the download from this link
              onClick={() => setTimeout(() => setMenuOpen(false), 0)}
              title={t('image_viewer_save')}
              aria-label={t('image_viewer_save')}
              style={roundButton}
            >
              <MdFileDownload size={24} />
            </a>
            <button
              type="button"
              role="menuitem"
              onClick={openPicker}
              title={t('image_viewer_forward')}
              aria-label={t('image_viewer_forward')}
              style={roundButton}
            >
              <MdForward size={24} />
            </button>
          </div>
        )}
      </div>

      {error && !pickerOpen && (
        <div
          role="alert"
          style={{
            position: 'absolute',
            bottom: 24,
            left: '50%',
            transform: 'translateX(-50%)',
            background: 'var(--surface)',
            color: 'var(--on-surface)',
            borderRadius: 8,
            padding: '8px 16px',
            fontSize: 'var(--fs-body-medium)',
          }}
        >
          {error}
        </div>
      )}

      {/* Chat picker */}
      {pickerOpen && (
        <ChatPicker
          title={t('image_viewer_forward_title')}
          chats={targets}
          busy={busy}
          error={error}
          onDismiss={() => setPickerOpen(false)}
          onPick={handleForward}
        />
      )}
    </div>,
    document.body,
  )
}

/** Shows the image opened with `openImageOnClick`, over whichever page opened it. */
export const ImageViewerHost: React.FC = () => {
  const attachment = useImageViewerStore((s) => s.attachment)
  const close = useImageViewerStore((s) => s.close)
  if (!attachment || !attachmentUrl(attachment)) return null
  return <ImageViewer key={attachment.sha256 ?? attachment.file_name} att={attachment} onClose={close} />
}

export default ImageViewer
