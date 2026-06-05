import React, { useEffect, useState, useCallback } from 'react'
import { apiKeys, customProviders, fullCustomProviders } from '../api/client'
import type { ApiKey, CustomProviderConfig, FullCustomProviderConfig } from '../api/types'
import styles from './KeysPage.module.css'

// ─── Provider list (common providers to show in dropdown) ────────────────────

const COMMON_PROVIDERS = [
  'openai', 'anthropic', 'google', 'poe', 'cohere', 'openrouter', 'mistral', 'together', 'custom',
]

// ─── Add Key Form ─────────────────────────────────────────────────────────────

interface AddKeyFormProps {
  onAdd: (provider: string, key: string, customName: string) => Promise<void>
  onCancel: () => void
}

const AddKeyForm: React.FC<AddKeyFormProps> = ({ onAdd, onCancel }) => {
  const [provider, setProvider] = useState('openai')
  const [customProvider, setCustomProvider] = useState('')
  const [key, setKey] = useState('')
  const [customName, setCustomName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async () => {
    const prov = provider === 'custom' ? customProvider.trim() : provider
    if (!prov || !key.trim()) {
      setError('Provider and key are required')
      return
    }
    setBusy(true)
    setError('')
    try {
      await onAdd(prov, key.trim(), customName.trim())
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to add key')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={styles.addForm}>
      <h3>Add API Key</h3>
      <div className={styles.formRow}>
        <label>Provider</label>
        <select
          className={styles.select}
          value={provider}
          onChange={(e) => setProvider(e.target.value)}
        >
          {COMMON_PROVIDERS.map((p) => (
            <option key={p} value={p}>{p}</option>
          ))}
        </select>
      </div>
      {provider === 'custom' && (
        <div className={styles.formRow}>
          <label>Provider name</label>
          <input
            className={styles.input}
            placeholder="e.g. my-provider"
            value={customProvider}
            onChange={(e) => setCustomProvider(e.target.value)}
          />
        </div>
      )}
      <div className={styles.formRow}>
        <label>API Key</label>
        <input
          className={styles.input}
          type="password"
          placeholder="sk-…"
          value={key}
          onChange={(e) => setKey(e.target.value)}
        />
      </div>
      <div className={styles.formRow}>
        <label>Display name (optional)</label>
        <input
          className={styles.input}
          placeholder="e.g. Work key"
          value={customName}
          onChange={(e) => setCustomName(e.target.value)}
        />
      </div>
      {error && <div className={styles.error}>{error}</div>}
      <div className={styles.formActions}>
        <button className={styles.btnSecondary} onClick={onCancel}>Cancel</button>
        <button
          className={styles.btnPrimary}
          onClick={handleSubmit}
          disabled={busy || !key.trim()}
        >
          {busy ? 'Adding…' : 'Add Key'}
        </button>
      </div>
    </div>
  )
}

// ─── Custom Provider Form ─────────────────────────────────────────────────────

interface CustomProviderFormProps {
  initial?: CustomProviderConfig
  onSave: (cfg: CustomProviderConfig) => Promise<void>
  onCancel: () => void
}

const CustomProviderForm: React.FC<CustomProviderFormProps> = ({ initial, onSave, onCancel }) => {
  const [name, setName] = useState(initial?.name ?? '')
  const [baseUrl, setBaseUrl] = useState(initial?.baseUrl ?? '')
  const [apiKey, setApiKey] = useState(initial?.apiKey ?? '')
  const [modelNamesText, setModelNamesText] = useState((initial?.modelNames ?? []).join('\n'))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const handleSave = async () => {
    if (!name.trim() || !baseUrl.trim()) {
      setError('Name and base URL are required')
      return
    }
    setBusy(true)
    setError('')
    try {
      await onSave({
        id: initial?.id,
        name: name.trim(),
        baseUrl: baseUrl.trim(),
        apiKey: apiKey.trim() || null,
        modelNames: modelNamesText.split('\n').map((s) => s.trim()).filter(Boolean),
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={styles.addForm}>
      <h3>{initial ? 'Edit' : 'Add'} Custom Provider</h3>
      <div className={styles.formRow}>
        <label>Name</label>
        <input className={styles.input} value={name} onChange={(e) => setName(e.target.value)} placeholder="My Provider" />
      </div>
      <div className={styles.formRow}>
        <label>Base URL</label>
        <input className={styles.input} value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://api.example.com/v1" />
      </div>
      <div className={styles.formRow}>
        <label>API Key (optional)</label>
        <input className={styles.input} type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="sk-…" />
      </div>
      <div className={styles.formRow}>
        <label>Model names (one per line)</label>
        <textarea className={styles.textarea} value={modelNamesText} onChange={(e) => setModelNamesText(e.target.value)} rows={4} placeholder="gpt-4o&#10;gpt-3.5-turbo" />
      </div>
      {error && <div className={styles.error}>{error}</div>}
      <div className={styles.formActions}>
        <button className={styles.btnSecondary} onClick={onCancel}>Cancel</button>
        <button className={styles.btnPrimary} onClick={handleSave} disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
      </div>
    </div>
  )
}

// ─── Full Custom Provider Form ────────────────────────────────────────────────

interface FullCustomProviderFormProps {
  initial?: FullCustomProviderConfig
  onSave: (cfg: FullCustomProviderConfig) => Promise<void>
  onCancel: () => void
}

const FullCustomProviderForm: React.FC<FullCustomProviderFormProps> = ({ initial, onSave, onCancel }) => {
  const [name, setName] = useState(initial?.name ?? '')
  const [baseUrl, setBaseUrl] = useState(initial?.baseUrl ?? '')
  const [apiKey, setApiKey] = useState(initial?.apiKey ?? '')
  const [modelNamesText, setModelNamesText] = useState((initial?.modelNames ?? []).join('\n'))
  const [requestTemplate, setRequestTemplate] = useState(
    initial?.requestTemplate ? JSON.stringify(initial.requestTemplate, null, 2) : '',
  )
  const [responseMapping, setResponseMapping] = useState(
    initial?.responseMapping ? JSON.stringify(initial.responseMapping, null, 2) : '',
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const handleSave = async () => {
    if (!name.trim() || !baseUrl.trim()) {
      setError('Name and base URL are required')
      return
    }
    let req: Record<string, unknown> | null = null
    let res: Record<string, unknown> | null = null
    try {
      if (requestTemplate.trim()) req = JSON.parse(requestTemplate)
      if (responseMapping.trim()) res = JSON.parse(responseMapping)
    } catch {
      setError('Invalid JSON in request template or response mapping')
      return
    }
    setBusy(true)
    setError('')
    try {
      await onSave({
        id: initial?.id,
        name: name.trim(),
        baseUrl: baseUrl.trim(),
        apiKey: apiKey.trim() || null,
        modelNames: modelNamesText.split('\n').map((s) => s.trim()).filter(Boolean),
        requestTemplate: req,
        responseMapping: res,
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={styles.addForm}>
      <h3>{initial ? 'Edit' : 'Add'} Full Custom Provider</h3>
      <div className={styles.formRow}>
        <label>Name</label>
        <input className={styles.input} value={name} onChange={(e) => setName(e.target.value)} placeholder="My Provider" />
      </div>
      <div className={styles.formRow}>
        <label>Base URL</label>
        <input className={styles.input} value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://api.example.com/v1" />
      </div>
      <div className={styles.formRow}>
        <label>API Key (optional)</label>
        <input className={styles.input} type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} />
      </div>
      <div className={styles.formRow}>
        <label>Model names (one per line)</label>
        <textarea className={styles.textarea} value={modelNamesText} onChange={(e) => setModelNamesText(e.target.value)} rows={3} />
      </div>
      <div className={styles.formRow}>
        <label>Request template (JSON, optional)</label>
        <textarea className={`${styles.textarea} ${styles.codeArea}`} value={requestTemplate} onChange={(e) => setRequestTemplate(e.target.value)} rows={6} placeholder='{"messages": ..., "model": ...}' />
      </div>
      <div className={styles.formRow}>
        <label>Response mapping (JSON, optional)</label>
        <textarea className={`${styles.textarea} ${styles.codeArea}`} value={responseMapping} onChange={(e) => setResponseMapping(e.target.value)} rows={4} />
      </div>
      {error && <div className={styles.error}>{error}</div>}
      <div className={styles.formActions}>
        <button className={styles.btnSecondary} onClick={onCancel}>Cancel</button>
        <button className={styles.btnPrimary} onClick={handleSave} disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
      </div>
    </div>
  )
}

// ─── Main KeysPage ────────────────────────────────────────────────────────────

type ActiveSection = 'keys' | 'custom' | 'fullcustom'
type FormMode = 'none' | 'add-key' | 'add-custom' | 'edit-custom' | 'add-full' | 'edit-full'

const KeysPage: React.FC = () => {
  const [keys, setKeys] = useState<ApiKey[]>([])
  const [customList, setCustomList] = useState<CustomProviderConfig[]>([])
  const [fullList, setFullList] = useState<FullCustomProviderConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeSection, setActiveSection] = useState<ActiveSection>('keys')
  const [formMode, setFormMode] = useState<FormMode>('none')
  const [editCustom, setEditCustom] = useState<CustomProviderConfig | undefined>()
  const [editFull, setEditFull] = useState<FullCustomProviderConfig | undefined>()

  const loadAll = useCallback(async () => {
    setLoading(true)
    try {
      const [k, c, f] = await Promise.all([
        apiKeys.list(),
        customProviders.list(),
        fullCustomProviders.list(),
      ])
      setKeys(k)
      setCustomList(c)
      setFullList(f)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Load failed')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadAll() }, [loadAll])

  // ── Key actions ────────────────────────────────────────────────────────────

  const handleAddKey = async (provider: string, key: string, customName: string) => {
    await apiKeys.create({
      provider,
      key,
      isActive: true,
      customName: customName || null,
    })
    setFormMode('none')
    await loadAll()
  }

  const handleToggleKey = async (keyId: string) => {
    try {
      const updated = await apiKeys.toggle(keyId)
      setKeys((prev) => prev.map((k) => k.id === updated.id ? updated : k))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Toggle failed')
    }
  }

  const handleDeleteKey = async (keyId: string) => {
    if (!window.confirm('Delete this API key?')) return
    try {
      await apiKeys.delete(keyId)
      setKeys((prev) => prev.filter((k) => k.id !== keyId))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Delete failed')
    }
  }

  // ── Custom provider actions ────────────────────────────────────────────────

  const handleSaveCustom = async (cfg: CustomProviderConfig) => {
    if (cfg.id) {
      await customProviders.replace(cfg.id, cfg)
    } else {
      await customProviders.create(cfg)
    }
    setFormMode('none')
    setEditCustom(undefined)
    await loadAll()
  }

  const handleDeleteCustom = async (id: string) => {
    if (!window.confirm('Delete this custom provider?')) return
    try {
      await customProviders.delete(id)
      await loadAll()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Delete failed')
    }
  }

  const handleSaveFull = async (cfg: FullCustomProviderConfig) => {
    if (cfg.id) {
      await fullCustomProviders.replace(cfg.id, cfg)
    } else {
      await fullCustomProviders.create(cfg)
    }
    setFormMode('none')
    setEditFull(undefined)
    await loadAll()
  }

  const handleDeleteFull = async (id: string) => {
    if (!window.confirm('Delete this provider?')) return
    try {
      await fullCustomProviders.delete(id)
      await loadAll()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Delete failed')
    }
  }

  // ── Reorder keys ──────────────────────────────────────────────────────────

  const handleReorder = async (fromIndex: number, toIndex: number) => {
    try {
      const updated = await apiKeys.reorder({ fromIndex, toIndex })
      setKeys(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Reorder failed')
    }
  }

  // ── Render ────────────────────────────────────────────────────────────────

  if (loading) {
    return <div className={styles.loading}>Loading…</div>
  }

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>API Keys & Providers</h1>
      </div>

      {error && (
        <div className={styles.error} role="alert">
          {error}
          <button onClick={() => setError(null)}>✕</button>
        </div>
      )}

      {/* Tabs */}
      <div className={styles.tabs}>
        {(['keys', 'custom', 'fullcustom'] as ActiveSection[]).map((s) => (
          <button
            key={s}
            className={`${styles.tab} ${activeSection === s ? styles.tabActive : ''}`}
            onClick={() => { setActiveSection(s); setFormMode('none') }}
          >
            {s === 'keys' ? 'API Keys' : s === 'custom' ? 'Custom Providers' : 'Full Custom Providers'}
          </button>
        ))}
      </div>

      {/* Form section */}
      {formMode === 'add-key' && (
        <AddKeyForm onAdd={handleAddKey} onCancel={() => setFormMode('none')} />
      )}
      {(formMode === 'add-custom' || formMode === 'edit-custom') && (
        <CustomProviderForm
          initial={editCustom}
          onSave={handleSaveCustom}
          onCancel={() => { setFormMode('none'); setEditCustom(undefined) }}
        />
      )}
      {(formMode === 'add-full' || formMode === 'edit-full') && (
        <FullCustomProviderForm
          initial={editFull}
          onSave={handleSaveFull}
          onCancel={() => { setFormMode('none'); setEditFull(undefined) }}
        />
      )}

      {/* API Keys list */}
      {activeSection === 'keys' && formMode === 'none' && (
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <span className={styles.sectionCount}>{keys.length} key{keys.length !== 1 ? 's' : ''}</span>
            <button
              className={styles.btnPrimary}
              onClick={() => setFormMode('add-key')}
            >
              + Add Key
            </button>
          </div>
          {keys.length === 0 ? (
            <div className={styles.empty}>No API keys yet. Add one to start chatting.</div>
          ) : (
            <div className={styles.list}>
              {keys.map((k, idx) => (
                <div key={k.id} className={`${styles.keyRow} ${!k.isActive ? styles.keyRowInactive : ''}`}>
                  <div className={styles.keyInfo}>
                    <span className={styles.keyProvider}>{k.provider}</span>
                    {k.customName && <span className={styles.keyName}>{k.customName}</span>}
                    <span className={styles.keyMasked}>{k.key}</span>
                  </div>
                  <div className={styles.keyActions}>
                    {idx > 0 && (
                      <button
                        className={styles.iconBtn}
                        onClick={() => handleReorder(idx, idx - 1)}
                        title="Move up"
                      >↑</button>
                    )}
                    {idx < keys.length - 1 && (
                      <button
                        className={styles.iconBtn}
                        onClick={() => handleReorder(idx, idx + 1)}
                        title="Move down"
                      >↓</button>
                    )}
                    <button
                      className={`${styles.toggleBtn} ${k.isActive ? styles.toggleActive : ''}`}
                      onClick={() => handleToggleKey(k.id)}
                      title={k.isActive ? 'Disable' : 'Enable'}
                    >
                      {k.isActive ? 'Active' : 'Inactive'}
                    </button>
                    <button
                      className={`${styles.iconBtn} ${styles.deleteBtn}`}
                      onClick={() => handleDeleteKey(k.id)}
                      title="Delete"
                    >
                      🗑
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Custom Providers list */}
      {activeSection === 'custom' && formMode === 'none' && (
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <span className={styles.sectionCount}>{customList.length} provider{customList.length !== 1 ? 's' : ''}</span>
            <button className={styles.btnPrimary} onClick={() => setFormMode('add-custom')}>
              + Add Provider
            </button>
          </div>
          {customList.length === 0 ? (
            <div className={styles.empty}>
              No custom providers. Custom providers allow using OpenAI-compatible APIs.
            </div>
          ) : (
            <div className={styles.list}>
              {customList.map((cp) => (
                <div key={cp.id} className={styles.providerRow}>
                  <div className={styles.providerInfo}>
                    <span className={styles.providerName}>{cp.name}</span>
                    <span className={styles.providerUrl}>{cp.baseUrl}</span>
                    <span className={styles.providerModels}>{cp.modelNames.length} model{cp.modelNames.length !== 1 ? 's' : ''}</span>
                  </div>
                  <div className={styles.keyActions}>
                    <button
                      className={styles.iconBtn}
                      onClick={() => { setEditCustom(cp); setFormMode('edit-custom') }}
                    >
                      ✏️
                    </button>
                    <button
                      className={`${styles.iconBtn} ${styles.deleteBtn}`}
                      onClick={() => handleDeleteCustom(cp.id!)}
                    >
                      🗑
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Full Custom Providers list */}
      {activeSection === 'fullcustom' && formMode === 'none' && (
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <span className={styles.sectionCount}>{fullList.length} provider{fullList.length !== 1 ? 's' : ''}</span>
            <button className={styles.btnPrimary} onClick={() => setFormMode('add-full')}>
              + Add Full Provider
            </button>
          </div>
          {fullList.length === 0 ? (
            <div className={styles.empty}>
              No full custom providers. These allow fully custom request/response templates.
            </div>
          ) : (
            <div className={styles.list}>
              {fullList.map((fp) => (
                <div key={fp.id} className={styles.providerRow}>
                  <div className={styles.providerInfo}>
                    <span className={styles.providerName}>{fp.name}</span>
                    <span className={styles.providerUrl}>{fp.baseUrl}</span>
                    <span className={styles.providerModels}>{fp.modelNames.length} model{fp.modelNames.length !== 1 ? 's' : ''}</span>
                  </div>
                  <div className={styles.keyActions}>
                    <button
                      className={styles.iconBtn}
                      onClick={() => { setEditFull(fp); setFormMode('edit-full') }}
                    >
                      ✏️
                    </button>
                    <button
                      className={`${styles.iconBtn} ${styles.deleteBtn}`}
                      onClick={() => handleDeleteFull(fp.id!)}
                    >
                      🗑
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export default KeysPage
