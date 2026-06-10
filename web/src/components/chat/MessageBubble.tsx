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
 * Context menu: long-press (~500ms) + right-click on the bubble.
 * Replicates the long-press pattern from ChatHistoryPage (R1).
 * Menu items: Copy, Edit (user), Regenerate (asst), Delete from here.
 * ThoughtsBubble and ToolCallBlocks rendered inside assistant bubble.
 * BranchNavigator below user messages with multiple variants.
 */

import React, { useState, useEffect, useRef, useMemo } from 'react'
import ReactDOM from 'react-dom'
import Markdown from '../Markdown'
import ThoughtsBubble from './ThoughtsBubble'
import ToolCallBlock from './ToolCallBlock'
import BranchNavigator from './BranchNavigator'
import { MdAttachFile, MdContentCopy, MdEdit, MdRefresh, MdDelete } from '../../ui/icons'
import { getModelInitial, getModelLogoPath } from '../../utils/chatUtils'
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

// ─── Context menu (portaled, positioned at pointer coordinates) ───────────────

interface ContextMenuProps {
  x: number
  y: number
  isUser: boolean
  onCopy: () => void
  onEdit?: () => void
  onRegenerate?: () => void
  onDelete: () => void
  onClose: () => void
}

const ContextMenu: React.FC<ContextMenuProps> = ({
  x, y, isUser, onCopy, onEdit, onRegenerate, onDelete, onClose,
}) => {
  const menuRef = useRef<HTMLDivElement>(null)

  // Adjust position so menu stays within viewport
  const [pos, setPos] = useState({ top: y, left: x })

  useEffect(() => {
    if (menuRef.current) {
      const { width, height } = menuRef.current.getBoundingClientRect()
      const vw = window.innerWidth
      const vh = window.innerHeight
      setPos({
        top: Math.min(y, vh - height - 8),
        left: Math.min(x, vw - width - 8),
      })
    }
  }, [x, y])

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose()
      }
    }
    const escHandler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    // Delay slightly so the pointerdown that opened us doesn't immediately close
    const t = setTimeout(() => {
      window.addEventListener('mousedown', handler)
      window.addEventListener('keydown', escHandler)
    }, 80)
    return () => {
      clearTimeout(t)
      window.removeEventListener('mousedown', handler)
      window.removeEventListener('keydown', escHandler)
    }
  }, [onClose])

  const itemStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    width: '100%',
    padding: '10px 16px',
    background: 'none',
    border: 'none',
    textAlign: 'start',
    color: 'var(--color-text)',
    cursor: 'pointer',
    fontSize: 14,
    whiteSpace: 'nowrap',
    transition: 'background 0.1s',
  }

  const menuNode = (
    <div
      ref={menuRef}
      role="menu"
      style={{
        position: 'fixed',
        top: pos.top,
        left: pos.left,
        zIndex: 400,
        background: 'var(--surface)',
        border: '1px solid var(--color-border)',
        borderRadius: 12,
        boxShadow: '0 4px 24px rgba(0,0,0,0.5)',
        minWidth: 170,
        overflow: 'hidden',
      }}
    >
      <button
        type="button"
        role="menuitem"
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
          role="menuitem"
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
          role="menuitem"
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
        role="menuitem"
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

  return ReactDOM.createPortal(menuNode, document.body)
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
  const [menuPos, setMenuPos] = useState<{ x: number; y: number } | null>(null)

  // Long-press timer (500ms) + right-click open the context menu
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const startPosRef = useRef({ x: 0, y: 0 })

  const cancelTimer = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }

  const handlePointerDown = (e: React.PointerEvent) => {
    // Only primary button (left/touch)
    if (e.button !== 0 && e.pointerType === 'mouse') return
    startPosRef.current = { x: e.clientX, y: e.clientY }
    timerRef.current = setTimeout(() => {
      setMenuPos({ x: e.clientX, y: e.clientY })
    }, 500)
  }

  const handlePointerMove = (e: React.PointerEvent) => {
    const dx = Math.abs(e.clientX - startPosRef.current.x)
    const dy = Math.abs(e.clientY - startPosRef.current.y)
    if (dx > 8 || dy > 8) cancelTimer()
  }

  const handleContextMenu = (e: React.MouseEvent) => {
    e.preventDefault()
    cancelTimer()
    setMenuPos({ x: e.clientX, y: e.clientY })
  }

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
  const modelLogoPath = getModelLogoPath(msg.model)

  // ── Bubble colors & layout ──

  // Mirrors MessageBubbles.kt in the app's RTL layout:
  //   user      → Alignment.End   = LEFT side  → flex-end in RTL
  //   assistant → Alignment.Start = RIGHT side → flex-start in RTL
  const wrapAlign: React.CSSProperties['alignItems'] = isUser ? 'flex-end' : 'flex-start'

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
    // Android: topStart=6 for assistant (RIGHT in RTL → top-right),
    //          topEnd=6 for user (LEFT in RTL → top-left)
    ...(isUser
      ? { borderTopLeftRadius: 6 }
      : isSystem
        ? {}
        : { borderTopRightRadius: 6 }),
    // The row below is full-width, so the % here resolves against real space
    // (a % against a shrink-to-fit parent caused absurdly narrow bubbles).
    maxWidth: 'min(320px, 88%)',
    overflowWrap: 'break-word',
    minWidth: 0,
    position: 'relative',
    // Subtle press feedback for long-press affordance
    cursor: 'default',
    userSelect: 'none',
    WebkitUserSelect: 'none',
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
      {/* Avatar row + bubble row (full width, like Android's Row(fillMaxWidth)) */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'row',
          alignItems: 'flex-end',
          gap: 8,
          width: '100%',
          // Row in RTL: flex-start = RIGHT (assistant side), flex-end = LEFT (user side)
          justifyContent: isUser ? 'flex-end' : 'flex-start',
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
              overflow: 'hidden',
            }}
          >
            {modelLogoPath ? (
              <img
                src={modelLogoPath}
                alt="Model Logo"
                style={{ width: '100%', height: '100%', objectFit: 'cover' }}
              />
            ) : (
              modelInitial
            )}
          </div>
        )}

        {/* Bubble — long-press + right-click opens context menu */}
        <div
          style={bubbleStyle}
          onPointerDown={handlePointerDown}
          onPointerUp={cancelTimer}
          onPointerLeave={cancelTimer}
          onPointerMove={handlePointerMove}
          onContextMenu={handleContextMenu}
          aria-label="Message actions"
        >
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
              /* Android: fontSize 15.sp, lineHeight 18.sp */
              <span style={{ whiteSpace: 'pre-wrap', fontSize: 15, lineHeight: '18px' }}>
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

          {/* Timestamp footer */}
          {timeStr && (
            <div
              style={{
                marginTop: 4,
                fontSize: 11,
                color: isUser ? 'rgba(255,255,255,0.7)' : 'var(--on-surface-variant)',
                textAlign: isUser ? 'end' : 'start',
              }}
            >
              {timeStr}
            </div>
          )}
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

      {/* Context menu (portaled, long-press or right-click) */}
      {menuPos && (
        <ContextMenu
          x={menuPos.x}
          y={menuPos.y}
          isUser={isUser}
          onCopy={() => onCopy(msg.text)}
          onEdit={isUser ? () => onEdit(msg) : undefined}
          onRegenerate={!isUser ? () => onRegenerate(msg) : undefined}
          onDelete={() => onDelete(msg.id)}
          onClose={() => setMenuPos(null)}
        />
      )}
    </div>
  )
}

export default MessageBubble
