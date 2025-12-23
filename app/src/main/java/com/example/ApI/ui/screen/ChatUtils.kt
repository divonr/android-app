package com.example.ApI.ui.screen

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.example.ApI.data.model.Message

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
