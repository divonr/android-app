# Web UI REST + SSE API Contract

This document is the authoritative specification for the `:server` Ktor module.
Later phases implement endpoints defined here.
Data model class names reference the `:shared` module (`com.example.ApI.data.model.*`).

---

## Base URL

All API endpoints are prefixed with `/api`.
Auth endpoints (`/login`, `/logout`, `/oauth/*`) live at the root.
`/health` lives at the root (unauthenticated).

```
GET  /health                        → 200 {"status":"ok"}
POST /login                         → set session cookie
POST /logout                        → clear session cookie
GET  /oauth/github/callback         → GitHub OAuth callback
GET  /oauth/google/callback         → Google OAuth callback
GET  /api/**                        → all require valid session
```

---

## Authentication (P1)

### Session mechanism

- Password read from environment variable `WEB_UI_PASSWORD` at startup.
- Ktor `Sessions` plugin with a signed HTTP-only cookie (`llm_web_session`).
- All `/api/**` routes protected by `authenticate("session") { }`.

### POST /login

**Request** (JSON body):
```json
{ "password": "string" }
```

**Response 200** — sets `Set-Cookie: llm_web_session=<signed>; HttpOnly; SameSite=Strict`:
```json
{ "ok": true }
```

**Response 401**:
```json
{ "error": "Invalid password" }
```

### POST /logout

Clears the session cookie.

**Response 200**:
```json
{ "ok": true }
```

---

## Health

### GET /health

No auth required.

**Response 200**:
```json
{ "status": "ok" }
```

---

## Providers & Models (P2)

### GET /api/providers

Returns the full list of providers as built by `DataRepository.loadProviders()`.

**Response 200** — array of `Provider`:
```json
[
  {
    "provider": "openai",
    "models": [ { "name": "gpt-4o", ... }, ... ],
    "request": { "request_type": "openai", "base_url": "...", "headers": {}, ... },
    "response_important_fields": { ... }
  }
]
```

### GET /api/providers/models

Returns a flat list of all available model names grouped by provider (convenience endpoint for the UI model picker).

**Response 200**:
```json
[
  { "provider": "openai", "modelName": "gpt-4o" },
  { "provider": "anthropic", "modelName": "claude-opus-4-5" }
]
```

### POST /api/providers/refresh

Triggers `DataRepository.forceRefreshModels()`.

**Response 200**:
```json
{ "ok": true, "changed": true }
```

---

## Chat History (P2)

Username is always the `current_user` from `AppSettings` (single-user server — the web server does not support multi-user).
The server resolves the username internally from `loadAppSettings().current_user`.

### GET /api/chats

Returns `UserChatHistory` for the current user.

**Response 200** — `UserChatHistory`:
```json
{
  "user_name": "alice",
  "chat_history": [ { "chat_id": "...", "preview_name": "...", "messages": [...], ... } ],
  "groups": [ { "group_id": "...", "group_name": "...", ... } ]
}
```

### GET /api/chats/{chatId}

Returns a single `Chat` by ID.

**Response 200** — `Chat`

**Response 404**:
```json
{ "error": "Chat not found" }
```

### POST /api/chats

Creates a new chat.

**Request**:
```json
{ "previewName": "string", "systemPrompt": "string", "groupId": "string|null" }
```

**Response 201** — `Chat`

### PATCH /api/chats/{chatId}

Updates chat metadata (rename, system prompt).

**Request** (all fields optional):
```json
{ "previewName": "string", "systemPrompt": "string" }
```

**Response 200** — updated `Chat`

### DELETE /api/chats/{chatId}

Deletes the chat from history.

**Response 204** — no content

### GET /api/chats/{chatId}/export

Returns the chat as exported JSON (calls `DataRepository.getChatJson`).

**Response 200** — raw JSON string (Content-Type: application/json)

### POST /api/chats/import

Imports a single chat from JSON.

**Request** (JSON body):
```json
{ "content": "<chat json string>" }
```

**Response 201**:
```json
{ "chatId": "new-chat-id" }
```

---

## Messages (P4)

### POST /api/chats/{chatId}/messages

Adds a message directly to chat history (used for user messages before streaming begins).

**Request** — `Message`:
```json
{
  "role": "user",
  "text": "Hello",
  "attachments": []
}
```

**Response 201** — `Message` with generated `id` and `datetime`

### DELETE /api/chats/{chatId}/messages/{messageId}

