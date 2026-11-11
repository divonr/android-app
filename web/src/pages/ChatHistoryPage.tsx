/**
 * ChatHistoryPage — R1 refactor.
 *
 * Visual + UX parity with the Android app's ChatHistoryScreen.kt:
 *   - Top bar: "ApI" logo (forced-LTR), search / api-keys / settings icons
 *   - Search mode: full-width search field, live results
 *   - List: chats + groups organised by organizeAndSortAllItems (ported from Kotlin)
 *   - Chat item: 48px model-initial avatar, title, preview, timestamp
 *   - Group item: folder icon, name, chat count, expand/collapse arrow
 *   - FAB: single-tap creates "שיחה חדשה" immediately (no dialog), navigates
 *   - Context menus: right-click + 500ms long-press
 *   - Dialogs: rename, delete-confirm, create-group, rename-group, delete-group
 *   - All text from i18n/he.ts, layout RTL
 */

import React, {
  useEffect,
  useState,
  useRef,
  useCallback,
  useMemo,
} from 'react'
import ReactDOM from 'react-dom'
import { useNavigate } from 'react-router-dom'
import { useChatStore } from '../stores/chatStore'
import {
  chats as chatsApi,
  groups as groupsApi,
  search as searchApi,
} from '../api/client'
import type { Chat, ChatGroup, SearchResult } from '../api/types'
import { t } from '../i18n/he'
import IconButton from '../ui/IconButton'
import Dialog, { DialogButton } from '../ui/Dialog'
import ChatExportDialog from '../components/chat/ChatExportDialog'
import {
  MdSearch,
  MdClose,
  MdAdd,
  MdSettings,
  MdKey,
  MdFolder,
  MdStar,
  MdForum,
  MdExpandMore,
  MdExpandLess,
  MdEdit,
  MdDelete,
  MdShare,
  MdBuild,
  MdRemove,
} from '../ui/icons'
import styles from './ChatHistoryPage.module.css'
import { getModelInitial, getModelLogoPath, formatTimestamp } from '../utils/chatUtils'

// ─── Helpers (ported from ChatUtils.kt + ChatListOrganizer.kt) ────────────────
// getModelInitial and formatTimestamp are now in utils/chatUtils.ts

/** Mirror of ChatUtils.kt getLastTimestampOrNull */
function getLastTimestamp(chat: Chat): number | null {
  const msgs = chat.messages
  if (!msgs || msgs.length === 0) return null
  const iso = msgs[msgs.length - 1].datetime
  if (!iso) return null
  const ts = Date.parse(iso)
  return isNaN(ts) ? null : ts
}

/** Get model from last assistant message (mirrors ChatHistoryItems.kt logic, incl. Chat.model fallback) */
function getChatModel(chat: Chat): string {
  const reversed = [...(chat.messages ?? [])].reverse()
  const last = reversed.find((m) => m.role === 'assistant')
  return last?.model ?? 'gpt-4o'
}

// ─── organizeAndSortAllItems (ported from ChatListOrganizer.kt) ───────────────

type ChatListEntry =
  | { type: 'group'; group: ChatGroup; chats: Chat[]; timestamp: number }
  | { type: 'chat'; chat: Chat; timestamp: number }

function organizeAndSortAllItems(
  chats: Chat[],
  groups: ChatGroup[],
): ChatListEntry[] {
  const entries: ChatListEntry[] = []
  const chatsByGroup = new Map<string, Chat[]>()
  const ungrouped: Chat[] = []

  for (const chat of chats) {
    if (chat.group) {
      const arr = chatsByGroup.get(chat.group) ?? []
      arr.push(chat)
      chatsByGroup.set(chat.group, arr)
    } else {
      ungrouped.push(chat)
    }
  }

  for (const group of groups) {
    const groupChats = chatsByGroup.get(group.group_id) ?? []
    if (groupChats.length > 0) {
      const ts = Math.max(
        ...groupChats.map(
          (c) => getLastTimestamp(c) ?? Number.MIN_SAFE_INTEGER,
        ),
      )
      entries.push({ type: 'group', group, chats: groupChats, timestamp: ts })
    }
  }

  for (const chat of ungrouped) {
    entries.push({
      type: 'chat',
      chat,
      timestamp: getLastTimestamp(chat) ?? Number.MIN_SAFE_INTEGER,
    })
  }

  return entries.sort((a, b) => b.timestamp - a.timestamp)
}

