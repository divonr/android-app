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
 * Cohere API provider implementation.
 * Handles streaming responses and tool calling.
 */
class CohereProvider(context: Context) : BaseProvider(context) {

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

                        println("[DEBUG] Cohere executing tool: ${toolCall.toolId}")
                        if (precedingText.isNotBlank()) {
                            println("[DEBUG] Preceding text: $precedingText")
                        }

                        val toolResult = callback.onToolCall(toolCall, precedingText)

                        val toolCallMessage = createToolCallMessage(toolCall, toolResult, precedingText)
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
            callback.onError("Failed to send Cohere message: ${e.message}")
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
        callback: StreamingCallback,
        temperature: Float? = null
    ): StreamingResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(provider.request.base_url)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val cohereMessages = buildMessages(messages, systemPrompt)
            val requestBody = buildRequestBody(modelName, cohereMessages, enabledTools, webSearchEnabled, temperature)

            println("[DEBUG] Cohere request body: $requestBody")

            connection.outputStream.write(requestBody.toString().toByteArray())
            connection.outputStream.flush()

            val responseCode = connection.responseCode
            if (responseCode >= 400) {
                val errorReader = BufferedReader(InputStreamReader(connection.errorStream))
                val errorResponse = errorReader.readText()
                errorReader.close()
                connection.disconnect()
                return@withContext StreamingResult.Error("HTTP $responseCode: $errorResponse")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            parseStreamingResponse(reader, connection, callback)
        } catch (e: Exception) {
            callback.onError("Failed to make Cohere streaming request: ${e.message}")
            StreamingResult.Error("Failed to make streaming request: ${e.message}")
        }
    }

    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback
    ): StreamingResult {
        val fullResponse = StringBuilder()

        var currentToolCallId: String? = null
        var currentToolName: String? = null
        var currentToolArguments = StringBuilder()
        var detectedToolCall: ToolCall? = null
        var toolPlanText = StringBuilder()

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            if (currentLine.isBlank()) continue
            if (currentLine == "data: [DONE]") {
                println("[DEBUG] Cohere stream complete")
                break
            }

            if (currentLine.startsWith("event: ")) {
                val eventType = currentLine.substring(7).trim()

                val dataLine = reader.readLine() ?: continue
                if (!dataLine.startsWith("data: ")) continue

                val dataContent = dataLine.substring(6).trim()
                if (dataContent.isEmpty() || dataContent == "[DONE]") continue

                try {
                    val chunkJson = json.parseToJsonElement(dataContent).jsonObject

                    when (eventType) {
                        "message-start" -> {
                            println("[DEBUG] Cohere message started")
                        }

                        "content-start" -> {
                            println("[DEBUG] Cohere content block started")
                        }

                        "content-delta" -> {
                            try {
                                val delta = chunkJson["delta"]?.jsonObject
                                val message = delta?.get("message")?.jsonObject
                                val contentElement = message?.get("content")

                                val text = when (contentElement) {
                                    is JsonObject -> contentElement["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                    is JsonPrimitive -> contentElement.contentOrNull ?: ""
                                    else -> ""
                                }

                                if (text.isNotEmpty()) {
                                    fullResponse.append(text)
                                    callback.onPartialResponse(text)
                                }
                            } catch (e: Exception) {
                                println("[DEBUG] Error parsing content-delta: ${e.message}")
                            }
                        }

                        "content-end" -> {
                            println("[DEBUG] Cohere content block ended")
                        }

                        "tool-plan-delta" -> {
                            val delta = chunkJson["delta"]?.jsonObject
                            val message = delta?.get("message")?.jsonObject
                            val toolPlan = message?.get("tool_plan")?.jsonPrimitive?.content ?: ""
                            if (toolPlan.isNotEmpty()) {
                                toolPlanText.append(toolPlan)
                            }
                        }

                        "tool-call-start" -> {
                            try {
                                val delta = chunkJson["delta"]?.jsonObject
                                val message = delta?.get("message")?.jsonObject
                                val toolCallsElement = message?.get("tool_calls")

                                val toolCalls = when (toolCallsElement) {
                                    is JsonObject -> toolCallsElement
                                    is JsonArray -> toolCallsElement.firstOrNull() as? JsonObject
                                    else -> null
                                }

                                currentToolCallId = toolCalls?.get("id")?.jsonPrimitive?.contentOrNull
                                val function = toolCalls?.get("function")?.jsonObject
                                currentToolName = function?.get("name")?.jsonPrimitive?.contentOrNull
                                currentToolArguments.clear()

                                val initialArgs = function?.get("arguments")?.jsonPrimitive?.contentOrNull
                                if (!initialArgs.isNullOrEmpty()) {
                                    currentToolArguments.append(initialArgs)
                                }

                                println("[DEBUG] Cohere tool call started: $currentToolName (id: $currentToolCallId)")
                            } catch (e: Exception) {
                                println("[DEBUG] Error parsing tool-call-start: ${e.message}")
                            }
                        }

                        "tool-call-delta" -> {
                            try {
                                val delta = chunkJson["delta"]?.jsonObject
                                val message = delta?.get("message")?.jsonObject
                                val toolCallsElement = message?.get("tool_calls")
                                val toolCalls = when (toolCallsElement) {
                                    is JsonObject -> toolCallsElement
                                    is JsonArray -> toolCallsElement.firstOrNull() as? JsonObject
                                    else -> null
                                }
                                val function = toolCalls?.get("function")?.jsonObject
                                val argsChunk = function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""
                                if (argsChunk.isNotEmpty()) {
                                    currentToolArguments.append(argsChunk)
                                }
                            } catch (e: Exception) {
                                println("[DEBUG] Error parsing tool-call-delta: ${e.message}")
                            }
                        }

                        "tool-call-end" -> {
                            if (currentToolCallId != null && currentToolName != null) {
                                try {
                                    val inputString = currentToolArguments.toString().ifEmpty { "{}" }
                                    val toolInputJson = json.parseToJsonElement(inputString).jsonObject

                                    detectedToolCall = ToolCall(
                                        id = currentToolCallId!!,
                                        toolId = currentToolName!!,
                                        parameters = toolInputJson,
                                        provider = "cohere"
                                    )

                                    println("[DEBUG] Cohere tool call detected: $detectedToolCall")
                                } catch (e: Exception) {
                                    println("[DEBUG] Error parsing Cohere tool input JSON: ${e.message}")
                                }
                            }
                        }

                        "message-end" -> {
                            val delta = chunkJson["delta"]?.jsonObject
                            val finishReason = delta?.get("finish_reason")?.jsonPrimitive?.content
                            println("[DEBUG] Cohere message ended with finish_reason: $finishReason")
                            break
                        }
                    }
                } catch (jsonException: Exception) {
                    println("[DEBUG] Error parsing Cohere SSE chunk: ${jsonException.message}")
                    continue
                }
            }
        }

        reader.close()
        connection.disconnect()

        return when {
            detectedToolCall != null -> StreamingResult.ToolCallResult(
                toolCall = detectedToolCall!!,
                precedingText = fullResponse.toString()
            )
            fullResponse.isNotEmpty() -> StreamingResult.TextResponse(fullResponse.toString())
            else -> StreamingResult.Error("Empty response from Cohere")
        }
    }

    private suspend fun buildMessages(messages: List<Message>, systemPrompt: String): List<JsonObject> = withContext(Dispatchers.IO) {
        val cohereMessages = mutableListOf<JsonObject>()

        if (systemPrompt.isNotBlank()) {
            cohereMessages.add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        messages.forEach { message ->
            when (message.role) {
                "user" -> {
                    if (message.attachments.isEmpty()) {
                        cohereMessages.add(buildJsonObject {
                            put("role", "user")
                            put("content", message.text)
                        })
                    } else {
                        cohereMessages.add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                if (message.text.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", message.text)
                                    })
                                }
                                message.attachments.forEach { attachment ->
                                    if (attachment.mime_type.startsWith("image/")) {
                                        attachment.local_file_path?.let { filePath ->
                                            try {
                                                val file = File(filePath)
                                                if (file.exists()) {
                                                    val bytes = file.readBytes()
                                                    val base64Data = Base64.getEncoder().encodeToString(bytes)
                                                    val dataUrl = "data:${attachment.mime_type};base64,$base64Data"
                                                    add(buildJsonObject {
                                                        put("type", "image_url")
                                                        put("image_url", buildJsonObject {
                                                            put("url", dataUrl)
                                                        })
                                                    })
                                                }
                                            } catch (e: Exception) {
                                                println("[DEBUG] Error encoding image for Cohere: ${e.message}")
                                            }
                                        }
                                    }
                                }
                            })
                        })
                    }
                }
                "assistant" -> {
                    cohereMessages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", message.text)
                    })
                }
                "tool_call" -> {
                    cohereMessages.add(buildJsonObject {
                        put("role", "assistant")
                        if (message.text.isNotBlank()) {
                            put("content", message.text)
                        }
                        put("tool_calls", buildJsonArray {
                            message.toolCall?.let { toolCall ->
                                add(buildJsonObject {
                                    put("id", message.toolCallId ?: "")
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", toolCall.toolId)
                                        put("arguments", toolCall.parameters.toString())
                                    })
                                })
                            }
                        })
                    })
                }
                "tool_response" -> {
                    cohereMessages.add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", message.toolResponseCallId ?: "")
                        put("content", message.toolResponseOutput ?: "")
                    })
                }
            }
        }

        cohereMessages
    }

    private fun buildRequestBody(
        modelName: String,
        cohereMessages: List<JsonObject>,
        enabledTools: List<ToolSpecification>,
        webSearchEnabled: Boolean,
        temperature: Float?
    ): JsonObject {
        return buildJsonObject {
            put("model", modelName)
            put("messages", JsonArray(cohereMessages))
            put("stream", true)

            if (temperature != null) {
                put("temperature", temperature.toDouble())
            }

            if (enabledTools.isNotEmpty()) {
                put("tools", convertToolsToCohereFormat(enabledTools))
            }

            if (webSearchEnabled) {
                put("connectors", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "web-search")
                    })
                })
            }
        }
    }

    private fun convertToolsToCohereFormat(tools: List<ToolSpecification>): JsonArray {
        return buildJsonArray {
            tools.forEach { tool ->
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            tool.parameters?.let { params ->
                                val properties = params["properties"]?.jsonObject
                                if (properties != null) {
                                    put("properties", buildJsonObject {
                                        properties.forEach { (paramName, paramDef) ->
                                            try {
                                                val paramObj = paramDef.jsonObject
                                                val typeValue = paramObj["type"]
                                                val typeString = when {
                                                    typeValue == null -> "string"
                                                    typeValue is JsonPrimitive -> typeValue.content
                                                    typeValue is JsonArray && typeValue.isNotEmpty() -> {
                                                        typeValue.mapNotNull {
                                                            (it as? JsonPrimitive)?.contentOrNull
                                                        }.firstOrNull { it != "null" } ?: "string"
                                                    }
                                                    else -> "string"
                                                }
                                                put(paramName, buildJsonObject {
                                                    put("type", typeString)
                                                    paramObj["description"]?.jsonPrimitive?.contentOrNull?.let {
                                                        put("description", it)
                                                    }
                                                })
                                            } catch (e: Exception) {
                                                println("[DEBUG] Error converting tool parameter $paramName: ${e.message}")
                                            }
                                        }
                                    })
                                } else {
                                    put("properties", buildJsonObject {})
                                }
                                try {
                                    val requiredParams = params["required"]?.jsonArray
                                    if (requiredParams != null && requiredParams.isNotEmpty()) {
                                        put("required", requiredParams)
                                    }
                                } catch (e: Exception) {
                                    // No required params
                                }
                            } ?: run {
                                put("properties", buildJsonObject {})
                            }
                        })
                    })
                })
            }
        }
    }

    private sealed class StreamingResult {
        data class TextResponse(val text: String) : StreamingResult()
        data class ToolCallResult(
            val toolCall: ToolCall,
            val precedingText: String = ""
        ) : StreamingResult()
        data class Error(val message: String) : StreamingResult()
    }
}
