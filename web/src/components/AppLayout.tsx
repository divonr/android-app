/**
 * AppLayout — full-screen centered-column shell.
 *
 * R0 CHANGE: The persistent sidebar has been removed.
 * Navigation is now full-screen-screen-switching (back-arrow per page),
 * matching the Android app's single-stack navigation model.
 *
 * Structure:
 *   .shell   — full viewport, background outside column (--bg, slightly darkened)
 *   .frame   — max-width 520px centered column, full min-height, bg --bg
 *              (all protected pages render here via <Outlet />)
 */

import React from 'react'
import { Outlet } from 'react-router-dom'
import styles from './AppLayout.module.css'

const AppLayout: React.FC = () => (
  <div className={styles.shell}>
    <div className={styles.frame}>
      <Outlet />
    </div>
  </div>
)

export default AppLayout
