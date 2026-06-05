import React, { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuthStore } from '../stores/authStore'
import styles from './AppLayout.module.css'

const NAV_ITEMS = [
  { to: '/', label: 'Chats', exact: true },
  { to: '/keys', label: 'API Keys', exact: false },
  { to: '/skills', label: 'Skills', exact: false },
  { to: '/integrations', label: 'Integrations', exact: false },
  { to: '/settings', label: 'Settings', exact: false },
]

const AppLayout: React.FC = () => {
  const navigate = useNavigate()
  const { logout } = useAuthStore()
  const [sidebarOpen, setSidebarOpen] = useState(false)

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className={styles.root}>
      {/* Mobile overlay */}
      {sidebarOpen && (
        <div
          className={styles.overlay}
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* Sidebar */}
      <nav className={`${styles.sidebar} ${sidebarOpen ? styles.sidebarOpen : ''}`}>
        <div className={styles.sidebarHeader}>
          <span className={styles.logo}>ApI</span>
          <button
            className={styles.closeBtn}
            onClick={() => setSidebarOpen(false)}
            aria-label="Close menu"
          >
            ✕
          </button>
        </div>

        <ul className={styles.navList}>
          {NAV_ITEMS.map((item) => (
            <li key={item.to}>
              <NavLink
                to={item.to}
                end={item.exact}
                className={({ isActive }) =>
                  `${styles.navLink} ${isActive ? styles.navLinkActive : ''}`
                }
                onClick={() => setSidebarOpen(false)}
              >
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>

        <div className={styles.sidebarFooter}>
          <button className={styles.logoutBtn} onClick={handleLogout}>
            Sign Out
          </button>
        </div>
      </nav>

      {/* Main area */}
      <div className={styles.main}>
        <header className={styles.topbar}>
          <button
            className={styles.menuBtn}
            onClick={() => setSidebarOpen(true)}
            aria-label="Open menu"
          >
            ☰
          </button>
          <span className={styles.topbarTitle}>ApI</span>
        </header>

        <div className={styles.content}>
          <Outlet />
        </div>
      </div>
    </div>
  )
}

export default AppLayout
