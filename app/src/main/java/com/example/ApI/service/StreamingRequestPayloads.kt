package com.example.ApI.service

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import java.util.concurrent.ConcurrentHashMap

/** The large part of a streaming request, handed to [StreamingService] in-process. */
data class StreamingRequestPayload(
    val provider: Provider,
    val messages: List<Message>,
    val systemPrompt: String,
    val projectAttachments: List<Attachment>,
    val enabledTools: List<ToolSpecification>,
    val thinkingBudget: ThinkingBudgetValue
) {
    /** Rough size of the request's text in chars (for logging only). */
    fun approxTextChars(): Long = systemPrompt.length.toLong() + messages.sumOf { m ->
        val result = when (val r = m.toolCall?.result) {
            is ToolExecutionResult.Success -> r.result.length + (r.details?.toString()?.length ?: 0)
            is ToolExecutionResult.Error -> r.error.length + (r.details?.toString()?.length ?: 0)
            null -> 0
        }
        (m.text.length + (m.thoughts?.length ?: 0) + (m.toolResponseOutput?.length ?: 0) +
            (m.toolCall?.parameters?.toString()?.length ?: 0) + result).toLong()
    }
}

/**
 * Holds request payloads by request id between the ViewModel and [StreamingService].
 *
 * The service runs in the app's process, so the payload never has to cross binder: an Intent
 * carrying a long conversation exceeds the binder transaction limit, the start command is
 * never delivered, and the app is reported as not responding.
 */
object StreamingRequestPayloads {
    private val payloads = ConcurrentHashMap<String, StreamingRequestPayload>()

    fun put(requestId: String, payload: StreamingRequestPayload) {
        payloads[requestId] = payload
    }

    /** Take the payload of [requestId]; null if there is none (e.g. the process was restarted). */
    fun take(requestId: String): StreamingRequestPayload? = payloads.remove(requestId)
}
