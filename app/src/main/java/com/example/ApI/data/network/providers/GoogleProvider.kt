package com.example.ApI.data.network.providers

import android.content.Context
import android.util.Log
import com.example.ApI.data.model.*
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
 * Google Gemini API provider implementation.
 * Handles streaming responses, tool calling, and thinking support.
 */
class GoogleProvider(context: Context) : BaseProvider(context) {

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
                webSearchEnabled, enabledTools, thinkingBudget, callback, temperature = temperature
            )

            when (streamingResponse) {
                is StreamingResult.TextComplete -> {
                    Log.d("TOOL_CALL_DEBUG", "Google Streaming: Text response complete")
                    callback.onComplete(streamingResponse.fullText)
                }
                is StreamingResult.ToolCallDetected -> {
                    handleToolCall(
                        provider, modelName, messages, systemPrompt, apiKey,
                        webSearchEnabled, enabledTools, thinkingBudget, temperature,
                        streamingResponse, callback
                    )
                }
                is StreamingResult.Error -> {
                    // Already called callback.onError
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send Google streaming message: ${e.message}")
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
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?,
        initialResponse: StreamingResult.ToolCallDetected,
        callback: StreamingCallback
    ) {
        Log.d("TOOL_CALL_DEBUG", "Google Streaming: Tool call detected - ${initialResponse.toolCall.toolId}")

        val toolResult = callback.onToolCall(
            toolCall = initialResponse.toolCall,
            precedingText = initialResponse.precedingText
        )
        Log.d("TOOL_CALL_DEBUG", "Google Streaming: Tool executed with result: $toolResult")

        val toolCallMessage = createToolCallMessage(initialResponse.toolCall, toolResult, "")
        val toolResponseMessage = createToolResponseMessage(initialResponse.toolCall, toolResult)

        callback.onSaveToolMessages(toolCallMessage, toolResponseMessage, initialResponse.precedingText)

        val messagesToAdd = mutableListOf<Message>()
        if (initialResponse.precedingText.isNotBlank()) {
            messagesToAdd.add(createAssistantMessage(initialResponse.precedingText, modelName))
        }
        messagesToAdd.add(toolCallMessage)
        messagesToAdd.add(toolResponseMessage)
        val messagesWithToolResult = messages + messagesToAdd

        var currentMessages = messagesWithToolResult
        var currentResponse: StreamingResult = makeStreamingRequest(
            provider, modelName, currentMessages, systemPrompt, apiKey,
            webSearchEnabled, enabledTools, thinkingBudget, callback,
            maxToolDepth = MAX_TOOL_DEPTH,
            currentDepth = 1,
            temperature = temperature
        )
        var toolDepth = 1

        while (currentResponse is StreamingResult.ToolCallDetected && toolDepth < MAX_TOOL_DEPTH) {
            Log.d("TOOL_CALL_DEBUG", "Google Streaming: Chained tool call #$toolDepth detected - ${currentResponse.toolCall.toolId}")

            val chainedToolResult = callback.onToolCall(
                toolCall = currentResponse.toolCall,
                precedingText = currentResponse.precedingText
            )

            val chainedMessagesToAdd = mutableListOf<Message>()
            if (currentResponse.precedingText.isNotBlank()) {
                chainedMessagesToAdd.add(createAssistantMessage(currentResponse.precedingText, modelName))
            }

            val chainedToolCallMessage = createToolCallMessage(currentResponse.toolCall, chainedToolResult, "")
            chainedMessagesToAdd.add(chainedToolCallMessage)

            val chainedToolResponseMessage = createToolResponseMessage(currentResponse.toolCall, chainedToolResult)
            chainedMessagesToAdd.add(chainedToolResponseMessage)

            callback.onSaveToolMessages(chainedToolCallMessage, chainedToolResponseMessage, currentResponse.precedingText)

            currentMessages = currentMessages + chainedMessagesToAdd
            toolDepth++

            Log.d("TOOL_CALL_DEBUG", "Google Streaming: Sending follow-up request (depth $toolDepth)")
            currentResponse = makeStreamingRequest(
                provider, modelName, currentMessages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, callback,
                maxToolDepth = MAX_TOOL_DEPTH,
                currentDepth = toolDepth,
                temperature = temperature
            )
        }

        when (currentResponse) {
            is StreamingResult.TextComplete -> {
                Log.d("TOOL_CALL_DEBUG", "Google Streaming: Got final text response after $toolDepth tool calls")
                callback.onComplete(currentResponse.fullText)
            }
            is StreamingResult.ToolCallDetected -> {
                Log.d("TOOL_CALL_DEBUG", "Google Streaming: Maximum tool depth ($MAX_TOOL_DEPTH) exceeded")
                callback.onError("Maximum tool call depth ($MAX_TOOL_DEPTH) exceeded")
            }
            is StreamingResult.Error -> {
                callback.onError(currentResponse.error)
            }
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
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
        callback: StreamingCallback,
        maxToolDepth: Int = MAX_TOOL_DEPTH,
        currentDepth: Int = 0,
        temperature: Float? = null
    ): StreamingResult = withContext(Dispatchers.IO) {
        try {
            // Build request URL - replace placeholder and change to streaming endpoint
            val baseUrl = provider.request.base_url.replace("{model_name}", modelName)
                .replace(":generateContent", ":streamGenerateContent")
            val url = URL("$baseUrl?alt=sse&key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val contents = buildContents(messages, systemPrompt)
            val requestBody = buildRequestBody(modelName, contents, webSearchEnabled, enabledTools, thinkingBudget, temperature)
            val requestBodyJson = json.encodeToString(requestBody)

            println("[DEBUG] Google Streaming API Request: $requestBodyJson")

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBodyJson)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode

            if (responseCode >= 400) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                callback.onError("HTTP $responseCode: $errorBody")
                return@withContext StreamingResult.Error("HTTP $responseCode: $errorBody")
            }

            parseStreamingResponse(connection, callback)
        } catch (e: Exception) {
            callback.onError("Failed to make Google streaming request: ${e.message}")
            StreamingResult.Error("Failed to make Google streaming request: ${e.message}")
        }
    }

    private fun parseStreamingResponse(
        connection: HttpURLConnection,
        callback: StreamingCallback
    ): StreamingResult {
        Log.d("TOOL_CALL_DEBUG", "Starting to read Google stream...")
        val reader = BufferedReader(InputStreamReader(connection.inputStream))
        val fullResponse = StringBuilder()
        var detectedToolCall: ToolCall? = null
        var line: String?

        // Thinking state tracking
        var isInThinkingPhase = false
        var thinkingStartTime: Long = 0
        val thoughtsBuilder = StringBuilder()

        while (reader.readLine().also { line = it } != null) {
            val currentLine = line!!

            if (currentLine.startsWith("data:")) {
                val dataContent = currentLine.substring(5).trim()

                if (dataContent.isBlank()) continue

                try {
                    val chunkJson = json.parseToJsonElement(dataContent).jsonObject

                    // Check for error
                    val error = chunkJson["error"]?.jsonObject
                    if (error != null) {
                        val errorMessage = error["message"]?.jsonPrimitive?.content ?: "Unknown Google API streaming error"
                        callback.onError("Google API streaming error: $errorMessage")
                        reader.close()
                        connection.disconnect()
                        return StreamingResult.Error("Google API streaming error: $errorMessage")
                    }

                    val candidates = chunkJson["candidates"]?.jsonArray

                    if (candidates != null && candidates.isNotEmpty()) {
                        val firstCandidate = candidates[0].jsonObject

                        // Check for safety blocks
                        val finishReason = firstCandidate["finishReason"]?.jsonPrimitive?.content
                        if (finishReason != null && finishReason != "STOP") {
                            callback.onError("Google API streaming blocked due to: $finishReason")
                            return StreamingResult.Error("Google API streaming blocked due to: $finishReason")
                        }

                        val content = firstCandidate["content"]?.jsonObject
                        val parts = content?.get("parts")?.jsonArray

                        if (parts != null && parts.isNotEmpty()) {
                            for (partElement in parts) {
                                val part = partElement.jsonObject

                                val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                                val text = part["text"]?.jsonPrimitive?.contentOrNull
                                val functionCall = part["functionCall"]?.jsonObject

                                when {
                                    isThought && text != null -> {
                                        if (!isInThinkingPhase) {
                                            isInThinkingPhase = true
                                            thinkingStartTime = System.currentTimeMillis()
                                            callback.onThinkingStarted()
                                        }
                                        thoughtsBuilder.append(text)
                                        callback.onThinkingPartial(text)
                                    }

                                    functionCall != null -> {
                                        Log.d("TOOL_CALL_DEBUG", "Google Streaming: Found functionCall part: $functionCall")
                                        val name = functionCall["name"]?.jsonPrimitive?.content
                                        val args = functionCall["args"]?.jsonObject
                                        // Capture thoughtSignature - required for Gemini 3+ function calling
                                        val thoughtSignature = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull

                                        if (name != null && args != null) {
                                            detectedToolCall = ToolCall(
                                                id = "google_${System.currentTimeMillis()}",
                                                toolId = name,
                                                parameters = args,
                                                provider = "google",
                                                thoughtSignature = thoughtSignature
                                            )
                                            Log.d("TOOL_CALL_DEBUG", "Google Streaming: Detected tool call in chunk, thoughtSignature=${thoughtSignature != null}")
                                        }
                                    }

                                    text != null -> {
                                        // Transition from thinking to response
                                        if (isInThinkingPhase) {
                                            val duration = (System.currentTimeMillis() - thinkingStartTime) / 1000f
                                            val thoughtsContent = thoughtsBuilder.toString().takeIf { it.isNotEmpty() }
                                            callback.onThinkingComplete(
                                                thoughts = thoughtsContent,
                                                durationSeconds = duration,
                                                status = if (thoughtsContent != null) ThoughtsStatus.PRESENT else ThoughtsStatus.UNAVAILABLE
                                            )
                                            isInThinkingPhase = false
                                        }

                                        Log.d("TOOL_CALL_DEBUG", "Google Streaming: Found text part: '$text'")
                                        fullResponse.append(text)
                                        callback.onPartialResponse(text)
                                    }
                                }
                            }
                        }
                    }
                } catch (jsonException: Exception) {
                    println("[DEBUG] Error parsing Google streaming chunk: ${jsonException.message}")
                    println("[DEBUG] Problematic chunk: $dataContent")
                    continue
                }
            }
        }

        reader.close()
        connection.disconnect()

        return when {
            detectedToolCall != null -> StreamingResult.ToolCallDetected(
                toolCall = detectedToolCall!!,
                precedingText = fullResponse.toString()
            )
            fullResponse.isNotEmpty() -> StreamingResult.TextComplete(
                fullText = fullResponse.toString(),
                thoughts = thoughtsBuilder.toString().takeIf { it.isNotEmpty() },
                thinkingDurationSeconds = if (thoughtsBuilder.isNotEmpty()) {
                    (thinkingStartTime.takeIf { it > 0 }?.let { (System.currentTimeMillis() - it) / 1000f })
                } else null,
                thoughtsStatus = when {
                    thoughtsBuilder.isNotEmpty() -> ThoughtsStatus.PRESENT
                    else -> ThoughtsStatus.NONE
                }
            )
            else -> StreamingResult.Error("Empty response from Google")
        }
    }

    private fun buildContents(messages: List<Message>, systemPrompt: String): List<JsonObject> {
        val contents = mutableListOf<JsonObject>()

        // Add system instruction if provided
        if (systemPrompt.isNotBlank()) {
            contents.add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("text", systemPrompt)
                    })
                })
            })
        }

        messages.forEach { message ->
            when (message.role) {
                "user" -> {
                    val parts = mutableListOf<JsonElement>()

                    if (message.text.isNotBlank()) {
                        parts.add(buildJsonObject {
                            put("text", message.text)
                        })
                    }

                    message.attachments.forEach { attachment ->
                        if (!attachment.file_GOOGLE_uri.isNullOrBlank() && attachment.file_GOOGLE_uri != "{file_URI}") {
                            parts.add(buildJsonObject {
                                put("file_data", buildJsonObject {
                                    put("mime_type", attachment.mime_type)
                                    put("file_uri", attachment.file_GOOGLE_uri)
                                })
                            })
                        }
                    }

                    if (parts.isNotEmpty()) {
                        contents.add(buildJsonObject {
                            put("role", "user")
                            put("parts", JsonArray(parts))
                        })
                    }
                }
                "assistant" -> {
                    if (message.text.isNotBlank()) {
                        contents.add(buildJsonObject {
                            put("role", "model")
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    put("text", message.text)
                                })
                            })
                        })
                    }
                }
                "tool_call" -> {
                    contents.add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("functionCall", buildJsonObject {
                                    put("name", message.toolCall?.toolId ?: "unknown")
                                    put("args", message.toolCall?.parameters ?: buildJsonObject {})
                                })
                                // Include thoughtSignature if available (required for Gemini 3+)
                                message.toolCall?.thoughtSignature?.let { signature ->
                                    put("thoughtSignature", signature)
                                }
                            })
                        })
                    })
                }
                "tool_response" -> {
                    contents.add(buildJsonObject {
                        put("role", "function")
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    put("name", message.toolResponseCallId?.let { findToolNameByCallId(messages, it) } ?: "unknown")
                                    put("response", buildJsonObject {
                                        put("result", message.toolResponseOutput ?: "")
                                    })
                                })
                            })
                        })
                    })
                }
            }
        }

        return contents
    }

    private fun findToolNameByCallId(messages: List<Message>, callId: String): String? {
        return messages.find { it.role == "tool_call" && it.toolCallId == callId }
            ?.toolCall?.toolId
    }

    private fun buildRequestBody(
        modelName: String,
        contents: List<JsonObject>,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?
    ): JsonObject {
        return buildJsonObject {
            // Add tools section
            val toolsArray = buildJsonArray {
                if (webSearchEnabled) {
                    add(buildJsonObject {
                        put("google_search", buildJsonObject {})
                    })
                }

                if (enabledTools.isNotEmpty()) {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            enabledTools.forEach { toolSpec ->
                                add(buildJsonObject {
                                    put("name", toolSpec.name)
                                    put("description", toolSpec.description)
                                    if (toolSpec.parameters != null) {
                                        put("parameters", toolSpec.parameters)
                                    } else {
                                        put("parameters", buildJsonObject {})
                                    }
                                })
                            }
                        })
                    })
                }
            }

            if (toolsArray.isNotEmpty()) {
                put("tools", toolsArray)
            }

            // Add generationConfig with thinkingConfig and temperature
            val modelLower = modelName.lowercase()
            val needsThinkingConfig = !modelLower.contains("flash-lite") && !modelLower.contains("2.0-flash-lite")
            val needsGenerationConfig = needsThinkingConfig || temperature != null

            if (needsGenerationConfig) {
                put("generationConfig", buildJsonObject {
                    if (temperature != null) {
                        put("temperature", temperature.toDouble())
                    }

                    if (needsThinkingConfig) {
                        put("thinkingConfig", buildJsonObject {
                            put("includeThoughts", true)

                            when (thinkingBudget) {
                                is ThinkingBudgetValue.Effort -> {
                                    put("thinkingLevel", thinkingBudget.level.uppercase())
                                }
                                is ThinkingBudgetValue.Tokens -> {
                                    if (thinkingBudget.count > 0) {
                                        put("thinkingBudget", thinkingBudget.count)
                                    }
                                }
                                ThinkingBudgetValue.None -> {
                                    // Use default behavior
                                }
                            }
                        })
                    }
                })
            }

            put("contents", JsonArray(contents))
        }
    }

    private sealed class StreamingResult {
        data class TextComplete(
            val fullText: String,
            val thoughts: String? = null,
            val thinkingDurationSeconds: Float? = null,
            val thoughtsStatus: ThoughtsStatus = ThoughtsStatus.NONE
        ) : StreamingResult()
        data class ToolCallDetected(
            val toolCall: ToolCall,
            val precedingText: String = ""
        ) : StreamingResult()
        data class Error(val error: String) : StreamingResult()
    }
}
