/**
 * SettingsPage — R5 refactor.
 *
 * Visual + UX parity with Android UsernameScreen.kt + component files:
 *   - ScreenTopBar: back arrow + Hebrew title "הגדרות מתקדמות"
 *   - User info card: current username (read-only, display)
 *   - TitleGenerationSettingsSection: enable toggle + provider selector + update-on-extension
 *   - Multi-message mode: toggle with explainer
 *   - ChildLockSettingsSection: enable toggle → setup/disable dialogs → time display when enabled
 *   - RemoteSyncSection: enable toggle + server URL + auth token (show/hide) + sync-api-keys
 *       toggle with warning + Sync-Now button + Test-Connection with live status
 *
 * Auto-save strategy (matches Android immediate-save):
 *   - Toggle switches: save immediately via settings.update({ field })
 *   - Text inputs (serverUrl, authToken): debounced 800ms auto-save
 *   - Child lock dialogs: save on confirm
 *
 * Remote sync note:
 *   - "Sync Now" → POST /api/sync/pull (wired)
 *   - "Test Connection" → display-only visual stub; no server test endpoint exists.
 *     R6/R7 can add POST /api/sync/test-connection to the server.
 */

import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { settings as settingsApi, sync as syncApi } from '../api/client'
import type { AppSettings, ChildLockSettings, RemoteSyncSettings, TitleGenerationSettings } from '../api/types'
import { sha256 } from '../utils/crypto'
import { isInLockRange } from '../utils/childLock'
import { t } from '../i18n/he'
import ScreenTopBar from '../components/ScreenTopBar'
import Dialog, { DialogButton } from '../ui/Dialog'
import {
  MdPerson,
  MdAccessTime,
  MdVisibility,
  MdVisibilityOff,
  MdExpandMore,
  MdExpandLess,
  MdLock,
  MdSync,
} from '../ui/icons'
import styles from './SettingsPage.module.css'

// ─── Helpers ──────────────────────────────────────────────────────────────────

const DEFAULT_REMOTE_SYNC: RemoteSyncSettings = {
  enabled: false,
  serverBaseUrl: 'https://sync.api-divonr.xyz',
  authToken: '',
  syncApiKeys: false,
}

const PROVIDERS_FOR_TITLE_GEN = [
  { key: 'auto', label: t('auto_mode') },
  { key: 'openai', label: t('openai_gpt5_nano') },
  { key: 'poe', label: t('poe_gpt5_nano') },
  { key: 'google', label: t('google_gemini_flash_lite') },
  { key: 'anthropic', label: t('anthropic_claude_haiku') },
  { key: 'cohere', label: t('cohere_command_r7b') },
]

function getProviderLabel(key: string): string {
  return PROVIDERS_FOR_TITLE_GEN.find((p) => p.key === key)?.label ?? key
}

// sha256 and isInLockRange are imported from utils/crypto and utils/childLock (A3 dedup).

// ─── TimePicker component ─────────────────────────────────────────────────────

interface TimePickerProps {
  initialTime: string
  onSelect: (time: string) => void
  onCancel: () => void
}

const COMMON_HOURS = [0, 6, 7, 8, 9, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23]

