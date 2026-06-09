/**
 * BranchNavigator — ‹ n/m › variant switcher per BranchNavigator.kt.
 * Shown below user messages when node has multiple variants.
 */

import React, { useState } from 'react'
import { MdChevronLeft, MdChevronRight } from '../../ui/icons'
import { branching } from '../../api/client'
import type { Chat } from '../../api/types'
import { t } from '../../i18n/he'

interface BranchNavigatorProps {
  chatId: string
  nodeId: string
  totalVariants: number
  currentVariantIndex: number
  onSwitch: (chat: Chat) => void
}

const BranchNavigator: React.FC<BranchNavigatorProps> = ({
  chatId, nodeId, totalVariants, currentVariantIndex, onSwitch,
}) => {
  const [busy, setBusy] = useState(false)

  const go = async (dir: -1 | 1) => {
    if (busy) return
    setBusy(true)
    try {
      const idx = currentVariantIndex + dir
      const chat = await branching.switchVariant(chatId, nodeId, idx)
      onSwitch(chat)
    } catch {
      // ignore
    } finally {
      setBusy(false)
    }
  }

  if (totalVariants <= 1) return null

  const hasPrev = currentVariantIndex > 0
  const hasNext = currentVariantIndex < totalVariants - 1

  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 2,
        background: 'rgba(32,33,44,0.85)',
        borderRadius: 16,
        padding: '2px 6px',
        marginTop: 4,
      }}
    >
      <button
        disabled={!hasPrev || busy}
        onClick={() => go(-1)}
        aria-label="Previous variant"
        style={{
          background: 'none',
          border: 'none',
          color: hasPrev ? 'var(--on-surface-variant)' : 'rgba(189,189,189,0.3)',
          cursor: hasPrev && !busy ? 'pointer' : 'not-allowed',
          padding: 2,
          display: 'flex',
          alignItems: 'center',
          opacity: hasPrev ? 1 : 0.3,
        }}
        title={t('previous_variant')}
      >
        <MdChevronLeft size={18} />
      </button>
      <span
        style={{
          padding: '0 4px',
          fontSize: 11,
          fontWeight: 500,
          color: 'var(--on-surface-variant)',
          userSelect: 'none',
          minWidth: 28,
          textAlign: 'center',
        }}
      >
        {currentVariantIndex + 1}/{totalVariants}
      </span>
      <button
        disabled={!hasNext || busy}
        onClick={() => go(1)}
        aria-label="Next variant"
        style={{
          background: 'none',
          border: 'none',
          color: hasNext ? 'var(--on-surface-variant)' : 'rgba(189,189,189,0.3)',
          cursor: hasNext && !busy ? 'pointer' : 'not-allowed',
          padding: 2,
          display: 'flex',
          alignItems: 'center',
          opacity: hasNext ? 1 : 0.3,
        }}
        title={t('next_variant')}
      >
        <MdChevronRight size={18} />
      </button>
    </div>
  )
}

export default BranchNavigator
