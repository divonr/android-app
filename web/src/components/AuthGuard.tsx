import React, { useEffect } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '../stores/authStore'

interface AuthGuardProps {
  children: React.ReactNode
}

/**
 * AuthGuard — wraps protected routes.
 * On mount it calls GET /api/session; redirects to /login if not authenticated.
 */
const AuthGuard: React.FC<AuthGuardProps> = ({ children }) => {
  const { authenticated, checking, check } = useAuthStore()
  const location = useLocation()

  useEffect(() => {
    if (authenticated === null) {
      check()
    }
  }, [authenticated, check])

  if (authenticated === null || checking) {
    return (
      <div
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: 'var(--color-text-secondary)',
          background: 'var(--color-bg)',
        }}
      >
        Loading…
      </div>
    )
  }

  if (!authenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />
  }

  return <>{children}</>
}

export default AuthGuard
