/**
 * chatDates.ts — WhatsApp-style date labels for the chat message list.
 * Mirrors DateSeparators.kt in the Android app.
 */

const HEBREW_WEEKDAYS = ['יום ראשון', 'יום שני', 'יום שלישי', 'יום רביעי', 'יום חמישי', 'יום שישי', 'שבת']

const HEBREW_MONTHS = [
  'ינואר', 'פברואר', 'מרץ', 'אפריל', 'מאי', 'יוני',
  'יולי', 'אוגוסט', 'ספטמבר', 'אוקטובר', 'נובמבר', 'דצמבר',
]

/** Local calendar day of an ISO datetime as "YYYY-MM-DD", or null if missing/invalid. */
export function messageDayKey(datetime: string | null | undefined): string | null {
  if (!datetime) return null
  const d = new Date(datetime)
  if (isNaN(d.getTime())) return null
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd}`
}

/** Label for a day key: today / yesterday / weekday (last week) / full date. */
export function formatDayLabel(dayKey: string, now: Date = new Date()): string {
  const [y, m, d] = dayKey.split('-').map(Number)
  const date = new Date(y, m - 1, d)
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const daysAgo = Math.round((today.getTime() - date.getTime()) / 86_400_000)
  if (daysAgo === 0) return 'היום'
  if (daysAgo === 1) return 'אתמול'
  if (daysAgo >= 2 && daysAgo <= 6) return HEBREW_WEEKDAYS[date.getDay()]
  const base = `${d} ב${HEBREW_MONTHS[m - 1]}`
  return y === today.getFullYear() ? base : `${base} ${y}`
}
