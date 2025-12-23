package com.example.ApI.ui.managers.streaming

import android.content.Context
import android.widget.Toast
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatUiState
import com.example.ApI.data.model.ExecutingToolInfo
import com.example.ApI.data.model.StreamingEvent
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.service.StreamingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manages streaming event handling from the StreamingService.
 * Processes partial responses, completion events, errors, tool calls, and thinking events.
 * Extracted from ChatViewModel to reduce complexity.
 */
class StreamingEventManager(
    private val repository: DataRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettings: StateFlow<AppSettings>,
    private val uiState: StateFlow<ChatUiState>,
    private val updateUiState: (ChatUiState) -> Unit,
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
        val currentText = uiState.value.streamingTextByChat[chatId] ?: ""
        updateUiState(
            uiState.value.copy(
                streamingTextByChat = uiState.value.streamingTextByChat + (chatId to currentText + event.text)
            )
        )
    }

    private fun handleComplete(event: StreamingEvent.Complete) {
        val chatId = event.chatId
        android.util.Log.d("StreamingEventManager", "Streaming complete for chat: $chatId")

        scope.launch {
            // Reload chat history to get the saved message
            val currentUser = appSettings.value.current_user
            val refreshedHistory = repository.loadChatHistory(currentUser)
            val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

            // Clear streaming state for this chat (including thoughts state)
            updateUiState(
                uiState.value.copy(
                    loadingChatIds = uiState.value.loadingChatIds - chatId,
                    streamingChatIds = uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = uiState.value.streamingTextByChat - chatId,
                    streamingThoughtsTextByChat = uiState.value.streamingThoughtsTextByChat - chatId,
                    completedThinkingDurationByChat = uiState.value.completedThinkingDurationByChat - chatId,
                    chatHistory = refreshedHistory.chat_history,
                    currentChat = if (uiState.value.currentChat?.chat_id == chatId) refreshedChat else uiState.value.currentChat
                )
            )

            // Handle title generation if this is the current chat
            if (refreshedChat != null && uiState.value.currentChat?.chat_id == chatId) {
                handleTitleGeneration(refreshedChat)
            }
        }
    }

    private fun handleError(event: StreamingEvent.Error) {
        val chatId = event.chatId
        android.util.Log.e("StreamingEventManager", "Streaming error for chat: $chatId - ${event.error}")

        scope.launch {
            // Reload chat history
            val currentUser = appSettings.value.current_user
            val refreshedHistory = repository.loadChatHistory(currentUser)
            val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

            // Check for Cohere image not supported error
            if (event.error.contains("image content is not supported for this model")) {
                Toast.makeText(
                    context,
                    "בחרו מודל שתומך בתמונה, כמו command-a-vision-07-2025",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                updateUiState(
                    uiState.value.copy(
                        snackbarMessage = "שגיאה: ${event.error}"
                    )
                )
            }

            // Clear streaming state for this chat (including thoughts state)
            updateUiState(
                uiState.value.copy(
                    loadingChatIds = uiState.value.loadingChatIds - chatId,
                    streamingChatIds = uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = uiState.value.streamingTextByChat - chatId,
                    streamingThoughtsTextByChat = uiState.value.streamingThoughtsTextByChat - chatId,
                    completedThinkingDurationByChat = uiState.value.completedThinkingDurationByChat - chatId,
                    thinkingChatIds = uiState.value.thinkingChatIds - chatId,
                    thinkingStartTimeByChat = uiState.value.thinkingStartTimeByChat - chatId,
                    chatHistory = refreshedHistory.chat_history,
                    currentChat = if (uiState.value.currentChat?.chat_id == chatId) refreshedChat else uiState.value.currentChat
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

        scope.launch {
            // Show tool execution indicator
            updateUiState(
                uiState.value.copy(
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
            updateUiState(uiState.value.copy(executingToolCall = null))

            // Provide result back to service
            getStreamingService()?.provideToolResult(requestId, result)
        }
    }

    private fun handleMessagesAdded(event: StreamingEvent.MessagesAdded) {
        // Messages were saved mid-stream (preceding text + tool messages)
        // Reload chat history and clear streaming text so UI shows them separately
        val chatId = event.chatId
        android.util.Log.d("StreamingEventManager", "Messages added mid-stream for chat: $chatId")

        scope.launch {
            // Reload chat history to show the newly saved messages
            val currentUser = appSettings.value.current_user
            val refreshedHistory = repository.loadChatHistory(currentUser)
            val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

            // Clear streaming text but keep streaming state active
            // This ensures saved messages appear as separate bubbles
            // and new streaming content starts fresh
            updateUiState(
                uiState.value.copy(
                    streamingTextByChat = uiState.value.streamingTextByChat + (chatId to ""),
                    chatHistory = refreshedHistory.chat_history,
                    currentChat = if (uiState.value.currentChat?.chat_id == chatId) refreshedChat else uiState.value.currentChat
                )
            )
        }
    }

    private fun handleThinkingStarted(event: StreamingEvent.ThinkingStarted) {
        val chatId = event.chatId
        android.util.Log.d("StreamingEventManager", "Thinking started for chat: $chatId")
        updateUiState(
            uiState.value.copy(
                thinkingChatIds = uiState.value.thinkingChatIds + chatId,
                thinkingStartTimeByChat = uiState.value.thinkingStartTimeByChat + (chatId to System.currentTimeMillis())
            )
        )
    }

    private fun handleThinkingPartial(event: StreamingEvent.ThinkingPartial) {
        val chatId = event.chatId
        val currentThoughts = uiState.value.streamingThoughtsTextByChat[chatId] ?: ""
        updateUiState(
            uiState.value.copy(
                streamingThoughtsTextByChat = uiState.value.streamingThoughtsTextByChat + (chatId to currentThoughts + event.text)
            )
        )
    }

    private fun handleThinkingComplete(event: StreamingEvent.ThinkingComplete) {
        val chatId = event.chatId
        android.util.Log.d("StreamingEventManager", "Thinking complete for chat: $chatId, duration: ${event.durationSeconds}s, status: ${event.status}")
        // Mark thinking as done but KEEP the thoughts text visible during response streaming
        // Store the completed duration for display (no longer live counting)
        updateUiState(
            uiState.value.copy(
                thinkingChatIds = uiState.value.thinkingChatIds - chatId,
                thinkingStartTimeByChat = uiState.value.thinkingStartTimeByChat - chatId,
                // DON'T clear streamingThoughtsTextByChat - keep it visible during response streaming
                completedThinkingDurationByChat = uiState.value.completedThinkingDurationByChat + (chatId to event.durationSeconds)
            )
        )
    }
}
