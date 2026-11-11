/**
 * ChatInputArea — matches ChatInputArea.kt precisely.
 *
 * Layout:
 *  - Outer Surface: SurfaceVariant bg, elevation shadow, padding h:20 v:16
 *  - Inner pill: Surface bg (#1A1B26), radius 24, padding 4
 *  - Row: emoji toggle | text field (flex 1) | attach (+) | [WebSearchToggle] | [SendButton / EditButtons]
 *  - ≥3 lines → stacked vertical layout: web search above send/edit
 *  - SelectedFilesPreview (single=list row, multi=thumbnail grid) above the pill
 *  - Emoji panel under the pill (lazy-loaded): the emoji toggle opens and closes it, showing a
 *    keyboard icon while it is open; Escape and a click outside the composer close it; a picked
 *    emoji goes in at the textarea's caret and the panel stays open
 *
 * Send button: 40px primary circle; streaming → spinner + stop icon
 * Edit mode: ConfirmEdit (check) + ConfirmAndResend (send) buttons
 */

import React, { Suspense, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import {
  MdAdd,
  MdSend,
  MdStop,
  MdCheck,
  MdLanguage,
  MdClose,
  MdAttachFile,
  MdRefresh,
  MdErrorOutline,
  MdOutlineEmojiEmotions,
  MdOutlineKeyboard,
} from '../../ui/icons'
import type { DraftAttachment } from '../../hooks/useDraftAttachments'
import { t } from '../../i18n/he'

// The picker and its emoji data load on first opening, as a chunk of their own
const EmojiPanel = React.lazy(() => import('./EmojiPanel'))

/** The panel's height, held while its chunk loads so the composer does not jump. */
const EMOJI_PANEL_HEIGHT = 320

const resizeTextarea = (ta: HTMLTextAreaElement) => {
  ta.style.height = 'auto'
  ta.style.height = Math.min(ta.scrollHeight, 150) + 'px'
}

// ─── Sub-components ──────────────────────────────────────────────────────────

interface RoundBtnProps {
  onClick: () => void
  disabled?: boolean
  loading?: boolean
  color?: string
  'aria-label': string
  children: React.ReactNode
  size?: number
}

const RoundBtn: React.FC<RoundBtnProps> = ({
  onClick, disabled = false, loading = false,
  color = 'var(--primary)',
  'aria-label': label,
  children,
  size = 40,
}) => (
  <button
    type="button"
    aria-label={label}
    onClick={onClick}
    disabled={disabled && !loading}
    style={{
      position: 'relative',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      width: size,
      height: size,
      minWidth: size,
      borderRadius: '50%',
      background: disabled && !loading ? 'var(--primary-30)' : color,
      border: 'none',
      cursor: disabled && !loading ? 'not-allowed' : 'pointer',
      color: '#fff',
      flexShrink: 0,
      transition: 'background 0.15s',
    }}
  >
    {loading && (
      <span
        style={{
          position: 'absolute',
          inset: 3,
          borderRadius: '50%',
          border: '2px solid rgba(255,255,255,0.8)',
          borderTopColor: 'transparent',
          animation: 'r3spin 0.75s linear infinite',
        }}
      />
    )}
    <span style={{ position: 'relative', zIndex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      {children}
    </span>
    <style>{`@keyframes r3spin { to { transform: rotate(360deg); } }`}</style>
  </button>
)

// ─── SelectedFilesPreview ────────────────────────────────────────────────────

interface FilesPreviewProps {
  attachments: DraftAttachment[]
  onRemove: (id: string) => void
  onRetry: (id: string) => void
}

/** The spinner laid over an attachment while it uploads. */
const UploadSpinner: React.FC<{ size: number }> = ({ size }) => (
  <span
    role="progressbar"
    aria-label={t('attachment_uploading')}
    style={{
      display: 'block',
      width: size,
      height: size,
      borderRadius: '50%',
      border: '2px solid var(--primary)',
      borderTopColor: 'transparent',
      animation: 'r3spin 0.75s linear infinite',
      flexShrink: 0,
    }}
  />
)

/** The file's picture: its image when it is one, else the paperclip. Greyed while uploading. */
const DraftGlyph: React.FC<{ att: DraftAttachment; size: number }> = ({ att, size }) => {
  const dim = att.status === 'uploading' ? 0.4 : 1
  if (att.previewUrl) {
    return (
      <img
        src={att.previewUrl}
        alt=""
        draggable={false}
        style={{
          width: size, height: size, borderRadius: 6, objectFit: 'cover', flexShrink: 0,
          opacity: dim, filter: att.status === 'uploading' ? 'grayscale(1)' : undefined,
          transition: 'opacity 0.2s, filter 0.2s',
        }}
      />
    )
  }
  if (att.status === 'failed') {
    return <MdErrorOutline size={Math.min(size, 24)} style={{ color: 'var(--color-error)', flexShrink: 0 }} />
  }
  return (
    <MdAttachFile
      size={Math.min(size, 24)}
      style={{ color: 'var(--primary)', flexShrink: 0, opacity: dim, transition: 'opacity 0.2s' }}
    />
  )
}

const SelectedFilesPreview: React.FC<FilesPreviewProps> = ({ attachments, onRemove, onRetry }) => {
  if (attachments.length === 0) return null

  const border = (att: DraftAttachment) =>
    `1px solid ${att.status === 'failed' ? 'var(--color-error)' : 'var(--color-border)'}`
  const stateTitle = (att: DraftAttachment) =>
    att.status === 'uploading' ? t('attachment_uploading') : att.status === 'failed' ? att.error : undefined

  if (attachments.length === 1) {
    // Single file: list row
    const att = attachments[0]
    return (
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 20px' }}>
        <div
          data-testid="draft-attachment"
          data-upload-status={att.status}
          aria-busy={att.status === 'uploading'}
          title={stateTitle(att)}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            background: 'var(--surface)',
            borderRadius: 8,
            padding: '6px 10px',
            border: border(att),
            maxWidth: '100%',
            overflow: 'hidden',
          }}
        >
          <span style={{ position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
            <DraftGlyph att={att} size={att.previewUrl ? 28 : 16} />
            {att.status === 'uploading' && (
              <span style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <UploadSpinner size={14} />
              </span>
            )}
          </span>
          <span style={{ display: 'flex', flexDirection: 'column', minWidth: 0, flex: 1 }}>
            <span
              style={{
                fontSize: 13,
                color: 'var(--color-text)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                opacity: att.status === 'uploading' ? 0.5 : 1,
                transition: 'opacity 0.2s',
              }}
            >
              {att.file_name}
            </span>
            {att.status === 'failed' && (
              <span role="alert" style={{ fontSize: 11, color: 'var(--color-error)' }}>{att.error}</span>
            )}
          </span>
          {att.status === 'failed' && att.retryable && (
            <button
              type="button"
              onClick={() => onRetry(att.id)}
              style={{ background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', padding: 0, display: 'flex' }}
              aria-label={t('attachment_retry')}
              title={t('attachment_retry')}
            >
              <MdRefresh size={16} />
            </button>
          )}
          <button
            type="button"
            onClick={() => onRemove(att.id)}
            style={{ background: 'none', border: 'none', color: 'var(--on-surface-variant)', cursor: 'pointer', padding: 0, display: 'flex' }}
            aria-label="Remove attachment"
            title={t('attachment_remove')}
          >
            <MdClose size={14} />
          </button>
        </div>
      </div>
    )
  }

  // Multiple files: thumbnail grid
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fill, minmax(80px, 1fr))',
        gap: 8,
        padding: '10px 20px 6px',
        maxHeight: 160,
        overflowY: 'auto',
      }}
    >
      {attachments.map((att) => (
        <div
          key={att.id}
          data-testid="draft-attachment"
          data-upload-status={att.status}
          aria-busy={att.status === 'uploading'}
          title={stateTitle(att)}
          style={{
            position: 'relative',
            background: 'var(--surface)',
            borderRadius: 8,
            border: border(att),
            padding: 6,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 4,
            minHeight: 64,
          }}
        >
          <span style={{ position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: 32 }}>
            <DraftGlyph att={att} size={att.previewUrl ? 40 : 24} />
            {att.status === 'uploading' && (
              <span style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <UploadSpinner size={20} />
              </span>
            )}
          </span>
          <span
            style={{
              fontSize: 10,
              color: att.status === 'failed' ? 'var(--color-error)' : 'var(--on-surface-variant)',
              textAlign: 'center',
              wordBreak: 'break-all',
              lineHeight: 1.2,
              opacity: att.status === 'uploading' ? 0.5 : 1,
            }}
          >
            {att.status === 'failed'
              ? <span role="alert">{att.error}</span>
              : att.file_name.length > 12 ? att.file_name.slice(0, 10) + '…' : att.file_name}
          </span>
          {att.status === 'failed' && att.retryable && (
            <button
              type="button"
              onClick={() => onRetry(att.id)}
              style={{
                background: 'var(--primary-15)', border: 'none', borderRadius: 10, color: 'var(--primary)',
                cursor: 'pointer', padding: '2px 8px', fontSize: 10, display: 'flex', alignItems: 'center', gap: 2,
              }}
              aria-label={t('attachment_retry')}
            >
              <MdRefresh size={11} />
              {t('attachment_retry')}
            </button>
          )}
          <button
            type="button"
            onClick={() => onRemove(att.id)}
            style={{
              position: 'absolute',
              top: -4, insetInlineEnd: -4,
              width: 18, height: 18,
              borderRadius: '50%',
              background: 'var(--color-error)',
              border: 'none',
              color: '#fff',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              padding: 0,
            }}
            aria-label="Remove attachment"
            title={t('attachment_remove')}
          >
            <MdClose size={11} />
          </button>
        </div>
      ))}
    </div>
  )
}

// ─── Main ChatInputArea ──────────────────────────────────────────────────────

interface ChatInputAreaProps {
  inputText: string
  onInputChange: (v: string) => void
  /** The un-sent attachments, each with its upload state */
  attachments: DraftAttachment[]
  onRemoveAttachment: (id: string) => void
  onRetryAttachment: (id: string) => void
  onAttachFile: () => void
  streaming: boolean
  editingMsg: boolean
  webSearch: boolean
  showWebSearch: boolean
  onToggleWebSearch: () => void
  onSend: () => void
  onStop: () => void
  onConfirmEdit: () => void
  onConfirmEditAndResend: () => void
  onCancelEdit: () => void
  onKeyDown: (e: React.KeyboardEvent<HTMLTextAreaElement>) => void
  textareaRef?: React.Ref<HTMLTextAreaElement>
}

const ChatInputArea: React.FC<ChatInputAreaProps> = ({
  inputText,
  onInputChange,
  attachments,
  onRemoveAttachment,
  onRetryAttachment,
  onAttachFile,
  streaming,
  editingMsg,
  webSearch,
  showWebSearch,
  onToggleWebSearch,
  onSend,
  onStop,
  onConfirmEdit,
  onConfirmEditAndResend,
  onCancelEdit,
  onKeyDown,
  textareaRef,
}) => {
  // Estimate line count (mirrors Android logic)
  const newlineCount = inputText.split('\n').length - 1
  const longestLineLength = inputText.split('\n').reduce((m, l) => Math.max(m, l.length), 0)
  const wrappedLines = longestLineLength > 35 ? Math.floor(longestLineLength / 35) : 0
  const estimatedLines = newlineCount + 1 + wrappedLines
  const isExpanded = estimatedLines >= 3

  // A message cannot go out before its files are in the store: sending waits for the uploads,
  // and a failed upload has to be retried or removed first
  const uploading = attachments.some((a) => a.status === 'uploading')
  const uploadFailed = attachments.some((a) => a.status === 'failed')
  const uploadsBlock = uploading || uploadFailed
  const blockedReason = uploading
    ? t('attachment_wait_for_uploads')
    : uploadFailed ? t('attachment_fix_failed_uploads') : undefined
  // A new message is a text, files, or both; an edit keeps the message's own files and takes
  // none from the composer, so it needs a text
  const hasText = inputText.trim().length > 0
  const canSend = (hasText || (!editingMsg && attachments.length > 0)) && !uploadsBlock

  // ── Emoji panel ──────────────────────────────────────────────────────────
  const [emojiOpen, setEmojiOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const ownTextareaRef = useRef<HTMLTextAreaElement | null>(null)
  const setTextareaRef = useCallback(
    (el: HTMLTextAreaElement | null) => {
      ownTextareaRef.current = el
      if (typeof textareaRef === 'function') textareaRef(el)
      else if (textareaRef) (textareaRef as React.MutableRefObject<HTMLTextAreaElement | null>).current = el
    },
    [textareaRef],
  )
  /** Where the caret goes once the text with the inserted emoji has rendered */
  const pendingCaret = useRef<number | null>(null)

  // A textarea keeps its selection while the panel has the focus, so every emoji goes in at
  // the caret, in place of the selection if there is one
  const insertEmoji = useCallback(
    (emoji: string) => {
      const ta = ownTextareaRef.current
      const start = Math.min(ta?.selectionStart ?? inputText.length, inputText.length)
      const end = Math.min(Math.max(ta?.selectionEnd ?? start, start), inputText.length)
      pendingCaret.current = start + emoji.length
      onInputChange(inputText.slice(0, start) + emoji + inputText.slice(end))
    },
    [inputText, onInputChange],
  )

  useLayoutEffect(() => {
    const caret = pendingCaret.current
    const ta = ownTextareaRef.current
    if (caret === null || !ta) return
    pendingCaret.current = null
    ta.setSelectionRange(caret, caret)
    resizeTextarea(ta)
  }, [inputText])

  const closeEmoji = useCallback((focusText: boolean) => {
    setEmojiOpen(false)
    if (focusText) ownTextareaRef.current?.focus()
  }, [])

  // Escape and a click outside the composer close the panel. (The picker takes an Escape for
  // itself first when it has a search query or a skin-tone popup to dismiss.)
  useEffect(() => {
    if (!emojiOpen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        closeEmoji(true)
      }
    }
    const onPointer = (e: MouseEvent) => {
      if (rootRef.current && e.target instanceof Node && !rootRef.current.contains(e.target)) {
        closeEmoji(false)
      }
    }
    document.addEventListener('keydown', onKey, true)
    document.addEventListener('mousedown', onPointer)
    return () => {
      document.removeEventListener('keydown', onKey, true)
      document.removeEventListener('mousedown', onPointer)
    }
  }, [emojiOpen, closeEmoji])

  // Action buttons (right side of pill)
  const actionButtons = () => {
    if (streaming) {
      return (
        <RoundBtn
          onClick={onStop}
          loading
          aria-label="Stop generation"
        >
          <MdStop size={14} />
        </RoundBtn>
      )
    }

    if (editingMsg) {
      if (isExpanded && showWebSearch) {
        // Vertical: web search on top, confirm + resend below
        return (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
            {showWebSearch && (
              <WebSearchToggleBtn enabled={webSearch} onClick={onToggleWebSearch} />
            )}
            <RoundBtn onClick={onConfirmEdit} disabled={!canSend} aria-label="Confirm edit">
              <MdCheck size={18} />
            </RoundBtn>
            <RoundBtn onClick={onConfirmEditAndResend} disabled={!canSend} aria-label="Confirm edit and resend">
              <MdSend size={18} />
            </RoundBtn>
          </div>
        )
      }
      return (
        <>
          {showWebSearch && <WebSearchToggleBtn enabled={webSearch} onClick={onToggleWebSearch} />}
          <div style={{ display: 'flex', gap: 6 }}>
            <RoundBtn onClick={onConfirmEdit} disabled={!canSend} aria-label="Confirm edit">
              <MdCheck size={18} />
            </RoundBtn>
            <RoundBtn onClick={onConfirmEditAndResend} disabled={!canSend} aria-label="Confirm edit and resend">
              <MdSend size={18} />
            </RoundBtn>
          </div>
        </>
      )
    }

    // Normal send
    if (isExpanded && showWebSearch) {
      return (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
          <WebSearchToggleBtn enabled={webSearch} onClick={onToggleWebSearch} />
          <RoundBtn onClick={onSend} disabled={!canSend} aria-label="Send message">
            <MdSend size={18} />
          </RoundBtn>
        </div>
      )
    }

    return (
      <>
        {showWebSearch && <WebSearchToggleBtn enabled={webSearch} onClick={onToggleWebSearch} />}
        <RoundBtn onClick={onSend} disabled={!canSend} aria-label="Send message">
          <MdSend size={18} />
        </RoundBtn>
      </>
    )
  }

  return (
    <div
      ref={rootRef}
      style={{
        background: 'var(--surface-variant)',
        borderTop: '1px solid var(--color-border)',
        boxShadow: '0 -2px 8px rgba(0,0,0,0.18)',
        flexShrink: 0,
      }}
    >
      {/* Edit mode banner */}
      {editingMsg && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '6px 20px',
            borderBottom: '1px solid var(--primary-20)',
            background: 'rgba(108,124,231,0.08)',
          }}
        >
          <span style={{ fontSize: 13, color: 'var(--primary)', fontWeight: 500 }}>
            {t('editing_message')}
          </span>
          <button
            type="button"
            onClick={onCancelEdit}
            style={{ background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', padding: 4, display: 'flex' }}
            aria-label="Cancel edit"
          >
            <MdClose size={18} />
          </button>
        </div>
      )}

      {/* Files preview (above pill) */}
      <SelectedFilesPreview
        attachments={attachments}
        onRemove={onRemoveAttachment}
        onRetry={onRetryAttachment}
      />
      {blockedReason && (
        <div
          role="status"
          style={{ padding: '0 20px', fontSize: 11, color: uploadFailed && !uploading ? 'var(--color-error)' : 'var(--on-surface-variant)' }}
        >
          {blockedReason}
        </div>
      )}

      {/* Outer padding */}
      <div style={{ padding: '16px 20px' }}>
        {/* Inner pill */}
        <div
          style={{
            display: 'flex',
            alignItems: isExpanded ? 'flex-end' : 'center',
            gap: 8,
            background: 'var(--surface)',
            borderRadius: 24,
            padding: 4,
          }}
        >
          {/* Emoji toggle: a smiley, or a keyboard while the emoji panel is open */}
          <button
            type="button"
            onClick={() => (emojiOpen ? closeEmoji(true) : setEmojiOpen(true))}
            aria-label={emojiOpen ? t('emoji_keyboard_show_keyboard') : t('emoji_keyboard_open')}
            aria-expanded={emojiOpen}
            title={emojiOpen ? t('emoji_keyboard_show_keyboard') : t('emoji_keyboard_open')}
            style={{
              width: 36,
              height: 36,
              minWidth: 36,
              borderRadius: '50%',
              background: 'transparent',
              border: 'none',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              flexShrink: 0,
              color: 'var(--on-surface)',
            }}
          >
            {emojiOpen ? <MdOutlineKeyboard size={24} /> : <MdOutlineEmojiEmotions size={24} />}
          </button>

          {/* Text field */}
          <textarea
            ref={setTextareaRef}
            placeholder={t('type_message')}
            value={inputText}
            onChange={(e) => {
              onInputChange(e.target.value)
              // Auto-resize
              resizeTextarea(e.target)
            }}
            onKeyDown={onKeyDown}
            rows={1}
            style={{
              flex: 1,
              background: 'transparent',
              border: 'none',
              outline: 'none',
              resize: 'none',
              color: 'var(--color-text)',
              fontFamily: 'var(--font-sans)',
              fontSize: 15,
              lineHeight: '22px',
              padding: '6px 4px',
              minHeight: 34,
              maxHeight: 150,
              direction: 'auto' as unknown as undefined,
              overflowY: 'auto',
            }}
          />

          {/* Attach (+) button, next to the web-search toggle */}
          <button
            type="button"
            onClick={onAttachFile}
            aria-label={t('attach_file')}
            title={t('attach_file')}
            style={{
              width: 34,
              height: 34,
              minWidth: 34,
              borderRadius: 12,
              background: attachments.length > 0 ? 'var(--primary-15)' : 'transparent',
              border: 'none',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              flexShrink: 0,
              transition: 'background 0.15s',
            }}
          >
            <MdAdd
              size={20}
              style={{ color: attachments.length > 0 ? 'var(--primary)' : 'var(--on-surface-variant)' }}
            />
          </button>

          {/* Action buttons */}
          <div
            style={{
              display: 'flex',
              alignItems: isExpanded ? 'flex-end' : 'center',
              gap: 6,
              flexShrink: 0,
            }}
          >
            {actionButtons()}
          </div>
        </div>
      </div>

      {/* Emoji panel */}
      {emojiOpen && (
        <Suspense fallback={<div style={{ height: EMOJI_PANEL_HEIGHT }} />}>
          <EmojiPanel onPick={insertEmoji} />
        </Suspense>
      )}
    </div>
  )
}

// ─── WebSearchToggleBtn ───────────────────────────────────────────────────────

interface WebSearchToggleBtnProps {
  enabled: boolean
  onClick: () => void
}

const WebSearchToggleBtn: React.FC<WebSearchToggleBtnProps> = ({ enabled, onClick }) => (
  <button
    type="button"
    onClick={onClick}
    aria-label={t('web_search_toggle')}
    title={t('web_search_toggle')}
    style={{
      width: 40,
      height: 40,
      minWidth: 40,
      borderRadius: '50%',
      background: enabled ? 'var(--primary-15)' : 'transparent',
      border: 'none',
      cursor: 'pointer',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      transition: 'background 0.15s',
    }}
  >
    <MdLanguage size={22} style={{ color: enabled ? 'var(--primary)' : 'var(--on-surface-variant)' }} />
  </button>
)

export default ChatInputArea
