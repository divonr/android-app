/**
 * components/chat/ModelSelectorDialog.tsx — Model selector dialog.
 *
 * Mirrors ModelSelectionDialogs.kt exactly:
 *   - Header: title + pricing ($) toggle + sort menu (price / newest / recommended)
 *     + refresh button (re-fetches the GitHub-backed models list via the server).
 *   - Custom model name entry with "Use: <name>" button (uses the viewed provider).
 *   - Tabs: star/favorites first, then one tab per provider with an active API key.
 *   - Model rows: name + optional pricing lines + star toggle.
 *
 * Data comes from GET /api/providers/detailed (name/pricing/releaseOrder),
 * which exposes the same fields the Android dialog reads from Provider.models.
 */

import React, { useState, useRef, useEffect } from 'react'
import Dialog from '../../ui/Dialog'
import { MdStar, MdStarBorder, MdCheck, MdRefresh, MdAttachMoney, MdSort } from '../../ui/icons'
import type { ModelDetail, ModelPricing, ProviderDetail, StarredModel } from '../../api/types'
import { t } from '../../i18n/he'
import { getProviderDisplayName } from './ChatTopBar'

// ── Types ──────────────────────────────────────────────────────────────────────

type SortOption = 'RECOMMENDED' | 'BY_PRICE' | 'NEWEST_FIRST'

interface ModelSelectorDialogProps {
  open: boolean
  onClose: () => void
  providers: ProviderDetail[]
  currentProvider: string
  currentModel: string
  onSelect: (provider: string, model: string) => void
  starredModels: StarredModel[]
  onToggleStar: (provider: string, modelName: string) => void
  /** Force-refresh the models cache (POST /api/providers/refresh). */
  onRefresh?: () => Promise<void>
}

// ── Pricing (mirrors PricingText in ModelSelectionDialogs.kt) ─────────────────

function formatPrice(price: number): string {
  if (price < 0.001) return `$${price.toFixed(5)}`
  if (price < 0.01) return `$${price.toFixed(4)}`
  if (Number.isInteger(price)) return `$${price.toFixed(0)}`
  return `$${price.toFixed(4)}`
}

function formatPoints(points: number): string {
  return Number.isInteger(points) ? String(points) : points.toFixed(2)
}

const pricingLineStyle: React.CSSProperties = {
  fontSize: 'var(--fs-body-small)',
  color: 'rgba(232,232,242,0.7)',
}

const PricingText: React.FC<{ pricing: ModelPricing }> = ({ pricing }) => {
  const hasUsd = pricing.input_price_per_1k != null || pricing.output_price_per_1k != null
  const isFixed = pricing.points != null
  const isTokenBased = pricing.input_points_per_1k != null || pricing.output_points_per_1k != null

  if (hasUsd) {
    return (
      <div>
        {pricing.input_price_per_1k != null && (
          <div style={pricingLineStyle}>Input: {formatPrice(pricing.input_price_per_1k)}/1k tokens</div>
        )}
        {pricing.output_price_per_1k != null && (
          <div style={pricingLineStyle}>Output: {formatPrice(pricing.output_price_per_1k)}/1k tokens</div>
        )}
      </div>
    )
  }
  if (isFixed) {
    return <div style={pricingLineStyle}>Points: {pricing.points}</div>
  }
  if (isTokenBased) {
    return (
      <div>
        {pricing.input_points_per_1k != null && (
          <div style={pricingLineStyle}>Input: {formatPoints(pricing.input_points_per_1k)} pts/1k tokens</div>
        )}
        {pricing.output_points_per_1k != null && (
          <div style={pricingLineStyle}>Output: {formatPoints(pricing.output_points_per_1k)} pts/1k tokens</div>
        )}
      </div>
    )
  }
  if (pricing.min_points != null) {
    return <div style={pricingLineStyle}>Min points: {pricing.min_points}</div>
  }
  return null
}

