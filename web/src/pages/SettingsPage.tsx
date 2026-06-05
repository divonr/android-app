import React, { useEffect, useState, useCallback } from 'react'
import { settings as settingsApi } from '../api/client'
import type { AppSettings, TitleGenerationSettings, StarredModel } from '../api/types'
import styles from './SettingsPage.module.css'

// ─── Child lock section ───────────────────────────────────────────────────────

interface ChildLockSectionProps {
  enabled: boolean
  startTime: string
  endTime: string
  onChange: (fields: Partial<AppSettings['childLockSettings']>) => void
}

const ChildLockSection: React.FC<ChildLockSectionProps> = ({ enabled, startTime, endTime, onChange }) => {
  return (
    <div className={styles.subSection}>
      <div className={styles.fieldRow}>
        <label className={styles.fieldLabel}>
          <span>Child Lock</span>
          <span className={styles.fieldHint}>Restrict access during certain hours</span>
        </label>
        <label className={styles.toggle}>
          <input type="checkbox" checked={enabled} onChange={(e) => onChange({ enabled: e.target.checked })} />
          <span className={styles.toggleSlider} />
        </label>
      </div>
      {enabled && (
        <>
          <div className={styles.fieldRow}>
            <label className={styles.fieldLabel}>Start Time</label>
            <input
              type="time"
              className={styles.input}
              value={startTime}
              onChange={(e) => onChange({ startTime: e.target.value })}
              style={{ width: 'auto' }}
            />
          </div>
          <div className={styles.fieldRow}>
            <label className={styles.fieldLabel}>End Time</label>
            <input
              type="time"
              className={styles.input}
              value={endTime}
              onChange={(e) => onChange({ endTime: e.target.value })}
              style={{ width: 'auto' }}
            />
          </div>
        </>
      )}
    </div>
  )
}

// ─── Title generation section ─────────────────────────────────────────────────

interface TitleGenSectionProps {
  settings: TitleGenerationSettings
  onChange: (fields: Partial<TitleGenerationSettings>) => void
}

const TITLE_GEN_PROVIDERS = ['auto', 'openai', 'anthropic', 'google', 'poe', 'cohere']

const TitleGenSection: React.FC<TitleGenSectionProps> = ({ settings, onChange }) => {
  return (
    <div className={styles.subSection}>
      <div className={styles.fieldRow}>
        <label className={styles.fieldLabel}>
          <span>Auto Title Generation</span>
          <span className={styles.fieldHint}>Automatically generate titles for new chats</span>
        </label>
        <label className={styles.toggle}>
          <input type="checkbox" checked={settings.enabled} onChange={(e) => onChange({ enabled: e.target.checked })} />
          <span className={styles.toggleSlider} />
        </label>
      </div>
      {settings.enabled && (
        <>
          <div className={styles.fieldRow}>
            <label className={styles.fieldLabel}>Provider</label>
            <select
              className={styles.select}
              value={settings.provider}
              onChange={(e) => onChange({ provider: e.target.value })}
            >
              {TITLE_GEN_PROVIDERS.map((p) => (
                <option key={p} value={p}>{p}</option>
              ))}
            </select>
          </div>
          <div className={styles.fieldRow}>
            <label className={styles.fieldLabel}>
              <span>Update on Extension</span>
              <span className={styles.fieldHint}>Regenerate title when chat grows</span>
            </label>
            <label className={styles.toggle}>
              <input
                type="checkbox"
                checked={settings.updateOnExtension}
                onChange={(e) => onChange({ updateOnExtension: e.target.checked })}
              />
              <span className={styles.toggleSlider} />
            </label>
          </div>
        </>
      )}
    </div>
  )
}

// ─── Starred models section ───────────────────────────────────────────────────

interface StarredModelsSectionProps {
  models: StarredModel[]
  onChange: (models: StarredModel[]) => void
}

const StarredModelsSection: React.FC<StarredModelsSectionProps> = ({ models, onChange }) => {
  const [newProvider, setNewProvider] = useState('')
  const [newModel, setNewModel] = useState('')

  const add = () => {
    if (!newProvider.trim() || !newModel.trim()) return
    onChange([...models, { provider: newProvider.trim(), modelName: newModel.trim() }])
    setNewProvider('')
    setNewModel('')
  }

  const remove = (i: number) => {
    onChange(models.filter((_, j) => j !== i))
  }

  return (
    <div className={styles.subSection}>
      <div className={styles.subSectionTitle}>Starred Models</div>
      <div className={styles.starredList}>
        {models.map((m, i) => (
          <div key={i} className={styles.starredItem}>
            <span className={styles.starredProvider}>{m.provider}</span>
            <span className={styles.starredModel}>{m.modelName}</span>
            <button className={styles.removeBtn} onClick={() => remove(i)}>✕</button>
          </div>
        ))}
      </div>
      <div className={styles.addStarredRow}>
        <input
          className={styles.inputSmall}
          placeholder="provider"
          value={newProvider}
          onChange={(e) => setNewProvider(e.target.value)}
        />
        <input
          className={styles.inputSmall}
          placeholder="model name"
          value={newModel}
          onChange={(e) => setNewModel(e.target.value)}
        />
        <button className={styles.btnSmall} onClick={add} disabled={!newProvider.trim() || !newModel.trim()}>
          + Star
        </button>
      </div>
    </div>
  )
}

