package com.example.ApI.data.network.providers

import android.content.Context
import com.example.ApI.data.model.*
import com.example.ApI.data.network.streaming.EventDataStreamParser
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
 * Anthropic Claude API provider implementation.
 * Handles streaming responses, tool calling, and thinking support.
 */
class AnthropicProvider(context: Context) : BaseProvider(context) {

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
            val conversationMessages = messages.toMutableList()
            var fullResponseText = ""
            val maxToolIterations = MAX_TOOL_DEPTH
            var currentIteration = 0

            while (currentIteration < maxToolIterations) {
                currentIteration++

                val result = makeStreamingRequest(
                    provider,
                    modelName,
                    conversationMessages,
                    systemPrompt,
                    apiKey,
                    enabledTools,
                    webSearchEnabled,
                    thinkingBudget,
                    callback,
                    temperature = temperature
                )

                when (result) {
                    is ProviderStreamingResult.TextComplete -> {
                        fullResponseText = result.fullText
                        callback.onComplete(fullResponseText)
                        return
                    }

                    is ProviderStreamingResult.ToolCallDetected -> {
                        val toolCall = result.toolCall
                        val precedingText = result.precedingText

                        val toolResult = callback.onToolCall(toolCall, precedingText)

                        val toolCallMessage = createToolCallMessage(toolCall, toolResult)
                        val toolResponseMessage = createToolResponseMessage(toolCall, toolResult)

                        callback.onSaveToolMessages(toolCallMessage, toolResponseMessage, precedingText)

                        // Add assistant message WITH THOUGHTS AND SIGNATURE so buildMessages can include thinking block
                        // Anthropic requires thinking block with signature before tool_use when thinking is enabled
                        val assistantMessage = Message(
                            role = "assistant",
                            text = precedingText,
                            attachments = emptyList(),
                            model = modelName,
                            datetime = java.time.Instant.now().toString(),
                            thoughts = result.thoughts,
                            thinkingDurationSeconds = result.thinkingDurationSeconds,
                            thoughtsStatus = result.thoughtsStatus,
                            thoughtsSignature = result.thoughtsSignature
                        )
                        conversationMessages.add(assistantMessage)
                        conversationMessages.add(toolCallMessage)
                        conversationMessages.add(toolResponseMessage)

                        fullResponseText = ""
                    }

                    is ProviderStreamingResult.Error -> {
                        callback.onError(result.error)
                        return
                    }
                }
            }

