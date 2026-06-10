/**
 * components/chat/ChatTopBar.tsx — Chat screen top bar.
 *
 * Mirrors ChatTopBar.kt: ChatTopBarContainer wrapping NormalModeTopBar /
 * SearchModeTopBar with the floating QuickSettingsToggleButton.
 *
 * Layout (RTL — all "left/right" descriptions are physical):
 *   NormalMode: [share][delete]  [provider ↑ model]  [search][back]
 *   SearchMode: [back]  [search field ──────────────────]  [✕/🔍]
 *
 * The toggle chevron floats at top=68px, inset-inline-end=20px
 * (= physical left in the RTL document, matching Android end=20dp).
 */

import React, { useRef, useEffect } from 'react'
import IconButton from '../../ui/IconButton'
import {
  MdArrowBack,
  MdSearch,
  MdShare,
  MdDelete,
  MdArrowDropDown,
  MdArrowDropUp,
  MdClose,
} from '../../ui/icons'
import { t } from '../../i18n/he'

// ── Provider display-name helper ───────────────────────────────────────────────

const PROVIDER_NAMES: Record<string, string> = {
  openai: t('provider_openai'),
  anthropic: t('provider_anthropic'),
  google: t('provider_google'),
  poe: t('provider_poe'),
  cohere: t('provider_cohere'),
  openrouter: t('provider_openrouter'),
  llmstats: t('provider_llmstats'),
}

export function getProviderDisplayName(providerKey: string): string {
  const builtin = PROVIDER_NAMES[providerKey]
  if (builtin) return builtin
  // Custom providers — mirrors getDisplayNameFromProviderKey (CustomProvider.kt)
  const prefix = providerKey.startsWith('fullcustom_')
    ? 'fullcustom_'
    : providerKey.startsWith('custom_')
      ? 'custom_'
      : null
  if (prefix) {
    return providerKey
      .slice(prefix.length)
      .split('_')
      .filter(Boolean)
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
      .join(' ')
  }
  return providerKey
}

// ── Types ──────────────────────────────────────────────────────────────────────

export interface ChatTopBarProps {
  /** Currently selected provider key (e.g. 'openai') */
  provider: string
  /** Currently selected model name */
  model: string
  /** Whether the search bar is active */
  searchMode: boolean
  searchQuery: string
  /** Whether quick-settings bar is expanded */
  quickSettingsExpanded: boolean
  onBack: () => void
  onSearch: () => void
  onShare: () => void
  onDelete: () => void
  /** Clicking the provider/model center area → open model selector */
  onClickProviderModel: () => void
  onSearchQueryChange: (q: string) => void
  onSearchAction: () => void
  onExitSearch: () => void
  onToggleQuickSettings: () => void
}

// ── NormalModeTopBar ──────────────────────────────────────────────────────────

const NormalModeTopBar: React.FC<{
  provider: string
  model: string
  onBack: () => void
  onSearch: () => void
  onShare: () => void
  onDelete: () => void
  onClickProviderModel: () => void
}> = ({ provider, model, onBack, onSearch, onShare, onDelete, onClickProviderModel }) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: '16px 20px',
      gap: 8,
    }}
  >
    {/* Left group in source = RTL physical-right (back + search) */}
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexShrink: 0 }}>
      <IconButton
        icon={MdArrowBack}
        aria-label="Back to chats"
        onClick={onBack}
      />
      <IconButton
        icon={MdSearch}
        aria-label={t('search_in_conversation')}
        onClick={onSearch}
      />
    </div>

    {/* Center: provider name + model name */}
    <div
      role="button"
      tabIndex={0}
      aria-label={`${getProviderDisplayName(provider)} ${model}`}
      onClick={onClickProviderModel}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onClickProviderModel() }}
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        flex: 1,
        cursor: 'pointer',
        userSelect: 'none',
        minWidth: 0,
        padding: '0 4px',
      }}
    >
      <span
        style={{
          fontSize: 'var(--fs-label-medium)',
          fontWeight: 'var(--fw-label-medium)',
          color: 'var(--on-surface-variant)',
          textAlign: 'center',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          maxWidth: '100%',
        }}
      >
        {getProviderDisplayName(provider)}
      </span>
      <span
        style={{
          fontSize: 'var(--fs-title-small)',
          fontWeight: 'var(--fw-title-small)',
          color: 'var(--on-surface)',
          textAlign: 'center',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          maxWidth: '100%',
          marginTop: 2,
        }}
      >
        {model}
      </span>
    </div>

    {/* Right group in source = RTL physical-left (share + delete) */}
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
      <IconButton
        icon={MdShare}
        aria-label={t('share_chat_title')}
        onClick={onShare}
      />
      <IconButton
        icon={MdDelete}
        aria-label={t('delete_chat')}
        onClick={onDelete}
      />
    </div>
  </div>
)

