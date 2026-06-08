/**
 * ui/RoundButton.tsx — 40px circular primary action button.
 *
 * Mirrors SendButton / ConfirmEditButton / WebSearchToggle from ChatInputArea.kt:
 *   Surface(shape=RoundedCornerShape(20.dp), color=if(enabled) Primary else Primary.copy(alpha=0.3f), size=40dp)
 *   Box(Center) { Icon(size=18dp, tint=White) }
 *
 * `loading` renders a spinner ring + optional inner icon.
 */

import React from 'react'
import type { IconType } from 'react-icons'

interface RoundButtonProps {
  /** react-icons icon component */
  icon: IconType
  onClick?: () => void
  disabled?: boolean
  /** Show a circular progress spinner (streaming state) */
  loading?: boolean
  /** Override button size (default 40px) */
  size?: number
  /** Override icon size (default 18px) */
  iconSize?: number
  /** Override bg color when enabled (default --primary) */
  color?: string
  'aria-label': string
  className?: string
  style?: React.CSSProperties
}

const RoundButton: React.FC<RoundButtonProps> = ({
  icon: Icon,
  onClick,
  disabled = false,
  loading = false,
  size = 40,
  iconSize = 18,
  color = 'var(--primary)',
  'aria-label': ariaLabel,
  className,
  style,
}) => {
  const isDisabled = disabled && !loading
  const bg = isDisabled ? 'var(--primary-30)' : color

  return (
    <button
      type="button"
      aria-label={ariaLabel}
      onClick={onClick}
      disabled={isDisabled}
      className={className}
      style={{
        position: 'relative',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: size,
        height: size,
        minWidth: size,
        minHeight: size,
        borderRadius: '50%',
        background: bg,
        border: 'none',
        cursor: isDisabled ? 'not-allowed' : 'pointer',
        padding: 0,
        color: '#ffffff',
        transition: 'background 0.15s',
        ...style,
      }}
    >
      {loading && (
        <span
          aria-hidden="true"
          style={{
            position: 'absolute',
            inset: 2,
            borderRadius: '50%',
            border: '2px solid rgba(255,255,255,0.8)',
            borderTopColor: 'transparent',
            animation: 'spin 0.75s linear infinite',
          }}
        />
      )}
      <Icon size={iconSize} style={{ position: 'relative', zIndex: 1 }} />
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </button>
  )
}

export default RoundButton
