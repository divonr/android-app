/**
 * ui/IconButton.tsx — 36px rounded-square icon button.
 *
 * Mirrors the Surface+Box+Icon pattern from ChatTopBar.kt:
 *   Surface(shape=MaterialTheme.shapes.medium, color=SurfaceVariant, size=36dp)
 *   Box(contentAlignment=Center) { Icon(size=18dp) }
 *
 * `active` prop → primary-15 bg + primary icon tint (ThinkingBudgetButton / TemperatureButton).
 */

import React from 'react'
import type { IconType } from 'react-icons'

interface IconButtonProps {
  /** react-icons icon component */
  icon: IconType
  onClick?: () => void
  /** Active state: primary-15 bg + primary tint */
  active?: boolean
  /** Override the button container size (default 36px) */
  size?: number
  /** Override the icon size (default 18px) */
  iconSize?: number
  /** Accessible label (required for screen-readers) */
  'aria-label': string
  disabled?: boolean
  className?: string
  style?: React.CSSProperties
}

const IconButton: React.FC<IconButtonProps> = ({
  icon: Icon,
  onClick,
  active = false,
  size = 36,
  iconSize = 18,
  'aria-label': ariaLabel,
  disabled = false,
  className,
  style,
}) => {
  const bg = active
    ? 'var(--primary-15)'
    : 'var(--surface-variant)'
  const color = active ? 'var(--primary)' : 'var(--on-surface-variant)'

  return (
    <button
      type="button"
      aria-label={ariaLabel}
      onClick={onClick}
      disabled={disabled}
      className={className}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: size,
        height: size,
        minWidth: size,
        minHeight: size,
        borderRadius: 'var(--radius-icon-btn)',
        background: bg,
        border: 'none',
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
        padding: 0,
        color,
        transition: 'background 0.15s, color 0.15s',
        ...style,
      }}
    >
      <Icon size={iconSize} />
    </button>
  )
}

export default IconButton
