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
import SkillEditorPage from './pages/SkillEditorPage'
import IntegrationsPage from './pages/IntegrationsPage'
import ChildLockPage from './pages/ChildLockPage'
import LogsPage from './pages/LogsPage'
import WelcomePage from './pages/WelcomePage'

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
          <Route path="skills/:name/edit" element={<SkillEditorPage />} />
          <Route path="integrations" element={<IntegrationsPage />} />
          <Route path="child-lock" element={<ChildLockPage />} />
          <Route path="logs" element={<LogsPage />} />
          <Route path="welcome" element={<WelcomePage />} />
        </Route>

        {/* Catch-all */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
