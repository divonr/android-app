/**
 * ThoughtsBubble — matches ThoughtsBubble in MessageBubbles.kt.
 *
 * Design:
 *  - Primary 8% bg, primary 20% border, radius 12
 *  - Lightbulb icon + "מחשבות... (x.x שניות)" in primary
 *  - Expand / collapse for completed thoughts (PRESENT status)
 *  - During streaming: live elapsed timer + show text live, no collapse affordance
 */

import React, { useState, useEffect } from 'react'
import { MdLightbulb, MdExpandMore, MdExpandLess } from '../../ui/icons'

interface ThoughtsBubbleProps {
  text: string
  /** Thinking phase complete (onThinkingComplete fired) */
  done: boolean
  /** Overall stream is active and thinking is NOT yet done */
  activelyThinking: boolean
  /** Timestamp when thinking started, for live elapsed timer */
  startTime?: number | null
  /** Duration in seconds from onThinkingComplete event */
  durationSeconds?: number | null
}

const ThoughtsBubble: React.FC<ThoughtsBubbleProps> = ({
  text,
  done,
  activelyThinking,
  startTime = null,
  durationSeconds = null,
}) => {
  const [open, setOpen] = useState(false)
  const [elapsed, setElapsed] = useState(0)

  // Live elapsed timer during active thinking phase
  useEffect(() => {
    if (!activelyThinking || !startTime) return
    const update = () => setElapsed((Date.now() - startTime) / 1000)
    update()
    const id = setInterval(update, 100)
    return () => clearInterval(id)
  }, [activelyThinking, startTime])

  const displayDuration = activelyThinking && startTime
    ? elapsed
    : (durationSeconds ?? elapsed)

  const durationStr = displayDuration.toFixed(1)

  const isExpandable = done && !activelyThinking && !!text

  return (
    <div
      style={{
        marginBottom: 8,
        borderRadius: 12,
        border: '1px solid var(--primary-20)',
        background: 'var(--primary-08)',
        overflow: 'hidden',
      }}
    >
      {/* Header row */}
      <button
        type="button"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '10px 12px',
          background: 'none',
          border: 'none',
          cursor: isExpandable ? 'pointer' : 'default',
          width: '100%',
          textAlign: 'start',
        }}
        onClick={() => isExpandable && setOpen((o) => !o)}
        aria-expanded={isExpandable ? open : undefined}
      >
        <MdLightbulb size={20} style={{ color: 'var(--primary)', flexShrink: 0 }} />
        <span
          style={{
            fontSize: 14,
            fontWeight: 600,
            color: 'var(--primary)',
            flex: 1,
            textAlign: 'start',
            direction: 'rtl',
          }}
        >
          מחשבות... ({durationStr} שניות)
        </span>
        {isExpandable && (
          open
            ? <MdExpandLess size={18} style={{ color: 'var(--on-surface-variant)', flexShrink: 0 }} />
            : <MdExpandMore size={18} style={{ color: 'var(--on-surface-variant)', flexShrink: 0 }} />
        )}
      </button>

      {/* Expandable body */}
      {(activelyThinking && text) || (isExpandable && open) ? (
        <div
          style={{
            padding: '8px 12px 12px',
            fontSize: 13,
            color: 'var(--on-surface-variant)',
            whiteSpace: 'pre-wrap',
            borderTop: '1px solid var(--primary-20)',
            background: 'var(--surface-variant)',
            maxHeight: 240,
            overflowY: 'auto',
            direction: 'ltr', // always LTR per Android's CompositionLocalProvider
          }}
        >
          {text}
        </div>
      ) : null}
    </div>
  )
}

export default ThoughtsBubble
