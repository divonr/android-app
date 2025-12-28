package com.example.ApI.ui.managers.organization

import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies

/**
 * Manages search functionality for chat history and conversation search.
 * Handles both global chat search and in-conversation message search.
 * Extracted from ChatViewModel to reduce complexity.
 */
class SearchManager(
    private val deps: ManagerDependencies,
    private val getCurrentScreen: () -> Screen
) {

    /**
     * Enter search mode (global chat search)
     */
    fun enterSearchMode() {
        deps.updateUiState(deps.uiState.value.copy(searchMode = true, searchQuery = ""))
    }

    /**
     * Enter conversation search mode (search within current chat)
     */
    fun enterConversationSearchMode() {
        deps.updateUiState(deps.uiState.value.copy(searchMode = true, searchQuery = ""))
    }

    /**
     * Enter search mode with a pre-populated query and perform search immediately
     */
    fun enterSearchModeWithQuery(query: String) {
        deps.updateUiState(
            deps.uiState.value.copy(
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
        deps.updateUiState(
            deps.uiState.value.copy(
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
        deps.updateUiState(deps.uiState.value.copy(searchQuery = query))
        // Always use conversation search when in search mode in chat screen
        if (deps.uiState.value.searchMode && getCurrentScreen() == Screen.Chat) {
            performConversationSearch()
        } else if (!deps.uiState.value.searchMode) {
            // Only perform general search if not in conversation search mode
            performSearch()
        }
    }

    /**
     * Perform global search across all chats
     */
    fun performSearch() {
        val query = deps.uiState.value.searchQuery.trim()
        if (query.isEmpty()) {
            deps.updateUiState(deps.uiState.value.copy(searchResults = emptyList()))
            return
        }

        val currentUser = deps.appSettings.value.current_user
        val results = deps.repository.searchChats(currentUser, query)
        deps.updateUiState(deps.uiState.value.copy(searchResults = results))
    }

    /**
     * Perform search within the current conversation
     * Searches message text and attachment file names
     */
    fun performConversationSearch() {
        val query = deps.uiState.value.searchQuery.trim()
        val currentChat = deps.uiState.value.currentChat

        if (query.isEmpty() || currentChat == null) {
            deps.updateUiState(deps.uiState.value.copy(searchContext = null))
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
        deps.updateUiState(
            deps.uiState.value.copy(
                searchResults = searchResults,
                searchContext = if (searchResults.isNotEmpty()) searchResults.first() else null
            )
        )
    }

    /**
     * Clear the current search context (highlighted search result)
     */
    fun clearSearchContext() {
        deps.updateUiState(deps.uiState.value.copy(searchContext = null))
    }
}
