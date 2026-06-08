import React, {
  useEffect,
  useState,
  useRef,
  useCallback,
  useMemo,
} from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useChatStore } from '../stores/chatStore'
import {
  providers as providersApi,
  branching,
  skills as skillsApi,
  files as filesApi,
  settings as settingsApi,
  chats as chatsApi,
} from '../api/client'
import { sendStream, resendStream } from '../api/stream'
import type { StreamCallbacks } from '../api/stream'
import type {
  Chat,
  Message,
  ProviderModel_Flat,
  InstalledSkill,
  Attachment,
  ThinkingBudget,
  BranchInfo,
  Provider,
  StarredModel,
} from '../api/types'
import Markdown from '../components/Markdown'
import styles from './ChatPage.module.css'
import ChatTopBar from '../components/chat/ChatTopBar'
import QuickSettingsBar from '../components/chat/QuickSettingsBar'
import type { TextDirectionMode } from '../components/chat/QuickSettingsBar'
import ModelSelectorDialog from '../components/chat/ModelSelectorDialog'
import SystemPromptDialog from '../components/chat/SystemPromptDialog'
import Dialog, { DialogButton } from '../ui/Dialog'
import { t } from '../i18n/he'

// ─── helpers ────────────────────────────────────────────────────────────────

function detectDir(text: string): 'rtl' | 'ltr' {
  // Simple heuristic: if the first strong character is RTL, use RTL
  const rtlRe = /[֑-߿יִ-﷽ﹰ-ﻼ]/
  return rtlRe.test(text.slice(0, 50)) ? 'rtl' : 'ltr'
}

