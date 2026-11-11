/**
 * ChatExportDialog — web mirror of ChatExportDialog (ChatImportExportDialogs.kt).
 *
 * Opened from the chat top-bar share button and the chat-list context-menu
 * "share" item, exactly like Android's openChatExportDialog().
 *
 * Layout: 90%-width / 80%-height panel.
 *   - Header bar ("ייצוא שיחה") with close button.
 *   - LTR monospace JSON textarea (read-only until edit mode).
 *   - Bottom buttons: Edit/✓, Share (system share sheet → download fallback),
 *     Link (create/copy/update/delete encrypted share link), "להורדות".
 *
 * Share link flow mirrors ExportImportManager: AES/CBC with random hex key,
 * POST/PUT/DELETE to https://api-divonr.xyz/share, link is
 * https://api-divonr.xyz/viewer/?id={uuid}#key={key}; the link is persisted on
 * the chat via PATCH /api/chats/{id} (shareLink/shareId fields).
 */

import React, { useEffect, useRef, useState } from 'react'
import ReactDOM from 'react-dom'
import {
  MdClose,
  MdEdit,
  MdCheck,
  MdShare,
  MdLink,
  MdDownload,
  MdContentCopy,
  MdRefresh,
} from '../../ui/icons'
import { chats as chatsApi } from '../../api/client'
import type { Chat } from '../../api/types'
import { generateEncryptionKey, encryptAES } from '../../utils/crypto'
import { t } from '../../i18n/he'

const SHARE_API_BASE = 'https://api-divonr.xyz/share'
const VIEWER_BASE = 'https://api-divonr.xyz/viewer/'

/** Extract the encryption key from a share link (…#key={KEY}) */
function extractKeyFromShareLink(link: string): string | null {
  const hashIndex = link.indexOf('#key=')
  return hashIndex !== -1 ? link.slice(hashIndex + 5) : null
}

