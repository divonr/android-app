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
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Generic provider for OpenAI-compatible APIs.
 * Base class for OpenRouter, LLM Stats, etc.
 */
abstract class OpenAICompatibleProvider(context: Context) : BaseProvider(context) {

    abstract val providerName: String
    
    protected open val logTag: String
        get() = "${providerName.replaceFirstChar { it.uppercase() }}Provider"

    /**
     * Override to add specific headers like Referer or Title.
     */
    protected open fun addCustomHeaders(connection: HttpURLConnection) {}

    /**
     * Override to format tool parameters.
     * Default implementation follows OpenAI standard: wraps properties in "type": "object".
     */
    protected open fun formatToolParameters(toolSpec: ToolSpecification): JsonElement {
        return buildJsonObject {
            put("type", "object")
            // Extract properties from the parameters schema
            toolSpec.parameters?.get("properties")?.let { props ->
                put("properties", props)
            } ?: put("properties", buildJsonObject {})
            // Extract required array from the parameters schema
            toolSpec.parameters?.get("required")?.let { req ->
                put("required", req)
            } ?: put("required", buildJsonArray {})
        }
    }

    /**
     * Override to provide specific empty response error message.
     */
    protected open fun getEmptyResponseErrorMessage(enabledTools: List<ToolSpecification>): String {
        return "Empty response from $providerName"
    }

