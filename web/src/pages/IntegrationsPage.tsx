import React, { useEffect, useState, useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import { integrations } from '../api/client'
import type { GitHubConnection, GoogleWorkspaceConnection, EnabledGoogleServices } from '../api/types'
import styles from './IntegrationsPage.module.css'

// ─── GitHub section ───────────────────────────────────────────────────────────

interface GitHubSectionProps {
  connection: GitHubConnection | null
  onConnect: () => void
  onDisconnect: () => Promise<void>
}

const GitHubSection: React.FC<GitHubSectionProps> = ({ connection, onConnect, onDisconnect }) => {
  const [disconnecting, setDisconnecting] = useState(false)

  const handleDisconnect = async () => {
    if (!window.confirm('Disconnect GitHub?')) return
    setDisconnecting(true)
    try {
      await onDisconnect()
    } finally {
      setDisconnecting(false)
    }
  }

  return (
    <div className={styles.integrationCard}>
      <div className={styles.integrationHeader}>
        <div className={styles.integrationIcon}>🐙</div>
        <div className={styles.integrationInfo}>
          <h3 className={styles.integrationName}>GitHub</h3>
          <p className={styles.integrationDesc}>Access repositories, issues, and pull requests</p>
        </div>
        <div className={`${styles.statusDot} ${connection ? styles.statusConnected : styles.statusDisconnected}`} />
      </div>

      {connection ? (
        <div className={styles.connectedSection}>
          <div className={styles.connectedUser}>
            <span className={styles.connectedLabel}>Connected as</span>
            <span className={styles.connectedValue}>@{connection.user.login}</span>
          </div>
          <div className={styles.connectedMeta}>
            Connected {new Date(connection.connectedAt).toLocaleDateString()}
          </div>
          <button
            className={styles.disconnectBtn}
            onClick={handleDisconnect}
            disabled={disconnecting}
          >
            {disconnecting ? 'Disconnecting…' : 'Disconnect'}
          </button>
        </div>
      ) : (
        <div className={styles.connectSection}>
          <p className={styles.connectHint}>
            Connect to access GitHub data from the chat assistant.
          </p>
          <button className={styles.connectBtn} onClick={onConnect}>
            Connect GitHub
          </button>
        </div>
      )}
    </div>
  )
}

// ─── Google section ───────────────────────────────────────────────────────────

interface GoogleSectionProps {
  connection: GoogleWorkspaceConnection | null
  onConnect: () => void
  onDisconnect: () => Promise<void>
  onServicesChange: (services: Partial<EnabledGoogleServices>) => Promise<void>
}

const GoogleSection: React.FC<GoogleSectionProps> = ({ connection, onConnect, onDisconnect, onServicesChange }) => {
  const [disconnecting, setDisconnecting] = useState(false)
  const [services, setServices] = useState<EnabledGoogleServices>({
    gmail: false,
    drive: false,
    calendar: false,
    ...connection?.enabledServices,
  })

  const handleDisconnect = async () => {
    if (!window.confirm('Disconnect Google Workspace?')) return
    setDisconnecting(true)
    try {
      await onDisconnect()
    } finally {
      setDisconnecting(false)
    }
  }

  const handleServiceToggle = async (service: keyof EnabledGoogleServices) => {
    const newServices = { ...services, [service]: !services[service] }
    setServices(newServices)
    try {
      await onServicesChange(newServices)
    } catch {
      // Revert on error
      setServices(services)
    }
  }

  const googleUser = connection?.user as { email?: string; name?: string } | undefined

  return (
    <div className={styles.integrationCard}>
      <div className={styles.integrationHeader}>
        <div className={styles.integrationIcon}>🔵</div>
        <div className={styles.integrationInfo}>
          <h3 className={styles.integrationName}>Google Workspace</h3>
          <p className={styles.integrationDesc}>Access Gmail, Drive, and Calendar</p>
        </div>
        <div className={`${styles.statusDot} ${connection ? styles.statusConnected : styles.statusDisconnected}`} />
      </div>

      {connection ? (
        <div className={styles.connectedSection}>
          {googleUser?.email && (
            <div className={styles.connectedUser}>
              <span className={styles.connectedLabel}>Connected as</span>
              <span className={styles.connectedValue}>{googleUser.email}</span>
            </div>
          )}
          <div className={styles.servicesSection}>
            <div className={styles.servicesTitle}>Enabled Services</div>
            {(['gmail', 'drive', 'calendar'] as (keyof EnabledGoogleServices)[]).map((svc) => (
              <label key={svc} className={styles.serviceToggle}>
                <span className={styles.serviceName}>{svc.charAt(0).toUpperCase() + svc.slice(1)}</span>
                <input
                  type="checkbox"
                  checked={services[svc]}
                  onChange={() => handleServiceToggle(svc)}
                />
                <span className={styles.toggleSlider} />
              </label>
            ))}
          </div>
          <button
            className={styles.disconnectBtn}
            onClick={handleDisconnect}
            disabled={disconnecting}
          >
            {disconnecting ? 'Disconnecting…' : 'Disconnect'}
          </button>
        </div>
      ) : (
        <div className={styles.connectSection}>
          <p className={styles.connectHint}>
            Connect to use Gmail, Drive, and Calendar from the chat assistant.
          </p>
          <button className={styles.connectBtn} onClick={onConnect}>
            Connect Google
          </button>
        </div>
      )}
    </div>
  )
}

// ─── Main IntegrationsPage ────────────────────────────────────────────────────

const IntegrationsPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams()
  const [github, setGithub] = useState<GitHubConnection | null>(null)
  const [google, setGoogle] = useState<GoogleWorkspaceConnection | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [successMsg, setSuccessMsg] = useState<string | null>(null)

  const loadAll = useCallback(async () => {
    setLoading(true)
    try {
      const [gh, g] = await Promise.allSettled([
        integrations.github.get(),
        integrations.google.get(),
      ])
      if (gh.status === 'fulfilled') setGithub(gh.value)
      if (g.status === 'fulfilled') setGoogle(g.value)
    } catch {
      // Continue with nulls
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadAll()
    // Check for OAuth return params
    const ghConnected = searchParams.get('github')
    const googleConnected = searchParams.get('google')
    if (ghConnected === 'connected') {
      setSuccessMsg('GitHub connected successfully!')
      setSearchParams({})
      loadAll()
    } else if (googleConnected === 'connected') {
      setSuccessMsg('Google Workspace connected successfully!')
      setSearchParams({})
      loadAll()
    }
  }, [loadAll, searchParams, setSearchParams])

  const handleGithubConnect = () => {
    integrations.github.startOAuth()
  }

  const handleGithubDisconnect = async () => {
    try {
      await integrations.github.disconnect()
      setGithub(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Disconnect failed')
    }
  }

  const handleGoogleConnect = () => {
    integrations.google.startOAuth()
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
    // Update local state
    setGoogle((prev) => prev ? {
      ...prev,
      enabledServices: { ...prev.enabledServices, ...services } as EnabledGoogleServices,
    } : prev)
  }

  if (loading) {
    return <div className={styles.loading}>Loading integrations…</div>
  }

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Integrations</h1>
      </div>

      {successMsg && (
        <div className={styles.success} role="status">
          {successMsg}
          <button onClick={() => setSuccessMsg(null)}>✕</button>
        </div>
      )}

      {error && (
        <div className={styles.error} role="alert">
          {error}
          <button onClick={() => setError(null)}>✕</button>
        </div>
      )}

      <div className={styles.cards}>
        <GitHubSection
          connection={github}
          onConnect={handleGithubConnect}
          onDisconnect={handleGithubDisconnect}
        />
        <GoogleSection
          connection={google}
          onConnect={handleGoogleConnect}
          onDisconnect={handleGoogleDisconnect}
          onServicesChange={handleGoogleServices}
        />
      </div>
    </div>
  )
}

export default IntegrationsPage
