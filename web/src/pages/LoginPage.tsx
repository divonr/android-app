import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../stores/authStore'
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
      setError('Invalid password. Please try again.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={styles.container}>
      <div className={styles.card}>
        <h1 className={styles.title}>ApI</h1>
        <p className={styles.subtitle}>LLM Web Interface</p>

        <form onSubmit={handleSubmit} className={styles.form}>
          <div className={styles.field}>
            <label htmlFor="password" className={styles.label}>
              Password
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={styles.input}
              placeholder="Enter server password"
              autoFocus
              disabled={loading}
            />
          </div>

          {error && <p className={styles.error}>{error}</p>}

          <button
            type="submit"
            className={styles.button}
            disabled={loading || !password}
          >
            {loading ? 'Signing in…' : 'Sign In'}
          </button>
        </form>
      </div>
    </div>
  )
}

export default LoginPage
