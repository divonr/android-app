import type {
  ApiKey,
  AppSettings,
  Attachment,
  BranchInfo,
  BranchResult,
  Chat,
  ChatGroup,
  CustomProviderConfig,
  FullCustomProviderConfig,
  GitHubConnection,
  GoogleWorkspaceConnection,
  EnabledGoogleServices,
  ImportChatResponse,
  InstalledSkill,
  KeyReorderRequest,
  OkResponse,
  Provider,
  ProviderModel_Flat,
  ResendMessageRequest,
  SearchResult,
  SessionResponse,
  TitleResponse,
  UserChatHistory,
} from './types'

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/**
 * Base URL for API calls.
 * - In development the Vite proxy rewrites /api → http://localhost:8091/api,
 *   so we use an empty string (relative paths).
 * - In production, the web app is served from the same origin as the Ktor
 *   server, so relative paths work there too.
 */
const BASE_URL = ''

// ---------------------------------------------------------------------------
// Typed error
// ---------------------------------------------------------------------------

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: unknown,
    message?: string,
  ) {
    super(message ?? `HTTP ${status}`)
    this.name = 'ApiError'
  }
}

// ---------------------------------------------------------------------------
// 401 event — emitted globally so the app can redirect to login
// ---------------------------------------------------------------------------

const UNAUTHENTICATED_EVENT = 'api:unauthenticated'

export function onUnauthenticated(handler: () => void): () => void {
  window.addEventListener(UNAUTHENTICATED_EVENT, handler)
  return () => window.removeEventListener(UNAUTHENTICATED_EVENT, handler)
}

function emitUnauthenticated(): void {
  window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT))
}

// ---------------------------------------------------------------------------
// Core request helper
// ---------------------------------------------------------------------------

export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const url = `${BASE_URL}${path}`
  const res = await fetch(url, {
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...options.headers,
    },
    ...options,
  })

  if (res.status === 401) {
    emitUnauthenticated()
    let body: unknown
    try {
      body = await res.json()
    } catch {
      body = null
    }
    throw new ApiError(401, body, 'Unauthenticated')
  }

  if (!res.ok) {
    let body: unknown
    try {
      body = await res.json()
    } catch {
      body = await res.text().catch(() => null)
    }
    throw new ApiError(res.status, body)
  }

  // 204 No Content
  if (res.status === 204) {
    return undefined as T
  }

  const contentType = res.headers.get('Content-Type') ?? ''
  if (contentType.includes('application/json')) {
    return res.json() as Promise<T>
  }
  // Fallback: return text as T (e.g. plain-text skill content)
  return res.text() as unknown as T
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

export const auth = {
  /** POST /login — sets the session cookie */
  login: (password: string) =>
    request<OkResponse>('/login', {
      method: 'POST',
      body: JSON.stringify({ password }),
    }),

  /** POST /logout — clears the session cookie */
  logout: () =>
    request<OkResponse>('/logout', { method: 'POST' }),

  /** GET /api/session — check if the session is still valid */
  session: () =>
    request<SessionResponse>('/api/session'),
}

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

export const providers = {
  /** GET /api/providers — full provider list */
  list: () => request<Provider[]>('/api/providers'),

  /** GET /api/providers/models — flat model list for the model picker */
  models: () => request<ProviderModel_Flat[]>('/api/providers/models'),

  /** GET /api/providers/detailed — providers with pricing/releaseOrder metadata */
  detailed: () => request<import('./types').ProviderDetail[]>('/api/providers/detailed'),

  /** POST /api/providers/refresh — force-refresh model list */
  refresh: () =>
    request<{ ok: true; changed: boolean }>('/api/providers/refresh', {
      method: 'POST',
    }),
}

// ---------------------------------------------------------------------------
// Chat History
// ---------------------------------------------------------------------------