const TimePicker: React.FC<TimePickerProps> = ({ initialTime, onSelect, onCancel }) => {
  const parts = initialTime.split(':').map((s) => parseInt(s, 10))
  const [hour, setHour] = useState(isNaN(parts[0]) ? 0 : parts[0])
  const [minute, setMinute] = useState(isNaN(parts[1]) ? 0 : parts[1])
  const [quickMode, setQuickMode] = useState(false)

  const incHour = () => setHour((h) => (h >= 23 ? 0 : h + 1))
  const decHour = () => setHour((h) => (h <= 0 ? 23 : h - 1))
  const incMinute = () => setMinute((m) => (m >= 59 ? 0 : m + 1))
  const decMinute = () => setMinute((m) => (m <= 0 ? 59 : m - 1))

  const formatted = `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`

  return (
    <Dialog
      open
      title={t('time_picker_title')}
      onClose={onCancel}
      actions={
        <>
          <DialogButton label={t('cancel')} onClick={onCancel} />
          <DialogButton label={t('approve')} primary onClick={() => onSelect(formatted)} />
        </>
      }
    >
      <div className={styles.timePicker}>
        {/* Mode toggle */}
        <div className={styles.modeToggleRow}>
          <button className={styles.modeToggleBtn} onClick={() => setQuickMode((q) => !q)}>
            {quickMode ? t('time_picker_precise_mode') : t('time_picker_quick_mode')}
          </button>
        </div>

        {/* Digital display */}
        <div className={styles.timeDisplay}>{formatted}</div>

        {quickMode ? (
          /* Quick-select chips */
          <div className={styles.quickHoursSection}>
            <div className={styles.sectionHint}>{t('time_picker_common_hours')}</div>
            <div className={styles.chipsRow}>
              {COMMON_HOURS.map((h) => (
                <button
                  key={h}
                  className={[styles.chip, hour === h ? styles.chipSelected : ''].join(' ')}
                  onClick={() => setHour(h)}
                >
                  {String(h).padStart(2, '0')}
                </button>
              ))}
            </div>
            <div className={styles.sectionHint}>{t('time_picker_minutes_label')}</div>
            <div className={styles.chipsRow}>
              {[0, 15, 30, 45].map((m) => (
                <button
                  key={m}
                  className={[styles.chip, minute === m ? styles.chipSelected : ''].join(' ')}
                  onClick={() => setMinute(m)}
                >
                  {String(m).padStart(2, '0')}
                </button>
              ))}
            </div>
          </div>
        ) : (
          /* Number-picker wheels */
          <div className={styles.timePickerCols}>
            {/* Minute */}
            <div className={styles.numberPicker}>
              <div className={styles.numberPickerLabel}>{t('time_picker_minutes')}</div>
              <button className={styles.pickerArrow} onClick={incMinute}>▲</button>
              <div className={styles.pickerAdjacent}>
                {String(minute <= 0 ? 59 : minute - 1).padStart(2, '0')}
              </div>
              <div className={styles.pickerValue}>{String(minute).padStart(2, '0')}</div>
              <div className={styles.pickerAdjacent}>
                {String(minute >= 59 ? 0 : minute + 1).padStart(2, '0')}
              </div>
              <button className={styles.pickerArrow} onClick={decMinute}>▼</button>
            </div>

            <div className={styles.timePickerColon}>:</div>

            {/* Hour */}
            <div className={styles.numberPicker}>
              <div className={styles.numberPickerLabel}>{t('time_picker_hours')}</div>
              <button className={styles.pickerArrow} onClick={incHour}>▲</button>
              <div className={styles.pickerAdjacent}>
                {String(hour <= 0 ? 23 : hour - 1).padStart(2, '0')}
              </div>
              <div className={styles.pickerValue}>{String(hour).padStart(2, '0')}</div>
              <div className={styles.pickerAdjacent}>
                {String(hour >= 23 ? 0 : hour + 1).padStart(2, '0')}
              </div>
              <button className={styles.pickerArrow} onClick={decHour}>▼</button>
            </div>
          </div>
        )}
      </div>
    </Dialog>
  )
}

// ─── ChildLockSetupDialog ─────────────────────────────────────────────────────

interface ChildLockSetupProps {
  onConfirm: (password: string, start: string, end: string) => void
  onCancel: () => void
}

