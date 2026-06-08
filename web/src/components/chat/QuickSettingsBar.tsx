/**
 * components/chat/QuickSettingsBar.tsx — Expandable quick-settings bar.
 *
 * Mirrors QuickSettingsBar + ThinkingBudgetButton + TemperatureButton +
 * ToolToggleButton + TextDirectionButton from ChatTopBar.kt and SettingsPopups.kt.
 *
 * Layout (RTL): row of 36px buttons right-aligned (Arrangement.End = logical
 * end = physical left in RTL), with 45px trailing spacer for toggle button.
 *
 * Active state:
 *   - Thinking: thinkingBudget !== 'none'
 *   - Temperature: temperature !== null
 *   - Tools: some tools excluded (enabled < total)
 *   - Text direction: always uses neutral state (mode shown as text)
 *   - System prompt: prompt !== ''
 */

import React, { useRef, useState, useEffect, useCallback } from 'react'
import ReactDOM from 'react-dom'
import IconButton from '../../ui/IconButton'
import {
  MdLightbulb,
  MdThermostat,
  MdExtension,
  MdFormatAlignRight,
  MdFormatAlignLeft,
  MdBuild,
  MdCheck,
} from '../../ui/icons'
import type { ThinkingBudget } from '../../api/types'
import { t } from '../../i18n/he'

// ── TextDirectionMode ─────────────────────────────────────────────────────────

export type TextDirectionMode = 'RTL' | 'AUTO' | 'LTR'

// ── Tool item ─────────────────────────────────────────────────────────────────

export interface ToolItem {
  id: string
  name: string
}

// ── Main props ────────────────────────────────────────────────────────────────

export interface QuickSettingsBarProps {
  /** Hide entire bar when in search mode */
  visible: boolean
  expanded: boolean
  thinkingBudget: ThinkingBudget
  onThinkingBudgetChange: (v: ThinkingBudget) => void
  temperature: number | null
  onTemperatureChange: (v: number | null) => void
  /** All tool items available for toggling */
  toolItems: ToolItem[]
  /** Tool IDs currently enabled (included in stream requests) */
  enabledToolIds: string[]
  onToolToggle: (toolId: string, enabled: boolean) => void
  textDirectionMode: TextDirectionMode
  onTextDirectionChange: (mode: TextDirectionMode) => void
  onSystemPrompt: () => void
  /** Used for system-prompt active state */
  systemPrompt: string
}

// ── Popup portal helper ───────────────────────────────────────────────────────

interface PopupPortalProps {
  anchorEl: HTMLElement | null
  open: boolean
  onClose: () => void
  children: React.ReactNode
}

const PopupPortal: React.FC<PopupPortalProps> = ({ anchorEl, open, onClose, children }) => {
  const [pos, setPos] = useState({ top: 0, left: 0 })

  useEffect(() => {
    if (!open || !anchorEl) return
    const rect = anchorEl.getBoundingClientRect()
    setPos({ top: rect.bottom + window.scrollY + 4, left: rect.left + window.scrollX })
  }, [open, anchorEl])

  useEffect(() => {
    if (!open) return
    const handle = (e: MouseEvent) => {
      if (anchorEl && anchorEl.contains(e.target as Node)) return
      onClose()
    }
    document.addEventListener('mousedown', handle)
    return () => document.removeEventListener('mousedown', handle)
  }, [open, onClose, anchorEl])

  if (!open) return null

  return ReactDOM.createPortal(
    <div
      onMouseDown={(e) => e.stopPropagation()}
      style={{
        position: 'absolute',
        top: pos.top,
        left: pos.left,
        background: 'var(--surface)',
        borderRadius: 'var(--radius-card)',
        boxShadow: '0 4px 16px rgba(0,0,0,0.45)',
        zIndex: 9999,
        minWidth: 200,
      }}
    >
      {children}
    </div>,
    document.body,
  )
}

// ── ThinkingBudgetPopup ───────────────────────────────────────────────────────

const THINKING_OPTIONS: { value: ThinkingBudget; label: string }[] = [
  { value: 'none',   label: t('thinking_none') },
  { value: 'low',    label: t('thinking_low') },
  { value: 'medium', label: t('thinking_medium') },
  { value: 'high',   label: t('thinking_high') },
  { value: 'max',    label: t('thinking_max') },
]

