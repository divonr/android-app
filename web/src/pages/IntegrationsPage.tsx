/**
 * IntegrationsPage — R6 refactor.
 * Mirrors Android IntegrationsScreen.kt:
 *   - Available tools section (date/time, group conversations, Python)
 *   - GitHub connection card (OAuth redirect + return handling)
 *   - Google Workspace connection card (OAuth redirect + per-service toggles)
 */
import React, { useEffect, useState, useCallback } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { integrations, settings as settingsApi } from '../api/client'
import type { AppSettings, GitHubConnection, GoogleWorkspaceConnection, EnabledGoogleServices } from '../api/types'
import ScreenTopBar from '../components/ScreenTopBar'
import { t } from '../i18n/he'
import styles from './IntegrationsPage.module.css'

// ── Toggle component ──────────────────────────────────────────────────────────

interface ToggleProps {
  checked: boolean
  onChange: (v: boolean) => void
  small?: boolean
  label?: string
}

const Toggle: React.FC<ToggleProps> = ({ checked, onChange, small, label }) => (
  <label className={`${styles.toggle} ${small ? styles.toggleSmall : ''}`}>
    <input
      type="checkbox"
      checked={checked}
      onChange={(e) => onChange(e.target.checked)}
      aria-label={label}
    />
    <span className={styles.toggleSlider} />
  </label>
)

// ── Tool item ─────────────────────────────────────────────────────────────────

interface ToolItemProps {
  title: string
  description: string
  enabled: boolean
  onToggle: (v: boolean) => void
}

const ToolItem: React.FC<ToolItemProps> = ({ title, description, enabled, onToggle }) => (
  <div className={styles.toolItem}>
    <div className={styles.toolInfo}>
      <p className={styles.toolTitle}>{title}</p>
      <p className={styles.toolDesc}>{description}</p>
    </div>
    <Toggle checked={enabled} onChange={onToggle} label={title} />
  </div>
)

// ── GitHub integration card ───────────────────────────────────────────────────

interface GitHubCardProps {
  connection: GitHubConnection | null
  onConnect: () => void
  onDisconnect: () => Promise<void>
}

const GitHubCard: React.FC<GitHubCardProps> = ({ connection, onConnect, onDisconnect }) => {
  const isConnected = connection !== null

  const handleToggle = async (checked: boolean) => {
    if (checked) onConnect()
    else await onDisconnect()
  }

  const githubTools = [
    'קריאת קבצים מ-repositories',
    'כתיבה ועדכון קבצים',
    'רשימת תוכן תיקיות',
    'חיפוש קוד ב-repositories',
    'יצירת ענפים (branches)',
    'יצירת Pull Requests',
    'קבלת מידע על repositories',
    'רשימת repositories של המשתמש',
  ]

  return (
    <div className={styles.integrationItem}>
      <div className={styles.integrationMainRow}>
        <div className={styles.integrationInfo}>
          <p className={styles.integrationTitle}>{t('integration_github_title')}</p>
          <p className={styles.integrationDesc}>
            {isConnected
              ? `${t('integration_github_connected_as')}${connection!.user.login}`
              : t('integration_github_desc')}
          </p>
        </div>
        <Toggle
          checked={isConnected}
          onChange={handleToggle}
          label={t('integration_github_title')}
        />
      </div>

      {isConnected && (
        <>
          <div className={styles.divider} />
          <div className={styles.connectedSection}>
            <p className={styles.connectedSubtitle}>{t('integration_github_tools_title')}</p>
            <ul className={styles.toolsList}>
              {githubTools.map((tool) => (
                <li key={tool} className={styles.toolsListItem}>
                  <span aria-hidden="true">•</span>
                  <span>{tool}</span>
                </li>
              ))}
            </ul>
          </div>
        </>
      )}
    </div>
  )
}

// ── Google Workspace card ─────────────────────────────────────────────────────

interface GoogleCardProps {
  connection: GoogleWorkspaceConnection | null
  onConnect: () => void
  onDisconnect: () => Promise<void>
  onServicesChange: (services: Partial<EnabledGoogleServices>) => Promise<void>
}

