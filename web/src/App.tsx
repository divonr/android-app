import React, { useEffect } from 'react'
import { BrowserRouter, Route, Routes, Navigate } from 'react-router-dom'
import { onUnauthenticated } from './api/client'
import { useAuthStore } from './stores/authStore'
import AuthGuard from './components/AuthGuard'
import AppLayout from './components/AppLayout'
import LoginPage from './pages/LoginPage'
import ChatHistoryPage from './pages/ChatHistoryPage'
import ChatPage from './pages/ChatPage'
import GroupPage from './pages/GroupPage'
import KeysPage from './pages/KeysPage'
import SettingsPage from './pages/SettingsPage'
import SkillsPage from './pages/SkillsPage'
import IntegrationsPage from './pages/IntegrationsPage'

const App: React.FC = () => {
  const { markUnauthenticated } = useAuthStore()

  // Listen for global 401 events emitted by the API client
  useEffect(() => {
    return onUnauthenticated(() => {
      markUnauthenticated()
    })
  }, [markUnauthenticated])

  return (
    <BrowserRouter>
      <Routes>
        {/* Public */}
        <Route path="/login" element={<LoginPage />} />

        {/* Protected — all routes inside AuthGuard + AppLayout */}
        <Route
          element={
            <AuthGuard>
              <AppLayout />
            </AuthGuard>
          }
        >
          <Route index element={<ChatHistoryPage />} />
          <Route path="chat/:id" element={<ChatPage />} />
          <Route path="groups/:id" element={<GroupPage />} />
          <Route path="keys" element={<KeysPage />} />
          <Route path="settings" element={<SettingsPage />} />
          <Route path="skills" element={<SkillsPage />} />
          <Route path="integrations" element={<IntegrationsPage />} />
        </Route>

        {/* Catch-all */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