// ─── Context menu state types ─────────────────────────────────────────────────

interface ChatMenuState {
  x: number
  y: number
  chat: Chat
}

interface GroupMenuState {
  x: number
  y: number
  group: ChatGroup
}

// ─── Sub-components ───────────────────────────────────────────────────────────

interface ChatItemCardProps {
  chat: Chat
  isAIRenaming: boolean
  onClick: () => void
  onContextMenu: (x: number, y: number) => void
  indented?: boolean
}

const ChatItemCard: React.FC<ChatItemCardProps> = ({
  chat,
  isAIRenaming,
  onClick,
  onContextMenu,
  indented = false,
}) => {
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const startPosRef = useRef({ x: 0, y: 0 })

  const handlePointerDown = (e: React.PointerEvent) => {
    startPosRef.current = { x: e.clientX, y: e.clientY }
    timerRef.current = setTimeout(() => onContextMenu(e.clientX, e.clientY), 500)
  }

  const cancelTimer = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }

  const handlePointerMove = (e: React.PointerEvent) => {
    const dx = Math.abs(e.clientX - startPosRef.current.x)
    const dy = Math.abs(e.clientY - startPosRef.current.y)
    if (dx > 8 || dy > 8) cancelTimer()
  }

  const handleContextMenuEvt = (e: React.MouseEvent) => {
    e.preventDefault()
    cancelTimer()
    onContextMenu(e.clientX, e.clientY)
  }

  const model = getChatModel(chat)
  const logoPath = getModelLogoPath(model)
  const initial = getModelInitial(model)
  const ts = getLastTimestamp(chat)
  const msgs = chat.messages ?? []
  const lastMsg = msgs[msgs.length - 1]

  return (
    <div
      className={`${styles.chatCard}${indented ? ` ${styles.chatCardIndented}` : ''}`}
      role="listitem"
      onClick={onClick}
      onContextMenu={handleContextMenuEvt}
      onPointerDown={handlePointerDown}
      onPointerUp={cancelTimer}
      onPointerLeave={cancelTimer}
      onPointerMove={handlePointerMove}
    >
      <div className={styles.chatCardInner}>
        {/* Avatar */}
        <div className={styles.avatar}>
          {isAIRenaming ? (
            <div className={styles.avatarSpinner} />
          ) : logoPath ? (
            <img src={logoPath} alt="Model Logo" className={styles.avatarLogo} />
          ) : (
            <span className={styles.avatarText}>{initial}</span>
          )}
        </div>

        {/* Content */}
        <div className={styles.chatContent}>
          <span className={styles.chatTitle}>
            {chat.preview_name || t('new_chat')}
          </span>
          {lastMsg && (
            <span className={styles.chatPreview}>
              {lastMsg.text?.slice(0, 100)}
            </span>
          )}
        </div>

        {/* Trailing meta */}
        <div className={styles.chatMeta}>
          {ts !== null && (
            <span className={styles.timestamp}>{formatTimestamp(ts)}</span>
          )}
          {msgs.length > 0 && (
            <span className={styles.msgCount}>
              <MdForum size={12} />
              {' '}
              {msgs.length}
            </span>
          )}
        </div>
      </div>
    </div>
  )
}

interface GroupItemCardProps {
  group: ChatGroup
  chatCount: number
  isExpanded: boolean
  onGroupClick: () => void
  onToggleExpansion: () => void
  onContextMenu: (x: number, y: number) => void
}