Deletes a message and all subsequent messages (calls `deleteMessagesFromPoint`).

**Response 200** — updated `Chat`

### POST /api/chats/{chatId}/messages/{messageId}/resend

Re-sends from a given message (deletes from that point, then triggers streaming send).

**Request**:
```json
{
  "provider": "openai",
  "modelName": "gpt-4o",
  "systemPrompt": "string",
  "webSearchEnabled": false,
  "enabledToolIds": [],
  "thinkingBudget": "none",
  "temperature": null
}
```

**Response** — SSE stream (same as `POST /api/chat/send`)

---

## Streaming Send (P3)

### POST /api/chat/send

Sends a user message and streams the response via Server-Sent Events.
The client should use `EventSource` or a fetch-based SSE reader.

**Request** (JSON body):
```json
{
  "chatId": "string",
  "provider": "openai",
  "modelName": "gpt-4o",
  "messages": [ { "role": "user", "text": "Hello" } ],
  "systemPrompt": "",
  "webSearchEnabled": false,
  "enabledToolIds": [],
  "thinkingBudget": "none",
  "temperature": null,
  "projectAttachments": []
}
```

**`thinkingBudget`** values: `"none"` | `"low"` | `"medium"` | `"high"` | `"max"` (maps to `ThinkingBudgetValue`).

**Response** — `Content-Type: text/event-stream`

Each SSE event has the form:
```
event: <event_type>
data: <JSON payload>
```

#### SSE event types (mapped from `StreamingCallback`):

| SSE `event` field    | `StreamingCallback` method     | `data` JSON shape                                                                 |
|----------------------|-------------------------------|-----------------------------------------------------------------------------------|
| `partial`            | `onPartialResponse(text)`     | `{ "text": "string" }`                                                            |
| `thinking_started`   | `onThinkingStarted()`         | `{}`                                                                              |
| `thinking_partial`   | `onThinkingPartial(text)`     | `{ "text": "string" }`                                                            |
| `thinking_complete`  | `onThinkingComplete(...)`     | `{ "thoughts": "string|null", "durationSeconds": 1.5, "status": "PRESENT" }`     |
| `tool_call`          | `onToolCall(toolCall, ...)`   | `{ "toolId": "string", "toolName": "string", "parameters": {} }`                 |
| `tool_result`        | after `ToolRegistry.execute`  | `{ "toolId": "string", "success": true, "output": "string" }`                    |
| `complete`           | `onComplete(fullText)`        | `{ "text": "string", "messageId": "string" }`                                    |
| `error`              | `onError(error)`              | `{ "error": "string" }`                                                           |

After the `complete` or `error` event, the server closes the SSE stream.

Tool execution sequence: on `onToolCall`, the server calls `ToolRegistry.getInstance().executeTool(...)`,
saves the tool messages via `onSaveToolMessages`, then emits `tool_result` and continues streaming.
This mirrors `desktop/ui/managers/streaming/` + `integration/ToolManager`.

---

## Groups (P2 + P4)

### GET /api/groups

Returns the groups array from `UserChatHistory`.

**Response 200** — array of `ChatGroup`

### POST /api/groups

Creates a new group.

**Request**:
```json
{ "groupName": "string" }
```

**Response 201** — `ChatGroup`

### PATCH /api/groups/{groupId}

Updates group properties.

**Request** (all optional):
```json
{
  "groupName": "string",
  "systemPrompt": "string",
  "isProject": true
}
```

**Response 200** — updated `ChatGroup`

### DELETE /api/groups/{groupId}

Deletes the group (calls `DataRepository.deleteGroup`). Chats in the group are NOT deleted — they become ungrouped.

**Response 204**

### POST /api/groups/{groupId}/chats/{chatId}

Adds a chat to a group.

**Response 200** — `{ "ok": true }`

### DELETE /api/groups/{groupId}/chats/{chatId}

Removes a chat from a group.

**Response 200** — `{ "ok": true }`

### POST /api/groups/{groupId}/attachments

Adds an attachment to a group (project context).

**Request** — `Attachment`:
```json
{
  "file_name": "doc.pdf",
  "mime_type": "application/pdf",
  "local_file_path": "/path/on/server"
}
```

**Response 201** — updated `ChatGroup`

### DELETE /api/groups/{groupId}/attachments/{attachmentIndex}

Removes an attachment from a group.

**Response 200** — updated `ChatGroup`

