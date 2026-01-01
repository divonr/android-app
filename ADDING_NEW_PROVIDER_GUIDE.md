# Complete Guide: Adding a New Provider to the Android LLM Chat App

This guide demonstrates how to add a new LLM provider, based on the actual implementation of the LLM Stats (ZeroEval) provider.

## Table of Contents
1. [Overview](#overview)
2. [Files to Modify](#files-to-modify)
3. [Step 1: Create Provider Implementation](#step-1-create-provider-implementation)
4. [Step 2: Register in LLMApiService](#step-2-register-in-llmapiservice)
5. [Step 3: Add to ModelsCacheManager](#step-3-add-to-modelscachemanager)
6. [Step 4: Add String Resources](#step-4-add-string-resources)
7. [Step 5: Update UI Components](#step-5-update-ui-components)
8. [Step 6: Optional Features](#step-6-optional-features)
9. [Common Pitfalls](#common-pitfalls)
10. [Testing Checklist](#testing-checklist)

---

## Overview

The app uses a modular provider architecture. Each LLM provider has its own implementation file that handles:
- Building API requests
- Streaming response parsing (SSE)
- Tool calling and chaining
- Thinking/reasoning support

### Architecture Flow

```
User Input (ChatScreen)
    ↓
ChatViewModel.sendMessage()
    ↓
DataRepository.sendMessageStreaming()
    ↓
LLMApiService.sendMessage()   ← Thin coordinator (routes by provider name)
    ↓
YourProvider.sendMessage()    ← Your implementation
    ↓
StreamingCallback.onPartialResponse()
    ↓
ChatViewModel updates UI state
    ↓
UI renders streaming text
```

---

## Files to Modify

| File | Purpose |
|------|---------|
| `data/network/providers/YourProvider.kt` | **NEW FILE** - Provider implementation |
| `data/network/LLMApiService.kt` | Register provider in routing |
| `data/repository/ModelsCacheManager.kt` | Default models + Provider entry |
| `res/values/strings.xml` | String resources |
| `ui/components/ChatTopBar.kt` | Provider name display |
| `ui/screen/ApiKeysScreen.kt` | API key card brand colors |
| `ui/components/dialogs/ApiKeyDialogs.kt` | Provider name in dropdown |
| `ui/components/dialogs/ModelSelectionDialogs.kt` | Provider name in tabs |

### Optional Files (for additional features)
| File | Purpose |
|------|---------|
| `data/repository/FileUploadManager.kt` | If provider needs separate file upload |
| `data/model/ChatHistory.kt` | If adding file ID field to Attachment |
| `ui/managers/provider/ModelSelectionManager.kt` | Web search support configuration |
| `ui/screen/UsernameScreen.kt` | Title generation provider option |

---

## Step 1: Create Provider Implementation

### File: `app/src/main/java/com/example/ApI/data/network/providers/YourProvider.kt`

**Best template to copy:** `OpenRouterProvider.kt` for OpenAI-compatible APIs

```kotlin
package com.example.ApI.data.network.providers

import android.content.Context
import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * YourProvider API implementation.
 * Uses OpenAI-compatible format for chat completions.
 */
class YourProvider(context: Context) : BaseProvider(context) {

    companion object {
        private const val TAG = "YourProvider"
    }

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
                provider, modelName, messages, systemPrompt, apiKey,
                enabledTools, thinkingBudget, callback, temperature = temperature
            )

            when (result) {
                is ProviderStreamingResult.TextComplete -> {
                    callback.onComplete(result.fullText)
                }
                is ProviderStreamingResult.ToolCallDetected -> {
                    handleToolCall(
                        provider, modelName, messages, systemPrompt, apiKey,
                        enabledTools, thinkingBudget, temperature, result, callback
                    )
                }
                is ProviderStreamingResult.Error -> {
                    callback.onError(result.error)
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send message: ${e.message}")
        }
    }

    private suspend fun handleToolCall(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?,
        initialResponse: ProviderStreamingResult.ToolCallDetected,
        callback: StreamingCallback
    ) {
        val finalText = handleToolCallChain(
            initialToolCall = initialResponse.toolCall,
            initialPrecedingText = initialResponse.precedingText,
            messages = messages,
            modelName = modelName,
            callback = callback
        ) { updatedMessages ->
            makeStreamingRequest(
                provider, modelName, updatedMessages, systemPrompt, apiKey,
                enabledTools, thinkingBudget, callback, temperature = temperature
            )
        }

        if (finalText != null) {
            callback.onComplete(finalText)
        } else {
            callback.onError("Maximum tool call depth ($MAX_TOOL_DEPTH) exceeded")
        }
    }

    private suspend fun makeStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
        callback: StreamingCallback,
        temperature: Float? = null
    ): ProviderStreamingResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(provider.request.base_url)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val providerMessages = buildMessages(messages, systemPrompt)
            val requestBody = buildRequestBody(
                modelName, providerMessages, enabledTools, thinkingBudget, temperature
            )

            Log.d(TAG, "Request body: $requestBody")

            connection.outputStream.write(requestBody.toString().toByteArray())
            connection.outputStream.flush()

            val responseCode = connection.responseCode
            if (responseCode >= 400) {
                val errorReader = BufferedReader(InputStreamReader(connection.errorStream))
                val errorResponse = errorReader.readText()
                errorReader.close()
                connection.disconnect()

                val errorMessage = try {
                    val errorJson = json.parseToJsonElement(errorResponse).jsonObject
                    errorJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: errorResponse
                } catch (e: Exception) {
                    errorResponse
                }

                return@withContext ProviderStreamingResult.Error("HTTP $responseCode: $errorMessage")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            parseStreamingResponse(reader, connection, callback, thinkingBudget)
        } catch (e: Exception) {
            ProviderStreamingResult.Error("Request failed: ${e.message}")
        }
    }

    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback,
        thinkingBudget: ThinkingBudgetValue
    ): ProviderStreamingResult {
        val fullResponse = StringBuilder()
        val reasoningBuilder = StringBuilder()

        var detectedToolCall: ToolCall? = null
        val toolCallsBuilder = mutableMapOf<Int, ToolCallBuilder>()

        var isInReasoningPhase = false
        var reasoningStartTime: Long = 0

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            if (currentLine.isBlank() || currentLine.startsWith(":")) continue

            if (currentLine == "data: [DONE]") {
                break
            }

            if (currentLine.startsWith("data: ")) {
                val dataContent = currentLine.substring(6).trim()
                if (dataContent.isEmpty() || dataContent == "[DONE]") continue

                try {
                    val chunkJson = json.parseToJsonElement(dataContent).jsonObject

                    val error = chunkJson["error"]?.jsonObject
                    if (error != null) {
                        val errorMessage = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                        reader.close()
                        connection.disconnect()
                        return ProviderStreamingResult.Error(errorMessage)
                    }

                    val choices = chunkJson["choices"]?.jsonArray
                    if (choices != null && choices.isNotEmpty()) {
                        val choice = choices[0].jsonObject
                        val delta = choice["delta"]?.jsonObject
                        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

                        if (delta != null) {
                            // Handle reasoning/thinking content
                            val reasoningContent = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                            if (!reasoningContent.isNullOrEmpty()) {
                                if (!isInReasoningPhase) {
                                    isInReasoningPhase = true
                                    reasoningStartTime = System.currentTimeMillis()
                                    callback.onThinkingStarted()
                                }
                                reasoningBuilder.append(reasoningContent)
                                callback.onThinkingPartial(reasoningContent)
                            }

                            // Extract text content
                            val content = delta["content"]?.jsonPrimitive?.contentOrNull
                            if (!content.isNullOrEmpty()) {
                                if (isInReasoningPhase) {
                                    completeThinkingPhase(callback, reasoningStartTime, reasoningBuilder)
                                    isInReasoningPhase = false
                                }
                                fullResponse.append(content)
                                callback.onPartialResponse(content)
                            }

                            // IMPORTANT: Handle JsonNull properly!
                            val toolCalls = delta["tool_calls"]?.takeIf { it !is JsonNull }?.jsonArray
                            if (toolCalls != null) {
                                if (isInReasoningPhase) {
                                    completeThinkingPhase(callback, reasoningStartTime, reasoningBuilder)
                                    isInReasoningPhase = false
                                }

                                toolCalls.forEach { toolCallElement ->
                                    val toolCallObj = toolCallElement.jsonObject
                                    val index = toolCallObj["index"]?.jsonPrimitive?.intOrNull ?: 0

                                    val builder = toolCallsBuilder.getOrPut(index) { ToolCallBuilder() }

                                    toolCallObj["id"]?.jsonPrimitive?.contentOrNull?.let { id ->
                                        builder.id = id
                                    }

                                    val function = toolCallObj["function"]?.jsonObject
                                    if (function != null) {
                                        function["name"]?.jsonPrimitive?.contentOrNull?.let { name ->
                                            builder.name = name
                                        }
                                        function["arguments"]?.jsonPrimitive?.contentOrNull?.let { args ->
                                            builder.argumentsBuilder.append(args)
                                        }
                                    }
                                }
                            }
                        }

                        if (finishReason == "tool_calls") {
                            val firstBuilder = toolCallsBuilder[0]
                            if (firstBuilder != null && firstBuilder.isComplete()) {
                                try {
                                    val argumentsStr = firstBuilder.argumentsBuilder.toString().ifEmpty { "{}" }
                                    val paramsJson = json.parseToJsonElement(argumentsStr).jsonObject

                                    detectedToolCall = ToolCall(
                                        id = firstBuilder.id!!,
                                        toolId = firstBuilder.name!!,
                                        parameters = paramsJson,
                                        provider = "yourprovider"  // CHANGE THIS
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing tool call arguments: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing chunk: ${e.message}")
                    continue
                }
            }
        }

        reader.close()
        connection.disconnect()

        if (isInReasoningPhase) {
            completeThinkingPhase(callback, reasoningStartTime, reasoningBuilder)
        }

        val thoughtsContent = reasoningBuilder.toString().takeIf { it.isNotEmpty() }
        val thinkingDuration = if (reasoningStartTime > 0) {
            (System.currentTimeMillis() - reasoningStartTime) / 1000f
        } else null
        val thoughtsStatus = when {
            thoughtsContent != null -> ThoughtsStatus.PRESENT
            thinkingBudget is ThinkingBudgetValue.Effort && thinkingBudget.level.isNotBlank() ->
                ThoughtsStatus.UNAVAILABLE
            else -> ThoughtsStatus.NONE
        }

        return when {
            detectedToolCall != null -> ProviderStreamingResult.ToolCallDetected(
                toolCall = detectedToolCall,
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
            else -> ProviderStreamingResult.Error("Empty response from YourProvider")
        }
    }

    private class ToolCallBuilder {
        var id: String? = null
        var name: String? = null
        val argumentsBuilder = StringBuilder()
        fun isComplete(): Boolean = id != null && name != null
    }

    private fun buildMessages(messages: List<Message>, systemPrompt: String): JsonArray {
        return buildJsonArray {
            if (systemPrompt.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }

            messages.forEach { message ->
                when (message.role) {
                    "user" -> {
                        val hasImages = message.attachments.any { it.mime_type.startsWith("image/") }
                        if (hasImages) {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", message.text)
                                    })
                                    message.attachments.filter { it.mime_type.startsWith("image/") }
                                        .forEach { attachment ->
                                            val base64Data = readFileAsBase64(attachment.local_file_path)
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
                        val nextIndex = messages.indexOf(message) + 1
                        val nextMessage = messages.getOrNull(nextIndex)
                        if (nextMessage?.role != "tool_call") {
                            add(buildJsonObject {
                                put("role", "assistant")
                                put("content", message.text)
                            })
                        }
                    }
                    "tool_call" -> {
                        val prevIndex = messages.indexOf(message) - 1
                        val prevMessage = messages.getOrNull(prevIndex)

                        add(buildJsonObject {
                            put("role", "assistant")
                            if (prevMessage?.role == "assistant" && prevMessage.text.isNotBlank()) {
                                put("content", prevMessage.text)
                            } else {
                                put("content", JsonNull)
                            }
                            put("tool_calls", buildJsonArray {
                                add(buildJsonObject {
                                    put("id", message.toolCallId ?: "")
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", message.toolCall?.toolId ?: "")
                                        put("arguments", message.toolCall?.parameters?.toString() ?: "{}")
                                    })
                                })
                            })
                        })
                    }
                    "tool_response" -> {
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", message.toolResponseCallId ?: "")
                            put("content", message.toolResponseOutput ?: "")
                        })
                    }
                }
            }
        }
    }

    private fun buildRequestBody(
        modelName: String,
        messages: JsonArray,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?
    ): JsonObject {
        return buildJsonObject {
            put("model", modelName)
            put("messages", messages)
            put("stream", true)
            put("max_tokens", 8192)

            if (temperature != null) {
                put("temperature", temperature.toDouble())
            }

            if (enabledTools.isNotEmpty()) {
                put("tools", buildToolsArray(enabledTools))
            }
        }
    }

    private fun buildToolsArray(enabledTools: List<ToolSpecification>): JsonArray {
        return buildJsonArray {
            enabledTools.forEach { toolSpec ->
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", toolSpec.name)
                        put("description", toolSpec.description)
                        // IMPORTANT: Use parameters directly - it's already a complete schema!
                        if (toolSpec.parameters != null) {
                            put("parameters", toolSpec.parameters)
                        } else {
                            put("parameters", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {})
                            })
                        }
                    })
                })
            }
        }
    }
}
```

---

## Step 2: Register in LLMApiService

### File: `app/src/main/java/com/example/ApI/data/network/LLMApiService.kt`

```kotlin
class LLMApiService(private val context: Context) {

    // Add lazy provider instance
    private val yourProvider by lazy { YourProvider(context) }

    suspend fun sendMessage(...) {
        when (provider.provider) {
            "openai" -> openAIProvider.sendMessage(...)
            "google" -> googleProvider.sendMessage(...)
            // ... other providers ...
            "yourprovider" -> yourProvider.sendMessage(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, temperature, callback
            )
            else -> callback.onError("Unknown provider: ${provider.provider}")
        }
    }
}
```

---

## Step 3: Add to ModelsCacheManager

### File: `app/src/main/java/com/example/ApI/data/repository/ModelsCacheManager.kt`

### 3.1: Add Default Models List

```kotlin
val defaultYourProviderModels = listOf(
    Model.SimpleModel("model-fast"),
    Model.SimpleModel("model-standard"),
    Model.SimpleModel("model-pro")
)
```

### 3.2: Add Provider Entry in `buildProviders()`

```kotlin
Provider(
    provider = "yourprovider",
    models = getModelsForProvider("yourprovider", defaultYourProviderModels),
    request = ApiRequest(
        request_type = "POST",
        base_url = "https://api.yourprovider.com/v1/chat/completions",
        headers = mapOf(
            "Authorization" to "Bearer {YOURPROVIDER_API_KEY_HERE}",
            "Content-Type" to "application/json"
        ),
        body = null
    ),
    response_important_fields = ResponseFields(
        response_format = "server_sent_events"
    ),
    upload_files_request = null,
    upload_files_response_important_fields = null
)
```

---

## Step 4: Add String Resources

### File: `app/src/main/res/values/strings.xml`

```xml
<!-- Provider names section -->
<string name="provider_yourprovider">YourProvider</string>
<string name="yourprovider_model">model-fast via YourProvider</string>
```

---

## Step 5: Update UI Components

### 5.1: ChatTopBar - Provider Name Display

**File:** `app/src/main/java/com/example/ApI/ui/components/ChatTopBar.kt`

Find the `when(provider)` block and add:

```kotlin
stringResource(id = when(provider) {
    "openai" -> R.string.provider_openai
    // ... other providers ...
    "yourprovider" -> R.string.provider_yourprovider
    else -> R.string.provider_openai
})
```

### 5.2: ApiKeysScreen - Brand Colors

**File:** `app/src/main/java/com/example/ApI/ui/screen/ApiKeysScreen.kt`

Find the gradient color `when` block and add:

```kotlin
"yourprovider" -> listOf(
    Color(0xFF_YOUR_COLOR).copy(alpha = 0.25f),
    Color(0xFF_YOUR_COLOR).copy(alpha = 0.15f)
)
```

### 5.3: ApiKeyDialogs - Provider Dropdown

**File:** `app/src/main/java/com/example/ApI/ui/components/dialogs/ApiKeyDialogs.kt`

Find the provider name `when` block and add:

```kotlin
"yourprovider" -> R.string.provider_yourprovider
```

### 5.4: ModelSelectionDialogs - Provider Tabs

**File:** `app/src/main/java/com/example/ApI/ui/components/dialogs/ModelSelectionDialogs.kt`

Find any provider name `when` blocks and add:

```kotlin
"yourprovider" -> R.string.provider_yourprovider
```

---

## Step 6: Optional Features

### 6.1: File Upload Support

If your provider needs **separate file upload** (not inline Base64):

**File:** `app/src/main/java/com/example/ApI/data/repository/FileUploadManager.kt`

Add to `ensureAttachmentUploadedForProvider()` and create upload method.

**File:** `app/src/main/java/com/example/ApI/data/model/ChatHistory.kt`

Add field to Attachment:
```kotlin
val file_YOURPROVIDER_id: String? = null
```

### 6.2: Web Search Support

**File:** `app/src/main/java/com/example/ApI/ui/managers/provider/ModelSelectionManager.kt`

Add case in `getWebSearchSupport()`:
```kotlin
"yourprovider" -> WebSearchSupport.OPTIONAL  // or REQUIRED/UNSUPPORTED
```

### 6.3: Title Generation

**File:** `app/src/main/java/com/example/ApI/ui/screen/UsernameScreen.kt`

Add provider to title generation selector if desired.

---

## Common Pitfalls

### 1. JsonNull vs Kotlin null

**Problem:** When API sends `"tool_calls": null`, calling `.jsonArray` throws an exception.

**Solution:**
```kotlin
// WRONG:
val toolCalls = delta["tool_calls"]?.jsonArray

// CORRECT:
val toolCalls = delta["tool_calls"]?.takeIf { it !is JsonNull }?.jsonArray
```

### 2. Tool Parameters Format

**Problem:** `toolSpec.parameters` is already a complete JSON schema. Don't wrap it!

**Solution:**
```kotlin
// WRONG:
put("parameters", buildJsonObject {
    put("type", "object")
    put("properties", toolSpec.parameters)  // Double-wrapping!
})

// CORRECT:
if (toolSpec.parameters != null) {
    put("parameters", toolSpec.parameters)  // Use directly
}
```

### 3. Provider Dropdown Shows Only Active Keys

**Problem:** AddApiKeyDialog only shows providers with existing keys.

**Solution:** In `MainActivity.kt`, pass `repository.loadProviders()` instead of `uiState.availableProviders` to ApiKeysScreen.

### 4. No `[DONE]` Marker

Some APIs don't send `data: [DONE]`. They just close the stream. The code handles this - `readLine()` returns null when stream ends.

### 5. SSE Comment Lines

Some APIs send `:` prefixed comment lines (keepalives). Skip them:
```kotlin
if (currentLine.startsWith(":")) continue
```

### 6. Streaming Tool Calls

Tool calls arrive in multiple chunks:
- Chunk 1: `id` and `name`
- Chunk 2: `arguments`
- Chunk 3: `finish_reason: "tool_calls"`

Use `ToolCallBuilder` to accumulate across chunks.

---

## Testing Checklist

### Basic Functionality
- [ ] API key can be added
- [ ] Provider appears in model selector
- [ ] Basic text messages stream correctly
- [ ] Multi-turn conversation works
- [ ] Provider name displays correctly in UI

### Tool Calling
- [ ] Tools are sent in correct format
- [ ] Tool calls are detected
- [ ] Tool results are returned to model
- [ ] Tool chaining works (multiple calls)

### Thinking/Reasoning
- [ ] Thinking indicator shows
- [ ] Thoughts are captured and displayed
- [ ] Thinking completes before content

### Error Handling
- [ ] Invalid API key shows error
- [ ] Network errors are handled
- [ ] Empty responses show error message

---

## File Structure Reference

```
app/src/main/java/com/example/ApI/
├── data/
│   ├── network/
│   │   ├── LLMApiService.kt          ← Register provider
│   │   └── providers/
│   │       ├── BaseProvider.kt       ← Base class with utilities
│   │       ├── ProviderResult.kt     ← Shared result types
│   │       ├── OpenAIProvider.kt     ← Full featured example
│   │       ├── OpenRouterProvider.kt ← OpenAI-compatible template
│   │       └── YourProvider.kt       ← NEW FILE
│   ├── repository/
│   │   ├── ModelsCacheManager.kt     ← Provider configuration
│   │   └── FileUploadManager.kt      ← File upload (if needed)
│   └── model/
│       └── ChatHistory.kt            ← Attachment model
├── ui/
│   ├── components/
│   │   ├── ChatTopBar.kt             ← Provider name display
│   │   └── dialogs/
│   │       ├── ApiKeyDialogs.kt      ← Provider dropdown
│   │       └── ModelSelectionDialogs.kt ← Provider tabs
│   ├── managers/provider/
│   │   └── ModelSelectionManager.kt  ← Web search support
│   └── screen/
│       ├── ApiKeysScreen.kt          ← Brand colors
│       └── UsernameScreen.kt         ← Title generation
└── res/values/
    └── strings.xml                   ← String resources
```

---

## Summary

Adding a new provider requires:

1. **Create provider implementation** (`providers/YourProvider.kt`)
   - Copy from OpenRouterProvider for OpenAI-compatible APIs
   - Handle JsonNull properly in tool_calls parsing
   - Use toolSpec.parameters directly

2. **Register in LLMApiService** - Add lazy instance and when case

3. **Add to ModelsCacheManager** - Default models and Provider entry

4. **Add string resources** - `provider_yourprovider`

5. **Update UI when statements**:
   - ChatTopBar.kt
   - ApiKeysScreen.kt (brand colors)
   - ApiKeyDialogs.kt
   - ModelSelectionDialogs.kt

6. **Test thoroughly** - Especially tool calling!
