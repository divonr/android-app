/**
 * utils/chatUtils.ts — Shared utility functions for chat display.
 *
 * Hoisted from ChatHistoryPage.tsx (R2) so both ChatHistoryPage and
 * ChatTopBar can use getModelInitial / formatTimestamp without duplication.
 * Mirrors ChatUtils.kt logic verbatim.
 */

/** Mirror of ChatUtils.kt getModelInitial */
export function getModelInitial(model: string | null | undefined): string {
  const m = (model ?? '').toLowerCase()
  if (m.includes('gpt-4')) return 'G4'
  if (m.includes('gpt-3')) return 'G3'
  if (m.includes('claude')) return 'C'
  if (m.includes('gemini')) return 'Gm'
  if (m.includes('llama')) return 'L'
  if (m.includes('mistral')) return 'M'
  const first = (model ?? '').slice(0, 1).toUpperCase()
  return first || '?'
}

/**
 * Mirror of ModelLogoUtils.kt getModelLogoPath — maps a model name to the
 * logo asset under /models_logos (same PNGs as the Android assets dir).
 * Returns null when no logo mapping exists (caller falls back to the initial).
 */
export function getModelLogoPath(model: string | null | undefined): string | null {
  if (!model) return null
  const lowerName = model.toLowerCase()
  const basePath = '/models_logos/'

  // 1. OpenAI: "gpt" or "o{digit}" → gpt.png
  if (lowerName.includes('gpt') || /.*o\d.*/.test(lowerName)) return `${basePath}gpt.png`
  // 2. Anthropic
  if (
    lowerName.includes('claude') ||
    lowerName.includes('sonnet') ||
    lowerName.includes('opus') ||
    lowerName.includes('haiku')
  ) return `${basePath}claude.png`
  // 3. Google
  if (lowerName.includes('gemini')) return `${basePath}gemini.png`
  // 4. xAI
  if (lowerName.includes('grok')) return `${basePath}grok.png`
  // 5. Cohere
  if (lowerName.includes('command')) return `${basePath}command.png`
  // 6. DeepSeek
  if (lowerName.includes('deep') && lowerName.includes('seek')) return `${basePath}deepseek.png`
  // 7. GLM
  if (lowerName.includes('glm')) return `${basePath}glm.png`
  // 8. MiniMax
  if (lowerName.includes('minimax')) return `${basePath}minimax.png`

  return null
}

/** Mirror of ChatUtils.kt formatTimestamp */
export function formatTimestamp(ts: number): string {
  const d = new Date(ts)
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const dStart = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  const diffDays = Math.round(
    (todayStart.getTime() - dStart.getTime()) / 86_400_000,
  )
  if (diffDays === 0) {
    return d.toLocaleTimeString('he-IL', {
      hour: '2-digit',
      minute: '2-digit',
    })
  }
  if (diffDays === 1) return 'אתמול'
  if (diffDays < 7) {
    return d.toLocaleDateString('he-IL', { weekday: 'long' })
  }
  const day = String(d.getDate()).padStart(2, '0')
  const month = String(d.getMonth() + 1).padStart(2, '0')
  return `${day}/${month}/${d.getFullYear()}`
}