function formatTime(datetime: string | null | undefined): string {
  if (!datetime) return ''
  const d = new Date(datetime)
  if (isNaN(d.getTime())) return ''
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

// ─── streaming state ─────────────────────────────────────────────────────────

interface StreamState {
  partialText: string
  thinkingText: string
  thinkingOpen: boolean
  thinkingDone: boolean
  toolCalls: Array<{ toolId: string; toolName: string; parameters: Record<string, unknown>; result?: string; success?: boolean }>
  streaming: boolean
}

const EMPTY_STREAM: StreamState = {
  partialText: '',
  thinkingText: '',
  thinkingOpen: false,
  thinkingDone: false,
  toolCalls: [],
  streaming: false,
}

// ─── BranchNavigator ─────────────────────────────────────────────────────────

interface BranchNavProps {
  chatId: string
  nodeId: string
  totalVariants: number
  currentVariantIndex: number
  onSwitch: (chat: Chat) => void
}

const BranchNav: React.FC<BranchNavProps> = ({
  chatId, nodeId, totalVariants, currentVariantIndex, onSwitch,
}) => {
  const [busy, setBusy] = useState(false)

  const go = async (dir: -1 | 1) => {
    setBusy(true)
    try {
      const idx = currentVariantIndex + dir
      const chat = await branching.switchVariant(chatId, nodeId, idx)
      onSwitch(chat)
    } catch {
      // ignore
    } finally {
      setBusy(false)
    }
  }

  if (totalVariants <= 1) return null

  return (
    <div className={styles.branchNav}>
      <button
        disabled={currentVariantIndex === 0 || busy}
        onClick={() => go(-1)}
        aria-label="Previous variant"
      >
        ‹
      </button>
      <span>{currentVariantIndex + 1}/{totalVariants}</span>
      <button
        disabled={currentVariantIndex === totalVariants - 1 || busy}
        onClick={() => go(1)}
        aria-label="Next variant"
      >
        ›
      </button>
    </div>
  )
}

// ─── Tool call block ─────────────────────────────────────────────────────────

interface ToolCallBlockProps {
  toolName: string
  parameters: Record<string, unknown>
  result?: string
  success?: boolean
}

const ToolCallBlock: React.FC<ToolCallBlockProps> = ({ toolName, parameters, result, success }) => {
  const [open, setOpen] = useState(false)
  return (
    <div className={styles.toolCall}>
      <button className={styles.toolCallHeader} onClick={() => setOpen((o) => !o)}>
        <span className={styles.toolCallIcon}>{result !== undefined ? (success ? '✓' : '✗') : '⚙'}</span>
        <span className={styles.toolCallName}>{toolName}</span>
        <span className={styles.toolCallToggle}>{open ? '▴' : '▾'}</span>
      </button>
      {open && (
        <div className={styles.toolCallBody}>
          <div className={styles.toolCallSection}>
            <div className={styles.toolCallLabel}>Parameters</div>
            <pre className={styles.toolCallPre}>{JSON.stringify(parameters, null, 2)}</pre>
          </div>
          {result !== undefined && (
            <div className={styles.toolCallSection}>
              <div className={styles.toolCallLabel}>Result</div>
              <pre className={styles.toolCallPre}>{result}</pre>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ─── Thinking block ───────────────────────────────────────────────────────────

interface ThinkingBlockProps {
  text: string
  done: boolean
}

const ThinkingBlock: React.FC<ThinkingBlockProps> = ({ text, done }) => {
  const [open, setOpen] = useState(false)
  return (
    <div className={styles.thinking}>
      <button className={styles.thinkingHeader} onClick={() => setOpen((o) => !o)}>
        <span className={styles.thinkingDot}>{done ? '●' : '◌'}</span>
        <span>{done ? 'Thought for a moment' : 'Thinking…'}</span>
        <span className={styles.thinkingToggle}>{open ? '▴' : '▾'}</span>
      </button>
      {open && <div className={styles.thinkingBody}>{text}</div>}
    </div>
  )
}

// ─── Message bubble ───────────────────────────────────────────────────────────

interface MessageBubbleProps {
  msg: Message
  chat: Chat
  onEdit: (msg: Message) => void
  onCopy: (text: string) => void
  onDelete: (msgId: string) => void
  onRegenerate: (msg: Message) => void
  onBranchSwitch: (chat: Chat) => void
}

const MessageBubble: React.FC<MessageBubbleProps> = ({
  msg, chat, onEdit, onCopy, onDelete, onRegenerate, onBranchSwitch,
}) => {
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)
  const isUser = msg.role === 'user'
  const isToolCall = msg.role === 'tool_call'
  const isToolResponse = msg.role === 'tool_response'

  // Find branch info for this message
  const branchInfo = useMemo((): BranchInfo | null => {
    if (!msg.nodeId) return null
    for (const node of chat.messageNodes ?? []) {
      if (node.nodeId === msg.nodeId && node.variants.length > 1) {
        const variantIdx = node.variants.findIndex(
          (v) => v.variantId === msg.variantId || v.userMessage.id === msg.id
        )
        return {
          nodeId: node.nodeId,
          currentVariantIndex: variantIdx >= 0 ? variantIdx : 0,
          totalVariants: node.variants.length,
          currentVariantId: msg.variantId ?? node.variants[0].variantId,
        }
      }
    }
    return null
  }, [chat, msg])

  // Close menu on outside click
  useEffect(() => {
    if (!menuOpen) return
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false)
      }
    }
    window.addEventListener('mousedown', handler)
    return () => window.removeEventListener('mousedown', handler)
  }, [menuOpen])

  if (isToolCall && msg.toolCall) {
    return (
      <div className={styles.bubbleWrap} data-role="tool_call">
        <ToolCallBlock
          toolName={msg.toolCall.toolName}
          parameters={msg.toolCall.parameters}
        />
      </div>
    )
  }

  if (isToolResponse) {
    return (
      <div className={styles.bubbleWrap} data-role="tool_response">
        <ToolCallBlock
          toolName="Result"
          parameters={{}}
          result={msg.toolResponseOutput ?? msg.text}
          success
        />
      </div>
    )
  }

  const dir = detectDir(msg.text)

  return (
    <div
      className={`${styles.bubbleWrap} ${isUser ? styles.bubbleWrapUser : styles.bubbleWrapAssistant}`}
    >
      <div className={`${styles.bubble} ${isUser ? styles.bubbleUser : styles.bubbleAssistant}`}>
        {/* Thinking block for assistant */}
        {!isUser && msg.thoughtsStatus === 'PRESENT' && msg.thoughts && (
          <ThinkingBlock text={msg.thoughts} done />
        )}

        {/* Message content */}
        <div
          className={styles.bubbleContent}
          dir={dir}
        >
          {isUser ? (
            <span className={styles.userText}>{msg.text}</span>
          ) : (
            <Markdown content={msg.text} />
          )}
        </div>

        {/* Attachments */}
        {msg.attachments && msg.attachments.length > 0 && (
          <div className={styles.attachments}>
            {msg.attachments.map((att, i) => (
              <div key={i} className={styles.attachment}>
                📎 {att.file_name}
              </div>
            ))}
          </div>
        )}

        {/* Footer: time + model + actions */}
        <div className={styles.bubbleFooter}>
          {msg.model && !isUser && (
            <span className={styles.bubbleModel}>{msg.model}</span>
          )}
          <span className={styles.bubbleTime}>{formatTime(msg.datetime)}</span>
          <div className={styles.bubbleActions} ref={menuRef}>
            <button
              className={styles.bubbleActionBtn}
              onClick={() => setMenuOpen((o) => !o)}
              aria-label="Message actions"
            >
              ⋯
            </button>
            {menuOpen && (
              <div className={styles.bubbleMenu}>
                <button onClick={() => { onCopy(msg.text); setMenuOpen(false) }}>Copy</button>
                {isUser && <button onClick={() => { onEdit(msg); setMenuOpen(false) }}>Edit</button>}
                {!isUser && <button onClick={() => { onRegenerate(msg); setMenuOpen(false) }}>Regenerate</button>}
                <button
                  className={styles.bubbleMenuDelete}
                  onClick={() => { onDelete(msg.id); setMenuOpen(false) }}
                >
                  Delete from here
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Branch navigator */}
      {branchInfo && branchInfo.totalVariants > 1 && (
        <BranchNav
          chatId={chat.chat_id}
          nodeId={branchInfo.nodeId}
          totalVariants={branchInfo.totalVariants}
          currentVariantIndex={branchInfo.currentVariantIndex}
          onSwitch={onBranchSwitch}
        />
      )}
    </div>
  )
}

// ─── Model picker ─────────────────────────────────────────────────────────────

interface ModelPickerProps {
  provider: string
  model: string
  onChange: (provider: string, model: string) => void
  models: ProviderModel_Flat[]
}

const ModelPicker: React.FC<ModelPickerProps> = ({ provider, model, onChange, models }) => {
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState('')
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    window.addEventListener('mousedown', handler)
    return () => window.removeEventListener('mousedown', handler)
  }, [open])

  const grouped = useMemo(() => {
    const map = new Map<string, string[]>()
    for (const m of models) {
      if (filter && !m.modelName.toLowerCase().includes(filter.toLowerCase()) &&
          !m.provider.toLowerCase().includes(filter.toLowerCase())) continue
      const arr = map.get(m.provider) ?? []
      arr.push(m.modelName)
      map.set(m.provider, arr)
    }
    return map
  }, [models, filter])

  const label = model ? `${provider} / ${model}` : 'Select model'

  return (
    <div className={styles.modelPicker} ref={ref}>
      <button
        className={styles.modelPickerBtn}
        onClick={() => setOpen((o) => !o)}
        title={label}
      >
        {label.length > 35 ? label.slice(0, 35) + '…' : label}
        <span>▾</span>
      </button>
      {open && (
        <div className={styles.modelPickerDropdown}>
          <input
            className={styles.modelPickerSearch}
            placeholder="Filter models…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            autoFocus
          />
          <div className={styles.modelPickerList}>
            {[...grouped.entries()].map(([prov, modelNames]) => (
              <div key={prov}>
                <div className={styles.modelPickerGroup}>{prov}</div>
                {modelNames.map((mn) => (
                  <button
                    key={mn}
                    className={`${styles.modelPickerItem} ${prov === provider && mn === model ? styles.modelPickerItemActive : ''}`}
                    onClick={() => { onChange(prov, mn); setOpen(false); setFilter('') }}
                  >
                    {mn}
                  </button>
                ))}
              </div>
            ))}
            {grouped.size === 0 && (
              <div className={styles.modelPickerEmpty}>No models found</div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Tools dropdown ───────────────────────────────────────────────────────────

interface ToolsDropdownProps {
  skills: InstalledSkill[]
  enabledToolIds: string[]
  onChange: (ids: string[]) => void
}

const BUILT_IN_TOOLS = [
  { id: 'web_search', name: 'Web Search' },
  { id: 'code_execution', name: 'Code Execution' },
  { id: 'file_read', name: 'File Read' },
]

const ToolsDropdown: React.FC<ToolsDropdownProps> = ({ skills, enabledToolIds, onChange }) => {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    window.addEventListener('mousedown', handler)
    return () => window.removeEventListener('mousedown', handler)
  }, [open])

  const toggle = (id: string) => {
    if (enabledToolIds.includes(id)) {
      onChange(enabledToolIds.filter((x) => x !== id))
    } else {
      onChange([...enabledToolIds, id])
    }
  }

  const count = enabledToolIds.length

  return (
    <div className={styles.toolsDropdown} ref={ref}>
      <button
        className={`${styles.topbarBtn} ${count > 0 ? styles.topbarBtnActive : ''}`}
        onClick={() => setOpen((o) => !o)}
        title="Tools"
      >
        🔧 {count > 0 ? count : ''}
      </button>
      {open && (
        <div className={styles.toolsMenu}>
          <div className={styles.toolsMenuSection}>Built-in</div>
          {BUILT_IN_TOOLS.map((t) => (
            <label key={t.id} className={styles.toolsMenuItem}>
              <input
                type="checkbox"
                checked={enabledToolIds.includes(t.id)}
                onChange={() => toggle(t.id)}
              />
              {t.name}
            </label>
          ))}
          {skills.length > 0 && (
            <>
              <div className={styles.toolsMenuSection}>Skills</div>
              {skills.filter((s) => s.enabled).map((s) => (
                <label key={s.name} className={styles.toolsMenuItem}>
                  <input
                    type="checkbox"
                    checked={enabledToolIds.includes(s.name)}
                    onChange={() => toggle(s.name)}
                  />
                  {s.name}
                </label>
              ))}
            </>
          )}
        </div>
      )}
    </div>
  )
}

// ─── Main ChatPage ────────────────────────────────────────────────────────────

const ChatPage: React.FC = () => {
  const { id: chatId } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { currentChat, loadChat, updateChat } = useChatStore()

  // Top-bar / controls state
  const [models, setModels] = useState<ProviderModel_Flat[]>([])
  const [provider, setProvider] = useState('openai')
  const [model, setModel] = useState('gpt-4o')
  const [webSearch, setWebSearch] = useState(false)
  const [enabledToolIds, setEnabledToolIds] = useState<string[]>([])
  const [thinkingBudget, setThinkingBudget] = useState<ThinkingBudget>('none')
  const [temperature, setTemperature] = useState<number | null>(null)
  const [skillsList, setSkillsList] = useState<InstalledSkill[]>([])

  // ── Chrome state (R2) ────────────────────────────────────────────────────────
  const [searchMode, setSearchMode] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [quickSettingsExpanded, setQuickSettingsExpanded] = useState(false)
  const [showModelSelector, setShowModelSelector] = useState(false)
  const [showSystemPromptDialog, setShowSystemPromptDialog] = useState(false)
  const [textDirectionMode, setTextDirectionMode] = useState<TextDirectionMode>('AUTO')
  const [providersList, setProvidersList] = useState<Provider[]>([])
  const [starredModels, setStarredModels] = useState<StarredModel[]>([])
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)

  // Input state
  const [inputText, setInputText] = useState('')
  const [attachments, setAttachments] = useState<Attachment[]>([])
  const [uploading, setUploading] = useState(false)

  // Edit mode
  const [editingMsg, setEditingMsg] = useState<Message | null>(null)

  // Streaming state
  const [stream, setStream] = useState<StreamState>(EMPTY_STREAM)
  const streamHandleRef = useRef<{ abort: () => void } | null>(null)

  // Error
  const [error, setError] = useState<string | null>(null)

  // Scroll
  const bottomRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // ── Load chat & models on mount ──────────────────────────────────────────

  useEffect(() => {
    if (chatId) loadChat(chatId)
  }, [chatId, loadChat])

  useEffect(() => {
    providersApi.models().then(setModels).catch(() => {})
    providersApi.list().then(setProvidersList).catch(() => {})
    skillsApi.list().then((skills) => {
      setSkillsList(skills)
      // Initialize enabled tools from skills that are enabled
      setEnabledToolIds(skills.filter(s => s.enabled).map(s => s.name))
    }).catch(() => {})
    settingsApi.get().then((s) => {
      if (s.selected_provider) setProvider(s.selected_provider)
      if (s.selected_model) setModel(s.selected_model)
      if (s.starredModels) setStarredModels(s.starredModels)
    }).catch(() => {})
  }, [])

  // Scroll to bottom when messages change or partial text grows
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [currentChat?.messages?.length, stream.partialText])

  // ── Send message ──────────────────────────────────────────────────────────

  const doSend = useCallback(
    (text: string, atts: Attachment[]) => {
      if (!chatId || !text.trim()) return
      setError(null)

      const req = {
        chatId,
        provider,
        modelName: model,
        messages: [
          ...(currentChat?.messages ?? []).map((m) => ({
            role: m.role,
            text: m.text,
            attachments: m.attachments ?? [],
          })),
          { role: 'user', text, attachments: atts },
        ],
        systemPrompt: currentChat?.systemPrompt ?? '',
        webSearchEnabled: webSearch,
        enabledToolIds,
        thinkingBudget,
        temperature,
        projectAttachments: [],
      }

      const newStream: StreamState = {
        partialText: '',
        thinkingText: '',
        thinkingOpen: false,
        thinkingDone: false,
        toolCalls: [],
        streaming: true,
      }
      setStream(newStream)

      const callbacks: StreamCallbacks = {
        onPartial: (e) =>
          setStream((s) => ({ ...s, partialText: s.partialText + e.text })),
        onThinkingStarted: () =>
          setStream((s) => ({ ...s, thinkingOpen: true })),
        onThinkingPartial: (e) =>
          setStream((s) => ({ ...s, thinkingText: s.thinkingText + e.text })),
        onThinkingComplete: () =>
          setStream((s) => ({ ...s, thinkingDone: true })),
        onToolCall: (e) =>
          setStream((s) => ({
            ...s,
            toolCalls: [...s.toolCalls, { toolId: e.toolId, toolName: e.toolName, parameters: e.parameters }],
          })),
        onToolResult: (e) =>
          setStream((s) => ({
            ...s,
            toolCalls: s.toolCalls.map((tc) =>
              tc.toolId === e.toolId
                ? { ...tc, result: e.output, success: e.success }
                : tc,
            ),
          })),
        onComplete: () => {
          setStream(EMPTY_STREAM)
          if (chatId) loadChat(chatId)
        },
        onError: (e) => {
          setStream(EMPTY_STREAM)
          setError(e.error)
        },
      }

      const handle = sendStream(req, callbacks)
      streamHandleRef.current = handle
    },
    [chatId, provider, model, currentChat, webSearch, enabledToolIds, thinkingBudget, temperature, loadChat],
  )

  const handleSubmit = useCallback(() => {
    if (!inputText.trim() || stream.streaming) return

    if (editingMsg) {
      // Edit: create a branch from that message
      handleEditSubmit(editingMsg, inputText, attachments)
      return
    }

    doSend(inputText, attachments)
    setInputText('')
    setAttachments([])
  }, [inputText, attachments, stream.streaming, editingMsg, doSend])

  // ── Edit / resend ────────────────────────────────────────────────────────

  const handleEditSubmit = useCallback(
    async (originalMsg: Message, newText: string, atts: Attachment[]) => {
      if (!chatId || !newText.trim()) return
      setEditingMsg(null)
      setError(null)

      try {
        const result = await branching.create(chatId, {
          nodeId: originalMsg.nodeId ?? originalMsg.id,
          newUserMessage: { role: 'user', text: newText },
        })
        updateChat(result.chat)
        setInputText('')
        setAttachments([])

        // Now resend from the new variant's last user message
        const newMsgId = result.newVariantId
        const newStream: StreamState = {
          partialText: '',
          thinkingText: '',
          thinkingOpen: false,
          thinkingDone: false,
          toolCalls: [],
          streaming: true,
        }
        setStream(newStream)

        const resendReq = {
          provider,
          modelName: model,
          systemPrompt: currentChat?.systemPrompt ?? '',
          webSearchEnabled: webSearch,
          enabledToolIds,
          thinkingBudget,
          temperature,
        }

        const callbacks: StreamCallbacks = {
          onPartial: (e) =>
            setStream((s) => ({ ...s, partialText: s.partialText + e.text })),
          onThinkingStarted: () =>
            setStream((s) => ({ ...s, thinkingOpen: true })),
          onThinkingPartial: (e) =>
            setStream((s) => ({ ...s, thinkingText: s.thinkingText + e.text })),
          onThinkingComplete: () =>
            setStream((s) => ({ ...s, thinkingDone: true })),
          onToolCall: (e) =>
            setStream((s) => ({
              ...s,
              toolCalls: [...s.toolCalls, { toolId: e.toolId, toolName: e.toolName, parameters: e.parameters }],
            })),
          onToolResult: (e) =>
            setStream((s) => ({
              ...s,
              toolCalls: s.toolCalls.map((tc) =>
                tc.toolId === e.toolId
                  ? { ...tc, result: e.output, success: e.success }
                  : tc,
              ),
            })),
          onComplete: () => {
            setStream(EMPTY_STREAM)
            if (chatId) loadChat(chatId)
          },
          onError: (e) => {
            setStream(EMPTY_STREAM)
            setError(e.error)
          },
        }

        // Use resendStream with the variant message id
        const handle = resendStream(chatId, newMsgId, resendReq, callbacks)
        streamHandleRef.current = handle
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Edit failed')
      }
    },
    [chatId, provider, model, currentChat, webSearch, enabledToolIds, thinkingBudget, temperature, updateChat, loadChat],
  )

  // ── Regenerate ────────────────────────────────────────────────────────────

  const handleRegenerate = useCallback(
    (msg: Message) => {
      if (!chatId || stream.streaming) return
      setError(null)

      const newStream: StreamState = {
        partialText: '',
        thinkingText: '',
        thinkingOpen: false,
        thinkingDone: false,
        toolCalls: [],
        streaming: true,
      }
      setStream(newStream)

      const resendReq = {
        provider,
        modelName: model,
        systemPrompt: currentChat?.systemPrompt ?? '',
        webSearchEnabled: webSearch,
        enabledToolIds,
        thinkingBudget,
        temperature,
      }

      const callbacks: StreamCallbacks = {
        onPartial: (e) =>
          setStream((s) => ({ ...s, partialText: s.partialText + e.text })),
        onThinkingStarted: () =>
          setStream((s) => ({ ...s, thinkingOpen: true })),
        onThinkingPartial: (e) =>
          setStream((s) => ({ ...s, thinkingText: s.thinkingText + e.text })),
        onThinkingComplete: () =>
          setStream((s) => ({ ...s, thinkingDone: true })),
        onComplete: () => {
          setStream(EMPTY_STREAM)
          if (chatId) loadChat(chatId)
        },
        onError: (e) => {
          setStream(EMPTY_STREAM)
          setError(e.error)
        },
      }

      const handle = resendStream(chatId, msg.id, resendReq, callbacks)
      streamHandleRef.current = handle
    },
    [chatId, provider, model, currentChat, webSearch, enabledToolIds, thinkingBudget, temperature, stream.streaming, loadChat],
  )

  // ── Delete message ────────────────────────────────────────────────────────

  const handleDeleteMessage = useCallback(
    async (msgId: string) => {
      if (!chatId) return
      if (!window.confirm('Delete this message and everything after it?')) return
      try {
        const { messages: messagesApi } = await import('../api/client')
        const updatedChat = await messagesApi.delete(chatId, msgId)
        updateChat(updatedChat)
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Delete failed')
      }
    },
    [chatId, updateChat],
  )

  // ── File upload ───────────────────────────────────────────────────────────

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !chatId) return
    setUploading(true)
    try {
      const att = await filesApi.upload(file, provider, chatId)
      setAttachments((prev) => [...prev, att])
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setUploading(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  // ── Copy ──────────────────────────────────────────────────────────────────

  const handleCopy = useCallback((text: string) => {
    navigator.clipboard.writeText(text).catch(() => {})
  }, [])

  // ── Chrome handlers (R2) ────────────────────────────────────────────────────

  const handleDeleteChat = useCallback(async () => {
    if (!chatId) return
    try {
      await chatsApi.delete(chatId)
      navigate('/')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Delete failed')
    }
    setShowDeleteConfirm(false)
  }, [chatId, navigate])

  const handleSaveSystemPrompt = useCallback(async (prompt: string) => {
    if (!chatId) return
    try {
      const updated = await chatsApi.update(chatId, { systemPrompt: prompt })
      updateChat(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save system prompt')
    }
  }, [chatId, updateChat])

  const handleModelSelect = useCallback((newProvider: string, newModel: string) => {
    setProvider(newProvider)
    setModel(newModel)
  }, [])

  const handleToggleStar = useCallback((prov: string, modelName: string) => {
    setStarredModels(prev => {
      const already = prev.some(s => s.provider === prov && s.modelName === modelName)
      return already
        ? prev.filter(s => !(s.provider === prov && s.modelName === modelName))
        : [...prev, { provider: prov, modelName }]
    })
  }, [])

  // ── Abort stream ──────────────────────────────────────────────────────────

  const handleAbort = () => {
    streamHandleRef.current?.abort()
    setStream(EMPTY_STREAM)
  }

  // ── Keyboard ──────────────────────────────────────────────────────────────

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  // ── Tool items (useMemo must be before any conditional return) ───────────────

  const toolItems = useMemo(() => {
    return skillsList
      .filter(s => s.enabled)
      .map(s => ({ id: s.name, name: s.name }))
  }, [skillsList])

  // ── Render ────────────────────────────────────────────────────────────────

  if (!currentChat && !stream.streaming) {
    return (
      <div className={styles.loading}>
        {chatId ? 'Loading chat…' : 'Chat not found'}
      </div>
    )
  }

  const chat = currentChat
  const messages = chat?.messages ?? []

  return (
    <div className={styles.page}>
      {/* ── Chrome (R2): top bar + quick settings ── */}
      <ChatTopBar
        provider={provider}
        model={model}
        searchMode={searchMode}
        searchQuery={searchQuery}
        quickSettingsExpanded={quickSettingsExpanded}
        onBack={() => navigate('/')}
        onSearch={() => setSearchMode(true)}
        onShare={() => {
          // Export chat scaffold (R3 can wire download)
          if (chatId) chatsApi.export(chatId).catch(() => {})
        }}
        onDelete={() => setShowDeleteConfirm(true)}
        onClickProviderModel={() => setShowModelSelector(true)}
        onSearchQueryChange={setSearchQuery}
        onSearchAction={() => { /* in-chat search highlight is R3 */ }}
        onExitSearch={() => { setSearchMode(false); setSearchQuery('') }}
        onToggleQuickSettings={() => setQuickSettingsExpanded(v => !v)}
      />

      <QuickSettingsBar
        visible={!searchMode}
        expanded={quickSettingsExpanded}
        thinkingBudget={thinkingBudget}
        onThinkingBudgetChange={setThinkingBudget}
        temperature={temperature}
        onTemperatureChange={setTemperature}
        toolItems={toolItems}
        enabledToolIds={enabledToolIds}
        onToolToggle={(id, enabled) => {
          setEnabledToolIds(prev => enabled
            ? [...prev.filter(x => x !== id), id]
            : prev.filter(x => x !== id)
          )
        }}
        textDirectionMode={textDirectionMode}
        onTextDirectionChange={setTextDirectionMode}
        onSystemPrompt={() => setShowSystemPromptDialog(true)}
        systemPrompt={chat?.systemPrompt ?? ''}
      />

      {/* Model selector dialog */}
      <ModelSelectorDialog
        open={showModelSelector}
        onClose={() => setShowModelSelector(false)}
        providers={providersList}
        currentProvider={provider}
        currentModel={model}
        onSelect={handleModelSelect}
        starredModels={starredModels}
        onToggleStar={handleToggleStar}
      />

      {/* System prompt dialog */}
      <SystemPromptDialog
        open={showSystemPromptDialog}
        onClose={() => setShowSystemPromptDialog(false)}
        currentPrompt={chat?.systemPrompt ?? ''}
        onSave={handleSaveSystemPrompt}
      />

      {/* Delete confirmation dialog */}
      <Dialog
        open={showDeleteConfirm}
        onClose={() => setShowDeleteConfirm(false)}
        title={t('delete_confirmation_title')}
        actions={
          <>
            <DialogButton label={t('cancel')} onClick={() => setShowDeleteConfirm(false)} />
            <DialogButton label={t('delete')} onClick={handleDeleteChat} danger />
          </>
        }
      >
        {t('delete_confirmation_message')}
      </Dialog>

      {/* Error banner */}
      {error && (
        <div className={styles.error} role="alert">
          {error}
          <button onClick={() => setError(null)}>✕</button>
        </div>
      )}

      {/* Messages */}
      <div className={styles.messages}>
        {messages.length === 0 && !stream.streaming && (
          <div className={styles.emptyChat}>
            <div className={styles.emptyChatIcon}>💬</div>
            <p>Start a conversation</p>
          </div>
        )}

        {messages.map((msg) => (
          <MessageBubble
            key={msg.id}
            msg={msg}
            chat={chat!}
            onEdit={(m) => { setEditingMsg(m); setInputText(m.text) }}
            onCopy={handleCopy}
            onDelete={handleDeleteMessage}
            onRegenerate={handleRegenerate}
            onBranchSwitch={updateChat}
          />
        ))}

        {/* Streaming partial */}
        {stream.streaming && (
          <div className={`${styles.bubbleWrap} ${styles.bubbleWrapAssistant}`}>
            <div className={`${styles.bubble} ${styles.bubbleAssistant}`}>
              {stream.thinkingOpen && (
                <ThinkingBlock text={stream.thinkingText} done={stream.thinkingDone} />
              )}
              {stream.toolCalls.map((tc, i) => (
                <ToolCallBlock
                  key={i}
                  toolName={tc.toolName}
                  parameters={tc.parameters}
                  result={tc.result}
                  success={tc.success}
                />
              ))}
              {stream.partialText ? (
                <div className={styles.bubbleContent}>
                  <Markdown content={stream.partialText} />
                </div>
              ) : (
                <div className={styles.streamingDots}>
                  <span /><span /><span />
                </div>
              )}
            </div>
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      {/* Input area */}
      <div className={styles.inputArea}>
        {editingMsg && (
          <div className={styles.editBanner}>
            Editing message
            <button onClick={() => { setEditingMsg(null); setInputText('') }}>✕</button>
          </div>
        )}

        {attachments.length > 0 && (
          <div className={styles.attachmentsList}>
            {attachments.map((att, i) => (
              <div key={i} className={styles.attachmentChip}>
                📎 {att.file_name}
                <button onClick={() => setAttachments((prev) => prev.filter((_, j) => j !== i))}>
                  ✕
                </button>
              </div>
            ))}
          </div>
        )}

        <div className={styles.inputRow}>
          <input
            ref={fileInputRef}
            type="file"
            className={styles.fileInput}
            onChange={handleFileSelect}
            aria-label="Attach file"
          />
          <button
            className={styles.attachBtn}
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
            title="Attach file"
          >
            {uploading ? '⏳' : '📎'}
          </button>

          <textarea
            className={styles.textarea}
            placeholder={editingMsg ? 'Edit your message…' : 'Type a message… (Enter to send, Shift+Enter for newline)'}
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            onKeyDown={handleKeyDown}
            rows={1}
            style={{ height: 'auto' }}
            ref={(el) => {
              if (el) {
                el.style.height = 'auto'
                el.style.height = Math.min(el.scrollHeight, 200) + 'px'
              }
            }}
          />

          {stream.streaming ? (
            <button
              className={`${styles.sendBtn} ${styles.stopBtn}`}
              onClick={handleAbort}
              aria-label="Stop generation"
            >
              ⏹
            </button>
          ) : (
            <button
              className={styles.sendBtn}
              onClick={handleSubmit}
              disabled={!inputText.trim() || uploading}
              aria-label="Send message"
            >
              ➤
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

export default ChatPage