---

## API Keys (P2 + P4)

### GET /api/keys

Returns all API keys for the current user (keys are stored server-side only).

**Response 200** — array of `ApiKey`:
```json
[
  { "id": "uuid", "provider": "openai", "key": "sk-...", "isActive": true, "customName": null }
]
```

### POST /api/keys

Adds an API key.

**Request** — `ApiKey` body (id may be omitted; server generates UUID):
```json
{ "provider": "openai", "key": "sk-...", "isActive": true, "customName": null }
```

**Response 201** — `ApiKey`

### PATCH /api/keys/{keyId}

Updates a key (typically toggles `isActive` or updates the key value).

**Request** (all optional):
```json
{ "key": "sk-new", "isActive": false, "customName": "My key" }
```

**Response 200** — updated `ApiKey`

### DELETE /api/keys/{keyId}

Deletes an API key.

**Response 204**

### POST /api/keys/{keyId}/toggle

Toggles the `isActive` flag.

**Response 200** — updated `ApiKey`

### POST /api/keys/reorder

Reorders keys (drag-and-drop in UI).

**Request**:
```json
{ "fromIndex": 0, "toIndex": 2 }
```

**Response 200** — updated array of `ApiKey`

---

## Settings (P2 + P4)

### GET /api/settings

Returns `AppSettings` for the current user.

**Response 200** — `AppSettings`

### PATCH /api/settings

Updates settings fields (partial update).

**Request** (all fields optional, same shape as `AppSettings`):
```json
{
  "selected_provider": "openai",
  "selected_model": "gpt-4o",
  "temperature": 0.8,
  "multiMessageMode": false,
  "titleGenerationSettings": { "enabled": true, "provider": "auto", "updateOnExtension": true },
  "skipWelcomeScreen": true
}
```

**Response 200** — updated `AppSettings`

---

## Custom Providers — OpenAI-compatible (P4)

### GET /api/custom-providers

Returns all `CustomProviderConfig` entries for the current user.

**Response 200** — array of `CustomProviderConfig`

### POST /api/custom-providers

Creates a new custom provider.

**Request** — `CustomProviderConfig` (id may be omitted)

**Response 201** — `CustomProviderConfig`

### PUT /api/custom-providers/{providerId}

Replaces a custom provider.

**Request** — full `CustomProviderConfig`

**Response 200** — updated `CustomProviderConfig`

### DELETE /api/custom-providers/{providerId}

Deletes a custom provider.

**Response 204**

---

## Full Custom Providers (P4)

Same shape as above, replacing `CustomProviderConfig` with `FullCustomProviderConfig`.

- `GET    /api/full-custom-providers`
- `POST   /api/full-custom-providers`
- `PUT    /api/full-custom-providers/{providerId}`
- `DELETE /api/full-custom-providers/{providerId}`

---

## Skills (P4)

### GET /api/skills

Returns all installed skills.

**Response 200** — array of `InstalledSkill`

### GET /api/skills/{skillName}

Returns the raw markdown content of the skill's SKILL.md file.

**Response 200** — plain text (Content-Type: text/plain)

### POST /api/skills

Creates or imports a skill.

**Request**:
```json
{ "name": "string", "description": "string", "body": "optional markdown body" }
```

or for import-from-text:
```json
{ "importText": "full skill markdown with frontmatter" }
```

**Response 201** — `InstalledSkill`

### PATCH /api/skills/{skillName}

Updates the skill's enabled state.

**Request**:
```json
{ "enabled": true }
```

**Response 200** — updated `InstalledSkill`

### DELETE /api/skills/{skillName}

Deletes the skill.

**Response 204**

---

## Branching (P4)

### POST /api/chats/{chatId}/branch

Creates a new branch at a node.

**Request**:
```json
{ "nodeId": "string", "newUserMessage": { "role": "user", "text": "Alternative message" } }
```

**Response 201**:
```json
{ "chat": { ...Chat... }, "newVariantId": "string" }
```

### POST /api/chats/{chatId}/nodes/{nodeId}/switch

Switches to a different variant at a node.

**Request**:
```json
{ "variantIndex": 1 }
```

**Response 200** — updated `Chat`

### GET /api/chats/{chatId}/nodes/{nodeId}/branch-info

Returns `BranchInfo` for a node.

**Response 200**:
```json
{
  "nodeId": "string",
  "currentVariantIndex": 0,
  "totalVariants": 2,
  "currentVariantId": "string"
}
```

