package com.example.ApI.data.network.providers

import android.content.Context
import android.util.Log
import com.example.ApI.data.model.*
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
 * OpenRouter API provider implementation.
 * Uses OpenAI-compatible format for multi-model access.
 */
class OpenRouterProvider(context: Context) : BaseProvider(context) {

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
                callback,
                temperature = temperature
            )

            when (result) {
                is StreamingResult.TextResponse -> {
                    callback.onComplete(result.text)
                }
                is StreamingResult.Error -> {
                    callback.onError(result.message)
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send OpenRouter message: ${e.message}")
        }
    }

    private suspend fun makeStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        callback: StreamingCallback,
        temperature: Float? = null
    ): StreamingResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(provider.request.base_url)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("HTTP-Referer", "https://github.com/your-app")
            connection.setRequestProperty("X-Title", "LLM Chat App")
            connection.doOutput = true

            val openRouterMessages = buildMessages(messages, systemPrompt)
            val requestBody = buildRequestBody(modelName, openRouterMessages, temperature)

            Log.d("OpenRouter", "Request body: $requestBody")

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
            StreamingResult.Error("OpenRouter request failed: ${e.message}")
        }
    }

    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback
    ): StreamingResult {
        val fullResponse = StringBuilder()

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            // Skip empty lines and comments (OpenRouter sends ": OPENROUTER PROCESSING" keepalives)
            if (currentLine.isBlank() || currentLine.startsWith(":")) continue

            // Check for [DONE] marker
            if (currentLine == "data: [DONE]") {
                Log.d("OpenRouter", "Stream complete")
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
                        return StreamingResult.Error(errorMessage)
                    }

                    // Extract text from choices[0].delta.content
                    val choices = chunkJson["choices"]?.jsonArray
                    if (choices != null && choices.isNotEmpty()) {
                        val delta = choices[0].jsonObject["delta"]?.jsonObject
                        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull

                        if (!content.isNullOrEmpty()) {
                            fullResponse.append(content)
                            callback.onPartialResponse(content)
                        }

                        // Check finish_reason
                        val finishReason = choices[0].jsonObject["finish_reason"]?.jsonPrimitive?.contentOrNull
                        if (finishReason == "stop" || finishReason == "length") {
                            Log.d("OpenRouter", "Finish reason: $finishReason")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("OpenRouter", "Error parsing chunk: ${e.message}")
                    continue
                }
            }
        }

        reader.close()
        connection.disconnect()

        return if (fullResponse.isNotEmpty()) {
            StreamingResult.TextResponse(fullResponse.toString())
        } else {
            StreamingResult.Error("Empty response from OpenRouter")
        }
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
                                        val base64Data = attachment.local_file_path?.let { path ->
                                            try {
                                                val file = File(path)
                                                if (file.exists()) {
                                                    Base64.getEncoder().encodeToString(file.readBytes())
                                                } else null
                                            } catch (e: Exception) { null }
                                        }
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
                        add(buildJsonObject {
                            put("role", "assistant")
                            put("content", message.text)
                        })
                    }
                    // Skip tool_call and tool_response for now
                }
            }
        }
    }

    private fun buildRequestBody(
        modelName: String,
        openRouterMessages: JsonArray,
        temperature: Float?
    ): JsonObject {
        return buildJsonObject {
            put("model", modelName)
            put("messages", openRouterMessages)
            put("stream", true)
            put("max_tokens", 8192)
            if (temperature != null) {
                put("temperature", temperature.toDouble())
            }
        }
    }

    private sealed class StreamingResult {
        data class TextResponse(val text: String) : StreamingResult()
        data class Error(val message: String) : StreamingResult()
    }
}
