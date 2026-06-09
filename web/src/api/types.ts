// =============================================================================
// Shared model interfaces — mirror exact JSON field names from the Kotlin DTOs.
// snake_case where the backend serializes snake_case (matching @SerialName).
// =============================================================================

// ---------------------------------------------------------------------------
// Chat History
// ---------------------------------------------------------------------------

export interface Attachment {
  file_name: string
  mime_type: string
  local_file_path?: string | null
  file_OPENAI_id?: string | null
  file_POE_url?: string | null
  file_GOOGLE_uri?: string | null
}

export type ThoughtsStatus = 'NONE' | 'PRESENT' | 'UNAVAILABLE'

export interface ToolCallInfo {
  toolId: string
  toolName: string
  parameters: Record<string, unknown>
}

export interface Message {
  id: string
  role: string          // "user" | "assistant" | "system" | "tool_call" | "tool_response"
  text: string
  attachments: Attachment[]
  model?: string | null
  datetime?: string | null
  toolCall?: ToolCallInfo | null
  toolCallId?: string | null
  toolResponseCallId?: string | null
  toolResponseOutput?: string | null
  nodeId?: string | null
  variantId?: string | null
  thoughts?: string | null
  thinkingDurationSeconds?: number | null
  thoughtsStatus: ThoughtsStatus
  thoughtsSignature?: string | null
}

export interface MessageVariant {
  variantId: string
  userMessage: Message
  responses: Message[]
  childNodeId?: string | null
}

export interface MessageNode {
  nodeId: string
  parentNodeId?: string | null
  variants: MessageVariant[]
}

export interface Chat {
  chat_id: string
  preview_name: string
  messages: Message[]
  systemPrompt: string
  group?: string | null
  messageNodes: MessageNode[]
  currentVariantPath: string[]
  shareLink: string
  shareId: string
}

export interface ChatGroup {
  group_id: string
  group_name: string
  system_prompt?: string | null
  group_attachments: Attachment[]
  is_project: boolean
}

export interface UserChatHistory {
  user_name: string
  chat_history: Chat[]
  groups: ChatGroup[]
}

// ---------------------------------------------------------------------------
// API Keys & Settings
// ---------------------------------------------------------------------------

export interface ApiKey {
  id: string
  provider: string
  key: string           // masked in reads (last 4 chars visible)
  isActive: boolean
  customName?: string | null
}

export interface TitleGenerationSettings {
  enabled: boolean
  provider: string      // "auto" | "openai" | "poe" | "google"
  updateOnExtension: boolean
}

export interface ChildLockSettings {
  enabled: boolean
  encryptedPassword: string
  startTime: string
  endTime: string
}

export interface RemoteSyncSettings {
  enabled: boolean
  serverBaseUrl: string
  authToken: string
  syncApiKeys: boolean
}

export interface StarredModel {
  provider: string
  modelName: string
}

export interface AppSettings {
  current_user: string
  selected_provider: string
  selected_model: string
  temperature: number
  titleGenerationSettings: TitleGenerationSettings
  multiMessageMode: boolean
  childLockSettings: ChildLockSettings
  enabledTools: string[]
  excludedToolIds: string[]
  skipWelcomeScreen: boolean
  starredModels: StarredModel[]
  remoteSync: RemoteSyncSettings
}

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

export interface ModelPricing {
  points?: number | null
  min_points?: number | null
  input_points_per_1k?: number | null
  output_points_per_1k?: number | null
  input_price_per_1k?: number | null
  output_price_per_1k?: number | null
}

export interface ProviderModel {
  name?: string | null
  min_points?: number | null
  pricing?: ModelPricing | null
  other_fields?: Record<string, unknown> | null
}

export interface ApiRequest {
  request_type: string
  base_url: string
  headers: Record<string, string>
  body?: Record<string, unknown> | null
  params?: Record<string, string> | null
}

export interface Provider {
  provider: string
  models: ProviderModel[]
  request: ApiRequest
  response_important_fields: Record<string, unknown>
  upload_files_request?: unknown | null
  upload_files_response_important_fields?: unknown | null
}

