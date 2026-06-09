/**
 * GroupPage — R6 refactor.
 * Mirrors Android GroupScreen.kt: group name topbar + project toggle,
 * ProjectArea (system prompt + attachments), chat list.
 */
import React, { useEffect, useState, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { groups as groupsApi, chats as chatsApi, files as filesApi } from '../api/client'
import type { ChatGroup, Chat } from '../api/types'
import ScreenTopBar from '../components/ScreenTopBar'
import { t } from '../i18n/he'
import styles from './GroupPage.module.css'

// ── Helpers ───────────────────────────────────────────────────────────────────

function getModelInitial(modelName?: string | null): string {
  if (!modelName) return 'A'
  const m = modelName.toLowerCase()
  if (m.includes('claude')) return 'C'
  if (m.includes('gpt') || m.includes('openai')) return 'G'
  if (m.includes('gemini') || m.includes('google')) return 'Ge'
  if (m.includes('llama')) return 'L'
  if (m.includes('mistral')) return 'M'
  return modelName[0]?.toUpperCase() ?? 'A'
}

function getLastTimestamp(chat: Chat): number | null {
  if (!chat.messages.length) return null
  const msg = chat.messages[chat.messages.length - 1]
  if (!msg.datetime) return null
  const ts = new Date(msg.datetime).getTime()
  return isNaN(ts) ? null : ts
}

function formatTimestamp(ts: number): string {
  const now = Date.now()
  const diff = now - ts
  const day = 86400000
  if (diff < day) {
    const d = new Date(ts)
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }
  if (diff < 7 * day) {
    return new Date(ts).toLocaleDateString('he-IL', { weekday: 'short' })
  }
  return new Date(ts).toLocaleDateString('he-IL', { day: 'numeric', month: 'numeric' })
}

function getMimeIcon(mimeType: string): string {
  if (mimeType.startsWith('image/')) return '🖼'
  if (mimeType.startsWith('video/')) return '🎬'
  if (mimeType.startsWith('audio/')) return '🎵'
  if (mimeType.includes('pdf')) return '📄'
  return '📎'
}

// ── Project area component ─────────────────────────────────────────────────────

interface ProjectAreaProps {
  group: ChatGroup
  onPromptSave: (prompt: string) => Promise<void>
  onAddFile: () => void
  onRemoveFile: (idx: number) => void
}

const ProjectArea: React.FC<ProjectAreaProps> = ({ group, onPromptSave, onAddFile, onRemoveFile }) => {
  const [editing, setEditing] = useState(false)
  const [editPrompt, setEditPrompt] = useState(group.system_prompt ?? '')
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  // Sync when group changes
  useEffect(() => {
    if (!editing) setEditPrompt(group.system_prompt ?? '')
  }, [group.system_prompt, editing])

  const handleSave = async () => {
    setSaving(true)
    try {
      await onPromptSave(editPrompt)
      setSaved(true)
      setEditing(false)
      setTimeout(() => setSaved(false), 2000)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className={styles.projectCard}>
      {/* Instructions */}
      <div
        className={styles.instructionsRow}
        onClick={() => setEditing((e) => !e)}
        role="button"
        aria-expanded={editing}
      >
        <div className={styles.instructionsIcon} aria-hidden="true">✏️</div>
        <div className={styles.instructionsText}>
          <p className={styles.instructionsTitle}>{t('group_instructions')}</p>
          <p className={styles.instructionsPreview}>
            {group.system_prompt?.trim() || t('group_no_instructions')}
          </p>
        </div>
      </div>

      {editing && (
        <>
          <textarea
            className={styles.promptArea}
            value={editPrompt}
            onChange={(e) => setEditPrompt(e.target.value)}
            placeholder={t('group_no_instructions')}
            rows={5}
            autoFocus
          />
          <div className={styles.savePromptRow}>
            {saved && <span className={styles.savedBadge}>✓ {t('save')}</span>}
            <button className={styles.btnPrimary} onClick={handleSave} disabled={saving}>
              {saving ? '...' : t('group_save_prompt')}
            </button>
          </div>
        </>
      )}

      {/* Files */}
      <div>
        <p className={styles.filesHeader}>{t('group_files')}</p>
        {group.group_attachments.length === 0 ? (
          <div
            className={styles.filesEmpty}
            onClick={onAddFile}
            role="button"
          >
            <span className={styles.filesEmptyText}>{t('group_add_files_placeholder')}</span>
          </div>
        ) : (
          <div className={styles.filesGrid}>
            {/* Add button */}
            <div
              className={`${styles.fileThumb} ${styles.fileThumbAdd}`}
              onClick={onAddFile}
              role="button"
              aria-label={t('group_add_file')}
            >
              <span className={styles.fileThumbIcon}>+</span>
            </div>
            {group.group_attachments.map((att, i) => (
              <div key={i} className={styles.fileThumb}>
                <span className={styles.fileThumbIcon}>{getMimeIcon(att.mime_type)}</span>
                <span className={styles.fileThumbName}>{att.file_name}</span>
                <button
                  className={styles.fileRemoveBtn}
                  onClick={(e) => { e.stopPropagation(); onRemoveFile(i) }}
                  aria-label={`${t('remove_file')} ${att.file_name}`}
                >
                  ✕
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

// ── ChatItem component ────────────────────────────────────────────────────────

interface ChatItemProps {
  chat: Chat
  onClick: () => void
}

const ChatItem: React.FC<ChatItemProps> = ({ chat, onClick }) => {
  const lastMsg = chat.messages[chat.messages.length - 1]
  const lastAssistantModel = [...chat.messages].reverse().find((m) => m.role === 'assistant')?.model
  const initial = getModelInitial(lastAssistantModel)
  const ts = getLastTimestamp(chat)

  return (
    <div className={styles.chatCard} onClick={onClick} role="button">
      <div className={styles.modelCircle}>{initial}</div>
      <div className={styles.chatInfo}>
        <p className={styles.chatTitle}>{chat.preview_name || t('new_chat')}</p>
        {lastMsg && (
          <p className={styles.chatSnippet}>
            {lastMsg.text.slice(0, 100)}
          </p>
        )}
      </div>
      <div className={styles.chatMeta}>
        {ts && <span className={styles.chatTimestamp}>{formatTimestamp(ts)}</span>}
        {chat.messages.length > 0 && (
          <span className={styles.chatCount}>
            💬 {chat.messages.length}
          </span>
        )}
      </div>
    </div>
  )
}

// ── Main GroupPage ────────────────────────────────────────────────────────────

const GroupPage: React.FC = () => {
  const { id: groupId } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [group, setGroup] = useState<ChatGroup | null>(null)
  const [groupChats, setGroupChats] = useState<Chat[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [creatingChat, setCreatingChat] = useState(false)
  const [uploadingFile, setUploadingFile] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const loadGroup = useCallback(async () => {
    if (!groupId) return
    setLoading(true)
    try {
      const [allGroups, allChats] = await Promise.all([
        groupsApi.list(),
        chatsApi.list(),
      ])
      const g = allGroups.find((g) => g.group_id === groupId)
      if (!g) {
        setError('Group not found')
        return
      }
      setGroup(g)
      const chatsInGroup = allChats.chat_history.filter((c) => c.group === groupId)
      // Sort by last message timestamp descending
      const sorted = [...chatsInGroup].sort((a, b) => {
        const ta = getLastTimestamp(a) ?? 0
        const tb = getLastTimestamp(b) ?? 0
        return tb - ta
      })
      setGroupChats(sorted)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Load failed')
    } finally {
      setLoading(false)
    }
  }, [groupId])

  useEffect(() => { loadGroup() }, [loadGroup])

  // ── Project mode toggle ────────────────────────────────────────────────────

  const handleToggleProject = async () => {
    if (!groupId || !group) return
    try {
      const updated = await groupsApi.update(groupId, { isProject: !group.is_project })
      setGroup(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Toggle failed')
    }
  }

  // ── Save system prompt ─────────────────────────────────────────────────────

  const handleSavePrompt = async (prompt: string) => {
    if (!groupId) return
    const updated = await groupsApi.update(groupId, { systemPrompt: prompt })
    setGroup(updated)
  }

  // ── File attachment ────────────────────────────────────────────────────────

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !groupId) return
    setUploadingFile(true)
    try {
      const att = await filesApi.upload(file)
      const updated = await groupsApi.addAttachment(groupId, att)
      setGroup(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setUploadingFile(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  const handleRemoveAttachment = async (idx: number) => {
    if (!groupId) return
    try {
      const updated = await groupsApi.removeAttachment(groupId, idx)
      setGroup(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Remove failed')
    }
  }

  // ── New chat in group ──────────────────────────────────────────────────────

  const handleNewChat = async () => {
    if (!groupId || !group) return
    setCreatingChat(true)
    try {
      const chat = await chatsApi.create({
        previewName: t('new_chat'),
        systemPrompt: group.system_prompt ?? '',
        groupId,
      })
      navigate(`/chat/${chat.chat_id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Create failed')
      setCreatingChat(false)
    }
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  if (loading) return <div className={styles.loading}>{t('new_chat')} loading…</div>
  if (!group) {
    return (
      <div className={styles.page}>
        <ScreenTopBar title="קבוצה" onBack={() => navigate('/')} />
        <div className={styles.emptyState}>
          <div className={styles.emptyIcon}>⚠️</div>
          <p className={styles.emptyTitle} role="status">{t('group_not_found')}</p>
        </div>
      </div>
    )
  }

  const projectToggle = (
    <div className={styles.projectToggleRow}>
      <span className={styles.projectToggleLabel}>{t('group_project_mode')}</span>
      <label className={styles.toggle} title={t('group_project_mode')}>
        <input
          type="checkbox"
          checked={group.is_project}
          onChange={handleToggleProject}
          aria-label={t('group_project_mode')}
        />
        <span className={styles.toggleSlider} />
      </label>
    </div>
  )

  return (
    <div className={styles.page}>
      <ScreenTopBar
        title={group.group_name}
        onBack={() => navigate('/')}
        actions={projectToggle}
      />

      {error && (
        <div className={styles.error} role="alert">
          {error}
          <button className={styles.errorClose} onClick={() => setError(null)}>✕</button>
        </div>
      )}

      <div className={styles.content}>
        {group.is_project && (
          <ProjectArea
            group={group}
            onPromptSave={handleSavePrompt}
            onAddFile={() => fileInputRef.current?.click()}
            onRemoveFile={handleRemoveAttachment}
          />
        )}

        {/* Chat list */}
        <div className={styles.chatsSection}>
          {groupChats.length === 0 ? (
            <div className={styles.emptyState}>
              <div className={styles.emptyIcon}>📁</div>
              <p className={styles.emptyTitle}>{t('group_no_chats')}</p>
              <p className={styles.emptyHint}>{t('group_add_chat_hint')}</p>
            </div>
          ) : (
            groupChats.map((chat) => (
              <ChatItem
                key={chat.chat_id}
                chat={chat}
                onClick={() => navigate(`/chat/${chat.chat_id}`)}
              />
            ))
          )}
        </div>
      </div>

      {/* Hidden file input */}
      <input
        ref={fileInputRef}
        type="file"
        className={styles.fileInput}
        onChange={handleFileUpload}
        disabled={uploadingFile}
      />

      {/* FAB: new chat */}
      <button
        className={styles.fab}
        onClick={handleNewChat}
        disabled={creatingChat}
        aria-label={t('group_new_chat')}
      >
        {creatingChat ? '…' : '+'} {t('new_chat')}
      </button>
    </div>
  )
}

export default GroupPage
