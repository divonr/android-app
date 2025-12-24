package com.example.ApI.ui.screen

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Scrolls to the previous message (one message up) in the chat.
 * Aligns the beginning of the message to the top of the screen.
 */
suspend fun scrollToPreviousMessage(
    listState: LazyListState,
    messages: List<Message>?,
    isStreaming: Boolean = false,
    showReplyButton: Boolean = false
) {
    if (messages.isNullOrEmpty()) return

    val currentIndex = listState.firstVisibleItemIndex

    // Account for streaming message and reply button at the top
    val streamingOffset = if (isStreaming) 1 else 0
    val replyOffset = if (showReplyButton) 1 else 0
    val totalOffset = streamingOffset + replyOffset

    // Calculate the message index we want to scroll to
    // Messages are displayed in reverse order (newest first)
    val targetMessageIndex = currentIndex - totalOffset + 1

    // Make sure we don't go beyond the available messages
    val maxMessageIndex = messages.size - 1 + totalOffset
    val targetIndex = minOf(targetMessageIndex, maxMessageIndex)

    if (targetIndex >= 0) {
        try {
            // Use scrollOffset = 0 to pin the top of the message to the top of the screen
            listState.animateScrollToItem(index = targetIndex, scrollOffset = 0)
        } catch (e: Exception) {
            // If scrolling fails, just scroll to the first item
            listState.animateScrollToItem(index = 0, scrollOffset = 0)
        }
    }
}

/**
 * Create highlighted text with search terms highlighted
 */
fun createHighlightedText(
    text: String,
    highlightRanges: List<IntRange>,
    highlightColor: Color
): AnnotatedString {
    if (highlightRanges.isEmpty() || text.isEmpty()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var lastIndex = 0

        // Sort ranges to process them in order
        val sortedRanges = highlightRanges.sortedBy { it.first }

        for (range in sortedRanges) {
            // Skip if range is out of bounds
            if (range.first >= text.length || range.last >= text.length) continue

            // Add text before highlight
            if (lastIndex < range.first) {
                append(text.substring(lastIndex, range.first))
            }

            // Add highlighted text with more visible highlighting
            withStyle(SpanStyle(background = highlightColor.copy(alpha = 0.6f))) {
                append(text.substring(range.first, minOf(range.last + 1, text.length)))
            }

            lastIndex = minOf(range.last + 1, text.length)
        }

        // Add remaining text
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

/**
 * Get a model initial abbreviation for display
 */
fun getModelInitial(model: String): String {
    return when {
        model.contains("gpt-4", ignoreCase = true) -> "G4"
        model.contains("gpt-3", ignoreCase = true) -> "G3"
        model.contains("claude", ignoreCase = true) -> "C"
        model.contains("gemini", ignoreCase = true) -> "Gm"
        model.contains("llama", ignoreCase = true) -> "L"
        model.contains("mistral", ignoreCase = true) -> "M"
        else -> model.take(1).uppercase()
    }
}

/**
 * Format a timestamp for display in the chat history
 */
fun formatTimestamp(timestamp: Long): String {
    val instant = Instant.ofEpochMilli(timestamp)
    val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()

    return when {
        date.isEqual(today) -> {
            val time = instant.atZone(ZoneId.systemDefault()).toLocalTime()
            time.format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        date.isEqual(today.minusDays(1)) -> "אתמול"
        ChronoUnit.DAYS.between(date, today) < 7 -> {
            val dayOfWeek = date.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL,
                java.util.Locale.forLanguageTag("he")
            )
            dayOfWeek
        }
        else -> date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }
}

/**
 * Get the timestamp of the last message in a chat, or null if no messages
 */
fun getLastTimestampOrNull(chat: Chat): Long? {
    val iso = chat.messages.lastOrNull()?.datetime ?: return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