const ChildLockSetupDialog: React.FC<ChildLockSetupProps> = ({ onConfirm, onCancel }) => {
  const [password, setPassword] = useState('')
  const [passVisible, setPassVisible] = useState(false)
  const [startTime, setStartTime] = useState('23:00')
  const [endTime, setEndTime] = useState('07:00')
  const [pickerFor, setPickerFor] = useState<'start' | 'end' | null>(null)

  return (
    <>
      <Dialog
        open
        title={t('child_lock_setup_title')}
        onClose={onCancel}
        actions={
          <>
            <DialogButton label={t('cancel')} onClick={onCancel} />
            <DialogButton label={t('approve')} primary disabled={!password.trim()} onClick={() => onConfirm(password, startTime, endTime)} />
          </>
        }
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <p style={{ fontSize: 'var(--fs-body-small)', color: 'var(--on-surface-variant)', lineHeight: 1.5 }}>
            {t('child_lock_setup_warning')}
          </p>

          {/* Password field */}
          <div className={styles.dialogField}>
            <div className={styles.dialogLabel}>{t('child_lock_password_label')}</div>
            <div className={styles.dialogInputWrap}>
              <input
                type={passVisible ? 'text' : 'password'}
                className={[styles.dialogInput, styles.hasTrailing].join(' ')}
                placeholder={t('child_lock_password_placeholder')}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                style={{ paddingInlineEnd: 40 }}
              />
              <button className={styles.dialogInputTrailing} onClick={() => setPassVisible((v) => !v)} type="button">
                {passVisible ? <MdVisibilityOff size={18} /> : <MdVisibility size={18} />}
              </button>
            </div>
          </div>

          {/* Time selection */}
          <div className={styles.timeSelectRow}>
            <div className={styles.timeSelectCol}>
              <div className={styles.dialogLabel}>{t('child_lock_from')}</div>
              <button className={styles.timePickerBtn} onClick={() => setPickerFor('start')}>
                <span>{startTime}</span>
                <MdAccessTime size={18} />
              </button>
            </div>
            <div className={styles.timeSelectCol}>
              <div className={styles.dialogLabel}>{t('child_lock_until')}</div>
              <button className={styles.timePickerBtn} onClick={() => setPickerFor('end')}>
                <span>{endTime}</span>
                <MdAccessTime size={18} />
              </button>
            </div>
          </div>
        </div>
      </Dialog>

      {pickerFor === 'start' && (
        <TimePicker
          initialTime={startTime}
          onSelect={(t) => { setStartTime(t); setPickerFor(null) }}
          onCancel={() => setPickerFor(null)}
        />
      )}
      {pickerFor === 'end' && (
        <TimePicker
          initialTime={endTime}
          onSelect={(t) => { setEndTime(t); setPickerFor(null) }}
          onCancel={() => setPickerFor(null)}
        />
      )}
    </>
  )
}

// ─── ChildLockDisableDialog ───────────────────────────────────────────────────

interface ChildLockDisableProps {
  storedHash: string
  onConfirm: () => void
  onCancel: () => void
}

