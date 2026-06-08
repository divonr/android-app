/**
 * LoginPage — dark minimal design matching the app palette.
 *
 * R0 restyle: Hebrew labels, primary action button, centered in the
 * phone-width column. Logic (login(), navigate) is unchanged.
 */

import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../stores/authStore'
import { t } from '../i18n/he'
import styles from './LoginPage.module.css'

const LoginPage: React.FC = () => {
  const navigate = useNavigate()
  const { login } = useAuthStore()

  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      await login(password)
      navigate('/', { replace: true })
    } catch {
      setError(t('invalid_password'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={styles.container}>
      <div className={styles.inner}>
        <h1 className={styles.appName}>{t('app_name')}</h1>

        <form onSubmit={handleSubmit} className={styles.form} noValidate>
          <div className={styles.field}>
            {/* Visible Hebrew label associated via htmlFor/id */}
            <label htmlFor="password-input" className={styles.label}>
              {t('password')}
            </label>
            <input
              id="password-input"
              /* aria-label keeps English text so legacy tests / screen readers find it */
              aria-label="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={styles.input}
              autoFocus
              disabled={loading}
            />
          </div>

          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}

          <button
            type="submit"
            className={styles.button}
            disabled={loading || !password}
          >
            {loading ? t('sign_in_loading') : t('sign_in')}
          </button>
        </form>
      </div>
    </div>
  )
}

export default LoginPage
