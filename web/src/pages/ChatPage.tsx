/**
 * ChatPage — wires all R3 components together.
 *
 * Keeps all R2 chrome (ChatTopBar, QuickSettingsBar, dialogs).
 * Adds: message list with MessageBubble, streaming assistant bubble
 * with ThoughtsBubble + ToolCallBlock, ChatInputArea, floating scroll buttons.
 */

import React, {
  useEffect,
  useState,
  useRef,
  useCallback,
  useMemo,
} from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useChatStore, CHAT_NOT_FOUND } from '../stores/chatStore'
import { apiErrorMessage } from '../utils/apiErrors'
import {
  providers as providersApi,
  branching,
  settings as settingsApi,
  chats as chatsApi,
  apiKeys as apiKeysApi,
  messages as messagesApi,
} from '../api/client'
import {
  getToolName,
  getIntegrationTitle,
  GROUP_CONVERSATIONS_TOOL_ID,
} from '../utils/toolCatalog'
import { sendStream, resendStream } from '../api/stream'
import type { StreamCallbacks } from '../api/stream'
import { cancelRun } from '../api/runs'
import { useRunsStore, selectForeignRun } from '../stores/runsStore'
import { useDraftAttachments } from '../hooks/useDraftAttachments'
import { clearForwardedFile, peekForwardedFile } from '../utils/forwardedFile'
import { messageDisplayText } from '../utils/attachments'
import {
  EMPTY_STREAM,
  dropSettled,
  isEmptySegment,
  nextSegmentId,
  reduceStream,
  settleSegment,
  streamSegments,
} from '../utils/streamState'
import type { StreamState } from '../utils/streamState'
import type {
  Message,
  ProviderModel_Flat,
  Attachment,
  ThinkingBudget,
  ProviderDetail,
  StarredModel,
} from '../api/types'
import styles from './ChatPage.module.css'

// ── R2 chrome ─────────────────────────────────────────────────────────────────
import ChatTopBar from '../components/chat/ChatTopBar'
import QuickSettingsBar from '../components/chat/QuickSettingsBar'
import type { TextDirectionMode } from '../components/chat/QuickSettingsBar'
import ModelSelectorDialog from '../components/chat/ModelSelectorDialog'
import SystemPromptDialog from '../components/chat/SystemPromptDialog'
import ChatExportDialog from '../components/chat/ChatExportDialog'
import Dialog, { DialogButton } from '../ui/Dialog'
import { t } from '../i18n/he'

// ── R3 body components ────────────────────────────────────────────────────────
import MessageBubble from '../components/chat/MessageBubble'
import ThoughtsBubble from '../components/chat/ThoughtsBubble'
import ToolCallBlock from '../components/chat/ToolCallBlock'
import ChatInputArea from '../components/chat/ChatInputArea'
import { MdArrowUpward, MdArrowDownward } from '../ui/icons'
import { messageDayKey, formatDayLabel } from '../utils/chatDates'

// ─── Streaming state ──────────────────────────────────────────────────────────

/** The tab's own request: the per-request stream it is reading, and the run executing it. */
interface OwnStream {
  chatId: string
  handle: { abort: () => void } | null
  /** Known from the stream's first event */
  runId: string | null
  /** Stop was pressed before the run id arrived: cancel as soon as it does */
  stopRequested: boolean
}

// ─── Main ChatPage ────────────────────────────────────────────────────────────

