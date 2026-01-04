package com.example.ApI.data.network.providers

import android.content.Context
import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.data.network.streaming.DataOnlyStreamParser
import com.example.ApI.data.network.streaming.StreamAction
import com.example.ApI.data.network.streaming.StreamEventHandler
import com.example.ApI.data.network.streaming.StreamResult
import com.example.ApI.tools.ToolCall
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
 * OpenAI API provider implementation.
 * Handles streaming responses, tool calling, and thinking/reasoning support.
 */
class OpenAIProvider(context: Context) : BaseProvider(context) {

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
            // Make streaming request and parse response
            val streamingResponse = makeStreamingRequest(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, callback, temperature = temperature
            )

            when (streamingResponse) {
                is ProviderStreamingResult.TextComplete -> {
                    Log.d("TOOL_CALL_DEBUG", "Streaming: Text response complete")
                    callback.onComplete(streamingResponse.fullText)
                }
                is ProviderStreamingResult.ToolCallDetected -> {
                    handleToolCall(
                        provider, modelName, messages, systemPrompt, apiKey,
                        webSearchEnabled, enabledTools, thinkingBudget, temperature,
                        streamingResponse, callback
                    )
                }
                is ProviderStreamingResult.Error -> {
                    // Already called callback.onError in makeStreamingRequest
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send OpenAI streaming message: ${e.message}")
        }
    }

    /**
     * Handles tool call execution and chaining for OpenAI
     */
    private suspend fun handleToolCall(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?,
        initialResponse: ProviderStreamingResult.ToolCallDetected,
        callback: StreamingCallback
    ) {
        Log.d("TOOL_CALL_DEBUG", "OpenAI Streaming: Tool call detected - ${initialResponse.toolCall.toolId}")

        val finalText = handleToolCallChain(
            initialToolCall = initialResponse.toolCall,
            initialPrecedingText = initialResponse.precedingText,
            messages = messages,
            modelName = modelName,
            callback = callback
        ) { updatedMessages ->
            makeStreamingRequest(
                provider, modelName, updatedMessages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, callback,
                temperature = temperature
            )
        }

        if (finalText != null) {
            callback.onComplete(finalText)
        } else {
            // Max depth exceeded (error already reported by handleToolCallChain if it was an error)
            callback.onError("Maximum tool call depth ($MAX_TOOL_DEPTH) exceeded")
        }
    }