const GoogleCard: React.FC<GoogleCardProps> = ({ connection, onConnect, onDisconnect, onServicesChange }) => {
  const isConnected = connection !== null
  const [services, setServices] = useState<EnabledGoogleServices>({
    gmail: false,
    drive: false,
    calendar: false,
    ...connection?.enabledServices,
  })

  // Sync services when connection prop changes
  useEffect(() => {
    setServices({
      gmail: false,
      drive: false,
      calendar: false,
      ...connection?.enabledServices,
    })
  }, [connection])

  const handleMainToggle = async (checked: boolean) => {
    if (checked) onConnect()
    else await onDisconnect()
  }

  const handleServiceToggle = async (svc: keyof EnabledGoogleServices, enabled: boolean) => {
    const next = { ...services, [svc]: enabled }
    setServices(next)
    try {
      await onServicesChange(next)
    } catch {
      setServices(services) // revert on error
    }
  }

  const googleUser = connection?.user as { email?: string } | undefined

  const serviceItems: Array<{ key: keyof EnabledGoogleServices; label: string; desc: string }> = [
    { key: 'gmail', label: 'Gmail', desc: t('integration_gmail_desc') },
    { key: 'calendar', label: 'Calendar', desc: t('integration_calendar_desc') },
    { key: 'drive', label: 'Drive', desc: t('integration_drive_desc') },
  ]

  return (
    <div className={styles.integrationItem}>
      <div className={styles.integrationMainRow}>
        <div className={styles.integrationInfo}>
          <p className={styles.integrationTitle}>{t('integration_google_title')}</p>
          <p className={styles.integrationDesc}>
            {isConnected
              ? `${t('integration_google_connected_as')}${googleUser?.email ?? 'user'}`
              : t('integration_google_desc')}
          </p>
        </div>
        <Toggle
          checked={isConnected}
          onChange={handleMainToggle}
          label={t('integration_google_title')}
        />
      </div>

      {isConnected && (
        <>
          <div className={styles.divider} />
          <div className={styles.connectedSection}>
            <p className={styles.connectedSubtitle}>{t('integration_google_services_title')}</p>
            {serviceItems.map(({ key, label, desc }) => (
              <div key={key} className={styles.serviceRow}>
                <div className={styles.serviceInfo}>
                  <p className={styles.serviceTitle}>{label}</p>
                  <p className={styles.serviceDesc}>{desc}</p>
                </div>
                <Toggle
                  checked={services[key]}
                  onChange={(v) => handleServiceToggle(key, v)}
                  small
                  label={label}
                />
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

// ── Main IntegrationsPage ─────────────────────────────────────────────────────

const IntegrationsPage: React.FC = () => {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()

  const [appSettings, setAppSettings] = useState<AppSettings | null>(null)
  const [github, setGithub] = useState<GitHubConnection | null>(null)
  const [google, setGoogle] = useState<GoogleWorkspaceConnection | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [successMsg, setSuccessMsg] = useState<string | null>(null)

  const loadAll = useCallback(async () => {
    setLoading(true)
    try {
      const [s, ghResult, gResult] = await Promise.allSettled([
        settingsApi.get(),
        integrations.github.get(),
        integrations.google.get(),
      ])
      if (s.status === 'fulfilled') setAppSettings(s.value)
      if (ghResult.status === 'fulfilled') setGithub(ghResult.value)
      if (gResult.status === 'fulfilled') setGoogle(gResult.value)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadAll()
    // Handle OAuth return
    const ghConnected = searchParams.get('github')
    const gConnected = searchParams.get('google')
    if (ghConnected === 'connected') {
      setSuccessMsg('GitHub חובר בהצלחה!')
      setSearchParams({})
    } else if (gConnected === 'connected') {
      setSuccessMsg('Google Workspace חובר בהצלחה!')
      setSearchParams({})
    }
  }, [loadAll, searchParams, setSearchParams])

  // ── Tool toggles (via settings.update) ────────────────────────────────────

  const toggleTool = async (toolId: string, enabled: boolean) => {
    if (!appSettings) return
    const current = appSettings.enabledTools ?? []
    const next = enabled
      ? [...current, toolId]
      : current.filter((id) => id !== toolId)
    try {
      const updated = await settingsApi.update({ enabledTools: next })
      setAppSettings(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Update failed')
    }
  }

  // ── GitHub OAuth (redirect to server, handle return) ──────────────────────

  const handleGithubConnect = () => {
    integrations.github.startOAuth() // navigates to /oauth/github/start
  }

  const handleGithubDisconnect = async () => {
    try {
      await integrations.github.disconnect()
      setGithub(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Disconnect failed')
    }
  }

  // ── Google OAuth ───────────────────────────────────────────────────────────

  const handleGoogleConnect = () => {
    integrations.google.startOAuth() // navigates to /oauth/google/start
  }

  const handleGoogleDisconnect = async () => {
    try {
      await integrations.google.disconnect()
      setGoogle(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Disconnect failed')
    }
  }

  const handleGoogleServices = async (services: Partial<EnabledGoogleServices>) => {
    await integrations.google.updateServices(services)
    setGoogle((prev) =>
      prev
        ? { ...prev, enabledServices: { ...prev.enabledServices, ...services } as EnabledGoogleServices }
        : prev,
    )
  }

  const enabledTools = appSettings?.enabledTools ?? []

  return (
    <div className={styles.page}>
      <ScreenTopBar
        title={t('integrations_title')}
        headingAriaLabel="Integrations"
        onBack={() => navigate(-1)}
      />

      {loading ? (
        <div className={styles.loading}>טוען…</div>
      ) : (
        <div className={styles.content}>
          {successMsg && (
            <div className={styles.success} role="status">
              {successMsg}
              <button className={styles.successClose} onClick={() => setSuccessMsg(null)}>✕</button>
            </div>
          )}
          {error && (
            <div className={styles.error} role="alert">
              {error}
              <button className={styles.errorClose} onClick={() => setError(null)}>✕</button>
            </div>
          )}

          {/* ── Available tools ── */}
          <div className={styles.sectionCard}>
            <p className={styles.sectionTitle}>{t('integrations_tools_section')}</p>

            <ToolItem
              title={t('integration_datetime')}
              description={t('integration_datetime_desc')}
              enabled={enabledTools.includes('get_date_time')}
              onToggle={(v) => toggleTool('get_date_time', v)}
            />
            <ToolItem
              title={t('integration_group_conv')}
              description={t('integration_group_conv_desc')}
              enabled={enabledTools.includes('get_current_group_conversations')}
              onToggle={(v) => toggleTool('get_current_group_conversations', v)}
            />
            <ToolItem
              title={t('integration_python')}
              description={t('integration_python_desc')}
              enabled={enabledTools.includes('python_interpreter')}
              onToggle={(v) => toggleTool('python_interpreter', v)}
            />

            {/* ── GitHub ── */}
            <GitHubCard
              connection={github}
              onConnect={handleGithubConnect}
              onDisconnect={handleGithubDisconnect}
            />

            {/* ── Google Workspace ── */}
            <GoogleCard
              connection={google}
              onConnect={handleGoogleConnect}
              onDisconnect={handleGoogleDisconnect}
              onServicesChange={handleGoogleServices}
            />
          </div>
        </div>
      )}
    </div>
  )
}

export default IntegrationsPage
