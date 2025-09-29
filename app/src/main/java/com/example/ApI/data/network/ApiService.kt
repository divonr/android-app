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
                    
                    // Create the tool messages
                    val toolCallMessage = Message(
                        role = "tool_call",
                        text = "Tool call: ${initialResponse.toolCall.toolId}",
                        toolCallId = initialResponse.toolCall.id,
                        toolCall = com.example.ApI.tools.ToolCallInfo(
                            toolId = initialResponse.toolCall.toolId,
                            toolName = initialResponse.toolCall.toolId,
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
                    val toolResult = callback.onToolCall(streamingResponse.toolCall)
                    Log.d("TOOL_CALL_DEBUG", "Streaming: Tool executed with result: $toolResult")
                    
                    // Create the tool messages
                    val toolCallMessage = Message(
                        role = "tool_call",
                        text = "Tool call: ${streamingResponse.toolCall.toolId}",
                        toolCallId = streamingResponse.toolCall.id,
                        toolCall = com.example.ApI.tools.ToolCallInfo(
                            toolId = streamingResponse.toolCall.toolId,
                            toolName = streamingResponse.toolCall.toolId,
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
                    val messagesWithToolResult = messages + listOf(toolCallMessage, toolResponseMessage)
                    
                    Log.d("TOOL_CALL_DEBUG", "Streaming: Sending follow-up request with tool result")
                    
                    // Send follow-up streaming request
                    val followUpResponse = makeOpenAIStreamingRequest(
                        provider, modelName, messagesWithToolResult, systemPrompt, apiKey,
                        webSearchEnabled, enabledTools, callback
                    )
                    
                    when (followUpResponse) {
                        is OpenAIStreamingResult.TextComplete -> {
                            Log.d("TOOL_CALL_DEBUG", "Streaming: Got final text response")
                            callback.onComplete(followUpResponse.fullText)
                        }
                        is OpenAIStreamingResult.ToolCallDetected -> {
                            // Model wants to call another tool - for now, just return an error
                            callback.onError("Multiple tool calls not yet supported")
                        }
                        is OpenAIStreamingResult.Error -> {
                            callback.onError(followUpResponse.error)
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
        callback: StreamingCallback
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
                detectedToolCall != null -> OpenAIStreamingResult.ToolCallDetected(detectedToolCall!!)
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
        data class ToolCallDetected(val toolCall: com.example.ApI.tools.ToolCall) : OpenAIStreamingResult()
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
                BufferedReader(InputStreamReader(connection.errorStream))
                callback.onError("HTTP $responseCode")
                return
            } else {
                BufferedReader(InputStreamReader(connection.inputStream))
            }
            
            // Parse server-sent events response and stream chunks
            val fullResponse = StringBuilder()
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!
                
                // Look for data lines in SSE format
                if (currentLine.startsWith("data:")) {
                    val dataContent = currentLine.substring(5).trim()
                    
                    // Skip empty data lines
                    if (dataContent.isBlank() || dataContent == "{}") {
                        continue
                    }
                    
                    try {
                        val eventJson = json.parseToJsonElement(dataContent).jsonObject
                        
                        // Check for text content (direct text field)
                        val text = eventJson["text"]?.jsonPrimitive?.content
                        if (text != null) {
                            fullResponse.append(text)
                            callback.onPartialResponse(text)
                            continue
                        }
                        
                        // Check for event type
                        val eventType = eventJson["event"]?.jsonPrimitive?.content
                        when (eventType) {
                            "text" -> {
                                val eventData = eventJson["data"]?.jsonObject
                                val eventText = eventData?.get("text")?.jsonPrimitive?.content
                                if (eventText != null) {
                                    fullResponse.append(eventText)
                                    callback.onPartialResponse(eventText)
                                }
                            }
                            "replace_response" -> {
                                val eventData = eventJson["data"]?.jsonObject
                                val replacementText = eventData?.get("text")?.jsonPrimitive?.content
                                if (replacementText != null) {
                                    fullResponse.clear()
                                    fullResponse.append(replacementText)
                                    callback.onPartialResponse(replacementText)
                                }
                            }
                            "json" -> {
                                val eventData = eventJson["data"]?.jsonObject
                                val choices = eventData?.get("choices")?.jsonArray
                                
                                choices?.firstOrNull()?.jsonObject?.let { choice ->
                                    val delta = choice["delta"]?.jsonObject
                                    val toolCalls = delta?.get("tool_calls")?.jsonArray
                                    
                                    toolCalls?.forEach { toolCallElement ->
                                        val toolCallObj = toolCallElement.jsonObject
                                        val index = toolCallObj["index"]?.jsonPrimitive?.int
                                        val function = toolCallObj["function"]?.jsonObject
                                        
                                        if (function != null) {
                                            val arguments = function["arguments"]?.jsonPrimitive?.content
                                            if (arguments != null) {
                                                // Create and execute tool call
                                                val paramsJson = json.parseToJsonElement(arguments).jsonObject
                                                val toolName = function["name"]?.jsonPrimitive?.content ?: "unknown_tool"
                                                val toolCall = com.example.ApI.tools.ToolCall(
                                                    id = index.toString(),
                                                    toolId = toolName,
                                                    parameters = paramsJson,
                                                    provider = "poe"
                                                )
                                                val toolResult = com.example.ApI.tools.ToolRegistry.getInstance().executeTool(toolCall, enabledTools.map { it.name })
                                                callback.onComplete("") // Empty response since we're handling tool call
                                                return
                                            }
                                        }
                                    }
                                    
                                    // Check finish reason
                                    val finishReason = choice["finish_reason"]?.jsonPrimitive?.content
                                    // Note: finish_reason handling removed to avoid inline lambda return issue
                                }
                            }
                            "error" -> {
                                val eventData = eventJson["data"]?.jsonObject
                                val errorText = eventData?.get("text")?.jsonPrimitive?.content ?: "Unknown error"
                                val errorType = eventData?.get("error_type")?.jsonPrimitive?.content
                                reader.close()
                                connection.disconnect()
                                callback.onError("Poe API error ($errorType): $errorText")
                                return
                            }
                            "done" -> {
                                // End of stream
                                break
                            }
                        }
                    } catch (jsonException: Exception) {
                        // If we can't parse as JSON, continue reading other lines
                        continue
                    }
                }
            }
            
            reader.close()
            connection.disconnect()
            
            callback.onComplete(fullResponse.toString())
        } catch (e: Exception) {
            callback.onError("Failed to send Poe streaming message: ${e.message}")
        }
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
            // Build request URL - replace {model_name} placeholder and change to streaming endpoint
            val baseUrl = provider.request.base_url.replace("{model_name}", modelName)
                .replace(":generateContent", ":streamGenerateContent")
            val url = URL("$baseUrl?alt=sse&key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            
            // Set headers
            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            // Build conversation history for Google format
            val conversationContents = mutableListOf<JsonObject>()
            
            // Add messages from conversation history (excluding system message for contents)
            messages.forEach { message ->
                when (message.role) {
                    "user" -> {
                        val parts = mutableListOf<JsonElement>()
                        
                        // Only add text if not empty
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
                            conversationContents.add(buildJsonObject {
                                put("role", "user")
                                put("parts", JsonArray(parts))
                            })
                        }
                    }
                    "assistant" -> {
                        if (message.text.isNotBlank()) {
                            conversationContents.add(buildJsonObject {
                                put("role", "model")
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", message.text)
                                    })
                                })
                            })
                        }
                    }
                }
            }
            
            // Build request body according to providers.json format
            val requestBodyBuilder = buildJsonObject {
                // Add system instruction if provided
                if (systemPrompt.isNotBlank()) {
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("text", systemPrompt)
                            })
                        })
                    })
                }
                
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
                
                put("contents", JsonArray(conversationContents))
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
                return
            }
            
            Log.d("TOOL_CALL_DEBUG", "Starting to read Google stream...")
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val fullResponse = StringBuilder()
            val toolCalls = mutableListOf<com.example.ApI.tools.ToolCall>()
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
                            return
                        }
                        
                        val candidates = chunkJson["candidates"]?.jsonArray
                        
                        if (candidates != null && candidates.isNotEmpty()) {
                            val firstCandidate = candidates[0].jsonObject
                            
                            // Check for safety blocks in streaming
                            val finishReason = firstCandidate["finishReason"]?.jsonPrimitive?.content
                            if (finishReason != null && finishReason != "STOP") {
                                callback.onError("Google API streaming blocked due to: $finishReason")
                                return
                            }
                            
                            val content = firstCandidate["content"]?.jsonObject
                            val parts = content?.get("parts")?.jsonArray
                            
                            if (parts != null && parts.isNotEmpty()) {
                                val firstPart = parts[0].jsonObject
                                
                                // Check for function call
                                val functionCall = firstPart["functionCall"]?.jsonObject
                                if (functionCall != null) {
                                    Log.d("TOOL_CALL_DEBUG", "Found functionCall part: $functionCall")
                                    val name = functionCall["name"]?.jsonPrimitive?.content
                                    val args = functionCall["args"]?.jsonObject?.toString()
                                    
                                    if (name != null && args != null) {
                                        // Create and execute tool call
                                        val paramsJson = if (args != null) json.parseToJsonElement(args).jsonObject else buildJsonObject {}
                                        val toolCall = com.example.ApI.tools.ToolCall(
                                            id = "google_${System.currentTimeMillis()}", // Google doesn't provide call IDs
                                            toolId = name,
                                            parameters = paramsJson,
                                            provider = "google"
                                        )
                                        val toolResult = com.example.ApI.tools.ToolRegistry.getInstance().executeTool(toolCall, enabledTools.map { it.name })
                                        callback.onComplete("") // Empty response since we're handling tool call
                                        return
                                    }
                                }
                                
                                // Check for text response
                                val chunkText = firstPart["text"]?.jsonPrimitive?.content
                                if (chunkText != null) {
                                    Log.d("TOOL_CALL_DEBUG", "Found text part: '$chunkText'")
                                    fullResponse.append(chunkText)
                                    callback.onPartialResponse(chunkText)
                                }
                            }
                        }
                    } catch (jsonException: Exception) {
                        // Log parsing errors for debugging
                        println("[DEBUG] Error parsing streaming chunk: ${jsonException.message}")
                        println("[DEBUG] Problematic chunk: $dataContent")
                        // Continue reading other chunks on JSON parsing error
                        continue
                    }
                }
            }
            
            reader.close()
            connection.disconnect()
            
            // Check if we got any response
            if (fullResponse.isEmpty()) {
                callback.onError("Google API streaming returned empty response")
            } else {
                callback.onComplete(fullResponse.toString())
            }
        } catch (e: Exception) {
            callback.onError("Failed to send Google streaming message: ${e.message}")
        }
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
