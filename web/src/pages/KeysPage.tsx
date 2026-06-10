/**
 * KeysPage — R4 refactor.
 *
 * Visual + UX parity with Android ApiKeysScreen.kt:
 *   - Top bar: back arrow + Hebrew title "מפתחות API"
 *   - Two action buttons side-by-side: "הוסף מפתח API" (primary) + "קבל מפתח API" (green)
 *   - API keys list: gradient cards (provider-coloured), masked key, status toggle, delete, up/down reorder
 *   - Add-key dialog: provider dropdown (built-in + custom + full-custom with edit/delete) + key + custom name
 *   - Custom providers list: each with edit/delete + Add button
 *   - Full custom providers list: each with edit/delete + Add button
 *   - Dialogs: AddKeyDialog, DeleteKeyConfirm, SimpleCustomProviderDialog, FullCustomProviderDialog, DeleteProviderConfirm
 *
 * Custom provider configs use the Android/shared shape (CustomProvider.kt /
 * FullCustomProvider.kt) — providerKey/defaultModel/bodyTemplate etc. — which is
 * exactly what the server stores and returns.
 */

import React, {
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react'
import ReactDOM from 'react-dom'
import { useNavigate } from 'react-router-dom'
import { apiKeys, customProviders, fullCustomProviders } from '../api/client'
import type {
  ApiKey,
  CustomProviderConfig,
  FullCustomProviderConfig,
} from '../api/types'
import { t } from '../i18n/he'
import ScreenTopBar from '../components/ScreenTopBar'
import IconButton from '../ui/IconButton'
import Dialog, { DialogButton } from '../ui/Dialog'
import {
  MdAdd,
  MdDelete,
  MdEdit,
  MdExpandMore,
  MdExpandLess,
  MdArrowUpward,
  MdArrowDownward,
} from '../ui/icons'
import styles from './KeysPage.module.css'

// ─── Provider color helpers (mirrors ApiKeysScreen.kt) ───────────────────────

function providerGradient(provider: string): string {
  switch (provider.toLowerCase()) {
    case 'openai':
      return 'linear-gradient(180deg, rgba(255,255,255,0.15) 0%, rgba(255,255,255,0.08) 100%)'
    case 'anthropic':
      return 'linear-gradient(180deg, rgba(198,97,63,0.25) 0%, rgba(198,97,63,0.15) 100%)'
    case 'google':
      return 'linear-gradient(90deg, rgba(66,133,244,0.25) 0%, rgba(234,67,53,0.25) 33%, rgba(251,188,5,0.25) 66%, rgba(52,168,83,0.25) 100%)'
    case 'poe':
      return 'linear-gradient(180deg, rgba(105,75,194,0.25) 0%, rgba(105,75,194,0.15) 100%)'
    case 'cohere':
      return 'linear-gradient(180deg, rgba(57,89,77,0.25) 0%, rgba(57,89,77,0.15) 100%)'
    case 'openrouter':
      return 'linear-gradient(180deg, rgba(255,255,255,0.15) 0%, rgba(255,255,255,0.08) 100%)'
    case 'llmstats':
      return 'linear-gradient(180deg, rgba(14,165,233,0.25) 0%, rgba(14,165,233,0.15) 100%)'
    default:
      return 'var(--surface-variant)'
  }
}

// ─── Provider key generation (mirrors generateProviderKey in CustomProvider.kt) ──

function generateProviderKey(name: string, prefix: 'custom_' | 'fullcustom_'): string {
  const sanitized = name
    .toLowerCase()
    .replace(/[^a-z0-9]/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_+|_+$/g, '')
  return `${prefix}${sanitized}`
}

// ─── Built-in providers ───────────────────────────────────────────────────────

const BUILTIN_PROVIDERS = [
  { key: 'openai', label: t('provider_openai') },
  { key: 'anthropic', label: t('provider_anthropic') },
  { key: 'google', label: t('provider_google') },
  { key: 'poe', label: t('provider_poe') },
  { key: 'cohere', label: t('provider_cohere') },
  { key: 'openrouter', label: t('provider_openrouter') },
  { key: 'llmstats', label: t('provider_llmstats') },
]

// ─── Provider Dropdown ────────────────────────────────────────────────────────

interface ProviderDropdownProps {
  value: string
  onChange: (v: string) => void
  customList: CustomProviderConfig[]
  fullList: FullCustomProviderConfig[]
  onEditCustom: (cfg: CustomProviderConfig) => void
  onDeleteCustom: (cfg: CustomProviderConfig) => void
  onEditFull: (cfg: FullCustomProviderConfig) => void
  onDeleteFull: (cfg: FullCustomProviderConfig) => void
  onCreateNew: () => void
}

const ProviderDropdown: React.FC<ProviderDropdownProps> = ({
  value, onChange, customList, fullList,
  onEditCustom, onDeleteCustom, onEditFull, onDeleteFull, onCreateNew,
}) => {
  const [open, setOpen] = useState(false)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  const [menuPos, setMenuPos] = useState({ top: 0, left: 0, width: 0 })

  const openMenu = () => {
    if (triggerRef.current) {
      const rect = triggerRef.current.getBoundingClientRect()
      setMenuPos({ top: rect.bottom + 4, left: rect.left, width: rect.width })
    }
    setOpen(true)
  }

  // Close on outside click
  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      if (
        menuRef.current && !menuRef.current.contains(e.target as Node) &&
        triggerRef.current && !triggerRef.current.contains(e.target as Node)
      ) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  const selectedLabel = (() => {
    if (!value) return t('select_provider')
    const builtin = BUILTIN_PROVIDERS.find(p => p.key === value)
    if (builtin) return builtin.label
    const cp = customList.find(c => c.providerKey === value)
    if (cp) return cp.name
    const fp = fullList.find(f => f.providerKey === value)
    if (fp) return fp.name
    return value
  })()

  const pick = (v: string) => {
    onChange(v)
    setOpen(false)
  }

  return (
    <div className={styles.providerDropdown}>
      <button
        ref={triggerRef}
        type="button"
        className={styles.providerDropdownTrigger}
        onClick={openMenu}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <span>{selectedLabel}</span>
        <MdExpandMore size={18} />
      </button>

      {open && ReactDOM.createPortal(
        <div
          ref={menuRef}
          className={styles.providerDropdownMenu}
          role="listbox"
          style={{ top: menuPos.top, left: menuPos.left, width: menuPos.width }}
        >
          {/* Built-in providers */}
          {BUILTIN_PROVIDERS.map(p => (
            <div
              key={p.key}
              role="option"
              aria-selected={value === p.key}
              className={`${styles.dropdownItem} ${value === p.key ? styles.dropdownItemSelected : ''}`}
              onClick={() => pick(p.key)}
            >
              {p.label}
            </div>
          ))}

          {/* Custom providers */}
          {customList.length > 0 && (
            <>
              <div className={styles.dropdownDivider} />
              {customList.map(cp => (
                <div key={cp.id} className={`${styles.dropdownItem} ${styles.dropdownItemCustom}`}>
                  <span
                    style={{ flex: 1, cursor: 'pointer' }}
                    onClick={() => pick(cp.providerKey)}
                  >
                    {cp.name}
                  </span>
                  <div className={styles.dropdownItemActions}>
                    <button
                      type="button"
                      className={styles.dropdownActionBtn}
                      aria-label={`${t('edit')} ${cp.name}`}
                      onClick={(e) => { e.stopPropagation(); setOpen(false); onEditCustom(cp) }}
                    >
                      <MdEdit size={14} />
                    </button>
                    <button
                      type="button"
                      className={`${styles.dropdownActionBtn} ${styles.dropdownActionBtnDelete}`}
                      aria-label={`${t('delete')} ${cp.name}`}
                      onClick={(e) => { e.stopPropagation(); setOpen(false); onDeleteCustom(cp) }}
                    >
                      <MdDelete size={14} />
                    </button>
                  </div>
                </div>
              ))}
            </>
          )}

          {/* Full custom providers */}
          {fullList.length > 0 && (
            <>
              <div className={styles.dropdownDivider} />
              {fullList.map(fp => (
                <div key={fp.id} className={`${styles.dropdownItem} ${styles.dropdownItemFull}`}>
                  <span
                    style={{ flex: 1, cursor: 'pointer' }}
                    onClick={() => pick(fp.providerKey)}
                  >
                    {fp.name}
                  </span>
                  <div className={styles.dropdownItemActions}>
                    <button
                      type="button"
                      className={styles.dropdownActionBtn}
                      aria-label={`${t('edit')} ${fp.name}`}
                      onClick={(e) => { e.stopPropagation(); setOpen(false); onEditFull(fp) }}
                    >
                      <MdEdit size={14} />
                    </button>
                    <button
                      type="button"
                      className={`${styles.dropdownActionBtn} ${styles.dropdownActionBtnDelete}`}
                      aria-label={`${t('delete')} ${fp.name}`}
                      onClick={(e) => { e.stopPropagation(); setOpen(false); onDeleteFull(fp) }}
                    >
                      <MdDelete size={14} />
                    </button>
                  </div>
                </div>
              ))}
            </>
          )}

          {/* Create new */}
          <div className={styles.dropdownDivider} />
          <div
            role="option"
            aria-selected={false}
            className={`${styles.dropdownItem} ${styles.dropdownItemCreate}`}
            onClick={() => { setOpen(false); onCreateNew() }}
          >
            <MdAdd size={18} />
            {t('define_new_provider')}
          </div>
        </div>,
        document.body,
      )}
    </div>
  )
}

// ─── Add API Key Dialog ───────────────────────────────────────────────────────

interface AddKeyDialogProps {
  open: boolean
  customList: CustomProviderConfig[]
  fullList: FullCustomProviderConfig[]
  onConfirm: (provider: string, key: string, customName: string | null) => Promise<void>
  onDismiss: () => void
  onEditCustom: (cfg: CustomProviderConfig) => void
  onDeleteCustom: (cfg: CustomProviderConfig) => void
  onEditFull: (cfg: FullCustomProviderConfig) => void
  onDeleteFull: (cfg: FullCustomProviderConfig) => void
  onCreateNew: () => void
}

const AddKeyDialog: React.FC<AddKeyDialogProps> = ({
  open, customList, fullList,
  onConfirm, onDismiss,
  onEditCustom, onDeleteCustom, onEditFull, onDeleteFull, onCreateNew,
}) => {
  const [provider, setProvider] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [customName, setCustomName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  // Reset when opened
  useEffect(() => {
    if (open) {
      setProvider('')
      setApiKey('')
      setCustomName('')
      setError('')
    }
  }, [open])

  const handleConfirm = async () => {
    if (!provider || !apiKey.trim()) {
      setError('יש לבחור ספק ולהזין מפתח API')
      return
    }
    setBusy(true)
    setError('')
    try {
      await onConfirm(provider, apiKey.trim(), customName.trim() || null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה בהוספת מפתח')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onDismiss}
      title={t('add_api_key')}
      maxWidth={440}
      actions={
        <>
          <DialogButton label={t('cancel')} onClick={onDismiss} />
          <DialogButton
            label={t('approve')}
            primary
            disabled={busy || !provider || !apiKey.trim()}
            onClick={handleConfirm}
          />
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {/* Provider dropdown */}
        <div className={styles.dialogField}>
          <label className={styles.dialogLabel}>{'ספק'}</label>
          <ProviderDropdown
            value={provider}
            onChange={setProvider}
            customList={customList}
            fullList={fullList}
            onEditCustom={onEditCustom}
            onDeleteCustom={onDeleteCustom}
            onEditFull={onEditFull}
            onDeleteFull={onDeleteFull}
            onCreateNew={onCreateNew}
          />
        </div>

        {/* API Key input */}
        <div className={styles.dialogField}>
          <label className={styles.dialogLabel}>{'מפתח API'}</label>
          <input
            className={styles.dialogInput}
            type="password"
            placeholder="sk-…"
            value={apiKey}
            onChange={e => setApiKey(e.target.value)}
            dir="ltr"
          />
        </div>

        {/* Custom name (optional) */}
        <div className={styles.dialogField}>
          <label className={styles.dialogLabel}>{t('custom_name_optional')}</label>
          <input
            className={styles.dialogInput}
            placeholder={t('custom_name_placeholder')}
            value={customName}
            onChange={e => setCustomName(e.target.value)}
          />
        </div>

        {error && <div className={styles.dialogError}>{error}</div>}
      </div>
    </Dialog>
  )
}

// ─── Delete Key Confirmation Dialog ──────────────────────────────────────────

interface DeleteKeyDialogProps {
  open: boolean
  onConfirm: () => void
  onDismiss: () => void
}

const DeleteKeyDialog: React.FC<DeleteKeyDialogProps> = ({ open, onConfirm, onDismiss }) => (
  <Dialog
    open={open}
    onClose={onDismiss}
    title={t('delete_api_key_title')}
    actions={
      <>
        <DialogButton label={t('cancel')} onClick={onDismiss} />
        <DialogButton label={t('delete')} danger onClick={onConfirm} />
      </>
    }
  >
    {t('delete_api_key_body')}
  </Dialog>
)

// ─── Delete Provider Confirmation Dialog ─────────────────────────────────────

interface DeleteProviderDialogProps {
  open: boolean
  onConfirm: () => void
  onDismiss: () => void
}

const DeleteProviderDialog: React.FC<DeleteProviderDialogProps> = ({ open, onConfirm, onDismiss }) => (
  <Dialog
    open={open}
    onClose={onDismiss}
    title={t('delete_provider_title')}
    actions={
      <>
        <DialogButton label={t('cancel')} onClick={onDismiss} />
        <DialogButton label={t('delete')} danger onClick={onConfirm} />
      </>
    }
  >
    {t('delete_provider_body')}
  </Dialog>
)

// ─── Simple Custom Provider Dialog ───────────────────────────────────────────
// Matches CustomProviderDialog.kt: name / baseUrl / defaultModel +
// advanced section (authHeaderName, authHeaderFormat, extraHeaders).

interface SimpleCustomProviderDialogProps {
  open: boolean
  initial?: CustomProviderConfig
  onConfirm: (cfg: CustomProviderConfig) => Promise<void>
  onDismiss: () => void
  onSwitchToFull: () => void
}

const SimpleCustomProviderDialog: React.FC<SimpleCustomProviderDialogProps> = ({
  open, initial, onConfirm, onDismiss, onSwitchToFull,
}) => {
  const [name, setName] = useState('')
  const [baseUrl, setBaseUrl] = useState('https://')
  const [defaultModel, setDefaultModel] = useState('')
  const [authHeaderName, setAuthHeaderName] = useState('Authorization')
  const [authHeaderFormat, setAuthHeaderFormat] = useState('Bearer {key}')
  const [extraHeaders, setExtraHeaders] = useState<Array<[string, string]>>([])
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const isEditing = !!initial

  // Reset on open
  useEffect(() => {
    if (open) {
      setName(initial?.name ?? '')
      setBaseUrl(initial?.baseUrl ?? 'https://')
      setDefaultModel(initial?.defaultModel ?? '')
      setAuthHeaderName(initial?.authHeaderName ?? 'Authorization')
      setAuthHeaderFormat(initial?.authHeaderFormat ?? 'Bearer {key}')
      setExtraHeaders(Object.entries(initial?.extraHeaders ?? {}))
      setShowAdvanced(false)
      setError('')
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const isValid = name.trim() && baseUrl.trim() && defaultModel.trim()

  const handleConfirm = async () => {
    if (!isValid) {
      setError('שם, כתובת API ומודל ברירת מחדל נדרשים')
      return
    }
    setBusy(true)
    setError('')
    try {
      await onConfirm({
        id: initial?.id,
        name: name.trim(),
        providerKey: initial?.providerKey ?? generateProviderKey(name.trim(), 'custom_'),
        baseUrl: baseUrl.trim(),
        defaultModel: defaultModel.trim(),
        authHeaderName: authHeaderName.trim() || 'Authorization',
        authHeaderFormat: authHeaderFormat.trim() || 'Bearer {key}',
        extraHeaders: Object.fromEntries(
          extraHeaders.filter(([k]) => k.trim()).map(([k, v]) => [k.trim(), v]),
        ),
        createdAt: initial?.createdAt,
        isEnabled: initial?.isEnabled ?? true,
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה בשמירת הספק')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onDismiss}
      title={isEditing ? t('edit_custom_provider') : t('create_custom_provider')}
      maxWidth={480}
      actions={
        <>
          <DialogButton label={t('cancel')} onClick={onDismiss} />
          <DialogButton
            label={isEditing ? t('save') : t('create')}
            primary
            disabled={busy || !isValid}
            onClick={handleConfirm}
          />
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

        {/* OpenAI-compatible indicator */}
        <label className={styles.checkRow}>
          <input
            type="checkbox"
            checked={true}
            onChange={e => { if (!e.target.checked) onSwitchToFull() }}
          />
          <span className={styles.checkLabel}>{t('openai_compatible_label')}</span>
        </label>

        {/* Provider Name */}
        <div className={styles.dialogField}>
          <label className={styles.dialogLabel}>{t('provider_name')}</label>
          <input
            className={styles.dialogInput}
            placeholder="e.g. My Local LLM"
            value={name}
            onChange={e => setName(e.target.value)}
          />
        </div>

        {/* Base URL */}
        <div className={styles.dialogField}>
          <label className={styles.dialogLabel}>{t('api_base_url')}</label>
          <input
            className={styles.dialogInput}
            placeholder="https://api.example.com/v1/chat/completions"
            value={baseUrl}
            onChange={e => setBaseUrl(e.target.value)}
            dir="ltr"
          />
        </div>

        {/* Default model */}
        <div className={styles.dialogField}>
          <label className={styles.dialogLabel}>{t('default_model')}</label>
          <input
            className={styles.dialogInput}
            placeholder="e.g. gpt-4o"
            value={defaultModel}
            onChange={e => setDefaultModel(e.target.value)}
            dir="ltr"
          />
        </div>

        {/* Advanced settings toggle */}
        <button
          type="button"
          className={styles.advancedToggle}
          onClick={() => setShowAdvanced(v => !v)}
        >
          {showAdvanced ? t('hide_advanced') : t('show_advanced')}
          {showAdvanced ? <MdExpandLess size={16} /> : <MdExpandMore size={16} />}
        </button>

        {showAdvanced && (
          <>
            <div className={styles.dialogField}>
              <label className={styles.dialogLabel}>{t('auth_header_name')}</label>
              <input
                className={styles.dialogInput}
                value={authHeaderName}
                onChange={e => setAuthHeaderName(e.target.value)}
                dir="ltr"
              />
            </div>
            <div className={styles.dialogField}>
              <label className={styles.dialogLabel}>{t('auth_header_format')}</label>
              <input
                className={styles.dialogInput}
                value={authHeaderFormat}
                onChange={e => setAuthHeaderFormat(e.target.value)}
                dir="ltr"
              />
              <span className={styles.dialogLabel}>{t('auth_format_hint')}</span>
            </div>
            <div className={styles.dialogField}>
              <label className={styles.dialogLabel}>{t('extra_headers')}</label>
              {extraHeaders.map(([k, v], i) => (
                <div key={i} style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                  <input
                    className={styles.dialogInput}
                    placeholder="Header"
                    value={k}
                    onChange={e => setExtraHeaders(prev => prev.map((h, j) => j === i ? [e.target.value, h[1]] : h))}
                    dir="ltr"
                  />
                  <input
                    className={styles.dialogInput}
                    placeholder="Value"
                    value={v}
                    onChange={e => setExtraHeaders(prev => prev.map((h, j) => j === i ? [h[0], e.target.value] : h))}
                    dir="ltr"
                  />
                  <button
                    type="button"
                    className={styles.dropdownActionBtn}
                    aria-label={t('delete')}
                    onClick={() => setExtraHeaders(prev => prev.filter((_, j) => j !== i))}
                  >
                    <MdDelete size={14} />
                  </button>
                </div>
              ))}
              <button
                type="button"
                className={styles.sectionAddBtn}
                onClick={() => setExtraHeaders(prev => [...prev, ['', '']])}
              >
                <MdAdd size={14} />
                {t('add_header')}
              </button>
            </div>
          </>
        )}

        {error && <div className={styles.dialogError}>{error}</div>}
      </div>
    </Dialog>
  )
}

// ─── Full Custom Provider Dialog ──────────────────────────────────────────────
// Matches FullCustomProviderDialog.kt (3-tab: Request / Response / Model) using
// the Android config shape: bodyTemplate + parserType/eventMappings + defaultModel.

interface FullCustomProviderDialogProps {
  open: boolean
  initial?: FullCustomProviderConfig
  onConfirm: (cfg: FullCustomProviderConfig) => Promise<void>
  onDismiss: () => void
  onSwitchToSimple: () => void
}

const DEFAULT_BODY_TEMPLATE = `{
  "model": "{model}",
  "stream": true,
  "messages": [],
  "max_tokens": 4096
}`

const FullCustomProviderDialog: React.FC<FullCustomProviderDialogProps> = ({
  open, initial, onConfirm, onDismiss, onSwitchToSimple,
}) => {
  const [activeTab, setActiveTab] = useState<'request' | 'response' | 'model'>('request')
  const [name, setName] = useState('')
  const [baseUrl, setBaseUrl] = useState('https://')
  const [authHeaderName, setAuthHeaderName] = useState('Authorization')
  const [authHeaderFormat, setAuthHeaderFormat] = useState('Bearer {key}')
  const [bodyTemplate, setBodyTemplate] = useState(DEFAULT_BODY_TEMPLATE)
  const [parserType, setParserType] = useState('DATA_ONLY')
  const [eventMappingsText, setEventMappingsText] = useState('')
  const [defaultModel, setDefaultModel] = useState('')
  const [streamingConfirmed, setStreamingConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const isEditing = !!initial

  useEffect(() => {
    if (open) {
      setActiveTab('request')
      setName(initial?.name ?? '')
      setBaseUrl(initial?.baseUrl ?? 'https://')
      setAuthHeaderName(initial?.authHeaderName ?? 'Authorization')
      setAuthHeaderFormat(initial?.authHeaderFormat ?? 'Bearer {key}')
      setBodyTemplate(initial?.bodyTemplate ?? DEFAULT_BODY_TEMPLATE)
      setParserType(initial?.parserType ?? 'DATA_ONLY')
      setEventMappingsText(
        initial?.eventMappings && Object.keys(initial.eventMappings).length > 0
          ? JSON.stringify(initial.eventMappings, null, 2)
          : '',
      )
      setDefaultModel(initial?.defaultModel ?? '')
      setStreamingConfirmed(!!initial)
      setError('')
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // JSON validation
  const parseJson = (s: string): [Record<string, unknown> | null, boolean] => {
    if (!s.trim()) return [null, true]
    try {
      return [JSON.parse(s), true]
    } catch {
      return [null, false]
    }
  }

  const [, bodyValid] = parseJson(bodyTemplate)
  const [eventMappingsObj, mappingsValid] = parseJson(eventMappingsText)
  const isValid = name.trim() && baseUrl.trim() && defaultModel.trim() &&
    bodyTemplate.trim() && bodyValid && mappingsValid && streamingConfirmed

  const handleConfirm = async () => {
    if (!isValid) {
      setError('שם, כתובת API, מודל ברירת מחדל ו-JSON תקין נדרשים; אשר גם הגדרת סטרימינג')
      return
    }
    setBusy(true)
    setError('')
    try {
      await onConfirm({
        id: initial?.id,
        name: name.trim(),
        providerKey: initial?.providerKey ?? generateProviderKey(name.trim(), 'fullcustom_'),
        baseUrl: baseUrl.trim(),
        defaultModel: defaultModel.trim(),
        authHeaderName: authHeaderName.trim() || 'Authorization',
        authHeaderFormat: authHeaderFormat.trim() || 'Bearer {key}',
        extraHeaders: initial?.extraHeaders ?? { 'Content-Type': 'application/json' },
        bodyTemplate,
        messageFields: initial?.messageFields ?? null,
        parserType,
        parserConfig: initial?.parserConfig,
        eventMappings: eventMappingsObj ?? initial?.eventMappings ?? {},
        toolCallConfig: initial?.toolCallConfig,
        isOpenAICompatible: false,
        createdAt: initial?.createdAt,
        isEnabled: initial?.isEnabled ?? true,
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה בשמירת הספק')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onDismiss}
      title={isEditing ? t('edit_full_provider') : t('define_full_provider')}
      maxWidth={520}
      actions={
        <>
          <DialogButton label={t('cancel')} onClick={onDismiss} />
          <DialogButton
            label={isEditing ? t('save') : t('create')}
            primary
            disabled={busy || !isValid}
            onClick={handleConfirm}
          />
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

        {/* OpenAI-compatible toggle → switches to simple dialog */}
        <label className={styles.checkRow}>
          <input
            type="checkbox"
            checked={false}
            onChange={e => { if (e.target.checked) onSwitchToSimple() }}
          />
          <span className={styles.checkLabel}>{t('openai_compatible_label')}</span>
        </label>

        {/* Tab bar */}
        <div className={styles.tabBar}>
          {(['request', 'response', 'model'] as const).map(tab => (
            <button
              key={tab}
              type="button"
              className={`${styles.tabBtn} ${activeTab === tab ? styles.tabBtnActive : ''}`}
              onClick={() => setActiveTab(tab)}
            >
              {tab === 'request' ? t('request_tab') : tab === 'response' ? t('response_tab') : t('model_tab')}
            </button>
          ))}
        </div>

        {/* Request tab */}
        {activeTab === 'request' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div className={styles.dialogField}>
              <label className={styles.dialogLabel}>{t('provider_name')}</label>
              <input
                className={styles.dialogInput}
                placeholder="e.g. My Custom API"
                value={name}
                onChange={e => setName(e.target.value)}
              />
            </div>
            <div className={styles.dialogField}>
              <label className={styles.dialogLabel}>{t('api_base_url')}</label>
              <input
                className={styles.dialogInput}
                placeholder="https://api.example.com/v1/chat"
                value={baseUrl}
                onChange={e => setBaseUrl(e.target.value)}
                dir="ltr"
              />
            </div>
            <div className={styles.dialogField}>
              <label className={styles.dialogLabel}>{t('auth_header_name')}</label>
              <input
                className={styles.dialogInput}
                value={authHeaderName}
                onChange={e => setAuthHeaderName(e.target.value)}
                dir="ltr"
              />
            </div>
            <div className={styles.dialogField}>
              <label className={styles.dialogLabel}>{t('auth_header_format')}</label>
              <input
                className={styles.dialogInput}
                value={authHeaderFormat}
                onChange={e => setAuthHeaderFormat(e.target.value)}
                dir="ltr"
              />
            </div>
            <div className={styles.dialogField}>
              <label className={styles.dialogLabel}>{t('body_template_section')}</label>
              <textarea
                className={styles.dialogTextarea}
                rows={10}
                placeholder={DEFAULT_BODY_TEMPLATE}
                value={bodyTemplate}
                onChange={e => setBodyTemplate(e.target.value)}
                dir="ltr"
                style={{ fontFamily: 'monospace', fontSize: 12 }}
              />
              {bodyTemplate.trim() && !bodyValid && (
                <div className={styles.dialogError}>{t('invalid_json')}</div>
              )}
            </div>
            {/* Streaming confirmed checkbox */}
            <label className={styles.checkRow}>
              <input
                type="checkbox"
                checked={streamingConfirmed}
                onChange={e => setStreamingConfirmed(e.target.checked)}
              />
              <span className={styles.checkLabel}>{t('streaming_confirmed_label')}</span>
            </label>
          </div>
        )}

        {/* Response tab */}
        {activeTab === 'response' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div className={styles.dialogField}>
              <label className={styles.dialogLabel}>{'Parser type'}</label>
              <select
                className={styles.dialogInput}
                value={parserType}
                onChange={e => setParserType(e.target.value)}
              >
                <option value="DATA_ONLY">DATA_ONLY (OpenAI / Google style)</option>
                <option value="EVENT_DATA">EVENT_DATA (Anthropic / Cohere style)</option>
              </select>
            </div>
            <div className={styles.dialogField}>
              <label className={styles.dialogLabel}>{'Event mappings (JSON)'}</label>
              <textarea
                className={styles.dialogTextarea}
                rows={8}
                placeholder={'{"TEXT_CONTENT": {"eventName": "content_block_delta", "fieldPath": "delta.text"}}'}
                value={eventMappingsText}
                onChange={e => setEventMappingsText(e.target.value)}
                dir="ltr"
                style={{ fontFamily: 'monospace', fontSize: 12 }}
              />
              {eventMappingsText.trim() && !mappingsValid && (
                <div className={styles.dialogError}>{t('invalid_json')}</div>
              )}
            </div>
          </div>
        )}

        {/* Model tab */}
        {activeTab === 'model' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div className={styles.dialogField}>
              <label className={styles.dialogLabel}>{t('default_model')}</label>
              <input
                className={styles.dialogInput}
                placeholder="gpt-4o"
                value={defaultModel}
                onChange={e => setDefaultModel(e.target.value)}
                dir="ltr"
              />
            </div>
          </div>
        )}

        {error && <div className={styles.dialogError}>{error}</div>}
      </div>
    </Dialog>
  )
}

// ─── Key Card ─────────────────────────────────────────────────────────────────

interface KeyCardProps {
  apiKey: ApiKey
  index: number
  total: number
  onToggle: () => void
  onDelete: () => void
  onMoveUp: () => void
  onMoveDown: () => void
}

const KeyCard: React.FC<KeyCardProps> = ({
  apiKey: k, index, total, onToggle, onDelete, onMoveUp, onMoveDown,
}) => {
  const initial = k.provider.charAt(0).toUpperCase()

  return (
    <div
      className={styles.keyCard}
      style={{ background: providerGradient(k.provider) }}
    >
      <div className={styles.keyCardInner}>
        {/* Provider icon circle */}
        <div className={styles.providerIcon}>
          <span className={styles.providerInitial}>{initial}</span>
        </div>

        {/* Info column */}
        <div className={styles.keyInfo}>
          <span className={styles.providerName}>{k.provider.toUpperCase()}</span>
          <span className={styles.maskedKey}>{k.key}</span>
          {k.customName && (
            <span className={styles.customName}>{k.customName}</span>
          )}
        </div>

        {/* Status toggle */}
        <button
          type="button"
          className={`${styles.statusPill} ${k.isActive ? styles.statusActive : styles.statusInactive}`}
          onClick={onToggle}
          aria-label={k.isActive ? t('key_status_active') : t('key_status_inactive')}
          aria-pressed={k.isActive}
        >
          {k.isActive ? t('key_status_active') : t('key_status_inactive')}
        </button>

        {/* Reorder buttons */}
        <div className={styles.reorderBtns}>
          <button
            type="button"
            className={styles.reorderBtn}
            onClick={onMoveUp}
            disabled={index === 0}
            aria-label={t('reorder_up')}
            title={t('reorder_up')}
          >
            <MdArrowUpward size={12} />
          </button>
          <button
            type="button"
            className={styles.reorderBtn}
            onClick={onMoveDown}
            disabled={index === total - 1}
            aria-label={t('reorder_down')}
            title={t('reorder_down')}
          >
            <MdArrowDownward size={12} />
          </button>
        </div>

        {/* Delete button */}
        <button
          type="button"
          className={styles.deleteBtn}
          onClick={onDelete}
          aria-label={`${t('delete_api_key_title')}`}
          title="Delete"
        >
          <MdDelete size={20} />
        </button>
      </div>
    </div>
  )
}

// ─── Main KeysPage ─────────────────────────────────────────────────────────────

type ProviderDialogMode = 'none' | 'simple-add' | 'simple-edit' | 'full-add' | 'full-edit'

const KeysPage: React.FC = () => {
  const navigate = useNavigate()

  // ── Data state ────────────────────────────────────────────────────────────
  const [keys, setKeys] = useState<ApiKey[]>([])
  const [customList, setCustomList] = useState<CustomProviderConfig[]>([])
  const [fullList, setFullList] = useState<FullCustomProviderConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // ── Dialog state ──────────────────────────────────────────────────────────
  const [showAddKey, setShowAddKey] = useState(false)
  const [deleteKeyTarget, setDeleteKeyTarget] = useState<ApiKey | null>(null)
  const [providerDialogMode, setProviderDialogMode] = useState<ProviderDialogMode>('none')
  const [editingCustom, setEditingCustom] = useState<CustomProviderConfig | undefined>()
  const [editingFull, setEditingFull] = useState<FullCustomProviderConfig | undefined>()
  const [deleteProviderTarget, setDeleteProviderTarget] = useState<
    { kind: 'simple'; cfg: CustomProviderConfig } |
    { kind: 'full'; cfg: FullCustomProviderConfig } |
    null
  >(null)

  // ── Load ──────────────────────────────────────────────────────────────────
  const loadAll = useCallback(async () => {
    setLoading(true)
    setError(null)
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
      setError(err instanceof Error ? err.message : 'שגיאה בטעינה')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadAll() }, [loadAll])

  // ── API Key actions ────────────────────────────────────────────────────────

  const handleAddKey = async (provider: string, key: string, customName: string | null) => {
    await apiKeys.create({ provider, key, isActive: true, customName })
    setShowAddKey(false)
    await loadAll()
  }

  const handleToggleKey = async (keyId: string) => {
    try {
      const updated = await apiKeys.toggle(keyId)
      setKeys(prev => prev.map(k => k.id === updated.id ? updated : k))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה בשינוי מצב')
    }
  }

  const handleDeleteKey = async () => {
    if (!deleteKeyTarget) return
    try {
      await apiKeys.delete(deleteKeyTarget.id)
      setKeys(prev => prev.filter(k => k.id !== deleteKeyTarget.id))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה במחיקה')
    } finally {
      setDeleteKeyTarget(null)
    }
  }

  const handleReorder = async (fromIndex: number, toIndex: number) => {
    try {
      const updated = await apiKeys.reorder({ fromIndex, toIndex })
      setKeys(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה בסידור מחדש')
    }
  }

  // ── Custom provider actions ────────────────────────────────────────────────

  const handleSaveCustom = async (cfg: CustomProviderConfig) => {
    if (cfg.id) {
      await customProviders.replace(cfg.id, cfg)
    } else {
      await customProviders.create(cfg)
    }
    setProviderDialogMode('none')
    setEditingCustom(undefined)
    await loadAll()
  }

  const handleDeleteCustom = async () => {
    if (!deleteProviderTarget || deleteProviderTarget.kind !== 'simple') return
    try {
      await customProviders.delete(deleteProviderTarget.cfg.id!)
      await loadAll()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה במחיקת ספק')
    } finally {
      setDeleteProviderTarget(null)
    }
  }

  // ── Full custom provider actions ───────────────────────────────────────────

  const handleSaveFull = async (cfg: FullCustomProviderConfig) => {
    if (cfg.id) {
      await fullCustomProviders.replace(cfg.id, cfg)
    } else {
      await fullCustomProviders.create(cfg)
    }
    setProviderDialogMode('none')
    setEditingFull(undefined)
    await loadAll()
  }

  const handleDeleteFull = async () => {
    if (!deleteProviderTarget || deleteProviderTarget.kind !== 'full') return
    try {
      await fullCustomProviders.delete(deleteProviderTarget.cfg.id!)
      await loadAll()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה במחיקת ספק')
    } finally {
      setDeleteProviderTarget(null)
    }
  }

  // ── Handlers to open dialogs from inside the Add Key dialog ───────────────

  const openEditCustomFromDropdown = (cfg: CustomProviderConfig) => {
    setEditingCustom(cfg)
    setProviderDialogMode('simple-edit')
    setShowAddKey(false)
  }

  const openDeleteCustomFromDropdown = (cfg: CustomProviderConfig) => {
    setDeleteProviderTarget({ kind: 'simple', cfg })
    setShowAddKey(false)
  }

  const openEditFullFromDropdown = (cfg: FullCustomProviderConfig) => {
    setEditingFull(cfg)
    setProviderDialogMode('full-edit')
    setShowAddKey(false)
  }

  const openDeleteFullFromDropdown = (cfg: FullCustomProviderConfig) => {
    setDeleteProviderTarget({ kind: 'full', cfg })
    setShowAddKey(false)
  }

  const openCreateProviderFromDropdown = () => {
    setProviderDialogMode('full-add')
    setEditingFull(undefined)
    setShowAddKey(false)
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  if (loading) {
    return (
      <div className={styles.page}>
        <ScreenTopBar
          title={t('api_keys')}
          onBack={() => navigate(-1)}
          headingAriaLabel="API Keys"
        />
        <div className={styles.loading}>{'טוען...'}</div>
      </div>
    )
  }

  return (
    <div className={styles.page}>
      {/* ── Top bar ── */}
      <ScreenTopBar
        title={t('api_keys')}
        onBack={() => navigate(-1)}
        headingAriaLabel="API Keys"
      />

      {/* ── Error banner ── */}
      {error && (
        <div className={styles.errorBanner} role="alert">
          <span>{error}</span>
          <button className={styles.errorCloseBtn} onClick={() => setError(null)}>✕</button>
        </div>
      )}

      {/* ── Scrollable content ── */}
      <div className={styles.content}>

        {/* Action buttons */}
        <div className={styles.actionButtons}>
          <button
            type="button"
            className={styles.addKeyBtn}
            aria-label={t('add_api_key')}
            onClick={() => setShowAddKey(true)}
          >
            <MdAdd size={18} />
            {t('add_api_key')}
          </button>
          <button type="button" className={styles.getKeyBtn}>
            {'⭐'}
            {t('get_api_key')}
          </button>
        </div>

        {/* ── API Keys list ── */}
        <div className={styles.keysList}>
          {keys.length === 0 ? (
            <div className={styles.emptyState}>
              {'אין מפתחות API. לחץ על "הוסף מפתח API" להתחלה.'}
            </div>
          ) : (
            keys.map((k, idx) => (
              <KeyCard
                key={k.id}
                apiKey={k}
                index={idx}
                total={keys.length}
                onToggle={() => handleToggleKey(k.id)}
                onDelete={() => setDeleteKeyTarget(k)}
                onMoveUp={() => handleReorder(idx, idx - 1)}
                onMoveDown={() => handleReorder(idx, idx + 1)}
              />
            ))
          )}
        </div>

        {/* ── Simple Custom Providers section ── */}
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>{t('custom_providers')}</h2>
            <button
              type="button"
              className={styles.sectionAddBtn}
              onClick={() => { setEditingCustom(undefined); setProviderDialogMode('simple-add') }}
            >
              <MdAdd size={14} />
              {t('create')}
            </button>
          </div>
          {customList.length === 0 ? (
            <div className={styles.emptyState}>{'אין ספקים מותאמים אישית'}</div>
          ) : (
            customList.map(cp => (
              <div key={cp.id} className={styles.providerRow}>
                <div className={styles.providerRowInfo}>
                  <span className={styles.providerRowName}>{cp.name}</span>
                  <span className={styles.providerRowUrl}>{cp.baseUrl}</span>
                  <span className={styles.providerRowMeta}>{cp.defaultModel}</span>
                </div>
                <div className={styles.providerRowActions}>
                  <IconButton
                    icon={MdEdit}
                    aria-label={`${t('edit')} ${cp.name}`}
                    onClick={() => { setEditingCustom(cp); setProviderDialogMode('simple-edit') }}
                    size={32}
                    iconSize={16}
                  />
                  <IconButton
                    icon={MdDelete}
                    aria-label={`${t('delete')} ${cp.name}`}
                    onClick={() => setDeleteProviderTarget({ kind: 'simple', cfg: cp })}
                    size={32}
                    iconSize={16}
                  />
                </div>
              </div>
            ))
          )}
        </div>

        {/* ── Full Custom Providers section ── */}
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>{t('full_custom_providers')}</h2>
            <button
              type="button"
              className={styles.sectionAddBtn}
              onClick={() => { setEditingFull(undefined); setProviderDialogMode('full-add') }}
            >
              <MdAdd size={14} />
              {t('create')}
            </button>
          </div>
          {fullList.length === 0 ? (
            <div className={styles.emptyState}>{'אין ספקים מותאמים אישית מלאים'}</div>
          ) : (
            fullList.map(fp => (
              <div key={fp.id} className={styles.providerRow}>
                <div className={styles.providerRowInfo}>
                  <span className={styles.providerRowName}>{fp.name}</span>
                  <span className={styles.providerRowUrl}>{fp.baseUrl}</span>
                  <span className={styles.providerRowMeta}>{fp.defaultModel}</span>
                </div>
                <div className={styles.providerRowActions}>
                  <IconButton
                    icon={MdEdit}
                    aria-label={`${t('edit')} ${fp.name}`}
                    onClick={() => { setEditingFull(fp); setProviderDialogMode('full-edit') }}
                    size={32}
                    iconSize={16}
                  />
                  <IconButton
                    icon={MdDelete}
                    aria-label={`${t('delete')} ${fp.name}`}
                    onClick={() => setDeleteProviderTarget({ kind: 'full', cfg: fp })}
                    size={32}
                    iconSize={16}
                  />
                </div>
              </div>
            ))
          )}
        </div>
      </div>

      {/* ── Dialogs ── */}

      <AddKeyDialog
        open={showAddKey}
        customList={customList}
        fullList={fullList}
        onConfirm={handleAddKey}
        onDismiss={() => setShowAddKey(false)}
        onEditCustom={openEditCustomFromDropdown}
        onDeleteCustom={openDeleteCustomFromDropdown}
        onEditFull={openEditFullFromDropdown}
        onDeleteFull={openDeleteFullFromDropdown}
        onCreateNew={openCreateProviderFromDropdown}
      />

      <DeleteKeyDialog
        open={!!deleteKeyTarget}
        onConfirm={handleDeleteKey}
        onDismiss={() => setDeleteKeyTarget(null)}
      />

      <SimpleCustomProviderDialog
        open={providerDialogMode === 'simple-add' || providerDialogMode === 'simple-edit'}
        initial={editingCustom}
        onConfirm={handleSaveCustom}
        onDismiss={() => { setProviderDialogMode('none'); setEditingCustom(undefined) }}
        onSwitchToFull={() => {
          setProviderDialogMode('full-add')
          setEditingFull(undefined)
          setEditingCustom(undefined)
        }}
      />

      <FullCustomProviderDialog
        open={providerDialogMode === 'full-add' || providerDialogMode === 'full-edit'}
        initial={editingFull}
        onConfirm={handleSaveFull}
        onDismiss={() => { setProviderDialogMode('none'); setEditingFull(undefined) }}
        onSwitchToSimple={() => {
          setProviderDialogMode('simple-add')
          setEditingCustom(undefined)
          setEditingFull(undefined)
        }}
      />

      <DeleteProviderDialog
        open={!!deleteProviderTarget}
        onConfirm={deleteProviderTarget?.kind === 'simple' ? handleDeleteCustom : handleDeleteFull}
        onDismiss={() => setDeleteProviderTarget(null)}
      />
    </div>
  )
}

export default KeysPage
