package com.example.ApI.data.network.streaming

import kotlinx.serialization.json.JsonObject

/**
 * Handler interface for SSE stream events.
 * Providers implement this to handle their specific event types.
 */
interface StreamEventHandler {
    /**
     * Handle a parsed SSE event
     * @param eventType - Event type (from JSON field or event: line, null if structure-based)
     * @param data - Parsed JSON object
     * @return StreamAction - Continue, Stop, or Error
     */
    fun onEvent(eventType: String?, data: JsonObject): StreamAction

    /**
     * Called when stream ends normally (done marker or natural end)
     */
    fun onStreamEnd()

    /**
     * Called on JSON parsing error
     */
    fun onParseError(line: String, error: Exception)
}

/**
 * Action returned by event handler to control stream flow
 */
sealed class StreamAction {
    object Continue : StreamAction()
    object Stop : StreamAction()
    data class Error(val message: String) : StreamAction()
}