export const chats = {
  /** GET /api/chats — returns UserChatHistory */
  list: () => request<UserChatHistory>('/api/chats'),

  /** GET /api/chats/:id */
  get: (chatId: string) => request<Chat>(`/api/chats/${chatId}`),

  /** POST /api/chats */
  create: (params: { previewName: string; systemPrompt: string; groupId?: string | null }) =>
    request<Chat>('/api/chats', { method: 'POST', body: JSON.stringify(params) }),

  /** PATCH /api/chats/:id */
  update: (chatId: string, params: { previewName?: string; systemPrompt?: string }) =>
    request<Chat>(`/api/chats/${chatId}`, {
      method: 'PATCH',
      body: JSON.stringify(params),
    }),

  /** DELETE /api/chats/:id */
  delete: (chatId: string) =>
    request<void>(`/api/chats/${chatId}`, { method: 'DELETE' }),

  /** GET /api/chats/:id/export */
  export: (chatId: string) => request<unknown>(`/api/chats/${chatId}/export`),

  /** POST /api/chats/import */
  import: (content: string) =>
    request<ImportChatResponse>('/api/chats/import', {
      method: 'POST',
      body: JSON.stringify({ content }),
    }),

  /** POST /api/chats/:id/generate-title */
  generateTitle: (chatId: string, provider?: string) =>
    request<TitleResponse>(`/api/chats/${chatId}/generate-title`, {
      method: 'POST',
      body: JSON.stringify({ provider: provider ?? 'auto' }),
    }),
}

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

export const messages = {
  /** POST /api/chats/:id/messages */
  add: (
    chatId: string,
    msg: { role: string; text: string; attachments?: Attachment[] },
  ) =>
    request<import('./types').Message>(`/api/chats/${chatId}/messages`, {
      method: 'POST',
      body: JSON.stringify(msg),
    }),

  /** DELETE /api/chats/:chatId/messages/:messageId */
  delete: (chatId: string, messageId: string) =>
    request<Chat>(`/api/chats/${chatId}/messages/${messageId}`, {
      method: 'DELETE',
    }),

  /** POST /api/chats/:chatId/messages/:messageId/resend */
  resend: (chatId: string, messageId: string, params: ResendMessageRequest) =>
    // Returns SSE stream — caller should use streamClient.resend() instead.
    // This overload is provided for completeness; the SSE client handles the
    // actual streaming.
    fetch(`${BASE_URL}/api/chats/${chatId}/messages/${messageId}/resend`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params),
    }),
}

// ---------------------------------------------------------------------------
// Branching
// ---------------------------------------------------------------------------

export const branching = {
  /** POST /api/chats/:chatId/branch */
  create: (
    chatId: string,
    params: { nodeId: string; newUserMessage: { role: string; text: string } },
  ) =>
    request<BranchResult>(`/api/chats/${chatId}/branch`, {
      method: 'POST',
      body: JSON.stringify(params),
    }),

  /** POST /api/chats/:chatId/nodes/:nodeId/switch */
  switchVariant: (chatId: string, nodeId: string, variantIndex: number) =>
    request<Chat>(`/api/chats/${chatId}/nodes/${nodeId}/switch`, {
      method: 'POST',
      body: JSON.stringify({ variantIndex }),
    }),

  /** GET /api/chats/:chatId/nodes/:nodeId/branch-info */
  info: (chatId: string, nodeId: string) =>
    request<BranchInfo>(`/api/chats/${chatId}/nodes/${nodeId}/branch-info`),
}

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------

export const groups = {
  /** GET /api/groups */
  list: () => request<ChatGroup[]>('/api/groups'),

  /** POST /api/groups */
  create: (groupName: string) =>
    request<ChatGroup>('/api/groups', {
      method: 'POST',
      body: JSON.stringify({ groupName }),
    }),

  /** PATCH /api/groups/:id */
  update: (
    groupId: string,
    params: { groupName?: string; systemPrompt?: string; isProject?: boolean },
  ) =>
    request<ChatGroup>(`/api/groups/${groupId}`, {
      method: 'PATCH',
      body: JSON.stringify(params),
    }),

  /** DELETE /api/groups/:id */
  delete: (groupId: string) =>
    request<void>(`/api/groups/${groupId}`, { method: 'DELETE' }),

  /** POST /api/groups/:groupId/chats/:chatId */
  addChat: (groupId: string, chatId: string) =>
    request<OkResponse>(`/api/groups/${groupId}/chats/${chatId}`, {
      method: 'POST',
    }),

  /** DELETE /api/groups/:groupId/chats/:chatId */
  removeChat: (groupId: string, chatId: string) =>
    request<OkResponse>(`/api/groups/${groupId}/chats/${chatId}`, {
      method: 'DELETE',
    }),

  /** POST /api/groups/:groupId/attachments */
  addAttachment: (groupId: string, attachment: Attachment) =>
    request<ChatGroup>(`/api/groups/${groupId}/attachments`, {
      method: 'POST',
      body: JSON.stringify(attachment),
    }),

  /** DELETE /api/groups/:groupId/attachments/:index */
  removeAttachment: (groupId: string, attachmentIndex: number) =>
    request<ChatGroup>(
      `/api/groups/${groupId}/attachments/${attachmentIndex}`,
      { method: 'DELETE' },
    ),
}

