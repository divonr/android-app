import React, { useEffect, useState, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useChatStore } from '../stores/chatStore'
import { chats as chatsApi, groups as groupsApi, search as searchApi } from '../api/client'
import type { Chat, ChatGroup, SearchResult } from '../api/types'
import styles from './ChatHistoryPage.module.css'

// ─── Helper ──────────────────────────────────────────────────────────────────

function timeAgo(dateStr: string | null | undefined): string {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}d ago`
  return d.toLocaleDateString()
}

function getLastMessage(chat: Chat): string {
  const msgs = chat.messages
  if (!msgs || msgs.length === 0) return 'No messages'
  const last = msgs[msgs.length - 1]
  return last.text?.slice(0, 80) || ''
}

function getLastMessageTime(chat: Chat): string {
  const msgs = chat.messages
  if (!msgs || msgs.length === 0) return ''
  const last = msgs[msgs.length - 1]
  return timeAgo(last.datetime)
}

// ─── Context Menu ─────────────────────────────────────────────────────────────

interface ContextMenuState {
  x: number
  y: number
  chatId: string
  chatName: string
}

// ─── Main Component ──────────────────────────────────────────────────────────

const ChatHistoryPage: React.FC = () => {
  const navigate = useNavigate()
  const { history, loadHistory, loading } = useChatStore()

  const [searchQuery, setSearchQuery] = useState('')
  const [searchResults, setSearchResults] = useState<SearchResult[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set())
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null)
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [newChatDialog, setNewChatDialog] = useState(false)
  const [newGroupDialog, setNewGroupDialog] = useState(false)
  const [newGroupName, setNewGroupName] = useState('')
  const [newChatName, setNewChatName] = useState('')
  const [error, setError] = useState<string | null>(null)

  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const renameInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    loadHistory()
  }, [loadHistory])

  // Close context menu on outside click
  useEffect(() => {
    const handler = () => setContextMenu(null)
    window.addEventListener('click', handler)
    return () => window.removeEventListener('click', handler)
  }, [])

  // Focus rename input
  useEffect(() => {
    if (renamingId && renameInputRef.current) {
      renameInputRef.current.focus()
      renameInputRef.current.select()
    }
  }, [renamingId])

  // ── Search ────────────────────────────────────────────────────────────────

  const handleSearchChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const q = e.target.value
    setSearchQuery(q)
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current)
    if (!q.trim()) {
      setSearchResults(null)
      return
    }
    searchTimerRef.current = setTimeout(async () => {
      setSearching(true)
      try {
        const results = await searchApi.query(q)
        setSearchResults(results)
      } catch {
        setSearchResults([])
      } finally {
        setSearching(false)
      }
    }, 350)
  }

  // ── Create chat ───────────────────────────────────────────────────────────

  const handleCreateChat = async () => {
    if (!newChatName.trim()) return
    try {
      const chat = await chatsApi.create({
        previewName: newChatName.trim(),
        systemPrompt: '',
        groupId: null,
      })
      setNewChatDialog(false)
      setNewChatName('')
      await loadHistory()
      navigate(`/chat/${chat.chat_id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create chat')
    }
  }

  // ── Create group ──────────────────────────────────────────────────────────

  const handleCreateGroup = async () => {
    if (!newGroupName.trim()) return
    try {
      await groupsApi.create(newGroupName.trim())
      setNewGroupDialog(false)
      setNewGroupName('')
      await loadHistory()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create group')
    }
  }

  // ── Rename ────────────────────────────────────────────────────────────────

  const startRename = (chatId: string, currentName: string) => {
    setContextMenu(null)
    setRenamingId(chatId)
    setRenameValue(currentName)
  }

  const commitRename = async () => {
    if (!renamingId || !renameValue.trim()) {
      setRenamingId(null)
      return
    }
    try {
      await chatsApi.update(renamingId, { previewName: renameValue.trim() })
      await loadHistory()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to rename chat')
    } finally {
      setRenamingId(null)
    }
  }

  // ── Delete ────────────────────────────────────────────────────────────────

  const handleDeleteChat = async (chatId: string) => {
    setContextMenu(null)
    if (!window.confirm('Delete this chat?')) return
    try {
      await chatsApi.delete(chatId)
      await loadHistory()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete chat')
    }
  }

  const handleDeleteGroup = async (groupId: string) => {
    if (!window.confirm('Delete this group? Chats inside will be ungrouped.')) return
    try {
      await groupsApi.delete(groupId)
      await loadHistory()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete group')
    }
  }

  // ── Context menu ──────────────────────────────────────────────────────────

  const openContextMenu = (e: React.MouseEvent, chatId: string, chatName: string) => {
    e.preventDefault()
    e.stopPropagation()
    setContextMenu({ x: e.clientX, y: e.clientY, chatId, chatName })
  }

  // ── Toggle group ──────────────────────────────────────────────────────────

  const toggleGroup = useCallback((groupId: string) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev)
      if (next.has(groupId)) next.delete(groupId)
      else next.add(groupId)
      return next
    })
  }, [])

  // ─── Render ──────────────────────────────────────────────────────────────

  if (loading && !history) {
    return <div className={styles.loading}>Loading chats…</div>
  }

  const groups = history?.groups ?? []
  const allChats = history?.chat_history ?? []

  // Map groupId → chats
  const chatsByGroup = new Map<string, Chat[]>()
  const ungrouped: Chat[] = []
  for (const chat of allChats) {
    if (chat.group) {
      const arr = chatsByGroup.get(chat.group) ?? []
      arr.push(chat)
      chatsByGroup.set(chat.group, arr)
    } else {
      ungrouped.push(chat)
    }
  }

  const renderChatRow = (chat: Chat) => {
    if (renamingId === chat.chat_id) {
      return (
        <div key={chat.chat_id} className={styles.chatRow}>
          <input
            ref={renameInputRef}
            className={styles.renameInput}
            value={renameValue}
            onChange={(e) => setRenameValue(e.target.value)}
            onBlur={commitRename}
            onKeyDown={(e) => {
              if (e.key === 'Enter') commitRename()
              if (e.key === 'Escape') setRenamingId(null)
            }}
          />
        </div>
      )
    }
    return (
      <div
        key={chat.chat_id}
        className={styles.chatRow}
        onClick={() => navigate(`/chat/${chat.chat_id}`)}
        onContextMenu={(e) => openContextMenu(e, chat.chat_id, chat.preview_name)}
      >
        <div className={styles.chatRowMain}>
          <span className={styles.chatName}>{chat.preview_name || 'Untitled'}</span>
          <span className={styles.chatTime}>{getLastMessageTime(chat)}</span>
        </div>
        <div className={styles.chatPreview}>{getLastMessage(chat)}</div>
        <button
          className={styles.moreBtn}
          onClick={(e) => openContextMenu(e, chat.chat_id, chat.preview_name)}
          aria-label="More options"
          title="More options"
        >
          ⋯
        </button>
      </div>
    )
  }

  return (
    <div className={styles.page}>
      {/* Header */}
      <div className={styles.header}>
        <h1 className={styles.title}>Chats</h1>
        <div className={styles.headerActions}>
          <button className={styles.btnSecondary} onClick={() => setNewGroupDialog(true)}>
            + Group
          </button>
          <button className={styles.btnPrimary} onClick={() => setNewChatDialog(true)}>
            + New Chat
          </button>
        </div>
      </div>

      {/* Search */}
      <div className={styles.searchBar}>
        <input
          className={styles.searchInput}
          placeholder="Search chats…"
          value={searchQuery}
          onChange={handleSearchChange}
          aria-label="Search chats"
        />
        {searchQuery && (
          <button
            className={styles.clearSearch}
            onClick={() => { setSearchQuery(''); setSearchResults(null) }}
          >
            ✕
          </button>
        )}
      </div>

      {/* Error banner */}
      {error && (
        <div className={styles.error} role="alert">
          {error}
          <button onClick={() => setError(null)}>✕</button>
        </div>
      )}

      {/* Search results */}
      {searchResults !== null ? (
        <div className={styles.list}>
          {searching && <div className={styles.searching}>Searching…</div>}
          {!searching && searchResults.length === 0 && (
            <div className={styles.empty}>No results for "{searchQuery}"</div>
          )}
          {searchResults.map((r) => (
            <div
              key={`${r.chatId}-${r.messageIndex}`}
              className={styles.searchResult}
              onClick={() => navigate(`/chat/${r.chatId}`)}
            >
              <div className={styles.searchResultTitle}>{r.chatTitle}</div>
              <div className={styles.searchResultSnippet}>{r.snippet}</div>
              <div className={styles.searchResultMeta}>{r.matchType.toLowerCase()}</div>
            </div>
          ))}
        </div>
      ) : (
        <div className={styles.list}>
          {/* Groups */}
          {groups.map((group) => {
            const groupChats = chatsByGroup.get(group.group_id) ?? []
            const isExpanded = expandedGroups.has(group.group_id)
            return (
              <div key={group.group_id} className={styles.groupSection}>
                <div
                  className={styles.groupHeader}
                  onClick={() => toggleGroup(group.group_id)}
                >
                  <span className={styles.groupIcon}>{isExpanded ? '▾' : '▸'}</span>
                  <span className={styles.groupName}>{group.group_name}</span>
                  {group.is_project && <span className={styles.projectBadge}>Project</span>}
                  <span className={styles.groupCount}>{groupChats.length}</span>
                  <button
                    className={styles.groupBtn}
                    onClick={(e) => { e.stopPropagation(); navigate(`/groups/${group.group_id}`) }}
                    title="Open group"
                  >
                    ⚙
                  </button>
                  <button
                    className={styles.groupBtn}
                    onClick={(e) => { e.stopPropagation(); handleDeleteGroup(group.group_id) }}
                    title="Delete group"
                  >
                    🗑
                  </button>
                </div>
                {isExpanded && (
                  <div className={styles.groupChats}>
                    {groupChats.length === 0 && (
                      <div className={styles.empty} style={{ paddingLeft: '2rem' }}>Empty group</div>
                    )}
                    {groupChats.map(renderChatRow)}
                  </div>
                )}
              </div>
            )
          })}

          {/* Ungrouped chats */}
          {ungrouped.length === 0 && groups.length === 0 && (
            <div className={styles.empty}>
              No chats yet. Create one to get started!
            </div>
          )}
          {ungrouped.map(renderChatRow)}
        </div>
      )}

      {/* Context menu */}
      {contextMenu && (
        <div
          className={styles.contextMenu}
          style={{ top: contextMenu.y, left: contextMenu.x }}
          onClick={(e) => e.stopPropagation()}
        >
          <button onClick={() => startRename(contextMenu.chatId, contextMenu.chatName)}>
            Rename
          </button>
          <button
            className={styles.contextMenuDelete}
            onClick={() => handleDeleteChat(contextMenu.chatId)}
          >
            Delete
          </button>
        </div>
      )}

      {/* New Chat Dialog */}
      {newChatDialog && (
        <div className={styles.overlay} onClick={() => setNewChatDialog(false)}>
          <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
            <h3>New Chat</h3>
            <input
              className={styles.dialogInput}
              placeholder="Chat name"
              value={newChatName}
              onChange={(e) => setNewChatName(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleCreateChat() }}
              autoFocus
            />
            <div className={styles.dialogActions}>
              <button className={styles.btnSecondary} onClick={() => setNewChatDialog(false)}>
                Cancel
              </button>
              <button
                className={styles.btnPrimary}
                onClick={handleCreateChat}
                disabled={!newChatName.trim()}
              >
                Create
              </button>
            </div>
          </div>
        </div>
      )}

      {/* New Group Dialog */}
      {newGroupDialog && (
        <div className={styles.overlay} onClick={() => setNewGroupDialog(false)}>
          <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
            <h3>New Group</h3>
            <input
              className={styles.dialogInput}
              placeholder="Group name"
              value={newGroupName}
              onChange={(e) => setNewGroupName(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleCreateGroup() }}
              autoFocus
            />
            <div className={styles.dialogActions}>
              <button className={styles.btnSecondary} onClick={() => setNewGroupDialog(false)}>
                Cancel
              </button>
              <button
                className={styles.btnPrimary}
                onClick={handleCreateGroup}
                disabled={!newGroupName.trim()}
              >
                Create
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default ChatHistoryPage
