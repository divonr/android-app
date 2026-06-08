/**
 * components/chat/ModelSelectorDialog.tsx — Model selector dialog.
 *
 * Mirrors ModelSelectorDialog.kt: provider tabs (star + per-provider),
 * model list with star toggle, custom model name entry, search filter.
 *
 * Uses R0 Dialog component.
 */

import React, { useState, useMemo } from 'react'
import Dialog, { DialogButton } from '../../ui/Dialog'
import { MdStar, MdSearch, MdCheck } from '../../ui/icons'
import type { Provider, StarredModel } from '../../api/types'
import { t } from '../../i18n/he'
import { getProviderDisplayName } from './ChatTopBar'

// ── Types ──────────────────────────────────────────────────────────────────────

interface ModelSelectorDialogProps {
  open: boolean
  onClose: () => void
  providers: Provider[]
  currentProvider: string
  currentModel: string
  onSelect: (provider: string, model: string) => void
  starredModels: StarredModel[]
  onToggleStar: (provider: string, modelName: string) => void
}

// ── Star tab page ─────────────────────────────────────────────────────────────

const StarredPage: React.FC<{
  starredModels: StarredModel[]
  providers: Provider[]
  onSelect: (provider: string, model: string) => void
  onToggleStar: (provider: string, modelName: string) => void
}> = ({ starredModels, providers, onSelect, onToggleStar }) => {
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
          fontWeight: 600,
          color: 'var(--on-surface)',
        }}>
          {t('quick_access_title')}
        </span>
        <span style={{
          fontSize: 'var(--fs-body-medium)',
          color: 'var(--on-surface-variant)',
          textAlign: 'center',
        }}>
          {t('starred_hint')}
        </span>
      </div>
    )
  }

  return (
    <div style={{ maxHeight: 280, overflowY: 'auto' }}>
      {starredModels.map((starred) => {
        const prov = providers.find(p => p.provider === starred.provider)
        if (!prov) return null
        return (
          <React.Fragment key={`${starred.provider}/${starred.modelName}`}>
            <button
              type="button"
              onClick={() => onSelect(starred.provider, starred.modelName)}
              style={{
                display: 'flex',
                width: '100%',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '12px 16px',
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                gap: 8,
              }}
            >
              <div style={{ textAlign: 'start', flex: 1, minWidth: 0 }}>
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
                  color: 'var(--on-surface-variant)',
                }}>
                  {getProviderDisplayName(starred.provider)}
                </div>
              </div>
              <button
                type="button"
                aria-label={t('remove_from_starred')}
                onClick={(e) => { e.stopPropagation(); onToggleStar(starred.provider, starred.modelName) }}
                style={{ background: 'none', border: 'none', cursor: 'pointer', flexShrink: 0 }}
              >
                <MdStar size={20} color="var(--primary)" />
              </button>
            </button>
            <div style={{ height: 1, background: 'var(--surface-variant)', margin: '0 8px' }} />
          </React.Fragment>
        )
      })}
    </div>
  )
}

// ── Provider model list page ──────────────────────────────────────────────────

