/**
 * ChatPicker — choose a chat to put something in (a forwarded image), as a full screen
 * (mirrors Android's ChatPicker.kt): a top bar with back and the title, and one flat list —
 * "new chat", then the chats, most recent first — whose rows look like the history page's,
 * each ending in a circle. A click selects a row (one at a time; a second click clears it),
 * and the button that then appears at the bottom confirms: `onPick` gets the chosen chat,
 * null for a new one. Fills the (positioned) element it is placed in.
 */
import React, { useMemo, useState } from 'react'
import type { Chat } from '../../api/types'
import { MdAdd, MdCheck, MdSend } from '../../ui/icons'
import ScreenTopBar from '../ScreenTopBar'
import { ChatRowBody, chatTitle, getLastTimestamp } from './ChatRow'
import { t } from '../../i18n/he'
import rowStyles from '../../pages/ChatHistoryPage.module.css'
import styles from './ChatPicker.module.css'

/** What is selected when it is "new chat" (no chat has this id). */
const NEW_CHAT_KEY = '\u0000new_chat'

interface ChatPickerProps {
  title: string
  /** The chats to choose from, in any order */
  chats: Chat[]
  /** The choice is being carried out: nothing can be changed meanwhile */
  busy?: boolean
  error?: string | null
  onDismiss: () => void
  /** The confirmed choice — null: a new chat */
  onPick: (chat: Chat | null) => void
}

const ChatPicker: React.FC<ChatPickerProps> = ({ title, chats, busy = false, error, onDismiss, onPick }) => {
  const sorted = useMemo(
    () => [...chats].sort(
      (a, b) => (getLastTimestamp(b) ?? Number.MIN_SAFE_INTEGER) - (getLastTimestamp(a) ?? Number.MIN_SAFE_INTEGER),
    ),
    [chats],
  )
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  // A selected chat that is no longer listed is not a selection
  const selectedChat = sorted.find((c) => c.chat_id === selectedKey) ?? null
  const hasSelection = selectedKey === NEW_CHAT_KEY || selectedChat !== null
  const toggle = (key: string) => setSelectedKey((current) => (current === key ? null : key))

  const row = (key: string, label: string, body: React.ReactNode) => {
    const selected = selectedKey === key
    return (
      <div
        key={key}
        role="radio"
        aria-checked={selected}
        aria-label={label}
        tabIndex={0}
        className={`${rowStyles.chatCard} ${styles.row}${selected ? ` ${styles.rowSelected}` : ''}`}
        onClick={() => toggle(key)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            toggle(key)
          }
        }}
      >
        <div className={rowStyles.chatCardInner}>
          {body}
          <span className={`${styles.circle}${selected ? ` ${styles.circleSelected}` : ''}`} aria-hidden="true">
            {selected && <MdCheck size={16} />}
          </span>
        </div>
      </div>
    )
  }

  return (
    <div className={styles.screen}>
      <div className={styles.column} data-testid="chat-picker">
        <ScreenTopBar title={title} onBack={onDismiss} />
        {error && <div role="alert" className={styles.error}>{error}</div>}
        <div
          role="radiogroup"
          aria-label={title}
          className={`${styles.list}${busy ? ` ${styles.listBusy}` : ''}`}
        >
          {row(
            NEW_CHAT_KEY,
            t('new_chat'),
            <>
              <div className={rowStyles.avatar}>
                <MdAdd size={24} className={styles.avatarIcon} />
              </div>
              <div className={rowStyles.chatContent}>
                <span className={rowStyles.chatTitle}>{t('new_chat')}</span>
              </div>
            </>,
          )}
          {sorted.map((chat) => row(chat.chat_id, chatTitle(chat), <ChatRowBody chat={chat} />))}
        </div>
        {hasSelection && (
          <button
            type="button"
            className={styles.confirm}
            onClick={() => onPick(selectedChat)}
            disabled={busy}
            title={t('chat_picker_confirm')}
            aria-label={t('chat_picker_confirm')}
          >
            {busy ? <span className={styles.spinner} /> : <MdSend size={24} />}
          </button>
        )}
      </div>
    </div>
  )
}

export default ChatPicker
