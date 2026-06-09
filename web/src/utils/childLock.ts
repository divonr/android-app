/**
 * Check whether the current local time falls within [startTime, endTime].
 * Handles overnight ranges (e.g. 23:00 → 07:00).
 * Times are "HH:MM" strings.
 * Used by SettingsPage and ChildLockPage.
 */
export function isInLockRange(startTime: string, endTime: string): boolean {
  try {
    const now = new Date()
    const [sh, sm] = startTime.split(':').map(Number)
    const [eh, em] = endTime.split(':').map(Number)
    const nowMins = now.getHours() * 60 + now.getMinutes()
    const startMins = sh * 60 + sm
    const endMins = eh * 60 + em
    if (startMins < endMins) {
      return nowMins >= startMins && nowMins < endMins
    } else {
      // overnight: e.g. 23:00 → 07:00
      return nowMins >= startMins || nowMins < endMins
    }
  } catch {
    return false
  }
}
