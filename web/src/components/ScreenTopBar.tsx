/**
 * ScreenTopBar — shared top-bar for secondary screens.
 *
 * Mirrors the Surface + Row + IconButton + Title pattern from Android screens:
 *   Surface(shadowElevation=1dp) {
 *     Row(padding=20dp/16dp) {
 *       Surface(RoundedCorner=12dp, size=40dp) { Icon(ArrowBack) }
 *       Text(headlineSmall, SemiBold)
 *     }
 *   }
 *
 * Usage:
 *   <ScreenTopBar title="הגדרות מתקדמות" onBack={() => navigate(-1)} />
 *
 * Screens that use it: SettingsPage (R5), ChildLockPage (R5).
 * KeysPage (R4) already has an inline top bar — migrate it in R7 if clean.
 */

import React from 'react'
import IconButton from '../ui/IconButton'
import { MdArrowBack } from '../ui/icons'

interface ScreenTopBarProps {
  /** Hebrew title shown next to the back button */
  title: string
  /** Called when the back arrow is pressed */
  onBack: () => void
  /**
   * Optional English aria-label for the heading element (used by tests that
   * match /settings/i, /keys/i, etc. while the visible text is Hebrew).
   */
  headingAriaLabel?: string
  /**
   * Optional trailing action elements (e.g. a project-mode toggle).
   * Rendered at the inline-end of the bar, after the title.
   */
  actions?: React.ReactNode
}

const ScreenTopBar: React.FC<ScreenTopBarProps> = ({ title, onBack, headingAriaLabel, actions }) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      gap: 16,
      padding: '16px 20px',
      background: 'var(--surface)',
      borderBottom: '1px solid rgba(255,255,255,0.06)',
      boxShadow: '0 1px 0 rgba(0,0,0,0.3)',
      flexShrink: 0,
    }}
  >
    <IconButton icon={MdArrowBack} onClick={onBack} aria-label="Back" size={40} iconSize={20} />
    <h1
      aria-label={headingAriaLabel}
      style={{
        fontSize: 'var(--fs-title-medium)',
        fontWeight: 600,
        color: 'var(--on-surface)',
        margin: 0,
        flex: 1,
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        whiteSpace: 'nowrap',
      }}
    >
      {title}
    </h1>
    {actions && (
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
        {actions}
      </div>
    )}
  </div>
)

export default ScreenTopBar
