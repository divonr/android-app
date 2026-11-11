/**
 * appLogger.ts — client-side log capture, mirrors Android AppLogger.
 *
 * Intercepts console.log/warn/error, stores entries in a ring buffer,
 * and lets subscribers (e.g. LogsPage) observe the current log list.
 */

export type LogLevel = 'INFO' | 'WARNING' | 'ERROR' | 'DEBUG'

export interface LogEntry {
  timestamp: string
  level: LogLevel
  message: string
}

const MAX_LOGS = 500

let _logs: LogEntry[] = []
const _listeners: Set<(logs: LogEntry[]) => void> = new Set()

function _notify() {
  const snapshot = [..._logs]
  _listeners.forEach((fn) => fn(snapshot))
}

function _timestamp(): string {
  const d = new Date()
  return [
    String(d.getHours()).padStart(2, '0'),
    String(d.getMinutes()).padStart(2, '0'),
    String(d.getSeconds()).padStart(2, '0'),
  ].join(':')
}

export function addLog(level: LogLevel, message: string): void {
  _logs = [..._logs, { timestamp: _timestamp(), level, message }]
  if (_logs.length > MAX_LOGS) _logs = _logs.slice(-MAX_LOGS)
  _notify()
}

export function clearLogs(): void {
  _logs = []
  _notify()
}

export function getLogs(): LogEntry[] {
  return [..._logs]
}

/** Subscribe to log changes. Returns an unsubscribe function. */
export function subscribe(fn: (logs: LogEntry[]) => void): () => void {
  _listeners.add(fn)
  fn([..._logs]) // immediate snapshot
  return () => _listeners.delete(fn)
}

// ── Console intercept (skip in Vitest/test environment to avoid noise) ───────

if (import.meta.env?.MODE !== 'test') {
  const origLog = console.log.bind(console)
  const origWarn = console.warn.bind(console)
  const origError = console.error.bind(console)

  console.log = (...args: unknown[]) => {
    origLog(...args)
    addLog('INFO', args.map(String).join(' '))
  }
  console.warn = (...args: unknown[]) => {
    origWarn(...args)
    addLog('WARNING', args.map(String).join(' '))
  }
  console.error = (...args: unknown[]) => {
    origError(...args)
    addLog('ERROR', args.map(String).join(' '))
  }

  window.addEventListener('error', (e) => {
    addLog('ERROR', e.message || String(e))
  })
}