// ---------------------------------------------------------------------------
// API Keys
// ---------------------------------------------------------------------------

export const apiKeys = {
  /** GET /api/keys */
  list: () => request<ApiKey[]>('/api/keys'),

  /** POST /api/keys */
  create: (key: Omit<ApiKey, 'id'> & { id?: string }) =>
    request<ApiKey>('/api/keys', {
      method: 'POST',
      body: JSON.stringify(key),
    }),

  /** PATCH /api/keys/:id */
  update: (keyId: string, params: { key?: string; isActive?: boolean; customName?: string | null }) =>
    request<ApiKey>(`/api/keys/${keyId}`, {
      method: 'PATCH',
      body: JSON.stringify(params),
    }),

  /** DELETE /api/keys/:id */
  delete: (keyId: string) =>
    request<void>(`/api/keys/${keyId}`, { method: 'DELETE' }),

  /** POST /api/keys/:id/toggle */
  toggle: (keyId: string) =>
    request<ApiKey>(`/api/keys/${keyId}/toggle`, { method: 'POST' }),

  /** POST /api/keys/reorder */
  reorder: (params: KeyReorderRequest) =>
    request<ApiKey[]>('/api/keys/reorder', {
      method: 'POST',
      body: JSON.stringify(params),
    }),
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

export const settings = {
  /** GET /api/settings */
  get: () => request<AppSettings>('/api/settings'),

  /** PATCH /api/settings */
  update: (params: Partial<AppSettings>) =>
    request<AppSettings>('/api/settings', {
      method: 'PATCH',
      body: JSON.stringify(params),
    }),
}

// ---------------------------------------------------------------------------
// Custom Providers
// ---------------------------------------------------------------------------

export const customProviders = {
  list: () => request<CustomProviderConfig[]>('/api/custom-providers'),
  create: (cfg: CustomProviderConfig) =>
    request<CustomProviderConfig>('/api/custom-providers', {
      method: 'POST',
      body: JSON.stringify(cfg),
    }),
  replace: (id: string, cfg: CustomProviderConfig) =>
    request<CustomProviderConfig>(`/api/custom-providers/${id}`, {
      method: 'PUT',
      body: JSON.stringify(cfg),
    }),
  delete: (id: string) =>
    request<void>(`/api/custom-providers/${id}`, { method: 'DELETE' }),
}

export const fullCustomProviders = {
  list: () => request<FullCustomProviderConfig[]>('/api/full-custom-providers'),
  create: (cfg: FullCustomProviderConfig) =>
    request<FullCustomProviderConfig>('/api/full-custom-providers', {
      method: 'POST',
      body: JSON.stringify(cfg),
    }),
  replace: (id: string, cfg: FullCustomProviderConfig) =>
    request<FullCustomProviderConfig>(`/api/full-custom-providers/${id}`, {
      method: 'PUT',
      body: JSON.stringify(cfg),
    }),
  delete: (id: string) =>
    request<void>(`/api/full-custom-providers/${id}`, { method: 'DELETE' }),
}

// ---------------------------------------------------------------------------
// Skills
// ---------------------------------------------------------------------------

export const skills = {
  /** GET /api/skills */
  list: () => request<InstalledSkill[]>('/api/skills'),

  /** GET /api/skills/:name — returns plain text */
  getContent: (name: string) => request<string>(`/api/skills/${name}`),

  /** POST /api/skills */
  create: (params: { name?: string; description?: string; body?: string; importText?: string }) =>
    request<InstalledSkill>('/api/skills', {
      method: 'POST',
      body: JSON.stringify(params),
    }),

  /** PATCH /api/skills/:name */
  setEnabled: (name: string, enabled: boolean) =>
    request<InstalledSkill>(`/api/skills/${name}`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled }),
    }),

  /** DELETE /api/skills/:name */
  delete: (name: string) =>
    request<void>(`/api/skills/${name}`, { method: 'DELETE' }),

  /** GET /api/skills/:name/export — returns download URL (use as anchor href) */
  exportUrl: (name: string) => `${BASE_URL}/api/skills/${encodeURIComponent(name)}/export`,

  /**
   * POST /api/skills/import-zip — multipart ZIP upload.
   * Returns the created InstalledSkill.
   */
  importZip: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return fetch(`${BASE_URL}/api/skills/import-zip`, {
      method: 'POST',
      credentials: 'include',
      body: form,
    }).then(async (res) => {
      if (!res.ok) {
        const body = await res.json().catch(() => null)
        throw new ApiError(res.status, body)
      }
      return res.json() as Promise<InstalledSkill>
    })
  },
}