const ChatPage: React.FC = () => {
  const { id: chatId } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { currentChat, loadChat, updateChat, setStreaming, error: storeError } = useChatStore()

  // ── Controls state (unchanged from R2) ────────────────────────────────────
  const [models, setModels] = useState<ProviderModel_Flat[]>([])
  const [provider, setProvider] = useState('openai')
  const [model, setModel] = useState('gpt-4o')
  const [webSearch, setWebSearch] = useState(false)
  // Tools enabled in the Integrations screen (AppSettings.enabledTools) and the
  // per-shortcut exclusions (AppSettings.excludedToolIds) — mirrors ChatViewModel.
  const [settingsEnabledTools, setSettingsEnabledTools] = useState<string[]>([])
  const [excludedToolIds, setExcludedToolIds] = useState<string[]>([])
  const [thinkingBudget, setThinkingBudget] = useState<ThinkingBudget>('none')
  const [temperature, setTemperature] = useState<number | null>(null)

  // ── Chrome state (R2) ────────────────────────────────────────────────────
  const [searchMode, setSearchMode] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [quickSettingsExpanded, setQuickSettingsExpanded] = useState(false)
  const [showModelSelector, setShowModelSelector] = useState(false)
  const [showSystemPromptDialog, setShowSystemPromptDialog] = useState(false)
  const [textDirectionMode, setTextDirectionMode] = useState<TextDirectionMode>('AUTO')
  const [providersDetailed, setProvidersDetailed] = useState<ProviderDetail[]>([])
  const [activeKeyProviders, setActiveKeyProviders] = useState<string[]>([])
  const [starredModels, setStarredModels] = useState<StarredModel[]>([])
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const [showExportDialog, setShowExportDialog] = useState(false)

  // ── Multi-message mode (mirrors MessageSendingManager.kt) ────────────────
  const [multiMessageMode, setMultiMessageMode] = useState(false)
  const [showReplyButton, setShowReplyButton] = useState(false)

  // ── Input state ──────────────────────────────────────────────────────────
  const [inputText, setInputText] = useState('')
  // Un-sent attachments: uploaded as soon as they are attached, deleted from the store again
  // when removed — or when the chat is left with them un-sent
  const draft = useDraftAttachments(chatId)
  const draftBlocked = draft.uploading || draft.hasFailed

  // An image forwarded to this chat from the image viewer: attached to the draft, ready to send.
  // Forgotten only after the effect has settled (StrictMode runs it twice; the first draft is
  // discarded with the first mount).
  const addToDraft = draft.add
  useEffect(() => {
    if (!chatId) return
    const forwarded = peekForwardedFile(chatId)
    if (!forwarded) return
    const rejected = addToDraft([forwarded])
    if (rejected.length > 0) setError(rejected.join(' · '))
    const timer = setTimeout(() => clearForwardedFile(forwarded), 0)
    return () => clearTimeout(timer)
  }, [chatId, addToDraft])
  const [editingMsg, setEditingMsg] = useState<Message | null>(null)

  // ── Streaming state ──────────────────────────────────────────────────────
  // The tab's own request (per-request stream) …
  const [stream, setStream] = useState<StreamState>(EMPTY_STREAM)
  const ownStreamRef = useRef<OwnStream | null>(null)
  // … or a run of this chat the tab is not reading itself (another device, this tab before a
  // reload, a per-request stream that broke): followed through the account-level stream
  const foreignRun = useRunsStore(selectForeignRun(chatId))
  const foreignError = useRunsStore((s) => (chatId ? s.errors[chatId] : undefined))
  /** The reply streaming into this chat, whoever reads it. */
  const live: StreamState | null = stream.streaming ? stream : foreignRun
  const busy = live !== null

  // ── UI state ─────────────────────────────────────────────────────────────
  const [error, setError] = useState<string | null>(null)
  const [showScrollDown, setShowScrollDown] = useState(false)
  const [showScrollUp, setShowScrollUp] = useState(false)
  // Floating date chip shown while scrolling (WhatsApp-style); hides shortly after scrolling stops
  const [floatingDay, setFloatingDay] = useState<string | null>(null)
  const [showFloatingDay, setShowFloatingDay] = useState(false)
  const floatingDayTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // ── Refs ─────────────────────────────────────────────────────────────────
  const messagesRef = useRef<HTMLDivElement>(null)
  const bottomRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // ── Load chat & data ─────────────────────────────────────────────────────

  useEffect(() => {
    if (chatId) loadChat(chatId)
  }, [chatId, loadChat])

  useEffect(() => {
    providersApi.models().then(setModels).catch(() => {})
    providersApi.detailed().then(setProvidersDetailed).catch(() => {})
    // Android shows only providers that have an ACTIVE API key
    apiKeysApi.list().then((keys) => {
      setActiveKeyProviders([...new Set(keys.filter((k) => k.isActive).map((k) => k.provider))])
    }).catch(() => {})
    settingsApi.get().then((s) => {
      if (s.selected_provider) setProvider(s.selected_provider)
      if (s.selected_model) setModel(s.selected_model)
      if (s.starredModels) setStarredModels(s.starredModels)
      setMultiMessageMode(!!s.multiMessageMode)
      setSettingsEnabledTools(s.enabledTools ?? [])
      setExcludedToolIds(s.excludedToolIds ?? [])
    }).catch(() => {})
  }, [])

  const availableProviders = useMemo(
    () => providersDetailed.filter((p) => activeKeyProviders.includes(p.provider)),
    [providersDetailed, activeKeyProviders],
  )

  // Tell the store the tab's own reply is streaming: live sync refreshes wait until it ends.
  // (Not for a foreign run: its question arrives through such a refresh.)
  useEffect(() => {
    setStreaming(stream.streaming)
  }, [stream.streaming, setStreaming])
  useEffect(() => () => setStreaming(false), [setStreaming])

  // Leaving the chat (or the page) while the tab's own reply streams: stop reading it — the
  // run goes on at the server and is followed through the account-level stream from here on
  useEffect(() => () => {
    const own = ownStreamRef.current
    if (!own) return
    ownStreamRef.current = null
    own.handle?.abort()
    if (own.runId) useRunsStore.getState().release(own.runId, own.chatId)
    setStream(EMPTY_STREAM)
  }, [chatId])

  // A foreign run of this chat failed
  useEffect(() => {
    if (!chatId || !foreignError) return
    setError(foreignError)
    useRunsStore.getState().clearError(chatId)
  }, [chatId, foreignError])

  // ── Autoscroll while streaming ───────────────────────────────────────────

  useEffect(() => {
    if (busy) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [live?.partialText, busy])

  useEffect(() => {
    if (!busy) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [currentChat?.messages?.length]) // eslint-disable-line react-hooks/exhaustive-deps

  // ── Scroll button visibility ─────────────────────────────────────────────

  const handleScroll = useCallback(() => {
    const el = messagesRef.current
    if (!el) return
    const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
    const distFromTop = el.scrollTop
    setShowScrollDown(distFromBottom > 150)
    setShowScrollUp(distFromTop > 150)

    // Day of the top-most visible message
    const top = el.getBoundingClientRect().top
    let day: string | null = null
    for (const node of el.querySelectorAll<HTMLElement>('[data-day]')) {
      if (node.getBoundingClientRect().bottom > top) { day = node.dataset.day ?? null; break }
    }
    if (day) {
      setFloatingDay(day)
      setShowFloatingDay(true)
      if (floatingDayTimerRef.current) clearTimeout(floatingDayTimerRef.current)
      floatingDayTimerRef.current = setTimeout(() => setShowFloatingDay(false), 1500)
    }
  }, [])

  useEffect(() => () => {
    if (floatingDayTimerRef.current) clearTimeout(floatingDayTimerRef.current)
  }, [])

  useEffect(() => {
    const el = messagesRef.current
    if (!el) return
    el.addEventListener('scroll', handleScroll, { passive: true })
    return () => el.removeEventListener('scroll', handleScroll)
  }, [handleScroll])

  // ── Tools (for QuickSettingsBar dropdown + stream requests) ──────────────
  // Mirrors ChatViewModel.getEnabledToolIds(): integrations-enabled tools, plus
  // the group-conversations tool when the chat belongs to a group.
  const availableToolIds = useMemo(() => {
    const ids = [...settingsEnabledTools]
    if (currentChat?.group && !ids.includes(GROUP_CONVERSATIONS_TOOL_ID)) {
      ids.push(GROUP_CONVERSATIONS_TOOL_ID)
    }
    return ids
  }, [settingsEnabledTools, currentChat?.group])

  const toolItems = useMemo(
    () =>
      availableToolIds.map((id) => ({
        id,
        name: getToolName(id),
        integrationTitle: getIntegrationTitle(id),
      })),
    [availableToolIds],
  )

  // Final tool list sent with stream requests = available − excluded (ToolManager.kt)
  const enabledToolIds = useMemo(
    () => availableToolIds.filter((id) => !excludedToolIds.includes(id)),
    [availableToolIds, excludedToolIds],
  )

  // Toggling persists exclusions in AppSettings (ChatViewModel.toggleToolExclusion)
  const handleToolToggle = useCallback((toolId: string, enabled: boolean) => {
    setExcludedToolIds((prev) => {
      const next = enabled ? prev.filter((x) => x !== toolId) : [...prev.filter((x) => x !== toolId), toolId]
      // A delta, not the whole list (another device may have changed it since it was loaded)
      settingsApi.update(enabled ? { excludedToolIdsRemove: [toolId] } : { excludedToolIdsAdd: [toolId] }).catch(() => {})
      return next
    })
  }, [])

  // ── Streams and runs ─────────────────────────────────────────────────────

  /**
   * Stop a run, keeping what streamed so far as the reply (like the Android stop button).
   * The run's `cancelled` event — not this call — ends the streaming state.
   */
  const requestCancel = useCallback((runId: string) => {
    cancelRun(runId, true).catch((err) => {
      // 404: the server no longer knows the run, i.e. it is over; its end reaches the tab anyway
      if ((err as { status?: unknown } | null)?.status === 404) return
      setError(apiErrorMessage(err, 'Failed to stop the reply'))
    })
  }, [])

  /**
   * Start one of the tab's own requests and follow its per-request stream.
   * [start] opens the stream (send / resend) with the callbacks built here.
   */
  const beginStream = useCallback(
    (start: (cbs: StreamCallbacks) => { abort: () => void }) => {
      if (!chatId) return
      const forChat = chatId
      const own: OwnStream = { chatId: forChat, handle: null, runId: null, stopRequested: false }
      ownStreamRef.current = own
      setStream({ ...EMPTY_STREAM, streaming: true })

      const active = () => ownStreamRef.current === own
      const content = (type: string) => (e: object) => {
        if (active()) setStream((s) => reduceStream(s, type, e as Record<string, unknown>))
      }
      /** The run's terminal event arrived. Returns whether this stream was still shown. */
      const end = (): boolean => {
        if (own.runId) useRunsStore.getState().finishOwn(own.runId)
        if (!active()) return false
        ownStreamRef.current = null
        setStream(EMPTY_STREAM)
        return true
      }

      const cbs: StreamCallbacks = {
        onRun: (e) => {
          own.runId = e.runId
          if (!active()) return
          // From here on the account-level stream's copy of this run is ignored
          useRunsStore.getState().claim(e.runId)
          if (own.stopRequested) requestCancel(e.runId)
        },
        onPartial: content('partial'),
        onThinkingStarted: content('thinking_started'),
        onThinkingPartial: content('thinking_partial'),
        onThinkingComplete: content('thinking_complete'),
        onToolCall: content('tool_call'),
        onToolResult: content('tool_result'),
        onMessagesAdded: () => {
          if (!active()) return
          // What streamed so far is saved: keep showing it until the reload brings it as
          // messages, then drop it (the stream goes on with a new segment)
          const segment = nextSegmentId()
          setStream((s) => settleSegment(s, segment))
          void Promise.resolve(loadChat(forChat)).finally(() => {
            setStream((s) => dropSettled(s, segment))
          })
        },
        onComplete: () => { if (end()) loadChat(forChat) },
        // Stopped: what the server kept (the partial reply, tool messages) is in the chat
        onCancelled: () => { if (end()) loadChat(forChat) },
        onError: (e) => { if (end()) setError(e.error) },
        onStreamLost: (e) => {
          // The connection broke, not the run: follow it through the account-level stream
          if (!active()) return
          ownStreamRef.current = null
          setStream(EMPTY_STREAM)
          useRunsStore.getState().release(e.runId, forChat)
        },
      }

      own.handle = start(cbs)
    },
    [chatId, loadChat, requestCancel],
  )

  // ── Send message ─────────────────────────────────────────────────────────

  const doSend = useCallback(
    (text: string, atts: Attachment[]) => {
      if (!chatId || (!text.trim() && atts.length === 0)) return
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

      beginStream((cbs) => sendStream(req, cbs))
    },
    [chatId, provider, model, currentChat, webSearch, enabledToolIds, thinkingBudget, temperature, beginStream],
  )

  // Multi-message mode: persist the user message WITHOUT calling the LLM;
  // the API call happens when the user presses the "השב" (reply) bubble.
  const bufferMessage = useCallback(
    async (text: string, atts: Attachment[]) => {
      if (!chatId) return
      setError(null)
      try {
        await messagesApi.add(chatId, { role: 'user', text, attachments: atts })
        await loadChat(chatId)
        setShowReplyButton(true)
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to add message')
      }
    },
    [chatId, loadChat],
  )

  // Mirrors MessageSendingManager.sendBufferedBatch(): stream a reply for the
  // already-persisted conversation without re-persisting any user message.
  const sendBufferedBatch = useCallback(() => {
    if (!chatId || busy) return
    setError(null)
    setShowReplyButton(false)

    const req = {
      chatId,
      provider,
      modelName: model,
      messages: (currentChat?.messages ?? []).map((m) => ({
        role: m.role,
        text: m.text,
        attachments: m.attachments ?? [],
      })),
      systemPrompt: currentChat?.systemPrompt ?? '',
      webSearchEnabled: webSearch,
      enabledToolIds,
      thinkingBudget,
      temperature,
      projectAttachments: [],
      persistUserMessage: false,
    }

    beginStream((cbs) => sendStream(req, cbs))
  }, [chatId, provider, model, currentChat, webSearch, enabledToolIds, thinkingBudget, temperature, busy, beginStream])

  const handleSubmit = useCallback(() => {
    // Files still uploading (or failed): the message waits — it must not go out without them
    if (busy || draftBlocked) return

    if (editingMsg) {
      // An edit takes no files from the composer: it needs a text
      if (inputText.trim()) handleEditSubmit(editingMsg, inputText)
      return
    }
    // A message is a text, files, or both
    if (!inputText.trim() && draft.items.length === 0) return

    // From here the files belong to the message: the server commits them when it saves it
    const atts = draft.takeForSend()
    // Files without a text: the text is empty
    const text = inputText.trim() ? inputText : ''
    if (multiMessageMode) {
      bufferMessage(text, atts)
    } else {
      doSend(text, atts)
    }
    setInputText('')
  }, [inputText, draft, draftBlocked, busy, editingMsg, multiMessageMode, bufferMessage, doSend]) // eslint-disable-line react-hooks/exhaustive-deps

  // ── Edit / resend ─────────────────────────────────────────────────────────

  const handleEditSubmit = useCallback(
    async (originalMsg: Message, newText: string) => {
      if (!chatId || !newText.trim()) return
      setEditingMsg(null)
      setError(null)
      // An edit keeps the message's own attachments; files attached to the composer meanwhile
      // are not part of it, so they are dropped (and deleted from the store)
      draft.discardAll()

      try {
        // Like Android's edit: a new variant (branch) in the message's node holding a copy of
        // the message (same id and attachments) with the new text; the reply streams into it.
        // A legacy message has no nodeId: the server resolves the message id to its node.
        const result = await branching.create(chatId, {
          nodeId: originalMsg.nodeId ?? originalMsg.id,
          newUserMessage: {
            id: originalMsg.id,
            role: 'user',
            text: newText,
            attachments: originalMsg.attachments ?? [],
          },
        })
        updateChat(result.chat)
        setInputText('')

        // The resend route accepts the new variant's id: it replies into that variant
        const newMsgId = result.newVariantId

        const resendReq = {
          provider,
          modelName: model,
          systemPrompt: currentChat?.systemPrompt ?? '',
          webSearchEnabled: webSearch,
          enabledToolIds,
          thinkingBudget,
          temperature,
        }

        beginStream((cbs) => resendStream(chatId, newMsgId, resendReq, cbs))
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Edit failed')
      }
    },
    [chatId, provider, model, currentChat, webSearch, enabledToolIds, thinkingBudget, temperature, updateChat, beginStream, draft.discardAll],
  )

  // ── Regenerate ────────────────────────────────────────────────────────────
  // The server creates a new variant in the node of the message's question (Android's
  // resend) and streams the new answer into it; the old answer stays a sibling branch.

  const handleRegenerate = useCallback(
    (msg: Message) => {
      if (!chatId || busy) return
      setError(null)

      const resendReq = {
        provider,
        modelName: model,
        systemPrompt: currentChat?.systemPrompt ?? '',
        webSearchEnabled: webSearch,
        enabledToolIds,
        thinkingBudget,
        temperature,
      }

      beginStream((cbs) => resendStream(chatId, msg.id, resendReq, cbs))
    },
    [chatId, provider, model, currentChat, webSearch, enabledToolIds, thinkingBudget, temperature, busy, beginStream],
  )

  // ── Delete message ────────────────────────────────────────────────────────

  // Branch-aware delete (Android's): only a message with nothing after it in its branch can
  // be deleted; otherwise the server explains why (shown in the error banner).
  const handleDeleteMessage = useCallback(
    async (msgId: string) => {
      if (!chatId) return
      if (!window.confirm('Delete this message?')) return
      try {
        const updatedChat = await messagesApi.delete(chatId, msgId)
        updateChat(updatedChat)
      } catch (err) {
        setError(apiErrorMessage(err, 'Delete failed'))
      }
    },
    [chatId, updateChat],
  )

  // ── File upload ───────────────────────────────────────────────────────────

  // Each picked file starts uploading at once (see useDraftAttachments); a file over the size
  // limit — or an empty one — is refused here, before any request
  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(e.target.files ?? [])
    if (fileInputRef.current) fileInputRef.current.value = ''
    if (picked.length === 0 || !chatId) return
    const rejected = draft.add(picked)
    if (rejected.length > 0) setError(rejected.join(' · '))
  }

  // ── Copy ──────────────────────────────────────────────────────────────────

  const handleCopy = useCallback((text: string) => {
    navigator.clipboard.writeText(text).catch(() => {})
  }, [])

  // ── Chat-level actions (R2) ───────────────────────────────────────────────

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
    // Android persists the selection in app settings
    settingsApi.update({ selected_provider: newProvider, selected_model: newModel }).catch(() => {})
  }, [])

  const handleToggleStar = useCallback((prov: string, modelName: string) => {
    setStarredModels((prev) => {
      const already = prev.some((s) => s.provider === prov && s.modelName === modelName)
      const next = already
        ? prev.filter((s) => !(s.provider === prov && s.modelName === modelName))
        : [...prev, { provider: prov, modelName }]
      // A delta, not the whole list (another device may have starred models since it was loaded)
      const model = { provider: prov, modelName }
      settingsApi.update(already ? { starredModelsRemove: [model] } : { starredModelsAdd: [model] }).catch(() => {})
      return next
    })
  }, [])

  const handleRefreshModels = useCallback(async () => {
    try {
      await providersApi.refresh()
      setProvidersDetailed(await providersApi.detailed())
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to refresh models')
    }
  }, [])

  // ── Stop ──────────────────────────────────────────────────────────────────
  // The request is a server-owned run: closing the stream would not stop it. Stop = cancel the
  // run, keeping the partial reply (Android's stop button: stopStreamingAndSave). The streamed
  // text stays on screen until the run's `cancelled` event, then the chat is re-read.

  const handleStop = useCallback(() => {
    const own = ownStreamRef.current
    if (own) {
      if (own.runId) requestCancel(own.runId)
      else own.stopRequested = true
      return
    }
    if (foreignRun) requestCancel(foreignRun.runId)
  }, [foreignRun, requestCancel])

  // ── Keyboard ──────────────────────────────────────────────────────────────

  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }, [handleSubmit])

  // ── Edit mode helpers ──────────────────────────────────────────────────────

  const startEditing = useCallback((msg: Message) => {
    setEditingMsg(msg)
    setInputText(messageDisplayText(msg))
    setTimeout(() => textareaRef.current?.focus(), 50)
  }, [])

  const cancelEdit = useCallback(() => {
    setEditingMsg(null)
    setInputText('')
  }, [])

  // ── Scroll helpers ────────────────────────────────────────────────────────

  const scrollToBottom = () => {
    messagesRef.current?.scrollTo({ top: messagesRef.current.scrollHeight, behavior: 'smooth' })
  }
  const scrollToTop = () => {
    messagesRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
  }

  // ── Render ────────────────────────────────────────────────────────────────

  if (!currentChat && !busy) {
    return (
      <div className={styles.loading}>
        {chatId && storeError !== CHAT_NOT_FOUND ? 'Loading chat…' : 'Chat not found'}
      </div>
    )
  }

  const chat = currentChat
  const messages = chat?.messages ?? []

  return (
    <div className={styles.page}>
      {/* ── R2 chrome ─── */}
      <ChatTopBar
        provider={provider}
        model={model}
        searchMode={searchMode}
        searchQuery={searchQuery}
        quickSettingsExpanded={quickSettingsExpanded}
        onBack={() => navigate('/')}
        onSearch={() => setSearchMode(true)}
        onShare={() => setShowExportDialog(true)}
        onDelete={() => setShowDeleteConfirm(true)}
        onClickProviderModel={() => setShowModelSelector(true)}
        onSearchQueryChange={setSearchQuery}
        onSearchAction={() => { /* R3 in-chat search is future */ }}
        onExitSearch={() => { setSearchMode(false); setSearchQuery('') }}
        onToggleQuickSettings={() => setQuickSettingsExpanded((v) => !v)}
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
        onToolToggle={handleToolToggle}
        textDirectionMode={textDirectionMode}
        onTextDirectionChange={setTextDirectionMode}
        onSystemPrompt={() => setShowSystemPromptDialog(true)}
        systemPrompt={chat?.systemPrompt ?? ''}
      />

      {/* ── Dialogs ─── */}
      <ModelSelectorDialog
        open={showModelSelector}
        onClose={() => setShowModelSelector(false)}
        providers={availableProviders}
        currentProvider={provider}
        currentModel={model}
        onSelect={handleModelSelect}
        starredModels={starredModels}
        onToggleStar={handleToggleStar}
        onRefresh={handleRefreshModels}
      />

      <SystemPromptDialog
        open={showSystemPromptDialog}
        onClose={() => setShowSystemPromptDialog(false)}
        currentPrompt={chat?.systemPrompt ?? ''}
        onSave={handleSaveSystemPrompt}
      />

      <ChatExportDialog
        open={showExportDialog}
        chat={chat ?? null}
        onClose={() => setShowExportDialog(false)}
        onChatUpdated={() => { if (chatId) loadChat(chatId) }}
      />

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

      {/* ── Error banner ─── */}
      {error && (
        <div className={styles.error} role="alert">
          <span>{error}</span>
          <button type="button" onClick={() => setError(null)}>✕</button>
        </div>
      )}

      {/* ── Message list ─── */}
      <div className={styles.messages} ref={messagesRef}>
        {messages.length === 0 && !busy && (
          <div className={styles.emptyChat}>
            <div className={styles.emptyChatIcon}>💬</div>
            <p>{t('empty_chat_message')}</p>
          </div>
        )}

        {messages.map((msg, i) => {
          const day = messageDayKey(msg.datetime)
          const prevDay = i > 0 ? messageDayKey(messages[i - 1].datetime) : null
          return (
            <div key={msg.id} data-day={day ?? undefined}>
              {day && day !== prevDay && (
                <div className={styles.dateSeparator}>
                  <span className={styles.dateChip}>{formatDayLabel(day)}</span>
                </div>
              )}
              <MessageBubble
                msg={msg}
                chat={chat!}
                textDirectionMode={textDirectionMode}
                onEdit={startEditing}
                onCopy={handleCopy}
                onDelete={handleDeleteMessage}
                onRegenerate={handleRegenerate}
                onBranchSwitch={updateChat}
              />
            </div>
          )
        })}

        {/* ── Multi-message mode: "השב" reply bubble (ReplyPromptBubble) ─── */}
        {showReplyButton && !busy && (
          <div className={styles.replyPromptWrap}>
            <div className={styles.replyPromptBubble}>
              <button
                type="button"
                className={styles.replyPromptBtn}
                onClick={sendBufferedBatch}
              >
                {t('reply_now')}
              </button>
            </div>
          </div>
        )}

        {/* ── Streaming partial assistant bubble ─── */}
        {live && (
          <div className={styles.streamingBubble}>
            {streamSegments(live).map((seg, si, all) => (
              <React.Fragment key={si === all.length - 1 ? 'live' : `settled-${live.settled[si].id}`}>
                {/* ThoughtsBubble during / after thinking */}
                {seg.thinkingOpen && (
                  <ThoughtsBubble
                    text={seg.thinkingText}
                    done={seg.thinkingDone}
                    activelyThinking={!seg.thinkingDone}
                    startTime={seg.thinkingStartTime}
                    durationSeconds={seg.thinkingDurationSeconds}
                  />
                )}

                {/* Inline tool calls */}
                {seg.toolCalls.map((tc, i) => (
                  <ToolCallBlock
                    key={i}
                    toolName={tc.toolName}
                    parameters={tc.parameters}
                    result={tc.result}
                    success={tc.success}
                    executing={tc.executing}
                  />
                ))}

                {/* Partial markdown text */}
                {seg.partialText && (
                  <div
                    style={{ fontSize: 15, lineHeight: '18px', color: 'var(--color-text)' }}
                    dir="auto"
                  >
                    {/* Use plain text for streaming to avoid parsing lag */}
                    <span style={{ whiteSpace: 'pre-wrap' }}>{seg.partialText}</span>
                  </div>
                )}
              </React.Fragment>
            ))}

            {/* Typing indicator dots when waiting for the (next) first token */}
            {isEmptySegment(live) && (
              <div className={styles.streamingDots}>
                <span /><span /><span />
              </div>
            )}
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      {/* ── Floating date chip while scrolling ─── */}
      {floatingDay && (
        <div
          className={`${styles.floatingDate} ${showFloatingDay ? styles.floatingDateVisible : ''}`}
          style={{ top: (messagesRef.current?.offsetTop ?? 0) + 8 }}
          aria-hidden
        >
          <span className={`${styles.dateChip} ${styles.dateChipElevated}`}>{formatDayLabel(floatingDay)}</span>
        </div>
      )}

      {/* ── Floating scroll buttons (blue circles per screenshot) ─── */}
      {showScrollUp && (
        <button
          type="button"
          className={styles.scrollBtn}
          style={{ bottom: 'calc(var(--input-height, 120px) + 60px)' }}
          onClick={scrollToTop}
          aria-label={t('scroll_to_top')}
        >
          <MdArrowUpward size={20} />
        </button>
      )}
      {showScrollDown && (
        <button
          type="button"
          className={styles.scrollBtn}
          style={{ bottom: 'calc(var(--input-height, 120px) + 8px)' }}
          onClick={scrollToBottom}
          aria-label={t('scroll_to_bottom')}
        >
          <MdArrowDownward size={20} />
        </button>
      )}

      {/* ── Input area ─── */}
      <input
        ref={fileInputRef}
        type="file"
        multiple
        style={{ display: 'none' }}
        onChange={handleFileSelect}
        aria-label="Attach file"
      />

      <ChatInputArea
        inputText={inputText}
        onInputChange={setInputText}
        attachments={draft.items}
        onRemoveAttachment={draft.remove}
        onRetryAttachment={draft.retry}
        onAttachFile={() => fileInputRef.current?.click()}
        streaming={busy}
        editingMsg={!!editingMsg}
        webSearch={webSearch}
        showWebSearch={true} // always show; future: check model capabilities
        onToggleWebSearch={() => setWebSearch((v) => !v)}
        onSend={handleSubmit}
        onStop={handleStop}
        onConfirmEdit={() => {
          if (editingMsg && !draftBlocked) handleEditSubmit(editingMsg, inputText)
        }}
        onConfirmEditAndResend={() => {
          if (editingMsg && !draftBlocked) handleEditSubmit(editingMsg, inputText)
        }}
        onCancelEdit={cancelEdit}
        onKeyDown={handleKeyDown}
        textareaRef={textareaRef}
      />
    </div>
  )
}

export default ChatPage
