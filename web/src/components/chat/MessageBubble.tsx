/**
 * MessageBubble — matches MessageBubbles.kt exactly.
 *
 * User bubble:   #6C7CE7 bg, white text, RIGHT side in RTL, avatar-less
 * Assistant:     #2A2B3A bg, onSurface text, LEFT side in RTL, model-initial avatar
 * System:        #3A3B4A bg
 *
 * Border radius (follows topStart/topEnd logic from Android, in RTL context):
 *  - User (RIGHT in RTL):  all 20px except border-top-left-radius: 6px (topEnd in RTL)
 *  - Assistant (LEFT in RTL): all 20px except border-top-right-radius: 6px (topStart in RTL)
 *
 * Context menu (right-click / ⋯ button): Copy, Edit (user), Regenerate (asst), Delete from here
 * ThoughtsBubble and ToolCallBlocks rendered inside assistant bubble.
 * BranchNavigator below user messages with multiple variants.
 */

import React, { useState, useEffect, useRef, useMemo } from 'react'
import Markdown from '../Markdown'
import ThoughtsBubble from './ThoughtsBubble'
import ToolCallBlock from './ToolCallBlock'
import BranchNavigator from './BranchNavigator'
import { MdAttachFile, MdContentCopy, MdEdit, MdRefresh, MdDelete } from '../../ui/icons'
import { getModelInitial } from '../../utils/chatUtils'
import { t } from '../../i18n/he'
import type { Chat, Message, BranchInfo } from '../../api/types'
import type { TextDirectionMode } from './QuickSettingsBar'

// ─── Direction detection ─────────────────────────────────────────────────────

function resolveDir(text: string, mode: TextDirectionMode): 'rtl' | 'ltr' | 'auto' {
  if (mode === 'RTL') return 'rtl'
  if (mode === 'LTR') return 'ltr'
  // AUTO: detect from first 50 chars
  const rtlRe = /[֐-׿؀-ۿ܀-ݏ‏]/
  return rtlRe.test(text.slice(0, 50)) ? 'rtl' : 'ltr'
}

function formatTime(datetime: string | null | undefined): string {
  if (!datetime) return ''
  const d = new Date(datetime)
  if (isNaN(d.getTime())) return ''
  return d.toLocaleTimeString('he-IL', { hour: '2-digit', minute: '2-digit' })
}

// ─── Context menu ─────────────────────────────────────────────────────────────

interface ContextMenuProps {
  isUser: boolean
  onCopy: () => void
  onEdit?: () => void
  onRegenerate?: () => void
  onDelete: () => void
  onClose: () => void
  anchorRef: React.RefObject<HTMLDivElement | null>
}

const ContextMenu: React.FC<ContextMenuProps> = ({
  isUser, onCopy, onEdit, onRegenerate, onDelete, onClose, anchorRef,
}) => {
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (
        menuRef.current && !menuRef.current.contains(e.target as Node) &&
        anchorRef.current && !anchorRef.current.contains(e.target as Node)
      ) {
        onClose()
      }
    }
    window.addEventListener('mousedown', handler)
    return () => window.removeEventListener('mousedown', handler)
  }, [onClose, anchorRef])

  const itemStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    width: '100%',
    padding: '9px 14px',
    background: 'none',
    border: 'none',
    textAlign: 'start',
    color: 'var(--color-text)',
    cursor: 'pointer',
    fontSize: 14,
    transition: 'background 0.1s',
  }

  return (
    <div
      ref={menuRef}
      style={{
        position: 'absolute',
        bottom: 'calc(100% + 4px)',
        insetInlineEnd: 0,
        zIndex: 300,
        background: 'var(--surface)',
        border: '1px solid var(--color-border)',
        borderRadius: 10,
        boxShadow: '0 4px 20px rgba(0,0,0,0.4)',
        minWidth: 160,
        overflow: 'hidden',
      }}
    >
      <button
        type="button"
        style={itemStyle}
        onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-variant)')}
        onMouseLeave={e => (e.currentTarget.style.background = 'none')}
        onClick={() => { onCopy(); onClose() }}
      >
        <MdContentCopy size={16} />
        {t('copy')}
      </button>

      {isUser && onEdit && (
        <button
          type="button"
          style={itemStyle}
          onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-variant)')}
          onMouseLeave={e => (e.currentTarget.style.background = 'none')}
          onClick={() => { onEdit(); onClose() }}
        >
          <MdEdit size={16} />
          {t('edit')}
        </button>
      )}

      {!isUser && onRegenerate && (
        <button
          type="button"
          style={itemStyle}
          onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-variant)')}
          onMouseLeave={e => (e.currentTarget.style.background = 'none')}
          onClick={() => { onRegenerate(); onClose() }}
        >
          <MdRefresh size={16} />
          {t('regenerate')}
        </button>
      )}

      <button
        type="button"
        style={{ ...itemStyle, color: 'var(--color-error)' }}
        onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-variant)')}
        onMouseLeave={e => (e.currentTarget.style.background = 'none')}
        onClick={() => { onDelete(); onClose() }}
      >
        <MdDelete size={16} />
        {t('delete_from_here')}
      </button>
    </div>
  )
}

