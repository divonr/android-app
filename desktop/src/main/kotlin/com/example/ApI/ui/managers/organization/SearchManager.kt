package com.example.ApI.ui.managers.organization

import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies

/**
 * Manages search functionality for chat history and conversation search.
 */
class SearchManager(
    private val deps: ManagerDependencies,
    private val getCurrentScreen: () -> Screen
) {

    fun enterSearchMode() {
        deps.updateUiState(deps.uiState.value.copy(searchMode = true, searchQuery = ""))
    }

    fun enterConversationSearchMode() {
        deps.updateUiState(deps.uiState.value.copy(searchMode = true, searchQuery = ""))
    }

    fun enterSearchModeWithQuery(query: String) {
        deps.updateUiState(deps.uiState.value.copy(searchMode = true, searchQuery = query))
        performConversationSearch()
    }

    fun exitSearchMode() {
        deps.updateUiState(deps.uiState.value.copy(searchMode = false, searchQuery = "", searchResults = emptyList(), searchContext = null))
    }

    fun updateSearchQuery(query: String) {
        deps.updateUiState(deps.uiState.value.copy(searchQuery = query))
        if (deps.uiState.value.searchMode && getCurrentScreen() == Screen.Chat) {
            performConversationSearch()
        } else if (!deps.uiState.value.searchMode) {
            performSearch()
        }
    }

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

    fun performConversationSearch() {
        val query = deps.uiState.value.searchQuery.trim()
        val currentChat = deps.uiState.value.currentChat
        if (query.isEmpty() || currentChat == null) {
            deps.updateUiState(deps.uiState.value.copy(searchContext = null))
            return
        }

        val searchResults = mutableListOf<SearchResult>()
        currentChat.messages.forEachIndexed { messageIndex, message ->
            val highlightRanges = mutableListOf<IntRange>()
            if (message.text.contains(query, ignoreCase = true)) {
                var startIndex = 0
                while (true) {
                    val index = message.text.indexOf(query, startIndex, ignoreCase = true)
                    if (index == -1) break
                    highlightRanges.add(IntRange(index, index + query.length - 1))
                    startIndex = index + 1
                }
                if (highlightRanges.isNotEmpty()) {
                    searchResults.add(SearchResult(chat = currentChat, searchQuery = query, matchType = SearchMatchType.CONTENT, messageIndex = messageIndex, highlightRanges = highlightRanges))
                }
            }
            message.attachments.forEach { attachment ->
                if (attachment.file_name.contains(query, ignoreCase = true)) {
                    searchResults.add(SearchResult(chat = currentChat, searchQuery = query, matchType = SearchMatchType.FILE_NAME, messageIndex = messageIndex, highlightRanges = emptyList()))
                }
            }
        }
        deps.updateUiState(deps.uiState.value.copy(
            searchResults = searchResults,
            searchContext = if (searchResults.isNotEmpty()) searchResults.first() else null
        ))
    }

    fun clearSearchContext() {
        deps.updateUiState(deps.uiState.value.copy(searchContext = null))
    }
}