    /**
     * Override to customize how the authorization header is applied.
     * Default implementation uses standard Bearer token format.
     */
    protected open fun applyAuthorizationHeader(connection: HttpURLConnection, apiKey: String) {
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
    }

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
                provider,
                modelName,
                messages,
                systemPrompt,
                apiKey,
                enabledTools,
                thinkingBudget,
                callback,
                temperature = temperature
            )

            when (result) {
                is ProviderStreamingResult.TextComplete -> {
                    callback.onComplete(result.fullText)
                }
                is ProviderStreamingResult.ToolCallDetected -> {
                    handleToolCall(
                        provider, modelName, messages, systemPrompt, apiKey,
                        enabledTools, thinkingBudget, temperature,
                        result, callback
                    )
                }
                is ProviderStreamingResult.Error -> {
                    callback.onError(result.error)
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send $providerName message: ${e.message}")
        }
    }

    /**
     * Handles tool call execution and chaining
     */
    private suspend fun handleToolCall(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?,
        initialResponse: ProviderStreamingResult.ToolCallDetected,
        callback: StreamingCallback
    ) {
        Log.d(logTag, "Tool call detected - ${initialResponse.toolCall.toolId}")

        val finalText = handleToolCallChain(
            initialToolCall = initialResponse.toolCall,
            initialPrecedingText = initialResponse.precedingText,
            messages = messages,
            modelName = modelName,
            callback = callback
        ) { updatedMessages ->
            makeStreamingRequest(
                provider, modelName, updatedMessages, systemPrompt, apiKey,
                enabledTools, thinkingBudget, callback, temperature = temperature
            )
        }

        if (finalText != null) {
            callback.onComplete(finalText)
        } else {
            callback.onError("Maximum tool call depth ($MAX_TOOL_DEPTH) exceeded")
        }
    }

    private suspend fun makeStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
        callback: StreamingCallback,
        temperature: Float? = null
    ): ProviderStreamingResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(provider.request.base_url)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = provider.request.request_type
            applyAuthorizationHeader(connection, apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            
            // Add provider-specific headers
            addCustomHeaders(connection)
            
            connection.doOutput = true

            val apiMessages = buildMessages(messages, systemPrompt)
            val requestBody = buildRequestBody(
                modelName, apiMessages, enabledTools, thinkingBudget, temperature
            )

            // Log request details
            logApiRequestDetails(
                baseUrl = provider.request.base_url,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json"
                ),
                body = requestBody.toString()
            )

            connection.outputStream.write(requestBody.toString().toByteArray())
            connection.outputStream.flush()

            val responseCode = connection.responseCode
            logApiResponse(responseCode)

            if (responseCode >= 400) {
                val errorReader = BufferedReader(InputStreamReader(connection.errorStream))
                val errorResponse = errorReader.readText()
                logApiError(errorResponse)
                errorReader.close()
                connection.disconnect()

                // Try to parse error message from JSON response
                val errorMessage = try {
                    val errorJson = json.parseToJsonElement(errorResponse).jsonObject
                    errorJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: errorResponse
                } catch (e: Exception) {
                    errorResponse
                }

                return@withContext ProviderStreamingResult.Error("HTTP $responseCode: $errorMessage")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            parseStreamingResponse(reader, connection, callback, thinkingBudget, enabledTools)
        } catch (e: Exception) {
            ProviderStreamingResult.Error("$providerName request failed: ${e.message}")
        }
    }

    /**
     * Inner class to handle OpenAI-compatible SSE events.
     * Implements StreamEventHandler for use with DataOnlyStreamParser.
     */
    private inner class OpenAICompatibleEventHandler(
        private val callback: StreamingCallback,
        private val thinkingBudget: ThinkingBudgetValue,
        private val enabledTools: List<ToolSpecification>
    ) : StreamEventHandler {

        // Response accumulation
        val fullResponse = StringBuilder()

        // Tool call state
        var detectedToolCall: ToolCall? = null
        val toolCallsBuilder = mutableMapOf<Int, ToolCallBuilder>()

        // Reasoning state
        var isInReasoningPhase = false
        var reasoningStartTime: Long = 0
        val reasoningBuilder = StringBuilder()

        // Error tracking
        var errorMessage: String? = null

        override fun onEvent(eventType: String?, data: JsonObject): StreamAction {
            // Check for error in chunk
            val error = data["error"]?.jsonObject
            if (error != null) {
                errorMessage = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                return StreamAction.Error(errorMessage!!)
            }

            // Extract from choices[0]
            val choices = data["choices"]?.jsonArray
            if (choices != null && choices.isNotEmpty()) {
                val choice = choices[0].jsonObject
                val delta = choice["delta"]?.jsonObject
                val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

                if (delta != null) {
                    // Check for reasoning/thinking content
                    val reasoningDetails = delta["reasoning_details"]?.jsonArray
                    if (reasoningDetails != null && reasoningDetails.isNotEmpty()) {
                        if (!isInReasoningPhase) {
                            isInReasoningPhase = true
                            reasoningStartTime = System.currentTimeMillis()
                            callback.onThinkingStarted()
                            Log.d(logTag, "Reasoning phase started")
                        }

                        reasoningDetails.forEach { detail ->
                            val detailObj = detail.jsonObject
                            val reasoning = detailObj["reasoning"]?.jsonPrimitive?.contentOrNull
                            if (!reasoning.isNullOrEmpty()) {
                                reasoningBuilder.append(reasoning)
                                callback.onThinkingPartial(reasoning)
                            }
                        }
                    }

                    // Also check for reasoning_content (alternative format)
                    val reasoningContent = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                    if (!reasoningContent.isNullOrEmpty()) {
                        if (!isInReasoningPhase) {
                            isInReasoningPhase = true
                            reasoningStartTime = System.currentTimeMillis()
                            callback.onThinkingStarted()
                        }
                        reasoningBuilder.append(reasoningContent)
                        callback.onThinkingPartial(reasoningContent)
                    }

                    // Extract text content
                    val content = delta["content"]?.jsonPrimitive?.contentOrNull
                    if (!content.isNullOrEmpty()) {
                        // If we were in reasoning phase and now getting content, complete reasoning
                        if (isInReasoningPhase) {
                            completeThinkingPhase(callback, reasoningStartTime, reasoningBuilder)
                            isInReasoningPhase = false
                        }

                        fullResponse.append(content)
                        callback.onPartialResponse(content)
                    }

                    // Check for tool calls
                    val toolCalls = delta["tool_calls"]?.takeIf { it !is JsonNull }?.jsonArray
                    if (toolCalls != null) {
                        // If we were in reasoning phase, complete it
                        if (isInReasoningPhase) {
                            completeThinkingPhase(callback, reasoningStartTime, reasoningBuilder)
                            isInReasoningPhase = false
                        }

                        toolCalls.forEach { toolCallElement ->
                            val toolCallObj = toolCallElement.jsonObject
                            val index = toolCallObj["index"]?.jsonPrimitive?.intOrNull ?: 0

                            // Get or create builder for this index
                            val builder = toolCallsBuilder.getOrPut(index) { ToolCallBuilder() }

                            // Update ID if present
                            toolCallObj["id"]?.jsonPrimitive?.contentOrNull?.let { id ->
                                builder.id = id
                            }

                            // Update function info if present
                            val function = toolCallObj["function"]?.jsonObject
                            if (function != null) {
                                function["name"]?.jsonPrimitive?.contentOrNull?.let { name ->
                                    builder.name = name
                                }
                                function["arguments"]?.jsonPrimitive?.contentOrNull?.let { args ->
                                    builder.argumentsBuilder.append(args)
                                }
                            }
                        }
                    }
                }

                // Check finish_reason
                if (finishReason == "tool_calls") {
                    Log.d(logTag, "Finish reason: tool_calls")
                    // Build the first complete tool call
                    val firstBuilder = toolCallsBuilder[0]
                    if (firstBuilder != null && firstBuilder.isComplete()) {
                        try {
                            val argumentsStr = firstBuilder.argumentsBuilder.toString().ifEmpty { "{}" }
                            val paramsJson = json.parseToJsonElement(argumentsStr).jsonObject

                            detectedToolCall = ToolCall(
                                id = firstBuilder.id!!,
                                toolId = firstBuilder.name!!,
                                parameters = paramsJson,
                                provider = providerName
                            )
                            Log.d(logTag, "Tool call built: ${detectedToolCall?.toolId}")
                        } catch (e: Exception) {
                            Log.e(logTag, "Error parsing tool call arguments: ${e.message}")
                        }
                    }
                } else if (finishReason == "stop" || finishReason == "length") {
                    Log.d(logTag, "Finish reason: $finishReason")
                }
            }

            return StreamAction.Continue
        }

        override fun onStreamEnd() {
            Log.d(logTag, "Stream complete")
            // Complete reasoning if still in progress
            if (isInReasoningPhase) {
                completeThinkingPhase(callback, reasoningStartTime, reasoningBuilder)
                isInReasoningPhase = false
            }
        }

        override fun onParseError(line: String, error: Exception) {
            Log.e(logTag, "Error parsing chunk: ${error.message}")
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
                // Reasoning was enabled but no content received
                thinkingBudget is ThinkingBudgetValue.Effort && thinkingBudget.level.isNotBlank() && thinkingBudget.level != "none" ->
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
                else -> ProviderStreamingResult.Error(getEmptyResponseErrorMessage(enabledTools))
            }
        }
    }

    /**
     * Helper class to accumulate streaming tool call data
     */
    private class ToolCallBuilder {
        var id: String? = null
        var name: String? = null
        val argumentsBuilder = StringBuilder()

        fun isComplete(): Boolean = id != null && name != null
    }

    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback,
        thinkingBudget: ThinkingBudgetValue,
        enabledTools: List<ToolSpecification>
    ): ProviderStreamingResult {
        val parser = DataOnlyStreamParser(
            json = json,
            eventTypeField = null,  // Structure-based parsing (via choices[0].delta)
            doneMarker = "[DONE]",
            skipKeepalives = true,  // For OpenRouter's ": OPENROUTER PROCESSING" comments
            logTag = logTag
        )
        val handler = OpenAICompatibleEventHandler(callback, thinkingBudget, enabledTools)

        val result = parser.parse(reader, handler)

        reader.close()
        connection.disconnect()

        // If parser returned an error (from StreamAction.Error), use that
        if (result is StreamResult.Error) {
            return ProviderStreamingResult.Error(result.message)
        }

        return handler.getResult()
    }

    private fun buildMessages(messages: List<Message>, systemPrompt: String): JsonArray {
        return buildJsonArray {
            // Add system prompt if provided
            if (systemPrompt.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }

            // Add conversation messages
            messages.forEach { message ->
                when (message.role) {
                    "user" -> {
                        val hasImages = message.attachments.any {
                            it.mime_type.startsWith("image/")
                        }

                        if (hasImages) {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", message.text)
                                    })
                                    message.attachments.filter {
                                        it.mime_type.startsWith("image/")
                                    }.forEach { attachment ->
                                        val base64Data = readFileAsBase64(attachment.local_file_path)
                                        if (base64Data != null) {
                                            add(buildJsonObject {
                                                put("type", "image_url")
                                                put("image_url", buildJsonObject {
                                                    put("url", "data:${attachment.mime_type};base64,$base64Data")
                                                })
                                            })
                                        }
                                    }
                                })
                            })
                        } else {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", message.text)
                            })
                        }
                    }
                    "assistant" -> {
                        // Check if next message is tool_call - combine them for OpenAI format
                        val nextIndex = messages.indexOf(message) + 1
                        val nextMessage = messages.getOrNull(nextIndex)

                        if (nextMessage?.role == "tool_call" && nextMessage.toolCall != null) {
                            // Skip - will be handled when we encounter tool_call
                        } else {
                            add(buildJsonObject {
                                put("role", "assistant")
                                put("content", message.text)
                            })
                        }
                    }
                    "tool_call" -> {
                        // Get preceding assistant message (may have text before tool call)
                        val prevIndex = messages.indexOf(message) - 1
                        val prevMessage = messages.getOrNull(prevIndex)

                        add(buildJsonObject {
                            put("role", "assistant")
                            // Include preceding text if any
                            if (prevMessage?.role == "assistant" && prevMessage.text.isNotBlank()) {
                                put("content", prevMessage.text)
                            } else if (message.text.isNotBlank()) {
                                put("content", message.text)
                            } else {
                                put("content", JsonNull)
                            }
                            put("tool_calls", buildJsonArray {
                                add(buildJsonObject {
                                    put("id", message.toolCallId ?: "")
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", message.toolCall?.toolId ?: "")
                                        put("arguments", message.toolCall?.parameters?.toString() ?: "{}")
                                    })
                                })
                            })
                        })
                    }
                    "tool_response" -> {
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", message.toolResponseCallId ?: "")
                            put("content", message.toolResponseOutput ?: "")
                        })
                    }
                }
            }
        }
    }

    private fun buildRequestBody(
        modelName: String,
        messages: JsonArray,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?
    ): JsonObject {
        return buildJsonObject {
            put("model", modelName)
            put("messages", messages)
            put("stream", true)
            put("max_tokens", 8192)

            if (temperature != null) {
                put("temperature", temperature.toDouble())
            }

            // Add reasoning parameter for supported models
            if (thinkingBudget is ThinkingBudgetValue.Effort && thinkingBudget.level.isNotBlank() && thinkingBudget.level != "none") {
                put("reasoning", buildJsonObject {
                    put("effort", thinkingBudget.level)
                })
                Log.d(logTag, "Added reasoning effort: ${thinkingBudget.level}")
            }

            // Add tools if provided
            if (enabledTools.isNotEmpty()) {
                put("tools", buildToolsArray(enabledTools))
                Log.d(logTag, "Added ${enabledTools.size} tools")
            }
        }
    }

    private fun buildToolsArray(enabledTools: List<ToolSpecification>): JsonArray {
        return buildJsonArray {
            enabledTools.forEach { toolSpec ->
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", toolSpec.name)
                        put("description", toolSpec.description)
                        // Use abstract method for parameter formatting
                        put("parameters", formatToolParameters(toolSpec))
                    })
                })
            }
        }
    }
}