const ProviderModelList: React.FC<{
  provider: Provider
  filter: string
  currentModel: string
  starredModels: StarredModel[]
  onSelect: (model: string) => void
  onToggleStar: (model: string) => void
}> = ({ provider, filter, currentModel, starredModels, onSelect, onToggleStar }) => {
  const filtered = useMemo(() => {
    if (!filter) return provider.models
    const q = filter.toLowerCase()
    return provider.models.filter((m) => (m.name ?? '').toLowerCase().includes(q))
  }, [provider.models, filter])

  return (
    <div style={{ maxHeight: 280, overflowY: 'auto' }}>
      {filtered.map((model) => {
        const name = model.name ?? ''
        const isSelected = name === currentModel
        const isStarred = starredModels.some(
          s => s.provider === provider.provider && s.modelName === name,
        )

        return (
          <React.Fragment key={name}>
            <button
              type="button"
              onClick={() => onSelect(name)}
              style={{
                display: 'flex',
                width: '100%',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '12px 4px 12px 16px',
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                gap: 8,
              }}
            >
              <div style={{ textAlign: 'start', flex: 1, minWidth: 0 }}>
                <span style={{
                  fontSize: 'var(--fs-body-large)',
                  color: isSelected ? 'var(--primary)' : 'var(--on-surface)',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  display: 'block',
                }}>
                  {name}
                  {isSelected && <MdCheck size={14} color="var(--primary)" style={{ marginInlineStart: 4 }} />}
                </span>
              </div>
              <button
                type="button"
                aria-label={isStarred ? t('remove_from_starred') : t('add_to_starred')}
                onClick={(e) => { e.stopPropagation(); onToggleStar(name) }}
                style={{ background: 'none', border: 'none', cursor: 'pointer', padding: '0 12px', flexShrink: 0 }}
              >
                <MdStar size={20} color={isStarred ? 'var(--primary)' : 'var(--on-surface-variant)'} style={{ opacity: isStarred ? 1 : 0.3 }} />
              </button>
            </button>
            <div style={{ height: 1, background: 'var(--surface-variant)', margin: '0 8px' }} />
          </React.Fragment>
        )
      })}
      {filtered.length === 0 && (
        <div style={{
          padding: 16,
          textAlign: 'center',
          color: 'var(--on-surface-variant)',
          fontSize: 'var(--fs-body-medium)',
        }}>
          No models found
        </div>
      )}
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
}) => {
  const [activeTab, setActiveTab] = useState<'stars' | string>('stars')
  const [filter, setFilter] = useState('')
  const [customName, setCustomName] = useState('')

  const activeProvider = providers.find(p => p.provider === activeTab)

  const handleSelect = (provider: string, model: string) => {
    onSelect(provider, model)
    onClose()
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t('model_selector_title')}
      maxWidth={360}
    >
      {/* Custom model entry */}
      <div style={{ marginBottom: 12 }}>
        <div style={{
          fontSize: 'var(--fs-body-medium)',
          color: 'var(--on-surface)',
          marginBottom: 6,
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
            background: 'var(--surface-variant)',
            border: '1px solid var(--message-border)',
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
              const prov = activeProvider ?? providers[0]
              handleSelect(prov.provider, customName.trim())
              setCustomName('')
            }}
            style={{
              marginTop: 6,
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
            {t('use_custom_model')} {customName.trim()}
          </button>
        )}
      </div>

      <div style={{ height: 1, background: 'var(--surface-variant)', margin: '8px 0' }} />

      {providers.length === 0 ? (
        <div style={{
          padding: '16px 0',
          color: 'var(--on-surface-variant)',
          fontSize: 'var(--fs-body-medium)',
        }}>
          {t('no_providers_available')}
        </div>
      ) : (
        <>
          {/* Filter search */}
          <div style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            background: 'var(--surface-variant)',
            borderRadius: 'var(--radius-icon-btn)',
            padding: '4px 10px',
            marginBottom: 8,
          }}>
            <MdSearch size={16} color="var(--on-surface-variant)" />
            <input
              type="search"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="סינון..."
              style={{
                flex: 1,
                background: 'none',
                border: 'none',
                outline: 'none',
                color: 'var(--on-surface)',
                fontSize: 'var(--fs-body-medium)',
                fontFamily: 'inherit',
                direction: 'rtl',
              }}
            />
          </div>

          {/* Provider tabs */}
          <div style={{
            display: 'flex',
            gap: 4,
            overflowX: 'auto',
            paddingBottom: 8,
            scrollbarWidth: 'none',
          }}>
            {/* Stars tab */}
            <button
              type="button"
              onClick={() => setActiveTab('stars')}
              style={{
                flexShrink: 0,
                padding: '4px 8px',
                borderRadius: 8,
                background: activeTab === 'stars' ? 'var(--primary-15)' : 'none',
                border: 'none',
                cursor: 'pointer',
                color: activeTab === 'stars' ? 'var(--primary)' : 'var(--on-surface-variant)',
              }}
            >
              <MdStar size={20} />
            </button>

            {/* Provider tabs */}
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
                  color: activeTab === prov.provider ? 'var(--primary)' : 'var(--on-surface-variant)',
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
              onSelect={handleSelect}
              onToggleStar={onToggleStar}
            />
          ) : activeProvider ? (
            <ProviderModelList
              provider={activeProvider}
              filter={filter}
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