// ─── Main SettingsPage ────────────────────────────────────────────────────────

const SettingsPage: React.FC = () => {
  const [appSettings, setAppSettings] = useState<AppSettings | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [dirty, setDirty] = useState(false)

  useEffect(() => {
    settingsApi.get().then((s) => {
      setAppSettings(s)
      setLoading(false)
    }).catch((err) => {
      setError(err instanceof Error ? err.message : 'Failed to load settings')
      setLoading(false)
    })
  }, [])

  const update = useCallback((fields: Partial<AppSettings>) => {
    setAppSettings((prev) => prev ? { ...prev, ...fields } : prev)
    setDirty(true)
    setSaved(false)
  }, [])

  const handleSave = async () => {
    if (!appSettings) return
    setSaving(true)
    setError(null)
    try {
      const updated = await settingsApi.update(appSettings)
      setAppSettings(updated)
      setDirty(false)
      setSaved(true)
      setTimeout(() => setSaved(false), 2000)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Save failed')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div className={styles.loading}>Loading settings…</div>
  if (!appSettings) return <div className={styles.loading}>Settings unavailable</div>

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Settings</h1>
        <div className={styles.headerActions}>
          {saved && <span className={styles.savedBadge}>✓ Saved</span>}
          <button
            className={styles.btnPrimary}
            onClick={handleSave}
            disabled={saving || !dirty}
          >
            {saving ? 'Saving…' : 'Save Changes'}
          </button>
        </div>
      </div>

      {error && (
        <div className={styles.error} role="alert">
          {error}
          <button onClick={() => setError(null)}>✕</button>
        </div>
      )}

      <div className={styles.sections}>
        {/* Account */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>Account</h2>
          <div className={styles.fieldRow}>
            <label className={styles.fieldLabel}>Username</label>
            <input
              className={styles.input}
              value={appSettings.current_user}
              onChange={(e) => update({ current_user: e.target.value })}
              placeholder="username"
            />
          </div>
        </section>

        {/* Model defaults */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>Model Defaults</h2>
          <div className={styles.fieldRow}>
            <label className={styles.fieldLabel}>Default Provider</label>
            <input
              className={styles.input}
              value={appSettings.selected_provider}
              onChange={(e) => update({ selected_provider: e.target.value })}
              placeholder="openai"
            />
          </div>
          <div className={styles.fieldRow}>
            <label className={styles.fieldLabel}>Default Model</label>
            <input
              className={styles.input}
              value={appSettings.selected_model}
              onChange={(e) => update({ selected_model: e.target.value })}
              placeholder="gpt-4o"
            />
          </div>
          <div className={styles.fieldRow}>
            <label className={styles.fieldLabel}>
              <span>Temperature</span>
              <span className={styles.fieldHint}>0 = deterministic, 1 = creative (null = model default)</span>
            </label>
            <input
              type="number"
              className={styles.input}
              min={0}
              max={2}
              step={0.05}
              value={appSettings.temperature ?? ''}
              onChange={(e) => update({ temperature: parseFloat(e.target.value) || 0 })}
              style={{ width: '80px' }}
            />
          </div>
        </section>

        {/* Title generation */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>Title Generation</h2>
          <TitleGenSection
            settings={appSettings.titleGenerationSettings}
            onChange={(fields) =>
              update({
                titleGenerationSettings: { ...appSettings.titleGenerationSettings, ...fields },
              })
            }
          />
        </section>

        {/* Chat behavior */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>Chat Behavior</h2>
          <div className={styles.fieldRow}>
            <label className={styles.fieldLabel}>
              <span>Multi-Message Mode</span>
              <span className={styles.fieldHint}>Send multiple turns in a single API call</span>
            </label>
            <label className={styles.toggle}>
              <input
                type="checkbox"
                checked={appSettings.multiMessageMode}
                onChange={(e) => update({ multiMessageMode: e.target.checked })}
              />
              <span className={styles.toggleSlider} />
            </label>
          </div>
          <div className={styles.fieldRow}>
            <label className={styles.fieldLabel}>
              <span>Skip Welcome Screen</span>
            </label>
            <label className={styles.toggle}>
              <input
                type="checkbox"
                checked={appSettings.skipWelcomeScreen}
                onChange={(e) => update({ skipWelcomeScreen: e.target.checked })}
              />
              <span className={styles.toggleSlider} />
            </label>
          </div>
        </section>

        {/* Starred models */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>Starred Models</h2>
          <StarredModelsSection
            models={appSettings.starredModels}
            onChange={(models) => update({ starredModels: models })}
          />
        </section>

        {/* Child lock */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>Child Lock</h2>
          <ChildLockSection
            enabled={appSettings.childLockSettings.enabled}
            startTime={appSettings.childLockSettings.startTime}
            endTime={appSettings.childLockSettings.endTime}
            onChange={(fields) =>
              update({
                childLockSettings: { ...appSettings.childLockSettings, ...fields },
              })
            }
          />
        </section>
      </div>
    </div>
  )
}

export default SettingsPage
