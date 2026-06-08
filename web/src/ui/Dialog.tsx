/**
 * ui/Dialog.tsx — Modal dialog (matches Material3 AlertDialog dark look).
 *
 * Surface bg, rounded-card corners, backdrop overlay, focus-trap via portal.
 * Matches dialogs in ChatHistoryScreenDialogs.kt / ApiKeyDialogs.kt etc.
 *
 * Usage:
 *   <Dialog
 *     open={showDialog}
 *     onClose={() => setShowDialog(false)}
 *     title="מחיקת שיחה"
 *     actions={<><button onClick={onCancel}>ביטול</button><button onClick={onConfirm}>מחק</button></>}
 *   >
 *     <p>השיחה תימחק...</p>
 *   </Dialog>
 */

import React, { useEffect } from 'react'
import ReactDOM from 'react-dom'

interface DialogProps {
  open: boolean
  onClose?: () => void
  title?: string
  children?: React.ReactNode
  /** Action buttons rendered in the footer row */
  actions?: React.ReactNode
  /** Override max-width (default 400px) */
  maxWidth?: number
}

const Dialog: React.FC<DialogProps> = ({
  open,
  onClose,
  title,
  children,
  actions,
  maxWidth = 400,
}) => {
  // Close on Escape
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose?.()
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [open, onClose])

  if (!open) return null

  return ReactDOM.createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-label={title}
      style={{
        position: 'fixed',
        inset: 0,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '1rem',
        zIndex: 10000,
      }}
    >
      {/* Backdrop */}
      <div
        aria-hidden="true"
        onClick={onClose}
        style={{
          position: 'absolute',
          inset: 0,
          background: 'rgba(0,0,0,0.6)',
        }}
      />

      {/* Panel */}
      <div
        style={{
          position: 'relative',
          width: '100%',
          maxWidth,
          background: 'var(--surface)',
          borderRadius: 'var(--radius-card)',
          boxShadow: '0 8px 32px rgba(0,0,0,0.50)',
          display: 'flex',
          flexDirection: 'column',
          gap: 0,
        }}
      >
        {title && (
          <div
            style={{
              padding: '20px 24px 12px',
              fontSize: 'var(--fs-title-small)',
              fontWeight: 'var(--fw-title-small)',
              color: 'var(--on-surface)',
            }}
          >
            {title}
          </div>
        )}

        {children && (
          <div
            style={{
              padding: title ? '0 24px 16px' : '20px 24px 16px',
              fontSize: 'var(--fs-body-medium)',
              color: 'var(--on-surface-variant)',
              lineHeight: 1.5,
            }}
          >
            {children}
          </div>
        )}

        {actions && (
          <div
            style={{
              display: 'flex',
              justifyContent: 'flex-end',
              gap: 8,
              padding: '8px 16px 16px',
            }}
          >
            {actions}
          </div>
        )}
      </div>
    </div>,
    document.body,
  )
}

// ── Convenience action button variants ───────────────────────────────────────

interface DialogButtonProps {
  label: string
  onClick?: () => void
  primary?: boolean
  danger?: boolean
  disabled?: boolean
}

export const DialogButton: React.FC<DialogButtonProps> = ({
  label,
  onClick,
  primary = false,
  danger = false,
  disabled = false,
}) => {
  const bg = primary
    ? 'var(--primary)'
    : danger
    ? 'var(--accent-red)'
    : 'transparent'
  const color = primary || danger ? '#fff' : 'var(--on-surface-variant)'
  const border = primary || danger ? 'none' : '1px solid var(--message-border)'

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      style={{
        padding: '8px 20px',
        borderRadius: 8,
        background: bg,
        color,
        border,
        fontSize: 'var(--fs-body-medium)',
        fontWeight: 500,
        fontFamily: 'inherit',
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
        transition: 'opacity 0.15s',
      }}
    >
      {label}
    </button>
  )
}

export default Dialog
