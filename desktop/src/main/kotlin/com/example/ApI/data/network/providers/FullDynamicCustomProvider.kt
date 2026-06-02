package com.example.ApI.data.network.providers

import android.content.Context
import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.data.network.streaming.DataOnlyStreamParser
import com.example.ApI.data.network.streaming.EventDataStreamParser
import com.example.ApI.data.network.streaming.SSEStreamParser
import com.example.ApI.data.network.streaming.StreamAction
import com.example.ApI.data.network.streaming.StreamEventHandler
import com.example.ApI.data.network.streaming.StreamResult
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.util.TemplateExpander
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fully dynamic provider that supports custom API configurations.
 * Uses FullCustomProviderConfig to define:
 * - Request body template with placeholders
 * - Parser type (EventDataStreamParser or DataOnlyStreamParser)
 * - Event mappings for semantic actions (TEXT_CONTENT, STREAM_END, etc.)
 *
 * This allows users to define their own provider integrations without
 * requiring code changes.
 */
class FullDynamicCustomProvider(
    context: Context,
    private val config: FullCustomProviderConfig
) : BaseProvider(context) {

    private val logTag = "FullCustomProvider[${config.name}]"

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
            val result = makeStreamingRequest(
                modelName = modelName,
                messages = messages,
                systemPrompt = systemPrompt,
                apiKey = apiKey,
                enabledTools = enabledTools,
                thinkingBudget = thinkingBudget,
                callback = callback,
                temperature = temperature
            )

            when (result) {
                is ProviderStreamingResult.TextComplete -> {
                    callback.onComplete(result.fullText)
                }
                is ProviderStreamingResult.ToolCallDetected -> {
                    // Handle tool call using the base provider's tool chain handler
                    if (config.toolCallConfig.isConfigured()) {
                        Log.d(logTag, "Tool call detected: ${result.toolCall.toolId}")
                        val finalText = handleToolCallChain(
                            initialToolCall = result.toolCall,
                            initialPrecedingText = result.precedingText,
                            messages = messages,
                            modelName = modelName,
                            callback = callback
                        ) { updatedMessages ->
                            makeStreamingRequest(
                                modelName = modelName,
                                messages = updatedMessages,
                                systemPrompt = systemPrompt,
                                apiKey = apiKey,
                                enabledTools = enabledTools,
                                thinkingBudget = thinkingBudget,
                                callback = callback,
                                temperature = temperature
                            )
                        }
                        if (finalText != null) {
                            callback.onComplete(finalText)
                        }
                    } else {
                        // Tool calling not configured - complete with any preceding text
                        callback.onComplete(result.precedingText)
                    }
                }
                is ProviderStreamingResult.Error -> {
                    callback.onError(result.error)
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error sending message", e)
            callback.onError("Failed to send message: ${e.message}")
        }
    }

    private suspend fun makeStreamingRequest(
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        callback: StreamingCallback,
        temperature: Float?
    ): ProviderStreamingResult = withContext(Dispatchers.IO) {
        try {
            // Expand placeholders in URL
            val expandedUrl = TemplateExpander.expandSimplePlaceholders(
                template = config.baseUrl,
                apiKey = apiKey,
                model = modelName,
                systemPrompt = systemPrompt
            )
            val url = URL(expandedUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // Apply authorization header (with placeholder expansion)
            applyAuthorizationHeader(connection, apiKey, modelName, systemPrompt)

            // Add extra headers from config (with placeholder expansion)
            config.extraHeaders.forEach { (name, value) ->
                val expandedValue = TemplateExpander.expandSimplePlaceholders(
                    template = value,
                    apiKey = apiKey,
                    model = modelName,
                    systemPrompt = systemPrompt
                )
                connection.setRequestProperty(name, expandedValue)
            }

            // Build request body using template expander
            // Use messageFields if configured, otherwise fall back to auto-detection
            val requestBody = if (config.messageFields != null && config.messageFields.hasAnyFields()) {
                TemplateExpander.expandTemplateWithMessageFields(
                    template = config.bodyTemplate,
                    messageFields = config.messageFields,
                    apiKey = apiKey,
                    model = modelName,
                    messages = messages,
                    systemPrompt = systemPrompt,
                    tools = enabledTools
                )
            } else {
                TemplateExpander.expandTemplate(
                    template = config.bodyTemplate,
                    apiKey = apiKey,
                    model = modelName,
                    messages = messages,
                    systemPrompt = systemPrompt,
                    tools = enabledTools
                )
            }

            // Log request details (with expanded values)
            val expandedAuthHeader = TemplateExpander.expandSimplePlaceholders(
                template = config.authHeaderFormat,
                apiKey = apiKey,
                model = modelName,
                systemPrompt = systemPrompt
            )
            val headers = mutableMapOf<String, String>(
                "Content-Type" to "application/json",
                config.authHeaderName to expandedAuthHeader
            )
            config.extraHeaders.forEach { (name, value) ->
                val expandedValue = TemplateExpander.expandSimplePlaceholders(
                    template = value,
                    apiKey = apiKey,
                    model = modelName,
                    systemPrompt = systemPrompt
                )
                headers[name] = expandedValue
            }
            logApiRequestDetails(
                baseUrl = expandedUrl,
                headers = headers,
                body = requestBody
            )

            connection.outputStream.write(requestBody.toByteArray())
            connection.outputStream.flush()

            val responseCode = connection.responseCode
            logApiResponse(responseCode)

            if (responseCode >= 400) {
                val errorStream = connection.errorStream
                val errorBody = errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                logApiError(errorBody)
                val errorMessage = parseErrorMessage(errorBody, responseCode)
                callback.onError(errorMessage)
                return@withContext ProviderStreamingResult.Error(errorMessage)
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            parseStreamingResponse(reader, connection, callback)
        } catch (e: Exception) {
            Log.e(logTag, "Error making streaming request", e)
            callback.onError("Failed to make streaming request: ${e.message}")
            ProviderStreamingResult.Error("Failed to make streaming request: ${e.message}")
        }
    }

    /**
     * Apply authorization header using the configured format.
     * Supports placeholder expansion for {key}, {model}, {system}.
     */
    private fun applyAuthorizationHeader(
        connection: HttpURLConnection,
        apiKey: String,
        modelName: String,
        systemPrompt: String
    ) {
        val headerValue = TemplateExpander.expandSimplePlaceholders(
            template = config.authHeaderFormat,
            apiKey = apiKey,
            model = modelName,
            systemPrompt = systemPrompt
        )
        connection.setRequestProperty(config.authHeaderName, headerValue)
    }

    /**
     * Parse error message from API response.
     */
    private fun parseErrorMessage(errorBody: String, responseCode: Int): String {
        return try {
            val errorJson = json.parseToJsonElement(errorBody).jsonObject
            // Try common error message fields
            errorJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: errorJson["message"]?.jsonPrimitive?.content
                ?: errorJson["detail"]?.jsonPrimitive?.content
                ?: "API error ($responseCode): $errorBody"
        } catch (e: Exception) {
            "API error ($responseCode): $errorBody"
        }
    }

    /**
     * Parse streaming response using the configured parser and event mappings.
     */
    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback
    ): ProviderStreamingResult {
        // Create the appropriate parser based on config
        val parser = createParser()
        val handler = DynamicEventHandler(callback)

        val result = parser.parse(reader, handler)

        reader.close()
        connection.disconnect()

        // If parser returned an error, use that
        if (result is StreamResult.Error) {
            return ProviderStreamingResult.Error(result.message)
        }

        return handler.getResult()
    }

    /**
     * Create the appropriate SSE parser based on configuration.
     */
    private fun createParser(): SSEStreamParser {
        return when (config.parserType) {
            StreamParserType.EVENT_DATA -> {
                EventDataStreamParser(
                    json = json,
                    stopEvents = config.parserConfig.stopEvents.toSet(),
                    logTag = logTag
                )
            }
            StreamParserType.DATA_ONLY -> {
                DataOnlyStreamParser(
                    json = json,
                    eventTypeField = config.parserConfig.eventTypeField,
                    doneMarker = config.parserConfig.doneMarker,
                    skipKeepalives = config.parserConfig.skipKeepalives,
                    logTag = logTag
                )
            }
        }
    }

    /**
     * Dynamic event handler that uses user-defined event mappings.
     */
    private inner class DynamicEventHandler(
        private val callback: StreamingCallback
    ) : StreamEventHandler {

        // Response accumulation
        val fullResponse = StringBuilder()

        // Thinking state tracking
        var isInThinkingPhase = false
        var thinkingStartTime: Long = 0
        val thoughtsBuilder = StringBuilder()

        // Tool call state tracking
        var detectedToolName: String? = null    // Internal tool name (e.g., "get_date_time") - from toolNamePath
        var detectedCallId: String? = null      // Unique call ID (e.g., "call_abc123") - from toolIdPath
        val toolParametersBuilder = StringBuilder()
        var toolCallDetected = false

        // Error tracking
        var errorMessage: String? = null

        override fun onEvent(eventType: String?, data: JsonObject): StreamAction {
            Log.d(logTag, "Event received: type=$eventType, data=$data")

            // First, check for tool calls if configured
            if (config.toolCallConfig.isConfigured()) {
                val toolCallAction = handleToolCallEvent(eventType, data)
                if (toolCallAction != null) {
                    return toolCallAction
                }
            }

            // Check each mapping to see if this event matches
            for ((semanticType, mapping) in config.eventMappings) {
                // For EventDataStreamParser, eventType comes from the "event:" line
                // For DataOnlyStreamParser, eventType comes from the configured eventTypeField in the JSON

                val matches = when {
                    // Match by event type if we have one
                    eventType != null && mapping.eventName == eventType -> true
                    // For structure-based matching (no eventType), check if data matches expected structure
                    eventType == null && mapping.eventName.isEmpty() -> {
                        // Try to extract value using field path - if it exists, this matches
                        TemplateExpander.extractByPath(data, mapping.fieldPath) != null
                    }
                    else -> false
                }

                if (matches) {
                    return handleSemanticEvent(semanticType, mapping, data)
                }
            }

            // No mapping matched - continue processing
            // This is normal for events we don't care about
            return StreamAction.Continue
        }

        /**
         * Handle potential tool call events based on toolCallConfig.
         * Returns StreamAction if this was a tool call event, null otherwise.
         */
        private fun handleToolCallEvent(eventType: String?, data: JsonObject): StreamAction? {
            val toolConfig = config.toolCallConfig

            // Check if this is a tool call event (using toolNamePath for detection)
            val isToolCallEvent = when {
                toolConfig.eventName.isNotBlank() && eventType == toolConfig.eventName -> true
                toolConfig.eventName.isBlank() && toolConfig.toolNamePath.isNotBlank() -> {
                    // Structure-based matching - check if tool name path exists
                    TemplateExpander.extractByPath(data, toolConfig.toolNamePath) != null
                }
                else -> false
            }

            if (isToolCallEvent) {
                // Extract tool name (required - used for {tool_name} placeholder)
                val toolName = TemplateExpander.extractByPath(data, toolConfig.toolNamePath)
                if (toolName != null) {
                    detectedToolName = toolName
                    Log.d(logTag, "Tool call detected: toolName=$toolName")

                    // Extract call ID if configured (for {tool_id} placeholder)
                    if (toolConfig.toolIdPath.isNotBlank()) {
                        detectedCallId = TemplateExpander.extractByPath(data, toolConfig.toolIdPath)
                        Log.d(logTag, "Tool call ID: $detectedCallId")
                    }

                    // If parameters come in the same event, extract them
                    if (toolConfig.parametersEventName.isBlank() && toolConfig.parametersPath.isNotBlank()) {
                        val params = TemplateExpander.extractByPath(data, toolConfig.parametersPath)
                        if (params != null) {
                            toolParametersBuilder.append(params)
                        }
                    }

                    toolCallDetected = true
                    return StreamAction.Continue
                }
            }

            // Check if this is a parameters delta event (when parameters come separately)
            if (toolConfig.parametersEventName.isNotBlank() && toolConfig.parametersPath.isNotBlank()) {
                val isParamsEvent = eventType == toolConfig.parametersEventName
                if (isParamsEvent) {
                    val paramsChunk = TemplateExpander.extractByPath(data, toolConfig.parametersPath)
                    if (paramsChunk != null) {
                        toolParametersBuilder.append(paramsChunk)
                        Log.d(logTag, "Tool parameters chunk: $paramsChunk")
                    }
                    return StreamAction.Continue
                }
            }

            // Not a tool call event
            return null
        }

        /**
         * Handle a semantic event type with the corresponding mapping.
         */
        private fun handleSemanticEvent(
            semanticType: StreamEventType,
            mapping: EventMapping,
            data: JsonObject
        ): StreamAction {
            when (semanticType) {
                StreamEventType.TEXT_CONTENT -> {
                    val text = TemplateExpander.extractByPath(data, mapping.fieldPath) ?: ""
                    if (text.isNotEmpty()) {
                        fullResponse.append(text)
                        callback.onPartialResponse(text)
                    }
                }

                StreamEventType.STREAM_END -> {
                    // Complete thinking if we were in thinking phase
                    if (isInThinkingPhase) {
                        completeThinkingPhase(callback, thinkingStartTime, thoughtsBuilder)
                        isInThinkingPhase = false
                    }
                    return StreamAction.Stop
                }

                StreamEventType.THINKING_START -> {
                    if (!isInThinkingPhase) {
                        isInThinkingPhase = true
                        thinkingStartTime = System.currentTimeMillis()
                        callback.onThinkingStarted()
                    }
                }

                StreamEventType.THINKING_CONTENT -> {
                    val thinkingText = TemplateExpander.extractByPath(data, mapping.fieldPath) ?: ""
                    if (thinkingText.isNotEmpty()) {
                        thoughtsBuilder.append(thinkingText)
                        callback.onThinkingPartial(thinkingText)
                    }
                }
            }

            return StreamAction.Continue
        }

        override fun onStreamEnd() {
            // Complete thinking if we were in thinking phase
            if (isInThinkingPhase) {
                completeThinkingPhase(callback, thinkingStartTime, thoughtsBuilder)
                isInThinkingPhase = false
            }
        }

        override fun onParseError(line: String, error: Exception) {
            Log.w(logTag, "Parse error on line: $line", error)
            // Continue processing - one bad line shouldn't stop the stream
        }

        /**
         * Build the final result based on accumulated state.
         */
        fun getResult(): ProviderStreamingResult {
            // Check for error first
            errorMessage?.let {
                return ProviderStreamingResult.Error(it)
            }

            val thoughtsContent = thoughtsBuilder.toString().takeIf { it.isNotEmpty() }
            val thinkingDuration = if (thinkingStartTime > 0) {
                (System.currentTimeMillis() - thinkingStartTime) / 1000f
            } else null
            val thoughtsStatus = when {
                thoughtsContent != null -> ThoughtsStatus.PRESENT
                thinkingStartTime > 0 -> ThoughtsStatus.UNAVAILABLE
                else -> ThoughtsStatus.NONE
            }

            // Check if a tool call was detected
            if (toolCallDetected && detectedToolName != null) {
                // Parse tool parameters as JSON
                val paramsJson = try {
                    val paramsStr = toolParametersBuilder.toString().trim()
                    if (paramsStr.isNotBlank()) {
                        json.parseToJsonElement(paramsStr).jsonObject
                    } else {
                        JsonObject(emptyMap())
                    }
                } catch (e: Exception) {
                    Log.w(logTag, "Failed to parse tool parameters as JSON: ${e.message}")
                    JsonObject(emptyMap())
                }

                val toolCall = ToolCall(
                    id = detectedCallId ?: java.util.UUID.randomUUID().toString(),  // {tool_id}
                    toolId = detectedToolName!!,  // {tool_name} - internal tool name
                    parameters = paramsJson,
                    provider = config.providerKey
                )

                Log.d(logTag, "Returning ToolCallDetected: toolName=${toolCall.toolId}, callId=${toolCall.id}, params=$paramsJson")

                return ProviderStreamingResult.ToolCallDetected(
                    toolCall = toolCall,
                    precedingText = fullResponse.toString(),
                    thoughts = thoughtsContent,
                    thinkingDurationSeconds = thinkingDuration,
                    thoughtsStatus = thoughtsStatus
                )
            }

            return if (fullResponse.isNotEmpty()) {
                ProviderStreamingResult.TextComplete(
                    fullText = fullResponse.toString(),
                    thoughts = thoughtsContent,
                    thinkingDurationSeconds = thinkingDuration,
                    thoughtsStatus = thoughtsStatus
                )
            } else {
                // If we have no text but have thoughts, still return success
                if (thoughtsContent != null) {
                    ProviderStreamingResult.TextComplete(
                        fullText = "",
                        thoughts = thoughtsContent,
                        thinkingDurationSeconds = thinkingDuration,
                        thoughtsStatus = thoughtsStatus
                    )
                } else {
                    ProviderStreamingResult.Error("Empty response from ${config.name}")
                }
            }
        }
    }
}
