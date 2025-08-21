package com.example.ApI.data.network

import android.content.Context
import com.example.ApI.data.model.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
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
        webSearchEnabled: Boolean = false
    ): ApiResponse = withContext(Dispatchers.IO) {
        
        when (provider.provider) {
            "openai" -> sendOpenAIMessage(provider, modelName, messages, systemPrompt, apiKeys, webSearchEnabled)
            "poe" -> sendPoeMessage(provider, modelName, messages, systemPrompt, apiKeys, webSearchEnabled)
            "google" -> sendGoogleMessage(provider, modelName, messages, systemPrompt, apiKeys, webSearchEnabled)
            else -> throw IllegalArgumentException("Unknown provider: ${provider.provider}")
        }
    }

    suspend fun sendMessageStreaming(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false,
        callback: StreamingCallback
    ): Unit = withContext(Dispatchers.IO) {
        
        when (provider.provider) {
            "openai" -> sendOpenAIMessageStreaming(provider, modelName, messages, systemPrompt, apiKeys, webSearchEnabled, callback)
            "poe" -> sendPoeMessageStreaming(provider, modelName, messages, systemPrompt, apiKeys, webSearchEnabled, callback)
            "google" -> sendGoogleMessageStreaming(provider, modelName, messages, systemPrompt, apiKeys, webSearchEnabled, callback)
            else -> {
                callback.onError("Unknown provider: ${provider.provider}")
            }
        }
    }

    private suspend fun sendOpenAIMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false
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
            put("input", JsonArray(conversationMessages))
            
            // Add tools section for web search if enabled
            if (webSearchEnabled) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "web_search_preview")
                    })
                })
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
            outputArray?.forEach { outputElement ->
                val outputObj = outputElement.jsonObject
                if (outputObj["type"]?.jsonPrimitive?.content == "message") {
                    val content = outputObj["content"]?.jsonArray
                    content?.forEach { contentElement ->
                        val contentObj = contentElement.jsonObject
                        if (contentObj["type"]?.jsonPrimitive?.content == "output_text") {
                            responseText = contentObj["text"]?.jsonPrimitive?.content ?: ""
                        }
                    }
                }
            }
            
            return ApiResponse.Success(responseText)
        } catch (e: Exception) {
            return ApiResponse.Error("Failed to parse response: ${e.message}")
        }
    }

    private suspend fun sendOpenAIMessageStreaming(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false,
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
                
                // Add tools section for web search if enabled
                if (webSearchEnabled) {
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "web_search_preview")
                        })
                    })
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
                    val fallbackResponse = sendOpenAIMessage(provider, modelName, messages, systemPrompt, apiKeys)

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
        webSearchEnabled: Boolean = false
    ): ApiResponse {
        val apiKey = apiKeys["poe"] ?: throw IllegalArgumentException("Poe API key is required")
        
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
        } else {
            BufferedReader(InputStreamReader(connection.inputStream))
        }
        
        if (responseCode >= 400) {
            val errorResponse = reader.readText()
            reader.close()
            connection.disconnect()
            return ApiResponse.Error("HTTP $responseCode: $errorResponse")
        }
        
        // Parse server-sent events response similar to Python implementation
        try {
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
                        
                        // Check for text content
                        val text = eventJson["text"]?.jsonPrimitive?.content
                        if (text != null) {
                            fullResponse.append(text)
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
                                }
                            }
                            "replace_response" -> {
                                val eventData = eventJson["data"]?.jsonObject
                                val replacementText = eventData?.get("text")?.jsonPrimitive?.content
                                if (replacementText != null) {
                                    fullResponse.clear()
                                    fullResponse.append(replacementText)
                                }
                            }
                            "error" -> {
                                val eventData = eventJson["data"]?.jsonObject
                                val errorText = eventData?.get("text")?.jsonPrimitive?.content ?: "Unknown error"
                                val errorType = eventData?.get("error_type")?.jsonPrimitive?.content
                                reader.close()
                                connection.disconnect()
                                return ApiResponse.Error("Poe API error ($errorType): $errorText")
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
            
            return ApiResponse.Success(fullResponse.toString())
        } catch (e: Exception) {
            reader.close()
            connection.disconnect()
            return ApiResponse.Error("Failed to parse Poe SSE response: ${e.message}")
        }
    }

    private suspend fun sendPoeMessageStreaming(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false,
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
        webSearchEnabled: Boolean = false
    ): ApiResponse {
        val apiKey = apiKeys["google"] ?: throw IllegalArgumentException("Google API key is required")
        
        // Build request URL - replace {model_name} placeholder and add API key
        val baseUrl = provider.request.base_url.replace("{model_name}", modelName)
        val url = URL("$baseUrl?key=$apiKey")
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
                    parts.add(buildJsonObject {
                        put("text", message.text)
                    })
                    
                    // Add file attachments
                    message.attachments.forEach { attachment ->
                        parts.add(buildJsonObject {
                            put("file_data", buildJsonObject {
                                put("mime_type", attachment.mime_type)
                                put("file_uri", attachment.file_GOOGLE_uri ?: "{file_URI}")
                            })
                        })
                    }
                    
                    conversationContents.add(buildJsonObject {
                        put("role", "user")
                        put("parts", JsonArray(parts))
                    })
                }
                "assistant" -> {
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
            
            // Add tools section for web search if enabled
            if (webSearchEnabled) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("google_search", buildJsonObject {})
                    })
                })
            }
            
            put("contents", JsonArray(conversationContents))
        }
        
        // Send request
        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(json.encodeToString(requestBodyBuilder))
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
            val candidates = responseJson["candidates"]?.jsonArray
            
            if (candidates != null && candidates.isNotEmpty()) {
                val firstCandidate = candidates[0].jsonObject
                val content = firstCandidate["content"]?.jsonObject
                val parts = content?.get("parts")?.jsonArray
                
                if (parts != null && parts.isNotEmpty()) {
                    val firstPart = parts[0].jsonObject
                    val responseText = firstPart["text"]?.jsonPrimitive?.content ?: ""
                    return ApiResponse.Success(responseText)
                }
            }
            
            return ApiResponse.Error("No valid response found in Google API response")
        } catch (e: Exception) {
            return ApiResponse.Error("Failed to parse Google response: ${e.message}")
        }
    }

    private suspend fun sendGoogleMessageStreaming(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false,
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
                        parts.add(buildJsonObject {
                            put("text", message.text)
                        })
                        
                        // Add file attachments
                        message.attachments.forEach { attachment ->
                            parts.add(buildJsonObject {
                                put("file_data", buildJsonObject {
                                    put("mime_type", attachment.mime_type)
                                    put("file_uri", attachment.file_GOOGLE_uri ?: "{file_URI}")
                                })
                            })
                        }
                        
                        conversationContents.add(buildJsonObject {
                            put("role", "user")
                            put("parts", JsonArray(parts))
                        })
                    }
                    "assistant" -> {
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
                
                // Add tools section for web search if enabled
                if (webSearchEnabled) {
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("google_search", buildJsonObject {})
                        })
                    })
                }
                
                put("contents", JsonArray(conversationContents))
            }
            
            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(json.encodeToString(requestBodyBuilder))
            writer.flush()
            writer.close()
            
            // Read streaming response
            val responseCode = connection.responseCode
            val reader = if (responseCode >= 400) {
                BufferedReader(InputStreamReader(connection.errorStream))
                callback.onError("HTTP $responseCode")
                return
            } else {
                BufferedReader(InputStreamReader(connection.inputStream))
            }
            
            val fullResponse = StringBuilder()
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
                        val candidates = chunkJson["candidates"]?.jsonArray
                        
                        if (candidates != null && candidates.isNotEmpty()) {
                            val firstCandidate = candidates[0].jsonObject
                            val content = firstCandidate["content"]?.jsonObject
                            val parts = content?.get("parts")?.jsonArray
                            
                            if (parts != null && parts.isNotEmpty()) {
                                val firstPart = parts[0].jsonObject
                                val chunkText = firstPart["text"]?.jsonPrimitive?.content
                                
                                if (chunkText != null && chunkText.isNotEmpty()) {
                                    fullResponse.append(chunkText)
                                    callback.onPartialResponse(chunkText)
                                }
                            }
                        }
                    } catch (jsonException: Exception) {
                        // Continue reading other chunks on JSON parsing error
                        continue
                    }
                }
            }
            
            reader.close()
            connection.disconnect()
            
            callback.onComplete(fullResponse.toString())
        } catch (e: Exception) {
            callback.onError("Failed to send Google streaming message: ${e.message}")
        }
    }
}

// Interface for streaming callbacks
interface StreamingCallback {
    fun onPartialResponse(text: String)
    fun onComplete(fullText: String)
    fun onError(error: String)
}

sealed class ApiResponse {
    data class Success(val message: String) : ApiResponse()
    data class Error(val error: String) : ApiResponse()
}