// ── Sorting (mirrors compareByPrice / sortModels) ──────────────────────────────

function compareByPrice(a: ModelDetail, b: ModelDetail): number {
  const aIn = a.pricing?.input_price_per_1k ?? a.pricing?.input_points_per_1k ?? Number.MAX_VALUE
  const bIn = b.pricing?.input_price_per_1k ?? b.pricing?.input_points_per_1k ?? Number.MAX_VALUE
  const aOut = a.pricing?.output_price_per_1k ?? a.pricing?.output_points_per_1k ?? Number.MAX_VALUE
  const bOut = b.pricing?.output_price_per_1k ?? b.pricing?.output_points_per_1k ?? Number.MAX_VALUE

  if (aIn < bIn && aOut < bOut) return -1
  if (aIn > bIn && aOut > bOut) return 1
  if (aIn === bIn && aOut < bOut) return -1
  if (aIn === bIn && aOut > bOut) return 1
  if (aOut < bOut) return -1
  if (aOut > bOut) return 1
  return 0
}

function sortModels(models: ModelDetail[], sortOption: SortOption): ModelDetail[] {
  switch (sortOption) {
    case 'RECOMMENDED':
      return models
    case 'BY_PRICE':
      return [...models].sort(compareByPrice)
    case 'NEWEST_FIRST':
      return [...models].sort((a, b) => (b.releaseOrder ?? 0) - (a.releaseOrder ?? 0))
  }
}

// ── Shared row pieces ──────────────────────────────────────────────────────────

const rowButtonStyle: React.CSSProperties = {
  display: 'flex',
  width: '100%',
  alignItems: 'center',
  justifyContent: 'space-between',
  padding: '12px 16px 12px 4px',
  background: 'none',
  border: 'none',
  cursor: 'pointer',
  gap: 8,
  fontFamily: 'inherit',
  textAlign: 'start',
}

const StarButton: React.FC<{ starred: boolean; onClick: () => void }> = ({ starred, onClick }) => (
  <button
    type="button"
    aria-label={starred ? t('remove_from_starred') : t('add_to_starred')}
    onClick={(e) => { e.stopPropagation(); onClick() }}
    style={{ background: 'none', border: 'none', cursor: 'pointer', padding: '0 8px', flexShrink: 0 }}
  >
    {starred
      ? <MdStar size={20} color="var(--primary)" />
      : <MdStarBorder size={20} color="var(--on-surface)" style={{ opacity: 0.3 }} />}
  </button>
)

const RowDivider: React.FC = () => (
  <div style={{ height: 1, background: 'rgba(232,232,242,0.2)' }} />
)

// ── Starred models page ────────────────────────────────────────────────────────

const StarredPage: React.FC<{
  starredModels: StarredModel[]
  providers: ProviderDetail[]
  showPricing: boolean
  onSelect: (provider: string, model: string) => void
  onToggleStar: (provider: string, modelName: string) => void
}> = ({ starredModels, providers, showPricing, onSelect, onToggleStar }) => {
  if (starredModels.length === 0) {
    return (
      <div style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '32px 16px',
        gap: 8,
      }}>
        <span style={{
          fontSize: 'var(--fs-title-small)',
          fontWeight: 700,
          color: 'var(--on-surface)',
        }}>
          {t('quick_access_title')}
        </span>
        <span style={{
          fontSize: 'var(--fs-body-medium)',
          color: 'rgba(232,232,242,0.7)',
          textAlign: 'center',
        }}>
          {t('starred_hint')}
        </span>
      </div>
    )
  }

  return (
    <div style={{ maxHeight: 300, overflowY: 'auto' }}>
      {starredModels.map((starred) => {
        const prov = providers.find(p => p.provider === starred.provider)
        if (!prov) return null
        const model = prov.models.find(m => m.name === starred.modelName)
        return (
          <React.Fragment key={`${starred.provider}/${starred.modelName}`}>
            <button
              type="button"
              onClick={() => onSelect(starred.provider, starred.modelName)}
              style={rowButtonStyle}
            >
              <div style={{ flex: 1, minWidth: 0, paddingInlineStart: 12 }}>
                <div style={{
                  fontSize: 'var(--fs-body-large)',
                  color: 'var(--on-surface)',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}>
                  {starred.modelName}
                </div>
                <div style={{
                  fontSize: 'var(--fs-body-small)',
                  color: 'rgba(232,232,242,0.5)',
                }}>
                  {getProviderDisplayName(starred.provider)}
                </div>
                {showPricing && model?.pricing && <PricingText pricing={model.pricing} />}
              </div>
              <StarButton starred onClick={() => onToggleStar(starred.provider, starred.modelName)} />
            </button>
            <RowDivider />
          </React.Fragment>
        )
      })}
    </div>
  )
}