const GroupItemCard: React.FC<GroupItemCardProps> = ({
  group,
  chatCount,
  isExpanded,
  onGroupClick,
  onToggleExpansion,
  onContextMenu,
}) => {
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const startPosRef = useRef({ x: 0, y: 0 })

  const handlePointerDown = (e: React.PointerEvent) => {
    startPosRef.current = { x: e.clientX, y: e.clientY }
    timerRef.current = setTimeout(() => onContextMenu(e.clientX, e.clientY), 500)
  }

  const cancelTimer = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }

  const handlePointerMove = (e: React.PointerEvent) => {
    const dx = Math.abs(e.clientX - startPosRef.current.x)
    const dy = Math.abs(e.clientY - startPosRef.current.y)
    if (dx > 8 || dy > 8) cancelTimer()
  }

  const handleContextMenuEvt = (e: React.MouseEvent) => {
    e.preventDefault()
    cancelTimer()
    onContextMenu(e.clientX, e.clientY)
  }

  return (
    <div
      className={styles.chatCard}
      role="listitem"
      onClick={onGroupClick}
      onContextMenu={handleContextMenuEvt}
      onPointerDown={handlePointerDown}
      onPointerUp={cancelTimer}
      onPointerLeave={cancelTimer}
      onPointerMove={handlePointerMove}
    >
      <div className={styles.chatCardInner}>
        {/* Folder avatar */}
        <div className={`${styles.avatar} ${styles.avatarGroup}`}>
          <MdFolder size={24} />
        </div>

        {/* Content — clicking navigates to group */}
        <div className={styles.chatContent}>
          <span className={styles.chatTitle}>{group.group_name}</span>
          <span className={styles.chatPreview}>{chatCount} שיחות</span>
        </div>

        {/* Expand arrow — separate click target */}
        <button
          className={styles.expandBtn}
          onClick={(e) => {
            e.stopPropagation()
            onToggleExpansion()
          }}
          aria-label={isExpanded ? 'כווץ קבוצה' : 'הרחב קבוצה'}
        >
          {isExpanded ? <MdExpandLess size={20} /> : <MdExpandMore size={20} />}
        </button>
      </div>
    </div>
  )
}

// ─── Main Component ───────────────────────────────────────────────────────────

