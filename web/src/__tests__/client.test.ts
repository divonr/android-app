import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { request, ApiError, onUnauthenticated } from '../api/client'

describe('request() helper', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('returns parsed JSON on 200', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: () => Promise.resolve({ status: 'ok' }),
      }),
    )

    const result = await request<{ status: string }>('/health')
    expect(result).toEqual({ status: 'ok' })
  })

  it('throws ApiError with status and body on 4xx', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: () => Promise.resolve({ error: 'Not found' }),
      }),
    )

    await expect(request('/api/chats/nonexistent')).rejects.toBeInstanceOf(ApiError)

    try {
      await request('/api/chats/nonexistent')
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError)
      const apiErr = err as ApiError
      expect(apiErr.status).toBe(404)
      expect((apiErr.body as { error: string }).error).toBe('Not found')
    }
  })

  it('emits unauthenticated event and throws ApiError on 401', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: () => Promise.resolve({ error: 'Unauthenticated' }),
      }),
    )

    const events: Event[] = []
    const handler = (e: Event) => events.push(e)
    window.addEventListener('api:unauthenticated', handler)

    try {
      await request('/api/settings')
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError)
      expect((err as ApiError).status).toBe(401)
    }

    window.removeEventListener('api:unauthenticated', handler)
    expect(events).toHaveLength(1)
  })

  it('returns undefined (no throw) on 204', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 204,
        headers: new Headers({}),
      }),
    )

    const result = await request<void>('/api/chats/c1', { method: 'DELETE' })
    expect(result).toBeUndefined()
  })

  it('onUnauthenticated registers / deregisters handler', () => {
    const called: number[] = []
    const unregister = onUnauthenticated(() => called.push(1))

    window.dispatchEvent(new Event('api:unauthenticated'))
    expect(called).toHaveLength(1)

    unregister()
    window.dispatchEvent(new Event('api:unauthenticated'))
    expect(called).toHaveLength(1) // not called again after unregister
  })
})
