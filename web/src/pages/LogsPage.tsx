/**
 * LogsPage — R6 refactor.
 * Mirrors Android LogsScreen.kt: black terminal-style log viewer.
 *
 * Since there is no server-side logs endpoint, this renders client-side
 * logs captured by utils/appLogger.ts (console intercept + window.onerror).
 * Auto-scrolls to the latest entry on new logs.
 */
import React, { useEffect, useState, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { subscribe, clearLogs, getLogs } from '../utils/appLogger'
import type { LogEntry } from '../utils/appLogger'
import { t } from '../i18n/he'
import styles from './LogsPage.module.css'

// ── Log row ───────────────────────────────────────────────────────────────────

const LOG_MSG_CLASS: Record<LogEntry['level'], string> = {
  INFO: styles.logMsgInfo,
  DEBUG: styles.logMsgDebug,
  WARNING: styles.logMsgWarning,
  ERROR: styles.logMsgError,
}

interface LogRowProps {
  entry: LogEntry
  isOdd: boolean
}

const LogRow: React.FC<LogRowProps> = ({ entry, isOdd }) => (
  <div className={`${styles.logRow} ${isOdd ? styles.logRowOdd : styles.logRowEven}`}>
    <span className={styles.logTime}>{entry.timestamp}</span>
    <span className={`${styles.logMsg} ${LOG_MSG_CLASS[entry.level]}`}>
      {entry.message}
    </span>
  </div>
)

// ── Main LogsPage ─────────────────────────────────────────────────────────────

const LogsPage: React.FC = () => {
  const navigate = useNavigate()
  const [logs, setLogs] = useState<LogEntry[]>(() => getLogs())
  const listRef = useRef<HTMLDivElement>(null)

  // Subscribe to log updates
  useEffect(() => {
    const unsub = subscribe((newLogs) => setLogs(newLogs))
    return unsub
  }, [])

  // Auto-scroll to bottom on new log
  useEffect(() => {
    if (listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight
    }
  }, [logs.length])

  const handleClear = () => {
    clearLogs()
  }

  return (
    <div className={styles.page}>
      {/* Custom black topbar matching Android LogsScreen */}
      <div className={styles.topbar}>
        <div className={styles.topbarLeft}>
          <button
            className={styles.backBtn}
            onClick={() => navigate(-1)}
            aria-label="Back"
          >
            →
          </button>
          <h1 className={styles.topbarTitle} aria-label="Logs">{t('logs_title')}</h1>
        </div>
        <button className={styles.clearBtn} onClick={handleClear}>
          🗑 {t('logs_clear')}
        </button>
      </div>

      {/* Table header — LTR (always in code columns) */}
      <div className={styles.tableHeader}>
        <span className={styles.headerTime}>Time</span>
        &nbsp;&nbsp;
        <span className={styles.headerLog}>Log</span>
      </div>
      <div className={styles.headerDivider} />

      {/* Log list */}
      {logs.length === 0 ? (
        <div className={styles.empty} role="status">{t('logs_no_logs')}</div>
      ) : (
        <div className={styles.logList} ref={listRef}>
          {logs.map((entry, i) => (
            <LogRow key={i} entry={entry} isOdd={i % 2 === 1} />
          ))}
        </div>
      )}
    </div>
  )
}

export default LogsPage
