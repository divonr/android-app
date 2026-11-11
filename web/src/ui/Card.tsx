/**
 * ui/Card.tsx — Convenience wrapper around Surface with card-level defaults.
 * Used for dialogs, list items, settings sections, etc.
 */

import React from 'react'
import Surface from './Surface'

interface CardProps {
  children: React.ReactNode
  elevation?: 0 | 1 | 2 | 3 | 4
  /** 'surface' (default) | 'surface-variant' | custom hex */
  bg?: string
  className?: string
  style?: React.CSSProperties
  onClick?: () => void
}

const Card: React.FC<CardProps> = ({
  children,
  elevation = 1,
  bg,
  className,
  style,
  onClick,
}) => (
  <Surface
    radius="card"
    elevation={elevation}
    bg={bg}
    className={className}
    style={{ padding: '1rem', ...style }}
    onClick={onClick}
  >
    {children}
  </Surface>
)

export default Card
