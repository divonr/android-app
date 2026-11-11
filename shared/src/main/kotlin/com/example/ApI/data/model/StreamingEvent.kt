package com.example.ApI.data.model

import com.example.ApI.tools.ToolCall

/**
 * Events emitted by StreamingService for communication with ViewModel.
 * These events allow the UI to update in real-time while the service
 * handles the actual API communication.
 */
sealed class StreamingEvent {
    /**
     * A partial chunk of streaming text received from the API.
     */
    data class PartialResponse(
        val requestId: String,
        val chatId: String,
        val text: String
    ) : StreamingEvent()

    /**
     * The streaming response completed successfully.
     */
    data class Complete(
        val requestId: String,
        val chatId: String,
        val fullText: String,
        val model: String
    ) : StreamingEvent()

    /**
     * An error occurred during the streaming request.
     */
    data class Error(
        val requestId: String,
        val chatId: String,
        val error: String
    ) : StreamingEvent()

    /**
     * Request status changed (e.g., from PENDING to STREAMING).
     */
    data class StatusChange(
        val requestId: String,
        val chatId: String,
        val status: RequestStatus
    ) : StreamingEvent()

    /**
     * A tool call was detected in the response and needs execution.
     * The ViewModel should execute the tool and provide the result back to the service.
     */
    data class ToolCallRequest(
        val requestId: String,
        val chatId: String,
        val toolCall: ToolCall,
        val precedingText: String
    ) : StreamingEvent()

    /**
     * Messages were saved to chat history mid-stream (e.g., preceding text + tool messages).
     * The ViewModel should reload chat history and clear streaming text so the UI
     * shows the saved messages separately from new streaming content.
     */
    data class MessagesAdded(
        val requestId: String,
        val chatId: String
    ) : StreamingEvent()

    /**
     * The model has started its thinking phase.
     */
    data class ThinkingStarted(
        val requestId: String,
        val chatId: String
    ) : StreamingEvent()

    /**
     * A partial chunk of thinking content received during streaming.
     */
    data class ThinkingPartial(
        val requestId: String,
        val chatId: String,
        val text: String
    ) : StreamingEvent()

    /**
     * The thinking phase has completed.
     */
    data class ThinkingComplete(
        val requestId: String,
        val chatId: String,
        val thoughts: String?,
        val durationSeconds: Float,
        val status: ThoughtsStatus
    ) : StreamingEvent()
}