// ── Provider model list page ──────────────────────────────────────────────────

const ProviderModelList: React.FC<{
  provider: ProviderDetail
  showPricing: boolean
  sortOption: SortOption
  currentModel: string
  starredModels: StarredModel[]
  onSelect: (model: string) => void
  onToggleStar: (model: string) => void
}> = ({ provider, showPricing, sortOption, currentModel, starredModels, onSelect, onToggleStar }) => {
  const sorted = sortModels(provider.models, sortOption)

  return (
    <div style={{ maxHeight: 300, overflowY: 'auto' }}>
      {sorted.map((model) => {
        const isSelected = model.name === currentModel
        const isStarred = starredModels.some(
          s => s.provider === provider.provider && s.modelName === model.name,
        )

        return (
          <React.Fragment key={model.name}>
            <button
              type="button"
              onClick={() => onSelect(model.name)}
              style={rowButtonStyle}
            >
              <div style={{ flex: 1, minWidth: 0, paddingInlineStart: 12 }}>
                <span style={{
                  fontSize: 'var(--fs-body-large)',
                  color: isSelected ? 'var(--primary)' : 'var(--on-surface)',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  display: 'block',
                }}>
                  {model.name}
                  {isSelected && <MdCheck size={14} color="var(--primary)" style={{ marginInlineStart: 4 }} />}
                </span>
                {showPricing && model.pricing && <PricingText pricing={model.pricing} />}
              </div>
              <StarButton starred={isStarred} onClick={() => onToggleStar(model.name)} />
            </button>
            <RowDivider />
          </React.Fragment>
        )
      })}
    </div>
  )
}

// ── Sort dropdown menu ─────────────────────────────────────────────────────────

const SortMenu: React.FC<{
  open: boolean
  sortOption: SortOption
  onPick: (opt: SortOption) => void
  onClose: () => void
}> = ({ open, sortOption, onPick, onClose }) => {
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) onClose()
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open, onClose])

  if (!open) return null

  const items: Array<{ opt: SortOption; label: string }> = [
    { opt: 'BY_PRICE', label: t('sort_by_price') },
    { opt: 'NEWEST_FIRST', label: t('sort_newest_first') },
    { opt: 'RECOMMENDED', label: t('sort_recommended') },
  ]

  return (
    <div
      ref={menuRef}
      role="menu"
      style={{
        position: 'absolute',
        top: '100%',
        insetInlineEnd: 0,
        zIndex: 50,
        background: 'var(--surface-variant)',
        border: '1px solid var(--message-border)',
        borderRadius: 8,
        boxShadow: '0 4px 16px rgba(0,0,0,0.4)',
        minWidth: 140,
        overflow: 'hidden',
      }}
    >
      {items.map(({ opt, label }) => (
        <button
          key={opt}
          type="button"
          role="menuitem"
          onClick={() => onPick(opt)}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            width: '100%',
            padding: '10px 14px',
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            color: 'var(--on-surface)',
            fontSize: 'var(--fs-body-medium)',
            fontFamily: 'inherit',
            textAlign: 'start',
          }}
        >
          <span style={{ width: 18, display: 'inline-flex' }}>
            {sortOption === opt && <MdCheck size={16} color="var(--primary)" />}
          </span>
          {label}
        </button>
      ))}
    </div>
  )
}