            callback.onError("Maximum tool calling iterations ($maxToolIterations) reached")
        } catch (e: Exception) {
            callback.onError("Failed to send Anthropic message: ${e.message}")
        }
    }

    private suspend fun makeStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        enabledTools: List<ToolSpecification>,
        webSearchEnabled: Boolean,
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
        callback: StreamingCallback,
        temperature: Float? = null
    ): ProviderStreamingResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(provider.request.base_url)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val anthropicMessages = buildMessages(messages)

            // Calculate max_tokens based on thinking budget
            val maxTokens = when (thinkingBudget) {
                is ThinkingBudgetValue.Tokens -> {
                    if (thinkingBudget.count > 0) {
                        minOf(thinkingBudget.count + 8192, 128000)
                    } else {
                        8192
                    }
                }
                else -> 8192
            }

            val requestBody = buildRequestBody(
                modelName, anthropicMessages, systemPrompt, maxTokens,
                enabledTools, webSearchEnabled, thinkingBudget, temperature
            )

            // DEBUG: Log the request body to verify thinking parameter
            android.util.Log.d("AnthropicProvider", "Request body: $requestBody")
            android.util.Log.d("AnthropicProvider", "ThinkingBudget type: ${thinkingBudget::class.simpleName}, value: $thinkingBudget")

            connection.outputStream.write(requestBody.toString().toByteArray())
            connection.outputStream.flush()

            val responseCode = connection.responseCode
            if (responseCode >= 400) {
                // Read error response from Anthropic
                val errorStream = connection.errorStream
                val errorBody = errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                val errorMessage = try {
                    val errorJson = json.parseToJsonElement(errorBody).jsonObject
                    errorJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: errorBody
                } catch (e: Exception) {
                    errorBody
                }
                callback.onError("Anthropic API error ($responseCode): $errorMessage")
                return@withContext ProviderStreamingResult.Error("Anthropic API error ($responseCode): $errorMessage")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            parseStreamingResponse(reader, connection, callback)
        } catch (e: Exception) {
            callback.onError("Failed to make Anthropic streaming request: ${e.message}")
            ProviderStreamingResult.Error("Failed to make Anthropic streaming request: ${e.message}")
        }
    }

    /**
     * Inner class to handle Anthropic SSE events.
     * Implements StreamEventHandler for use with EventDataStreamParser.
     */
    private inner class AnthropicEventHandler(
        private val callback: StreamingCallback
    ) : StreamEventHandler {

        // Response accumulation
        val fullResponse = StringBuilder()

        // Tool use tracking
        var currentToolUseId: String? = null
        var currentToolName: String? = null
        val currentToolInput = StringBuilder()
        var isAccumulatingToolInput = false
        var detectedToolCall: ToolCall? = null

        // Thinking state tracking
        var isInThinkingPhase = false
        var thinkingStartTime: Long = 0
        val thoughtsBuilder = StringBuilder()
        val signatureBuilder = StringBuilder()
        var currentBlockIndex: Int = -1
        var currentBlockType: String? = null

        // Error tracking
        var errorMessage: String? = null

        override fun onEvent(eventType: String?, data: JsonObject): StreamAction {
            when (eventType) {
                "message_start" -> {
                    // Message started - nothing to do
                }

                "content_block_start" -> {
                    val contentBlock = data["content_block"]?.jsonObject
                    val blockType = contentBlock?.get("type")?.jsonPrimitive?.content
                    currentBlockIndex = data["index"]?.jsonPrimitive?.intOrNull ?: -1
                    currentBlockType = blockType

                    when (blockType) {
                        "thinking" -> {
                            if (!isInThinkingPhase) {
                                isInThinkingPhase = true
                                thinkingStartTime = System.currentTimeMillis()
                                callback.onThinkingStarted()
                            }
                        }
                        "text" -> {
                            if (isInThinkingPhase) {
                                completeThinkingPhase(callback, thinkingStartTime, thoughtsBuilder)
                                isInThinkingPhase = false
                            }
                        }
                        "tool_use" -> {
                            if (isInThinkingPhase) {
                                completeThinkingPhase(callback, thinkingStartTime, thoughtsBuilder)
                                isInThinkingPhase = false
                            }

                            currentToolUseId = contentBlock?.get("id")?.jsonPrimitive?.content
                            currentToolName = contentBlock?.get("name")?.jsonPrimitive?.content
                            currentToolInput.clear()

                            val initialInput = contentBlock?.get("input")?.jsonObject
                            if (initialInput != null) {
                                currentToolInput.append(initialInput.toString())
                            }

                            isAccumulatingToolInput = true
                        }
                    }
                }

                "content_block_delta" -> {
                    val delta = data["delta"]?.jsonObject
                    val deltaType = delta?.get("type")?.jsonPrimitive?.content

                    when (deltaType) {
                        "thinking_delta" -> {
                            val thinkingText = delta?.get("thinking")?.jsonPrimitive?.content ?: ""
                            if (thinkingText.isNotEmpty()) {
                                thoughtsBuilder.append(thinkingText)
                                callback.onThinkingPartial(thinkingText)
                            }
                        }
                        "text_delta" -> {
                            val text = delta?.get("text")?.jsonPrimitive?.content ?: ""
                            fullResponse.append(text)
                            callback.onPartialResponse(text)
                        }
                        "input_json_delta" -> {
                            val partialJson = delta?.get("partial_json")?.jsonPrimitive?.content ?: ""
                            currentToolInput.append(partialJson)
                        }
                        "signature_delta" -> {
                            // Capture signature for follow-up requests (required by Anthropic API)
                            val signature = delta?.get("signature")?.jsonPrimitive?.content ?: ""
                            signatureBuilder.append(signature)
                        }
                    }
                }

                "content_block_stop" -> {
                    if (isAccumulatingToolInput && currentToolUseId != null && currentToolName != null) {
                        try {
                            val inputString = currentToolInput.toString().ifEmpty { "{}" }
                            val toolInputJson = json.parseToJsonElement(inputString).jsonObject

                            detectedToolCall = ToolCall(
                                id = currentToolUseId!!,
                                toolId = currentToolName!!,
                                parameters = toolInputJson,
                                provider = "anthropic"
                            )
                        } catch (e: Exception) {
                            // Error parsing tool input - continue without tool call
                        }

                        isAccumulatingToolInput = false
                    }
                }

                "message_delta" -> {
                    // Message delta received - nothing to do
                }

                "error" -> {
                    val error = data["error"]?.jsonObject
                    errorMessage = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                    callback.onError("Anthropic API error: $errorMessage")
                    return StreamAction.Error(errorMessage!!)
                }
            }

            return StreamAction.Continue
        }

        override fun onStreamEnd() {
            // Stream ended - nothing specific needed here as result is built in getResult()
        }

        override fun onParseError(line: String, error: Exception) {
            // JSON parse error - continue processing (matches previous behavior)
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
            val thoughtsSignature = signatureBuilder.toString().takeIf { it.isNotEmpty() }
            val thinkingDuration = if (thinkingStartTime > 0) {
                (System.currentTimeMillis() - thinkingStartTime) / 1000f
            } else null
            val thoughtsStatus = when {
                thoughtsContent != null -> ThoughtsStatus.PRESENT
                thinkingStartTime > 0 -> ThoughtsStatus.UNAVAILABLE
                else -> ThoughtsStatus.NONE
            }

            return when {
                detectedToolCall != null -> ProviderStreamingResult.ToolCallDetected(
                    toolCall = detectedToolCall!!,
                    precedingText = fullResponse.toString(),
                    thoughts = thoughtsContent,
                    thinkingDurationSeconds = thinkingDuration,
                    thoughtsStatus = thoughtsStatus,
                    thoughtsSignature = thoughtsSignature
                )
                fullResponse.isNotEmpty() -> ProviderStreamingResult.TextComplete(
                    fullText = fullResponse.toString(),
                    thoughts = thoughtsContent,
                    thinkingDurationSeconds = thinkingDuration,
                    thoughtsStatus = thoughtsStatus,
                    thoughtsSignature = thoughtsSignature
                )
                else -> ProviderStreamingResult.Error("Empty response from Anthropic")
            }
        }
    }

    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback
    ): ProviderStreamingResult {
        val parser = EventDataStreamParser(
            json = json,
            stopEvents = setOf("message_stop"),
            logTag = "AnthropicProvider"
        )
        val handler = AnthropicEventHandler(callback)

        val result = parser.parse(reader, handler)

        reader.close()
        connection.disconnect()

        // If parser returned an error (from StreamAction.Error), use that
        if (result is StreamResult.Error) {
            return ProviderStreamingResult.Error(result.message)
        }

        return handler.getResult()
    }

    private suspend fun buildMessages(messages: List<Message>): List<JsonObject> = withContext(Dispatchers.IO) {
        val anthropicMessages = mutableListOf<JsonObject>()
        var i = 0

        while (i < messages.size) {
            val message = messages[i]

            when (message.role) {
                "user" -> {
                    val contentArray = buildJsonArray {
                        if (message.text.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            })
                        }

                        message.attachments.forEach { attachment ->
                            val base64Data = readFileAsBase64(attachment.local_file_path)
                            if (base64Data != null) {
                                when {
                                    attachment.mime_type.startsWith("image/") -> {
                                        add(buildJsonObject {
                                            put("type", "image")
                                            put("source", buildJsonObject {
                                                put("type", "base64")
                                                put("media_type", attachment.mime_type)
                                                put("data", base64Data)
                                            })
                                        })
                                    }
                                    attachment.mime_type == "application/pdf" -> {
                                        add(buildJsonObject {
                                            put("type", "document")
                                            put("source", buildJsonObject {
                                                put("type", "base64")
                                                put("media_type", "application/pdf")
                                                put("data", base64Data)
                                            })
                                        })
                                    }
                                }
                            }
                        }
                    }

                    anthropicMessages.add(buildJsonObject {
                        put("role", "user")
                        put("content", contentArray)
                    })
                    i++
                }
                "assistant" -> {
                    // Check if next message is tool_call - combine them into one API message
                    val nextMessage = messages.getOrNull(i + 1)
                    if (nextMessage?.role == "tool_call") {
                        // Combine thinking (if any) + text + tool_use into one message
                        // Anthropic requires thinking block FIRST when thinking is enabled
                        val contentArray = buildJsonArray {
                            // Add thinking block with signature if present (required by Anthropic when thinking is enabled)
                            if (!message.thoughts.isNullOrBlank()) {
                                add(buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", message.thoughts)
                                    message.thoughtsSignature?.let { put("signature", it) }
                                })
                            }
                            if (message.text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", message.text)
                                })
                            }
                            nextMessage.toolCall?.let { toolCall ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", nextMessage.toolCallId ?: "")
                                    put("name", toolCall.toolId)
                                    put("input", toolCall.parameters)
                                })
                            }
                        }
                        anthropicMessages.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", contentArray)
                        })
                        i += 2  // Skip both messages
                    } else {
                        // Regular assistant message (may have thoughts from extended thinking)
                        val contentArray = buildJsonArray {
                            if (!message.thoughts.isNullOrBlank()) {
                                add(buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", message.thoughts)
                                    message.thoughtsSignature?.let { put("signature", it) }
                                })
                            }
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            })
                        }
                        anthropicMessages.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", contentArray)
                        })
                        i++
                    }
                }
                "tool_call" -> {
                    // Standalone tool_call (old format with text, or new format without preceding assistant)
                    val contentArray = buildJsonArray {
                        // Add thinking block with signature if present (for backward compatibility)
                        if (!message.thoughts.isNullOrBlank()) {
                            add(buildJsonObject {
                                put("type", "thinking")
                                put("thinking", message.thoughts)
                                message.thoughtsSignature?.let { put("signature", it) }
                            })
                        }
                        // Backward compatibility: old format stored preceding text in tool_call.text
                        if (message.text.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            })
                        }

                        message.toolCall?.let { toolCall ->
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", message.toolCallId ?: "")
                                put("name", toolCall.toolId)
                                put("input", toolCall.parameters)
                            })
                        }
                    }

                    anthropicMessages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", contentArray)
                    })
                    i++
                }
                "tool_response" -> {
                    anthropicMessages.add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", message.toolResponseCallId ?: "")
                                put("content", message.toolResponseOutput ?: "")
                            })
                        })
                    })
                    i++
                }
                else -> i++
            }
        }

        anthropicMessages
    }

    private fun buildRequestBody(
        modelName: String,
        anthropicMessages: List<JsonObject>,
        systemPrompt: String,
        maxTokens: Int,
        enabledTools: List<ToolSpecification>,
        webSearchEnabled: Boolean,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?
    ): JsonObject {
        return buildJsonObject {
            put("model", modelName)
            put("max_tokens", maxTokens)
            put("messages", JsonArray(anthropicMessages))

            if (systemPrompt.isNotBlank()) {
                put("system", systemPrompt)
            }

            if (temperature != null) {
                put("temperature", temperature.toDouble())
            }

            put("stream", true)

            // Add thinking parameter
            if (thinkingBudget is ThinkingBudgetValue.Tokens) {
                if (thinkingBudget.count > 0) {
                    put("thinking", buildJsonObject {
                        put("type", "enabled")
                        put("budget_tokens", thinkingBudget.count)
                    })
                } else {
                    put("thinking", buildJsonObject {
                        put("type", "disabled")
                    })
                }
            }

            // Add tools
            if (enabledTools.isNotEmpty() || webSearchEnabled) {
                put("tools", buildJsonArray {
                    if (webSearchEnabled) {
                        add(buildJsonObject {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                        })
                    }
                    if (enabledTools.isNotEmpty()) {
                        convertToolsToAnthropicFormat(enabledTools).forEach { add(it) }
                    }
                })
            }
        }
    }

    private fun convertToolsToAnthropicFormat(tools: List<ToolSpecification>): JsonArray {
        return buildJsonArray {
            tools.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", buildJsonObject {
                        put("type", "object")
                        tool.parameters?.let { params ->
                            put("properties", params)
                        } ?: put("properties", buildJsonObject {})
                        put("required", buildJsonArray {})
                    })
                })
            }
        }
    }

}
