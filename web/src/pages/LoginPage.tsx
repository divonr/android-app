/**
 * LoginPage — Google-only sign-in (Step 6 refactor).
 *
 * The password form is gone. Login is exclusively via Google Sign-In:
 *   - A single button does a full-page navigation to GET /auth/google/start
 *     (cannot be XHR — the OAuth dance requires browser redirects).
 *   - On callback failure, the server redirects to /login?error={code}.
 *     This page reads that param and shows a human-readable Hebrew message.
 */

import React from 'react'
import { useSearchParams } from 'react-router-dom'
import { t } from '../i18n/he'
import styles from './LoginPage.module.css'

function getErrorMessage(code: string | null): string | null {
  switch (code) {
    case 'invalid_state':
      return t('google_auth_error_invalid_state')
    case 'token_exchange_failed':
      return t('google_auth_error_token_exchange_failed')
    case 'token_invalid':
      return t('google_auth_error_token_invalid')
    case 'not_allowed':
      return t('google_auth_error_not_allowed')
    case 'sync_unavailable':
      return t('google_auth_error_sync_unavailable')
    default:
      return null
  }
}

const LoginPage: React.FC = () => {
  const [searchParams] = useSearchParams()
  const errorCode = searchParams.get('error')
  const errorMessage = getErrorMessage(errorCode)

  const handleSignIn = () => {
    window.location.href = '/auth/google/start'
  }

  return (
    <div className={styles.container}>
      <div className={styles.inner}>
        <h1 className={styles.appName}>{t('app_name')}</h1>

        {errorMessage && (
          <p className={styles.error} role="alert">
            {errorMessage}
          </p>
        )}

        <button
          type="button"
          className={styles.button}
          onClick={handleSignIn}
        >
          {t('sign_in_with_google')}
        </button>
      </div>
    </div>
  )
}

export default LoginPage
