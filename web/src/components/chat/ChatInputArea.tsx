/**
 * ChatInputArea — matches ChatInputArea.kt precisely.
 *
 * Layout:
 *  - Outer Surface: SurfaceVariant bg, elevation shadow, padding h:20 v:16
 *  - Inner pill: Surface bg (#1A1B26), radius 24, padding 4
 *  - Row: attach (+) | text field (flex 1) | [WebSearchToggle] | [SendButton / EditButtons]
 *  - ≥3 lines → stacked vertical layout: web search above send/edit
 *  - SelectedFilesPreview (single=list row, multi=thumbnail grid) above the pill
 *
 * Send button: 40px primary circle; streaming → spinner + stop icon
 * Edit mode: ConfirmEdit (check) + ConfirmAndResend (send) buttons
 */

import React, { useRef, useCallback } from 'react'
import {
  MdAdd,
  MdSend,
  MdStop,
  MdCheck,
  MdLanguage,
  MdClose,
  MdAttachFile,
} from '../../ui/icons'
import type { Attachment } from '../../api/types'
import { t } from '../../i18n/he'

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
  attachments: Attachment[]
  uploading: boolean
  onRemove: (idx: number) => void
}

const SelectedFilesPreview: React.FC<FilesPreviewProps> = ({ attachments, uploading, onRemove }) => {
  if (attachments.length === 0 && !uploading) return null

  if (attachments.length === 1) {
    // Single file: list row
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '6px 20px',
        }}
      >
        {attachments.map((att, i) => (
          <div
            key={i}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              background: 'var(--surface)',
              borderRadius: 8,
              padding: '6px 10px',
              border: '1px solid var(--color-border)',
              maxWidth: '100%',
              overflow: 'hidden',
            }}
          >
            <MdAttachFile size={16} style={{ color: 'var(--primary)', flexShrink: 0 }} />
            <span style={{ fontSize: 13, color: 'var(--color-text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
              {att.file_name}
            </span>
            <button
              type="button"
              onClick={() => onRemove(i)}
              style={{ background: 'none', border: 'none', color: 'var(--on-surface-variant)', cursor: 'pointer', padding: 0, display: 'flex' }}
              aria-label="Remove attachment"
            >
              <MdClose size={14} />
            </button>
          </div>
        ))}
        {uploading && (
          <span style={{ fontSize: 12, color: 'var(--on-surface-variant)' }}>מעלה...</span>
        )}
      </div>
    )
  }

  // Multiple files: thumbnail grid
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fill, minmax(80px, 1fr))',
        gap: 4,
        padding: '6px 20px',
        maxHeight: 160,
        overflowY: 'auto',
      }}
    >
      {attachments.map((att, i) => (
        <div
          key={i}
          style={{
            position: 'relative',
            background: 'var(--surface)',
            borderRadius: 8,
            border: '1px solid var(--color-border)',
            padding: 6,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 4,
            minHeight: 64,
          }}
        >
          <MdAttachFile size={24} style={{ color: 'var(--primary)' }} />
          <span style={{ fontSize: 10, color: 'var(--on-surface-variant)', textAlign: 'center', wordBreak: 'break-all', lineHeight: 1.2 }}>
            {att.file_name.length > 12 ? att.file_name.slice(0, 10) + '…' : att.file_name}
          </span>
          <button
            type="button"
            onClick={() => onRemove(i)}
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
          >
            <MdClose size={11} />
          </button>
        </div>
      ))}
      {uploading && (
        <div style={{ fontSize: 11, color: 'var(--on-surface-variant)', alignSelf: 'center' }}>מעלה...</div>
      )}
    </div>
  )
}

// ─── Main ChatInputArea ──────────────────────────────────────────────────────

interface ChatInputAreaProps {
  inputText: string
  onInputChange: (v: string) => void
  attachments: Attachment[]
  uploading: boolean
  onRemoveAttachment: (idx: number) => void
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
  uploading,
  onRemoveAttachment,
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

  const canSend = inputText.trim().length > 0 || attachments.length > 0

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
        uploading={uploading}
        onRemove={onRemoveAttachment}
      />

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
          {/* Attach (+) button */}
          <button
            type="button"
            onClick={onAttachFile}
            disabled={uploading}
            aria-label={t('attach_file')}
            title={t('attach_file')}
            style={{
              width: 34,
              height: 34,
              minWidth: 34,
              borderRadius: 12,
              background: attachments.length > 0 ? 'var(--primary-15)' : 'transparent',
              border: 'none',
              cursor: uploading ? 'not-allowed' : 'pointer',
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

          {/* Text field */}
          <textarea
            ref={textareaRef}
            placeholder={t('type_message')}
            value={inputText}
            onChange={(e) => {
              onInputChange(e.target.value)
              // Auto-resize
              e.target.style.height = 'auto'
              e.target.style.height = Math.min(e.target.scrollHeight, 150) + 'px'
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
