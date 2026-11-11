/**
 * ToolCallBlock — inline tool_call + tool_result rendering per ToolCallComponents.kt.
 *
 * Design:
 *  - Primary 10% bg + primary 20% border
 *  - Extension icon in primary-15 circle (40px)
 *  - Tool name in primary (semibold) + status indicator
 *  - Expand/collapse for params + result
 *  - Result bg: green-10% for success, red-10% for failure
 */

import React, { useState } from 'react'
import { MdExtension, MdExpandMore, MdExpandLess } from '../../ui/icons'

interface ToolCallBlockProps {
  toolName: string
  parameters: Record<string, unknown>
  result?: string
  success?: boolean
  /** Tool is currently being executed (streaming) */
  executing?: boolean
}

const ToolCallBlock: React.FC<ToolCallBlockProps> = ({
  toolName,
  parameters,
  result,
  success,
  executing = false,
}) => {
  const [open, setOpen] = useState(false)

  const statusText = executing
    ? 'מבצע...'
    : result !== undefined
      ? success ? '✅ הושלם' : '❌ נכשל'
      : ''

  const statusColor = executing
    ? 'var(--on-surface-variant)'
    : result !== undefined
      ? success ? 'var(--color-success)' : 'var(--color-error)'
      : 'var(--on-surface-variant)'

  return (
    <div
      style={{
        marginBottom: 8,
        borderRadius: '6px 20px 20px 20px',
        border: '1px solid var(--primary-20)',
        background: 'rgba(108,124,231,0.08)',
        overflow: 'hidden',
        maxWidth: 320,
      }}
    >
      {/* Header */}
      <button
        type="button"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          padding: 14,
          background: 'none',
          border: 'none',
          cursor: 'pointer',
          width: '100%',
          textAlign: 'start',
        }}
        onClick={() => setOpen((o) => !o)}
      >
        {/* Tool icon */}
        <div
          style={{
            width: 40,
            height: 40,
            minWidth: 40,
            borderRadius: 12,
            background: 'var(--primary-15)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <MdExtension size={20} style={{ color: 'var(--primary)' }} />
        </div>

        {/* Name + status */}
        <div style={{ flex: 1, textAlign: 'start', overflow: 'hidden' }}>
          <div
            style={{
              fontSize: 14,
              fontWeight: 600,
              color: 'var(--primary)',
              direction: 'ltr',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {toolName}
          </div>
          {statusText && (
            <div style={{ fontSize: 12, color: statusColor, marginTop: 2 }}>
              {statusText}
            </div>
          )}
        </div>

        {/* Expand toggle */}
        <div
          style={{
            width: 32,
            height: 32,
            borderRadius: 8,
            background: open ? 'var(--primary-15)' : 'transparent',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
          }}
        >
          {open
            ? <MdExpandLess size={18} style={{ color: 'var(--on-surface-variant)' }} />
            : <MdExpandMore size={18} style={{ color: 'var(--on-surface-variant)' }} />}
        </div>
      </button>

      {/* Summary chip (always visible) */}
      <div
        style={{
          margin: '0 14px 10px',
          padding: '8px 12px',
          fontSize: 14,
          color: 'var(--color-text)',
          background: 'var(--surface)',
          borderRadius: 8,
          direction: 'ltr',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {toolName}
      </div>

      {/* Expanded details */}
      {open && (
        <div style={{ padding: '0 14px 14px' }}>
          {Object.keys(parameters).length > 0 && (
            <div style={{ marginBottom: 10 }}>
              <div
                style={{
                  fontSize: 11,
                  fontWeight: 600,
                  color: 'var(--on-surface-variant)',
                  textTransform: 'uppercase',
                  letterSpacing: '0.05em',
                  marginBottom: 6,
                  direction: 'ltr',
                }}
              >
                Parameters
              </div>
              <pre
                style={{
                  background: 'var(--surface-variant)',
                  borderRadius: 8,
                  padding: 12,
                  fontSize: 12,
                  fontFamily: 'var(--font-mono)',
                  color: 'var(--color-text)',
                  overflowX: 'auto',
                  whiteSpace: 'pre-wrap',
                  margin: 0,
                  direction: 'ltr',
                  textAlign: 'left',
                }}
              >
                {JSON.stringify(parameters, null, 2)}
              </pre>
            </div>
          )}

          {result !== undefined && (
            <div>
              <div
                style={{
                  fontSize: 11,
                  fontWeight: 600,
                  color: 'var(--on-surface-variant)',
                  textTransform: 'uppercase',
                  letterSpacing: '0.05em',
                  marginBottom: 6,
                  direction: 'ltr',
                }}
              >
                Result
              </div>
              <div
                style={{
                  background: success
                    ? 'rgba(88,214,141,0.10)'
                    : 'rgba(236,112,99,0.10)',
                  borderRadius: 8,
                  padding: 12,
                  fontSize: 13,
                  color: 'var(--color-text)',
                  direction: 'ltr',
                  textAlign: 'left',
                  maxHeight: 200,
                  overflowY: 'auto',
                  whiteSpace: 'pre-wrap',
                }}
              >
                {result}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export default ToolCallBlock