const ThinkingBudgetPopup: React.FC<{
  anchorEl: HTMLElement | null
  open: boolean
  value: ThinkingBudget
  onChange: (v: ThinkingBudget) => void
  onClose: () => void
}> = ({ anchorEl, open, value, onChange, onClose }) => (
  <PopupPortal anchorEl={anchorEl} open={open} onClose={onClose}>
    <div style={{ padding: '4px 0' }}>
      <div style={{
        padding: '8px 16px',
        fontSize: 'var(--fs-label-medium)',
        fontWeight: 'var(--fw-label-medium)',
        color: 'var(--on-surface-variant)',
      }}>
        {t('thinking_budget')}
      </div>
      {THINKING_OPTIONS.map((opt) => (
        <button
          key={opt.value}
          type="button"
          onClick={() => { onChange(opt.value); onClose() }}
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            width: '100%',
            padding: '10px 16px',
            background: opt.value === value ? 'var(--primary-08)' : 'none',
            border: 'none',
            cursor: 'pointer',
            color: opt.value === value ? 'var(--primary)' : 'var(--on-surface)',
            fontSize: 'var(--fs-body-medium)',
            fontWeight: opt.value === value ? 600 : 400,
            fontFamily: 'inherit',
            textAlign: 'start',
            gap: 8,
          }}
        >
          {opt.label}
          {opt.value === value && <MdCheck size={16} color="var(--primary)" />}
        </button>
      ))}
    </div>
  </PopupPortal>
)

// ── TemperaturePopup ──────────────────────────────────────────────────────────

const TemperaturePopup: React.FC<{
  anchorEl: HTMLElement | null
  open: boolean
  value: number | null
  onChange: (v: number | null) => void
  onClose: () => void
}> = ({ anchorEl, open, value, onChange, onClose }) => {
  const [sliderVal, setSliderVal] = useState(value ?? 1.0)

  useEffect(() => {
    setSliderVal(value ?? 1.0)
  }, [value, open])

  return (
    <PopupPortal anchorEl={anchorEl} open={open} onClose={onClose}>
      <div style={{ padding: 16, width: 260 }}>
        <div style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 16,
        }}>
          <span style={{
            fontSize: 'var(--fs-label-medium)',
            fontWeight: 'var(--fw-label-medium)',
            color: 'var(--on-surface-variant)',
          }}>
            {t('temperature_label')}
          </span>
          <span style={{
            background: value === null ? 'var(--surface-variant)' : 'var(--primary-15)',
            color: value === null ? 'var(--on-surface-variant)' : 'var(--primary)',
            fontSize: 'var(--fs-label-medium)',
            fontWeight: 600,
            padding: '4px 10px',
            borderRadius: 8,
          }}>
            {value === null ? t('temperature_default') : sliderVal.toFixed(1)}
          </span>
        </div>

        <input
          type="range"
          min={0}
          max={2}
          step={0.1}
          value={sliderVal}
          onChange={(e) => {
            const v = parseFloat(e.target.value)
            setSliderVal(v)
            onChange(v)
          }}
          style={{ width: '100%', accentColor: 'var(--primary)' }}
        />

        <div style={{
          display: 'flex',
          justifyContent: 'space-between',
          fontSize: 'var(--fs-label-small)',
          color: 'var(--on-surface-variant)',
          marginTop: 4,
        }}>
          <span>0.0</span>
          <span>2.0</span>
        </div>

        {value !== null && (
          <div style={{ textAlign: 'center', marginTop: 8 }}>
            <button
              type="button"
              onClick={() => { onChange(null); onClose() }}
              style={{
                background: 'none',
                border: 'none',
                color: 'var(--primary)',
                cursor: 'pointer',
                fontSize: 'var(--fs-label-small)',
                fontFamily: 'inherit',
              }}
            >
              {t('temperature_reset')}
            </button>
          </div>
        )}
      </div>
    </PopupPortal>
  )
}

// ── ToolToggleDropdown ────────────────────────────────────────────────────────

