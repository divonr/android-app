package com.example.ApI.ui.managers.streaming

import android.util.Log
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ExecutingToolInfo
import com.example.ApI.data.model.StreamingEvent
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch

/**
 * Manages streaming event handling.
 * Desktop adaptation: no Android StreamingService; streaming events are dispatched
 * directly by the DesktopStreamingCoordinator (which calls handleStreamingEvent).
 * The getStreamingService callback returns null on desktop; tool results are handled
 * via the DesktopStreamingCoordinator's tool result channel instead.
 */
class StreamingEventManager(
    private val deps: ManagerDependencies,
    private val getCurrentDateTimeISO: () -> String,
    private val handleTitleGeneration: suspend (Chat) -> Unit,
    private val executeToolCall: suspend (ToolCall) -> ToolExecutionResult,
    private val provideToolResult: (String, ToolExecutionResult) -> Unit = { _, _ -> }
) {

    fun handleStreamingEvent(event: StreamingEvent) {
        when (event) {
            is StreamingEvent.PartialResponse -> handlePartialResponse(event)
            is StreamingEvent.Complete -> handleComplete(event)
            is StreamingEvent.Error -> handleError(event)
            is StreamingEvent.StatusChange -> handleStatusChange(event)
            is StreamingEvent.ToolCallRequest -> handleToolCallRequest(event)
            is StreamingEvent.MessagesAdded -> handleMessagesAdded(event)
            is StreamingEvent.ThinkingStarted -> handleThinkingStarted(event)
            is StreamingEvent.ThinkingPartial -> handleThinkingPartial(event)
            is StreamingEvent.ThinkingComplete -> handleThinkingComplete(event)
        }
    }

    private fun handlePartialResponse(event: StreamingEvent.PartialResponse) {
        val chatId = event.chatId
        val currentText = deps.uiState.value.streamingTextByChat[chatId] ?: ""
        deps.updateUiState(deps.uiState.value.copy(
            streamingTextByChat = deps.uiState.value.streamingTextByChat + (chatId to currentText + event.text)
        ))
    }

    private fun handleComplete(event: StreamingEvent.Complete) {
        val chatId = event.chatId
        Log.d("StreamingEventManager", "Streaming complete for chat: $chatId")
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val refreshedHistory = deps.repository.loadChatHistory(currentUser)
            val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }
            deps.updateUiState(deps.uiState.value.copy(
                loadingChatIds = deps.uiState.value.loadingChatIds - chatId,
                streamingChatIds = deps.uiState.value.streamingChatIds - chatId,
                streamingTextByChat = deps.uiState.value.streamingTextByChat - chatId,
                streamingThoughtsTextByChat = deps.uiState.value.streamingThoughtsTextByChat - chatId,
                completedThinkingDurationByChat = deps.uiState.value.completedThinkingDurationByChat - chatId,
                chatHistory = refreshedHistory.chat_history,
                currentChat = if (deps.uiState.value.currentChat?.chat_id == chatId) refreshedChat else deps.uiState.value.currentChat
            ))
            if (refreshedChat != null && deps.uiState.value.currentChat?.chat_id == chatId) {
                handleTitleGeneration(refreshedChat)
            }
        }
    }

    private fun handleError(event: StreamingEvent.Error) {
        val chatId = event.chatId
        Log.e("StreamingEventManager", "Streaming error for chat: $chatId - ${event.error}")
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val refreshedHistory = deps.repository.loadChatHistory(currentUser)
            val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

            val errorMsg = when {
                event.error.contains("image content is not supported for this model") ->
                    "Select a model that supports images"
                event.error == "LLM_STATS_EMPTY_RESPONSE_WITH_TOOLS" ->
                    "Empty response returned. Try disabling tools or switching model."
                else -> "Error: ${event.error}"
            }

            deps.updateUiState(deps.uiState.value.copy(
                loadingChatIds = deps.uiState.value.loadingChatIds - chatId,
                streamingChatIds = deps.uiState.value.streamingChatIds - chatId,
                streamingTextByChat = deps.uiState.value.streamingTextByChat - chatId,
                streamingThoughtsTextByChat = deps.uiState.value.streamingThoughtsTextByChat - chatId,
                completedThinkingDurationByChat = deps.uiState.value.completedThinkingDurationByChat - chatId,
                thinkingChatIds = deps.uiState.value.thinkingChatIds - chatId,
                thinkingStartTimeByChat = deps.uiState.value.thinkingStartTimeByChat - chatId,
                chatHistory = refreshedHistory.chat_history,
                currentChat = if (deps.uiState.value.currentChat?.chat_id == chatId) refreshedChat else deps.uiState.value.currentChat,
                snackbarMessage = errorMsg
            ))
        }
    }

    private fun handleStatusChange(event: StreamingEvent.StatusChange) {
        Log.d("StreamingEventManager", "Status change for ${event.chatId}: ${event.status}")
    }

    private fun handleToolCallRequest(event: StreamingEvent.ToolCallRequest) {
        val chatId = event.chatId
        val requestId = event.requestId
        Log.d("StreamingEventManager", "Tool call request for chat: $chatId, tool: ${event.toolCall.toolId}")
        deps.scope.launch {
            deps.updateUiState(deps.uiState.value.copy(
                executingToolCall = ExecutingToolInfo(
                    toolId = event.toolCall.toolId,
                    toolName = event.toolCall.toolId,
                    startTime = getCurrentDateTimeISO()
                )
            ))
            val result = executeToolCall(event.toolCall)
            deps.updateUiState(deps.uiState.value.copy(executingToolCall = null))
            // Provide result back to desktop streaming coordinator
            provideToolResult(requestId, result)
        }
    }

    private fun handleMessagesAdded(event: StreamingEvent.MessagesAdded) {
        val chatId = event.chatId
        Log.d("StreamingEventManager", "Messages added mid-stream for chat: $chatId")
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val refreshedHistory = deps.repository.loadChatHistory(currentUser)
            val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }
            deps.updateUiState(deps.uiState.value.copy(
                streamingTextByChat = deps.uiState.value.streamingTextByChat + (chatId to ""),
                chatHistory = refreshedHistory.chat_history,
                currentChat = if (deps.uiState.value.currentChat?.chat_id == chatId) refreshedChat else deps.uiState.value.currentChat
            ))
        }
    }

    private fun handleThinkingStarted(event: StreamingEvent.ThinkingStarted) {
        val chatId = event.chatId
        Log.d("StreamingEventManager", "Thinking started for chat: $chatId")
        deps.updateUiState(deps.uiState.value.copy(
            thinkingChatIds = deps.uiState.value.thinkingChatIds + chatId,
            thinkingStartTimeByChat = deps.uiState.value.thinkingStartTimeByChat + (chatId to System.currentTimeMillis())
        ))
    }

    private fun handleThinkingPartial(event: StreamingEvent.ThinkingPartial) {
        val chatId = event.chatId
        val currentThoughts = deps.uiState.value.streamingThoughtsTextByChat[chatId] ?: ""
        deps.updateUiState(deps.uiState.value.copy(
            streamingThoughtsTextByChat = deps.uiState.value.streamingThoughtsTextByChat + (chatId to currentThoughts + event.text)
        ))
    }

    private fun handleThinkingComplete(event: StreamingEvent.ThinkingComplete) {
        val chatId = event.chatId
        Log.d("StreamingEventManager", "Thinking complete for chat: $chatId, duration: ${event.durationSeconds}s")
        deps.updateUiState(deps.uiState.value.copy(
            thinkingChatIds = deps.uiState.value.thinkingChatIds - chatId,
            thinkingStartTimeByChat = deps.uiState.value.thinkingStartTimeByChat - chatId,
            completedThinkingDurationByChat = deps.uiState.value.completedThinkingDurationByChat + (chatId to event.durationSeconds)
        ))
    }
}
