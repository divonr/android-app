package com.example.ApI.data.network.providers

import android.content.Context
import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.data.network.streaming.EventDataStreamParser
import com.example.ApI.data.network.streaming.StreamAction
import com.example.ApI.data.network.streaming.StreamEventHandler
import com.example.ApI.data.network.streaming.StreamResult
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Poe API provider implementation.
 * Handles streaming responses and tool calling with Poe-specific format.
 */
class PoeProvider(context: Context) : BaseProvider(context) {

    override suspend fun sendMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?,
        callback: StreamingCallback
    ) {
        try {
            val streamingResponse = makeStreamingRequest(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, callback
            )

            when (streamingResponse) {
                is ProviderStreamingResult.TextComplete -> {
                    Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Text response complete")
                    callback.onComplete(streamingResponse.fullText)
                }
                is ProviderStreamingResult.ToolCallDetected -> {
                    handleToolCall(
                        provider, modelName, messages, systemPrompt, apiKey,
                        webSearchEnabled, enabledTools, streamingResponse, callback
                    )
                }
                is ProviderStreamingResult.Error -> {
                    // Already called callback.onError
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send Poe streaming message: ${e.message}")
        }
    }

    private suspend fun handleToolCall(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        initialResponse: ProviderStreamingResult.ToolCallDetected,
        callback: StreamingCallback
    ) {
        Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Tool call detected - ${initialResponse.toolCall.toolId}")

        val toolResult = callback.onToolCall(
            toolCall = initialResponse.toolCall,
            precedingText = initialResponse.precedingText
        )
        Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Tool executed with result: $toolResult")

        val toolCallMessage = createToolCallMessage(initialResponse.toolCall, toolResult)
        val toolResponseMessage = createToolResponseMessage(initialResponse.toolCall, toolResult)

        callback.onSaveToolMessages(toolCallMessage, toolResponseMessage, initialResponse.precedingText)

        Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Sending follow-up request with tool_calls and tool_results")

        // Handle tool chaining
        var currentMessages = messages
        if (initialResponse.precedingText.isNotBlank()) {
            currentMessages = currentMessages + createAssistantMessage(initialResponse.precedingText, modelName)
        }

        var currentToolCall = initialResponse.toolCall
        var currentToolResult = toolResult
        var currentResponse: ProviderStreamingResult
        var toolDepth = 1

        while (toolDepth < MAX_TOOL_DEPTH) {
            Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Tool chain depth = $toolDepth")

            // First iteration uses WithToolResults, subsequent use regular request
            currentResponse = if (toolDepth == 1) {
                makeStreamingRequestWithToolResults(
                    provider, modelName, currentMessages, systemPrompt, apiKey,
                    webSearchEnabled, enabledTools, currentToolCall, currentToolResult, callback
                )
            } else {
                makeStreamingRequest(
                    provider, modelName, currentMessages, systemPrompt, apiKey,
                    webSearchEnabled, enabledTools, ThinkingBudgetValue.None, callback
                )
            }

            when (currentResponse) {
                is ProviderStreamingResult.TextComplete -> {
                    Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Got final text response")
                    callback.onComplete(currentResponse.fullText)
                    return
                }
                is ProviderStreamingResult.ToolCallDetected -> {
                    Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Chained tool call #$toolDepth detected - ${currentResponse.toolCall.toolId}")

                    if (currentResponse.precedingText.isNotBlank()) {
                        currentMessages = currentMessages + createAssistantMessage(currentResponse.precedingText, modelName)
                    }

                    val nextToolResult = callback.onToolCall(
                        toolCall = currentResponse.toolCall,
                        precedingText = currentResponse.precedingText
                    )

                    val nextToolCallMessage = createToolCallMessage(currentResponse.toolCall, nextToolResult)
                    val nextToolResponseMessage = createToolResponseMessage(currentResponse.toolCall, nextToolResult)

                    callback.onSaveToolMessages(nextToolCallMessage, nextToolResponseMessage, currentResponse.precedingText)

                    // For Poe: Add tool result as a bot message
                    val toolResultText = when (nextToolResult) {
                        is ToolExecutionResult.Success ->
                            "Tool ${currentResponse.toolCall.toolId} result: ${nextToolResult.result}"
                        is ToolExecutionResult.Error ->
                            "Tool ${currentResponse.toolCall.toolId} error: ${nextToolResult.error}"
                    }
                    currentMessages = currentMessages + Message(
                        role = "assistant",
                        text = toolResultText,
                        attachments = emptyList(),
                        model = modelName,
                        datetime = java.time.Instant.now().toString()
                    )

                    currentToolCall = currentResponse.toolCall
                    currentToolResult = nextToolResult
                    toolDepth++
                }
                is ProviderStreamingResult.Error -> {
                    callback.onError(currentResponse.error)
                    return
                }
            }
        }

        if (toolDepth >= MAX_TOOL_DEPTH) {
            callback.onError("Maximum tool call depth ($MAX_TOOL_DEPTH) exceeded")
        }
    }

    private suspend fun makeStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        callback: StreamingCallback,
        maxToolDepth: Int = MAX_TOOL_DEPTH,
        currentDepth: Int = 0
    ): ProviderStreamingResult = withContext(Dispatchers.IO) {
        try {
            val baseUrl = provider.request.base_url.replace("{model_name}", modelName)
            val url = URL(baseUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true

            val conversationMessages = buildMessages(messages, systemPrompt, webSearchEnabled, thinkingBudget)
            val requestBody = buildRequestBody(conversationMessages, enabledTools)
            val requestBodyString = json.encodeToString(requestBody)

            // Log request details
            logApiRequestDetails(
                baseUrl = baseUrl,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                body = requestBodyString
            )

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBodyString)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            logApiResponse(responseCode)

            if (responseCode >= 400) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                logApiError(errorBody)
                callback.onError("HTTP $responseCode: $errorBody")
                return@withContext ProviderStreamingResult.Error("HTTP $responseCode: $errorBody")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            parseStreamingResponse(reader, connection, callback)
        } catch (e: Exception) {
            callback.onError("Failed to make Poe streaming request: ${e.message}")
            ProviderStreamingResult.Error("Failed to make Poe streaming request: ${e.message}")
        }
    }

    private suspend fun makeStreamingRequestWithToolResults(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        toolCall: ToolCall,
        toolResult: ToolExecutionResult,
        callback: StreamingCallback
    ): ProviderStreamingResult = withContext(Dispatchers.IO) {
        try {
            val baseUrl = provider.request.base_url.replace("{model_name}", modelName)
            val url = URL(baseUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true

            val conversationMessages = buildMessages(messages, systemPrompt, webSearchEnabled, ThinkingBudgetValue.None, skipToolMessages = true)
            val requestBody = buildRequestBodyWithToolResults(conversationMessages, enabledTools, toolCall, toolResult)
            val requestBodyString = json.encodeToString(requestBody)

            // Log request details
            logApiRequestDetails(
                baseUrl = baseUrl,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                body = requestBodyString
            )

            Log.d("TOOL_CALL_DEBUG", "Poe: Sending request with tool results: $requestBodyString")

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBodyString)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            logApiResponse(responseCode)

            if (responseCode >= 400) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                logApiError(errorBody)
                callback.onError("HTTP $responseCode: $errorBody")
                return@withContext ProviderStreamingResult.Error("HTTP $responseCode: $errorBody")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            Log.d("TOOL_CALL_DEBUG", "Poe: Starting to read follow-up stream...")
            // Use the same parser as initial requests - tool calls can happen at any point in the chain
            parseStreamingResponse(reader, connection, callback)
        } catch (e: Exception) {
            callback.onError("Failed to make Poe streaming request with tool results: ${e.message}")
            ProviderStreamingResult.Error("Failed to make Poe streaming request with tool results: ${e.message}")
        }
    }

    /**
     * Inner class to handle Poe SSE events.
     * Implements StreamEventHandler for use with EventDataStreamParser.
     */
    private inner class PoeEventHandler(
        private val callback: StreamingCallback
    ) : StreamEventHandler {

        // Response accumulation
        val fullResponse = StringBuilder()
        val actualResponseBuilder = StringBuilder()  // Response without thinking content
        val thoughtsBuilder = StringBuilder()

        // Tool call tracking
        var detectedToolCall: ToolCall? = null
        val toolCallBuffer = mutableMapOf<Int, MutableMap<String, Any>>()

        // Thinking state tracking
        var thinkingDetected = false
        var thinkingInProgress = false
        var thinkingComplete = false
        var thinkingStartTime: Long = 0
        var lastSentThinkingLength = 0  // Track how much thinking content we've already sent
        var lastSentResponseLength = 0  // Track how much response content we've already sent

        // Error tracking
        var errorMessage: String? = null

        // Regex patterns for thinking detection
        private val boldPattern = Regex("""^\*Thinking\.\.\.\*\n+""")
        private val plainPattern = Regex("""^Thinking\.\.\.\n+""")

        override fun onEvent(eventType: String?, data: JsonObject): StreamAction {
            when (eventType) {
                "text" -> {
                    val text = data["text"]?.jsonPrimitive?.content
                    if (text != null) {
                        fullResponse.append(text)

                        // Handle thinking detection and extraction
                        val currentFull = fullResponse.toString()

                        if (!thinkingDetected) {
                            // Check if this is the start of thinking using flexible pattern detection
                            val thinkingResult = detectThinkingPatternInternal(currentFull)

                            if (thinkingResult != null) {
                                thinkingDetected = true
                                thinkingInProgress = true
                                thinkingStartTime = System.currentTimeMillis()
                                callback.onThinkingStarted()
                                Log.d("POE_THINKING", "Thinking started")
                            } else {
                                // No thinking pattern detected, regular response
                                actualResponseBuilder.append(text)
                                callback.onPartialResponse(text)
                            }
                        }

                        // If thinking was detected (either just now or previously), re-parse full content
                        if (thinkingDetected) {
                            val thinkingResult = detectThinkingPatternInternal(currentFull)
                            if (thinkingResult != null) {
                                val (_, afterPrefix) = thinkingResult
                                val (thinking, response) = parseThinkingChunkInternal(afterPrefix)

                                // Send only new thinking content (delta)
                                if (thinkingInProgress && thinking.length > lastSentThinkingLength) {
                                    val newThinking = thinking.substring(lastSentThinkingLength)
                                    if (newThinking.isNotBlank()) {
                                        callback.onThinkingPartial(newThinking)
                                    }
                                    lastSentThinkingLength = thinking.length
                                    // Update thoughtsBuilder with full thinking content
                                    thoughtsBuilder.clear()
                                    thoughtsBuilder.append(thinking)
                                }

                                // Check if we've transitioned to response
                                if (response.isNotBlank() && thinkingInProgress) {
                                    thinkingInProgress = false
                                    thinkingComplete = true
                                    completeThinkingPhase(callback, thinkingStartTime, thoughtsBuilder)
                                    Log.d("POE_THINKING", "Thinking complete, duration: ${(System.currentTimeMillis() - thinkingStartTime) / 1000f}s")
                                }

                                // Send only new response content (delta)
                                if (!thinkingInProgress && response.length > lastSentResponseLength) {
                                    val newResponse = response.substring(lastSentResponseLength)
                                    if (newResponse.isNotBlank()) {
                                        callback.onPartialResponse(newResponse)
                                    }
                                    lastSentResponseLength = response.length
                                    // Update actualResponseBuilder with full response content
                                    actualResponseBuilder.clear()
                                    actualResponseBuilder.append(response)
                                }
                            }
                        }
                    }
                }
                "replace_response" -> {
                    val replacementText = data["text"]?.jsonPrimitive?.content
                    if (replacementText != null && replacementText.isNotBlank()) {
                        // Reset all state on replace_response
                        fullResponse.clear()
                        fullResponse.append(replacementText)
                        actualResponseBuilder.clear()
                        thoughtsBuilder.clear()
                        thinkingDetected = false
                        thinkingInProgress = false
                        thinkingComplete = false
                        thinkingStartTime = 0
                        lastSentThinkingLength = 0
                        lastSentResponseLength = 0

                        // Re-process as if it's a fresh chunk using flexible pattern detection
                        val thinkingResult = detectThinkingPatternInternal(replacementText)
                        if (thinkingResult != null) {
                            val (_, afterPrefix) = thinkingResult
                            thinkingDetected = true
                            thinkingInProgress = true
                            thinkingStartTime = System.currentTimeMillis()
                            callback.onThinkingStarted()

                            val (thinking, response) = parseThinkingChunkInternal(afterPrefix)
                            if (thinking.isNotBlank()) {
                                thoughtsBuilder.append(thinking)
                                callback.onThinkingPartial(thinking)
                                lastSentThinkingLength = thinking.length
                            }
                            if (response.isNotBlank()) {
                                thinkingInProgress = false
                                thinkingComplete = true
                                completeThinkingPhase(callback, thinkingStartTime, thoughtsBuilder)
                                actualResponseBuilder.append(response)
                                callback.onPartialResponse(response)
                                lastSentResponseLength = response.length
                            }
                        } else {
                            actualResponseBuilder.append(replacementText)
                            callback.onPartialResponse(replacementText)
                        }
                    }
                }
                "json" -> {
                    val choices = data["choices"]?.jsonArray

                    choices?.firstOrNull()?.jsonObject?.let { choice ->
                        val delta = choice["delta"]?.jsonObject
                        val toolCalls = delta?.get("tool_calls")?.jsonArray

                        if (toolCalls != null) {
                            toolCalls.forEach { toolCallElement ->
                                val toolCallObj = toolCallElement.jsonObject
                                val index = toolCallObj["index"]?.jsonPrimitive?.int ?: 0
                                val id = toolCallObj["id"]?.jsonPrimitive?.content
                                val type = toolCallObj["type"]?.jsonPrimitive?.content
                                val function = toolCallObj["function"]?.jsonObject

                                if (!toolCallBuffer.containsKey(index)) {
                                    toolCallBuffer[index] = mutableMapOf()
                                }

                                val buffer = toolCallBuffer[index]!!

                                if (id != null) buffer["id"] = id
                                if (type != null) buffer["type"] = type
                                if (function != null) {
                                    val name = function["name"]?.jsonPrimitive?.content
                                    val arguments = function["arguments"]?.jsonPrimitive?.content

                                    if (name != null) buffer["name"] = name
                                    if (arguments != null) {
                                        val existingArgs = buffer["arguments"] as? String ?: ""
                                        buffer["arguments"] = existingArgs + arguments
                                    }
                                }

                                Log.d("TOOL_CALL_DEBUG", "Poe: Accumulated tool call data for index $index: $buffer")
                            }
                        }

                        val finishReason = choice["finish_reason"]?.jsonPrimitive?.content
                        if (finishReason == "tool_calls") {
                            Log.d("TOOL_CALL_DEBUG", "Poe: Finish reason is tool_calls")

                            toolCallBuffer.values.firstOrNull()?.let { buffer ->
                                val toolId = buffer["id"] as? String
                                val toolName = buffer["name"] as? String
                                val toolArguments = buffer["arguments"] as? String

                                if (toolId != null && toolName != null && toolArguments != null) {
                                    try {
                                        val paramsJson = json.parseToJsonElement(toolArguments).jsonObject
                                        detectedToolCall = ToolCall(
                                            id = toolId,
                                            toolId = toolName,
                                            parameters = paramsJson,
                                            provider = "poe"
                                        )
                                        Log.d("TOOL_CALL_DEBUG", "Poe: Created tool call: $detectedToolCall")
                                    } catch (e: Exception) {
                                        Log.e("TOOL_CALL_DEBUG", "Poe: Failed to parse tool arguments: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                }
                "error" -> {
                    val errorText = data["text"]?.jsonPrimitive?.content ?: "Unknown error"
                    val errorType = data["error_type"]?.jsonPrimitive?.content
                    errorMessage = "Poe API error ($errorType): $errorText"
                    callback.onError(errorMessage!!)
                    return StreamAction.Error(errorMessage!!)
                }
            }

            return StreamAction.Continue
        }

        override fun onStreamEnd() {
            Log.d("TOOL_CALL_DEBUG", "Poe: Stream done")

            // Complete thinking phase if still in progress
            if (thinkingInProgress) {
                completeThinkingPhase(callback, thinkingStartTime, thoughtsBuilder)
                Log.d("POE_THINKING", "Thinking completed at stream end")
            }
        }

        override fun onParseError(line: String, error: Exception) {
            Log.e("TOOL_CALL_DEBUG", "Poe: Error parsing event: ${error.message}")
            // Continue processing (matches previous behavior)
        }

        /**
         * Detects if the text starts with a thinking pattern and extracts the content after the prefix.
         * Supports both bold (*Thinking...*) and non-bold (Thinking...) formats.
         * @return Pair of (isThinking, contentAfterPrefix) or null if not a thinking pattern
         */
        private fun detectThinkingPatternInternal(text: String): Pair<Boolean, String>? {
            val matchResult = boldPattern.find(text) ?: plainPattern.find(text)

            if (matchResult != null) {
                val afterPrefix = text.substring(matchResult.range.last + 1)
                // Check if the content after prefix starts with blockquote (after any leading whitespace/newlines)
                val trimmedAfter = afterPrefix.trimStart('\n', '\r')
                if (trimmedAfter.isEmpty() || trimmedAfter.startsWith(">")) {
                    return Pair(true, afterPrefix)
                }
            }

            return null
        }

        /**
         * Parse a chunk of text to separate thinking content (blockquotes) from response content.
         * @return Pair of (thinkingContent, responseContent)
         */
        private fun parseThinkingChunkInternal(text: String): Pair<String, String> {
            val lines = text.lines()
            val thinkingLines = mutableListOf<String>()
            val responseLines = mutableListOf<String>()
            var foundNonBlockquote = false

            for (line in lines) {
                if (!foundNonBlockquote && (line.startsWith(">") || line.isBlank())) {
                    // Still in thinking section - strip the > prefix
                    val cleanedLine = when {
                        line.startsWith("> ") -> line.removePrefix("> ")
                        line.startsWith(">") -> line.removePrefix(">")
                        else -> line
                    }
                    thinkingLines.add(cleanedLine)
                } else if (line.isNotBlank()) {
                    // First non-blank, non-blockquote line - transition to response
                    foundNonBlockquote = true
                    responseLines.add(line)
                } else if (foundNonBlockquote) {
                    // Blank line after we've started response
                    responseLines.add(line)
                }
            }

            return Pair(thinkingLines.joinToString("\n"), responseLines.joinToString("\n"))
        }

        /**
         * Build the final result based on accumulated state.
         */
        fun getResult(): ProviderStreamingResult {
            // Check for error first
            errorMessage?.let {
                return ProviderStreamingResult.Error(it)
            }

            // Calculate thinking duration if thinking occurred
            val thinkingDuration = if (thinkingDetected && thinkingStartTime > 0) {
                (System.currentTimeMillis() - thinkingStartTime) / 1000f
            } else null

            // Use actualResponseBuilder for the response (without thinking prefix/blockquotes)
            val finalResponse = if (thinkingDetected) {
                actualResponseBuilder.toString()
            } else {
                fullResponse.toString()
            }

            return when {
                detectedToolCall != null -> ProviderStreamingResult.ToolCallDetected(
                    toolCall = detectedToolCall!!,
                    precedingText = actualResponseBuilder.toString(),
                    thoughts = thoughtsBuilder.toString().takeIf { it.isNotEmpty() },
                    thinkingDurationSeconds = thinkingDuration
                )
                finalResponse.isNotEmpty() -> ProviderStreamingResult.TextComplete(
                    fullText = finalResponse,
                    thoughts = thoughtsBuilder.toString().takeIf { it.isNotEmpty() },
                    thinkingDurationSeconds = thinkingDuration
                )
                else -> ProviderStreamingResult.Error("Empty response from Poe")
            }
        }
    }

    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback
    ): ProviderStreamingResult {
        Log.d("TOOL_CALL_DEBUG", "Poe: Starting to read stream...")

        val parser = EventDataStreamParser(
            json = json,
            stopEvents = setOf("done"),
            logTag = "PoeProvider"
        )
        val handler = PoeEventHandler(callback)

        val result = parser.parse(reader, handler)

        reader.close()
        connection.disconnect()

        // If parser returned an error (from StreamAction.Error), use that
        if (result is StreamResult.Error) {
            return ProviderStreamingResult.Error(result.message)
        }

        return handler.getResult()
    }

    private fun buildMessages(
        messages: List<Message>,
        systemPrompt: String,
        webSearchEnabled: Boolean,
        thinkingBudget: ThinkingBudgetValue,
        skipToolMessages: Boolean = false
    ): List<JsonObject> {
        val conversationMessages = mutableListOf<JsonObject>()

        if (systemPrompt.isNotBlank()) {
            conversationMessages.add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
                put("content_type", "text/markdown")
            })
        }

        val lastUserMessageIndex = messages.indexOfLast { it.role == "user" }

        messages.forEachIndexed { index, message ->
            when (message.role) {
                "user" -> {
                    val messageObj = buildJsonObject {
                        put("role", "user")
                        put("content", message.text)
                        put("content_type", "text/markdown")

                        if (webSearchEnabled && index == lastUserMessageIndex) {
                            put("parameters", buildJsonObject {
                                put("web_search", true)
                                if (thinkingBudget is ThinkingBudgetValue.Effort && thinkingBudget.level.lowercase() != "none") {
                                    put("thinking_level", thinkingBudget.level)
                                }
                            })
                        } else if (thinkingBudget is ThinkingBudgetValue.Effort && thinkingBudget.level.lowercase() != "none" && index == lastUserMessageIndex) {
                            // Case where web search is disabled but thinking is enabled
                            put("parameters", buildJsonObject {
                                put("thinking_level", thinkingBudget.level)
                            })
                        }

                        if (message.attachments.isNotEmpty()) {
                            put("attachments", buildJsonArray {
                                message.attachments.forEach { attachment ->
                                    add(buildJsonObject {
                                        put("url", attachment.file_POE_url ?: "{file_URL}")
                                        put("content_type", attachment.mime_type)
                                        put("name", attachment.file_name)
                                        put("inline_ref", JsonNull)
                                        put("parsed_content", JsonNull)
                                    })
                                }
                            })
                        }
                    }
                    conversationMessages.add(messageObj)
                }
                "assistant" -> {
                    conversationMessages.add(buildJsonObject {
                        put("role", "bot")
                        put("content", message.text)
                        put("content_type", "text/markdown")
                    })
                }
                "tool_call", "tool_response" -> {
                    if (!skipToolMessages) {
                        // Skip - Poe handles these outside the conversation flow
                    }
                }
            }
        }

        return conversationMessages
    }

    private fun buildRequestBody(
        conversationMessages: List<JsonObject>,
        enabledTools: List<ToolSpecification>
    ): JsonObject {
        return buildJsonObject {
            put("version", "1.2")
            put("type", "query")
            put("query", JsonArray(conversationMessages))
            put("user_id", "")
            put("conversation_id", "")
            put("message_id", "")

            if (enabledTools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    enabledTools.forEach { toolSpec ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", toolSpec.name)
                                put("description", toolSpec.description)
                                if (toolSpec.parameters != null) {
                                    put("parameters", toolSpec.parameters)
                                } else {
                                    put("parameters", buildJsonObject {})
                                }
                            })
                        })
                    }
                })
            }
        }
    }

    private fun buildRequestBodyWithToolResults(
        conversationMessages: List<JsonObject>,
        enabledTools: List<ToolSpecification>,
        toolCall: ToolCall,
        toolResult: ToolExecutionResult
    ): JsonObject {
        return buildJsonObject {
            put("version", "1.2")
            put("type", "query")
            put("query", JsonArray(conversationMessages))
            put("user_id", "")
            put("conversation_id", "")
            put("message_id", "")

            if (enabledTools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    enabledTools.forEach { toolSpec ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", toolSpec.name)
                                put("description", toolSpec.description)
                                if (toolSpec.parameters != null) {
                                    put("parameters", toolSpec.parameters)
                                } else {
                                    put("parameters", buildJsonObject {})
                                }
                            })
                        })
                    }
                })
            }

            // Add tool_calls
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", toolCall.id)
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", toolCall.toolId)
                        put("arguments", json.encodeToString(toolCall.parameters))
                    })
                })
            })

            // Add tool_results
            put("tool_results", buildJsonArray {
                add(buildJsonObject {
                    put("role", "tool")
                    put("name", toolCall.toolId)
                    put("tool_call_id", toolCall.id)
                    put("content", when (toolResult) {
                        is ToolExecutionResult.Success -> toolResult.result
                        is ToolExecutionResult.Error -> "{\"error\":\"${toolResult.error}\"}"
                    })
                })
            })
        }
    }

}
