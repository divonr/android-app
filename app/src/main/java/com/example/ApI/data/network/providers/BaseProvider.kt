package com.example.ApI.data.network.providers

import android.content.Context
import com.example.ApI.data.model.*
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolCallInfo
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Base class for all LLM providers.
 * Contains common utilities and defines the interface for provider implementations.
 */
abstract class BaseProvider(protected val context: Context) {

    protected val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Send a message to the provider and stream the response.
     * Each provider implements this method with its specific API logic.
     */
    abstract suspend fun sendMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean = false,
        enabledTools: List<ToolSpecification> = emptyList(),
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
        temperature: Float? = null,
        callback: StreamingCallback
    )

    /**
     * Creates a tool call message from a detected tool call.
     */
    protected fun createToolCallMessage(
        toolCall: ToolCall,
        toolResult: ToolExecutionResult,
        precedingText: String = ""
    ): Message {
        val toolDisplayName = ToolRegistry.getInstance().getToolDisplayName(toolCall.toolId)

        return Message(
            role = "tool_call",
            text = if (precedingText.isNotBlank()) precedingText else "Tool call: $toolDisplayName",
            toolCallId = toolCall.id,
            toolCall = ToolCallInfo(
                toolId = toolCall.toolId,
                toolName = toolDisplayName,
                parameters = toolCall.parameters,
                result = toolResult,
                timestamp = java.time.Instant.now().toString(),
                thoughtSignature = toolCall.thoughtSignature // Pass through Google's thought signature
            ),
            datetime = java.time.Instant.now().toString()
        )
    }

    /**
     * Creates a tool response message from a tool execution result.
     */
    protected fun createToolResponseMessage(
        toolCall: ToolCall,
        toolResult: ToolExecutionResult
    ): Message {
        val responseText = when (toolResult) {
            is ToolExecutionResult.Success -> toolResult.result
            is ToolExecutionResult.Error -> toolResult.error
        }

        return Message(
            role = "tool_response",
            text = responseText,
            toolResponseCallId = toolCall.id,
            toolResponseOutput = responseText,
            datetime = java.time.Instant.now().toString()
        )
    }

    /**
     * Creates an assistant message for preceding text before a tool call.
     */
    protected fun createAssistantMessage(
        text: String,
        modelName: String
    ): Message {
        return Message(
            role = "assistant",
            text = text,
            attachments = emptyList(),
            model = modelName,
            datetime = java.time.Instant.now().toString()
        )
    }

    /**
     * Reads text content from a file for inclusion in API requests.
     * Returns null if the file cannot be read or is too large.
     */
    protected fun readTextFileContent(filePath: String?, fileName: String): String? {
        if (filePath == null) return null
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) return null
            // Limit file size to 100KB to avoid very large payloads
            if (file.length() > 100 * 1024) {
                return "[File too large to include inline. Size: ${file.length() / 1024}KB]"
            }
            file.readText()
        } catch (e: Exception) {
            "[Error reading file: ${e.message}]"
        }
    }

    /**
     * Helper to get tool result text from ToolExecutionResult
     */
    protected fun getToolResultText(result: ToolExecutionResult): String {
        return when (result) {
            is ToolExecutionResult.Success -> result.result
            is ToolExecutionResult.Error -> result.error
        }
    }

    companion object {
        const val MAX_TOOL_DEPTH = 25
    }
}
