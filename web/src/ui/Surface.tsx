/**
 * ui/Surface.tsx — Background + border-radius wrapper (mirrors Compose Surface).
 *
 * Defaults to --surface bg with --radius-card radius.
 * Pass `variant` for --surface-variant, or `bg` for a fully custom color.
 * Pass `elevation` (1–4) to add a drop-shadow.
 */

import React from 'react'

type SurfaceVariant = 'surface' | 'surface-variant' | 'bg'

interface SurfaceProps {
  children: React.ReactNode
  /** Which CSS variable to use for background */
  variant?: SurfaceVariant
  /** Fully override the background color */
  bg?: string
  /** Radius token — 'card'(12) | 'bubble'(18) | 'pill'(24) | 'icon-btn'(11) | px number */
  radius?: 'card' | 'bubble' | 'pill' | 'icon-btn' | number
  /** Drop-shadow level 0–4 */
  elevation?: 0 | 1 | 2 | 3 | 4
  className?: string
  style?: React.CSSProperties
  onClick?: () => void
  as?: keyof JSX.IntrinsicElements
}

const RADIUS_MAP: Record<string, string> = {
  card:      'var(--radius-card)',
  bubble:    'var(--radius-bubble)',
  pill:      'var(--radius-pill)',
  'icon-btn':'var(--radius-icon-btn)',
}

const SHADOW_MAP: Record<number, string> = {
  0: 'none',
  1: '0 1px 4px rgba(0,0,0,0.30)',
  2: '0 2px 8px rgba(0,0,0,0.35)',
  3: '0 4px 16px rgba(0,0,0,0.40)',
  4: '0 8px 32px rgba(0,0,0,0.45)',
}

const Surface: React.FC<SurfaceProps> = ({
  children,
  variant = 'surface',
  bg,
  radius = 'card',
  elevation = 0,
  className,
  style,
  onClick,
  as: Tag = 'div',
}) => {
  const background = bg ?? `var(--${variant})`
  const borderRadius =
    typeof radius === 'number' ? `${radius}px` : (RADIUS_MAP[radius] ?? 'var(--radius-card)')
  const boxShadow = SHADOW_MAP[elevation] ?? 'none'

  return (
    <Tag
      className={className}
      onClick={onClick}
      style={{
        background,
        borderRadius,
        boxShadow,
        ...style,
      }}
    >
      {children}
    </Tag>
  )
}

export default Surface