// ── ModelSelectorDialog ────────────────────────────────────────────────────────

const ModelSelectorDialog: React.FC<ModelSelectorDialogProps> = ({
  open,
  onClose,
  providers,
  currentProvider,
  currentModel,
  onSelect,
  starredModels,
  onToggleStar,
  onRefresh,
}) => {
  const [activeTab, setActiveTab] = useState<'stars' | string>('stars')
  const [customName, setCustomName] = useState('')
  const [showPricing, setShowPricing] = useState(false)
  const [sortOption, setSortOption] = useState<SortOption>('RECOMMENDED')
  const [showSortMenu, setShowSortMenu] = useState(false)
  const [refreshing, setRefreshing] = useState(false)

  // Open on the current provider's tab (Android: initialPageIndex)
  useEffect(() => {
    if (open) {
      setActiveTab(providers.some(p => p.provider === currentProvider) ? currentProvider : 'stars')
      setCustomName('')
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const activeProvider = providers.find(p => p.provider === activeTab)

  const handleSelect = (provider: string, model: string) => {
    onSelect(provider, model)
    onClose()
  }

  const handleRefresh = async () => {
    if (!onRefresh || refreshing) return
    setRefreshing(true)
    try {
      await onRefresh()
    } finally {
      setRefreshing(false)
    }
  }

  const iconBtnStyle = (active: boolean): React.CSSProperties => ({
    width: 32,
    height: 32,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    background: 'none',
    border: 'none',
    cursor: 'pointer',
    color: active ? 'var(--primary)' : 'rgba(232,232,242,0.5)',
    padding: 0,
  })

  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth={360}
    >
      {/* Header: title + pricing/sort/refresh buttons */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: 16,
      }}>
        <span style={{
          fontSize: 'var(--fs-title-medium)',
          fontWeight: 700,
          color: 'var(--on-surface)',
        }}>
          {t('model_selector_title')}
        </span>

        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <button
            type="button"
            aria-label={showPricing ? 'Hide pricing' : 'Show pricing'}
            aria-pressed={showPricing}
            onClick={() => setShowPricing(v => !v)}
            style={iconBtnStyle(showPricing)}
          >
            <MdAttachMoney size={20} />
          </button>

          <div style={{ position: 'relative' }}>
            <button
              type="button"
              aria-label="Sort models"
              onClick={() => setShowSortMenu(v => !v)}
              style={iconBtnStyle(sortOption !== 'RECOMMENDED')}
            >
              <MdSort size={20} />
            </button>
            <SortMenu
              open={showSortMenu}
              sortOption={sortOption}
              onPick={(opt) => { setSortOption(opt); setShowSortMenu(false) }}
              onClose={() => setShowSortMenu(false)}
            />
          </div>

          {onRefresh && (
            <button
              type="button"
              aria-label="Refresh models"
              onClick={handleRefresh}
              disabled={refreshing}
              style={{ ...iconBtnStyle(true), opacity: refreshing ? 0.5 : 1 }}
            >
              <MdRefresh
                size={20}
                style={refreshing ? { animation: 'spin 1s linear infinite' } : undefined}
              />
            </button>
          )}
        </div>
      </div>

      {/* Custom model entry */}
      <div style={{ marginBottom: 12 }}>
        <div style={{
          fontSize: 'var(--fs-body-medium)',
          color: 'var(--on-surface)',
          marginBottom: 8,
        }}>
          {t('custom_model_enter')}
        </div>
        <input
          type="text"
          value={customName}
          onChange={(e) => setCustomName(e.target.value)}
          placeholder={t('custom_model_placeholder')}
          style={{
            width: '100%',
            padding: '8px 12px',
            background: 'transparent',
            border: '1px solid rgba(232,232,242,0.3)',
            borderRadius: 'var(--radius-icon-btn)',
            color: 'var(--on-surface)',
            fontSize: 'var(--fs-body-medium)',
            fontFamily: 'inherit',
            outline: 'none',
            boxSizing: 'border-box',
          }}
        />
        {customName.trim() && providers.length > 0 && (
          <button
            type="button"
            onClick={() => {
              // Android: star tab → first provider; otherwise the viewed provider
              const prov = activeProvider ?? providers[0]
              handleSelect(prov.provider, customName.trim())
              setCustomName('')
            }}
            style={{
              marginTop: 8,
              width: '100%',
              padding: '8px',
              background: 'var(--primary)',
              color: 'var(--on-primary)',
              border: 'none',
              borderRadius: 'var(--radius-icon-btn)',
              cursor: 'pointer',
              fontSize: 'var(--fs-body-medium)',
              fontFamily: 'inherit',
              fontWeight: 500,
            }}
          >
            Use: {customName.trim()}
          </button>
        )}
      </div>

      <div style={{ height: 1, background: 'rgba(232,232,242,0.3)', margin: '8px 0' }} />

      {providers.length === 0 ? (
        <div style={{
          padding: '16px 0',
          color: 'rgba(232,232,242,0.7)',
          fontSize: 'var(--fs-body-medium)',
        }}>
          {t('no_providers_available')}
        </div>
      ) : (
        <>
          {/* Provider tabs: star first, then providers */}
          <div style={{
            display: 'flex',
            gap: 4,
            overflowX: 'auto',
            paddingBottom: 8,
            scrollbarWidth: 'none',
          }}>
            <button
              type="button"
              aria-label={t('starred_tab')}
              onClick={() => setActiveTab('stars')}
              style={{
                flexShrink: 0,
                padding: '4px 8px',
                borderRadius: 8,
                background: activeTab === 'stars' ? 'var(--primary-15)' : 'none',
                border: 'none',
                cursor: 'pointer',
                color: activeTab === 'stars' ? 'var(--primary)' : 'rgba(232,232,242,0.6)',
                display: 'flex',
                alignItems: 'center',
              }}
            >
              {starredModels.length > 0 ? <MdStar size={20} /> : <MdStarBorder size={20} />}
            </button>

            {providers.map((prov) => (
              <button
                key={prov.provider}
                type="button"
                onClick={() => setActiveTab(prov.provider)}
                style={{
                  flexShrink: 0,
                  padding: '4px 10px',
                  borderRadius: 8,
                  background: activeTab === prov.provider ? 'var(--primary-15)' : 'none',
                  border: 'none',
                  cursor: 'pointer',
                  color: activeTab === prov.provider ? 'var(--primary)' : 'rgba(232,232,242,0.6)',
                  fontSize: 'var(--fs-body-medium)',
                  fontWeight: activeTab === prov.provider ? 600 : 400,
                  fontFamily: 'inherit',
                  whiteSpace: 'nowrap',
                }}
              >
                {getProviderDisplayName(prov.provider)}
              </button>
            ))}
          </div>

          {/* Tab content */}
          {activeTab === 'stars' ? (
            <StarredPage
              starredModels={starredModels}
              providers={providers}
              showPricing={showPricing}
              onSelect={handleSelect}
              onToggleStar={onToggleStar}
            />
          ) : activeProvider ? (
            <ProviderModelList
              provider={activeProvider}
              showPricing={showPricing}
              sortOption={sortOption}
              currentModel={currentModel}
              starredModels={starredModels}
              onSelect={(model) => handleSelect(activeProvider.provider, model)}
              onToggleStar={(model) => onToggleStar(activeProvider.provider, model)}
            />
          ) : null}
        </>
      )}
    </Dialog>
  )
}

export default ModelSelectorDialog
