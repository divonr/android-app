/**
 * ChatRow — what a chat's row shows, on the history page and wherever else a chat is listed
 * (the chat picker). Mirrors Android's ChatRowContent (ChatHistoryItems.kt): the model's logo
 * or initial, the title over the last message, and the time of the last message over the
 * number of messages. To be placed in a `chatCardInner` of ChatHistoryPage.module.css.
 */
import React from 'react'
import type { Chat } from '../../api/types'
import { MdForum } from '../../ui/icons'
import { getModelInitial, getModelLogoPath, formatTimestamp } from '../../utils/chatUtils'
import { messagePreviewText } from '../../utils/attachments'
import { t } from '../../i18n/he'
import styles from '../../pages/ChatHistoryPage.module.css'

/** Mirror of ChatUtils.kt getLastTimestampOrNull */
export function getLastTimestamp(chat: Chat): number | null {
  const msgs = chat.messages
  if (!msgs || msgs.length === 0) return null
  const iso = msgs[msgs.length - 1].datetime
  if (!iso) return null
  const ts = Date.parse(iso)
  return isNaN(ts) ? null : ts
}

/** Get model from last assistant message (mirrors ChatHistoryItems.kt logic, incl. Chat.model fallback) */
export function getChatModel(chat: Chat): string {
  const reversed = [...(chat.messages ?? [])].reverse()
  const last = reversed.find((m) => m.role === 'assistant')
  return last?.model ?? 'gpt-4o'
}

export function chatTitle(chat: Chat): string {
  return chat.preview_name || t('new_chat')
}

export const ChatRowBody: React.FC<{ chat: Chat; isAIRenaming?: boolean }> = ({ chat, isAIRenaming = false }) => {
  const model = getChatModel(chat)
  const logoPath = getModelLogoPath(model)
  const initial = getModelInitial(model)
  const ts = getLastTimestamp(chat)
  const msgs = chat.messages ?? []
  const lastMsg = msgs[msgs.length - 1]

  return (
    <>
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
        <span className={styles.chatTitle}>{chatTitle(chat)}</span>
        {lastMsg && (
          <span className={styles.chatPreview}>
            {messagePreviewText(lastMsg).slice(0, 100)}
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
            {' '}
            {msgs.length}
          </span>
        )}
      </div>
    </>
  )
}
