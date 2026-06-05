import React, { useEffect, useState, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { groups as groupsApi, chats as chatsApi, files as filesApi } from '../api/client'
import type { ChatGroup, Chat, Attachment } from '../api/types'
import styles from './GroupPage.module.css'

const GroupPage: React.FC = () => {
  const { id: groupId } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [group, setGroup] = useState<ChatGroup | null>(null)
  const [groupChats, setGroupChats] = useState<Chat[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  const [editPrompt, setEditPrompt] = useState('')
  const [editingName, setEditingName] = useState(false)
  const [savingPrompt, setSavingPrompt] = useState(false)
  const [uploadingFile, setUploadingFile] = useState(false)
  const [saved, setSaved] = useState(false)
  const fileInputRef = React.useRef<HTMLInputElement>(null)

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
      setEditName(g.group_name)
      setEditPrompt(g.system_prompt ?? '')
      // Filter chats belonging to this group
      const chatsInGroup = allChats.chat_history.filter((c) => c.group === groupId)
      setGroupChats(chatsInGroup)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Load failed')
    } finally {
      setLoading(false)
    }
  }, [groupId])

  useEffect(() => { loadGroup() }, [loadGroup])

  // ── Save group name ────────────────────────────────────────────────────────

  const handleSaveName = async () => {
    if (!groupId || !editName.trim()) return
    try {
      const updated = await groupsApi.update(groupId, { groupName: editName.trim() })
      setGroup(updated)
      setEditingName(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Rename failed')
    }
  }

  // ── Save system prompt ────────────────────────────────────────────────────

  const handleSavePrompt = async () => {
    if (!groupId) return
    setSavingPrompt(true)
    try {
      const updated = await groupsApi.update(groupId, { systemPrompt: editPrompt })
      setGroup(updated)
      setSaved(true)
      setTimeout(() => setSaved(false), 2000)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Save failed')
    } finally {
      setSavingPrompt(false)
    }
  }

  // ── Toggle project mode ───────────────────────────────────────────────────

  const handleToggleProject = async () => {
    if (!groupId || !group) return
    try {
      const updated = await groupsApi.update(groupId, { isProject: !group.is_project })
      setGroup(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Toggle failed')
    }
  }

  // ── Remove chat from group ────────────────────────────────────────────────

  const handleRemoveChat = async (chatId: string) => {
    if (!groupId) return
    try {
      await groupsApi.removeChat(groupId, chatId)
      setGroupChats((prev) => prev.filter((c) => c.chat_id !== chatId))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Remove failed')
    }
  }

  // ── Add file attachment ───────────────────────────────────────────────────

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

  // ── Remove attachment ─────────────────────────────────────────────────────

  const handleRemoveAttachment = async (idx: number) => {
    if (!groupId) return
    try {
      const updated = await groupsApi.removeAttachment(groupId, idx)
      setGroup(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Remove failed')
    }
  }

  // ── Delete group ──────────────────────────────────────────────────────────

  const handleDeleteGroup = async () => {
    if (!groupId) return
    if (!window.confirm('Delete this group? Chats inside will be ungrouped.')) return
    try {
      await groupsApi.delete(groupId)
      navigate('/')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Delete failed')
    }
  }

  // ── Render ────────────────────────────────────────────────────────────────

  if (loading) return <div className={styles.loading}>Loading group…</div>
  if (!group) return <div className={styles.loading}>{error ?? 'Group not found'}</div>

  return (
    <div className={styles.page}>
      <div className={styles.topbar}>
        <button className={styles.backBtn} onClick={() => navigate('/')} aria-label="Back">←</button>
        <div className={styles.topbarTitle}>
          {editingName ? (
            <input
              className={styles.nameInput}
              value={editName}
              onChange={(e) => setEditName(e.target.value)}
              onBlur={handleSaveName}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleSaveName()
                if (e.key === 'Escape') { setEditingName(false); setEditName(group.group_name) }
              }}
              autoFocus
            />
          ) : (
            <span
              className={styles.groupNameDisplay}
              onClick={() => setEditingName(true)}
              title="Click to rename"
            >
              {group.group_name}
              {group.is_project && <span className={styles.projectBadge}>Project</span>}
            </span>
          )}
        </div>
        <div className={styles.topbarActions}>
          <label className={styles.projectToggle}>
            <input
              type="checkbox"
              checked={group.is_project}
              onChange={handleToggleProject}
            />
            <span className={styles.toggleSlider} />
            <span>Project mode</span>
          </label>
          <button
            className={styles.deleteBtnSmall}
            onClick={handleDeleteGroup}
            title="Delete group"
          >
            🗑
          </button>
        </div>
      </div>

      {error && (
        <div className={styles.error} role="alert">
          {error}
          <button onClick={() => setError(null)}>✕</button>
        </div>
      )}

      <div className={styles.content}>
        {/* Project area: system prompt + attachments */}
        {group.is_project && (
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Shared System Prompt</h2>
            <p className={styles.sectionHint}>
              This prompt is prepended to all chats in this group.
            </p>
            <textarea
              className={styles.promptArea}
              value={editPrompt}
              onChange={(e) => setEditPrompt(e.target.value)}
              placeholder="Enter a shared system prompt for all chats in this group…"
              rows={6}
            />
            <div className={styles.saveRow}>
              {saved && <span className={styles.savedBadge}>✓ Saved</span>}
              <button
                className={styles.btnPrimary}
                onClick={handleSavePrompt}
                disabled={savingPrompt}
              >
                {savingPrompt ? 'Saving…' : 'Save Prompt'}
              </button>
            </div>
          </section>
        )}

        {/* Project attachments */}
        {group.is_project && (
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Project Attachments</h2>
            <p className={styles.sectionHint}>
              Files attached here are available in all chats in this group.
            </p>
            <div className={styles.attachmentsList}>
              {group.group_attachments.map((att, i) => (
                <div key={i} className={styles.attachmentRow}>
                  <span className={styles.attachmentIcon}>📎</span>
                  <span className={styles.attachmentName}>{att.file_name}</span>
                  <span className={styles.attachmentType}>{att.mime_type}</span>
                  <button
                    className={styles.removeBtn}
                    onClick={() => handleRemoveAttachment(i)}
                    title="Remove attachment"
                  >
                    ✕
                  </button>
                </div>
              ))}
              {group.group_attachments.length === 0 && (
                <div className={styles.emptyAttachments}>No attachments yet</div>
              )}
            </div>
            <div className={styles.addAttachmentRow}>
              <input
                ref={fileInputRef}
                type="file"
                className={styles.fileInput}
                onChange={handleFileUpload}
              />
              <button
                className={styles.btnSecondary}
                onClick={() => fileInputRef.current?.click()}
                disabled={uploadingFile}
              >
                {uploadingFile ? 'Uploading…' : '+ Add File'}
              </button>
            </div>
          </section>
        )}

        {/* Chats in group */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>
            Chats
            <span className={styles.chatCount}>{groupChats.length}</span>
          </h2>
          {groupChats.length === 0 ? (
            <div className={styles.emptyChats}>No chats in this group yet.</div>
          ) : (
            <div className={styles.chatList}>
              {groupChats.map((chat) => (
                <div key={chat.chat_id} className={styles.chatRow}>
                  <span
                    className={styles.chatName}
                    onClick={() => navigate(`/chat/${chat.chat_id}`)}
                  >
                    {chat.preview_name || 'Untitled'}
                  </span>
                  <div className={styles.chatRowActions}>
                    <button
                      className={styles.openChatBtn}
                      onClick={() => navigate(`/chat/${chat.chat_id}`)}
                    >
                      Open
                    </button>
                    <button
                      className={styles.removeBtn}
                      onClick={() => handleRemoveChat(chat.chat_id)}
                      title="Remove from group"
                    >
                      ✕
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}

export default GroupPage