const ToolToggleDropdown: React.FC<{
  anchorEl: HTMLElement | null
  open: boolean
  toolItems: ToolItem[]
  enabledToolIds: string[]
  onToggle: (id: string, enabled: boolean) => void
  onClose: () => void
}> = ({ anchorEl, open, toolItems, enabledToolIds, onToggle, onClose }) => {
  const allEnabled = toolItems.length > 0 && toolItems.every(t => enabledToolIds.includes(t.id))
  const noneEnabled = toolItems.length > 0 && toolItems.every(t => !enabledToolIds.includes(t.id))

  return (
    <PopupPortal anchorEl={anchorEl} open={open} onClose={onClose}>
      <div style={{ minWidth: 240, maxHeight: 360, overflowY: 'auto', padding: '4px 0' }}>
        {toolItems.length === 0 ? (
          <div style={{
            padding: 16,
            textAlign: 'center',
            color: 'var(--on-surface-variant)',
            fontSize: 'var(--fs-body-medium)',
          }}>
            {t('no_tools_available')}
          </div>
        ) : (
          <>
            {/* Master toggle */}
            <button
              type="button"
              onClick={() => {
                if (allEnabled || !noneEnabled) {
                  toolItems.forEach(item => {
                    if (enabledToolIds.includes(item.id)) onToggle(item.id, false)
                  })
                } else {
                  toolItems.forEach(item => {
                    if (!enabledToolIds.includes(item.id)) onToggle(item.id, true)
                  })
                }
              }}
              style={{
                display: 'flex',
                width: '100%',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '10px 16px',
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                color: 'var(--on-surface)',
                fontSize: 'var(--fs-body-medium)',
                fontWeight: 600,
                fontFamily: 'inherit',
              }}
            >
              <span>{t('all_tools')}</span>
              <input
                type="checkbox"
                checked={allEnabled}
                readOnly
                style={{ accentColor: 'var(--primary)', pointerEvents: 'none' }}
              />
            </button>
            <div style={{ height: 1, background: 'var(--surface-variant)', margin: '4px 0' }} />
            {toolItems.map((item) => {
              const isEnabled = enabledToolIds.includes(item.id)
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => onToggle(item.id, !isEnabled)}
                  style={{
                    display: 'flex',
                    width: '100%',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '10px 16px',
                    background: 'none',
                    border: 'none',
                    cursor: 'pointer',
                    color: 'var(--on-surface)',
                    fontSize: 'var(--fs-body-medium)',
                    fontFamily: 'inherit',
                  }}
                >
                  <span>{item.name}</span>
                  <input
                    type="checkbox"
                    checked={isEnabled}
                    readOnly
                    style={{ accentColor: 'var(--primary)', pointerEvents: 'none' }}
                  />
                </button>
              )
            })}
          </>
        )}
      </div>
    </PopupPortal>
  )
}

// ── TextDirectionMenu ─────────────────────────────────────────────────────────

const TextDirectionMenu: React.FC<{
  anchorEl: HTMLElement | null
  open: boolean
  mode: TextDirectionMode
  onSelect: (m: TextDirectionMode) => void
  onClose: () => void
}> = ({ anchorEl, open, mode, onSelect, onClose }) => (
  <PopupPortal anchorEl={anchorEl} open={open} onClose={onClose}>
    <div style={{ display: 'flex', padding: 8, gap: 4 }}>
      {(['RTL', 'AUTO', 'LTR'] as TextDirectionMode[]).map((m) => (
        <button
          key={m}
          type="button"
          aria-label={m}
          onClick={() => { onSelect(m); onClose() }}
          style={{
            width: 36,
            height: 36,
            borderRadius: 'var(--radius-icon-btn)',
            background: mode === m ? 'var(--primary-15)' : 'var(--surface-variant)',
            border: 'none',
            cursor: 'pointer',
            color: mode === m ? 'var(--primary)' : 'var(--on-surface-variant)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontWeight: m === 'AUTO' ? 700 : 400,
            fontSize: m === 'AUTO' ? 16 : 14,
            fontFamily: 'inherit',
          }}
        >
          {m === 'RTL' ? <MdFormatAlignRight size={18} /> :
           m === 'LTR' ? <MdFormatAlignLeft size={18} /> :
           'A'}
        </button>
      ))}
    </div>
  </PopupPortal>
)

// ── QuickSettingsBar ──────────────────────────────────────────────────────────