export interface ProviderModel_Flat {
  provider: string
  modelName: string
}

// ---------------------------------------------------------------------------
// Skills
// ---------------------------------------------------------------------------

export interface InstalledSkill {
  name: string
  description: string
  enabled: boolean
  body?: string | null
}

// ---------------------------------------------------------------------------
// Custom Providers
// ---------------------------------------------------------------------------

export interface CustomProviderConfig {
  id?: string
  name: string
  baseUrl: string
  apiKey?: string | null
  modelNames: string[]
}

export interface FullCustomProviderConfig {
  id?: string
  name: string
  baseUrl: string
  apiKey?: string | null
  modelNames: string[]
  requestTemplate?: Record<string, unknown> | null
  responseMapping?: Record<string, unknown> | null
}

// ---------------------------------------------------------------------------
// Branching
// ---------------------------------------------------------------------------

export interface BranchInfo {
  nodeId: string
  currentVariantIndex: number
  totalVariants: number
  currentVariantId: string
}

export interface BranchResult {
  chat: Chat
  newVariantId: string
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

export type SearchMatchType = 'TITLE' | 'CONTENT' | 'FILE_NAME'

export interface SearchResult {
  chatId: string
  chatTitle: string
  matchType: SearchMatchType
  messageIndex: number
  snippet: string
}

// ---------------------------------------------------------------------------
// Integrations
// ---------------------------------------------------------------------------

export interface GitHubAuth {
  accessToken: string
  scope: string
}

export interface GitHubUser {
  login: string
  id: number
}

export interface GitHubConnection {
  auth: GitHubAuth
  user: GitHubUser
  connectedAt: number
}

export interface EnabledGoogleServices {
  gmail: boolean
  drive: boolean
  calendar: boolean
}

export interface GoogleWorkspaceConnection {
  auth: Record<string, unknown>
  user: Record<string, unknown>
  connectedAt: number
  enabledServices?: EnabledGoogleServices | null
}

// ---------------------------------------------------------------------------
// Streaming (SSE event payloads)
// ---------------------------------------------------------------------------

export interface SsePartialEvent {
  text: string
}

export interface SseThinkingStartedEvent {
  // empty object
}

export interface SseThinkingPartialEvent {
  text: string
}

export interface SseThinkingCompleteEvent {
  thoughts: string | null
  durationSeconds: number
  status: ThoughtsStatus
}

export interface SseToolCallEvent {
  toolId: string
  toolName: string
  parameters: Record<string, unknown>
}

export interface SseToolResultEvent {
  toolId: string
  success: boolean
  output: string
}

export interface SseCompleteEvent {
  text: string
  messageId: string
}

export interface SseErrorEvent {
  error: string
}

// ---------------------------------------------------------------------------
// Send request shape
// ---------------------------------------------------------------------------

export type ThinkingBudget = 'none' | 'low' | 'medium' | 'high' | 'max'

export interface SendMessageRequest {
  chatId: string
  provider: string
  modelName: string
  messages: Pick<Message, 'role' | 'text' | 'attachments'>[]
  systemPrompt: string
  webSearchEnabled: boolean
  enabledToolIds: string[]
  thinkingBudget: ThinkingBudget
  temperature: number | null
  projectAttachments: Attachment[]
}

export interface ResendMessageRequest {
  provider: string
  modelName: string
  systemPrompt: string
  webSearchEnabled: boolean
  enabledToolIds: string[]
  thinkingBudget: ThinkingBudget
  temperature: number | null
}

// ---------------------------------------------------------------------------
// Simple response wrappers
// ---------------------------------------------------------------------------

export interface OkResponse {
  ok: true
}

export interface SessionResponse {
  authenticated: boolean
}

export interface HealthResponse {
  status: 'ok'
}

export interface TitleResponse {
  title: string
}

export interface ImportChatResponse {
  chatId: string
}

export interface KeyReorderRequest {
  fromIndex: number
  toIndex: number
}