const ChatHistoryPage: React.FC = () => {
  const navigate = useNavigate()
  const { history, loadHistory, loading } = useChatStore()

  // ── Search state ───────────────────────────────────────────────────────────
  const [searchMode, setSearchMode] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [searchResults, setSearchResults] = useState<SearchResult[]>([])
  const [searching, setSearching] = useState(false)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // ── Group expansion ────────────────────────────────────────────────────────
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set())

  // ── Context menus ──────────────────────────────────────────────────────────
  const [chatMenu, setChatMenu] = useState<ChatMenuState | null>(null)
  const [chatMenuGroupSubmenu, setChatMenuGroupSubmenu] = useState(false)
  const [groupMenu, setGroupMenu] = useState<GroupMenuState | null>(null)

  // ── Dialogs ────────────────────────────────────────────────────────────────
  const [renameDialog, setRenameDialog] = useState<Chat | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [deleteDialog, setDeleteDialog] = useState<Chat | null>(null)
  const [exportDialogChat, setExportDialogChat] = useState<Chat | null>(null)
  const [createGroupDialog, setCreateGroupDialog] = useState(false)
  const [createGroupForChat, setCreateGroupForChat] = useState<Chat | null>(null)
  const [newGroupName, setNewGroupName] = useState('')
  const [renameGroupDialog, setRenameGroupDialog] = useState<ChatGroup | null>(null)
  const [renameGroupValue, setRenameGroupValue] = useState('')
  const [deleteGroupDialog, setDeleteGroupDialog] = useState<ChatGroup | null>(null)

  // ── Loading/error ──────────────────────────────────────────────────────────
  const [error, setError] = useState<string | null>(null)
  const [aiRenamingIds, setAiRenamingIds] = useState<Set<string>>(new Set())

  // ── Effects ────────────────────────────────────────────────────────────────

  useEffect(() => {
    loadHistory()
  }, [loadHistory])

  // Auto-focus search input when search mode activates
  useEffect(() => {
    if (searchMode) {
      const id = setTimeout(() => searchInputRef.current?.focus(), 50)
      return () => clearTimeout(id)
    }
  }, [searchMode])

  // ── Derived data ───────────────────────────────────────────────────────────

  const groups = history?.groups ?? []
  const allChats = history?.chat_history ?? []

  const chatById = useMemo(() => {
    const m = new Map<string, Chat>()
    allChats.forEach((c) => m.set(c.chat_id, c))
    return m
  }, [allChats])

  const sortedItems = useMemo(
    () => organizeAndSortAllItems(allChats, groups),
    [allChats, groups],
  )

  // ── Search ─────────────────────────────────────────────────────────────────

  const handleSearchChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const q = e.target.value
    setSearchQuery(q)
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current)
    if (!q.trim()) {
      setSearchResults([])
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

  const exitSearchMode = useCallback(() => {
    setSearchMode(false)
    setSearchQuery('')
    setSearchResults([])
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current)
  }, [])

  // ── New chat (FAB) ─────────────────────────────────────────────────────────

  const handleNewChat = async () => {
    try {
      const chat = await chatsApi.create({
        previewName: t('new_chat'),
        systemPrompt: '',
        groupId: null,
      })
      await loadHistory()
      navigate(`/chat/${chat.chat_id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה ביצירת שיחה')
    }
  }

  // ── Rename chat ────────────────────────────────────────────────────────────

  const openRenameDialog = (chat: Chat) => {
    setChatMenu(null)
    setRenameValue(chat.preview_name)
    setRenameDialog(chat)
  }

  const handleRenameConfirm = async () => {
    if (!renameDialog || !renameValue.trim()) return
    try {
      await chatsApi.update(renameDialog.chat_id, {
        previewName: renameValue.trim(),
      })
      await loadHistory()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה בשינוי שם')
    } finally {
      setRenameDialog(null)
    }
  }

  // ── AI rename ──────────────────────────────────────────────────────────────

  const handleAIRename = async (chat: Chat) => {
    setChatMenu(null)
    setAiRenamingIds((prev) => new Set([...prev, chat.chat_id]))
    try {
      await chatsApi.generateTitle(chat.chat_id)
      await loadHistory()
    } catch {
      // silently fail — name stays unchanged
    } finally {
      setAiRenamingIds((prev) => {
        const next = new Set(prev)
        next.delete(chat.chat_id)
        return next
      })
    }
  }

  // ── Share / export chat — opens the export dialog (selectAndExportChat) ───

  const handleShareChat = (chat: Chat) => {
    setChatMenu(null)
    setExportDialogChat(chat)
  }

  // ── Delete chat ────────────────────────────────────────────────────────────

  const openDeleteDialog = (chat: Chat) => {
    setChatMenu(null)
    setDeleteDialog(chat)
  }

  const handleDeleteConfirm = async () => {
    if (!deleteDialog) return
    try {
      await chatsApi.delete(deleteDialog.chat_id)
      await loadHistory()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה במחיקת שיחה')
    } finally {
      setDeleteDialog(null)
    }
  }

  // ── Group membership ───────────────────────────────────────────────────────

  const handleAddToGroup = async (groupId: string, chat: Chat) => {
    setChatMenu(null)
    setChatMenuGroupSubmenu(false)
    try {
      await groupsApi.addChat(groupId, chat.chat_id)
      await loadHistory()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה בהוספה לקבוצה')
    }
  }

  const handleRemoveFromGroup = async (chat: Chat) => {
    setChatMenu(null)
    if (!chat.group) return
    try {
      await groupsApi.removeChat(chat.group, chat.chat_id)
      await loadHistory()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה בהסרה מקבוצה')
    }
  }

  // ── Create group ───────────────────────────────────────────────────────────

  const openCreateGroupDialog = (chat: Chat | null = null) => {
    setChatMenu(null)
    setChatMenuGroupSubmenu(false)
    setCreateGroupForChat(chat)
    setNewGroupName('')
    setCreateGroupDialog(true)
  }

  const handleCreateGroupConfirm = async () => {
    if (!newGroupName.trim()) return
    try {
      const group = await groupsApi.create(newGroupName.trim())
      if (createGroupForChat) {
        await groupsApi.addChat(group.group_id, createGroupForChat.chat_id)
      }
      await loadHistory()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה ביצירת קבוצה')
    } finally {
      setCreateGroupDialog(false)
      setCreateGroupForChat(null)
    }
  }

  // ── Rename group ───────────────────────────────────────────────────────────

  const openRenameGroupDialog = (group: ChatGroup) => {
    setGroupMenu(null)
    setRenameGroupValue(group.group_name)
    setRenameGroupDialog(group)
  }

  const handleRenameGroupConfirm = async () => {
    if (!renameGroupDialog || !renameGroupValue.trim()) return
    try {
      await groupsApi.update(renameGroupDialog.group_id, {
        groupName: renameGroupValue.trim(),
      })
      await loadHistory()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה בשינוי שם קבוצה')
    } finally {
      setRenameGroupDialog(null)
    }
  }

  // ── Make group a project ───────────────────────────────────────────────────

  const handleMakeProject = async (group: ChatGroup) => {
    setGroupMenu(null)
    try {
      await groupsApi.update(group.group_id, { isProject: true })
      await loadHistory()
    } catch {
      // silently fail
    }
  }

  // ── New conversation in group ──────────────────────────────────────────────

  const handleNewConversationInGroup = async (group: ChatGroup) => {
    setGroupMenu(null)
    try {
      const chat = await chatsApi.create({
        previewName: t('new_chat'),
        systemPrompt: '',
        groupId: group.group_id,
      })
      await loadHistory()
      navigate(`/chat/${chat.chat_id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה ביצירת שיחה')
    }
  }

  // ── Delete group ───────────────────────────────────────────────────────────

  const openDeleteGroupDialog = (group: ChatGroup) => {
    setGroupMenu(null)
    setDeleteGroupDialog(group)
  }

  const handleDeleteGroupConfirm = async () => {
    if (!deleteGroupDialog) return
    try {
      await groupsApi.delete(deleteGroupDialog.group_id)
      await loadHistory()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'שגיאה במחיקת קבוצה')
    } finally {
      setDeleteGroupDialog(null)
    }
  }

  // ── Toggle group expansion ─────────────────────────────────────────────────

  const toggleGroup = useCallback((groupId: string) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev)
      if (next.has(groupId)) next.delete(groupId)
      else next.add(groupId)
      return next
    })
  }, [])

  // ── Context menu position helper ───────────────────────────────────────────

  const safeMenuPos = (x: number, y: number) => ({
    top: Math.min(y, (typeof window !== 'undefined' ? window.innerHeight : 768) - 320),
    left: Math.min(x, (typeof window !== 'undefined' ? window.innerWidth : 1024) - 224),
  })

  // ─── Loading skeleton ─────────────────────────────────────────────────────

  if (loading && !history) {
    return (
      <div className={styles.page}>
        <div className={styles.emptyState}>
          <div className={styles.loadingSpinner} />
        </div>
      </div>
    )
  }

  // ─── Main render ──────────────────────────────────────────────────────────

  return (
    <div className={styles.page}>
      {/* ── Top Bar ── */}
      {/*
        Force LTR so the logo is always on the left and icons on the right,
        matching the Android app's CompositionLocalProvider(LayoutDirection.Ltr) wrapper.
      */}
      <div className={styles.topBar} dir="ltr">
        <span className={styles.topBarLogo} aria-hidden="true">
          <b>A</b>p<b>I</b>
        </span>

        {searchMode ? (
          /* Search mode: full-width input */
          <div className={styles.searchField} dir="rtl">
            <input
              ref={searchInputRef}
              className={styles.searchInput}
              placeholder={t('search_placeholder')}
              value={searchQuery}
              onChange={handleSearchChange}
              aria-label={t('search_placeholder')}
            />
            <button
              className={styles.searchFieldClearBtn}
              onClick={() => {
                if (searchQuery) {
                  setSearchQuery('')
                  setSearchResults([])
                } else {
                  exitSearchMode()
                }
              }}
              aria-label={t('close_search')}
            >
              <MdClose size={18} />
            </button>
          </div>
        ) : (
          /* Normal mode: icon row */
          <div className={styles.topBarActions}>
            <IconButton
              icon={MdSearch}
              onClick={() => setSearchMode(true)}
              aria-label="חיפוש"
            />
            <IconButton
              icon={MdKey}
              onClick={() => navigate('/keys')}
              aria-label={t('api_keys')}
            />
            <IconButton
              icon={MdSettings}
              onClick={() => navigate('/settings')}
              aria-label={t('settings')}
            />
          </div>
        )}
      </div>

      {/* ── Error banner ── */}
      {error && (
        <div className={styles.errorBanner} role="alert">
          <span>{error}</span>
          <button
            onClick={() => setError(null)}
            aria-label="סגור שגיאה"
            className={styles.errorCloseBtn}
          >
            <MdClose size={14} />
          </button>
        </div>
      )}

      {/* ── Scrollable list ── */}
      <div className={styles.list}>
        {searchMode ? (
          /* ── Search mode content ── */
          searchQuery === '' ? (
            <div className={styles.emptyState}>
              <MdSearch size={64} className={styles.emptyIcon} />
              <p className={styles.emptyTitle}>{t('search_enter_hint')}</p>
              <p className={styles.emptySubtitle}>{t('search_content_hint')}</p>
            </div>
          ) : searching ? (
            <div className={styles.emptyState}>
              <div className={styles.loadingSpinner} />
            </div>
          ) : searchResults.length === 0 ? (
            <div className={styles.emptyState}>
              <MdSearch size={64} className={styles.emptyIcon} />
              <p className={styles.emptyTitle}>{t('no_results_found')}</p>
              <p className={styles.emptySubtitle}>{t('try_different_search')}</p>
            </div>
          ) : (
            searchResults.map((result) => {
              const chat = chatById.get(result.chatId)
              const model = chat ? getChatModel(chat) : ''
              const logoPath = getModelLogoPath(model)
              const initial = getModelInitial(model)
              return (
                <div
                  key={`${result.chatId}-${result.messageIndex}`}
                  className={styles.chatCard}
                  role="listitem"
                  onClick={() => navigate(`/chat/${result.chatId}`)}
                  onContextMenu={(e) => {
                    if (chat) {
                      e.preventDefault()
                      setChatMenu({ x: e.clientX, y: e.clientY, chat })
                    }
                  }}
                >
                  <div className={styles.chatCardInner}>
                    <div className={styles.avatar}>
                      {logoPath ? (
                        <img src={logoPath} alt="Model Logo" className={styles.avatarLogo} />
                      ) : (
                        <span className={styles.avatarText}>{initial}</span>
                      )}
                    </div>
                    <div className={styles.chatContent}>
                      <span className={styles.chatTitle}>{result.chatTitle}</span>
                      {result.snippet && (
                        <span className={styles.chatPreview}>{result.snippet}</span>
                      )}
                    </div>
                    <div className={styles.chatMeta}>
                      <span className={styles.matchType}>
                        {result.matchType?.toLowerCase()}
                      </span>
                    </div>
                  </div>
                </div>
              )
            })
          )
        ) : (
          /* ── Normal mode content ── */
          sortedItems.length === 0 ? (
            <div className={styles.emptyState}>
              <MdForum size={64} className={styles.emptyIcon} />
              <p className={styles.emptyTitle}>{t('no_chats_yet')}</p>
              <p className={styles.emptySubtitle}>{t('start_new_chat_hint')}</p>
            </div>
          ) : (
            sortedItems.map((entry) => {
              if (entry.type === 'group') {
                const { group, chats: groupChats } = entry
                const isExpanded = expandedGroups.has(group.group_id)
                const sortedGroupChats = [...groupChats].sort(
                  (a, b) =>
                    (getLastTimestamp(b) ?? 0) - (getLastTimestamp(a) ?? 0),
                )
                return (
                  <React.Fragment key={`group-${group.group_id}`}>
                    <GroupItemCard
                      group={group}
                      chatCount={groupChats.length}
                      isExpanded={isExpanded}
                      onGroupClick={() =>
                        navigate(`/groups/${group.group_id}`)
                      }
                      onToggleExpansion={() => toggleGroup(group.group_id)}
                      onContextMenu={(x, y) =>
                        setGroupMenu({ x, y, group })
                      }
                    />
                    {isExpanded &&
                      sortedGroupChats.map((chat) => (
                        <ChatItemCard
                          key={`grouped-${chat.chat_id}`}
                          chat={chat}
                          isAIRenaming={aiRenamingIds.has(chat.chat_id)}
                          onClick={() => navigate(`/chat/${chat.chat_id}`)}
                          onContextMenu={(x, y) =>
                            setChatMenu({ x, y, chat })
                          }
                          indented
                        />
                      ))}
                  </React.Fragment>
                )
              } else {
                const { chat } = entry
                return (
                  <ChatItemCard
                    key={`chat-${chat.chat_id}`}
                    chat={chat}
                    isAIRenaming={aiRenamingIds.has(chat.chat_id)}
                    onClick={() => navigate(`/chat/${chat.chat_id}`)}
                    onContextMenu={(x, y) => setChatMenu({ x, y, chat })}
                  />
                )
              }
            })
          )
        )}
      </div>

      {/* ── FAB (new chat) ── */}
      {!searchMode && (
        <button
          className={styles.fab}
          onClick={handleNewChat}
          aria-label={t('new_chat')}
        >
          <MdAdd size={24} />
        </button>
      )}

      {/* ── Chat context menu ── */}
      {chatMenu &&
        ReactDOM.createPortal(
          <>
            <div
              className={styles.menuBackdrop}
              onClick={() => {
                setChatMenu(null)
                setChatMenuGroupSubmenu(false)
              }}
            />
            <div
              className={styles.contextMenu}
              style={safeMenuPos(chatMenu.x, chatMenu.y)}
            >
              <button
                className={styles.menuItem}
                onClick={() => openRenameDialog(chatMenu.chat)}
              >
                <MdEdit size={16} className={styles.menuItemIcon} />
                {t('update_chat_name')}
              </button>

              <button
                className={styles.menuItem}
                onClick={() => handleAIRename(chatMenu.chat)}
              >
                <MdBuild
                  size={16}
                  className={styles.menuItemIcon}
                  style={{ color: 'var(--accent-blue)' }}
                />
                {t('update_chat_name_ai')}
              </button>

              <button
                className={styles.menuItem}
                onClick={() => handleShareChat(chatMenu.chat)}
              >
                <MdShare size={16} className={styles.menuItemIcon} />
                {t('share_chat')}
              </button>

              <div className={styles.menuDivider} />

              {chatMenu.chat.group ? (
                <button
                  className={styles.menuItem}
                  onClick={() => handleRemoveFromGroup(chatMenu.chat)}
                >
                  <MdRemove
                    size={16}
                    className={styles.menuItemIcon}
                    style={{ color: 'var(--on-surface-variant)' }}
                  />
                  {t('remove_from_group')}
                </button>
              ) : (
                <>
                  <button
                    className={styles.menuItem}
                    onClick={() =>
                      setChatMenuGroupSubmenu((v) => !v)
                    }
                  >
                    <MdAdd size={16} className={styles.menuItemIcon} />
                    {t('add_to_group')}
                    <MdExpandMore
                      size={14}
                      style={{ marginInlineStart: 'auto', opacity: 0.6 }}
                    />
                  </button>
                  {chatMenuGroupSubmenu && (
                    <div className={styles.submenu}>
                      {groups.map((g) => (
                        <button
                          key={g.group_id}
                          className={styles.menuItem}
                          onClick={() =>
                            handleAddToGroup(g.group_id, chatMenu.chat)
                          }
                        >
                          <MdFolder
                            size={16}
                            className={styles.menuItemIcon}
                            style={{ color: 'var(--secondary)' }}
                          />
                          {g.group_name}
                        </button>
                      ))}
                      <div className={styles.menuDivider} />
                      <button
                        className={styles.menuItem}
                        onClick={() => openCreateGroupDialog(chatMenu.chat)}
                        style={{ color: 'var(--primary)' }}
                      >
                        <MdAdd
                          size={16}
                          className={styles.menuItemIcon}
                          style={{ color: 'var(--primary)' }}
                        />
                        {t('create_new_group')}
                      </button>
                    </div>
                  )}
                </>
              )}

              <div className={styles.menuDivider} />

              <button
                className={`${styles.menuItem} ${styles.menuItemDanger}`}
                onClick={() => openDeleteDialog(chatMenu.chat)}
              >
                <MdDelete size={16} className={styles.menuItemIcon} />
                {t('delete_chat')}
              </button>
            </div>
          </>,
          document.body,
        )}

      {/* ── Group context menu ── */}
      {groupMenu &&
        ReactDOM.createPortal(
          <>
            <div
              className={styles.menuBackdrop}
              onClick={() => setGroupMenu(null)}
            />
            <div
              className={styles.contextMenu}
              style={safeMenuPos(groupMenu.x, groupMenu.y)}
            >
              <button
                className={styles.menuItem}
                onClick={() => openRenameGroupDialog(groupMenu.group)}
              >
                <MdEdit size={16} className={styles.menuItemIcon} />
                {t('rename_group')}
              </button>

              <button
                className={styles.menuItem}
                onClick={() => handleMakeProject(groupMenu.group)}
              >
                <MdStar
                  size={16}
                  className={styles.menuItemIcon}
                  style={{ color: 'var(--accent-blue)' }}
                />
                {t('make_project')}
              </button>

              <div className={styles.menuDivider} />

              <button
                className={styles.menuItem}
                onClick={() =>
                  handleNewConversationInGroup(groupMenu.group)
                }
              >
                <MdAdd size={16} className={styles.menuItemIcon} />
                {t('new_chat')}
              </button>

              <div className={styles.menuDivider} />

              <button
                className={`${styles.menuItem} ${styles.menuItemDanger}`}
                onClick={() => openDeleteGroupDialog(groupMenu.group)}
              >
                <MdDelete size={16} className={styles.menuItemIcon} />
                {t('delete_group_scatter')}
              </button>
            </div>
          </>,
          document.body,
        )}

      {/* ── Rename chat dialog ── */}
      <Dialog
        open={!!renameDialog}
        onClose={() => setRenameDialog(null)}
        title={t('rename')}
        actions={
          <>
            <DialogButton
              label={t('cancel')}
              onClick={() => setRenameDialog(null)}
            />
            <DialogButton
              label={t('save')}
              primary
              onClick={handleRenameConfirm}
              disabled={!renameValue.trim()}
            />
          </>
        }
      >
        <input
          className={styles.dialogInput}
          value={renameValue}
          onChange={(e) => setRenameValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleRenameConfirm()
          }}
          placeholder={t('chat_title')}
          autoFocus
        />
      </Dialog>

      {/* ── Delete chat dialog ── */}
      <Dialog
        open={!!deleteDialog}
        onClose={() => setDeleteDialog(null)}
        title={t('delete_confirmation_title')}
        actions={
          <>
            <DialogButton
              label={t('cancel')}
              onClick={() => setDeleteDialog(null)}
            />
            <DialogButton
              label={t('delete')}
              danger
              onClick={handleDeleteConfirm}
            />
          </>
        }
      >
        {t('delete_confirmation_message')}
      </Dialog>

      {/* ── Chat export/share dialog ── */}
      <ChatExportDialog
        open={!!exportDialogChat}
        chat={exportDialogChat}
        onClose={() => setExportDialogChat(null)}
        onChatUpdated={() => { loadHistory() }}
      />

      {/* ── Create group dialog ── */}
      <Dialog
        open={createGroupDialog}
        onClose={() => setCreateGroupDialog(false)}
        title={t('create_group_title')}
        actions={
          <>
            <DialogButton
              label={t('cancel')}
              onClick={() => setCreateGroupDialog(false)}
            />
            <DialogButton
              label={t('create')}
              primary
              onClick={handleCreateGroupConfirm}
              disabled={!newGroupName.trim()}
            />
          </>
        }
      >
        <input
          className={styles.dialogInput}
          value={newGroupName}
          onChange={(e) => setNewGroupName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleCreateGroupConfirm()
          }}
          placeholder={t('group_name_label')}
          autoFocus
        />
      </Dialog>

      {/* ── Rename group dialog ── */}
      <Dialog
        open={!!renameGroupDialog}
        onClose={() => setRenameGroupDialog(null)}
        title={t('rename_group')}
        actions={
          <>
            <DialogButton
              label={t('cancel')}
              onClick={() => setRenameGroupDialog(null)}
            />
            <DialogButton
              label={t('save')}
              primary
              onClick={handleRenameGroupConfirm}
              disabled={
                !renameGroupValue.trim() ||
                renameGroupValue === renameGroupDialog?.group_name
              }
            />
          </>
        }
      >
        <input
          className={styles.dialogInput}
          value={renameGroupValue}
          onChange={(e) => setRenameGroupValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleRenameGroupConfirm()
          }}
          placeholder={t('new_group_name_label')}
          autoFocus
        />
      </Dialog>

      {/* ── Delete group dialog ── */}
      <Dialog
        open={!!deleteGroupDialog}
        onClose={() => setDeleteGroupDialog(null)}
        title={t('delete_group_title')}
        actions={
          <>
            <DialogButton
              label={t('cancel')}
              onClick={() => setDeleteGroupDialog(null)}
            />
            <DialogButton
              label={t('delete')}
              danger
              onClick={handleDeleteGroupConfirm}
            />
          </>
        }
      >
        {`האם אתה בטוח שברצונך למחוק את הקבוצה "${deleteGroupDialog?.group_name}"? ${t('delete_group_message')}`}
      </Dialog>
    </div>
  )
}

export default ChatHistoryPage