// ---------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------

export const files = {
  /**
   * POST /api/files/upload — multipart upload.
   * Returns an Attachment with server paths and provider file IDs.
   */
  upload: (file: File, provider = '', chatId = '') => {
    const form = new FormData()
    form.append('file', file)
    form.append('provider', provider)
    form.append('chatId', chatId)
    return fetch(`${BASE_URL}/api/files/upload`, {
      method: 'POST',
      credentials: 'include',
      body: form,
      // No Content-Type header — browser sets multipart boundary automatically
    }).then(async (res) => {
      if (!res.ok) {
        const body = await res.json().catch(() => null)
        throw new ApiError(res.status, body)
      }
      return res.json() as Promise<Attachment>
    })
  },

  /** DELETE /api/files */
  delete: (filePath: string) =>
    request<void>('/api/files', {
      method: 'DELETE',
      body: JSON.stringify({ filePath }),
    }),
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

export const search = {
  /** GET /api/search?q=... */
  query: (q: string) =>
    request<SearchResult[]>(`/api/search?q=${encodeURIComponent(q)}`),
}

// ---------------------------------------------------------------------------
// Integrations
// ---------------------------------------------------------------------------

export const integrations = {
  github: {
    get: () => request<GitHubConnection | null>('/api/integrations/github'),
    disconnect: () =>
      request<void>('/api/integrations/github', { method: 'DELETE' }),
    /** Navigates to OAuth start — returns the redirect URL for the browser */
    startOAuth: () => {
      window.location.href = '/oauth/github/start'
    },
  },
  google: {
    get: () => request<GoogleWorkspaceConnection | null>('/api/integrations/google'),
    disconnect: () =>
      request<void>('/api/integrations/google', { method: 'DELETE' }),
    startOAuth: () => {
      window.location.href = '/oauth/google/start'
    },
    updateServices: (services: Partial<EnabledGoogleServices>) =>
      request<OkResponse>('/api/integrations/google/services', {
        method: 'PATCH',
        body: JSON.stringify(services),
      }),
  },
}

// ---------------------------------------------------------------------------
// Remote Sync (server-side sync engine control)
// ---------------------------------------------------------------------------

export const sync = {
  /** POST /api/sync/pull — trigger an immediate pull from the remote sync server */
  pull: () => request<OkResponse>('/api/sync/pull', { method: 'POST' }),

  /** GET /api/sync/status — returns current sync enablement state and remote reachability probe */
  status: () =>
    request<{ enabled: boolean; serverBaseUrl: string; lastChangeTick: number; reachable: boolean | null }>(
      '/api/sync/status',
    ),
}

// ---------------------------------------------------------------------------
// Health (unauthenticated)
// ---------------------------------------------------------------------------

export const health = {
  check: () => request<{ status: 'ok' }>('/health'),
}