// ─── MessageBubble ────────────────────────────────────────────────────────────

interface MessageBubbleProps {
  msg: Message
  chat: Chat
  textDirectionMode: TextDirectionMode
  onEdit: (msg: Message) => void
  onCopy: (text: string) => void
  onDelete: (msgId: string) => void
  onRegenerate: (msg: Message) => void
  onBranchSwitch: (chat: Chat) => void
}

const MessageBubble: React.FC<MessageBubbleProps> = ({
  msg, chat, textDirectionMode,
  onEdit, onCopy, onDelete, onRegenerate, onBranchSwitch,
}) => {
  const [menuOpen, setMenuOpen] = useState(false)
  const menuAnchorRef = useRef<HTMLDivElement>(null)

  const isUser = msg.role === 'user'
  const isSystem = msg.role === 'system'
  const isToolCall = msg.role === 'tool_call'
  const isToolResponse = msg.role === 'tool_response'

  // Branch info for user messages
  const branchInfo = useMemo((): BranchInfo | null => {
    if (!isUser || !msg.nodeId) return null
    for (const node of chat.messageNodes ?? []) {
      if (node.nodeId === msg.nodeId && node.variants.length > 1) {
        const idx = node.variants.findIndex(
          (v) => v.variantId === msg.variantId || v.userMessage.id === msg.id,
        )
        return {
          nodeId: node.nodeId,
          currentVariantIndex: idx >= 0 ? idx : 0,
          totalVariants: node.variants.length,
          currentVariantId: msg.variantId ?? node.variants[0].variantId,
        }
      }
    }
    return null
  }, [chat, msg, isUser])

  // Tool call / tool response → ToolCallBlock rendering
  if (isToolCall && msg.toolCall) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', padding: '2px 12px' }}>
        <ToolCallBlock
          toolName={msg.toolCall.toolName}
          parameters={msg.toolCall.parameters}
        />
      </div>
    )
  }

  if (isToolResponse) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', padding: '2px 12px' }}>
        <ToolCallBlock
          toolName="Tool Result"
          parameters={{}}
          result={msg.toolResponseOutput ?? msg.text}
          success
        />
      </div>
    )
  }

  const dir = resolveDir(msg.text, textDirectionMode)
  const timeStr = formatTime(msg.datetime)
  const modelInitial = getModelInitial(msg.model)

  // ── Bubble colors & layout ──

  // In RTL html context (dir="rtl"):
  //   flex-start on cross-axis = RIGHT side
  //   flex-end on cross-axis   = LEFT side
  // User messages appear on RIGHT (flex-start), assistant on LEFT (flex-end)
  const wrapAlign: React.CSSProperties['alignItems'] = isUser ? 'flex-start' : 'flex-end'

  const bubbleBg = isSystem
    ? '#3A3B4A'
    : isUser
      ? '#6C7CE7'
      : '#2A2B3A'

  const bubbleStyle: React.CSSProperties = {
    background: bubbleBg,
    color: isUser ? '#ffffff' : 'var(--color-text)',
    padding: '12px 16px',
    borderRadius: 20,
    // Notch corner: user on RIGHT → sharp top-left; assistant on LEFT → sharp top-right
    ...(isUser
      ? { borderTopLeftRadius: 6 }
      : isSystem
        ? {}
        : { borderTopRightRadius: 6 }),
    maxWidth: 'min(320px, 88%)',
    wordBreak: 'break-word',
    position: 'relative',
  }

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: wrapAlign,
        padding: '4px 8px',
      }}
    >
      {/* Avatar row + bubble row */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'row',
          alignItems: 'flex-end',
          gap: 8,
          // Assistant bubbles: avatar to the right of bubble in RTL
          // (Row in RTL: first child goes right, second goes left)
          justifyContent: 'flex-end',
        }}
      >
        {/* Model avatar — for assistant, appears to the "start" of bubble (rightward in RTL) */}
        {!isUser && !isSystem && msg.model && (
          <div
            style={{
              width: 32,
              height: 32,
              minWidth: 32,
              borderRadius: '50%',
              background: 'var(--primary-15)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 13,
              fontWeight: 700,
              color: 'var(--primary)',
              flexShrink: 0,
            }}
          >
            {modelInitial}
          </div>
        )}

        {/* Bubble */}
        <div style={bubbleStyle}>
          {/* Model name label (assistant only, like WhatsApp sender name) */}
          {!isUser && !isSystem && msg.model && (
            <div
              style={{
                fontSize: 12,
                fontWeight: 600,
                color: 'var(--primary)',
                marginBottom: 6,
              }}
            >
              {msg.model}
            </div>
          )}

          {/* ThoughtsBubble for completed thoughts */}
          {!isUser && msg.thoughtsStatus === 'PRESENT' && msg.thoughts && (
            <ThoughtsBubble
              text={msg.thoughts}
              done
              activelyThinking={false}
              durationSeconds={msg.thinkingDurationSeconds ?? null}
            />
          )}

          {/* Message content */}
          <div dir={dir}>
            {isUser ? (
              <span style={{ whiteSpace: 'pre-wrap', fontSize: 15, lineHeight: '22px' }}>
                {msg.text}
              </span>
            ) : (
              <Markdown content={msg.text} />
            )}
          </div>

          {/* Attachments */}
          {msg.attachments && msg.attachments.length > 0 && (
            <div
              style={{
                marginTop: 8,
                display: 'flex',
                flexWrap: 'wrap',
                gap: 6,
              }}
            >
              {msg.attachments.map((att, i) => (
                <div
                  key={i}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 4,
                    background: isUser ? 'rgba(255,255,255,0.15)' : 'var(--surface-variant)',
                    borderRadius: 6,
                    padding: '4px 8px',
                    fontSize: 12,
                    color: isUser ? 'rgba(255,255,255,0.9)' : 'var(--on-surface-variant)',
                  }}
                >
                  <MdAttachFile size={13} />
                  <span>{att.file_name}</span>
                </div>
              ))}
            </div>
          )}

          {/* Footer: timestamp + menu */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              marginTop: 6,
              justifyContent: isUser ? 'flex-end' : 'flex-start',
            }}
          >
            {timeStr && (
              <span
                style={{
                  fontSize: 11,
                  color: isUser ? 'rgba(255,255,255,0.7)' : 'var(--on-surface-variant)',
                }}
              >
                {timeStr}
              </span>
            )}

            {/* ⋯ context menu anchor */}
            <div ref={menuAnchorRef} style={{ position: 'relative', marginInlineStart: 'auto' }}>
              <button
                type="button"
                onClick={() => setMenuOpen((o) => !o)}
                style={{
                  background: 'none',
                  border: 'none',
                  color: isUser ? 'rgba(255,255,255,0.6)' : 'var(--on-surface-variant)',
                  cursor: 'pointer',
                  padding: '0 2px',
                  fontSize: 16,
                  lineHeight: 1,
                  opacity: 0,
                  transition: 'opacity 0.15s',
                }}
                onMouseEnter={e => { (e.currentTarget.style.opacity = '1') }}
                onMouseLeave={e => { if (!menuOpen) e.currentTarget.style.opacity = '0' }}
                aria-label="Message actions"
              >
                ⋯
              </button>
              {menuOpen && (
                <ContextMenu
                  isUser={isUser}
                  onCopy={() => onCopy(msg.text)}
                  onEdit={isUser ? () => onEdit(msg) : undefined}
                  onRegenerate={!isUser ? () => onRegenerate(msg) : undefined}
                  onDelete={() => onDelete(msg.id)}
                  onClose={() => setMenuOpen(false)}
                  anchorRef={menuAnchorRef}
                />
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Branch navigator below user messages */}
      {isUser && branchInfo && branchInfo.totalVariants > 1 && (
        <BranchNavigator
          chatId={chat.chat_id}
          nodeId={branchInfo.nodeId}
          totalVariants={branchInfo.totalVariants}
          currentVariantIndex={branchInfo.currentVariantIndex}
          onSwitch={onBranchSwitch}
        />
      )}
    </div>
  )
}

export default MessageBubble
