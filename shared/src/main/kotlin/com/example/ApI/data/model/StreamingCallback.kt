package com.example.ApI.data.model

import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult

// Interface for streaming callbacks
interface StreamingCallback {
    fun onPartialResponse(text: String)
    fun onComplete(fullText: String)
    fun onError(error: String)

    /**
     * Called when a tool call is detected in the response
     * The implementation should execute the tool and return the result
     * @param toolCall The tool call information from the model
     * @param precedingText Optional text that appeared before the tool call
     * @return The result of executing the tool
     */
    suspend fun onToolCall(toolCall: ToolCall, precedingText: String = ""): ToolExecutionResult {
        // Default implementation returns an error
        return ToolExecutionResult.Error("Tool execution not supported")
    }

    /**
     * Called when tool messages need to be saved to chat history
     * @param toolCallMessage The tool_call message to save
     * @param toolResponseMessage The tool_response message to save
     * @param precedingText Optional text that appeared before the tool call (to save as assistant message)
     */
    suspend fun onSaveToolMessages(toolCallMessage: Message, toolResponseMessage: Message, precedingText: String = "") {
        // Default implementation - no-op
    }

    /**
     * Called when the model begins its thinking phase
     */
    fun onThinkingStarted() {
        // Default implementation - no-op
    }

    /**
     * Called when a partial chunk of thinking content is received during streaming
     * @param text The thinking content chunk
     */
    fun onThinkingPartial(text: String) {
        // Default implementation - no-op
    }

    /**
     * Called when the thinking phase completes
     * @param thoughts The complete thinking content (null if thoughts not available)
     * @param durationSeconds Duration of thinking phase in seconds
     * @param status Status indicating whether thoughts are present, unavailable, or none
     */
    fun onThinkingComplete(thoughts: String?, durationSeconds: Float, status: ThoughtsStatus) {
        // Default implementation - no-op
    }
}
