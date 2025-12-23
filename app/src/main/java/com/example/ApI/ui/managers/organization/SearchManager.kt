package com.example.ApI.ui.managers.organization

import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages search functionality for chat history and conversation search.
 * Handles both global chat search and in-conversation message search.
 * Extracted from ChatViewModel to reduce complexity.
 */
class SearchManager(
    private val repository: DataRepository,
    private val appSettings: StateFlow<AppSettings>,
    private val uiState: StateFlow<ChatUiState>,
    private val updateUiState: (ChatUiState) -> Unit,
    private val getCurrentScreen: () -> Screen
) {

    /**
     * Enter search mode (global chat search)
     */
    fun enterSearchMode() {
        updateUiState(uiState.value.copy(searchMode = true, searchQuery = ""))
    }

    /**
     * Enter conversation search mode (search within current chat)
     */
    fun enterConversationSearchMode() {
        updateUiState(uiState.value.copy(searchMode = true, searchQuery = ""))
    }

    /**
     * Enter search mode with a pre-populated query and perform search immediately
     */
    fun enterSearchModeWithQuery(query: String) {
        updateUiState(
            uiState.value.copy(
                searchMode = true,
                searchQuery = query
            )
        )
        // Perform search immediately with the given query
        performConversationSearch()
    }

    /**
     * Exit search mode and clear all search results
     */
    fun exitSearchMode() {
        updateUiState(
            uiState.value.copy(
                searchMode = false,
                searchQuery = "",
                searchResults = emptyList(),
                searchContext = null // Clear search context when exiting
            )
        )
    }

    /**
     * Update the search query and trigger appropriate search
     */
    fun updateSearchQuery(query: String) {
        updateUiState(uiState.value.copy(searchQuery = query))
        // Always use conversation search when in search mode in chat screen
        if (uiState.value.searchMode && getCurrentScreen() == Screen.Chat) {
            performConversationSearch()
        } else if (!uiState.value.searchMode) {
            // Only perform general search if not in conversation search mode
            performSearch()
        }
    }

    /**
     * Perform global search across all chats
     */
    fun performSearch() {
        val query = uiState.value.searchQuery.trim()
        if (query.isEmpty()) {
            updateUiState(uiState.value.copy(searchResults = emptyList()))
            return
        }

        val currentUser = appSettings.value.current_user
        val results = repository.searchChats(currentUser, query)
        updateUiState(uiState.value.copy(searchResults = results))
    }

    /**
     * Perform search within the current conversation
     * Searches message text and attachment file names
     */
    fun performConversationSearch() {
        val query = uiState.value.searchQuery.trim()
        val currentChat = uiState.value.currentChat

        if (query.isEmpty() || currentChat == null) {
            updateUiState(uiState.value.copy(searchContext = null))
            return
        }

        // Search in current conversation messages and attachments
        val searchResults = mutableListOf<SearchResult>()

        currentChat.messages.forEachIndexed { messageIndex, message ->
            val highlightRanges = mutableListOf<IntRange>()

            // Search in message text
            if (message.text.contains(query, ignoreCase = true)) {
                // Find all occurrences of the search term
                var startIndex = 0
                while (true) {
                    val index = message.text.indexOf(query, startIndex, ignoreCase = true)
                    if (index == -1) break
                    highlightRanges.add(IntRange(index, index + query.length - 1))
                    startIndex = index + 1
                }

                if (highlightRanges.isNotEmpty()) {
                    searchResults.add(
                        SearchResult(
                            chat = currentChat,
                            searchQuery = query,
                            matchType = SearchMatchType.CONTENT,
                            messageIndex = messageIndex,
                            highlightRanges = highlightRanges
                        )
                    )
                }
            }

            // Search in attachment file names
            message.attachments.forEach { attachment ->
                if (attachment.file_name.contains(query, ignoreCase = true)) {
                    searchResults.add(
                        SearchResult(
                            chat = currentChat,
                            searchQuery = query,
                            matchType = SearchMatchType.FILE_NAME,
                            messageIndex = messageIndex,
                            highlightRanges = emptyList() // File name highlighting would be in a separate UI element
                        )
                    )
                }
            }
        }

        // Update the search context to highlight matches
        updateUiState(
            uiState.value.copy(
                searchResults = searchResults,
                searchContext = if (searchResults.isNotEmpty()) searchResults.first() else null
            )
        )
    }

    /**
     * Clear the current search context (highlighted search result)
     */
    fun clearSearchContext() {
        updateUiState(uiState.value.copy(searchContext = null))
    }
}
