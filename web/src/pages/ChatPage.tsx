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
import { useChatStore } from '../stores/chatStore'
import {
  providers as providersApi,
  branching,
  skills as skillsApi,
  files as filesApi,
  settings as settingsApi,
  chats as chatsApi,
  apiKeys as apiKeysApi,
  messages as messagesApi,
} from '../api/client'
import { sendStream, resendStream } from '../api/stream'
import type { StreamCallbacks } from '../api/stream'
import type {
  Message,
  ProviderModel_Flat,
  InstalledSkill,
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
import Dialog, { DialogButton } from '../ui/Dialog'
import { t } from '../i18n/he'

// ── R3 body components ────────────────────────────────────────────────────────
import MessageBubble from '../components/chat/MessageBubble'
import ThoughtsBubble from '../components/chat/ThoughtsBubble'
import ToolCallBlock from '../components/chat/ToolCallBlock'
import ChatInputArea from '../components/chat/ChatInputArea'
import { MdArrowUpward, MdArrowDownward } from '../ui/icons'

// ─── Streaming state ──────────────────────────────────────────────────────────

interface StreamState {
  partialText: string
  thinkingText: string
  thinkingOpen: boolean
  thinkingDone: boolean
  thinkingStartTime: number | null
  thinkingDurationSeconds: number | null
  toolCalls: Array<{
    toolId: string
    toolName: string
    parameters: Record<string, unknown>
    result?: string
    success?: boolean
    executing: boolean
  }>
  streaming: boolean
}

const EMPTY_STREAM: StreamState = {
  partialText: '',
  thinkingText: '',
  thinkingOpen: false,
  thinkingDone: false,
  thinkingStartTime: null,
  thinkingDurationSeconds: null,
  toolCalls: [],
  streaming: false,
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function makeStreamCallbacks(
  setStream: React.Dispatch<React.SetStateAction<StreamState>>,
  onComplete: () => void,
  onError: (msg: string) => void,
  onMessagesAdded?: () => void,
): StreamCallbacks {
  return {
    onPartial: (e) =>
      setStream((s) => ({ ...s, partialText: s.partialText + e.text })),
    onThinkingStarted: () =>
      setStream((s) => ({ ...s, thinkingOpen: true, thinkingStartTime: Date.now() })),
    onThinkingPartial: (e) =>
      setStream((s) => ({ ...s, thinkingText: s.thinkingText + e.text })),
    onThinkingComplete: (e) =>
      setStream((s) => ({
        ...s,
        thinkingDone: true,
        thinkingDurationSeconds: e.durationSeconds,
      })),
    onToolCall: (e) =>
      setStream((s) => ({
        ...s,
        toolCalls: [
          ...s.toolCalls,
          { toolId: e.toolId, toolName: e.toolName, parameters: e.parameters, executing: true },
        ],
      })),
    onToolResult: (e) =>
      setStream((s) => ({
        ...s,
        toolCalls: s.toolCalls.map((tc) =>
          tc.toolId === e.toolId
            ? { ...tc, result: e.output, success: e.success, executing: false }
            : tc,
        ),
      })),
    onMessagesAdded: onMessagesAdded ? () => onMessagesAdded() : undefined,
    onComplete: () => {
      setStream(EMPTY_STREAM)
      onComplete()
    },
    onError: (e) => {
      setStream(EMPTY_STREAM)
      onError(e.error)
    },
  }
}

// ─── Main ChatPage ────────────────────────────────────────────────────────────

const ChatPage: React.FC = () => {
  const { id: chatId } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { currentChat, loadChat, updateChat } = useChatStore()

  // ── Controls state (unchanged from R2) ────────────────────────────────────
  const [models, setModels] = useState<ProviderModel_Flat[]>([])
  const [provider, setProvider] = useState('openai')
  const [model, setModel] = useState('gpt-4o')
  const [webSearch, setWebSearch] = useState(false)
  const [enabledToolIds, setEnabledToolIds] = useState<string[]>([])
  const [thinkingBudget, setThinkingBudget] = useState<ThinkingBudget>('none')
  const [temperature, setTemperature] = useState<number | null>(null)
  const [skillsList, setSkillsList] = useState<InstalledSkill[]>([])

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

  // ── Multi-message mode (mirrors MessageSendingManager.kt) ────────────────
  const [multiMessageMode, setMultiMessageMode] = useState(false)
  const [showReplyButton, setShowReplyButton] = useState(false)

  // ── Input state ──────────────────────────────────────────────────────────
  const [inputText, setInputText] = useState('')
  const [attachments, setAttachments] = useState<Attachment[]>([])
  const [uploading, setUploading] = useState(false)
  const [editingMsg, setEditingMsg] = useState<Message | null>(null)

  // ── Streaming state ──────────────────────────────────────────────────────
  const [stream, setStream] = useState<StreamState>(EMPTY_STREAM)
  const streamHandleRef = useRef<{ abort: () => void } | null>(null)

  // ── UI state ─────────────────────────────────────────────────────────────
  const [error, setError] = useState<string | null>(null)
  const [showScrollDown, setShowScrollDown] = useState(false)
  const [showScrollUp, setShowScrollUp] = useState(false)

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
    skillsApi.list().then((skills) => {
      setSkillsList(skills)
      setEnabledToolIds(skills.filter((s) => s.enabled).map((s) => s.name))
    }).catch(() => {})
    settingsApi.get().then((s) => {
      if (s.selected_provider) setProvider(s.selected_provider)
      if (s.selected_model) setModel(s.selected_model)
      if (s.starredModels) setStarredModels(s.starredModels)
      setMultiMessageMode(!!s.multiMessageMode)
    }).catch(() => {})
  }, [])

  const availableProviders = useMemo(
    () => providersDetailed.filter((p) => activeKeyProviders.includes(p.provider)),
    [providersDetailed, activeKeyProviders],
  )

  // ── Autoscroll while streaming ───────────────────────────────────────────

  useEffect(() => {
    if (stream.streaming) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [stream.partialText, stream.streaming])

  useEffect(() => {
    if (!stream.streaming) {
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
  }, [])

  useEffect(() => {
    const el = messagesRef.current
    if (!el) return
    el.addEventListener('scroll', handleScroll, { passive: true })
    return () => el.removeEventListener('scroll', handleScroll)
  }, [handleScroll])

  // ── Send message ─────────────────────────────────────────────────────────

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

      setStream({ ...EMPTY_STREAM, streaming: true })

      const cbs = makeStreamCallbacks(
        setStream,
        () => { if (chatId) loadChat(chatId) },
        setError,
        () => { if (chatId) loadChat(chatId) },
      )

      const handle = sendStream(req, cbs)
      streamHandleRef.current = handle
    },
    [chatId, provider, model, currentChat, webSearch, enabledToolIds, thinkingBudget, temperature, loadChat],
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
    if (!chatId || stream.streaming) return
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

    setStream({ ...EMPTY_STREAM, streaming: true })

    const cbs = makeStreamCallbacks(
      setStream,
      () => { if (chatId) loadChat(chatId) },
      setError,
      () => { if (chatId) loadChat(chatId) },
    )

    streamHandleRef.current = sendStream(req, cbs)
  }, [chatId, provider, model, currentChat, webSearch, enabledToolIds, thinkingBudget, temperature, stream.streaming, loadChat])

  const handleSubmit = useCallback(() => {
    if (!inputText.trim() || stream.streaming) return

    if (editingMsg) {
      handleEditSubmit(editingMsg, inputText, attachments)
      return
    }

    if (multiMessageMode) {
      bufferMessage(inputText, attachments)
    } else {
      doSend(inputText, attachments)
    }
    setInputText('')
    setAttachments([])
  }, [inputText, attachments, stream.streaming, editingMsg, multiMessageMode, bufferMessage, doSend]) // eslint-disable-line react-hooks/exhaustive-deps

  // ── Edit / resend ─────────────────────────────────────────────────────────

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

        const newMsgId = result.newVariantId
        setStream({ ...EMPTY_STREAM, streaming: true })

        const resendReq = {
          provider,
          modelName: model,
          systemPrompt: currentChat?.systemPrompt ?? '',
          webSearchEnabled: webSearch,
          enabledToolIds,
          thinkingBudget,
          temperature,
        }

        const cbs = makeStreamCallbacks(
          setStream,
          () => { if (chatId) loadChat(chatId) },
          setError,
          () => { if (chatId) loadChat(chatId) },
        )

        const handle = resendStream(chatId, newMsgId, resendReq, cbs)
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
      setStream({ ...EMPTY_STREAM, streaming: true })

      const resendReq = {
        provider,
        modelName: model,
        systemPrompt: currentChat?.systemPrompt ?? '',
        webSearchEnabled: webSearch,
        enabledToolIds,
        thinkingBudget,
        temperature,
      }

      const cbs = makeStreamCallbacks(
        setStream,
        () => { if (chatId) loadChat(chatId) },
        setError,
        () => { if (chatId) loadChat(chatId) },
      )

      const handle = resendStream(chatId, msg.id, resendReq, cbs)
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
      settingsApi.update({ starredModels: next }).catch(() => {})
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

  // ── Abort ─────────────────────────────────────────────────────────────────

  const handleAbort = useCallback(() => {
    streamHandleRef.current?.abort()
    setStream(EMPTY_STREAM)
  }, [])

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
    setInputText(msg.text)
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

  // ── toolItems (for QuickSettingsBar) ──────────────────────────────────────

  const toolItems = useMemo(() => {
    return skillsList.filter((s) => s.enabled).map((s) => ({ id: s.name, name: s.name }))
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
      {/* ── R2 chrome ─── */}
      <ChatTopBar
        provider={provider}
        model={model}
        searchMode={searchMode}
        searchQuery={searchQuery}
        quickSettingsExpanded={quickSettingsExpanded}
        onBack={() => navigate('/')}
        onSearch={() => setSearchMode(true)}
        onShare={() => {
          if (chatId) chatsApi.export(chatId).catch(() => {})
        }}
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
        onToolToggle={(id, enabled) => {
          setEnabledToolIds((prev) =>
            enabled ? [...prev.filter((x) => x !== id), id] : prev.filter((x) => x !== id),
          )
        }}
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
        {messages.length === 0 && !stream.streaming && (
          <div className={styles.emptyChat}>
            <div className={styles.emptyChatIcon}>💬</div>
            <p>{t('empty_chat_message')}</p>
          </div>
        )}

        {messages.map((msg) => (
          <MessageBubble
            key={msg.id}
            msg={msg}
            chat={chat!}
            textDirectionMode={textDirectionMode}
            onEdit={startEditing}
            onCopy={handleCopy}
            onDelete={handleDeleteMessage}
            onRegenerate={handleRegenerate}
            onBranchSwitch={updateChat}
          />
        ))}

        {/* ── Multi-message mode: "השב" reply bubble (ReplyPromptBubble) ─── */}
        {showReplyButton && !stream.streaming && (
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
        {stream.streaming && (
          <div className={styles.streamingBubble}>
            {/* ThoughtsBubble during / after thinking */}
            {stream.thinkingOpen && (
              <ThoughtsBubble
                text={stream.thinkingText}
                done={stream.thinkingDone}
                activelyThinking={!stream.thinkingDone}
                startTime={stream.thinkingStartTime}
                durationSeconds={stream.thinkingDurationSeconds}
              />
            )}

            {/* Inline tool calls */}
            {stream.toolCalls.map((tc, i) => (
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
            {stream.partialText ? (
              <div
                style={{ fontSize: 15, lineHeight: '18px', color: 'var(--color-text)' }}
                dir="auto"
              >
                {/* Use plain text for streaming to avoid parsing lag */}
                <span style={{ whiteSpace: 'pre-wrap' }}>{stream.partialText}</span>
              </div>
            ) : (
              /* Typing indicator dots when waiting for first token */
              !stream.thinkingOpen && !stream.toolCalls.length && (
                <div className={styles.streamingDots}>
                  <span /><span /><span />
                </div>
              )
            )}
          </div>
        )}

        <div ref={bottomRef} />
      </div>

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
        style={{ display: 'none' }}
        onChange={handleFileSelect}
        aria-label="Attach file"
      />

      <ChatInputArea
        inputText={inputText}
        onInputChange={setInputText}
        attachments={attachments}
        uploading={uploading}
        onRemoveAttachment={(idx) => setAttachments((prev) => prev.filter((_, j) => j !== idx))}
        onAttachFile={() => fileInputRef.current?.click()}
        streaming={stream.streaming}
        editingMsg={!!editingMsg}
        webSearch={webSearch}
        showWebSearch={true} // always show; future: check model capabilities
        onToggleWebSearch={() => setWebSearch((v) => !v)}
        onSend={handleSubmit}
        onStop={handleAbort}
        onConfirmEdit={() => {
          if (editingMsg) handleEditSubmit(editingMsg, inputText, attachments)
        }}
        onConfirmEditAndResend={() => {
          if (editingMsg) handleEditSubmit(editingMsg, inputText, attachments)
        }}
        onCancelEdit={cancelEdit}
        onKeyDown={handleKeyDown}
        textareaRef={textareaRef}
      />
    </div>
  )
}

export default ChatPage
