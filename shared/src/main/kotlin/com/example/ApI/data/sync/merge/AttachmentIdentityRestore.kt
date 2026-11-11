package com.example.ApI.data.sync.merge

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.UserChatHistory

/**
 * Keeps an attachment's store identity (`sha256` + `size`) across merges.
 *
 * The chat merge works on whole messages, and a client older than the two fields rewrites the
 * history without them: a 3-way merge would read that as "cleared" and drop the hashes of every
 * chat the old client saved. A hash cannot be cleared on purpose (it is derived from the file),
 * so an attachment that lacks it takes it from the same attachment in another copy of the
 * history — same chat (or group), same message id, same `local_file_path`, name and mime type.
 *
 * Used twice by [ChatHistoryMerger.merge]:
 * - before the merge, each side is restored **from the base only**: this only makes a side look
 *   more like the base, so an old client's rewrite is no longer a "modification" (which would
 *   also beat a deletion made on the other device);
 * - after the merge, the result is restored from all three copies, so a hash that only the
 *   losing side of a message conflict knew (e.g. one device recorded the hash while the other
 *   wrote a provider file id) survives.
 * The merged file then differs from the remote copy, so it is uploaded and the server's copy is
 * repaired too (same idea as `SyncFileMerger.restoreDropped` for typed files).
 */
internal object AttachmentIdentityRestore {

    private data class Key(val owner: String, val messageId: String, val path: String?, val name: String, val mime: String)

    class Index internal constructor(private val known: Map<Any, Attachment>) {
        val isEmpty: Boolean get() = known.isEmpty()
        internal fun find(key: Any): Attachment? = known[key]
    }

    private fun key(owner: String, messageId: String, a: Attachment) =
        Key(owner, messageId, a.local_file_path, a.file_name, a.mime_type)

    private fun chatOwner(chat: Chat) = "chat:${chat.chat_id}"
    private fun groupOwner(group: ChatGroup) = "group:${group.group_id}"

    private inline fun forEachMessage(chat: Chat, action: (Message) -> Unit) {
        chat.messages.forEach(action)
        for (node in chat.messageNodes) for (variant in node.variants) {
            action(variant.userMessage)
            variant.responses.forEach(action)
        }
    }

    /** Every attachment with a hash in [histories]; earlier histories win. */
    fun index(vararg histories: UserChatHistory?): Index {
        val known = HashMap<Any, Attachment>()
        for (history in histories) {
            if (history == null) continue
            for (chat in history.chat_history) {
                val owner = chatOwner(chat)
                forEachMessage(chat) { m ->
                    for (a in m.attachments) if (a.sha256 != null) known.putIfAbsent(key(owner, m.id, a), a)
                }
            }
            for (group in history.groups) {
                val owner = groupOwner(group)
                for (a in group.group_attachments) if (a.sha256 != null) known.putIfAbsent(key(owner, "", a), a)
            }
        }
        return Index(known)
    }

    /** [history] with missing hashes filled from [index]; the same instance when nothing was missing. */
    fun restore(history: UserChatHistory, index: Index): UserChatHistory {
        if (index.isEmpty) return history
        var changed = false
        val chats = history.chat_history.map { chat -> restoreChat(chat, index).also { if (it !== chat) changed = true } }
        val groups = history.groups.map { group ->
            val attachments = restoreAttachments(groupOwner(group), "", group.group_attachments, index)
            if (attachments === group.group_attachments) group
            else group.copy(group_attachments = attachments).also { changed = true }
        }
        return if (changed) history.copy(chat_history = chats, groups = groups) else history
    }

    private fun restoreChat(chat: Chat, index: Index): Chat {
        val owner = chatOwner(chat)
        var needed = false
        forEachMessage(chat) { m ->
            if (!needed && m.attachments.any { it.sha256 == null && index.find(key(owner, m.id, it)) != null }) needed = true
        }
        if (!needed) return chat
        fun fix(m: Message): Message {
            val attachments = restoreAttachments(owner, m.id, m.attachments, index)
            return if (attachments === m.attachments) m else m.copy(attachments = attachments)
        }
        return chat.copy(
            messages = chat.messages.map(::fix),
            messageNodes = chat.messageNodes.map { node ->
                node.copy(variants = node.variants.map { v ->
                    v.copy(userMessage = fix(v.userMessage), responses = v.responses.map(::fix))
                })
            }
        )
    }

    private fun restoreAttachments(owner: String, messageId: String, attachments: List<Attachment>, index: Index): List<Attachment> {
        if (attachments.none { it.sha256 == null }) return attachments
        var changed = false
        val out = attachments.map { a ->
            val source = if (a.sha256 == null) index.find(key(owner, messageId, a)) else null
            if (source == null) a else a.copy(sha256 = source.sha256, size = source.size).also { changed = true }
        }
        return if (changed) out else attachments
    }
}
