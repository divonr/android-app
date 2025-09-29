package com.example.ApI.data.network

import android.content.Context
import android.app.AlertDialog
import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.example.ApI.data.model.*
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolRegistry
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

private data class OpenAIToolCallPayload(
    val callId: String,
    val functionName: String,
    val rawArguments: String
)

private data class OpenAIToolCallBuffer(
    val functionName: String,
    val argumentsBuilder: StringBuilder = StringBuilder()
)

class ApiService(private val context: Context) {
    
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun notifyToolCallCollected(provider: String, callId: String, functionName: String, rawArguments: String) {
        val preview = if (rawArguments.length > 200) rawArguments.substring(0, 200) + "..." else rawArguments
        val dialogMessage = buildString {
            appendLine("Provider: $provider")
            appendLine("Tool: $functionName")
            appendLine("Call ID: $callId")
            appendLine()
            append(preview)
        }
        mainHandler.post {
            val activity = context as? Activity ?: return@post
            AlertDialog.Builder(activity)
                .setTitle("Tool Call Ready")
                .setMessage(dialogMessage)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

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
        enabledTools: List<ToolSpecification> = emptyList()
    ): ApiResponse {
        val apiKey = apiKeys["openai"] ?: throw IllegalArgumentException("OpenAI API key is required")
        
        // Build request URL
        val url = URL(provider.request.base_url)
        val connection = url.openConnection() as HttpURLConnection
        
        // Set headers
        connection.requestMethod = provider.request.request_type
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        
        // Build conversation history for OpenAI format
        val conversationMessages = mutableListOf<JsonObject>()
        
        // Add system prompt
        if (systemPrompt.isNotBlank()) {
            conversationMessages.add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
        
        // Add messages from conversation history
        messages.forEach { message ->
            when (message.role) {
                "user" -> {
                    if (message.attachments.isEmpty()) {
                        // Text-only message
                        conversationMessages.add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_text")
                                    put("text", message.text)
                                })
                            })
                        })
                    } else {
                        // Message with attachments
                        conversationMessages.add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_text")
                                    put("text", message.text)
                                })
                                // Add file attachments
                                message.attachments.forEach { attachment ->
                                    // This assumes files are already uploaded and we have file_id
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
                    conversationMessages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", message.text)
                    })
                }
            }
        }
        
            // Build request body according to providers.json format
        val requestBody = buildJsonObject {
            put("model", modelName)

            // Build input array with messages
            put("input", JsonArray(conversationMessages))

            // Add tools section for web search and custom tools if enabled
            val toolsArray = buildJsonArray {
                // Add web search tool if enabled
                if (webSearchEnabled) {
                    add(buildJsonObject {
                        put("type", "web_search_preview")
                    })
                }
                
                // Add custom tools from enabledTools list
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
                        }
                        else {
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
            return ApiResponse.Error("HTTP $responseCode: $response")
        }
        
            // Parse response according to providers.json format
        try {
            val responseJson = json.parseToJsonElement(response).jsonObject
            val outputArray = responseJson["output"]?.jsonArray
            
            var responseText = ""
            var pendingToolCall: OpenAIToolCallPayload? = null

            outputArray?.forEach { outputElement ->
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
                                pendingToolCall = OpenAIToolCallPayload(
                                    callId = callId,
                                    functionName = name,
                                    rawArguments = arguments
                                )
                            }
                        }
                    }
                }
            }

            // If we found a tool call, pass it to the registry for decoding later
            if (pendingToolCall != null) {
                notifyToolCallCollected(
                    provider = "openai",
                    callId = pendingToolCall.callId,
                    functionName = pendingToolCall.functionName,
                    rawArguments = pendingToolCall.rawArguments
                )
                ToolRegistry.getInstance().registerPendingToolCall(
                    provider = "openai",
                    callId = pendingToolCall.callId,
                    toolName = pendingToolCall.functionName,
                    rawArguments = pendingToolCall.rawArguments
                )
                return ApiResponse.Success("")
            }

            return ApiResponse.Success(responseText)
        } catch (e: Exception) {
            return ApiResponse.Error("Failed to parse response: ${e.message}")
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
            // Build request URL
            val url = URL(provider.request.base_url)
            val connection = url.openConnection() as HttpURLConnection
            
            // Set headers
            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            // Build conversation history for OpenAI format
            val conversationMessages = mutableListOf<JsonObject>()
            
            // Add system prompt
            if (systemPrompt.isNotBlank()) {
                conversationMessages.add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            
            // Add messages from conversation history
            messages.forEach { message ->
                when (message.role) {
                    "user" -> {
                        if (message.attachments.isEmpty()) {
                            // Text-only message
                            conversationMessages.add(buildJsonObject {
                                put("role", "user")
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "input_text")
                                        put("text", message.text)
                                    })
                                })
                            })
                        } else {
                            // Message with attachments
                            conversationMessages.add(buildJsonObject {
                                put("role", "user")
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "input_text")
                                        put("text", message.text)
                                    })
                                    // Add file attachments
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
                        conversationMessages.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", message.text)
                        })
                    }
                }
            }
            
            // Build request body with streaming enabled
            val requestBody = buildJsonObject {
                put("model", modelName)
                put("input", JsonArray(conversationMessages))
                put("stream", true)  // Enable streaming
                
                // Add tools section for web search and custom tools if enabled
                val toolsArray = buildJsonArray {
                    // Add web search tool if enabled
                    if (webSearchEnabled) {
                        add(buildJsonObject {
                            put("type", "web_search_preview")
                        })
                    }
                    
                    // Add custom tools from enabledTools list
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
                            }
                            else {
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
                    connection.disconnect() // Disconnect the failed stream connection

                    // Run the non-streaming version as a fallback
                    val fallbackResponse = sendOpenAIMessageNonStreaming(provider, modelName, messages, systemPrompt, apiKeys, webSearchEnabled, enabledTools)

                    when (fallbackResponse) {
                        is ApiResponse.Success -> {
                            // We got a successful response from the fallback.
                            // We can call onComplete directly.
                            // The ViewModel will handle adding the message.
                            callback.onComplete(fallbackResponse.message)
                        }
                        is ApiResponse.Error -> {
                            // The fallback also failed.
                            callback.onError("Streaming failed (400), fallback also failed: ${fallbackResponse.error}")
                        }
                    }
                } else {
                    // For other errors (not 400), report them directly.
                    val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                    callback.onError("HTTP $responseCode: $errorBody")
                }
                return // Exit function as we've handled the error/fallback
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            
            val fullResponse = StringBuilder()
            val toolCallBuffers = mutableMapOf<String, OpenAIToolCallBuffer>()
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!
                
                // OpenAI SSE format: "data: {json}" for each chunk
                if (currentLine.startsWith("data:")) {
                    val dataContent = currentLine.substring(5).trim()
                    Log.d("TOOL_CALL_DEBUG", "RAW DATA CHUNK: $dataContent")
                    Log.d("TOOL_CALL_DEBUG", "RAW DATA CHUNK: $dataContent")
                    
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
                        Log.d("TOOL_CALL_DEBUG", "Parsed JSON chunk successfully.")
                        val eventType = chunkJson["type"]?.jsonPrimitive?.content
                        
                        when (eventType) {
                            "response.output_text.delta" -> {
                                val deltaText = chunkJson["delta"]?.jsonPrimitive?.content
                                if (deltaText != null) {
                                    fullResponse.append(deltaText)
                                    callback.onPartialResponse(deltaText)
                                }
                            }
                            "response.output_item.added" -> {
                                val item = chunkJson["item"]?.jsonObject
                                val itemType = item?.get("type")?.jsonPrimitive?.content
                                if (itemType == "tool_call") {
                                    val callId = item["id"]?.jsonPrimitive?.content
                                    val functionName = item["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                                    val initialArgs = item["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content
                                    if (callId != null && functionName != null) {
                                        val buffer = OpenAIToolCallBuffer(functionName = functionName)
                                        if (!initialArgs.isNullOrEmpty()) {
                                            buffer.argumentsBuilder.append(initialArgs)
                                        }
                                        toolCallBuffers[callId] = buffer
                                        Log.d("TOOL_CALL_DEBUG", "Registered tool call buffer for $functionName ($callId)")
                                    }
                                }
                            }
                            "response.function_call_arguments.delta" -> {
                                val callId = chunkJson["item_id"]?.jsonPrimitive?.content
                                val delta = chunkJson["delta"]?.jsonPrimitive?.content
                                if (!callId.isNullOrEmpty() && !delta.isNullOrEmpty()) {
                                    val buffer = toolCallBuffers[callId]
                                    if (buffer != null) {
                                        buffer.argumentsBuilder.append(delta)
                                    } else {
                                        Log.w("TOOL_CALL_DEBUG", "Delta received for unknown tool call id: $callId")
                                    }
                                }
                            }
                            "response.function_call_arguments.done" -> {
                                val callId = chunkJson["item_id"]?.jsonPrimitive?.content
                                if (!callId.isNullOrEmpty()) {
                                    val buffer = toolCallBuffers[callId]
                                    if (buffer != null) {
                                        val completedArguments = chunkJson["arguments"]?.jsonPrimitive?.content
                                            ?: buffer.argumentsBuilder.toString()
                                        notifyToolCallCollected(
                                            provider = "openai",
                                            callId = callId,
                                            functionName = buffer.functionName,
                                            rawArguments = completedArguments
                                        )
                                        ToolRegistry.getInstance().registerPendingToolCall(
                                            provider = "openai",
                                            callId = callId,
                                            toolName = buffer.functionName,
                                            rawArguments = completedArguments
                                        )
                                        toolCallBuffers.remove(callId)
                                    } else {
                                        Log.w("TOOL_CALL_DEBUG", "Completed arguments received for unknown tool call id: $callId")
                                    }
                                }
                            }
                            "response.completed" -> {
                                // Can handle final usage stats here if needed
                                break
                            }
                            "response.failed" -> {
                                val error = chunkJson["response"]?.jsonObject?.get("error")?.jsonObject
                                val errorMessage = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                                callback.onError("OpenAI API error: $errorMessage")
                                return
                            }
                        }
                    } catch (jsonException: Exception) {
                        Log.e("TOOL_CALL_DEBUG", "Error parsing chunk: ${jsonException.message}")
                        // Continue reading other chunks on JSON parsing error
                        continue
                    }
                }
            }
            
            reader.close()
            connection.disconnect()
            
            callback.onComplete(fullResponse.toString())
        } catch (e: Exception) {
            callback.onError("Failed to send OpenAI streaming message: ${e.message}")
        }
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
