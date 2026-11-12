package com.example.ApI.data.model

/**
 * A message that only carries files has an empty text. It used to be stored with a stand-in
 * text, and chats saved before that (or written by a device on an older version) still have
 * it: every reader goes through here, so the stand-in is never shown and never sent to a model.
 */
object FileOnlyMessages {
    /** The stand-in texts older versions stored (Android / web / cloud runs, and desktop). */
    val LEGACY_PLACEHOLDERS = setOf("[קובץ מצורף]", "[Attached file]")

    /** Whether [text] is nothing but a stand-in for "this message has files". */
    fun isPlaceholder(text: String): Boolean = text.trim() in LEGACY_PLACEHOLDERS

    /** The text of a message with these [attachments] as a person or a model is to read it. */
    fun textOf(role: String, text: String, hasAttachments: Boolean): String =
        if (hasAttachments && (role == "user" || role == "assistant") && isPlaceholder(text)) "" else text

    /** What stands for [attachments] where one line has to say what a message is. */
    fun label(attachments: List<Attachment>): String =
        if (attachments.isEmpty()) "" else "📎 " + attachments.joinToString(", ") { it.file_name }
}

/** The message's own text: empty for a message that only carries files. */
val Message.displayText: String
    get() = FileOnlyMessages.textOf(role, text, attachments.isNotEmpty())

/** One line for a list of chats or of search results: the text, else the names of the files. */
val Message.previewText: String
    get() = displayText.ifBlank { FileOnlyMessages.label(attachments) }

/**
 * The messages as a provider is to be given them: no stand-in text, and no assistant message
 * that says nothing (no text and no thoughts - one that only carried generated files, or an
 * empty reply). Such a message would be an empty turn, which several APIs reject, and between
 * two tool calls it would split one step in two. The same stored chat always gives the same
 * list (a prompt cache's prefix); a message that needs no change is the same object.
 */
fun List<Message>.forProvider(): List<Message> = mapNotNull { message ->
    val text = message.displayText
    when {
        message.role == "assistant" && message.toolCall == null &&
            text.isBlank() && message.thoughts.isNullOrBlank() -> null
        text != message.text -> message.copy(text = text)
        else -> message
    }
}