---

## Files (P5)

### POST /api/files/upload

Uploads a file as an attachment. Saves locally via `DataRepository.saveFileLocally`.

**Request** — multipart/form-data:
- `file`: binary file content
- `provider`: provider key (e.g. "openai") — if non-empty, also uploads to provider's file API
- `chatId`: optional, links the attachment to a chat

**Response 201** — `Attachment`:
```json
{
  "file_name": "document.pdf",
  "mime_type": "application/pdf",
  "local_file_path": "/home/user/.llm-api-web/files/llm_data/...",
  "file_OPENAI_id": "file-abc123",
  "file_GOOGLE_uri": null,
  "file_POE_url": null
}
```

### DELETE /api/files

Deletes a locally saved file.

**Request**:
```json
{ "filePath": "/absolute/path/on/server" }
```

**Response 204**

---

## Search (P2)

### GET /api/search?q={query}

Searches chats and messages via `DataRepository.searchChats`.

**Response 200** — array of search results:
```json
[
  {
    "chatId": "string",
    "chatTitle": "string",
    "matchType": "TITLE|CONTENT|FILE_NAME",
    "messageIndex": 3,
    "snippet": "...matching text excerpt..."
  }
]
```

---

## Title Generation (P3)

### POST /api/chats/{chatId}/generate-title

Triggers `DataRepository.generateConversationTitle`. Used after streaming completes.

**Request** (optional):
```json
{ "provider": "auto" }
```

**Response 200**:
```json
{ "title": "Generated title string" }
```

---

## Integrations — GitHub (P5)

### GET /api/integrations/github

Returns the current user's GitHub connection status.

**Response 200** — `GitHubConnection` or `null`:
```json
{
  "auth": { "accessToken": "gho_...", "scope": "repo,read:user", ... },
  "user": { "login": "alice", "id": 12345, ... },
  "connectedAt": 1700000000000
}
```

### GET /oauth/github/start

Redirects the browser to GitHub's OAuth authorization page.
Server generates a random `state` token, stores it in session.

**Response 302** → `https://github.com/login/oauth/authorize?...`

### GET /oauth/github/callback?code={code}&state={state}

Exchanges the code for an access token, fetches the user profile,
stores via `DataRepository.saveGitHubConnection`.

**Response 302** → `/` (app root, on success)
**Response 400** on state mismatch or token exchange failure.

### DELETE /api/integrations/github

Disconnects GitHub (calls `DataRepository.removeGitHubConnection`).

**Response 204**

---

## Integrations — Google Workspace (P5)

Same pattern as GitHub, using `GoogleWorkspaceConnection`.

- `GET    /api/integrations/google`         — returns connection or null
- `GET    /oauth/google/start`              — redirect to Google OAuth
- `GET    /oauth/google/callback`           — exchange code, store connection
- `DELETE /api/integrations/google`         — disconnect
- `PATCH  /api/integrations/google/services` — update `EnabledGoogleServices`

---

## Error Responses

All error responses use a consistent shape:

```json
{ "error": "Human-readable message" }
```

Standard HTTP status codes:

| Code | Meaning                  |
|------|--------------------------|
| 200  | OK                       |
| 201  | Created                  |
| 204  | No Content               |
| 400  | Bad Request              |
| 401  | Unauthorized (no session)|
| 403  | Forbidden                |
| 404  | Not Found                |
| 409  | Conflict                 |
| 500  | Internal Server Error    |

---

## Notes for implementers

- The server is **single-user**: all repository calls use `loadAppSettings().current_user` for the `username` parameter. No per-request username routing needed.
- The `DataRepository` instance is a singleton in `AppModule` (created once at startup). Thread safety for concurrent requests is handled by Ktor's coroutine dispatcher and the fact that `DataRepository` file operations are synchronous (low concurrency expected).
- SSE streaming (`POST /api/chat/send`) uses Ktor's `respondTextWriter` (Ktor 2.x) or `sse { }` (Ktor 3.x). P3 will confirm the approach after evaluating library availability. The event format above is the contract regardless of Ktor version.
- Password comparison must use constant-time equality to prevent timing attacks.
- The `ktor-server-sse` artifact is only available in Ktor 3.x. If staying on 2.3.x for P3, implement SSE manually via `respondTextWriter` with appropriate `Content-Type: text/event-stream` header and chunked encoding.
