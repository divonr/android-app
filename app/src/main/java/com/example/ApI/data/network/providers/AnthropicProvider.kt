package com.example.ApI.data.network.providers

import android.content.Context
import com.example.ApI.data.model.*
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

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
                    is StreamingResult.TextResponse -> {
                        fullResponseText = result.text
                        callback.onComplete(fullResponseText)
                        return
                    }

                    is StreamingResult.ToolCallResult -> {
                        val toolCall = result.toolCall
                        val precedingText = result.precedingText

                        println("[DEBUG] Executing tool: ${toolCall.toolId}")
                        if (precedingText.isNotBlank()) {
                            println("[DEBUG] Preceding text: $precedingText")
                        }

                        val toolResult = callback.onToolCall(toolCall, precedingText)

                        val toolCallMessage = createToolCallMessageForAnthropic(toolCall, toolResult, precedingText, modelName)
                        val toolResponseMessage = createToolResponseMessage(toolCall, toolResult)

                        callback.onSaveToolMessages(toolCallMessage, toolResponseMessage, precedingText)

                        conversationMessages.add(toolCallMessage)
                        conversationMessages.add(toolResponseMessage)

                        fullResponseText = ""
                    }

                    is StreamingResult.Error -> {
                        callback.onError(result.message)
                        return
                    }
                }
            }

            callback.onError("Maximum tool calling iterations ($maxToolIterations) reached")
        } catch (e: Exception) {
            callback.onError("Failed to send Anthropic message: ${e.message}")
        }
    }

    /**
     * Creates a tool call message specifically for Anthropic format
     * (stores preceding text in message.text for reconstruction)
     */
    private fun createToolCallMessageForAnthropic(
        toolCall: ToolCall,
        toolResult: com.example.ApI.tools.ToolExecutionResult,
        precedingText: String,
        modelName: String
    ): Message {
        val toolDisplayName = com.example.ApI.tools.ToolRegistry.getInstance()
            .getToolDisplayName(toolCall.toolId)

        return Message(
            role = "tool_call",
            text = precedingText, // Store preceding text for reconstruction
            model = modelName,
            datetime = java.time.Instant.now().toString(),
            toolCall = com.example.ApI.tools.ToolCallInfo(
                toolId = toolCall.toolId,
                toolName = toolDisplayName,
                parameters = toolCall.parameters,
                result = toolResult,
                timestamp = java.time.Instant.now().toString()
            ),
            toolCallId = toolCall.id
        )
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
    ): StreamingResult = withContext(Dispatchers.IO) {
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

            connection.outputStream.write(requestBody.toString().toByteArray())
            connection.outputStream.flush()

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            parseStreamingResponse(reader, connection, callback)
        } catch (e: Exception) {
            callback.onError("Failed to make Anthropic streaming request: ${e.message}")
            StreamingResult.Error("Failed to make Anthropic streaming request: ${e.message}")
        }
    }

    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback
    ): StreamingResult {
        val fullResponse = StringBuilder()

        // Track tool use
        var currentToolUseId: String? = null
        var currentToolName: String? = null
        var currentToolInput = StringBuilder()
        var isAccumulatingToolInput = false
        var detectedToolCall: ToolCall? = null

        // Thinking state tracking
        var isInThinkingPhase = false
        var thinkingStartTime: Long = 0
        val thoughtsBuilder = StringBuilder()
        var currentBlockIndex: Int = -1
        var currentBlockType: String? = null

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            if (currentLine.startsWith("event: ")) {
                val eventType = currentLine.substring(7).trim()

                val dataLine = reader.readLine() ?: continue
                if (!dataLine.startsWith("data: ")) continue

                val dataContent = dataLine.substring(6).trim()
                if (dataContent.isEmpty()) continue

                try {
                    val chunkJson = json.parseToJsonElement(dataContent).jsonObject

                    when (eventType) {
                        "message_start" -> {
                            println("[DEBUG] Anthropic message started")
                        }

                        "content_block_start" -> {
                            val contentBlock = chunkJson["content_block"]?.jsonObject
                            val blockType = contentBlock?.get("type")?.jsonPrimitive?.content
                            currentBlockIndex = chunkJson["index"]?.jsonPrimitive?.intOrNull ?: -1
                            currentBlockType = blockType

                            when (blockType) {
                                "thinking" -> {
                                    if (!isInThinkingPhase) {
                                        isInThinkingPhase = true
                                        thinkingStartTime = System.currentTimeMillis()
                                        callback.onThinkingStarted()
                                        println("[DEBUG] Anthropic thinking started")
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
                                    println("[DEBUG] Tool use started: $currentToolName (id: $currentToolUseId)")
                                }
                            }
                        }

                        "content_block_delta" -> {
                            val delta = chunkJson["delta"]?.jsonObject
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
                                    // Ignore signature for display purposes
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

                                    println("[DEBUG] Tool call detected: $detectedToolCall")
                                } catch (e: Exception) {
                                    println("[DEBUG] Error parsing tool input JSON: ${e.message}")
                                }

                                isAccumulatingToolInput = false
                            }
                        }

                        "message_delta" -> {
                            val stopReason = chunkJson["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content
                            println("[DEBUG] Message delta - stop_reason: $stopReason")
                        }

                        "message_stop" -> {
                            println("[DEBUG] Anthropic message stopped")
                            break
                        }

                        "error" -> {
                            val error = chunkJson["error"]?.jsonObject
                            val errorMessage = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                            callback.onError("Anthropic API error: $errorMessage")
                            return StreamingResult.Error(errorMessage)
                        }
                    }
                } catch (jsonException: Exception) {
                    println("[DEBUG] Error parsing Anthropic SSE chunk: ${jsonException.message}")
                    continue
                }
            }
        }

        reader.close()
        connection.disconnect()

        val thoughtsContent = thoughtsBuilder.toString().takeIf { it.isNotEmpty() }
        val thinkingDuration = if (thinkingStartTime > 0) {
            (System.currentTimeMillis() - thinkingStartTime) / 1000f
        } else null
        val thoughtsStatus = when {
            thoughtsContent != null -> ThoughtsStatus.PRESENT
            thinkingStartTime > 0 -> ThoughtsStatus.UNAVAILABLE
            else -> ThoughtsStatus.NONE
        }

        return when {
            detectedToolCall != null -> StreamingResult.ToolCallResult(
                toolCall = detectedToolCall!!,
                precedingText = fullResponse.toString(),
                thoughts = thoughtsContent,
                thinkingDurationSeconds = thinkingDuration,
                thoughtsStatus = thoughtsStatus
            )
            fullResponse.isNotEmpty() -> StreamingResult.TextResponse(
                text = fullResponse.toString(),
                thoughts = thoughtsContent,
                thinkingDurationSeconds = thinkingDuration,
                thoughtsStatus = thoughtsStatus
            )
            else -> StreamingResult.Error("Empty response from Anthropic")
        }
    }

    private fun completeThinkingPhase(
        callback: StreamingCallback,
        thinkingStartTime: Long,
        thoughtsBuilder: StringBuilder
    ) {
        val duration = (System.currentTimeMillis() - thinkingStartTime) / 1000f
        val thoughtsContent = thoughtsBuilder.toString().takeIf { it.isNotEmpty() }
        callback.onThinkingComplete(
            thoughts = thoughtsContent,
            durationSeconds = duration,
            status = if (thoughtsContent != null) ThoughtsStatus.PRESENT else ThoughtsStatus.UNAVAILABLE
        )
        println("[DEBUG] Anthropic thinking completed, duration: ${duration}s")
    }

    private suspend fun buildMessages(messages: List<Message>): List<JsonObject> = withContext(Dispatchers.IO) {
        val anthropicMessages = mutableListOf<JsonObject>()

        messages.forEach { message ->
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
                            attachment.local_file_path?.let { filePath ->
                                try {
                                    val file = File(filePath)
                                    if (file.exists()) {
                                        val bytes = file.readBytes()
                                        val base64Data = Base64.getEncoder().encodeToString(bytes)

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
                                } catch (e: Exception) {
                                    println("[DEBUG] Error encoding file ${attachment.file_name}: ${e.message}")
                                }
                            }
                        }
                    }

                    anthropicMessages.add(buildJsonObject {
                        put("role", "user")
                        put("content", contentArray)
                    })
                }
                "assistant" -> {
                    anthropicMessages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            })
                        })
                    })
                }
                "tool_call" -> {
                    val contentArray = buildJsonArray {
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
                }
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

    private sealed class StreamingResult {
        data class TextResponse(
            val text: String,
            val thoughts: String? = null,
            val thinkingDurationSeconds: Float? = null,
            val thoughtsStatus: ThoughtsStatus = ThoughtsStatus.NONE
        ) : StreamingResult()
        data class ToolCallResult(
            val toolCall: ToolCall,
            val precedingText: String = "",
            val thoughts: String? = null,
            val thinkingDurationSeconds: Float? = null,
            val thoughtsStatus: ThoughtsStatus = ThoughtsStatus.NONE
        ) : StreamingResult()
        data class Error(val message: String) : StreamingResult()
    }
}
