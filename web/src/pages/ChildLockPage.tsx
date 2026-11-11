/**
 * ChildLockPage — R5 refactor.
 *
 * Mirrors Android ChildLockScreen.kt:
 *   - Black full-screen background
 *   - Lock icon (80dp)
 *   - "האפליקציה נעולה" headline
 *   - "האפליקציה נעולה עד לשעה XX:XX"
 *   - "האפליקציה תהיה זמינה מחוץ לשעות הנעילה"
 *
 * Additional web-only behaviour:
 *   - Loads child lock settings from the API on mount
 *   - If the lock is NOT currently active (wrong time range), redirects to /
 *   - Password entry field to unlock early (verifies against SHA-256 hash stored in settings)
 *
 * Route: /child-lock (added to App.tsx)
 */

import React, { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { settings as settingsApi } from '../api/client'
import type { AppSettings } from '../api/types'
import { t } from '../i18n/he'
import { MdLock, MdVisibility, MdVisibilityOff } from '../ui/icons'
import { sha256 } from '../utils/crypto'
import { isInLockRange } from '../utils/childLock'

// ── Component ─────────────────────────────────────────────────────────────────

const ChildLockPage: React.FC = () => {
  const navigate = useNavigate()
  const [appSettings, setAppSettings] = useState<AppSettings | null>(null)
  const [loading, setLoading] = useState(true)
  const [password, setPassword] = useState('')
  const [passVisible, setPassVisible] = useState(false)
  const [wrong, setWrong] = useState(false)

  useEffect(() => {
    settingsApi.get().then((s) => {
      setAppSettings(s)
      setLoading(false)
      // If child lock isn't active right now, redirect away
      if (!s.childLockSettings.enabled || !isInLockRange(s.childLockSettings.startTime, s.childLockSettings.endTime)) {
        navigate('/', { replace: true })
      }
    }).catch(() => {
      setLoading(false)
      navigate('/', { replace: true })
    })
  }, [navigate])

  const handleUnlock = async () => {
    if (!appSettings) return
    const hash = await sha256(password)
    if (hash === appSettings.childLockSettings.encryptedPassword) {
      // Disable child lock
      await settingsApi.update({
        childLockSettings: {
          ...appSettings.childLockSettings,
          enabled: false,
          encryptedPassword: '',
        },
      })
      navigate('/', { replace: true })
    } else {
      setWrong(true)
      setPassword('')
    }
  }

  if (loading || !appSettings) {
    return (
      <div style={{ minHeight: '100vh', background: '#000', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <span style={{ color: '#fff' }}>...</span>
      </div>
    )
  }

  const { endTime } = appSettings.childLockSettings

  return (
    <div
      dir="rtl"
      style={{
        minHeight: '100vh',
        background: '#000',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '0 32px',
        gap: 0,
      }}
    >
      {/* Lock icon */}
      <MdLock size={80} color="#ffffff" />

      {/* Headline */}
      <h1
        style={{
          color: '#ffffff',
          fontSize: 28,
          fontWeight: 700,
          textAlign: 'center',
          marginTop: 24,
          marginBottom: 0,
        }}
      >
        {t('child_lock_locked_title')}
      </h1>

      {/* Until time */}
      <p
        style={{
          color: 'rgba(255,255,255,0.8)',
          fontSize: 20,
          textAlign: 'center',
          marginTop: 16,
          marginBottom: 0,
          lineHeight: 1.4,
        }}
      >
        {t('child_lock_until_time_prefix')} {endTime}
      </p>

      {/* Availability note */}
      <p
        style={{
          color: 'rgba(255,255,255,0.6)',
          fontSize: 14,
          textAlign: 'center',
          marginTop: 32,
          marginBottom: 0,
          lineHeight: 1.5,
        }}
      >
        {t('child_lock_available_msg')}
      </p>

      {/* Password unlock section */}
      <div
        style={{
          marginTop: 48,
          width: '100%',
          maxWidth: 320,
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
        }}
      >
        <p style={{ color: 'rgba(255,255,255,0.7)', fontSize: 13, textAlign: 'center', margin: 0 }}>
          {t('child_lock_enter_password_to_unlock')}
        </p>
        <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
          <input
            type={passVisible ? 'text' : 'password'}
            value={password}
            onChange={(e) => { setPassword(e.target.value); setWrong(false) }}
            placeholder={t('child_lock_password_placeholder')}
            style={{
              width: '100%',
              padding: '12px 44px 12px 16px',
              background: 'rgba(255,255,255,0.1)',
              border: '1px solid rgba(255,255,255,0.2)',
              borderRadius: 12,
              color: '#fff',
              fontSize: 16,
              fontFamily: 'inherit',
              outline: 'none',
            }}
          />
          <button
            type="button"
            onClick={() => setPassVisible((v) => !v)}
            style={{
              position: 'absolute',
              insetInlineEnd: 8,
              background: 'none',
              border: 'none',
              cursor: 'pointer',
              color: 'rgba(255,255,255,0.6)',
              display: 'flex',
              alignItems: 'center',
              padding: 6,
            }}
          >
            {passVisible ? <MdVisibilityOff size={20} /> : <MdVisibility size={20} />}
          </button>
        </div>

        {wrong && (
          <div style={{ color: '#ec7063', fontSize: 13, textAlign: 'center' }}>
            {t('child_lock_wrong_password')}
          </div>
        )}

        <button
          onClick={handleUnlock}
          disabled={!password.trim()}
          style={{
            padding: '12px',
            background: password.trim() ? 'var(--primary)' : 'rgba(255,255,255,0.1)',
            border: 'none',
            borderRadius: 12,
            color: '#fff',
            fontSize: 16,
            fontWeight: 600,
            fontFamily: 'inherit',
            cursor: password.trim() ? 'pointer' : 'not-allowed',
            opacity: password.trim() ? 1 : 0.5,
            transition: 'background 0.2s, opacity 0.2s',
          }}
        >
          {t('child_lock_unlock')}
        </button>
      </div>
    </div>
  )
}

export default ChildLockPage