const ChildLockDisableDialog: React.FC<ChildLockDisableProps> = ({ storedHash, onConfirm, onCancel }) => {
  const [password, setPassword] = useState('')
  const [passVisible, setPassVisible] = useState(false)
  const [wrong, setWrong] = useState(false)

  const verify = async () => {
    const hash = await sha256(password)
    if (hash === storedHash) {
      onConfirm()
    } else {
      setWrong(true)
      setPassword('')
    }
  }

  return (
    <Dialog
      open
      title={t('child_lock_disable_title')}
      onClose={onCancel}
      actions={
        <>
          <DialogButton label={t('cancel')} onClick={onCancel} />
          <DialogButton label={t('approve')} primary disabled={!password.trim()} onClick={verify} />
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <p style={{ fontSize: 'var(--fs-body-small)', color: 'var(--on-surface-variant)' }}>
          {t('child_lock_disable_body')}
        </p>
        <div className={styles.dialogField}>
          <div className={styles.dialogInputWrap}>
            <input
              type={passVisible ? 'text' : 'password'}
              className={styles.dialogInput}
              placeholder={t('child_lock_password_placeholder')}
              value={password}
              onChange={(e) => { setPassword(e.target.value); setWrong(false) }}
              style={{ paddingInlineEnd: 40 }}
            />
            <button className={styles.dialogInputTrailing} onClick={() => setPassVisible((v) => !v)} type="button">
              {passVisible ? <MdVisibilityOff size={18} /> : <MdVisibility size={18} />}
            </button>
          </div>
          {wrong && (
            <div style={{ color: 'var(--accent-red)', fontSize: 'var(--fs-body-small)' }}>
              {t('child_lock_wrong_password')}
            </div>
          )}
        </div>
      </div>
    </Dialog>
  )
}

// ─── Main SettingsPage ────────────────────────────────────────────────────────

const SettingsPage: React.FC = () => {
  const navigate = useNavigate()
  const [appSettings, setAppSettings] = useState<AppSettings | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Title gen
  const [providerOpen, setProviderOpen] = useState(false)

  // Child lock dialogs
  const [showChildLockSetup, setShowChildLockSetup] = useState(false)
  const [showChildLockDisable, setShowChildLockDisable] = useState(false)

  // Remote sync local text state (debounced save)
  const [syncServerUrl, setSyncServerUrl] = useState('')
  const [syncAuthToken, setSyncAuthToken] = useState('')
  const [tokenVisible, setTokenVisible] = useState(false)
  const [syncNowDone, setSyncNowDone] = useState(false)
  const [testResult, setTestResult] = useState<'ok' | 'fail' | null>(null)
  const [testInProgress, setTestInProgress] = useState(false)

  const syncUrlDebounce = useRef<ReturnType<typeof setTimeout>>()
  const syncTokenDebounce = useRef<ReturnType<typeof setTimeout>>()

  // ── Load ──────────────────────────────────────────────────────────────────

  useEffect(() => {
    settingsApi.get().then((s) => {
      setAppSettings(s)
      const remSync = s.remoteSync ?? DEFAULT_REMOTE_SYNC
      setSyncServerUrl(remSync.serverBaseUrl)
      setSyncAuthToken(remSync.authToken)
      setLoading(false)
    }).catch((err) => {
      setError(err instanceof Error ? err.message : 'Failed to load settings')
      setLoading(false)
    })
  }, [])

  // ── Auto-save helper ──────────────────────────────────────────────────────

  const autoSave = useCallback(async (patch: Partial<AppSettings>) => {
    setAppSettings((prev) => prev ? { ...prev, ...patch } : prev)
    try {
      await settingsApi.update(patch)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Save failed')
    }
  }, [])

  // ── Title Generation ──────────────────────────────────────────────────────

  const updateTitleGen = (patch: Partial<TitleGenerationSettings>) => {
    if (!appSettings) return
    const updated = { ...appSettings.titleGenerationSettings, ...patch }
    autoSave({ titleGenerationSettings: updated })
  }

  // ── Multi-message mode ────────────────────────────────────────────────────

  const updateMultiMessage = (enabled: boolean) => autoSave({ multiMessageMode: enabled })

  // ── Child lock ────────────────────────────────────────────────────────────

  const handleChildLockToggle = (enabled: boolean) => {
    if (!appSettings) return
    if (enabled) {
      setShowChildLockSetup(true)
    } else {
      if (appSettings.childLockSettings.encryptedPassword) {
        setShowChildLockDisable(true)
      } else {
        // No password set, just disable
        const updatedCl: ChildLockSettings = {
          ...appSettings.childLockSettings,
          enabled: false,
          encryptedPassword: '',
        }
        autoSave({ childLockSettings: updatedCl })
      }
    }
  }

  const handleChildLockSetup = async (password: string, start: string, end: string) => {
    setShowChildLockSetup(false)
    const hash = await sha256(password)
    const updatedCl: ChildLockSettings = {
      enabled: true,
      encryptedPassword: hash,
      startTime: start,
      endTime: end,
    }
    autoSave({ childLockSettings: updatedCl })
  }

  const handleChildLockDisable = () => {
    setShowChildLockDisable(false)
    const updatedCl: ChildLockSettings = {
      enabled: false,
      encryptedPassword: '',
      startTime: appSettings?.childLockSettings.startTime ?? '23:00',
      endTime: appSettings?.childLockSettings.endTime ?? '07:00',
    }
    autoSave({ childLockSettings: updatedCl })
  }

  // ── Remote sync text fields (debounced) ───────────────────────────────────

  const updateSyncUrl = (url: string) => {
    setSyncServerUrl(url)
    clearTimeout(syncUrlDebounce.current)
    syncUrlDebounce.current = setTimeout(() => {
      if (!appSettings) return
      const updated: RemoteSyncSettings = {
        ...(appSettings.remoteSync ?? DEFAULT_REMOTE_SYNC),
        serverBaseUrl: url,
      }
      autoSave({ remoteSync: updated })
    }, 800)
  }

  const updateSyncToken = (token: string) => {
    setSyncAuthToken(token)
    clearTimeout(syncTokenDebounce.current)
    syncTokenDebounce.current = setTimeout(() => {
      if (!appSettings) return
      const updated: RemoteSyncSettings = {
        ...(appSettings.remoteSync ?? DEFAULT_REMOTE_SYNC),
        authToken: token,
      }
      autoSave({ remoteSync: updated })
    }, 800)
  }

  const updateSyncField = (patch: Partial<RemoteSyncSettings>) => {
    if (!appSettings) return
    const updated: RemoteSyncSettings = {
      ...(appSettings.remoteSync ?? DEFAULT_REMOTE_SYNC),
      ...patch,
    }
    autoSave({ remoteSync: updated })
  }

  const handleSyncNow = async () => {
    try {
      await syncApi.pull()
      setSyncNowDone(true)
      setTimeout(() => setSyncNowDone(false), 3000)
    } catch {
      setError('Sync failed')
    }
  }

  // Test connection: display-only stub — no server test endpoint exists.
  const handleTestConnection = async () => {
    setTestResult(null)
    setTestInProgress(true)
    try {
      await syncApi.status()
      setTestResult('ok')
    } catch {
      setTestResult('fail')
    } finally {
      setTestInProgress(false)
    }
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  if (loading) return <div className={styles.loading}>...</div>
  if (!appSettings) return <div className={styles.loading}>Settings unavailable</div>

  const remoteSync = appSettings.remoteSync ?? DEFAULT_REMOTE_SYNC
  const cl = appSettings.childLockSettings
  const tg = appSettings.titleGenerationSettings

  return (
    <div className={styles.page} dir="rtl">
      <ScreenTopBar
        title={t('advanced_settings')}
        onBack={() => navigate(-1)}
        headingAriaLabel="Advanced Settings"
      />

      <div className={styles.content}>
        {error && (
          <div className={styles.error} role="alert">
            {error}
            <button className={styles.errorClose} onClick={() => setError(null)}>✕</button>
          </div>
        )}

        {/* ── User info ── */}
        <div className={styles.userCard}>
          <div className={styles.userAvatar}>
            <MdPerson size={22} />
          </div>
          <div className={styles.userInfo}>
            <div className={styles.userName}>{appSettings.current_user}</div>
            <div className={styles.userLabel}>{t('current_user_label')}</div>
          </div>
        </div>

        {/* ── Navigation: Integrations ── */}
        <button
          className={styles.navRow}
          onClick={() => navigate('/integrations')}
          aria-label="Integrations"
        >
          <span className={styles.navRowTitle}>אינטגרציות (MCPs)</span>
          <span style={{ fontSize: 18, color: 'var(--on-surface-variant)', opacity: 0.7 }}>›</span>
        </button>

        {/* ── Navigation: Skills ── */}
        <button
          className={styles.navRow}
          onClick={() => navigate('/skills')}
          aria-label="Skills"
        >
          <span className={styles.navRowTitle}>סקילים (Skills)</span>
          <span style={{ fontSize: 18, color: 'var(--on-surface-variant)', opacity: 0.7 }}>›</span>
        </button>

        {/* ── Title generation ── */}
        <div className={styles.card}>
          <div className={styles.sectionRow}>
            <div className={styles.sectionLabel}>
              <h2
                className={styles.sectionTitle}
                aria-label="Title Generation"
              >
                {t('auto_generate_title')}
              </h2>
              <span className={styles.sectionHint}>{t('ai_api_call_note')}</span>
            </div>
            <label className={styles.toggle}>
              <input
                type="checkbox"
                checked={tg.enabled}
                onChange={(e) => updateTitleGen({ enabled: e.target.checked })}
              />
              <span className={styles.toggleSlider} />
            </label>
          </div>

          {tg.enabled && (
            <>
              <div className={styles.spacer} />

              {/* Provider selector */}
              <button className={styles.providerBtn} onClick={() => setProviderOpen((o) => !o)}>
                <div className={styles.providerBtnLabel}>
                  <span className={styles.providerBtnTitle}>{t('select_model')}</span>
                  <span className={styles.providerBtnValue}>{getProviderLabel(tg.provider)}</span>
                </div>
                {providerOpen ? <MdExpandLess size={22} /> : <MdExpandMore size={22} />}
              </button>

              {providerOpen && (
                <div className={styles.providerList}>
                  {PROVIDERS_FOR_TITLE_GEN.map((p) => (
                    <label key={p.key} className={styles.providerOption}>
                      <input
                        type="radio"
                        name="titleGenProvider"
                        value={p.key}
                        checked={tg.provider === p.key}
                        onChange={() => { updateTitleGen({ provider: p.key }); setProviderOpen(false) }}
                        style={{ accentColor: 'var(--primary)', width: 16, height: 16 }}
                      />
                      <span style={{ fontSize: 'var(--fs-body-medium)', color: 'var(--on-surface)' }}>{p.label}</span>
                    </label>
                  ))}
                </div>
              )}

              <div className={styles.spacer} />

              {/* Update on extension */}
              <div className={styles.checkboxRow}>
                <input
                  type="checkbox"
                  className="appCheck"
                  id="updateOnExt"
                  checked={tg.updateOnExtension}
                  onChange={(e) => updateTitleGen({ updateOnExtension: e.target.checked })}
                  style={{ width: 20, height: 20, accentColor: 'var(--primary)', cursor: 'pointer', flexShrink: 0 }}
                />
                <label htmlFor="updateOnExt" className={styles.checkboxLabel}>
                  <span>{t('update_title_on_extension')}</span>
                  <span className={styles.checkboxHint}>{t('after_3_responses')}</span>
                </label>
              </div>
            </>
          )}
        </div>

        {/* ── Multi-message mode ── */}
        <div className={styles.cardSmall}>
          <div className={styles.sectionRow}>
            <div className={styles.sectionLabel}>
              <h2 className={styles.sectionTitle} aria-label="Multi-Message Mode">
                {t('multi_message_mode')}
              </h2>
              <span className={styles.sectionHint}>{t('multi_message_mode_explainer')}</span>
            </div>
            <label className={styles.toggle}>
              <input
                type="checkbox"
                checked={appSettings.multiMessageMode}
                onChange={(e) => updateMultiMessage(e.target.checked)}
              />
              <span className={styles.toggleSlider} />
            </label>
          </div>
        </div>

        {/* ── Child lock ── */}
        <div className={styles.card}>
          <div className={styles.sectionRow}>
            <div className={styles.sectionLabel}>
              <h2 className={styles.sectionTitle} aria-label="Child Lock">
                {t('child_lock_mode')}
              </h2>
              <span className={styles.sectionHint}>{t('child_lock_hours_hint')}</span>
            </div>
            <label className={styles.toggle}>
              <input
                type="checkbox"
                checked={cl.enabled}
                onChange={(e) => handleChildLockToggle(e.target.checked)}
              />
              <span className={styles.toggleSlider} />
            </label>
          </div>

          {cl.enabled && (
            <div className={styles.timeRow}>
              <div className={styles.timeChip}>
                <MdAccessTime size={16} style={{ color: 'var(--on-surface-variant)' }} />
                <span>{t('child_lock_from')} {cl.startTime}</span>
              </div>
              <div className={styles.timeChip}>
                <MdAccessTime size={16} style={{ color: 'var(--on-surface-variant)' }} />
                <span>{t('child_lock_until')} {cl.endTime}</span>
              </div>
            </div>
          )}
        </div>

        {/* ── Remote Sync ── */}
        <div className={styles.card}>
          {/* Header */}
          <div className={styles.sectionRow}>
            <div className={styles.sectionLabel}>
              <h2 className={styles.sectionTitle} aria-label="Remote Sync">
                {t('remote_sync_title')}
              </h2>
              <span className={remoteSync.enabled ? styles.statusOn : styles.statusOff}>
                {remoteSync.enabled ? t('remote_sync_status_enabled') : t('remote_sync_status_off')}
              </span>
            </div>
            <label className={styles.toggle}>
              <input
                type="checkbox"
                checked={remoteSync.enabled}
                onChange={(e) => updateSyncField({ enabled: e.target.checked })}
              />
              <span className={styles.toggleSlider} />
            </label>
          </div>

          <div className={styles.spacer} />

          {/* Server URL */}
          <div className={styles.textField}>
            <div className={styles.fieldLabel}>{t('remote_sync_server_url')}</div>
            <div className={styles.fieldInputWrap}>
              <input
                type="url"
                className={styles.fieldInput}
                value={syncServerUrl}
                onChange={(e) => updateSyncUrl(e.target.value)}
                placeholder="https://sync.example.com"
              />
            </div>
          </div>

          <div className={styles.spacerSmall} />

          {/* Auth token */}
          <div className={styles.textField}>
            <div className={styles.fieldLabel}>{t('remote_sync_auth_token')}</div>
            <div className={styles.fieldInputWrap}>
              <input
                type={tokenVisible ? 'text' : 'password'}
                className={[styles.fieldInput, styles.hasTrailing].join(' ')}
                value={syncAuthToken}
                onChange={(e) => updateSyncToken(e.target.value)}
                placeholder="••••••••"
                style={{ paddingInlineEnd: 40 }}
              />
              <button className={styles.trailingBtn} onClick={() => setTokenVisible((v) => !v)} type="button">
                {tokenVisible ? <MdVisibilityOff size={18} /> : <MdVisibility size={18} />}
              </button>
            </div>
          </div>

          <div className={styles.spacer} />

          {/* Sync API keys toggle */}
          <div className={styles.sectionRow}>
            <div className={styles.sectionLabel}>
              <span style={{ fontSize: 'var(--fs-body-medium)', fontWeight: 500, color: 'var(--on-surface)' }}>
                {t('remote_sync_api_keys')}
              </span>
              <span className={styles.sectionHint}>{t('remote_sync_api_keys_warning')}</span>
            </div>
            <label className={styles.toggle}>
              <input
                type="checkbox"
                checked={remoteSync.syncApiKeys}
                onChange={(e) => updateSyncField({ syncApiKeys: e.target.checked })}
              />
              <span className={styles.toggleSlider} />
            </label>
          </div>

          <div className={styles.spacer} />

          {/* Action buttons */}
          <div className={styles.actionRow}>
            {/* Sync Now */}
            <button
              className={[styles.outlineBtn, styles.outlineBtnOk].join(' ')}
              onClick={handleSyncNow}
            >
              <MdSync size={16} />
              {syncNowDone ? t('remote_sync_now_done') : t('remote_sync_now')}
            </button>

            {/* Test Connection */}
            <button
              className={[
                styles.outlineBtn,
                testResult === 'ok' ? styles.outlineBtnOk
                  : testResult === 'fail' ? styles.outlineBtnFail
                  : styles.outlineBtnNeutral,
              ].join(' ')}
              onClick={handleTestConnection}
              disabled={testInProgress}
            >
              {testInProgress ? (
                <span className={styles.spinner} />
              ) : (
                testResult === 'ok' ? t('remote_sync_test_ok')
                : testResult === 'fail' ? t('remote_sync_test_fail')
                : t('remote_sync_test')
              )}
            </button>
          </div>
        </div>

        {/* ── Navigation: Logs ── */}
        <button
          className={styles.navRow}
          onClick={() => navigate('/logs')}
          aria-label="Logs"
        >
          <span className={styles.navRowTitle}>מסך לוגים</span>
          <span style={{ fontSize: 18, color: 'var(--on-surface-variant)', opacity: 0.7 }}>›</span>
        </button>
      </div>

      {/* ── Child lock setup dialog ── */}
      {showChildLockSetup && (
        <ChildLockSetupDialog
          onConfirm={handleChildLockSetup}
          onCancel={() => setShowChildLockSetup(false)}
        />
      )}

      {/* ── Child lock disable dialog ── */}
      {showChildLockDisable && (
        <ChildLockDisableDialog
          storedHash={cl.encryptedPassword}
          onConfirm={handleChildLockDisable}
          onCancel={() => setShowChildLockDisable(false)}
        />
      )}
    </div>
  )
}

export default SettingsPage
