/**
 * The server's `{ error }` message of a failed request (an ApiError's body), else the error's
 * message, else [fallback]. Checked structurally, so it works with a mocked API client too.
 */
export function apiErrorMessage(err: unknown, fallback: string): string {
  const body = typeof err === 'object' && err !== null ? (err as { body?: unknown }).body : null
  if (typeof body === 'object' && body !== null) {
    const message = (body as { error?: unknown }).error
    if (typeof message === 'string' && message) return message
  }
  return err instanceof Error && err.message ? err.message : fallback
}
