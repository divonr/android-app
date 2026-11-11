package com.example.ApI.data.repository

import com.example.ApI.data.attachments.AttachmentHash
import com.example.ApI.data.attachments.FileDigest
import com.example.ApI.data.attachments.withDigest
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.UserChatHistory

/** Where an attachment is stored in the chat history. */
sealed class AttachmentOwner {
    /** The message [messageId] of chat [chatId] (every copy of it: the path and the tree). */
    data class ChatMessage(val chatId: String, val messageId: String) : AttachmentOwner()

    /** The project attachments of group [groupId]. */
    data class Group(val groupId: String) : AttachmentOwner()
}

/**
 * Changes to attachments that are already stored in the chat history (CLOUD_RUNS_PLAN.md,
 * "Attachment store"): what a device learns about a file after the message was saved — its
 * identity (hash + size, the lazy backfill) and the ids a provider gave it.
 *
 * Every change is one locked load-modify-save of the chat history ([ChatHistoryManager.modifyChatHistory])
 * that touches only the fields it is about: in particular it never writes a `local_file_path`,
 * which belongs to the device that attached the file.
 */
class StoredAttachments(private val chatHistoryManager: ChatHistoryManager) {

    /**
     * Apply [transform] to every attachment of [owner]. Returns how many stored attachments it
     * changed (0: no such chat / message / group, or nothing to change).
     */
    fun update(username: String, owner: AttachmentOwner, transform: (Attachment) -> Attachment): Int =
        chatHistoryManager.modifyChatHistory(username) { history ->
            var changed = 0
            fun List<Attachment>.updated(): List<Attachment> = map { stored ->
                val result = transform(stored)
                if (result != stored) changed++
                result
            }
            val updated = when (owner) {
                is AttachmentOwner.ChatMessage -> history.withMessage(owner) { it.copy(attachments = it.attachments.updated()) }
                is AttachmentOwner.Group -> history.copy(groups = history.groups.map { group ->
                    if (group.group_id != owner.groupId || group.group_attachments.isEmpty()) group
                    else group.copy(group_attachments = group.group_attachments.updated())
                })
            }
            (if (changed == 0) history else updated) to changed
        }

    /**
     * Record the identity of [attachment]'s file on the stored attachment: the one without a hash
     * that has the same `local_file_path`, name and mime type (an attachment that already has a
     * hash is never changed). Returns whether anything was written.
     */
    fun recordIdentity(username: String, owner: AttachmentOwner, attachment: Attachment, digest: FileDigest): Boolean {
        if (!AttachmentHash.isValid(digest.sha256)) return false
        return update(username, owner) { stored ->
            if (stored.sha256 == null && sameDescription(stored, attachment)) stored.withDigest(digest) else stored
        } > 0
    }

    /**
     * Record the provider ids of [uploaded] (`file_OPENAI_id`, `file_POE_url`, `file_GOOGLE_uri`;
     * only the ones that are set) on the stored attachment that is the same file as [attachment]:
     * the same hash when both have one, else the same `local_file_path`, name and mime type.
     * Nothing else of [uploaded] is read. Returns whether anything was written.
     */
    fun recordProviderIds(username: String, owner: AttachmentOwner, attachment: Attachment, uploaded: Attachment): Boolean =
        update(username, owner) { stored ->
            if (!sameFile(stored, attachment)) stored else stored.withProviderIdsOf(uploaded)
        } > 0

    /** [change] applied to every copy of the owner's message: in `messages` and in the tree. */
    private fun UserChatHistory.withMessage(owner: AttachmentOwner.ChatMessage, change: (Message) -> Message): UserChatHistory {
        fun Message.changed(): Message = if (id == owner.messageId && attachments.isNotEmpty()) change(this) else this
        return copy(chat_history = chat_history.map { chat ->
            if (chat.chat_id != owner.chatId) chat
            else chat.copy(
                messages = chat.messages.map { it.changed() },
                messageNodes = chat.messageNodes.map { node ->
                    node.copy(variants = node.variants.map { variant ->
                        variant.copy(
                            userMessage = variant.userMessage.changed(),
                            responses = variant.responses.map { it.changed() }
                        )
                    })
                }
            )
        })
    }
}

internal fun sameDescription(a: Attachment, b: Attachment): Boolean =
    a.local_file_path == b.local_file_path && a.file_name == b.file_name && a.mime_type == b.mime_type

/** The same file: the same hash when both have one, else the same path, name and mime type. */
internal fun sameFile(stored: Attachment, requested: Attachment): Boolean =
    if (stored.sha256 != null && requested.sha256 != null) stored.sha256 == requested.sha256
    else sameDescription(stored, requested)

/** [this] with the provider ids of [uploaded] (only the ones that are set); nothing else changes. */
internal fun Attachment.withProviderIdsOf(uploaded: Attachment): Attachment = copy(
    file_OPENAI_id = uploaded.file_OPENAI_id?.takeIf { it.isNotBlank() } ?: file_OPENAI_id,
    file_POE_url = uploaded.file_POE_url?.takeIf { it.isNotBlank() } ?: file_POE_url,
    file_GOOGLE_uri = uploaded.file_GOOGLE_uri?.takeIf { it.isNotBlank() } ?: file_GOOGLE_uri
)
