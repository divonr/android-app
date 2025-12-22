# Complete Guide: Adding a New Provider to the Android LLM Chat App

This guide demonstrates how to add a new LLM provider to the app.

## Table of Contents
1. [Overview](#overview)
2. [Step 1: Add Provider Configuration](#step-1-add-provider-configuration)
3. [Step 2: Create Provider Implementation](#step-2-create-provider-implementation)
4. [Step 3: Register Provider in LLMApiService](#step-3-register-provider-in-llmapiservice)
5. [Step 4: Add Provider to DataRepository](#step-4-add-provider-to-datarepository)
6. [Step 5: Update UI Components](#step-5-update-ui-components)
7. [Step 6: Optional Features](#step-6-optional-features)
8. [Testing](#testing)

---

## Overview

The app uses a modular provider architecture. Each LLM provider (OpenAI, Google, Anthropic, etc.) has its own implementation file that handles:
- Building API requests
- Streaming response parsing (SSE)
- Tool calling and chaining
- Thinking/reasoning support

### Architecture Overview

```
User Input (ChatScreen)
    ↓
ChatViewModel.sendMessage()
    ↓
DataRepository.sendMessageStreaming()
    ↓
LLMApiService.sendMessage()   ← Thin coordinator
    ↓  (routes by provider name)
    ↓
YourProvider.sendMessage()    ← Your implementation
    ↓  (tool calling loop)
    ↓
StreamingCallback.onPartialResponse()
    ↓
ChatViewModel updates UI state
    ↓
UI renders streaming text (ChatScreen)
```

### Files to Modify/Create

Adding a new provider requires changes across these files:

| File | Purpose |
|------|---------|
| `app/src/main/assets/providers.json` | Provider API configuration |
| `app/src/main/java/.../providers/YourProvider.kt` | **NEW FILE** - Provider implementation |
| `app/src/main/java/.../network/LLMApiService.kt` | Register provider in routing |
| `app/src/main/java/.../repository/DataRepository.kt` | Provider registration & file uploads |
| `app/src/main/java/.../ui/screen/ApiKeysScreen.kt` | API key UI styling |
| `app/src/main/java/.../ui/ChatViewModel.kt` | Title generation provider list |
| `app/src/main/java/.../ui/screen/UsernameScreen.kt` | Auto-naming settings UI |
| `app/src/main/java/.../ui/screen/ChatScreen.kt` | Provider display name in chat |
| `app/src/main/java/.../ui/components/Dialogs.kt` | Provider display name in selection menus |
| `app/src/main/res/values/strings.xml` | String resources |

---

## Step 1: Add Provider Configuration

### File: `app/src/main/assets/providers.json`

Add your provider's configuration to the JSON array. This defines the API contract.

```json
{
    "provider": "yourprovider",
    "models": [
        "model-v1",
        "model-v2-fast"
    ],
    "request": {
        "request_type": "POST",
        "base_url": "https://api.yourprovider.com/v1/chat",
        "headers": {
            "Authorization": "Bearer {API_KEY_HERE}",
            "Content-Type": "application/json"
        },
        "body": {
            "model": "{model_name}",
            "messages": [...]
        }
    },
    "response_important_fields": {
        "response_format": "server_sent_events",
        "events": [...]
    }
}
```

### Key Configuration Elements:

- **Provider Name**: Lowercase identifier (e.g., `"yourprovider"`)
- **Models**: List of model names available from this provider
- **Request Structure**: API endpoint, headers, body template
- **Response Format**: Document the SSE event structure or JSON response schema
- **File Uploads** (Optional): If your provider supports file uploads, add `upload_files_request` and `upload_files_response_important_fields` sections

---

## Step 2: Create Provider Implementation

### File: `app/src/main/java/com/example/ApI/data/network/providers/YourProvider.kt`

Create a new file extending `BaseProvider`. Use existing providers as reference:
- `OpenAIProvider.kt` - Full featured with tool calling and thinking
- `AnthropicProvider.kt` - Tool calling with thinking support
- `GoogleProvider.kt` - Google Gemini with thinking
- `PoeProvider.kt` - Complex tool result handling
- `CohereProvider.kt` - Cohere v2 API format
- `OpenRouterProvider.kt` - Simple OpenAI-compatible (no tools)

### Template:

```kotlin
package com.example.ApI.data.network.providers

import android.content.Context
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
 * Handles streaming responses, tool calling, and thinking support.
 */
class YourProvider(context: Context) : BaseProvider(context) {

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
            val maxToolIterations = MAX_TOOL_DEPTH
            var currentIteration = 0

            while (currentIteration < maxToolIterations) {
                currentIteration++

                val result = makeStreamingRequest(
                    provider, modelName, conversationMessages, systemPrompt,
                    apiKey, enabledTools, webSearchEnabled, callback,
                    temperature = temperature
                )

                when (result) {
                    is StreamingResult.TextResponse -> {
                        callback.onComplete(result.text)
                        return
                    }
                    is StreamingResult.ToolCallResult -> {
                        // Execute tool and continue conversation
                        val toolResult = callback.onToolCall(result.toolCall, result.precedingText)

                        val toolCallMessage = createToolCallMessage(result.toolCall, toolResult, result.precedingText)
                        val toolResponseMessage = createToolResponseMessage(result.toolCall, toolResult)

                        callback.onSaveToolMessages(toolCallMessage, toolResponseMessage, result.precedingText)

                        conversationMessages.add(toolCallMessage)
                        conversationMessages.add(toolResponseMessage)
                    }
                    is StreamingResult.Error -> {
                        callback.onError(result.message)
                        return
                    }
                }
            }

            callback.onError("Maximum tool calling iterations ($maxToolIterations) reached")
        } catch (e: Exception) {
            callback.onError("Failed to send message: ${e.message}")
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

            // Set up request
            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // Build messages and request body
            val providerMessages = buildMessages(messages, systemPrompt)
            val requestBody = buildRequestBody(modelName, providerMessages, enabledTools, webSearchEnabled, temperature)

            connection.outputStream.write(requestBody.toString().toByteArray())
            connection.outputStream.flush()

            val responseCode = connection.responseCode
            if (responseCode >= 400) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                return@withContext StreamingResult.Error("HTTP $responseCode: $errorBody")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            parseStreamingResponse(reader, connection, callback)
        } catch (e: Exception) {
            StreamingResult.Error("Request failed: ${e.message}")
        }
    }

    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        callback: StreamingCallback
    ): StreamingResult {
        val fullResponse = StringBuilder()
        var detectedToolCall: ToolCall? = null

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            // Parse SSE format - adapt to your provider's format
            if (currentLine.startsWith("data: ")) {
                val dataContent = currentLine.substring(6).trim()
                if (dataContent == "[DONE]") break
                if (dataContent.isBlank()) continue

                try {
                    val chunkJson = json.parseToJsonElement(dataContent).jsonObject

                    // Extract text content - adapt to your provider's response format
                    val text = extractTextFromChunk(chunkJson)
                    if (text != null) {
                        fullResponse.append(text)
                        callback.onPartialResponse(text)
                    }

                    // Check for tool calls - adapt to your provider's format
                    val toolCall = extractToolCallFromChunk(chunkJson)
                    if (toolCall != null) {
                        detectedToolCall = toolCall
                    }
                } catch (e: Exception) {
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
            else -> StreamingResult.Error("Empty response")
        }
    }

    private fun extractTextFromChunk(chunk: JsonObject): String? {
        // Adapt to your provider's response format
        // Example: OpenAI uses choices[0].delta.content
        // Example: Anthropic uses content_block_delta with delta.text
        return null // Implement based on your provider
    }

    private fun extractToolCallFromChunk(chunk: JsonObject): ToolCall? {
        // Adapt to your provider's tool call format
        return null // Implement based on your provider
    }

    private fun buildMessages(messages: List<Message>, systemPrompt: String): List<JsonObject> {
        val providerMessages = mutableListOf<JsonObject>()

        // Add system prompt if provided
        if (systemPrompt.isNotBlank()) {
            providerMessages.add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        // Convert messages to your provider's format
        messages.forEach { message ->
            when (message.role) {
                "user" -> {
                    providerMessages.add(buildJsonObject {
                        put("role", "user")
                        put("content", message.text)
                        // Handle attachments if needed
                    })
                }
                "assistant" -> {
                    providerMessages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", message.text)
                    })
                }
                "tool_call" -> {
                    // Convert tool call message to your provider's format
                }
                "tool_response" -> {
                    // Convert tool response to your provider's format
                }
            }
        }

        return providerMessages
    }

    private fun buildRequestBody(
        modelName: String,
        messages: List<JsonObject>,
        enabledTools: List<ToolSpecification>,
        webSearchEnabled: Boolean,
        temperature: Float?
    ): JsonObject {
        return buildJsonObject {
            put("model", modelName)
            put("messages", JsonArray(messages))
            put("stream", true)

            temperature?.let { put("temperature", it.toDouble()) }

            // Add tools if your provider supports them
            if (enabledTools.isNotEmpty()) {
                put("tools", convertToolsToProviderFormat(enabledTools))
            }
        }
    }

    private fun convertToolsToProviderFormat(tools: List<ToolSpecification>): JsonArray {
        // Convert tools to your provider's format
        return buildJsonArray {
            tools.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    // Add parameters in your provider's format
                })
            }
        }
    }

    // Internal result types
    private sealed class StreamingResult {
        data class TextResponse(val text: String) : StreamingResult()
        data class ToolCallResult(
            val toolCall: ToolCall,
            val precedingText: String = ""
        ) : StreamingResult()
        data class Error(val message: String) : StreamingResult()
    }
}
```

### Key Implementation Points:

1. **Extend BaseProvider**: Gives you access to common utilities like `createToolCallMessage()`, `createToolResponseMessage()`, and `json` parser.

2. **Override sendMessage()**: Main entry point - handles the tool calling loop.

3. **Implement makeStreamingRequest()**: Makes HTTP request and parses response.

4. **Adapt to Your Provider's Format**:
   - Message format (roles, content structure)
   - SSE event format
   - Tool call/response format
   - Error handling

---

## Step 3: Register Provider in LLMApiService

### File: `app/src/main/java/com/example/ApI/data/network/LLMApiService.kt`

Add your provider to the coordinator:

```kotlin
class LLMApiService(private val context: Context) {

    // Add lazy-initialized provider instance
    private val yourProvider by lazy { YourProvider(context) }

    suspend fun sendMessage(
        provider: Provider,
        ...
    ): Unit = withContext(Dispatchers.IO) {
        val apiKey = apiKeys[provider.provider]

        if (apiKey == null) {
            callback.onError("${provider.provider.replaceFirstChar { it.uppercase() }} API key is required")
            return@withContext
        }

        when (provider.provider) {
            "openai" -> openAIProvider.sendMessage(...)
            "google" -> googleProvider.sendMessage(...)
            "anthropic" -> anthropicProvider.sendMessage(...)
            "poe" -> poeProvider.sendMessage(...)
            "cohere" -> cohereProvider.sendMessage(...)
            "openrouter" -> openRouterProvider.sendMessage(...)
            "yourprovider" -> yourProvider.sendMessage(...)  // ← ADD THIS
            else -> {
                callback.onError("Unknown provider: ${provider.provider}")
            }
        }
    }
}
```

---

## Step 4: Add Provider to DataRepository

### File: `app/src/main/java/com/example/ApI/data/repository/DataRepository.kt`

### 4.1: Add to loadProviders()

```kotlin
fun loadProviders(): List<Provider> {
    return listOf(
        // ... existing providers ...

        Provider(
            provider = "yourprovider",
            models = getModelsForProvider("yourprovider", defaultYourProviderModels),
            request = ApiRequest(
                request_type = "POST",
                base_url = "https://api.yourprovider.com/v1/chat",
                headers = mapOf(
                    "Authorization" to "Bearer {API_KEY_HERE}",
                    "Content-Type" to "application/json"
                ),
                body = null
            ),
            response_important_fields = ResponseFields(
                id = "{message_id}",
                model = "{model_name}"
            ),
            upload_files_request = null,  // Add if your provider supports file uploads
            upload_files_response_important_fields = null
        )
    )
}
```

### 4.2: Add Default Models (Optional)

```kotlin
private val defaultYourProviderModels = listOf(
    Model.SimpleModel("model-v1"),
    Model.SimpleModel("model-v2-fast")
)
```

### 4.3: Add File Upload Support (If Needed)

If your provider requires separate file upload endpoints:

```kotlin
// In ensureFilesUploadedForProvider()
when (provider.provider.lowercase()) {
    "openai" -> attachment.file_OPENAI_id == null
    "google" -> attachment.file_GOOGLE_uri == null
    "yourprovider" -> attachment.file_YOURPROVIDER_id == null  // ADD
    else -> false
}

// In uploadFileForProvider()
"yourprovider" -> uploadFileToYourProvider(uploadRequest, filePath, fileName, mimeType, apiKey)
```

### 4.4: Add to Title Generation Models

```kotlin
// In getTitleGenerationModel()
val providerModels = mapOf(
    "openai" to "gpt-5-nano",
    "google" to "gemini-2.5-flash-lite",
    "anthropic" to "claude-3-haiku-20240307",
    "yourprovider" to "model-v2-fast"  // ADD - use your cheapest/fastest model
)
```

---

## Step 5: Update UI Components

### 5.1: API Keys Screen - Add Brand Colors

**File**: `app/src/main/java/com/example/ApI/ui/screen/ApiKeysScreen.kt`

```kotlin
val cardGradient = when (apiKey.provider.lowercase()) {
    // ... existing providers ...
    "yourprovider" -> listOf(
        Color(0xFF_YOUR_COLOR).copy(alpha = 0.25f),
        Color(0xFF_YOUR_COLOR).copy(alpha = 0.15f)
    )
    else -> listOf(SurfaceVariant, SurfaceVariant)
}
```

### 5.2: ChatViewModel - Title Generation List

**File**: `app/src/main/java/com/example/ApI/ui/ChatViewModel.kt`

⚠️ **CRITICAL**: Add to `getAvailableProvidersForTitleGeneration()`:

```kotlin
return listOf("openai", "anthropic", "google", "poe", "cohere", "yourprovider").filter { provider ->
    apiKeys.contains(provider)
}
```

### 5.3: Username Screen - Auto-Naming Option

**File**: `app/src/main/java/com/example/ApI/ui/screen/UsernameScreen.kt`

```kotlin
// In provider selector dropdown:
if ("yourprovider" in availableProviders) {
    ProviderOption(
        text = stringResource(R.string.yourprovider_model),
        isSelected = settings.provider == "yourprovider",
        onClick = {
            onSettingsChange(settings.copy(provider = "yourprovider"))
            showProviderSelector = false
        }
    )
}

// In getProviderDisplayName():
"yourprovider" -> stringResource(R.string.yourprovider_model)
```

### 5.4: ChatScreen - Provider Display Name

**File**: `app/src/main/java/com/example/ApI/ui/screen/ChatScreen.kt`

⚠️ **CRITICAL**: Add to provider name mapping:

```kotlin
stringResource(id = when(provider) {
    // ... existing providers ...
    "yourprovider" -> R.string.provider_yourprovider
    else -> R.string.provider_openai
})
```

### 5.5: Dialogs.kt - Selection Menus

**File**: `app/src/main/java/com/example/ApI/ui/components/Dialogs.kt`

⚠️ **CRITICAL**: Add to BOTH `when` statements (ProviderSelectorDialog and AddApiKeyDialog):

```kotlin
"yourprovider" -> R.string.provider_yourprovider
```

### 5.6: Add String Resources

**File**: `app/src/main/res/values/strings.xml`

```xml
<string name="provider_yourprovider">YourProvider</string>
<string name="yourprovider_model">model-v2-fast via YourProvider</string>
```

---

## Step 6: Optional Features

### 6.1: Thinking/Reasoning Support

If your provider supports thinking/reasoning (like OpenAI o1, Anthropic Claude thinking, Google Gemini thinking):

```kotlin
// In your streaming parser:
if (isThinkingBlock) {
    callback.onThinkingStarted()
    callback.onThinkingPartial(thinkingText)
    callback.onThinkingComplete(thoughts, durationSeconds, status)
}
```

### 6.2: Web Search Support

If your provider has native web search:

```kotlin
// In buildRequestBody():
if (webSearchEnabled) {
    put("tools", buildJsonArray {
        add(buildJsonObject {
            put("type", "web_search")
            // Provider-specific format
        })
    })
}
```

### 6.3: File Attachments

Different providers handle files differently:
- **Inline Base64** (Anthropic, Cohere): Encode files in message content
- **Upload Endpoint** (OpenAI, Google): Upload first, reference by ID
- **URL Reference** (Poe): Upload to their CDN, reference by URL

---

## Testing

### Checklist:

1. **Basic Messaging**
   - [ ] Send a simple text message
   - [ ] Receive streaming response
   - [ ] Response displays correctly in UI

2. **File Attachments** (if supported)
   - [ ] Upload image attachment
   - [ ] Upload PDF/document
   - [ ] Files are properly encoded/uploaded

3. **Tool Calling** (if supported)
   - [ ] Enable a tool
   - [ ] Trigger tool use in conversation
   - [ ] Verify tool result is returned to model
   - [ ] Verify model responds to tool result
   - [ ] Test tool chaining (multiple sequential tool calls)

4. **Thinking/Reasoning** (if supported)
   - [ ] Enable thinking budget
   - [ ] Verify thinking indicator shows
   - [ ] Verify thoughts are displayed

5. **Multi-turn Conversations**
   - [ ] Have a 5+ message conversation
   - [ ] Edit and resend a message
   - [ ] Verify conversation context is maintained

6. **Error Handling**
   - [ ] Test with invalid API key
   - [ ] Test with network timeout
   - [ ] Verify error messages display correctly

7. **UI Components**
   - [ ] API key card has correct brand colors
   - [ ] Provider appears in auto-naming settings
   - [ ] Provider name displays correctly throughout app

### Build and Run:

```bash
./gradlew clean
./gradlew assembleDebug
./gradlew installDebug
./gradlew testDebugUnitTest
```

---

## Common Pitfalls

1. **SSE Parsing**: Different providers use different formats. Some use `event:` + `data:`, others use only `data:`.

2. **Role Mapping**: APIs use different role names (`user/assistant` vs `user/model` vs `user/bot`).

3. **File Encoding**: Some providers need inline base64, others need separate upload endpoints.

4. **Tool Format**: Tool specifications vary widely between providers.

5. **Empty Tool Input**: Handle tools with no parameters (`{}`).

6. **Streaming Chunks**: Providers send data differently (character, word, or sentence level).

7. **Missing UI Updates**: Search for `else -> R.string.provider_openai` to find all `when` statements that need updating.

---

## File Structure Reference

```
app/src/main/java/com/example/ApI/
├── data/
│   ├── network/
│   │   ├── LLMApiService.kt       ← Thin coordinator
│   │   └── providers/
│   │       ├── BaseProvider.kt    ← Common utilities
│   │       ├── ProviderResult.kt  ← Shared types
│   │       ├── OpenAIProvider.kt
│   │       ├── GoogleProvider.kt
│   │       ├── AnthropicProvider.kt
│   │       ├── PoeProvider.kt
│   │       ├── CohereProvider.kt
│   │       ├── OpenRouterProvider.kt
│   │       └── YourProvider.kt    ← NEW FILE
│   └── repository/
│       └── DataRepository.kt      ← Provider registration & file uploads
├── ui/
│   ├── ChatViewModel.kt
│   ├── screen/
│   │   ├── ChatScreen.kt
│   │   ├── ApiKeysScreen.kt
│   │   └── UsernameScreen.kt
│   └── components/
│       └── Dialogs.kt
```

---

## Summary

Adding a new provider requires:

1. ✅ Define API contract in `providers.json`
2. ✅ **Create provider implementation in `providers/YourProvider.kt`** (NEW)
3. ✅ Register provider in `LLMApiService.kt`
4. ✅ Add provider registration in `DataRepository.kt`
5. ✅ Add UI branding in `ApiKeysScreen.kt`
6. ✅ Add to title generation list in `ChatViewModel.kt` ⚠️
7. ✅ Add to settings UI in `UsernameScreen.kt`
8. ✅ Add provider display name to `ChatScreen.kt` ⚠️
9. ✅ Add provider display name to `Dialogs.kt` ⚠️
10. ✅ Add string resources in `strings.xml`
11. ✅ Test thoroughly

The modular architecture handles the rest (chat history, tool calling, UI rendering, etc.) automatically once you implement these components correctly.
