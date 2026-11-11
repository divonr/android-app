/**
 * ui/Popup.tsx — Anchored dropdown / popup menu.
 *
 * Matches the Material3 dark DropdownMenu from the Android app
 * (surface bg, rounded-card corners, subtle shadow).
 *
 * Usage:
 *   <Popup anchor={<button>...</button>} open={show} onClose={() => setShow(false)}>
 *     <PopupItem label="Rename" onClick={...} />
 *   </Popup>
 *
 * The popup renders in a portal-like div appended to document.body so it
 * never clips inside overflow:hidden containers.
 */

import React, { useEffect, useRef, useState, useCallback } from 'react'
import ReactDOM from 'react-dom'

// ── PopupItem helper ──────────────────────────────────────────────────────────

interface PopupItemProps {
  label: string
  onClick?: () => void
  icon?: React.ReactNode
  danger?: boolean
  disabled?: boolean
}

export const PopupItem: React.FC<PopupItemProps> = ({
  label,
  onClick,
  icon,
  danger = false,
  disabled = false,
}) => (
  <button
    type="button"
    onClick={onClick}
    disabled={disabled}
    style={{
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      width: '100%',
      padding: '10px 16px',
      background: 'none',
      border: 'none',
      color: danger ? 'var(--accent-red)' : 'var(--on-surface)',
      fontSize: 'var(--fs-body-medium)',
      fontWeight: 'var(--fw-body-medium)',
      fontFamily: 'inherit',
      cursor: disabled ? 'not-allowed' : 'pointer',
      opacity: disabled ? 0.5 : 1,
      textAlign: 'start',
      borderRadius: 6,
      transition: 'background 0.1s',
    }}
    onMouseEnter={(e) => {
      if (!disabled) (e.currentTarget as HTMLElement).style.background = 'var(--surface-variant)'
    }}
    onMouseLeave={(e) => {
      (e.currentTarget as HTMLElement).style.background = 'none'
    }}
  >
    {icon && <span style={{ display: 'flex', opacity: 0.8 }}>{icon}</span>}
    {label}
  </button>
)

// ── Popup ─────────────────────────────────────────────────────────────────────

interface PopupProps {
  /** The trigger element — Popup positions itself relative to this */
  anchor: React.ReactNode
  open: boolean
  onClose: () => void
  children: React.ReactNode
  /** Min width of the popup panel (default 180px) */
  minWidth?: number
}

const Popup: React.FC<PopupProps> = ({
  anchor,
  open,
  onClose,
  children,
  minWidth = 180,
}) => {
  const anchorRef = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState({ top: 0, left: 0 })

  const updatePos = useCallback(() => {
    if (!anchorRef.current) return
    const rect = anchorRef.current.getBoundingClientRect()
    setPos({
      top: rect.bottom + window.scrollY + 4,
      left: rect.left + window.scrollX,
    })
  }, [])

  useEffect(() => {
    if (open) updatePos()
  }, [open, updatePos])

  useEffect(() => {
    if (!open) return
    const handle = () => onClose()
    document.addEventListener('mousedown', handle)
    return () => document.removeEventListener('mousedown', handle)
  }, [open, onClose])

  const panel = open
    ? ReactDOM.createPortal(
        <div
          onMouseDown={(e) => e.stopPropagation()}
          style={{
            position: 'absolute',
            top: pos.top,
            left: pos.left,
            minWidth,
            background: 'var(--surface)',
            borderRadius: 'var(--radius-card)',
            boxShadow: '0 4px 16px rgba(0,0,0,0.40)',
            padding: 4,
            zIndex: 9999,
          }}
        >
          {children}
        </div>,
        document.body,
      )
    : null

  return (
    <>
      <div ref={anchorRef} style={{ display: 'contents' }}>
        {anchor}
      </div>
      {panel}
    </>
  )
}

export default Popup
