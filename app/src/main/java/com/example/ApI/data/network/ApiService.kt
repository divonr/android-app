package com.example.ApI.data.network

import android.content.Context
import com.example.ApI.data.model.*
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApiService(private val context: Context) {
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun sendMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false,
        enabledTools: List<ToolSpecification> = emptyList(),
        callback: StreamingCallback
    ): Unit = withContext(Dispatchers.IO) {
        
        when (provider.provider) {
            "openai" -> sendOpenAIMessage(provider, modelName, messages, systemPrompt, apiKeys, webSearchEnabled, enabledTools, callback)
            "poe" -> sendPoeMessage(provider, modelName, messages, systemPrompt, apiKeys, webSearchEnabled, enabledTools, callback)
            "google" -> sendGoogleMessage(provider, modelName, messages, systemPrompt, apiKeys, webSearchEnabled, enabledTools, callback)
            "anthropic" -> sendAnthropicMessage(provider, modelName, messages, systemPrompt, apiKeys, webSearchEnabled, enabledTools, callback)
            else -> {
                callback.onError("Unknown provider: ${provider.provider}")
            }
        }
    }

    private suspend fun sendOpenAIMessageNonStreaming(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false,
        enabledTools: List<ToolSpecification> = emptyList(),
        callback: StreamingCallback
    ) {
        val apiKey = apiKeys["openai"] ?: run {
            callback.onError("OpenAI API key is required")
            return
        }
        
        try {
            // Build and send initial request
            val initialResponse = makeOpenAIRequest(
                provider, modelName, messages, systemPrompt, apiKey, 
                webSearchEnabled, enabledTools
            )
            
            when (initialResponse) {
                is OpenAIResponse.TextResponse -> {
                    // Normal text response - we're done
                    callback.onComplete(initialResponse.text)
                }
                is OpenAIResponse.ToolCallResponse -> {
                    // Model wants to call a tool
                    Log.d("TOOL_CALL_DEBUG", "Non-streaming: Tool call detected - ${initialResponse.toolCall.toolId}")
                    
                    // Execute the tool via callback
                    val toolResult = callback.onToolCall(initialResponse.toolCall)
                    Log.d("TOOL_CALL_DEBUG", "Non-streaming: Tool executed with result: $toolResult")

                    // Get canonical tool name from registry
                    val toolDisplayName = com.example.ApI.tools.ToolRegistry.getInstance()
                        .getToolDisplayName(initialResponse.toolCall.toolId)

                    // Create the tool messages
                    val toolCallMessage = Message(
                        role = "tool_call",
                        text = "Tool call: $toolDisplayName",
                        toolCallId = initialResponse.toolCall.id,
                        toolCall = com.example.ApI.tools.ToolCallInfo(
                            toolId = initialResponse.toolCall.toolId,
                            toolName = toolDisplayName,  // Use canonical name from registry
                            parameters = initialResponse.toolCall.parameters,
                            result = toolResult,
                            timestamp = java.time.Instant.now().toString()
                        ),
                        datetime = java.time.Instant.now().toString()
                    )
                    
                    val toolResponseMessage = Message(
                        role = "tool_response",
                        text = when (toolResult) {
                            is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                            is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                        },
                        toolResponseCallId = initialResponse.toolCall.id,
                        toolResponseOutput = when (toolResult) {
                            is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                            is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                        },
                        datetime = java.time.Instant.now().toString()
                    )
                    
                    // Save the tool messages to chat history
                    callback.onSaveToolMessages(toolCallMessage, toolResponseMessage)
                    
                    // Build follow-up request with tool result
                    val messagesWithToolResult = messages + listOf(toolCallMessage, toolResponseMessage)
                    
                    Log.d("TOOL_CALL_DEBUG", "Non-streaming: Sending follow-up request with tool result")
                    
                    // Send follow-up request
                    val followUpResponse = makeOpenAIRequest(
                        provider, modelName, messagesWithToolResult, systemPrompt, apiKey,
                        webSearchEnabled, enabledTools
                    )
                    
                    when (followUpResponse) {
                        is OpenAIResponse.TextResponse -> {
                            Log.d("TOOL_CALL_DEBUG", "Non-streaming: Got final text response")
                            callback.onComplete(followUpResponse.text)
                        }
                        is OpenAIResponse.ToolCallResponse -> {
                            // Model wants to call another tool - for now, just return an error
                            callback.onError("Multiple tool calls not yet supported")
                        }
                        is OpenAIResponse.ErrorResponse -> {
                            callback.onError(followUpResponse.error)
                        }
                    }
                }
                is OpenAIResponse.ErrorResponse -> {
                    callback.onError(initialResponse.error)
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send OpenAI non-streaming message: ${e.message}")
        }
    }
    
    /**
     * Makes a single OpenAI API request and returns the parsed response
     */
    private suspend fun makeOpenAIRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>
    ): OpenAIResponse = withContext(Dispatchers.IO) {
        try {
        // Build request URL
        val url = URL(provider.request.base_url)
        val connection = url.openConnection() as HttpURLConnection
        
        // Set headers
        connection.requestMethod = provider.request.request_type
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        
        // Build conversation history for OpenAI format
            val conversationInput = buildOpenAIInput(messages, systemPrompt)
        
            // Build request body according to providers.json format
        val requestBody = buildJsonObject {
            put("model", modelName)
                put("input", JsonArray(conversationInput))

                // Add tools section
            val toolsArray = buildJsonArray {
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
            if (toolsArray.isNotEmpty()) {
                put("tools", toolsArray)
            }
        }
        
        // Send request
        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(json.encodeToString(requestBody))
        writer.flush()
        writer.close()
        
        // Read response
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
                return@withContext OpenAIResponse.ErrorResponse("HTTP $responseCode: $response")
            }
            
            // Parse response
            parseOpenAIResponse(response)
        } catch (e: Exception) {
            OpenAIResponse.ErrorResponse("Failed to make OpenAI request: ${e.message}")
        }
    }
    
    /**
     * Builds the input array for OpenAI API request
     */
    private fun buildOpenAIInput(messages: List<Message>, systemPrompt: String): List<JsonObject> {
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
                    if (message.attachments.isEmpty()) {
                        conversationInput.add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_text")
                                    put("text", message.text)
                                })
                            })
                        })
                    } else {
                        conversationInput.add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_text")
                                    put("text", message.text)
                                })
                                message.attachments.forEach { attachment ->
                                    add(buildJsonObject {
                                        put("type", if (attachment.mime_type.startsWith("image/")) "input_image" else "input_file")
                                        put("file_id", attachment.file_OPENAI_id ?: "{file_ID}")
                                    })
                                }
                            })
                        })
                    }
                }
                "assistant" -> {
                    conversationInput.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", message.text)
                    })
                }
                "tool_call" -> {
                    // Add function_call entry
                    conversationInput.add(buildJsonObject {
                        put("type", "function_call")
                        put("name", message.toolCall?.toolId ?: "unknown")
                        put("arguments", message.toolCall?.parameters?.toString() ?: "{}")
                        put("call_id", message.toolCallId ?: "")
                    })
                }
                "tool_response" -> {
                    // Add function_call_output entry
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
    
    /**
     * Parses OpenAI API response and returns structured response
     */
    private fun parseOpenAIResponse(response: String): OpenAIResponse {
        try {
            val responseJson = json.parseToJsonElement(response).jsonObject
            val outputArray = responseJson["output"]?.jsonArray ?: return OpenAIResponse.ErrorResponse("No output in response")
            
            var responseText = ""
            var toolCall: com.example.ApI.tools.ToolCall? = null
            
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
                                toolCall = com.example.ApI.tools.ToolCall(
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
                toolCall != null -> OpenAIResponse.ToolCallResponse(toolCall!!)
                responseText.isNotEmpty() -> OpenAIResponse.TextResponse(responseText)
                else -> OpenAIResponse.ErrorResponse("Empty response from OpenAI")
            }
        } catch (e: Exception) {
            return OpenAIResponse.ErrorResponse("Failed to parse response: ${e.message}")
        }
    }
    
    /**
     * Sealed class representing different types of OpenAI responses
     */
    private sealed class OpenAIResponse {
        data class TextResponse(val text: String) : OpenAIResponse()
        data class ToolCallResponse(val toolCall: com.example.ApI.tools.ToolCall) : OpenAIResponse()
        data class ErrorResponse(val error: String) : OpenAIResponse()
    }

    /**
     * Sealed class representing different types of Google responses
     */
    private sealed class GoogleResponse {
        data class TextResponse(val text: String) : GoogleResponse()
        data class ToolCallResponse(val toolCall: com.example.ApI.tools.ToolCall) : GoogleResponse()
        data class ErrorResponse(val error: String) : GoogleResponse()
    }

    /**
     * Builds the contents array for Google API request
     */
    private fun buildGoogleContents(messages: List<Message>, systemPrompt: String): List<JsonObject> {
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

        // Add messages from conversation history
        messages.forEach { message ->
            when (message.role) {
                "user" -> {
                    val parts = mutableListOf<JsonElement>()

                    // Add text if not empty
                    if (message.text.isNotBlank()) {
                        parts.add(buildJsonObject {
                            put("text", message.text)
                        })
                    }

                    // Add file attachments
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

                    // Only add if there are parts
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
                    // Add function call message
                    contents.add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("functionCall", buildJsonObject {
                                    put("name", message.toolCall?.toolId ?: "unknown")
                                    put("args", message.toolCall?.parameters ?: buildJsonObject {})
                                })
                            })
                        })
                    })
                }
                "tool_response" -> {
                    // Add function response message
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

    /**
     * Helper function to find tool name by call ID for Google format
     */
    private fun findToolNameByCallId(messages: List<Message>, callId: String): String? {
        return messages.find { it.role == "tool_call" && it.toolCallId == callId }
            ?.toolCall?.toolId
    }

    /**
     * Parses Google API response and returns structured response
     */
    private fun parseGoogleResponse(response: String): GoogleResponse {
        try {
            val responseJson = json.parseToJsonElement(response).jsonObject
            val candidates = responseJson["candidates"]?.jsonArray

            if (candidates != null && candidates.isNotEmpty()) {
                val firstCandidate = candidates[0].jsonObject
                val content = firstCandidate["content"]?.jsonObject
                val parts = content?.get("parts")?.jsonArray

                if (parts != null && parts.isNotEmpty()) {
                    val firstPart = parts[0].jsonObject

                    // Check for function call
                    val functionCall = firstPart["functionCall"]?.jsonObject
                    if (functionCall != null) {
                        val name = functionCall["name"]?.jsonPrimitive?.content
                        val args = functionCall["args"]?.jsonObject

                        if (name != null && args != null) {
                            val toolCall = com.example.ApI.tools.ToolCall(
                                id = "google_${System.currentTimeMillis()}", // Google doesn't provide call IDs
                                toolId = name,
                                parameters = args,
                                provider = "google"
                            )
                            return GoogleResponse.ToolCallResponse(toolCall)
                        }
                    }

                    // Check for text response
                    val text = firstPart["text"]?.jsonPrimitive?.content
                    if (text != null) {
                        return GoogleResponse.TextResponse(text)
                    }
                }
            }

            return GoogleResponse.ErrorResponse("Empty response from Google")
        } catch (e: Exception) {
            return GoogleResponse.ErrorResponse("Failed to parse response: ${e.message}")
        }
    }

    private suspend fun sendOpenAIMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false,
        enabledTools: List<ToolSpecification> = emptyList(),
        callback: StreamingCallback
    ) {
        val apiKey = apiKeys["openai"] ?: run {
            callback.onError("OpenAI API key is required")
            return
        }
        
        try {
            // Make streaming request and parse response
            val streamingResponse = makeOpenAIStreamingRequest(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, callback
            )
            
            when (streamingResponse) {
                is OpenAIStreamingResult.TextComplete -> {
                    // Normal text response - we're done
                    Log.d("TOOL_CALL_DEBUG", "Streaming: Text response complete")
                    callback.onComplete(streamingResponse.fullText)
                }
                is OpenAIStreamingResult.ToolCallDetected -> {
                    // Model wants to call a tool
                    Log.d("TOOL_CALL_DEBUG", "Streaming: Tool call detected - ${streamingResponse.toolCall.toolId}")

                    // Execute the tool via callback
                    val toolResult = callback.onToolCall(
                        toolCall = streamingResponse.toolCall,
                        precedingText = streamingResponse.precedingText
                    )
                    Log.d("TOOL_CALL_DEBUG", "Streaming: Tool executed with result: $toolResult")

                    // Get canonical tool name from registry
                    val toolDisplayName = com.example.ApI.tools.ToolRegistry.getInstance()
                        .getToolDisplayName(streamingResponse.toolCall.toolId)

                    // Create the tool messages
                    val toolCallMessage = Message(
                        role = "tool_call",
                        text = "Tool call: $toolDisplayName",
                        toolCallId = streamingResponse.toolCall.id,
                        toolCall = com.example.ApI.tools.ToolCallInfo(
                            toolId = streamingResponse.toolCall.toolId,
                            toolName = toolDisplayName,  // Use canonical name from registry
                            parameters = streamingResponse.toolCall.parameters,
                            result = toolResult,
                            timestamp = java.time.Instant.now().toString()
                        ),
                        datetime = java.time.Instant.now().toString()
                    )
                    
                    val toolResponseMessage = Message(
                        role = "tool_response",
                        text = when (toolResult) {
                            is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                            is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                        },
                        toolResponseCallId = streamingResponse.toolCall.id,
                        toolResponseOutput = when (toolResult) {
                            is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                            is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                        },
                        datetime = java.time.Instant.now().toString()
                    )
                    
                    // Save the tool messages to chat history
                    callback.onSaveToolMessages(toolCallMessage, toolResponseMessage)

                    // Build follow-up request with tool result
                    // IMPORTANT: Include preceding text message if it exists!
                    val messagesToAdd = mutableListOf<Message>()
                    if (streamingResponse.precedingText.isNotBlank()) {
                        messagesToAdd.add(Message(
                            role = "assistant",
                            text = streamingResponse.precedingText,
                            attachments = emptyList(),
                            model = modelName,
                            datetime = java.time.Instant.now().toString()
                        ))
                    }
                    messagesToAdd.add(toolCallMessage)
                    messagesToAdd.add(toolResponseMessage)
                    val messagesWithToolResult = messages + messagesToAdd

                    // Handle tool chaining with loop (supports up to 25 sequential tool calls)
                    var currentMessages = messagesWithToolResult
                    var currentResponse: OpenAIStreamingResult = makeOpenAIStreamingRequest(
                        provider, modelName, currentMessages, systemPrompt, apiKey,
                        webSearchEnabled, enabledTools, callback,
                        maxToolDepth = 25,
                        currentDepth = 1
                    )
                    var toolDepth = 1

                    // Loop to handle sequential tool calls
                    while (currentResponse is OpenAIStreamingResult.ToolCallDetected && toolDepth < 25) {
                        Log.d("TOOL_CALL_DEBUG", "Streaming: Chained tool call #$toolDepth detected - ${currentResponse.toolCall.toolId}")

                        // Execute the tool
                        val toolResult = callback.onToolCall(
                            toolCall = currentResponse.toolCall,
                            precedingText = currentResponse.precedingText
                        )

                        // Create messages: assistant text (if exists), tool call, and tool response
                        val messagesToAdd = mutableListOf<Message>()

                        // Add preceding text as assistant message if it exists
                        if (currentResponse.precedingText.isNotBlank()) {
                            messagesToAdd.add(Message(
                                role = "assistant",
                                text = currentResponse.precedingText,
                                attachments = emptyList(),
                                model = modelName,
                                datetime = java.time.Instant.now().toString()
                            ))
                        }

                        // Get canonical tool name from registry
                        val toolDisplayName = com.example.ApI.tools.ToolRegistry.getInstance()
                            .getToolDisplayName(currentResponse.toolCall.toolId)

                        // Create tool call message
                        val toolCallMessage = Message(
                            role = "tool_call",
                            text = "Tool call: $toolDisplayName",
                            toolCallId = currentResponse.toolCall.id,
                            toolCall = com.example.ApI.tools.ToolCallInfo(
                                toolId = currentResponse.toolCall.toolId,
                                toolName = toolDisplayName,  // Use canonical name from registry
                                parameters = currentResponse.toolCall.parameters,
                                result = toolResult,
                                timestamp = java.time.Instant.now().toString()
                            ),
                            datetime = java.time.Instant.now().toString()
                        )
                        messagesToAdd.add(toolCallMessage)

                        // Create tool response message
                        val toolResponseMessage = Message(
                            role = "tool_response",
                            text = when (toolResult) {
                                is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                                is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                            },
                            toolResponseCallId = currentResponse.toolCall.id,
                            toolResponseOutput = when (toolResult) {
                                is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                                is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                            },
                            datetime = java.time.Instant.now().toString()
                        )
                        messagesToAdd.add(toolResponseMessage)

                        // Save tool messages (for UI display)
                        callback.onSaveToolMessages(toolCallMessage, toolResponseMessage)

                        // Add ALL messages (including preceding text) to conversation history
                        currentMessages = currentMessages + messagesToAdd
                        toolDepth++

                        // Send follow-up request
                        Log.d("TOOL_CALL_DEBUG", "Streaming: Sending follow-up request (depth $toolDepth)")
                        currentResponse = makeOpenAIStreamingRequest(
                            provider, modelName, currentMessages, systemPrompt, apiKey,
                            webSearchEnabled, enabledTools, callback,
                            maxToolDepth = 25,
                            currentDepth = toolDepth
                        )
                    }

                    // Handle final response (either text or depth exceeded)
                    when (currentResponse) {
                        is OpenAIStreamingResult.TextComplete -> {
                            Log.d("TOOL_CALL_DEBUG", "Streaming: Got final text response after $toolDepth tool calls")
                            callback.onComplete(currentResponse.fullText)
                        }
                        is OpenAIStreamingResult.ToolCallDetected -> {
                            Log.d("TOOL_CALL_DEBUG", "Streaming: Maximum tool depth (25) exceeded")
                            callback.onError("Maximum tool call depth (25) exceeded")
                        }
                        is OpenAIStreamingResult.Error -> {
                            callback.onError(currentResponse.error)
                        }
                    }
                }
                is OpenAIStreamingResult.Error -> {
                    // Already called callback.onError in makeOpenAIStreamingRequest
                    // No need to call it again
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send OpenAI streaming message: ${e.message}")
        }
    }
    
    /**
     * Makes a streaming OpenAI API request and returns the parsed result
     */
    private suspend fun makeOpenAIStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        callback: StreamingCallback,
        maxToolDepth: Int = 25,
        currentDepth: Int = 0
    ): OpenAIStreamingResult = withContext(Dispatchers.IO) {
        try {
            // Build request URL
            val url = URL(provider.request.base_url)
            val connection = url.openConnection() as HttpURLConnection
            
            // Set headers
            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            // Build conversation input
            val conversationInput = buildOpenAIInput(messages, systemPrompt)
            
            // Build request body with streaming enabled
            val requestBody = buildJsonObject {
                put("model", modelName)
                put("input", JsonArray(conversationInput))
                put("stream", true)
                
                // Add tools section
                val toolsArray = buildJsonArray {
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
                if (toolsArray.isNotEmpty()) {
                    put("tools", toolsArray)
                }
            }
            
            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(json.encodeToString(requestBody))
            writer.flush()
            writer.close()
            
            // Read streaming response
            val responseCode = connection.responseCode

            if (responseCode >= 400) {
                if (responseCode == 400) {
                    // Fallback for 400 Bad Request
                    connection.disconnect()

                    // Run the non-streaming version as a fallback
                    sendOpenAIMessageNonStreaming(provider, modelName, messages, systemPrompt, mapOf("openai" to apiKey), webSearchEnabled, enabledTools, callback)
                    return@withContext OpenAIStreamingResult.Error("Fallback to non-streaming handled")
                } else {
                    val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                    callback.onError("HTTP $responseCode: $errorBody")
                    return@withContext OpenAIStreamingResult.Error("HTTP $responseCode: $errorBody")
                }
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val fullResponse = StringBuilder()
            var detectedToolCall: com.example.ApI.tools.ToolCall? = null
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!
                
                // OpenAI SSE format: "data: {json}" for each chunk
                if (currentLine.startsWith("data:")) {
                    val dataContent = currentLine.substring(5).trim()
                    
                    // Check for end of stream
                    if (dataContent == "[DONE]") {
                        break
                    }
                    
                    // Skip empty data lines
                    if (dataContent.isBlank() || dataContent == "{}") {
                        continue
                    }
                    
                    try {
                        val chunkJson = json.parseToJsonElement(dataContent).jsonObject
                        val eventType = chunkJson["type"]?.jsonPrimitive?.content
                        
                        when (eventType) {
                            "response.output_text.delta" -> {
                                val deltaText = chunkJson["delta"]?.jsonPrimitive?.content
                                if (deltaText != null) {
                                    fullResponse.append(deltaText)
                                    callback.onPartialResponse(deltaText)
                                }
                            }
                            "response.output_item.done" -> {
                                // Check if this is a function call completion
                                val item = chunkJson["item"]?.jsonObject
                                if (item?.get("type")?.jsonPrimitive?.content == "function_call") {
                                    val status = item["status"]?.jsonPrimitive?.content
                                    if (status == "completed") {
                                        val name = item["name"]?.jsonPrimitive?.content
                                        val callId = item["call_id"]?.jsonPrimitive?.content
                                        val arguments = item["arguments"]?.jsonPrimitive?.content
                                        
                                        if (name != null && callId != null && arguments != null) {
                                            val paramsJson = json.parseToJsonElement(arguments).jsonObject
                                            detectedToolCall = com.example.ApI.tools.ToolCall(
                                                id = callId,
                                                toolId = name,
                                                parameters = paramsJson,
                                                provider = "openai"
                                            )
                                            Log.d("TOOL_CALL_DEBUG", "Streaming: Detected tool call in output_item.done")
                                        }
                                    }
                                }
                            }
                            "response.completed" -> {
                                // Response is complete - check for tool call in the full response
                                if (detectedToolCall == null) {
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
                                                    detectedToolCall = com.example.ApI.tools.ToolCall(
                                                        id = callId,
                                                        toolId = name,
                                                        parameters = paramsJson,
                                                        provider = "openai"
                                                    )
                                                    Log.d("TOOL_CALL_DEBUG", "Streaming: Detected tool call in response.completed")
                                                }
                                            }
                                        }
                                    }
                                }
                                break
                            }
                            "response.failed" -> {
                                val error = chunkJson["response"]?.jsonObject?.get("error")?.jsonObject
                                val errorMessage = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                                callback.onError("OpenAI API error: $errorMessage")
                                reader.close()
                                connection.disconnect()
                                return@withContext OpenAIStreamingResult.Error("OpenAI API error: $errorMessage")
                            }
                        }
                    } catch (jsonException: Exception) {
                        Log.e("TOOL_CALL_DEBUG", "Error parsing streaming chunk: ${jsonException.message}")
                        continue
                    }
                }
            }
            
            reader.close()
            connection.disconnect()
            
            // Return result based on what we found
            return@withContext when {
                detectedToolCall != null -> OpenAIStreamingResult.ToolCallDetected(
                    toolCall = detectedToolCall!!,
                    precedingText = fullResponse.toString()
                )
                fullResponse.isNotEmpty() -> OpenAIStreamingResult.TextComplete(fullResponse.toString())
                else -> OpenAIStreamingResult.Error("Empty response from OpenAI")
            }
        } catch (e: Exception) {
            callback.onError("Failed to make OpenAI streaming request: ${e.message}")
            OpenAIStreamingResult.Error("Failed to make OpenAI streaming request: ${e.message}")
        }
    }
    
    /**
     * Sealed class representing different types of OpenAI streaming results
     */
    private sealed class OpenAIStreamingResult {
        data class TextComplete(val fullText: String) : OpenAIStreamingResult()
        data class ToolCallDetected(
            val toolCall: com.example.ApI.tools.ToolCall,
            val precedingText: String = ""
        ) : OpenAIStreamingResult()
        data class Error(val error: String) : OpenAIStreamingResult()
    }

    private suspend fun sendPoeMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false,
        enabledTools: List<ToolSpecification> = emptyList(),
        callback: StreamingCallback
    ) {
        val apiKey = apiKeys["poe"] ?: run {
            callback.onError("Poe API key is required")
            return
        }
        
        try {
            // Make streaming request and parse response
            val streamingResponse = makePoeStreamingRequest(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, callback
            )
            
            when (streamingResponse) {
                is PoeStreamingResult.TextComplete -> {
                    // Normal text response - we're done
                    Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Text response complete")
                    callback.onComplete(streamingResponse.fullText)
                }
                is PoeStreamingResult.ToolCallDetected -> {
                    // Model wants to call a tool
                    Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Tool call detected - ${streamingResponse.toolCall.toolId}")

                    // Execute the tool via callback
                    val toolResult = callback.onToolCall(
                        toolCall = streamingResponse.toolCall,
                        precedingText = streamingResponse.precedingText
                    )
                    Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Tool executed with result: $toolResult")
                    
                    // Get canonical tool name from registry
                    val toolDisplayName = com.example.ApI.tools.ToolRegistry.getInstance()
                        .getToolDisplayName(streamingResponse.toolCall.toolId)

                    // Create the tool messages (but don't add them to conversation history for Poe)
                    val toolCallMessage = Message(
                        role = "tool_call",
                        text = "Tool call: $toolDisplayName",
                        toolCallId = streamingResponse.toolCall.id,
                        toolCall = com.example.ApI.tools.ToolCallInfo(
                            toolId = streamingResponse.toolCall.toolId,
                            toolName = toolDisplayName,  // Use canonical name from registry
                            parameters = streamingResponse.toolCall.parameters,
                            result = toolResult,
                            timestamp = java.time.Instant.now().toString()
                        ),
                        datetime = java.time.Instant.now().toString()
                    )
                    
                    val toolResponseMessage = Message(
                        role = "tool_response",
                        text = when (toolResult) {
                            is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                            is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                        },
                        toolResponseCallId = streamingResponse.toolCall.id,
                        toolResponseOutput = when (toolResult) {
                            is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                            is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                        },
                        datetime = java.time.Instant.now().toString()
                    )
                    
                    // Save the tool messages to chat history (displayed separately from conversation)
                    callback.onSaveToolMessages(toolCallMessage, toolResponseMessage)

                    // For Poe: Send the tool_calls and tool_results in the follow-up request
                    // This is how Poe handles client-side tool execution
                    Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Sending follow-up request with tool_calls and tool_results")

                    // Handle tool chaining with loop (supports up to 25 sequential tool calls)
                    // For Poe: First call uses WithToolResults, subsequent calls use regular request
                    // with tool results incorporated as bot messages
                    var currentMessages = messages  // Start with original messages

                    // Add preceding text if exists (Message 2 - before first tool call)
                    if (streamingResponse.precedingText.isNotBlank()) {
                        currentMessages = currentMessages + Message(
                            role = "assistant",
                            text = streamingResponse.precedingText,
                            attachments = emptyList(),
                            model = modelName,
                            datetime = java.time.Instant.now().toString()
                        )
                    }

                    var currentToolCall = streamingResponse.toolCall
                    var currentToolResult = toolResult
                    var currentResponse: PoeStreamingResult
                    var toolDepth = 1

                    // Loop to handle sequential tool calls
                    while (toolDepth < 25) {
                        Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Tool chain depth = $toolDepth")

                        // FIRST iteration: Use WithToolResults (sends tool_calls and tool_results arrays)
                        // SUBSEQUENT iterations: Use regular request (tool result already in conversation as bot message)
                        currentResponse = if (toolDepth == 1) {
                            makePoeStreamingRequestWithToolResults(
                                provider, modelName, currentMessages, systemPrompt, apiKey,
                                webSearchEnabled, enabledTools,
                                currentToolCall,
                                currentToolResult,
                                callback,
                                maxToolDepth = 25,
                                currentDepth = toolDepth
                            )
                        } else {
                            // For subsequent calls, use regular request
                            // Tool result is already incorporated as bot message in currentMessages
                            makePoeStreamingRequest(
                                provider, modelName, currentMessages, systemPrompt, apiKey,
                                webSearchEnabled, enabledTools, callback,
                                maxToolDepth = 25,
                                currentDepth = toolDepth
                            )
                        }

                        when (currentResponse) {
                            is PoeStreamingResult.TextComplete -> {
                                Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Got final text response")
                                callback.onComplete(currentResponse.fullText)
                                break  // Exit loop - conversation complete
                            }
                            is PoeStreamingResult.ToolCallDetected -> {
                                // Model wants to call another tool
                                Log.d("TOOL_CALL_DEBUG", "Poe Streaming: Chained tool call #$toolDepth detected - ${currentResponse.toolCall.toolId}")

                                // Add preceding text as bot message if exists
                                if (currentResponse.precedingText.isNotBlank()) {
                                    currentMessages = currentMessages + Message(
                                        role = "assistant",
                                        text = currentResponse.precedingText,
                                        attachments = emptyList(),
                                        model = modelName,
                                        datetime = java.time.Instant.now().toString()
                                    )
                                }

                                // Execute the tool
                                val nextToolResult = callback.onToolCall(
                                    toolCall = currentResponse.toolCall,
                                    precedingText = currentResponse.precedingText
                                )

                                // Get canonical tool name from registry
                                val nextToolDisplayName = com.example.ApI.tools.ToolRegistry.getInstance()
                                    .getToolDisplayName(currentResponse.toolCall.toolId)

                                // Create and save the tool messages (for UI display only)
                                val nextToolCallMessage = Message(
                                    role = "tool_call",
                                    text = "Tool call: $nextToolDisplayName",
                                    toolCallId = currentResponse.toolCall.id,
                                    toolCall = com.example.ApI.tools.ToolCallInfo(
                                        toolId = currentResponse.toolCall.toolId,
                                        toolName = nextToolDisplayName,  // Use canonical name from registry
                                        parameters = currentResponse.toolCall.parameters,
                                        result = nextToolResult,
                                        timestamp = java.time.Instant.now().toString()
                                    ),
                                    datetime = java.time.Instant.now().toString()
                                )

                                val nextToolResponseMessage = Message(
                                    role = "tool_response",
                                    text = when (nextToolResult) {
                                        is com.example.ApI.tools.ToolExecutionResult.Success -> nextToolResult.result
                                        is com.example.ApI.tools.ToolExecutionResult.Error -> nextToolResult.error
                                    },
                                    toolResponseCallId = currentResponse.toolCall.id,
                                    toolResponseOutput = when (nextToolResult) {
                                        is com.example.ApI.tools.ToolExecutionResult.Success -> nextToolResult.result
                                        is com.example.ApI.tools.ToolExecutionResult.Error -> nextToolResult.error
                                    },
                                    datetime = java.time.Instant.now().toString()
                                )

                                callback.onSaveToolMessages(nextToolCallMessage, nextToolResponseMessage)

                                // For Poe: Add tool RESULT as a bot message to conversation history
                                // This is how Poe expects tool results - incorporated into conversation, not separate
                                val toolResultText = when (nextToolResult) {
                                    is com.example.ApI.tools.ToolExecutionResult.Success ->
                                        "Tool ${currentResponse.toolCall.toolId} result: ${nextToolResult.result}"
                                    is com.example.ApI.tools.ToolExecutionResult.Error ->
                                        "Tool ${currentResponse.toolCall.toolId} error: ${nextToolResult.error}"
                                }
                                currentMessages = currentMessages + Message(
                                    role = "assistant",
                                    text = toolResultText,
                                    attachments = emptyList(),
                                    model = modelName,
                                    datetime = java.time.Instant.now().toString()
                                )

                                // Prepare for next iteration
                                currentToolCall = currentResponse.toolCall
                                currentToolResult = nextToolResult
                                toolDepth++

                                // Continue loop for next potential tool call
                            }
                            is PoeStreamingResult.Error -> {
                                callback.onError(currentResponse.error)
                                break  // Exit loop on error
                            }
                        }
                    }

                    // If we exited because depth limit reached
                    if (toolDepth >= 25) {
                        callback.onError("Maximum tool call depth (25) exceeded")
                    }
                }
                is PoeStreamingResult.Error -> {
                    // Already called callback.onError in makePoeStreamingRequest
                    // No need to call it again
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send Poe streaming message: ${e.message}")
        }
    }
    
    /**
     * Makes a streaming Poe API request WITH tool results and returns the parsed result
     * This is used for the follow-up request after a tool has been executed
     */
    private suspend fun makePoeStreamingRequestWithToolResults(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        toolCall: com.example.ApI.tools.ToolCall,
        toolResult: com.example.ApI.tools.ToolExecutionResult,
        callback: StreamingCallback,
        maxToolDepth: Int = 25,
        currentDepth: Int = 0
    ): PoeStreamingResult = withContext(Dispatchers.IO) {
        try {
            // Build request URL - replace {model_name} placeholder
            val baseUrl = provider.request.base_url.replace("{model_name}", modelName)
            val url = URL(baseUrl)
            val connection = url.openConnection() as HttpURLConnection
            
            // Set headers
            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            
            // Build conversation history for Poe format (same as before)
            val conversationMessages = mutableListOf<JsonObject>()
            
            // Add system prompt
            if (systemPrompt.isNotBlank()) {
                conversationMessages.add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                    put("content_type", "text/markdown")
                })
            }
            
            // Add messages from conversation history (skip tool_call and tool_response)
            messages.forEach { message ->
                when (message.role) {
                    "user" -> {
                        val messageObj = buildJsonObject {
                            put("role", "user")
                            put("content", message.text)
                            put("content_type", "text/markdown")
                            
                            // Add attachments if any
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
                        // Skip - these are handled separately in tool_calls/tool_results
                    }
                }
            }
            
            // Build request body with tool_calls and tool_results
            val requestBody = buildJsonObject {
                put("version", "1.2")
                put("type", "query")
                put("query", JsonArray(conversationMessages))
                put("user_id", "")
                put("conversation_id", "")
                put("message_id", "")

                // Add tools section
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
                
                // Add tool_calls - the original tool call that was made
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
                
                // Add tool_results - the result from executing the tool
                put("tool_results", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "tool")
                        put("name", toolCall.toolId)
                        put("tool_call_id", toolCall.id)
                        put("content", when (toolResult) {
                            is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                            is com.example.ApI.tools.ToolExecutionResult.Error -> "{\"error\":\"${toolResult.error}\"}"
                        })
                    })
                })
            }
            
            Log.d("TOOL_CALL_DEBUG", "Poe: Sending request with tool results: ${json.encodeToString(requestBody)}")
            
            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(json.encodeToString(requestBody))
            writer.flush()
            writer.close()
            
            // Read response - same as before
            val responseCode = connection.responseCode
            val reader = if (responseCode >= 400) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                callback.onError("HTTP $responseCode: $errorBody")
                return@withContext PoeStreamingResult.Error("HTTP $responseCode: $errorBody")
            } else {
                BufferedReader(InputStreamReader(connection.inputStream))
            }
            
            // Parse server-sent events response and stream chunks
            val fullResponse = StringBuilder()
            var line: String?
            
            Log.d("TOOL_CALL_DEBUG", "Poe: Starting to read follow-up stream...")
            
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!
                
                // Look for event lines in SSE format
                if (currentLine.startsWith("event:")) {
                    val eventType = currentLine.substring(6).trim()
                    
                    // Read the next line which should be the data
                    val dataLine = reader.readLine()
                    if (dataLine == null || !dataLine.startsWith("data:")) {
                        continue
                    }
                    
                    val dataContent = dataLine.substring(5).trim()
                    
                    // Skip empty data lines
                    if (dataContent.isBlank() || dataContent == "{}") {
                        continue
                    }
                    
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
                            "error" -> {
                                val eventJson = json.parseToJsonElement(dataContent).jsonObject
                                val errorText = eventJson["text"]?.jsonPrimitive?.content ?: "Unknown error"
                                val errorType = eventJson["error_type"]?.jsonPrimitive?.content
                                reader.close()
                                connection.disconnect()
                                callback.onError("Poe API error ($errorType): $errorText")
                                return@withContext PoeStreamingResult.Error("Poe API error ($errorType): $errorText")
                            }
                            "done" -> {
                                // End of stream
                                Log.d("TOOL_CALL_DEBUG", "Poe: Follow-up stream done")
                                break
                            }
                        }
                    } catch (jsonException: Exception) {
                        Log.e("TOOL_CALL_DEBUG", "Poe: Error parsing follow-up event: ${jsonException.message}")
                        continue
                    }
                }
            }
            
            reader.close()
            connection.disconnect()
            
            // Return the text response (no more tool calls expected in follow-up)
            return@withContext if (fullResponse.isNotEmpty()) {
                PoeStreamingResult.TextComplete(fullResponse.toString())
            } else {
                PoeStreamingResult.Error("Empty response from Poe follow-up request")
            }
        } catch (e: Exception) {
            callback.onError("Failed to make Poe streaming request with tool results: ${e.message}")
            PoeStreamingResult.Error("Failed to make Poe streaming request with tool results: ${e.message}")
        }
    }
    
    /**
     * Makes a streaming Poe API request and returns the parsed result
     */
    private suspend fun makePoeStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        callback: StreamingCallback,
        maxToolDepth: Int = 25,
        currentDepth: Int = 0
    ): PoeStreamingResult = withContext(Dispatchers.IO) {
        try {
            // Build request URL - replace {model_name} placeholder
            val baseUrl = provider.request.base_url.replace("{model_name}", modelName)
            val url = URL(baseUrl)
            val connection = url.openConnection() as HttpURLConnection
            
            // Set headers
            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            
            // Build conversation history for Poe format
            val conversationMessages = mutableListOf<JsonObject>()
            
            // Add system prompt
            if (systemPrompt.isNotBlank()) {
                conversationMessages.add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                    put("content_type", "text/markdown")
                })
            }
            
            // Add messages from conversation history
            // NOTE: For Poe, we DON'T include tool_call and tool_response messages in the conversation
            messages.forEach { message ->
                when (message.role) {
                    "user" -> {
                        val messageObj = buildJsonObject {
                            put("role", "user")
                            put("content", message.text)
                            put("content_type", "text/markdown")
                            
                            // Add attachments if any
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
                    // Skip tool_call and tool_response - they're not part of Poe's conversation history
                    "tool_call", "tool_response" -> {
                        // Do nothing - Poe handles these outside the conversation flow
                    }
                }
            }
            
            // Build request body according to providers.json format
            val requestBody = buildJsonObject {
                put("version", "1.2")
                put("type", "query")
                put("query", JsonArray(conversationMessages))
                put("user_id", "")
                put("conversation_id", "")
                put("message_id", "")

                // Add tools section for web search and custom tools if enabled
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
                                    }
                                    else {
                                        put("parameters", buildJsonObject {})
                                    }
                                })
                            })
                        }
                    })
                }
            }
            
            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(json.encodeToString(requestBody))
            writer.flush()
            writer.close()
            
            // Read response - Poe uses server-sent events format
            val responseCode = connection.responseCode
            val reader = if (responseCode >= 400) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                callback.onError("HTTP $responseCode: $errorBody")
                return@withContext PoeStreamingResult.Error("HTTP $responseCode: $errorBody")
            } else {
                BufferedReader(InputStreamReader(connection.inputStream))
            }
            
            // Parse server-sent events response and stream chunks
            val fullResponse = StringBuilder()
            var detectedToolCall: com.example.ApI.tools.ToolCall? = null
            val toolCallBuffer = mutableMapOf<Int, MutableMap<String, Any>>() // Buffer to accumulate tool call data
            var line: String?
            
            Log.d("TOOL_CALL_DEBUG", "Poe: Starting to read stream...")
            
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!
                
                // Look for event lines in SSE format
                if (currentLine.startsWith("event:")) {
                    val eventType = currentLine.substring(6).trim()
                    
                    // Read the next line which should be the data
                    val dataLine = reader.readLine()
                    if (dataLine == null || !dataLine.startsWith("data:")) {
                        continue
                    }
                    
                    val dataContent = dataLine.substring(5).trim()
                    
                    // Skip empty data lines
                    if (dataContent.isBlank() || dataContent == "{}") {
                        continue
                    }
                    
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
                                            
                                            // Initialize buffer for this index if needed
                                            if (!toolCallBuffer.containsKey(index)) {
                                                toolCallBuffer[index] = mutableMapOf()
                                            }
                                            
                                            val buffer = toolCallBuffer[index]!!
                                            
                                            // Accumulate tool call data
                                            if (id != null) {
                                                buffer["id"] = id
                                            }
                                            if (type != null) {
                                                buffer["type"] = type
                                            }
                                            if (function != null) {
                                                val name = function["name"]?.jsonPrimitive?.content
                                                val arguments = function["arguments"]?.jsonPrimitive?.content
                                                
                                                if (name != null) {
                                                    buffer["name"] = name
                                                }
                                                if (arguments != null) {
                                                    // Append to existing arguments
                                                    val existingArgs = buffer["arguments"] as? String ?: ""
                                                    buffer["arguments"] = existingArgs + arguments
                                                }
                                            }
                                            
                                            Log.d("TOOL_CALL_DEBUG", "Poe: Accumulated tool call data for index $index: $buffer")
                                        }
                                    }
                                    
                                    // Check finish reason
                                    val finishReason = choice["finish_reason"]?.jsonPrimitive?.content
                                    if (finishReason == "tool_calls") {
                                        Log.d("TOOL_CALL_DEBUG", "Poe: Finish reason is tool_calls")
                                        
                                        // Finalize the tool call from buffer
                                        toolCallBuffer.values.firstOrNull()?.let { buffer ->
                                            val toolId = buffer["id"] as? String
                                            val toolName = buffer["name"] as? String
                                            val toolArguments = buffer["arguments"] as? String
                                            
                                            if (toolId != null && toolName != null && toolArguments != null) {
                                                try {
                                                    val paramsJson = json.parseToJsonElement(toolArguments).jsonObject
                                                    detectedToolCall = com.example.ApI.tools.ToolCall(
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
                                return@withContext PoeStreamingResult.Error("Poe API error ($errorType): $errorText")
                            }
                            "done" -> {
                                // End of stream
                                Log.d("TOOL_CALL_DEBUG", "Poe: Stream done")
                                break
                            }
                        }
                    } catch (jsonException: Exception) {
                        Log.e("TOOL_CALL_DEBUG", "Poe: Error parsing event: ${jsonException.message}")
                        // If we can't parse as JSON, continue reading other lines
                        continue
                    }
                }
            }
            
            reader.close()
            connection.disconnect()
            
            // Return result based on what we found
            return@withContext when {
                detectedToolCall != null -> PoeStreamingResult.ToolCallDetected(
                    toolCall = detectedToolCall!!,
                    precedingText = fullResponse.toString()
                )
                fullResponse.isNotEmpty() -> PoeStreamingResult.TextComplete(fullResponse.toString())
                else -> PoeStreamingResult.Error("Empty response from Poe")
            }
        } catch (e: Exception) {
            callback.onError("Failed to make Poe streaming request: ${e.message}")
            PoeStreamingResult.Error("Failed to make Poe streaming request: ${e.message}")
        }
    }
    
    /**
     * Sealed class representing different types of Poe streaming results
     */
    private sealed class PoeStreamingResult {
        data class TextComplete(val fullText: String) : PoeStreamingResult()
        data class ToolCallDetected(
            val toolCall: com.example.ApI.tools.ToolCall,
            val precedingText: String = ""
        ) : PoeStreamingResult()
        data class Error(val error: String) : PoeStreamingResult()
    }

    private suspend fun sendGoogleMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false,
        enabledTools: List<ToolSpecification> = emptyList(),
        callback: StreamingCallback
    ) {
        val apiKey = apiKeys["google"] ?: run {
            callback.onError("Google API key is required")
            return
        }

        try {
            // Make streaming request and parse response
            val streamingResponse = makeGoogleStreamingRequest(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, callback
            )

            when (streamingResponse) {
                is GoogleStreamingResult.TextComplete -> {
                    // Normal text response - we're done
                    Log.d("TOOL_CALL_DEBUG", "Google Streaming: Text response complete")
                    callback.onComplete(streamingResponse.fullText)
                }
                is GoogleStreamingResult.ToolCallDetected -> {
                    // Model wants to call a tool
                    Log.d("TOOL_CALL_DEBUG", "Google Streaming: Tool call detected - ${streamingResponse.toolCall.toolId}")

                    // Execute the tool via callback
                    val toolResult = callback.onToolCall(
                        toolCall = streamingResponse.toolCall,
                        precedingText = streamingResponse.precedingText
                    )
                    Log.d("TOOL_CALL_DEBUG", "Google Streaming: Tool executed with result: $toolResult")

                    // Get canonical tool name from registry
                    val toolDisplayName = com.example.ApI.tools.ToolRegistry.getInstance()
                        .getToolDisplayName(streamingResponse.toolCall.toolId)

                    // Build tool messages
                    val toolCallMessage = Message(
                        role = "tool_call",
                        text = "Tool call: $toolDisplayName",
                        toolCallId = streamingResponse.toolCall.id,
                        toolCall = com.example.ApI.tools.ToolCallInfo(
                            toolId = streamingResponse.toolCall.toolId,
                            toolName = toolDisplayName,  // Use canonical name from registry
                            parameters = streamingResponse.toolCall.parameters,
                            result = toolResult,
                            timestamp = java.time.Instant.now().toString()
                        ),
                        datetime = java.time.Instant.now().toString()
                    )

                    val toolResponseMessage = Message(
                        role = "tool_response",
                        text = when (toolResult) {
                            is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                            is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                        },
                        toolResponseCallId = streamingResponse.toolCall.id,
                        toolResponseOutput = when (toolResult) {
                            is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                            is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                        },
                        datetime = java.time.Instant.now().toString()
                    )

                    // Save the tool messages to chat history
                    callback.onSaveToolMessages(toolCallMessage, toolResponseMessage)

                    // Build follow-up request with tool result
                    // IMPORTANT: Include preceding text message if it exists!
                    val messagesToAdd = mutableListOf<Message>()
                    if (streamingResponse.precedingText.isNotBlank()) {
                        messagesToAdd.add(Message(
                            role = "assistant",
                            text = streamingResponse.precedingText,
                            attachments = emptyList(),
                            model = modelName,
                            datetime = java.time.Instant.now().toString()
                        ))
                    }
                    messagesToAdd.add(toolCallMessage)
                    messagesToAdd.add(toolResponseMessage)
                    val messagesWithToolResult = messages + messagesToAdd

                    // Handle tool chaining with loop (supports up to 25 sequential tool calls)
                    var currentMessages = messagesWithToolResult
                    var currentResponse: GoogleStreamingResult = makeGoogleStreamingRequest(
                        provider, modelName, currentMessages, systemPrompt, apiKey,
                        webSearchEnabled, enabledTools, callback,
                        maxToolDepth = 25,
                        currentDepth = 1
                    )
                    var toolDepth = 1

                    // Loop to handle sequential tool calls
                    while (currentResponse is GoogleStreamingResult.ToolCallDetected && toolDepth < 25) {
                        Log.d("TOOL_CALL_DEBUG", "Google Streaming: Chained tool call #$toolDepth detected - ${currentResponse.toolCall.toolId}")

                        // Execute the tool
                        val toolResult = callback.onToolCall(
                            toolCall = currentResponse.toolCall,
                            precedingText = currentResponse.precedingText
                        )

                        // Create messages: assistant text (if exists), tool call, and tool response
                        val messagesToAdd = mutableListOf<Message>()

                        // Add preceding text as assistant message if it exists
                        if (currentResponse.precedingText.isNotBlank()) {
                            messagesToAdd.add(Message(
                                role = "assistant",
                                text = currentResponse.precedingText,
                                attachments = emptyList(),
                                model = modelName,
                                datetime = java.time.Instant.now().toString()
                            ))
                        }

                        // Get canonical tool name from registry
                        val toolDisplayName = com.example.ApI.tools.ToolRegistry.getInstance()
                            .getToolDisplayName(currentResponse.toolCall.toolId)

                        // Create tool call message
                        val toolCallMessage = Message(
                            role = "tool_call",
                            text = "Tool call: $toolDisplayName",
                            toolCallId = currentResponse.toolCall.id,
                            toolCall = com.example.ApI.tools.ToolCallInfo(
                                toolId = currentResponse.toolCall.toolId,
                                toolName = toolDisplayName,  // Use canonical name from registry
                                parameters = currentResponse.toolCall.parameters,
                                result = toolResult,
                                timestamp = java.time.Instant.now().toString()
                            ),
                            datetime = java.time.Instant.now().toString()
                        )
                        messagesToAdd.add(toolCallMessage)

                        // Create tool response message
                        val toolResponseMessage = Message(
                            role = "tool_response",
                            text = when (toolResult) {
                                is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                                is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                            },
                            toolResponseCallId = currentResponse.toolCall.id,
                            toolResponseOutput = when (toolResult) {
                                is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                                is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                            },
                            datetime = java.time.Instant.now().toString()
                        )
                        messagesToAdd.add(toolResponseMessage)

                        // Save tool messages (for UI display)
                        callback.onSaveToolMessages(toolCallMessage, toolResponseMessage)

                        // Add ALL messages (including preceding text) to conversation history
                        currentMessages = currentMessages + messagesToAdd
                        toolDepth++

                        // Send follow-up request
                        Log.d("TOOL_CALL_DEBUG", "Google Streaming: Sending follow-up request (depth $toolDepth)")
                        currentResponse = makeGoogleStreamingRequest(
                            provider, modelName, currentMessages, systemPrompt, apiKey,
                            webSearchEnabled, enabledTools, callback,
                            maxToolDepth = 25,
                            currentDepth = toolDepth
                        )
                    }

                    // Handle final response (either text or depth exceeded)
                    when (currentResponse) {
                        is GoogleStreamingResult.TextComplete -> {
                            Log.d("TOOL_CALL_DEBUG", "Google Streaming: Got final text response after $toolDepth tool calls")
                            callback.onComplete(currentResponse.fullText)
                        }
                        is GoogleStreamingResult.ToolCallDetected -> {
                            Log.d("TOOL_CALL_DEBUG", "Google Streaming: Maximum tool depth (25) exceeded")
                            callback.onError("Maximum tool call depth (25) exceeded")
                        }
                        is GoogleStreamingResult.Error -> {
                            callback.onError(currentResponse.error)
                        }
                    }
                }
                is GoogleStreamingResult.Error -> {
                    // Already called callback.onError in makeGoogleStreamingRequest
                    // No need to call it again
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send Google streaming message: ${e.message}")
        }
    }

    /**
     * Makes a streaming Google API request and returns the parsed result
     */
    private suspend fun makeGoogleStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        callback: StreamingCallback,
        maxToolDepth: Int = 25,
        currentDepth: Int = 0
    ): GoogleStreamingResult = withContext(Dispatchers.IO) {
        try {
            // Build request URL - replace {model_name} placeholder and change to streaming endpoint
            val baseUrl = provider.request.base_url.replace("{model_name}", modelName)
                .replace(":generateContent", ":streamGenerateContent")
            val url = URL("$baseUrl?alt=sse&key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection

            // Set headers
            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // Build contents array
            val contents = buildGoogleContents(messages, systemPrompt)

            // Build request body according to providers.json format
            val requestBodyBuilder = buildJsonObject {
                // Add tools section for web search and custom tools if enabled
                val toolsArray = buildJsonArray {
                    // Add web search tool if enabled
                    if (webSearchEnabled) {
                        add(buildJsonObject {
                            put("google_search", buildJsonObject {})
                        })
                    }

                    // Add custom tools from enabledTools list
                    if (enabledTools.isNotEmpty()) {
                        add(buildJsonObject {
                            put("functionDeclarations", buildJsonArray {
                                enabledTools.forEach { toolSpec ->
                                    add(buildJsonObject {
                                        put("name", toolSpec.name)
                                        put("description", toolSpec.description)
                                        if (toolSpec.parameters != null) {
                                            put("parameters", toolSpec.parameters)
                                        }
                                        else {
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

                put("contents", JsonArray(contents))
            }

            val requestBodyJson = json.encodeToString(requestBodyBuilder)

            // Log request for debugging (remove in production)
            println("[DEBUG] Google Streaming API Request: $requestBodyJson")

            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBodyJson)
            writer.flush()
            writer.close()

            // Read streaming response
            val responseCode = connection.responseCode

            if (responseCode >= 400) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                callback.onError("HTTP $responseCode: $errorBody")
                return@withContext GoogleStreamingResult.Error("HTTP $responseCode: $errorBody")
            }

            Log.d("TOOL_CALL_DEBUG", "Starting to read Google stream...")
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val fullResponse = StringBuilder()
            var detectedToolCall: com.example.ApI.tools.ToolCall? = null
            var line: String?

            // Google Gemini streams SSE JSON objects
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!

                if (currentLine.startsWith("data:")) {
                    val dataContent = currentLine.substring(5).trim()

                    // Skip empty lines
                    if (dataContent.isBlank()) {
                        continue
                    }

                    try {
                        val chunkJson = json.parseToJsonElement(dataContent).jsonObject

                        // Check for error in streaming response
                        val error = chunkJson["error"]?.jsonObject
                        if (error != null) {
                            val errorMessage = error["message"]?.jsonPrimitive?.content ?: "Unknown Google API streaming error"
                            callback.onError("Google API streaming error: $errorMessage")
                            reader.close()
                            connection.disconnect()
                            return@withContext GoogleStreamingResult.Error("Google API streaming error: $errorMessage")
                        }

                        val candidates = chunkJson["candidates"]?.jsonArray

                        if (candidates != null && candidates.isNotEmpty()) {
                            val firstCandidate = candidates[0].jsonObject

                            // Check for safety blocks in streaming
                            val finishReason = firstCandidate["finishReason"]?.jsonPrimitive?.content
                            if (finishReason != null && finishReason != "STOP") {
                                callback.onError("Google API streaming blocked due to: $finishReason")
                                return@withContext GoogleStreamingResult.Error("Google API streaming blocked due to: $finishReason")
                            }

                            val content = firstCandidate["content"]?.jsonObject
                            val parts = content?.get("parts")?.jsonArray

                            if (parts != null && parts.isNotEmpty()) {
                                val firstPart = parts[0].jsonObject

                                // Check for function call
                                val functionCall = firstPart["functionCall"]?.jsonObject
                                if (functionCall != null) {
                                    Log.d("TOOL_CALL_DEBUG", "Google Streaming: Found functionCall part: $functionCall")
                                    val name = functionCall["name"]?.jsonPrimitive?.content
                                    val args = functionCall["args"]?.jsonObject

                                    if (name != null && args != null) {
                                        detectedToolCall = com.example.ApI.tools.ToolCall(
                                            id = "google_${System.currentTimeMillis()}", // Google doesn't provide call IDs
                                            toolId = name,
                                            parameters = args,
                                            provider = "google"
                                        )
                                        Log.d("TOOL_CALL_DEBUG", "Google Streaming: Detected tool call in chunk")
                                    }
                                }

                                // Check for text response
                                val chunkText = firstPart["text"]?.jsonPrimitive?.content
                                if (chunkText != null) {
                                    Log.d("TOOL_CALL_DEBUG", "Google Streaming: Found text part: '$chunkText'")
                                    fullResponse.append(chunkText)
                                    callback.onPartialResponse(chunkText)
                                }
                            }
                        }
                    } catch (jsonException: Exception) {
                        // Log parsing errors for debugging
                        println("[DEBUG] Error parsing Google streaming chunk: ${jsonException.message}")
                        println("[DEBUG] Problematic chunk: $dataContent")
                        // Continue reading other chunks on JSON parsing error
                        continue
                    }
                }
            }

            reader.close()
            connection.disconnect()

            // Return result based on what we found
            return@withContext when {
                detectedToolCall != null -> GoogleStreamingResult.ToolCallDetected(
                    toolCall = detectedToolCall!!,
                    precedingText = fullResponse.toString()
                )
                fullResponse.isNotEmpty() -> GoogleStreamingResult.TextComplete(fullResponse.toString())
                else -> GoogleStreamingResult.Error("Empty response from Google")
            }
        } catch (e: Exception) {
            callback.onError("Failed to make Google streaming request: ${e.message}")
            GoogleStreamingResult.Error("Failed to make Google streaming request: ${e.message}")
        }
    }

    /**
     * Send message to Anthropic Claude API with streaming and tool calling support
     */
    private suspend fun sendAnthropicMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false,
        enabledTools: List<ToolSpecification> = emptyList(),
        callback: StreamingCallback
    ) {
        val apiKey = apiKeys["anthropic"] ?: run {
            callback.onError("Anthropic API key is required")
            return
        }

        try {
            val conversationMessages = messages.toMutableList()
            var fullResponseText = ""
            val maxToolIterations = 25
            var currentIteration = 0

            while (currentIteration < maxToolIterations) {
                currentIteration++

                // Make streaming request
                val result = makeAnthropicStreamingRequest(
                    provider,
                    modelName,
                    conversationMessages,
                    systemPrompt,
                    apiKey,
                    enabledTools,
                    webSearchEnabled,
                    callback
                )

                when (result) {
                    is AnthropicStreamingResult.TextResponse -> {
                        // Got text response - we're done
                        fullResponseText = result.text
                        callback.onComplete(fullResponseText)
                        return
                    }

                    is AnthropicStreamingResult.ToolCall -> {
                        // Tool call detected - execute it
                        val toolCall = result.toolCall
                        val precedingText = result.precedingText

                        println("[DEBUG] Executing tool: ${toolCall.toolId}")
                        if (precedingText.isNotBlank()) {
                            println("[DEBUG] Preceding text: $precedingText")
                        }

                        // Execute the tool
                        val toolResult = callback.onToolCall(toolCall, precedingText)

                        // Create assistant message with both text and tool_use (for Anthropic format)
                        // Store preceding text in the message so we can reconstruct it later
                        // Get canonical tool name from registry
                        val toolDisplayName = com.example.ApI.tools.ToolRegistry.getInstance()
                            .getToolDisplayName(toolCall.toolId)

                        val toolCallMessage = Message(
                            role = "tool_call",
                            text = precedingText, // Store the preceding text here
                            model = modelName,
                            datetime = java.time.Instant.now().toString(),
                            toolCall = com.example.ApI.tools.ToolCallInfo(
                                toolId = toolCall.toolId,
                                toolName = toolDisplayName,  // Use canonical name from registry
                                parameters = toolCall.parameters,
                                result = toolResult,
                                timestamp = java.time.Instant.now().toString()
                            ),
                            toolCallId = toolCall.id
                        )

                        // Create tool response message
                        val toolResponseMessage = Message(
                            role = "tool_response",
                            text = when (toolResult) {
                                is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                                is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                            },
                            datetime = java.time.Instant.now().toString(),
                            toolResponseCallId = toolCall.id,
                            toolResponseOutput = when (toolResult) {
                                is com.example.ApI.tools.ToolExecutionResult.Success -> toolResult.result
                                is com.example.ApI.tools.ToolExecutionResult.Error -> toolResult.error
                            }
                        )

                        // Save tool messages to history
                        callback.onSaveToolMessages(toolCallMessage, toolResponseMessage)

                        // Add tool response to conversation
                        conversationMessages.add(toolCallMessage)
                        conversationMessages.add(toolResponseMessage)

                        // Continue loop to get Claude's response to the tool result
                        fullResponseText = ""
                    }

                    is AnthropicStreamingResult.Error -> {
                        // Error occurred
                        callback.onError(result.message)
                        return
                    }
                }
            }

            // Max iterations reached
            callback.onError("Maximum tool calling iterations ($maxToolIterations) reached")
        } catch (e: Exception) {
            callback.onError("Failed to send Anthropic message: ${e.message}")
        }
    }

    /**
     * Make streaming request to Anthropic API and parse SSE events
     */
    private suspend fun makeAnthropicStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        enabledTools: List<ToolSpecification>,
        webSearchEnabled: Boolean,
        callback: StreamingCallback
    ): AnthropicStreamingResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(provider.request.base_url)
            val connection = url.openConnection() as HttpURLConnection

            // Set up request
            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // Build messages array
            val anthropicMessages = buildAnthropicMessages(messages)

            // Build request body
            val requestBody = buildJsonObject {
                put("model", modelName)
                put("max_tokens", 8192)
                put("messages", JsonArray(anthropicMessages))

                // Add system prompt if present
                if (systemPrompt.isNotBlank()) {
                    put("system", systemPrompt)
                }

                put("stream", true)

                // Add tools if any are enabled or web search is enabled
                if (enabledTools.isNotEmpty() || webSearchEnabled) {
                    put("tools", buildJsonArray {
                        // Add web search tool if enabled
                        if (webSearchEnabled) {
                            add(buildJsonObject {
                                put("type", "web_search_20250305")
                                put("name", "web_search")
                                // Optional: Add max_uses to limit searches per request
                                // put("max_uses", 5)
                            })
                        }
                        // Add regular tools
                        if (enabledTools.isNotEmpty()) {
                            convertToolsToAnthropicFormat(enabledTools).forEach { add(it) }
                        }
                    })
                }
            }

            // Send request
            connection.outputStream.write(requestBody.toString().toByteArray())
            connection.outputStream.flush()

            // Read streaming response
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val fullResponse = StringBuilder()

            // Track tool use
            var currentToolUseId: String? = null
            var currentToolName: String? = null
            var currentToolInput = StringBuilder()
            var isAccumulatingToolInput = false
            var detectedToolCall: com.example.ApI.tools.ToolCall? = null

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                // Parse SSE format: "event: type" followed by "data: {json}"
                if (currentLine.startsWith("event: ")) {
                    val eventType = currentLine.substring(7).trim()

                    // Read the next line which should be "data: {json}"
                    val dataLine = reader.readLine() ?: continue
                    if (!dataLine.startsWith("data: ")) continue

                    val dataContent = dataLine.substring(6).trim()
                    if (dataContent.isEmpty()) continue

                    try {
                        val chunkJson = json.parseToJsonElement(dataContent).jsonObject

                        when (eventType) {
                            "message_start" -> {
                                // Message started - metadata received
                                println("[DEBUG] Anthropic message started")
                            }

                            "content_block_start" -> {
                                // New content block starting (text or tool_use)
                                val contentBlock = chunkJson["content_block"]?.jsonObject
                                val blockType = contentBlock?.get("type")?.jsonPrimitive?.content

                                if (blockType == "tool_use") {
                                    // Tool use detected
                                    currentToolUseId = contentBlock?.get("id")?.jsonPrimitive?.content
                                    currentToolName = contentBlock?.get("name")?.jsonPrimitive?.content
                                    currentToolInput.clear()
                                    
                                    // Capture initial input if present (for tools with no/small parameters)
                                    val initialInput = contentBlock?.get("input")?.jsonObject
                                    if (initialInput != null) {
                                        currentToolInput.append(initialInput.toString())
                                    }
                                    
                                    isAccumulatingToolInput = true
                                    println("[DEBUG] Tool use started: $currentToolName (id: $currentToolUseId), initial input: $initialInput")
                                }
                            }

                            "content_block_delta" -> {
                                // Content chunk received
                                val delta = chunkJson["delta"]?.jsonObject
                                val deltaType = delta?.get("type")?.jsonPrimitive?.content

                                when (deltaType) {
                                    "text_delta" -> {
                                        // Text content chunk
                                        val text = delta?.get("text")?.jsonPrimitive?.content ?: ""
                                        fullResponse.append(text)
                                        callback.onPartialResponse(text)
                                    }
                                    "input_json_delta" -> {
                                        // Tool input JSON chunk
                                        val partialJson = delta?.get("partial_json")?.jsonPrimitive?.content ?: ""
                                        currentToolInput.append(partialJson)
                                    }
                                }
                            }

                            "content_block_stop" -> {
                                // Content block finished
                                if (isAccumulatingToolInput && currentToolUseId != null && currentToolName != null) {
                                    // Tool use complete - parse the accumulated JSON
                                    try {
                                        // Handle empty input (tools with no parameters)
                                        val inputString = currentToolInput.toString().ifEmpty { "{}" }
                                        val toolInputJson = json.parseToJsonElement(inputString).jsonObject

                                        detectedToolCall = com.example.ApI.tools.ToolCall(
                                            id = currentToolUseId!!,
                                            toolId = currentToolName!!,
                                            parameters = toolInputJson,
                                            provider = "anthropic"
                                        )

                                        println("[DEBUG] Tool call detected: $detectedToolCall")
                                    } catch (e: Exception) {
                                        println("[DEBUG] Error parsing tool input JSON: ${e.message}")
                                        println("[DEBUG] Attempted to parse: ${currentToolInput}")
                                    }

                                    isAccumulatingToolInput = false
                                }
                            }

                            "message_delta" -> {
                                // Message metadata update (usage, stop_reason)
                                val stopReason = chunkJson["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content
                                println("[DEBUG] Message delta - stop_reason: $stopReason")
                            }

                            "message_stop" -> {
                                // Message complete
                                println("[DEBUG] Anthropic message stopped")
                                break
                            }

                            "error" -> {
                                // Error occurred
                                val error = chunkJson["error"]?.jsonObject
                                val errorMessage = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                                callback.onError("Anthropic API error: $errorMessage")
                                return@withContext AnthropicStreamingResult.Error(errorMessage)
                            }
                        }
                    } catch (jsonException: Exception) {
                        println("[DEBUG] Error parsing Anthropic SSE chunk: ${jsonException.message}")
                        println("[DEBUG] Problematic chunk: $dataContent")
                        continue
                    }
                }
            }

            reader.close()
            connection.disconnect()

            // Return tool call if detected, otherwise return text response
            return@withContext when {
                detectedToolCall != null -> AnthropicStreamingResult.ToolCall(
                    toolCall = detectedToolCall!!,
                    precedingText = fullResponse.toString()
                )
                fullResponse.isNotEmpty() -> AnthropicStreamingResult.TextResponse(fullResponse.toString())
                else -> AnthropicStreamingResult.Error("Empty response from Anthropic")
            }
        } catch (e: Exception) {
            callback.onError("Failed to make Anthropic streaming request: ${e.message}")
            AnthropicStreamingResult.Error("Failed to make Anthropic streaming request: ${e.message}")
        }
    }

    /**
     * Build Anthropic Messages API format from Message objects
     * Handles role mapping and on-demand file encoding
     */
    private suspend fun buildAnthropicMessages(messages: List<Message>): List<JsonObject> = withContext(Dispatchers.IO) {
        val anthropicMessages = mutableListOf<JsonObject>()

        messages.forEach { message ->
            when (message.role) {
                "user" -> {
                    // Build content array with text and attachments
                    val contentArray = buildJsonArray {
                        // Add text content
                        if (message.text.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            })
                        }

                        // Add attachments (encoded as base64)
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
                                            // Add more document types as needed
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
                    // Reconstruct assistant message with both text and tool_use content blocks
                    val contentArray = buildJsonArray {
                        // Add text content if there was preceding text
                        if (message.text.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            })
                        }

                        // Add tool_use content block
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
                    // Tool results are sent as user messages with tool_result content
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

    /**
     * Convert tool specifications to Anthropic's tool format
     */
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
                        // Anthropic doesn't strictly require "required" field
                        put("required", buildJsonArray {})
                    })
                })
            }
        }
    }

    /**
     * Sealed class representing different types of Google streaming results
     */
    private sealed class GoogleStreamingResult {
        data class TextComplete(val fullText: String) : GoogleStreamingResult()
        data class ToolCallDetected(
            val toolCall: com.example.ApI.tools.ToolCall,
            val precedingText: String = ""
        ) : GoogleStreamingResult()
        data class Error(val error: String) : GoogleStreamingResult()
    }

    private sealed class AnthropicStreamingResult {
        data class TextResponse(val text: String) : AnthropicStreamingResult()
        data class ToolCall(
            val toolCall: com.example.ApI.tools.ToolCall,
            val precedingText: String = ""
        ) : AnthropicStreamingResult()
        data class Error(val message: String) : AnthropicStreamingResult()
    }
}


sealed class ApiResponse {
    data class Success(val message: String) : ApiResponse()
    data class Error(val error: String) : ApiResponse()
}

// Debug helper function to log HTTP request details
private fun logHttpRequest(connection: HttpURLConnection, requestBody: String) {
    println("[DEBUG] HTTP ${connection.requestMethod} ${connection.url}")
    println("[DEBUG] Headers: ${connection.requestProperties}")
    println("[DEBUG] Request Body: $requestBody")
}
