/**
 * Sizing of the composer's textarea: one line while empty, growing with the text up to a
 * maximum, like the Android field (minLines = 1, a capped height).
 *
 * The height is an inline style measured from scrollHeight, so it has to be measured again
 * whenever the text or the textarea's width changes, by whatever path: typing, a send or an
 * edit clearing the text, an edit loading a text, a window resize.
 */

/** The tallest the composer's textarea grows before it scrolls. */
export const COMPOSER_MAX_HEIGHT = 150

/**
 * The inline height for a textarea holding `text` whose content measures `scrollHeight` at
 * `width`, or null to leave the height to the stylesheet (rows = 1 and min-height: one line).
 *
 * An empty textarea is never measured: its one-line height is known, and a measurement
 * could only make it wrong (a wrapping placeholder, a hidden or not yet laid out element).
 * Nor is one with no width, where every character wraps.
 */
export function composerHeight(text: string, scrollHeight: number, width: number): string | null {
  if (text === '' || width <= 0 || !(scrollHeight > 0)) return null
  return Math.min(scrollHeight, COMPOSER_MAX_HEIGHT) + 'px'
}

/** Fits the textarea to its current text: back to one line when empty, else up to the maximum. */
export function fitTextarea(ta: HTMLTextAreaElement): void {
  // Without the old height, scrollHeight is that of the content alone, so the box can shrink
  ta.style.height = ''
  const height = composerHeight(ta.value, ta.scrollHeight, ta.clientWidth)
  if (height !== null) ta.style.height = height
}
