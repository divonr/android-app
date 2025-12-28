package com.example.ApI.data.network.providers

import android.content.Context
import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolCallInfo
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.util.JsonConfig
import kotlinx.serialization.json.*

/**
 * Base class for all LLM providers.
 * Contains common utilities and defines the interface for provider implementations.
 */
abstract class BaseProvider(protected val context: Context) {

    protected val json = JsonConfig.prettyPrint

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
     * Reads a file from disk and encodes it as Base64.
     * Returns null if the file doesn't exist or cannot be read.
     */
    protected fun readFileAsBase64(filePath: String?): String? {
        if (filePath == null) return null
        return try {
            val file = java.io.File(filePath)
            if (file.exists()) {
                java.util.Base64.getEncoder().encodeToString(file.readBytes())
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Creates a data URL from a file path and MIME type.
     * Returns null if the file cannot be read.
     */
    protected fun createDataUrl(filePath: String?, mimeType: String): String? {
        val base64Data = readFileAsBase64(filePath) ?: return null
        return "data:$mimeType;base64,$base64Data"
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

    /**
     * Completes the thinking phase and notifies the callback.
     * Common utility used by providers that support thinking/reasoning.
     */
    protected fun completeThinkingPhase(
        callback: StreamingCallback,
        thinkingStartTime: Long,
        thoughtsBuilder: StringBuilder
    ) {
        val durationSeconds = (System.currentTimeMillis() - thinkingStartTime) / 1000f
        val thoughtsContent = thoughtsBuilder.toString().takeIf { it.isNotEmpty() }
        callback.onThinkingComplete(
            thoughts = thoughtsContent,
            durationSeconds = durationSeconds,
            status = if (thoughtsContent != null) ThoughtsStatus.PRESENT else ThoughtsStatus.UNAVAILABLE
        )
    }

    /**
     * Handles tool call chaining loop - executes tools and makes follow-up requests
     * until a text response is received or max depth is reached.
     *
     * @param initialToolCall The first detected tool call
     * @param initialPrecedingText Any text before the tool call
     * @param messages Current conversation messages
     * @param modelName The model being used
     * @param callback Streaming callback for notifications
     * @param makeFollowUpRequest Lambda that makes the next API request with updated messages
     * @return The final text response or null if max depth exceeded
     */
    protected suspend fun handleToolCallChain(
        initialToolCall: ToolCall,
        initialPrecedingText: String,
        messages: List<Message>,
        modelName: String,
        callback: StreamingCallback,
        makeFollowUpRequest: suspend (List<Message>) -> ProviderStreamingResult
    ): String? {
        // Execute the initial tool
        val toolResult = callback.onToolCall(
            toolCall = initialToolCall,
            precedingText = initialPrecedingText
        )

        // Create tool messages
        val toolCallMessage = createToolCallMessage(initialToolCall, toolResult, "")
        val toolResponseMessage = createToolResponseMessage(initialToolCall, toolResult)

        // Save the tool messages
        callback.onSaveToolMessages(toolCallMessage, toolResponseMessage, initialPrecedingText)

        // Build follow-up messages
        val messagesToAdd = mutableListOf<Message>()
        if (initialPrecedingText.isNotBlank()) {
            messagesToAdd.add(createAssistantMessage(initialPrecedingText, modelName))
        }
        messagesToAdd.add(toolCallMessage)
        messagesToAdd.add(toolResponseMessage)

        var currentMessages = messages + messagesToAdd
        var currentResponse = makeFollowUpRequest(currentMessages)
        var toolDepth = 1

        // Loop to handle sequential tool calls
        while (currentResponse is ProviderStreamingResult.ToolCallDetected && toolDepth < MAX_TOOL_DEPTH) {
            Log.d("TOOL_CALL_DEBUG", "BaseProvider: Chained tool call #$toolDepth detected - ${currentResponse.toolCall.toolId}")

            val chainedToolResult = callback.onToolCall(
                toolCall = currentResponse.toolCall,
                precedingText = currentResponse.precedingText
            )

            val chainedMessagesToAdd = mutableListOf<Message>()
            if (currentResponse.precedingText.isNotBlank()) {
                chainedMessagesToAdd.add(createAssistantMessage(currentResponse.precedingText, modelName))
            }

            val chainedToolCallMessage = createToolCallMessage(currentResponse.toolCall, chainedToolResult, "")
            chainedMessagesToAdd.add(chainedToolCallMessage)

            val chainedToolResponseMessage = createToolResponseMessage(currentResponse.toolCall, chainedToolResult)
            chainedMessagesToAdd.add(chainedToolResponseMessage)

            callback.onSaveToolMessages(chainedToolCallMessage, chainedToolResponseMessage, currentResponse.precedingText)

            currentMessages = currentMessages + chainedMessagesToAdd
            toolDepth++

            currentResponse = makeFollowUpRequest(currentMessages)
        }

        // Return based on final response
        return when (currentResponse) {
            is ProviderStreamingResult.TextComplete -> currentResponse.fullText
            is ProviderStreamingResult.ToolCallDetected -> null // Max depth exceeded
            is ProviderStreamingResult.Error -> {
                callback.onError(currentResponse.error)
                null
            }
        }
    }

    companion object {
        const val MAX_TOOL_DEPTH = 25
    }
}
