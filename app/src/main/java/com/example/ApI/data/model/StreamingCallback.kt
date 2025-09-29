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
     * @return The result of executing the tool
     */
    suspend fun onToolCall(toolCall: ToolCall): ToolExecutionResult {
        // Default implementation returns an error
        return ToolExecutionResult.Error("Tool execution not supported")
    }
}
