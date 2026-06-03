package com.example.ApI.data.repository

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.SearchResult
import com.example.ApI.data.model.SearchMatchType
import com.example.ApI.data.model.UserChatHistory

/**
 * Service for searching chats by title, content, and file names.
 */
class ChatSearchService(
    private val loadChatHistory: (String) -> UserChatHistory
) {
    /**
     * Search chats by title and content with highlighting information
     */
    fun searchChats(username: String, query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val chatHistory = loadChatHistory(username)
        val lowercaseQuery = query.lowercase()

        // Search results divided into categories
        val titleMatches = mutableListOf<SearchResult>()
        val contentMatches = mutableListOf<SearchResult>()
        val fileMatches = mutableListOf<SearchResult>()

        for (chat in chatHistory.chat_history) {
            var foundInTitle = false

            // Check title match - use preview_name as the title
            val titleText = chat.preview_name ?: "שיחה חדשה"
            val titleLowercase = titleText.lowercase()
            if (titleLowercase.contains(lowercaseQuery)) {
                val highlightRanges = findAllOccurrences(titleLowercase, lowercaseQuery)
                titleMatches.add(SearchResult(
                    chat = chat,
                    searchQuery = query,
                    matchType = SearchMatchType.TITLE,
                    highlightRanges = highlightRanges
                ))
                foundInTitle = true
            }

            // Check content and file name matches if not found in title
            if (!foundInTitle) {
                for ((messageIndex, message) in chat.messages.withIndex()) {
                    var foundInThisMessage = false

                    // Check message text
                    val messageLowercase = message.text.lowercase()
                    if (messageLowercase.contains(lowercaseQuery)) {
                        val highlightRanges = findAllOccurrences(messageLowercase, lowercaseQuery)
                        contentMatches.add(SearchResult(
                            chat = chat,
                            searchQuery = query,
                            matchType = SearchMatchType.CONTENT,
                            messageIndex = messageIndex,
                            highlightRanges = highlightRanges
                        ))
                        foundInThisMessage = true
                        break
                    }

                    // Check attachment file names
                    if (!foundInThisMessage) {
                        for (attachment in message.attachments) {
                            val fileNameLowercase = attachment.file_name.lowercase()
                            if (fileNameLowercase.contains(lowercaseQuery)) {
                                val highlightRanges = findAllOccurrences(fileNameLowercase, lowercaseQuery)
                                fileMatches.add(SearchResult(
                                    chat = chat,
                                    searchQuery = query,
                                    matchType = SearchMatchType.FILE_NAME,
                                    messageIndex = messageIndex,
                                    highlightRanges = highlightRanges
                                ))
                                foundInThisMessage = true
                                break
                            }
                        }
                    }

                    if (foundInThisMessage) break
                }
            }
        }

        // Sort each category by date (most recent first) - using the same logic as main screen
        val sortedTitleMatches = titleMatches.sortedWith(
            compareByDescending<SearchResult> { getLastTimestampOrNull(it.chat) != null }
                .thenByDescending { getLastTimestampOrNull(it.chat) ?: Long.MIN_VALUE }
        )

        val sortedContentMatches = contentMatches.sortedWith(
            compareByDescending<SearchResult> { getLastTimestampOrNull(it.chat) != null }
                .thenByDescending { getLastTimestampOrNull(it.chat) ?: Long.MIN_VALUE }
        )

        val sortedFileMatches = fileMatches.sortedWith(
            compareByDescending<SearchResult> { getLastTimestampOrNull(it.chat) != null }
                .thenByDescending { getLastTimestampOrNull(it.chat) ?: Long.MIN_VALUE }
        )

        // Return title matches first, then content matches, then file matches
        return sortedTitleMatches + sortedContentMatches + sortedFileMatches
    }

    /**
     * Find all occurrences of a query in a text and return their ranges
     */
    private fun findAllOccurrences(text: String, query: String): List<IntRange> {
        if (query.isBlank() || text.isBlank()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var startIndex = 0

        while (true) {
            val index = text.indexOf(query, startIndex, ignoreCase = true)
            if (index == -1) break

            ranges.add(IntRange(index, index + query.length - 1))
            startIndex = index + 1
        }

        return ranges
    }

    // Helper function to get last timestamp from a chat
    private fun getLastTimestampOrNull(chat: Chat): Long? {
        val iso = chat.messages.lastOrNull()?.datetime ?: return null
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