// ── SearchModeTopBar ──────────────────────────────────────────────────────────

const SearchModeTopBar: React.FC<{
  searchQuery: string
  onSearchQueryChange: (q: string) => void
  onSearchAction: () => void
  onBack: () => void
  onExitSearch: () => void
}> = ({ searchQuery, onSearchQueryChange, onSearchAction, onBack, onExitSearch }) => {
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        padding: '16px 20px',
      }}
    >
      <IconButton
        icon={MdArrowBack}
        aria-label={t('exit_search_mode')}
        onClick={onBack}
      />

      <div
        style={{
          flex: 1,
          display: 'flex',
          alignItems: 'center',
          background: 'var(--surface-variant)',
          borderRadius: 'var(--radius-icon-btn)',
          padding: '0 12px',
          gap: 8,
          height: 36,
        }}
      >
        <input
          ref={inputRef}
          type="search"
          value={searchQuery}
          onChange={(e) => onSearchQueryChange(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') onSearchAction() }}
          placeholder={t('search_in_conversation')}
          style={{
            flex: 1,
            background: 'none',
            border: 'none',
            outline: 'none',
            color: 'var(--on-surface)',
            fontSize: 'var(--fs-body-medium)',
            fontFamily: 'inherit',
            textAlign: 'start',
            direction: 'rtl',
          }}
        />
        <button
          type="button"
          aria-label={searchQuery ? t('exit_search_mode') : t('search_in_conversation')}
          onClick={searchQuery ? onExitSearch : onSearchAction}
          style={{
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            color: 'var(--on-surface-variant)',
            display: 'flex',
            alignItems: 'center',
            padding: 0,
          }}
        >
          {searchQuery ? <MdClose size={18} /> : <MdSearch size={18} />}
        </button>
      </div>
    </div>
  )
}

// ── ChatTopBar (main export) ──────────────────────────────────────────────────

/**
 * Top bar container — includes the main bar + the floating toggle chevron.
 * The QuickSettingsBar itself is rendered separately below this in the page,
 * but the toggle button floats over it via absolute positioning.
 */
const ChatTopBar: React.FC<ChatTopBarProps> = ({
  provider,
  model,
  searchMode,
  searchQuery,
  quickSettingsExpanded,
  onBack,
  onSearch,
  onShare,
  onDelete,
  onClickProviderModel,
  onSearchQueryChange,
  onSearchAction,
  onExitSearch,
  onToggleQuickSettings,
}) => (
  <div
    style={{
      position: 'relative',
      background: 'var(--surface)',
      boxShadow: '0 1px 0 rgba(0,0,0,0.25)',
      flexShrink: 0,
    }}
  >
    {searchMode ? (
      <SearchModeTopBar
        searchQuery={searchQuery}
        onSearchQueryChange={onSearchQueryChange}
        onSearchAction={onSearchAction}
        onBack={onBack}
        onExitSearch={onExitSearch}
      />
    ) : (
      <NormalModeTopBar
        provider={provider}
        model={model}
        onBack={onBack}
        onSearch={onSearch}
        onShare={onShare}
        onDelete={onDelete}
        onClickProviderModel={onClickProviderModel}
      />
    )}

    {/* Floating toggle button — visible only in normal mode */}
    {!searchMode && (
      <button
        type="button"
        aria-label={quickSettingsExpanded ? t('quick_settings_collapse') : t('quick_settings_expand')}
        onClick={onToggleQuickSettings}
        style={{
          position: 'absolute',
          bottom: -14,  /* half of 28px button */
          insetInlineEnd: 20,   /* = physical left in RTL (end side) */
          width: 28,
          height: 28,
          borderRadius: '50%',
          background: 'var(--surface-variant)',
          border: 'none',
          cursor: 'pointer',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          boxShadow: '0 2px 8px rgba(0,0,0,0.30)',
          color: 'var(--on-surface-variant)',
          zIndex: 10,
        }}
      >
        {quickSettingsExpanded
          ? <MdArrowDropUp size={18} />
          : <MdArrowDropDown size={18} />}
      </button>
    )}
  </div>
)

export default ChatTopBar
