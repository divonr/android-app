package com.example.ApI.data.network.providers

import android.content.Context
import android.util.Log
import com.example.ApI.data.model.*
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
                webSearchEnabled, enabledTools, callback
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
                    webSearchEnabled, enabledTools, callback
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

            val conversationMessages = buildMessages(messages, systemPrompt)
            val requestBody = buildRequestBody(conversationMessages, enabledTools)

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(json.encodeToString(requestBody))
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode >= 400) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
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

            val conversationMessages = buildMessages(messages, systemPrompt, skipToolMessages = true)
            val requestBody = buildRequestBodyWithToolResults(conversationMessages, enabledTools, toolCall, toolResult)

            Log.d("TOOL_CALL_DEBUG", "Poe: Sending request with tool results: ${json.encodeToString(requestBody)}")

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(json.encodeToString(requestBody))
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode >= 400) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
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

    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback
    ): ProviderStreamingResult {
        val fullResponse = StringBuilder()
        var detectedToolCall: ToolCall? = null
        val toolCallBuffer = mutableMapOf<Int, MutableMap<String, Any>>()
        var line: String?

        Log.d("TOOL_CALL_DEBUG", "Poe: Starting to read stream...")

        while (reader.readLine().also { line = it } != null) {
            val currentLine = line!!

            if (currentLine.startsWith("event:")) {
                val eventType = currentLine.substring(6).trim()

                val dataLine = reader.readLine()
                if (dataLine == null || !dataLine.startsWith("data:")) continue

                val dataContent = dataLine.substring(5).trim()
                if (dataContent.isBlank() || dataContent == "{}") continue

                try {
                    when (eventType) {
                        "text" -> {
                            val eventJson = json.parseToJsonElement(dataContent).jsonObject
                            val text = eventJson["text"]?.jsonPrimitive?.content
                            if (text != null) {
                                fullResponse.append(text)
                                callback.onPartialResponse(text)
                            }
                        }
                        "replace_response" -> {
                            val eventJson = json.parseToJsonElement(dataContent).jsonObject
                            val replacementText = eventJson["text"]?.jsonPrimitive?.content
                            if (replacementText != null && replacementText.isNotBlank()) {
                                fullResponse.clear()
                                fullResponse.append(replacementText)
                                callback.onPartialResponse(replacementText)
                            }
                        }
                        "json" -> {
                            val eventJson = json.parseToJsonElement(dataContent).jsonObject
                            val choices = eventJson["choices"]?.jsonArray

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
                            val eventJson = json.parseToJsonElement(dataContent).jsonObject
                            val errorText = eventJson["text"]?.jsonPrimitive?.content ?: "Unknown error"
                            val errorType = eventJson["error_type"]?.jsonPrimitive?.content
                            reader.close()
                            connection.disconnect()
                            callback.onError("Poe API error ($errorType): $errorText")
                            return ProviderStreamingResult.Error("Poe API error ($errorType): $errorText")
                        }
                        "done" -> {
                            Log.d("TOOL_CALL_DEBUG", "Poe: Stream done")
                            break
                        }
                    }
                } catch (jsonException: Exception) {
                    Log.e("TOOL_CALL_DEBUG", "Poe: Error parsing event: ${jsonException.message}")
                    continue
                }
            }
        }

        reader.close()
        connection.disconnect()

        return when {
            detectedToolCall != null -> ProviderStreamingResult.ToolCallDetected(
                toolCall = detectedToolCall!!,
                precedingText = fullResponse.toString()
            )
            fullResponse.isNotEmpty() -> ProviderStreamingResult.TextComplete(fullResponse.toString())
            else -> ProviderStreamingResult.Error("Empty response from Poe")
        }
    }

    private fun buildMessages(
        messages: List<Message>,
        systemPrompt: String,
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

        messages.forEach { message ->
            when (message.role) {
                "user" -> {
                    val messageObj = buildJsonObject {
                        put("role", "user")
                        put("content", message.text)
                        put("content_type", "text/markdown")

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
