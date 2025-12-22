package com.example.ApI.data.network.providers

import com.example.ApI.data.model.ThoughtsStatus
import com.example.ApI.tools.ToolCall

/**
 * Common sealed class for streaming results across all LLM providers.
 * Each provider returns one of these result types.
 */
sealed class ProviderStreamingResult {
    /**
     * Text response completed successfully
     */
    data class TextComplete(
        val fullText: String,
        val thoughts: String? = null,
        val thinkingDurationSeconds: Float? = null,
        val thoughtsStatus: ThoughtsStatus = ThoughtsStatus.NONE
    ) : ProviderStreamingResult()

    /**
     * Tool call detected - provider wants to execute a tool
     */
    data class ToolCallDetected(
        val toolCall: ToolCall,
        val precedingText: String = "",
        val thoughts: String? = null,
        val thinkingDurationSeconds: Float? = null,
        val thoughtsStatus: ThoughtsStatus = ThoughtsStatus.NONE
    ) : ProviderStreamingResult()

    /**
     * Error occurred during streaming
     */
    data class Error(val error: String) : ProviderStreamingResult()
}

/**
 * Common response type for non-streaming API calls
 */
sealed class ApiResponse {
    data class Success(val message: String) : ApiResponse()
    data class Error(val error: String) : ApiResponse()
}
