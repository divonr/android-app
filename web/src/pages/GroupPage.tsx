/**
 * GroupPage — R6 refactor.
 * Mirrors Android GroupScreen.kt: group name topbar + project toggle,
 * ProjectArea (system prompt + attachments), chat list.
 */
import React, { useEffect, useState, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { groups as groupsApi, chats as chatsApi, files as filesApi } from '../api/client'
import type { Attachment, ChatGroup, Chat } from '../api/types'
import { StoreImage } from '../components/chat/AttachmentView'
import { markAttachmentsUsed } from '../hooks/useDraftAttachments'
import {
  attachmentKind,
  attachmentUrl,
  describeUploadError,
  fileRejection,
} from '../utils/attachments'
import ScreenTopBar from '../components/ScreenTopBar'
import { t } from '../i18n/he'
import { apiErrorMessage } from '../utils/apiErrors'
import styles from './GroupPage.module.css'
import { SYNC_CHANGED_EVENT } from '../hooks/useSyncRefresh'

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
  /** Names of the files that are being uploaded right now */
  uploadingNames: string[]
}

/**
 * One project file. With a store hash it is usable here whichever device added it: an image is
 * shown (loaded when it scrolls into view) and opens on click, any other file downloads. Without
 * a hash the file exists only on the device that added it.
 */
const ProjectFileTile: React.FC<{ att: Attachment; onRemove: () => void }> = ({ att, onRemove }) => {
  const [broken, setBroken] = useState(false)
  const kind = attachmentKind(att)
  const removeBtn = (
    <button
      className={styles.fileRemoveBtn}
      onClick={(e) => { e.preventDefault(); e.stopPropagation(); onRemove() }}
      aria-label={`${t('remove_file')} ${att.file_name}`}
    >
      ✕
    </button>
  )
  const name = <span className={styles.fileThumbName}>{att.file_name}</span>

  if (kind === 'legacy' || broken) {
    const reason = kind === 'legacy' ? t('attachment_device_only') : t('attachment_unavailable')
    return (
      <div
        className={`${styles.fileThumb} ${styles.fileThumbUnavailable}`}
        data-testid="project-file"
        data-attachment-state={kind === 'legacy' ? 'legacy' : 'unavailable'}
        title={reason}
      >
        <span className={styles.fileThumbIcon}>{getMimeIcon(att.mime_type)}</span>
        {name}
        <span className={styles.fileThumbHint}>{reason}</span>
        {removeBtn}
      </div>
    )
  }

  if (kind === 'image') {
    return (
      <div className={styles.fileThumb} data-testid="project-file" data-attachment-state="image">
        <a
          className={styles.fileThumbLink}
          href={attachmentUrl(att)!}
          target="_blank"
          rel="noopener noreferrer"
          title={att.file_name}
          aria-label={`${t('attachment_open_image')}: ${att.file_name}`}
        >
          <StoreImage att={att} width={100} height={100} onError={() => setBroken(true)} />
        </a>
        {removeBtn}
      </div>
    )
  }

  return (
    <div className={styles.fileThumb} data-testid="project-file" data-attachment-state="file">
      <a
        className={styles.fileThumbLink}
        href={attachmentUrl(att, true)!}
        download={att.file_name}
        title={`${t('attachment_download')}: ${att.file_name}`}
        aria-label={`${t('attachment_download')}: ${att.file_name}`}
      >
        <span className={styles.fileThumbIcon}>{getMimeIcon(att.mime_type)}</span>
        {name}
        <span className={styles.fileThumbHint}>⬇ {t('attachment_download')}</span>
      </a>
      {removeBtn}
    </div>
  )
}

const ProjectArea: React.FC<ProjectAreaProps> = ({ group, onPromptSave, onAddFile, onRemoveFile, uploadingNames }) => {
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
        {group.group_attachments.length === 0 && uploadingNames.length === 0 ? (
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
              <ProjectFileTile
                key={`${i}-${att.sha256 ?? att.file_name}`}
                att={att}
                onRemove={() => onRemoveFile(i)}
              />
            ))}
            {/* Being uploaded: greyed, with a spinner, until the project has it */}
            {uploadingNames.map((fileName, i) => (
              <div
                key={`uploading-${i}`}
                className={`${styles.fileThumb} ${styles.fileThumbUploading}`}
                data-testid="project-file"
                data-attachment-state="uploading"
                aria-busy="true"
                title={t('attachment_uploading')}
              >
                <span className={styles.fileSpinner} role="progressbar" aria-label={t('attachment_uploading')} />
                <span className={styles.fileThumbName}>{fileName}</span>
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
  const [uploadingNames, setUploadingNames] = useState<string[]>([])
  const fileInputRef = useRef<HTMLInputElement>(null)
  const groupRef = useRef<ChatGroup | null>(null)
  groupRef.current = group

  // silent: a background refresh (sync) — no loading state, the page stays as is meanwhile
  const loadGroup = useCallback(async (silent = false) => {
    if (!groupId) return
    if (!silent) setLoading(true)
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
      if (!silent) setError(err instanceof Error ? err.message : 'Load failed')
    } finally {
      if (!silent) setLoading(false)
    }
  }, [groupId])

  useEffect(() => { loadGroup() }, [loadGroup])

  // Another device changed the account's data (server-side sync pull): reload quietly
  useEffect(() => {
    const onSyncChanged = () => { void loadGroup(true) }
    window.addEventListener(SYNC_CHANGED_EVENT, onSyncChanged)
    return () => window.removeEventListener(SYNC_CHANGED_EVENT, onSyncChanged)
  }, [loadGroup])

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

  // A project file is used the moment it is added: upload → add to the group (the server
  // commits it). If the group cannot take it, the upload is deleted again.
  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(e.target.files ?? [])
    if (fileInputRef.current) fileInputRef.current.value = ''
    if (picked.length === 0 || !groupId) return

    const problems: string[] = []
    for (const file of picked) {
      const reason = fileRejection(file)
      if (reason) {
        problems.push(`${file.name}: ${reason}`)
        continue
      }
      setUploadingNames((prev) => [...prev, file.name])
      try {
        const att = await filesApi.upload(file)
        try {
          const updated = await groupsApi.addAttachment(groupId, att)
          markAttachmentsUsed([att])
          setGroup(updated)
        } catch (err) {
          // The server refused it (a definite answer — after a lost connection it may have been
          // saved): nothing references the upload, unless the project already holds the same file
          const status = (err as { status?: unknown } | null)?.status
          const refused = typeof status === 'number' && status >= 400 && status < 500
          const held = groupRef.current?.group_attachments.some((a) => a.sha256 && a.sha256 === att.sha256)
          if (refused && att.sha256 && !held) filesApi.delete(att.sha256).catch(() => {})
          problems.push(`${file.name}: ${apiErrorMessage(err, t('attachment_upload_failed'))}`)
        }
      } catch (err) {
        problems.push(`${file.name}: ${describeUploadError(err).message}`)
      } finally {
        setUploadingNames((prev) => {
          const at = prev.indexOf(file.name)
          return at < 0 ? prev : [...prev.slice(0, at), ...prev.slice(at + 1)]
        })
      }
    }
    if (problems.length > 0) setError(problems.join(' · '))
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
            uploadingNames={uploadingNames}
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
        multiple
        onChange={handleFileUpload}
        aria-label={t('group_add_file')}
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
