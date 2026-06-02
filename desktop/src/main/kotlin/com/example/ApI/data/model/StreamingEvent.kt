package com.example.ApI.data.model

import com.example.ApI.tools.ToolCall

/**
 * Events emitted during streaming for communication with ViewModel.
 */
sealed class StreamingEvent {
    data class PartialResponse(val requestId: String, val chatId: String, val text: String) : StreamingEvent()
    data class Complete(val requestId: String, val chatId: String, val fullText: String, val model: String) : StreamingEvent()
    data class Error(val requestId: String, val chatId: String, val error: String) : StreamingEvent()
    data class StatusChange(val requestId: String, val chatId: String, val status: RequestStatus) : StreamingEvent()
    data class ToolCallRequest(val requestId: String, val chatId: String, val toolCall: ToolCall, val precedingText: String) : StreamingEvent()
    data class MessagesAdded(val requestId: String, val chatId: String) : StreamingEvent()
    data class ThinkingStarted(val requestId: String, val chatId: String) : StreamingEvent()
    data class ThinkingPartial(val requestId: String, val chatId: String, val text: String) : StreamingEvent()
    data class ThinkingComplete(val requestId: String, val chatId: String, val thoughts: String?, val durationSeconds: Float, val status: ThoughtsStatus) : StreamingEvent()
}

enum class RequestStatus {
    PENDING, UPLOADING, STREAMING, COMPLETED, FAILED, CANCELLED
}
