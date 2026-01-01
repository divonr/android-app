package com.example.ApI.ui.managers.streaming

import android.widget.Toast
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ExecutingToolInfo
import com.example.ApI.data.model.StreamingEvent
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.service.StreamingService
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch

/**
 * Manages streaming event handling from the StreamingService.
 * Processes partial responses, completion events, errors, tool calls, and thinking events.
 * Extracted from ChatViewModel to reduce complexity.
 */
class StreamingEventManager(
    private val deps: ManagerDependencies,
    private val getCurrentDateTimeISO: () -> String,
    private val handleTitleGeneration: suspend (Chat) -> Unit,
    private val executeToolCall: suspend (ToolCall) -> ToolExecutionResult,
    private val getStreamingService: () -> StreamingService?
) {

    /**
     * Handle streaming events from the StreamingService.
     * Routes events to appropriate handlers based on event type.
     */
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
        // Update per-chat streaming text
        val chatId = event.chatId
        val currentText = deps.uiState.value.streamingTextByChat[chatId] ?: ""
        deps.updateUiState(
            deps.uiState.value.copy(
                streamingTextByChat = deps.uiState.value.streamingTextByChat + (chatId to currentText + event.text)
            )
        )
    }

    private fun handleComplete(event: StreamingEvent.Complete) {
        val chatId = event.chatId
        android.util.Log.d("StreamingEventManager", "Streaming complete for chat: $chatId")

        deps.scope.launch {
            // Reload chat history to get the saved message
            val currentUser = deps.appSettings.value.current_user
            val refreshedHistory = deps.repository.loadChatHistory(currentUser)
            val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

            // Clear streaming state for this chat (including thoughts state)
            deps.updateUiState(
                deps.uiState.value.copy(
                    loadingChatIds = deps.uiState.value.loadingChatIds - chatId,
                    streamingChatIds = deps.uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = deps.uiState.value.streamingTextByChat - chatId,
                    streamingThoughtsTextByChat = deps.uiState.value.streamingThoughtsTextByChat - chatId,
                    completedThinkingDurationByChat = deps.uiState.value.completedThinkingDurationByChat - chatId,
                    chatHistory = refreshedHistory.chat_history,
                    currentChat = if (deps.uiState.value.currentChat?.chat_id == chatId) refreshedChat else deps.uiState.value.currentChat
                )
            )

            // Handle title generation if this is the current chat
            if (refreshedChat != null && deps.uiState.value.currentChat?.chat_id == chatId) {
                handleTitleGeneration(refreshedChat)
            }
        }
    }

    private fun handleError(event: StreamingEvent.Error) {
        val chatId = event.chatId
        android.util.Log.e("StreamingEventManager", "Streaming error for chat: $chatId - ${event.error}")

        deps.scope.launch {
            // Reload chat history
            val currentUser = deps.appSettings.value.current_user
            val refreshedHistory = deps.repository.loadChatHistory(currentUser)
            val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

            // Check for Cohere image not supported error
            if (event.error.contains("image content is not supported for this model")) {
                Toast.makeText(
                    deps.context,
                    "בחרו מודל שתומך בתמונה, כמו command-a-vision-07-2025",
                    Toast.LENGTH_LONG
                ).show()
            } else if (event.error == "LLM_STATS_EMPTY_RESPONSE_WITH_TOOLS") {
                // LLM Stats empty response with tools - show Hebrew toast
                Toast.makeText(
                    deps.context,
                    "חזרה תשובה ריקה. נסו לכבות את ה-MCPs או להחליף מודל.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                deps.updateUiState(
                    deps.uiState.value.copy(
                        snackbarMessage = "שגיאה: ${event.error}"
                    )
                )
            }

            // Clear streaming state for this chat (including thoughts state)
            deps.updateUiState(
                deps.uiState.value.copy(
                    loadingChatIds = deps.uiState.value.loadingChatIds - chatId,
                    streamingChatIds = deps.uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = deps.uiState.value.streamingTextByChat - chatId,
                    streamingThoughtsTextByChat = deps.uiState.value.streamingThoughtsTextByChat - chatId,
                    completedThinkingDurationByChat = deps.uiState.value.completedThinkingDurationByChat - chatId,
                    thinkingChatIds = deps.uiState.value.thinkingChatIds - chatId,
                    thinkingStartTimeByChat = deps.uiState.value.thinkingStartTimeByChat - chatId,
                    chatHistory = refreshedHistory.chat_history,
                    currentChat = if (deps.uiState.value.currentChat?.chat_id == chatId) refreshedChat else deps.uiState.value.currentChat
                )
            )
        }
    }

    private fun handleStatusChange(event: StreamingEvent.StatusChange) {
        // Status changes are mainly for logging/debugging
        android.util.Log.d("StreamingEventManager", "Status change for ${event.chatId}: ${event.status}")
    }

    private fun handleToolCallRequest(event: StreamingEvent.ToolCallRequest) {
        val chatId = event.chatId
        val requestId = event.requestId
        android.util.Log.d("StreamingEventManager", "Tool call request for chat: $chatId, tool: ${event.toolCall.toolId}")

        deps.scope.launch {
            // Show tool execution indicator
            deps.updateUiState(
                deps.uiState.value.copy(
                    executingToolCall = ExecutingToolInfo(
                        toolId = event.toolCall.toolId,
                        toolName = event.toolCall.toolId,
                        startTime = getCurrentDateTimeISO()
                    )
                )
            )

            // Execute the tool
            val result = executeToolCall(event.toolCall)

            // Clear tool execution indicator
            deps.updateUiState(deps.uiState.value.copy(executingToolCall = null))

            // Provide result back to service
            getStreamingService()?.provideToolResult(requestId, result)
        }
    }

    private fun handleMessagesAdded(event: StreamingEvent.MessagesAdded) {
        // Messages were saved mid-stream (preceding text + tool messages)
        // Reload chat history and clear streaming text so UI shows them separately
        val chatId = event.chatId
        android.util.Log.d("StreamingEventManager", "Messages added mid-stream for chat: $chatId")

        deps.scope.launch {
            // Reload chat history to show the newly saved messages
            val currentUser = deps.appSettings.value.current_user
            val refreshedHistory = deps.repository.loadChatHistory(currentUser)
            val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

            // Clear streaming text but keep streaming state active
            // This ensures saved messages appear as separate bubbles
            // and new streaming content starts fresh
            deps.updateUiState(
                deps.uiState.value.copy(
                    streamingTextByChat = deps.uiState.value.streamingTextByChat + (chatId to ""),
                    chatHistory = refreshedHistory.chat_history,
                    currentChat = if (deps.uiState.value.currentChat?.chat_id == chatId) refreshedChat else deps.uiState.value.currentChat
                )
            )
        }
    }

    private fun handleThinkingStarted(event: StreamingEvent.ThinkingStarted) {
        val chatId = event.chatId
        android.util.Log.d("StreamingEventManager", "Thinking started for chat: $chatId")
        deps.updateUiState(
            deps.uiState.value.copy(
                thinkingChatIds = deps.uiState.value.thinkingChatIds + chatId,
                thinkingStartTimeByChat = deps.uiState.value.thinkingStartTimeByChat + (chatId to System.currentTimeMillis())
            )
        )
    }

    private fun handleThinkingPartial(event: StreamingEvent.ThinkingPartial) {
        val chatId = event.chatId
        val currentThoughts = deps.uiState.value.streamingThoughtsTextByChat[chatId] ?: ""
        deps.updateUiState(
            deps.uiState.value.copy(
                streamingThoughtsTextByChat = deps.uiState.value.streamingThoughtsTextByChat + (chatId to currentThoughts + event.text)
            )
        )
    }

    private fun handleThinkingComplete(event: StreamingEvent.ThinkingComplete) {
        val chatId = event.chatId
        android.util.Log.d("StreamingEventManager", "Thinking complete for chat: $chatId, duration: ${event.durationSeconds}s, status: ${event.status}")
        // Mark thinking as done but KEEP the thoughts text visible during response streaming
        // Store the completed duration for display (no longer live counting)
        deps.updateUiState(
            deps.uiState.value.copy(
                thinkingChatIds = deps.uiState.value.thinkingChatIds - chatId,
                thinkingStartTimeByChat = deps.uiState.value.thinkingStartTimeByChat - chatId,
                // DON'T clear streamingThoughtsTextByChat - keep it visible during response streaming
                completedThinkingDurationByChat = deps.uiState.value.completedThinkingDurationByChat + (chatId to event.durationSeconds)
            )
        )
    }
}
