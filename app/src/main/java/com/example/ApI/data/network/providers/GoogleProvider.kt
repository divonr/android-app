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
                is ProviderStreamingResult.TextComplete -> {
                    Log.d("TOOL_CALL_DEBUG", "Google Streaming: Text response complete")
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
        initialResponse: ProviderStreamingResult.ToolCallDetected,
        callback: StreamingCallback
    ) {
        Log.d("TOOL_CALL_DEBUG", "Google Streaming: Tool call detected - ${initialResponse.toolCall.toolId}")

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
    ): ProviderStreamingResult = withContext(Dispatchers.IO) {
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
                return@withContext ProviderStreamingResult.Error("HTTP $responseCode: $errorBody")
            }

            parseStreamingResponse(connection, callback)
        } catch (e: Exception) {
            callback.onError("Failed to make Google streaming request: ${e.message}")
            ProviderStreamingResult.Error("Failed to make Google streaming request: ${e.message}")
        }
    }

    private fun parseStreamingResponse(
        connection: HttpURLConnection,
        callback: StreamingCallback
    ): ProviderStreamingResult {
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
                        return ProviderStreamingResult.Error("Google API streaming error: $errorMessage")
                    }

                    val candidates = chunkJson["candidates"]?.jsonArray

                    if (candidates != null && candidates.isNotEmpty()) {
                        val firstCandidate = candidates[0].jsonObject

                        // Check for safety blocks
                        val finishReason = firstCandidate["finishReason"]?.jsonPrimitive?.content
                        if (finishReason != null && finishReason != "STOP") {
                            callback.onError("Google API streaming blocked due to: $finishReason")
                            return ProviderStreamingResult.Error("Google API streaming blocked due to: $finishReason")
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
                                            completeThinkingPhase(callback, thinkingStartTime, thoughtsBuilder)
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

        // Calculate thoughts data (shared by both TextComplete and ToolCallDetected)
        val thoughtsContent = thoughtsBuilder.toString().takeIf { it.isNotEmpty() }
        val thinkingDuration = if (thoughtsBuilder.isNotEmpty()) {
            thinkingStartTime.takeIf { it > 0 }?.let { (System.currentTimeMillis() - it) / 1000f }
        } else null
        val thoughtsStatus = when {
            thoughtsContent != null -> ThoughtsStatus.PRESENT
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
            else -> ProviderStreamingResult.Error("Empty response from Google")
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

        var i = 0
        while (i < messages.size) {
            val message = messages[i]

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
                    i++
                }
                "assistant" -> {
                    // Check if next message is tool_call - combine them into one model turn
                    val nextMessage = messages.getOrNull(i + 1)
                    if (nextMessage?.role == "tool_call") {
                        // Combine assistant text + functionCall into one message
                        contents.add(buildJsonObject {
                            put("role", "model")
                            put("parts", buildJsonArray {
                                if (message.text.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("text", message.text)
                                    })
                                }
                                add(buildJsonObject {
                                    put("functionCall", buildJsonObject {
                                        put("name", nextMessage.toolCall?.toolId ?: "unknown")
                                        put("args", nextMessage.toolCall?.parameters ?: buildJsonObject {})
                                    })
                                    nextMessage.toolCall?.thoughtSignature?.let { signature ->
                                        put("thoughtSignature", signature)
                                    }
                                })
                            })
                        })
                        i += 2  // Skip both messages
                    } else {
                        // Regular assistant message
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
                        i++
                    }
                }
                "tool_call" -> {
                    // Standalone tool_call (old format or no preceding assistant)
                    contents.add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("functionCall", buildJsonObject {
                                    put("name", message.toolCall?.toolId ?: "unknown")
                                    put("args", message.toolCall?.parameters ?: buildJsonObject {})
                                })
                                message.toolCall?.thoughtSignature?.let { signature ->
                                    put("thoughtSignature", signature)
                                }
                            })
                        })
                    })
                    i++
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
                    i++
                }
                else -> i++
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

}