function downloadJson(content: string, fileName: string) {
  const blob = new Blob([content], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}

// ── Small square action button (black bg, purple icon — darkButtonColors) ────

const SquareButton: React.FC<{
  'aria-label': string
  onClick: () => void
  active?: boolean
  disabled?: boolean
  children: React.ReactNode
}> = ({ 'aria-label': ariaLabel, onClick, active, disabled, children }) => (
  <button
    type="button"
    aria-label={ariaLabel}
    onClick={onClick}
    disabled={disabled}
    style={{
      width: 48,
      height: 40,
      borderRadius: 8,
      border: 'none',
      background: active ? 'var(--primary)' : '#1A1A1A',
      color: active ? '#ffffff' : 'var(--primary)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      cursor: disabled ? 'not-allowed' : 'pointer',
      opacity: disabled ? 0.6 : 1,
      flexShrink: 0,
    }}
  >
    {children}
  </button>
)

// ── Main dialog ───────────────────────────────────────────────────────────────

export interface ChatExportDialogProps {
  open: boolean
  chat: Chat | null
  onClose: () => void
  /** Called after the share-link fields changed so the page can reload the chat */
  onChatUpdated?: () => void
}

const ChatExportDialog: React.FC<ChatExportDialogProps> = ({
  open,
  chat,
  onClose,
  onChatUpdated,
}) => {
  const [content, setContent] = useState('')
  const [isEditing, setIsEditing] = useState(false)
  const [shareLink, setShareLink] = useState('')
  const [shareId, setShareId] = useState('')
  const [linkLoading, setLinkLoading] = useState(false)
  const [showLinkMenu, setShowLinkMenu] = useState(false)
  const [toast, setToast] = useState<string | null>(null)
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const showToast = (msg: string) => {
    setToast(msg)
    if (toastTimer.current) clearTimeout(toastTimer.current)
    toastTimer.current = setTimeout(() => setToast(null), 2500)
  }

  // Load export JSON + share-link state when (re)opened
  useEffect(() => {
    if (!open || !chat) return
    setIsEditing(false)
    setShowLinkMenu(false)
    setShareLink(chat.shareLink ?? '')
    setShareId(chat.shareId ?? '')
    setContent('')
    chatsApi
      .export(chat.chat_id)
      .then((data) => setContent(JSON.stringify(data, null, 2)))
      .catch(() => setContent(''))
  }, [open, chat])

  // Close on Escape
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [open, onClose])

  if (!open || !chat) return null

  const isShareLinkActive = shareLink !== '' && shareId !== ''
  const fileName = `${chat.preview_name || 'chat'}.json`

  const copyToClipboard = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      // Clipboard API unavailable (non-secure context) — best effort fallback
      const ta = document.createElement('textarea')
      ta.value = text
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      ta.remove()
    }
  }

  // ── Share (system share sheet, like Android ACTION_SEND) ──────────────────
  const handleShare = async () => {
    if (!content) {
      showToast(t('no_content_to_export'))
      return
    }
    const file = new File([content], fileName, { type: 'application/json' })
    if (navigator.canShare?.({ files: [file] })) {
      try {
        await navigator.share({ files: [file], title: t('share_chat_title') })
        return
      } catch {
        // user cancelled or share failed — fall through to download
      }
    }
    downloadJson(content, fileName)
  }

  // ── Share link: create ─────────────────────────────────────────────────────
  const handleCreateLink = async () => {
    if (!content) {
      showToast(t('no_content_to_export'))
      return
    }
    setLinkLoading(true)
    try {
      const key = generateEncryptionKey()
      const encrypted = await encryptAES(content, key)
      const response = await fetch(SHARE_API_BASE, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain' },
        body: encrypted,
      })
      if (!response.ok) throw new Error(`Server error: ${response.status}`)
      const { id: uuid } = (await response.json()) as { id: string }
      const fullLink = `${VIEWER_BASE}?id=${uuid}#key=${key}`

      await chatsApi.update(chat.chat_id, { shareLink: fullLink, shareId: uuid })
      setShareLink(fullLink)
      setShareId(uuid)
      await copyToClipboard(fullLink)
      showToast(t('share_link_copied'))
      onChatUpdated?.()
    } catch (e) {
      showToast(t('share_link_create_error') + (e instanceof Error ? e.message : String(e)))
    } finally {
      setLinkLoading(false)
    }
  }

  // ── Share link: update in place (same key, PUT to same UUID) ──────────────
  const handleUpdateLink = async () => {
    const existingKey = extractKeyFromShareLink(shareLink)
    if (!shareId || !existingKey) {
      setShowLinkMenu(false)
      await handleCreateLink()
      return
    }
    if (!content) {
      showToast(t('no_content_to_export'))
      return
    }
    setLinkLoading(true)
    setShowLinkMenu(false)
    try {
      const encrypted = await encryptAES(content, existingKey)
      const response = await fetch(`${SHARE_API_BASE}/${shareId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'text/plain' },
        body: encrypted,
      })
      if (!response.ok) throw new Error(`Server error: ${response.status}`)
      await copyToClipboard(shareLink)
      showToast(t('share_link_updated'))
    } catch (e) {
      showToast(t('share_link_update_error') + (e instanceof Error ? e.message : String(e)))
    } finally {
      setLinkLoading(false)
    }
  }

  // ── Share link: delete ─────────────────────────────────────────────────────
  const handleDeleteLink = async () => {
    setShowLinkMenu(false)
    if (!shareId) return
    try {
      await fetch(`${SHARE_API_BASE}/${shareId}`, { method: 'DELETE' }).catch(() => {})
      await chatsApi.update(chat.chat_id, { shareLink: '', shareId: '' })
      setShareLink('')
      setShareId('')
      showToast(t('share_link_deleted'))
      onChatUpdated?.()
    } catch (e) {
      showToast(t('share_link_delete_error') + (e instanceof Error ? e.message : String(e)))
    }
  }

  const handleCopyLink = async () => {
    setShowLinkMenu(false)
    if (shareLink) {
      await copyToClipboard(shareLink)
      showToast(t('share_link_copied'))
    }
  }

  const linkMenuItems: Array<{ label: string; icon: React.ReactNode; onClick: () => void }> = [
    { label: t('share_link_copy'), icon: <MdContentCopy size={18} />, onClick: handleCopyLink },
    { label: t('share_link_update'), icon: <MdRefresh size={18} />, onClick: handleUpdateLink },
    { label: t('share_link_delete'), icon: <MdClose size={18} />, onClick: handleDeleteLink },
  ]

  return ReactDOM.createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t('chat_export_header')}
      style={{
        position: 'fixed',
        inset: 0,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 10000,
      }}
    >
      {/* Backdrop */}
      <div
        aria-hidden="true"
        onClick={onClose}
        style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.6)' }}
      />

      {/* Panel — fillMaxWidth(0.9) / fillMaxHeight(0.8) */}
      <div
        style={{
          position: 'relative',
          width: '90%',
          height: '80%',
          background: 'var(--surface)',
          borderRadius: 'var(--radius-card)',
          boxShadow: '0 8px 32px rgba(0,0,0,0.50)',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}
      >
        {/* Header (primaryContainer-style) */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: 16,
            background: 'var(--primary-15)',
            flexShrink: 0,
          }}
        >
          <span
            style={{
              fontSize: 'var(--fs-title-medium)',
              fontWeight: 600,
              color: 'var(--on-surface)',
            }}
          >
            {t('chat_export_header')}
          </span>
          <button
            type="button"
            aria-label={t('close')}
            onClick={onClose}
            style={{
              background: 'none',
              border: 'none',
              color: 'var(--on-surface)',
              cursor: 'pointer',
              display: 'flex',
              padding: 4,
            }}
          >
            <MdClose size={20} />
          </button>
        </div>

        {/* Edit-mode indicator */}
        {isEditing && (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 8,
              padding: '8px 16px',
              background: 'var(--primary-08)',
              color: 'var(--primary)',
              fontSize: 'var(--fs-label-medium)',
              fontWeight: 500,
              flexShrink: 0,
            }}
          >
            <MdEdit size={16} />
            {t('chat_export_editing_active')}
          </div>
        )}

        {/* JSON content — LTR for proper JSON formatting */}
        <div style={{ flex: 1, padding: 16, minHeight: 0 }}>
          <textarea
            dir="ltr"
            value={content}
            onChange={(e) => setContent(e.target.value)}
            readOnly={!isEditing}
            placeholder={content === '' ? 'No content to export' : undefined}
            style={{
              width: '100%',
              height: '100%',
              boxSizing: 'border-box',
              resize: 'none',
              background: isEditing ? 'var(--surface)' : 'var(--surface-variant)',
              color: 'var(--on-surface)',
              border: isEditing ? '2px solid var(--primary-30)' : '1px solid transparent',
              borderRadius: 12,
              padding: 12,
              fontFamily: 'monospace',
              fontSize: 13,
              lineHeight: 1.5,
              outline: 'none',
            }}
          />
        </div>

        {/* Toast (Android Toast equivalent) */}
        {toast && (
          <div
            role="status"
            style={{
              textAlign: 'center',
              padding: '6px 16px',
              color: 'var(--primary)',
              fontSize: 'var(--fs-label-medium)',
              flexShrink: 0,
            }}
          >
            {toast}
          </div>
        )}

        {/* Bottom buttons */}
        <div
          style={{
            display: 'flex',
            gap: 8,
            padding: 16,
            flexShrink: 0,
          }}
        >
          {/* Edit / Save toggle */}
          <SquareButton
            aria-label={isEditing ? 'Save changes' : 'Edit'}
            onClick={() => setIsEditing((v) => !v)}
          >
            {isEditing ? <MdCheck size={18} /> : <MdEdit size={18} />}
          </SquareButton>

          {/* Share */}
          <SquareButton aria-label={t('share_button')} onClick={handleShare}>
            <MdShare size={18} />
          </SquareButton>

          {/* Link — with floating menu when active */}
          <div style={{ position: 'relative' }}>
            <SquareButton
              aria-label="Link"
              active={isShareLinkActive}
              disabled={linkLoading}
              onClick={() => {
                if (isShareLinkActive) setShowLinkMenu((v) => !v)
                else handleCreateLink()
              }}
            >
              {linkLoading ? (
                <span
                  style={{
                    width: 16,
                    height: 16,
                    border: '2px solid currentColor',
                    borderTopColor: 'transparent',
                    borderRadius: '50%',
                    display: 'inline-block',
                    animation: 'spin 0.7s linear infinite',
                  }}
                />
              ) : (
                <MdLink size={18} />
              )}
            </SquareButton>

            {showLinkMenu && (
              <div
                style={{
                  position: 'absolute',
                  bottom: 46,
                  insetInlineStart: 0,
                  background: 'var(--surface)',
                  borderRadius: 'var(--radius-card)',
                  boxShadow: '0 4px 16px rgba(0,0,0,0.45)',
                  zIndex: 10,
                  minWidth: 140,
                  padding: '4px 0',
                }}
              >
                {linkMenuItems.map((item) => (
                  <button
                    key={item.label}
                    type="button"
                    onClick={item.onClick}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 10,
                      width: '100%',
                      padding: '10px 16px',
                      background: 'none',
                      border: 'none',
                      cursor: 'pointer',
                      color: 'var(--on-surface)',
                      fontSize: 'var(--fs-body-medium)',
                      fontFamily: 'inherit',
                      textAlign: 'start',
                    }}
                  >
                    {item.icon}
                    {item.label}
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* Save to Downloads — takes remaining space */}
          <button
            type="button"
            onClick={() => {
              if (!content) {
                showToast(t('no_content_to_export'))
                return
              }
              downloadJson(content, fileName)
            }}
            style={{
              flex: 1,
              height: 40,
              borderRadius: 8,
              border: 'none',
              background: 'var(--primary)',
              color: '#ffffff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 8,
              cursor: 'pointer',
              fontSize: 'var(--fs-body-medium)',
              fontWeight: 500,
              fontFamily: 'inherit',
            }}
          >
            <MdDownload size={18} />
            {t('chat_export_to_downloads')}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}

export default ChatExportDialog