    /**
     * Makes a streaming OpenAI API request and returns the parsed result
     */
    private suspend fun makeStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
        callback: StreamingCallback,
        maxToolDepth: Int = MAX_TOOL_DEPTH,
        currentDepth: Int = 0,
        requestReasoningSummary: Boolean = true,
        temperature: Float? = null
    ): ProviderStreamingResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(provider.request.base_url)
            val connection = url.openConnection() as HttpURLConnection

            // Set headers
            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // Build conversation input
            val conversationInput = buildInput(messages, systemPrompt)

            // Build request body with streaming enabled
            val requestBody = buildRequestBody(
                modelName, conversationInput, webSearchEnabled, enabledTools,
                thinkingBudget, requestReasoningSummary, temperature
            )

            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(json.encodeToString(requestBody))
            writer.flush()
            writer.close()

            // Read streaming response
            val responseCode = connection.responseCode

            if (responseCode >= 400) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                connection.disconnect()

                if (responseCode == 400) {
                    // Check if this is the reasoning.summary error
                    if (requestReasoningSummary && errorBody.contains("reasoning.summary")) {
                        Log.d("OPENAI_REASONING", "Reasoning summary not available for this account, retrying without it")
                        return@withContext makeStreamingRequest(
                            provider, modelName, messages, systemPrompt, apiKey,
                            webSearchEnabled, enabledTools, thinkingBudget, callback,
                            maxToolDepth, currentDepth, requestReasoningSummary = false,
                            temperature = temperature
                        )
                    }

                    // Other 400 errors - fallback to non-streaming
                    sendNonStreaming(provider, modelName, messages, systemPrompt, apiKey, webSearchEnabled, enabledTools, callback)
                    return@withContext ProviderStreamingResult.Error("Fallback to non-streaming handled")
                } else {
                    callback.onError("HTTP $responseCode: $errorBody")
                    return@withContext ProviderStreamingResult.Error("HTTP $responseCode: $errorBody")
                }
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            parseStreamingResponse(reader, connection, callback, thinkingBudget, requestReasoningSummary)
        } catch (e: Exception) {
            callback.onError("Failed to make OpenAI streaming request: ${e.message}")
            ProviderStreamingResult.Error("Failed to make OpenAI streaming request: ${e.message}")
        }
    }

    /**
     * Inner class to handle OpenAI SSE events.
     * Implements StreamEventHandler for use with DataOnlyStreamParser.
     */
    private inner class OpenAIEventHandler(
        private val callback: StreamingCallback,
        private val thinkingBudget: ThinkingBudgetValue,
        private val requestReasoningSummary: Boolean
    ) : StreamEventHandler {

        // Response accumulation
        val fullResponse = StringBuilder()

        // Tool call tracking
        var detectedToolCall: ToolCall? = null

        // Reasoning/thinking state tracking
        var isInReasoningPhase = false
        var reasoningStartTime: Long = 0
        val reasoningBuilder = StringBuilder()

        // Error tracking
        var errorMessage: String? = null

        init {
            // Track if reasoning summaries are unavailable
            val reasoningSummariesUnavailable = !requestReasoningSummary &&
                thinkingBudget is ThinkingBudgetValue.Effort &&
                thinkingBudget.level.isNotBlank()

            if (reasoningSummariesUnavailable) {
                isInReasoningPhase = true
                reasoningStartTime = System.currentTimeMillis()
                callback.onThinkingStarted()
                Log.d("OPENAI_REASONING", "Reasoning phase started (summaries unavailable, timing from request)")
            }
        }

        override fun onEvent(eventType: String?, data: JsonObject): StreamAction {
            when (eventType) {
                "response.output_item.added" -> {
                    val item = data["item"]?.jsonObject
                    val itemType = item?.get("type")?.jsonPrimitive?.content
                    if (itemType == "reasoning") {
                        if (!isInReasoningPhase) {
                            isInReasoningPhase = true
                            reasoningStartTime = System.currentTimeMillis()
                            callback.onThinkingStarted()
                            Log.d("OPENAI_REASONING", "Reasoning phase started")
                        }
                    }
                }

                "response.reasoning_summary_text.delta" -> {
                    val deltaText = data["delta"]?.jsonPrimitive?.content
                    if (deltaText != null && deltaText.isNotEmpty()) {
                        reasoningBuilder.append(deltaText)
                        callback.onThinkingPartial(deltaText)
                    }
                }

                "response.output_text.delta" -> {
                    if (isInReasoningPhase) {
                        completeThinkingPhase(callback, reasoningStartTime, reasoningBuilder)
                        isInReasoningPhase = false
                    }

                    val deltaText = data["delta"]?.jsonPrimitive?.content
                    if (deltaText != null) {
                        fullResponse.append(deltaText)
                        callback.onPartialResponse(deltaText)
                    }
                }

                "response.output_item.done" -> {
                    val item = data["item"]?.jsonObject
                    val itemType = item?.get("type")?.jsonPrimitive?.content

                    if (itemType == "function_call") {
                        if (isInReasoningPhase) {
                            completeThinkingPhase(callback, reasoningStartTime, reasoningBuilder)
                            isInReasoningPhase = false
                        }

                        detectedToolCall = parseToolCall(item)
                    }
                }

                "response.completed" -> {
                    if (isInReasoningPhase) {
                        completeThinkingPhase(callback, reasoningStartTime, reasoningBuilder)
                        isInReasoningPhase = false
                    }

                    if (detectedToolCall == null) {
                        detectedToolCall = parseToolCallFromResponse(data)
                    }
                    return StreamAction.Stop
                }

                "response.failed" -> {
                    val error = data["response"]?.jsonObject?.get("error")?.jsonObject
                    errorMessage = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                    callback.onError("OpenAI API error: $errorMessage")
                    return StreamAction.Error("OpenAI API error: $errorMessage")
                }
            }

            return StreamAction.Continue
        }

        override fun onStreamEnd() {
            // Stream ended - complete thinking phase if still in progress
            if (isInReasoningPhase) {
                completeThinkingPhase(callback, reasoningStartTime, reasoningBuilder)
                isInReasoningPhase = false
            }
        }

        override fun onParseError(line: String, error: Exception) {
            Log.e("TOOL_CALL_DEBUG", "Error parsing streaming chunk: ${error.message}")
        }

        /**
         * Build the final result based on accumulated state.
         */
        fun getResult(): ProviderStreamingResult {
            // Check for error first
            errorMessage?.let {
                return ProviderStreamingResult.Error(it)
            }

            val thoughtsContent = reasoningBuilder.toString().takeIf { it.isNotEmpty() }
            val thinkingDuration = if (reasoningStartTime > 0) {
                (System.currentTimeMillis() - reasoningStartTime) / 1000f
            } else null
            val thoughtsStatus = when {
                thoughtsContent != null -> ThoughtsStatus.PRESENT
                // Reasoning was enabled but summaries unavailable
                thinkingBudget is ThinkingBudgetValue.Effort && thinkingBudget.level.isNotBlank() && !requestReasoningSummary ->
                    ThoughtsStatus.UNAVAILABLE
                else -> ThoughtsStatus.NONE
            }

            return when {
                detectedToolCall != null -> ProviderStreamingResult.ToolCallDetected(
                    toolCall = detectedToolCall!!,
                    precedingText = fullResponse.toString(),
                    thoughts = thoughtsContent,
                    thinkingDurationSeconds = thinkingDuration,
                    thoughtsStatus = thoughtsStatus
                )
                fullResponse.isNotEmpty() -> ProviderStreamingResult.TextComplete(
                    fullText = fullResponse.toString(),
                    thoughts = thoughtsContent,
                    thinkingDurationSeconds = thinkingDuration,
                    thoughtsStatus = thoughtsStatus
                )
                else -> ProviderStreamingResult.Error("Empty response from OpenAI")
            }
        }
    }

    /**
     * Parses the streaming response from OpenAI
     */
    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback,
        thinkingBudget: ThinkingBudgetValue,
        requestReasoningSummary: Boolean
    ): ProviderStreamingResult {
        val parser = DataOnlyStreamParser(
            json = json,
            eventTypeField = "type",
            doneMarker = "[DONE]",
            skipKeepalives = false,
            logTag = "OpenAIProvider"
        )
        val handler = OpenAIEventHandler(callback, thinkingBudget, requestReasoningSummary)

        val result = parser.parse(reader, handler)

        reader.close()
        connection.disconnect()

        // If parser returned an error (from StreamAction.Error), use that
        if (result is StreamResult.Error) {
            return ProviderStreamingResult.Error(result.message)
        }

        return handler.getResult()
    }

    private fun parseToolCall(item: JsonObject?): ToolCall? {
        val status = item?.get("status")?.jsonPrimitive?.content
        if (status == "completed") {
            val name = item["name"]?.jsonPrimitive?.content
            val callId = item["call_id"]?.jsonPrimitive?.content
            val arguments = item["arguments"]?.jsonPrimitive?.content

            if (name != null && callId != null && arguments != null) {
                val paramsJson = json.parseToJsonElement(arguments).jsonObject
                Log.d("TOOL_CALL_DEBUG", "Streaming: Detected tool call in output_item.done")
                return ToolCall(
                    id = callId,
                    toolId = name,
                    parameters = paramsJson,
                    provider = "openai"
                )
            }
        }
        return null
    }

    private fun parseToolCallFromResponse(chunkJson: JsonObject): ToolCall? {
        val response = chunkJson["response"]?.jsonObject
        val output = response?.get("output")?.jsonArray

        output?.forEach { outputItem ->
            val outputObj = outputItem.jsonObject
            if (outputObj["type"]?.jsonPrimitive?.content == "function_call") {
                val status = outputObj["status"]?.jsonPrimitive?.content
                if (status == "completed") {
                    val name = outputObj["name"]?.jsonPrimitive?.content
                    val callId = outputObj["call_id"]?.jsonPrimitive?.content
                    val arguments = outputObj["arguments"]?.jsonPrimitive?.content

                    if (name != null && callId != null && arguments != null) {
                        val paramsJson = json.parseToJsonElement(arguments).jsonObject
                        Log.d("TOOL_CALL_DEBUG", "Streaming: Detected tool call in response.completed")
                        return ToolCall(
                            id = callId,
                            toolId = name,
                            parameters = paramsJson,
                            provider = "openai"
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * Builds the input array for OpenAI API request
     */
    private fun buildInput(messages: List<Message>, systemPrompt: String): List<JsonObject> {
        val conversationInput = mutableListOf<JsonObject>()

        // Add system prompt
        if (systemPrompt.isNotBlank()) {
            conversationInput.add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        // Add messages from conversation history
        messages.forEach { message ->
            when (message.role) {
                "user" -> {
                    conversationInput.add(buildUserMessage(message))
                }
                "assistant" -> {
                    conversationInput.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", message.text)
                    })
                }
                "tool_call" -> {
                    conversationInput.add(buildJsonObject {
                        put("type", "function_call")
                        put("name", message.toolCall?.toolId ?: "unknown")
                        put("arguments", message.toolCall?.parameters?.toString() ?: "{}")
                        put("call_id", message.toolCallId ?: "")
                    })
                }
                "tool_response" -> {
                    conversationInput.add(buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", message.toolResponseCallId ?: "")
                        put("output", message.toolResponseOutput ?: "")
                    })
                }
            }
        }

        return conversationInput
    }

    private fun buildUserMessage(message: Message): JsonObject {
        return if (message.attachments.isEmpty()) {
            buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", message.text)
                    })
                })
            }
        } else {
            buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", message.text)
                    })
                    message.attachments.forEach { attachment ->
                        when {
                            attachment.mime_type.startsWith("image/") -> {
                                add(buildJsonObject {
                                    put("type", "input_image")
                                    put("file_id", attachment.file_OPENAI_id ?: "{file_ID}")
                                })
                            }
                            attachment.mime_type == "application/pdf" -> {
                                add(buildJsonObject {
                                    put("type", "input_file")
                                    put("file_id", attachment.file_OPENAI_id ?: "{file_ID}")
                                })
                            }
                            else -> {
                                val fileContent = readTextFileContent(attachment.local_file_path, attachment.file_name)
                                if (fileContent != null) {
                                    add(buildJsonObject {
                                        put("type", "input_text")
                                        put("text", "[ATTACHED FILE: ${attachment.file_name}]\n[MIME TYPE: ${attachment.mime_type}]\n[CONTENT START]\n$fileContent\n[CONTENT END]")
                                    })
                                }
                            }
                        }
                    }
                })
            }
        }
    }

    private fun buildRequestBody(
        modelName: String,
        conversationInput: List<JsonObject>,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        requestReasoningSummary: Boolean,
        temperature: Float?
    ): JsonObject {
        return buildJsonObject {
            put("model", modelName)
            put("input", JsonArray(conversationInput))
            put("stream", true)

            if (temperature != null) {
                put("temperature", temperature.toDouble())
            }

            // Add reasoning parameter
            if (thinkingBudget is ThinkingBudgetValue.Effort && thinkingBudget.level.isNotBlank()) {
                put("reasoning", buildJsonObject {
                    put("effort", thinkingBudget.level)
                    if (requestReasoningSummary) {
                        put("summary", "detailed")
                    }
                })
            }

            // Add tools section
            val toolsArray = buildToolsArray(webSearchEnabled, enabledTools)
            if (toolsArray.isNotEmpty()) {
                put("tools", toolsArray)
            }
        }
    }

    private fun buildToolsArray(
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>
    ): JsonArray {
        return buildJsonArray {
            if (webSearchEnabled) {
                add(buildJsonObject {
                    put("type", "web_search_preview")
                })
            }
            enabledTools.forEach { toolSpec ->
                add(buildJsonObject {
                    put("type", "function")
                    put("name", toolSpec.name)
                    put("description", toolSpec.description)
                    if (toolSpec.parameters != null) {
                        val newParametersObject = buildJsonObject {
                            toolSpec.parameters.forEach { (key, value) ->
                                put(key, value)
                            }
                            put("additionalProperties", false)
                        }
                        put("parameters", newParametersObject)
                    } else {
                        put("parameters", buildJsonObject {})
                    }
                    put("strict", true)
                })
            }
        }
    }

    /**
     * Non-streaming fallback for when streaming fails
     */
    private suspend fun sendNonStreaming(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        callback: StreamingCallback
    ) {
        try {
            val response = makeNonStreamingRequest(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools
            )

            when (response) {
                is NonStreamingResponse.TextResponse -> {
                    callback.onComplete(response.text)
                }
                is NonStreamingResponse.ToolCallResponse -> {
                    Log.d("TOOL_CALL_DEBUG", "Non-streaming: Tool call detected - ${response.toolCall.toolId}")

                    val toolResult = callback.onToolCall(response.toolCall)
                    Log.d("TOOL_CALL_DEBUG", "Non-streaming: Tool executed with result: $toolResult")

                    val toolCallMessage = createToolCallMessage(response.toolCall, toolResult)
                    val toolResponseMessage = createToolResponseMessage(response.toolCall, toolResult)

                    callback.onSaveToolMessages(toolCallMessage, toolResponseMessage, "")

                    val messagesWithToolResult = messages + listOf(toolCallMessage, toolResponseMessage)

                    Log.d("TOOL_CALL_DEBUG", "Non-streaming: Sending follow-up request with tool result")

                    val followUpResponse = makeNonStreamingRequest(
                        provider, modelName, messagesWithToolResult, systemPrompt, apiKey,
                        webSearchEnabled, enabledTools
                    )

                    when (followUpResponse) {
                        is NonStreamingResponse.TextResponse -> {
                            Log.d("TOOL_CALL_DEBUG", "Non-streaming: Got final text response")
                            callback.onComplete(followUpResponse.text)
                        }
                        is NonStreamingResponse.ToolCallResponse -> {
                            callback.onError("Multiple tool calls not yet supported")
                        }
                        is NonStreamingResponse.ErrorResponse -> {
                            callback.onError(followUpResponse.error)
                        }
                    }
                }
                is NonStreamingResponse.ErrorResponse -> {
                    callback.onError(response.error)
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send OpenAI non-streaming message: ${e.message}")
        }
    }

    private suspend fun makeNonStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>
    ): NonStreamingResponse = withContext(Dispatchers.IO) {
        try {
            val url = URL(provider.request.base_url)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val conversationInput = buildInput(messages, systemPrompt)

            val requestBody = buildJsonObject {
                put("model", modelName)
                put("input", JsonArray(conversationInput))

                val toolsArray = buildToolsArray(webSearchEnabled, enabledTools)
                if (toolsArray.isNotEmpty()) {
                    put("tools", toolsArray)
                }
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(json.encodeToString(requestBody))
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            val reader = if (responseCode >= 400) {
                BufferedReader(InputStreamReader(connection.errorStream))
            } else {
                BufferedReader(InputStreamReader(connection.inputStream))
            }

            val response = reader.readText()
            reader.close()
            connection.disconnect()

            if (responseCode >= 400) {
                return@withContext NonStreamingResponse.ErrorResponse("HTTP $responseCode: $response")
            }

            parseNonStreamingResponse(response)
        } catch (e: Exception) {
            NonStreamingResponse.ErrorResponse("Failed to make OpenAI request: ${e.message}")
        }
    }

    private fun parseNonStreamingResponse(response: String): NonStreamingResponse {
        try {
            val responseJson = json.parseToJsonElement(response).jsonObject
            val outputArray = responseJson["output"]?.jsonArray
                ?: return NonStreamingResponse.ErrorResponse("No output in response")

            var responseText = ""
            var toolCall: ToolCall? = null

            outputArray.forEach { outputElement ->
                val outputObj = outputElement.jsonObject
                when (outputObj["type"]?.jsonPrimitive?.content) {
                    "message" -> {
                        val content = outputObj["content"]?.jsonArray
                        content?.forEach { contentElement ->
                            val contentObj = contentElement.jsonObject
                            if (contentObj["type"]?.jsonPrimitive?.content == "output_text") {
                                responseText = contentObj["text"]?.jsonPrimitive?.content ?: ""
                            }
                        }
                    }
                    "function_call" -> {
                        val status = outputObj["status"]?.jsonPrimitive?.content
                        if (status == "completed") {
                            val name = outputObj["name"]?.jsonPrimitive?.content
                            val callId = outputObj["call_id"]?.jsonPrimitive?.content
                            val arguments = outputObj["arguments"]?.jsonPrimitive?.content

                            if (name != null && callId != null && arguments != null) {
                                val paramsJson = json.parseToJsonElement(arguments).jsonObject
                                toolCall = ToolCall(
                                    id = callId,
                                    toolId = name,
                                    parameters = paramsJson,
                                    provider = "openai"
                                )
                            }
                        }
                    }
                }
            }

            return when {
                toolCall != null -> NonStreamingResponse.ToolCallResponse(toolCall!!)
                responseText.isNotEmpty() -> NonStreamingResponse.TextResponse(responseText)
                else -> NonStreamingResponse.ErrorResponse("Empty response from OpenAI")
            }
        } catch (e: Exception) {
            return NonStreamingResponse.ErrorResponse("Failed to parse response: ${e.message}")
        }
    }

    private sealed class NonStreamingResponse {
        data class TextResponse(val text: String) : NonStreamingResponse()
        data class ToolCallResponse(val toolCall: ToolCall) : NonStreamingResponse()
        data class ErrorResponse(val error: String) : NonStreamingResponse()
    }
}
