@file:OptIn(ExperimentalEncodingApi::class)
package com.example.ApI.data.network.providers

import com.example.ApI.data.model.*
import com.example.ApI.tools.ToolCall
import com.example.ApI.util.AppLogger
import com.example.ApI.tools.ToolCallInfo
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.util.JsonConfig
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.*

/**
 * Base class for all LLM providers.
 * Contains common utilities and defines the interface for provider implementations.
 */
abstract class BaseProvider() {

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
     * Note: Preceding text and thoughts are saved separately as an assistant message.
     */
    protected fun createToolCallMessage(
        toolCall: ToolCall,
        toolResult: ToolExecutionResult
    ): Message {
        // Shown under the name the model called it by; the stored ID is the tool's own
        val toolDisplayName = com.example.ApI.tools.ToolRegistry.modelNameForToolId(toolCall.toolId)

        return Message(
            role = "tool_call",
            text = "",  // Clean format - preceding text saved as separate assistant message
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
     * The notes for the attachments this request does not send to the model itself but the
     * code sandbox has, by attachment. Empty unless the sandbox is one of [offeredTools] - the
     * tools this very request declares - and only for files of the sandbox's own manifest,
     * under the name they have there; any other attachment keeps what the provider says of it.
     *
     * The text depends on the attachment's name, its content and the files before it in the
     * conversation, so it is the same on every later request (a prompt cache's prefix).
     *
     * @param notSent the attachments the provider is not going to send
     */
    protected suspend fun sandboxFileNotes(
        messages: List<Message>,
        offeredTools: List<ToolSpecification>,
        notSent: (Attachment) -> Boolean
    ): Map<Attachment, String> {
        val sandboxId = com.example.ApI.tools.ToolRegistry.PYTHON_INTERPRETER
        val sandbox = com.example.ApI.tools.ToolRegistry.specificationOf(sandboxId, offeredTools) ?: return emptyMap()
        val inputFiles = sandbox.inputFiles ?: return emptyMap()
        val candidates = messages.filter { it.role == "user" }.flatMap { it.attachments }.filter(notSent).distinct()
        if (candidates.isEmpty()) return emptyMap()

        val paths = try {
            inputFiles.paths()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("Could not tell which attachments the code sandbox has: ${e.message}")
            return emptyMap()
        }
        val notes = HashMap<Attachment, String>()
        for (attachment in candidates) {
            val path = paths.pathOf(attachment) ?: continue
            notes[attachment] = "[User attached file \"${attachment.file_name}\" — available inside ${sandbox.name} at $path]"
        }
        return notes
    }

    /**
     * What a user turn says when the user wrote nothing and none of its files reaches the
     * model in the request: the names of the files, so that the turn is not empty (an API
     * rejects an empty turn). Empty for a message without files. Never used for an assistant
     * turn - that one is left out instead.
     */
    protected fun fileNamesNote(message: Message): String =
        message.attachments.joinToString("\n") { "[Attached file: \"${it.file_name}\"]" }

    /** [text] followed by [notes], for a provider whose user turn is one string. */
    protected fun textWithNotes(text: String, notes: List<String>): String =
        (listOf(text).filter { it.isNotBlank() } + notes).joinToString("\n\n")

    /**
     * Whether [attachment]'s file can be put in the prompt as text ([readTextFileContent]): it
     * is here, within that function's size limit, and its bytes are text - no NUL byte, and
     * either declared as text or valid UTF-8. An office document, an archive or any other
     * binary file is not.
     */
    protected fun isInlineableTextFile(attachment: Attachment): Boolean {
        val file = attachment.local_file_path?.let { java.io.File(it) }?.takeIf { it.isFile } ?: return false
        if (file.length() > MAX_INLINE_TEXT_FILE_BYTES) return false
        val bytes = try { file.readBytes() } catch (e: Exception) { return false }
        if (bytes.any { it == 0.toByte() }) return false
        if (attachment.mime_type.lowercase().startsWith("text/")) return true
        return try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (e: java.nio.charset.CharacterCodingException) {
            false
        }
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
                Base64.encode(file.readBytes())
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
            if (file.length() > MAX_INLINE_TEXT_FILE_BYTES) {
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
     * @param initialAdditionalToolCalls The rest of the first step, when it was a parallel one
     * @param messages Current conversation messages
     * @param modelName The model being used
     * @param callback Streaming callback for notifications
     * @param makeFollowUpRequest Lambda that makes the next API request with updated messages
     * @return The final text response or null if max depth exceeded
     */
    protected suspend fun handleToolCallChain(
        initialToolCall: ToolCall,
        initialPrecedingText: String,
        initialAdditionalToolCalls: List<ToolCall> = emptyList(),
        messages: List<Message>,
        modelName: String,
        callback: StreamingCallback,
        makeFollowUpRequest: suspend (List<Message>) -> ProviderStreamingResult
    ): String? {
        // Execute the initial step and build the follow-up messages
        val messagesToAdd = executeToolCalls(
            calls = listOf(initialToolCall) + initialAdditionalToolCalls,
            precedingText = initialPrecedingText,
            modelName = modelName,
            callback = callback
        )

        var currentMessages = messages + messagesToAdd
        var currentResponse = makeFollowUpRequest(currentMessages)
        var toolDepth = 1

        // Loop to handle sequential tool calls
        while (currentResponse is ProviderStreamingResult.ToolCallDetected && toolDepth < MAX_TOOL_DEPTH) {
            AppLogger.d("[TOOL_CALL_DEBUG] BaseProvider: Chained tool call #$toolDepth detected - ${currentResponse.toolCall.toolId}")

            val chainedMessagesToAdd = executeToolCalls(
                calls = listOf(currentResponse.toolCall) + currentResponse.additionalToolCalls,
                precedingText = currentResponse.precedingText,
                modelName = modelName,
                callback = callback
            )

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

    /**
     * Executes one model step's tool calls in order, saving each call/response pair.
     * The preceding text belongs to the first call only.
     *
     * @return the messages to append to the conversation for the follow-up request
     */
    private suspend fun executeToolCalls(
        calls: List<ToolCall>,
        precedingText: String,
        modelName: String,
        callback: StreamingCallback
    ): List<Message> {
        val messagesToAdd = mutableListOf<Message>()
        if (precedingText.isNotBlank()) {
            messagesToAdd.add(createAssistantMessage(precedingText, modelName))
        }

        calls.forEachIndexed { index, toolCall ->
            val textForThisCall = if (index == 0) precedingText else ""
            val toolResult = callback.onToolCall(toolCall = toolCall, precedingText = textForThisCall)
            val toolCallMessage = createToolCallMessage(toolCall, toolResult)
            val toolResponseMessage = createToolResponseMessage(toolCall, toolResult)
            callback.onSaveToolMessages(toolCallMessage, toolResponseMessage, textForThisCall)
            messagesToAdd.add(toolCallMessage)
            messagesToAdd.add(toolResponseMessage)
        }

        return messagesToAdd
    }

    /**
     * Log API request details to the app logs.
     * Note: Conversation name, model, and provider are logged at StreamingService level.
     */
    protected fun logApiRequestDetails(
        baseUrl: String,
        headers: Map<String, String>,
        body: String
    ) {
        // Mask sensitive data in headers
        val maskedHeaders = headers.mapValues { (key, value) ->
            if (key.lowercase().contains("authorization") || key.lowercase().contains("api-key") || key.lowercase().contains("x-api-key")) {
                "***MASKED***"
            } else {
                value
            }
        }

        AppLogger.i("Request structure; base url: $baseUrl, headers: $maskedHeaders, body: $body")
    }

    /**
     * Log API response code to the app logs.
     */
    protected fun logApiResponse(responseCode: Int) {
        AppLogger.logApiResponse(responseCode)
    }

    /**
     * Log API error to the app logs.
     */
    protected fun logApiError(errorContent: String) {
        AppLogger.logApiError(errorContent)
    }

    companion object {
        const val MAX_TOOL_DEPTH = 25

        /** The largest file [readTextFileContent] puts in the prompt as text. */
        const val MAX_INLINE_TEXT_FILE_BYTES = 100 * 1024
    }
}