const QuickSettingsBar: React.FC<QuickSettingsBarProps> = ({
  visible,
  expanded,
  thinkingBudget,
  onThinkingBudgetChange,
  temperature,
  onTemperatureChange,
  toolItems,
  enabledToolIds,
  onToolToggle,
  textDirectionMode,
  onTextDirectionChange,
  onSystemPrompt,
  systemPrompt,
}) => {
  const [showThinking, setShowThinking] = useState(false)
  const [showTemp, setShowTemp] = useState(false)
  const [showTools, setShowTools] = useState(false)
  const [showDir, setShowDir] = useState(false)

  const thinkingRef = useRef<HTMLButtonElement>(null)
  const tempRef = useRef<HTMLButtonElement>(null)
  const toolsRef = useRef<HTMLButtonElement>(null)
  const dirRef = useRef<HTMLButtonElement>(null)

  const closeAll = useCallback(() => {
    setShowThinking(false)
    setShowTemp(false)
    setShowTools(false)
    setShowDir(false)
  }, [])

  if (!visible) return null

  const thinkingActive = thinkingBudget !== 'none'
  const tempActive = temperature !== null
  const toolsActive = toolItems.length > 0 && enabledToolIds.length < toolItems.length
  const promptActive = systemPrompt.trim() !== ''

  return (
    <>
      <div
        style={{
          background: 'var(--surface)',
          boxShadow: '0 1px 0 rgba(0,0,0,0.25)',
          overflow: expanded ? 'visible' : 'hidden',
          maxHeight: expanded ? 120 : 0,
          opacity: expanded ? 1 : 0,
          transition: 'max-height 0.2s ease, opacity 0.15s ease',
          paddingTop: expanded ? 0 : 0,
        }}
      >
        {expanded && (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'flex-end',
              padding: '16px 20px',
              paddingInlineEnd: 65,  /* 45px spacer + 20px margin for toggle button */
              gap: 12,
            }}
          >
            {/* Thinking budget button */}
            <div style={{ position: 'relative' }}>
              <button
                ref={thinkingRef}
                type="button"
                aria-label={t('thinking_budget')}
                onClick={() => { closeAll(); setShowThinking(v => !v) }}
                style={{
                  width: 36, height: 36,
                  borderRadius: 'var(--radius-icon-btn)',
                  background: thinkingActive ? 'var(--primary-15)' : 'var(--surface-variant)',
                  border: 'none',
                  cursor: 'pointer',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: thinkingActive ? 'var(--primary)' : 'var(--on-surface-variant)',
                }}
              >
                <MdLightbulb size={18} />
              </button>
            </div>

            {/* Temperature button */}
            <div style={{ position: 'relative' }}>
              <button
                ref={tempRef}
                type="button"
                aria-label={t('temperature_label')}
                onClick={() => { closeAll(); setShowTemp(v => !v) }}
                style={{
                  width: 36, height: 36,
                  borderRadius: 'var(--radius-icon-btn)',
                  background: tempActive ? 'var(--primary-15)' : 'var(--surface-variant)',
                  border: 'none',
                  cursor: 'pointer',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: tempActive ? 'var(--primary)' : 'var(--on-surface-variant)',
                }}
              >
                <MdThermostat size={18} />
              </button>
            </div>

            {/* Tools toggle button */}
            <div style={{ position: 'relative' }}>
              <button
                ref={toolsRef}
                type="button"
                aria-label={t('tools_label')}
                onClick={() => { closeAll(); setShowTools(v => !v) }}
                style={{
                  width: 36, height: 36,
                  borderRadius: 'var(--radius-icon-btn)',
                  background: toolsActive ? 'var(--primary-15)' : 'var(--surface-variant)',
                  border: 'none',
                  cursor: 'pointer',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: toolsActive ? 'var(--primary)' : 'var(--on-surface-variant)',
                }}
              >
                <MdExtension size={18} />
              </button>
            </div>

            {/* Text direction button */}
            <div style={{ position: 'relative' }}>
              <button
                ref={dirRef}
                type="button"
                aria-label={t('text_direction_label')}
                onClick={() => { closeAll(); setShowDir(v => !v) }}
                style={{
                  width: 36, height: 36,
                  borderRadius: 'var(--radius-icon-btn)',
                  background: 'var(--surface-variant)',
                  border: 'none',
                  cursor: 'pointer',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: 'var(--on-surface-variant)',
                }}
              >
                <MdFormatAlignRight size={18} />
              </button>
            </div>

            {/* System prompt button */}
            <IconButton
              icon={MdBuild}
              aria-label={t('system_prompt')}
              active={promptActive}
              onClick={() => { closeAll(); onSystemPrompt() }}
            />
          </div>
        )}
      </div>

      {/* Popups rendered in portal */}
      <ThinkingBudgetPopup
        anchorEl={thinkingRef.current}
        open={showThinking}
        value={thinkingBudget}
        onChange={onThinkingBudgetChange}
        onClose={() => setShowThinking(false)}
      />

      <TemperaturePopup
        anchorEl={tempRef.current}
        open={showTemp}
        value={temperature}
        onChange={onTemperatureChange}
        onClose={() => setShowTemp(false)}
      />

      <ToolToggleDropdown
        anchorEl={toolsRef.current}
        open={showTools}
        toolItems={toolItems}
        enabledToolIds={enabledToolIds}
        onToggle={onToolToggle}
        onClose={() => setShowTools(false)}
      />

      <TextDirectionMenu
        anchorEl={dirRef.current}
        open={showDir}
        mode={textDirectionMode}
        onSelect={onTextDirectionChange}
        onClose={() => setShowDir(false)}
      />
    </>
  )
}

export default QuickSettingsBar
