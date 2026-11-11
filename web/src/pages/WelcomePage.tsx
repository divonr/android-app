/**
 * WelcomePage — R6 refactor.
 * Mirrors Android WelcomeScreen.kt: first-run welcome with provider cards,
 * skip-welcome toggle, and "I have an API key" link to /keys.
 *
 * Route: /welcome
 * Provider logos are not available on web → show styled name cards instead.
 */
import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { settings as settingsApi } from '../api/client'
import { t } from '../i18n/he'
import styles from './WelcomePage.module.css'

// ── Provider data ─────────────────────────────────────────────────────────────

interface ProviderInfo {
  id: string
  label: string
  cardClass: string
  apiUrl: string
}

const FREE_PROVIDERS: ProviderInfo[] = [
  { id: 'google',   label: 'Google',   cardClass: styles.providerGoogle,   apiUrl: 'https://aistudio.google.com/app/apikey' },
  { id: 'poe',      label: 'Poe',      cardClass: styles.providerPoe,      apiUrl: 'https://poe.com/api_key' },
  { id: 'cohere',   label: 'Cohere',   cardClass: styles.providerCohere,   apiUrl: 'https://dashboard.cohere.com/api-keys' },
  { id: 'llmstats', label: 'LLM Stats',cardClass: styles.providerLlmstats, apiUrl: 'https://llmstats.ai' },
]

const PAID_PROVIDERS: ProviderInfo[] = [
  { id: 'openai',     label: 'OpenAI',     cardClass: styles.providerOpenai,     apiUrl: 'https://platform.openai.com/api-keys' },
  { id: 'anthropic',  label: 'Anthropic',  cardClass: styles.providerAnthropic,  apiUrl: 'https://console.anthropic.com/account/keys' },
  { id: 'openrouter', label: 'OpenRouter', cardClass: styles.providerOpenrouter, apiUrl: 'https://openrouter.ai/keys' },
]

// ── Provider card ─────────────────────────────────────────────────────────────

const ProviderCard: React.FC<{ info: ProviderInfo }> = ({ info }) => {
  const handleClick = () => {
    window.open(info.apiUrl, '_blank', 'noopener,noreferrer')
  }

  return (
    <button
      className={`${styles.providerCard} ${info.cardClass}`}
      onClick={handleClick}
      aria-label={`${info.label} — קבל מפתח API`}
    >
      <span className={styles.providerName}>{info.label}</span>
    </button>
  )
}

// ── Provider grid (2 columns) ─────────────────────────────────────────────────

const ProviderGrid: React.FC<{ providers: ProviderInfo[] }> = ({ providers }) => {
  const rows: ProviderInfo[][] = []
  for (let i = 0; i < providers.length; i += 2) {
    rows.push(providers.slice(i, i + 2))
  }
  return (
    <div className={styles.providerGrid}>
      {rows.map((row, ri) => (
        <div key={ri} className={styles.providerRow}>
          {row.map((p) => <ProviderCard key={p.id} info={p} />)}
          {row.length === 1 && <div style={{ flex: 1 }} />}
        </div>
      ))}
    </div>
  )
}

// ── Main WelcomePage ──────────────────────────────────────────────────────────

const WelcomePage: React.FC = () => {
  const navigate = useNavigate()
  const [skipWelcome, setSkipWelcome] = useState(false)

  const handleSkipChange = async (checked: boolean) => {
    setSkipWelcome(checked)
    try {
      await settingsApi.update({ skipWelcomeScreen: checked })
    } catch {
      // Non-critical; ignore
    }
  }

  const handleClose = () => navigate('/')

  const handleGoToKeys = () => navigate('/keys')

  return (
    <div className={styles.page}>
      {/* Close button (top-start in RTL = visual top-right) */}
      <div className={styles.closeRow}>
        <button className={styles.closeBtn} onClick={handleClose} aria-label="Close">
          ✕
        </button>
      </div>

      {/* Scrollable content */}
      <div className={styles.content}>
        <h1 className={styles.title}>{t('welcome_title')}</h1>
        <p className={styles.subtitle}>{t('welcome_subtitle')}</p>

        {/* Free providers */}
        <p className={`${styles.sectionHeader} ${styles.sectionHeaderFree}`}>
          {t('welcome_free_section')}
        </p>
        <ProviderGrid providers={FREE_PROVIDERS} />

        {/* Paid providers */}
        <p className={`${styles.sectionHeader} ${styles.sectionHeaderPaid}`}>
          {t('welcome_paid_section')}
        </p>
        <ProviderGrid providers={PAID_PROVIDERS} />
      </div>

      {/* Bottom fixed section */}
      <div className={styles.bottomSection}>
        <button className={styles.haveKeyBtn} onClick={handleGoToKeys}>
          {t('welcome_have_api_key')}
        </button>

        <label className={styles.skipRow}>
          <input
            type="checkbox"
            checked={skipWelcome}
            onChange={(e) => handleSkipChange(e.target.checked)}
          />
          <span className={styles.skipLabel}>{t('welcome_skip_screen')}</span>
        </label>
      </div>
    </div>
  )
}

export default WelcomePage
