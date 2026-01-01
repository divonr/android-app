package com.example.ApI.data.network.providers

import android.content.Context
import android.util.Log
import com.example.ApI.data.model.*
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
 * LLM Stats (ZeroEval) API provider implementation.
 * Uses OpenAI-compatible format for multi-model access via ZeroEval gateway.
 * Supports:
 * - Streaming responses
 * - Reasoning/thinking with effort levels (xhigh, high, medium, low, minimal)
 * - Tool/function calling with OpenAI-compatible format
 */
class LLMStatsProvider(context: Context) : BaseProvider(context) {

    companion object {
        private const val TAG = "LLMStatsProvider"
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
            callback.onError("Failed to send LLM Stats message: ${e.message}")
        }
    }

    /**
     * Handles tool call execution and chaining for LLM Stats
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
        Log.d(TAG, "Tool call detected - ${initialResponse.toolCall.toolId}")

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
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val llmStatsMessages = buildMessages(messages, systemPrompt)
            val requestBody = buildRequestBody(
                modelName, llmStatsMessages, enabledTools, thinkingBudget, temperature
            )

            Log.d(TAG, "Request body: $requestBody")

            connection.outputStream.write(requestBody.toString().toByteArray())
            connection.outputStream.flush()

            val responseCode = connection.responseCode
            if (responseCode >= 400) {
                val errorReader = BufferedReader(InputStreamReader(connection.errorStream))
                val errorResponse = errorReader.readText()
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
            parseStreamingResponse(reader, connection, callback, thinkingBudget)
        } catch (e: Exception) {
            ProviderStreamingResult.Error("LLM Stats request failed: ${e.message}")
        }
    }

    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback,
        thinkingBudget: ThinkingBudgetValue
    ): ProviderStreamingResult {
        val fullResponse = StringBuilder()
        val reasoningBuilder = StringBuilder()

        // Tool call state
        var detectedToolCall: ToolCall? = null
        val toolCallsBuilder = mutableMapOf<Int, ToolCallBuilder>()

        // Reasoning state
        var isInReasoningPhase = false
        var reasoningStartTime: Long = 0

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            // Skip empty lines and comments
            if (currentLine.isBlank() || currentLine.startsWith(":")) continue

            // Check for [DONE] marker
            if (currentLine == "data: [DONE]") {
                Log.d(TAG, "Stream complete")
                break
            }

            // Parse SSE format: "data: {json}"
            if (currentLine.startsWith("data: ")) {
                val dataContent = currentLine.substring(6).trim()
                if (dataContent.isEmpty() || dataContent == "[DONE]") continue

                try {
                    val chunkJson = json.parseToJsonElement(dataContent).jsonObject

                    // Check for error in chunk
                    val error = chunkJson["error"]?.jsonObject
                    if (error != null) {
                        val errorMessage = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                        reader.close()
                        connection.disconnect()
                        return ProviderStreamingResult.Error(errorMessage)
                    }

                    // Extract from choices[0]
                    val choices = chunkJson["choices"]?.jsonArray
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
                                    Log.d(TAG, "Reasoning phase started")
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
                            Log.d(TAG, "Finish reason: tool_calls")
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
                                        provider = "llmstats"
                                    )
                                    Log.d(TAG, "Tool call built: ${detectedToolCall.toolId}")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing tool call arguments: ${e.message}")
                                }
                            }
                        } else if (finishReason == "stop" || finishReason == "length") {
                            Log.d(TAG, "Finish reason: $finishReason")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing chunk: ${e.message}")
                    continue
                }
            }
        }

        reader.close()
        connection.disconnect()

        // Complete reasoning if still in progress
        if (isInReasoningPhase) {
            completeThinkingPhase(callback, reasoningStartTime, reasoningBuilder)
        }

        // Calculate thoughts data
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
                toolCall = detectedToolCall,
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
            else -> ProviderStreamingResult.Error("Empty response from LLM Stats")
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
        llmStatsMessages: JsonArray,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?
    ): JsonObject {
        return buildJsonObject {
            put("model", modelName)
            put("messages", llmStatsMessages)
            put("stream", true)
            put("max_tokens", 8192)

            if (temperature != null) {
                put("temperature", temperature.toDouble())
            }

            // Add reasoning parameter for supported models
            // LLM Stats uses discrete effort levels: xhigh, high, medium, low, minimal
            if (thinkingBudget is ThinkingBudgetValue.Effort && thinkingBudget.level.isNotBlank() && thinkingBudget.level != "none") {
                put("reasoning", buildJsonObject {
                    put("effort", thinkingBudget.level)
                })
                Log.d(TAG, "Added reasoning effort: ${thinkingBudget.level}")
            }

            // Add tools if provided
            if (enabledTools.isNotEmpty()) {
                put("tools", buildToolsArray(enabledTools))
                Log.d(TAG, "Added ${enabledTools.size} tools")
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
                        if (toolSpec.parameters != null) {
                            put("parameters", toolSpec.parameters)
                        } else {
                            put("parameters", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {})
                            })
                        }
                    })
                })
            }
        }
    }
}
