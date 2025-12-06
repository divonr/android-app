# Complete Guide: Adding a New Provider to the Android LLM Chat App

This guide demonstrates how to add a new LLM provider to the app, based on the recent Anthropic integration (commits 21c59bd and 52a8488).

## Table of Contents
1. [Overview](#overview)
2. [Step 1: Add Provider Configuration](#step-1-add-provider-configuration)
3. [Step 2: Implement Streaming in ApiService](#step-2-implement-streaming-in-apiservice)
4. [Step 3: Add Provider to DataRepository](#step-3-add-provider-to-datarepository)
5. [Step 4: Update UI Components](#step-4-update-ui-components)
6. [Step 5: Optional Features](#step-5-optional-features)
7. [Testing](#testing)

---

## Overview

Adding a new provider requires changes across 9 main files:
- `app/src/main/assets/providers.json` - Provider API configuration
- `app/src/main/java/com/example/ApI/data/network/ApiService.kt` - Network/streaming logic
- `app/src/main/java/com/example/ApI/data/repository/DataRepository.kt` - Provider registration
- `app/src/main/java/com/example/ApI/ui/screen/ApiKeysScreen.kt` - API key UI styling
- `app/src/main/java/com/example/ApI/ui/ChatViewModel.kt` - Title generation provider list
- `app/src/main/java/com/example/ApI/ui/screen/UsernameScreen.kt` - Auto-naming settings UI
- `app/src/main/java/com/example/ApI/ui/screen/ChatScreen.kt` - Provider display name in chat
- `app/src/main/java/com/example/ApI/ui/components/Dialogs.kt` - Provider display name in selection menus
- `app/src/main/res/values/strings.xml` - String resources

---

## Step 1: Add Provider Configuration

### File: `app/src/main/assets/providers.json`

Add your provider's configuration to the JSON array. This defines the API contract.

```json
{
    "provider": "anthropic",
    "models": [
        "claude-sonnet-4-5-20250929",
        "claude-sonnet-4-20250514",
        "claude-haiku-4-5-20251001",
        "claude-opus-4-5-20251101",
        "claude-3-5-sonnet-20241022"
    ],
    "request": {
        "request_type": "POST",
        "base_url": "https://api.anthropic.com/v1/messages",
        "headers": {
            "x-api-key": "{ANTHROPIC_API_KEY_HERE}",
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json"
        },
        "body": {
            "model": "{model_name}",
            "max_tokens": 8192,
            "system": "{system_prompt}",
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": "{user_first_prompt}"
                        },
                        {
                            "type": "image",
                            "source": {
                                "type": "base64",
                                "media_type": "{mime_type}",
                                "data": "{base64_encoded_image}"
                            }
                        }
                    ]
                },
                {
                    "role": "assistant",
                    "content": [
                        {
                            "type": "text",
                            "text": "{model_first_response}"
                        }
                    ]
                }
            ]
        }
    },
    "response_important_fields": {
        "response_format": "server_sent_events",
        "events": [
            {
                "event": "message_start",
                "data": {
                    "type": "message_start",
                    "message": {
                        "id": "{message_id}",
                        "model": "{model_name}"
                    }
                }
            },
            {
                "event": "content_block_delta",
                "data": {
                    "type": "content_block_delta",
                    "index": 0,
                    "delta": {
                        "type": "text_delta",
                        "text": "{partial_text_chunk}"
                    }
                }
            },
            {
                "event": "message_stop",
                "data": {
                    "type": "message_stop"
                }
            }
        ]
    }
}
```

### Key Configuration Elements:

**Provider Name**: Lowercase identifier (e.g., `"anthropic"`)

**Models**: List of model names available from this provider

**Request Structure**:
- `base_url`: API endpoint
- `headers`: Authentication and content type
- `body`: Template showing message format with placeholders

**Response Format**: Document the SSE event structure or JSON response schema

**File Uploads** (Optional): If your provider supports file uploads, add `upload_files_request` and `upload_files_response_important_fields` sections (see OpenAI/Google examples).

---

## Step 2: Implement Streaming in ApiService

### File: `app/src/main/java/com/example/ApI/data/network/ApiService.kt`

You need to implement **three key methods**:

### 2.1: Add Provider Router

In the main `sendMessageStreaming()` method, add your provider case:

```kotlin
suspend fun sendMessageStreaming(
    provider: Provider,
    modelName: String,
    messages: List<Message>,
    systemPrompt: String,
    apiKeys: Map<String, String>,
    webSearchEnabled: Boolean = false,
    enabledTools: List<ToolSpecification> = emptyList(),
    callback: StreamingCallback
) {
    when (provider.provider.lowercase()) {
        "openai" -> sendOpenAIMessage(...)
        "poe" -> sendPoeMessage(...)
        "google" -> sendGoogleMessage(...)
        "anthropic" -> sendAnthropicMessage(...)  // ← ADD THIS
        else -> {
            callback.onError("Unknown provider: ${provider.provider}")
        }
    }
}
```

### 2.2: Implement Main Provider Method

Create a method that handles the overall message flow, including tool calling loops:

```kotlin
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

                    // Execute the tool
                    val toolResult = callback.onToolCall(toolCall, precedingText)

                    // Create tool call and response messages
                    val toolCallMessage = Message(
                        role = "tool_call",
                        text = precedingText,
                        model = modelName,
                        datetime = java.time.Instant.now().toString(),
                        toolCall = com.example.ApI.tools.ToolCallInfo(
                            toolId = toolCall.toolId,
                            toolName = toolCall.toolId,
                            parameters = toolCall.parameters,
                            result = toolResult,
                            timestamp = java.time.Instant.now().toString()
                        ),
                        toolCallId = toolCall.id
                    )

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

                    // Save to history and continue conversation
                    callback.onSaveToolMessages(toolCallMessage, toolResponseMessage)
                    conversationMessages.add(toolCallMessage)
                    conversationMessages.add(toolResponseMessage)

                    fullResponseText = ""
                }

                is AnthropicStreamingResult.Error -> {
                    callback.onError(result.message)
                    return
                }
            }
        }

        callback.onError("Maximum tool calling iterations ($maxToolIterations) reached")
    } catch (e: Exception) {
        callback.onError("Failed to send Anthropic message: ${e.message}")
    }
}
```

### 2.3: Implement Streaming Request Parser

Create a method that makes the HTTP request and parses Server-Sent Events:

```kotlin
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

        // Set up request headers
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

            if (systemPrompt.isNotBlank()) {
                put("system", systemPrompt)
            }

            put("stream", true)

            // Add tools if enabled
            if (enabledTools.isNotEmpty() || webSearchEnabled) {
                put("tools", buildJsonArray {
                    // Add web search if enabled
                    if (webSearchEnabled) {
                        add(buildJsonObject {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                        })
                    }
                    // Add custom tools
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
                        "content_block_delta" -> {
                            val delta = chunkJson["delta"]?.jsonObject
                            val deltaType = delta?.get("type")?.jsonPrimitive?.content

                            when (deltaType) {
                                "text_delta" -> {
                                    val text = delta?.get("text")?.jsonPrimitive?.content ?: ""
                                    fullResponse.append(text)
                                    callback.onPartialResponse(text)
                                }
                                "input_json_delta" -> {
                                    val partialJson = delta?.get("partial_json")?.jsonPrimitive?.content ?: ""
                                    currentToolInput.append(partialJson)
                                }
                            }
                        }

                        "content_block_start" -> {
                            val contentBlock = chunkJson["content_block"]?.jsonObject
                            val blockType = contentBlock?.get("type")?.jsonPrimitive?.content

                            if (blockType == "tool_use") {
                                currentToolUseId = contentBlock?.get("id")?.jsonPrimitive?.content
                                currentToolName = contentBlock?.get("name")?.jsonPrimitive?.content
                                currentToolInput.clear()

                                val initialInput = contentBlock?.get("input")?.jsonObject
                                if (initialInput != null) {
                                    currentToolInput.append(initialInput.toString())
                                }

                                isAccumulatingToolInput = true
                            }
                        }

                        "content_block_stop" -> {
                            if (isAccumulatingToolInput && currentToolUseId != null && currentToolName != null) {
                                val inputString = currentToolInput.toString().ifEmpty { "{}" }
                                val toolInputJson = json.parseToJsonElement(inputString).jsonObject

                                detectedToolCall = com.example.ApI.tools.ToolCall(
                                    id = currentToolUseId!!,
                                    toolId = currentToolName!!,
                                    parameters = toolInputJson,
                                    provider = "anthropic"
                                )

                                isAccumulatingToolInput = false
                            }
                        }

                        "message_stop" -> {
                            break
                        }

                        "error" -> {
                            val error = chunkJson["error"]?.jsonObject
                            val errorMessage = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                            callback.onError("Anthropic API error: $errorMessage")
                            return@withContext AnthropicStreamingResult.Error(errorMessage)
                        }
                    }
                } catch (jsonException: Exception) {
                    println("[DEBUG] Error parsing SSE chunk: ${jsonException.message}")
                    continue
                }
            }
        }

        reader.close()
        connection.disconnect()

        // Return result
        return@withContext when {
            detectedToolCall != null -> AnthropicStreamingResult.ToolCall(
                toolCall = detectedToolCall!!,
                precedingText = fullResponse.toString()
            )
            fullResponse.isNotEmpty() -> AnthropicStreamingResult.TextResponse(fullResponse.toString())
            else -> AnthropicStreamingResult.Error("Empty response from Anthropic")
        }
    } catch (e: Exception) {
        callback.onError("Failed to make streaming request: ${e.message}")
        AnthropicStreamingResult.Error("Failed to make streaming request: ${e.message}")
    }
}
```

### 2.4: Add Helper Methods

Create message builder and tool format converters:

```kotlin
/**
 * Build Anthropic Messages API format from Message objects
 */
private suspend fun buildAnthropicMessages(messages: List<Message>): List<JsonObject> = withContext(Dispatchers.IO) {
    val anthropicMessages = mutableListOf<JsonObject>()

    messages.forEach { message ->
        when (message.role) {
            "user" -> {
                val contentArray = buildJsonArray {
                    if (message.text.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        })
                    }

                    // Add file attachments as base64
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
                                    }
                                }
                            } catch (e: Exception) {
                                println("[DEBUG] Error encoding file: ${e.message}")
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
                val contentArray = buildJsonArray {
                    if (message.text.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        })
                    }

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
                    tool.required?.let { req ->
                        put("required", JsonArray(req.map { JsonPrimitive(it) }))
                    }
                })
            })
        }
    }
}
```

### 2.5: Define Result Sealed Class

Add a sealed class to represent different streaming outcomes:

```kotlin
/**
 * Sealed class representing the result of an Anthropic streaming request
 */
private sealed class AnthropicStreamingResult {
    data class TextResponse(val text: String) : AnthropicStreamingResult()
    data class ToolCall(val toolCall: com.example.ApI.tools.ToolCall, val precedingText: String) : AnthropicStreamingResult()
    data class Error(val message: String) : AnthropicStreamingResult()
}
```

---

## Step 3: Add Provider to DataRepository

### File: `app/src/main/java/com/example/ApI/data/repository/DataRepository.kt`

Add your provider to the `loadProviders()` method:

```kotlin
fun loadProviders(): List<Provider> {
    return listOf(
        // ... existing providers (openai, poe, google) ...

        Provider(
            provider = "anthropic",
            models = listOf(
                Model.SimpleModel("claude-sonnet-4-5-20250929"),
                Model.SimpleModel("claude-sonnet-4-20250514"),
                Model.SimpleModel("claude-haiku-4-5-20251001"),
                Model.SimpleModel("claude-opus-4-5-20251101"),
                Model.SimpleModel("claude-3-5-sonnet-20241022"),
                Model.SimpleModel("claude-3-5-haiku-20241022")
            ),
            request = ApiRequest(
                request_type = "POST",
                base_url = "https://api.anthropic.com/v1/messages",
                headers = mapOf(
                    "x-api-key" to "{ANTHROPIC_API_KEY_HERE}",
                    "anthropic-version" to "2023-06-01",
                    "Content-Type" to "application/json"
                ),
                body = null
            ),
            response_important_fields = ResponseFields(
                id = "{message_id}",
                model = "{model_name}"
            ),
            upload_files_request = null,  // Anthropic uses inline base64, no separate upload
            upload_files_response_important_fields = null
        )
    )
}
```

**Note**: If your provider requires file upload endpoints (like OpenAI/Google), populate `upload_files_request` and `upload_files_response_important_fields`.

---

## Step 4: Update UI Components

### 4.1: API Keys Screen - Add Brand Colors

**File**: `app/src/main/java/com/example/ApI/ui/screen/ApiKeysScreen.kt`

Find the `ApiKeyItem` composable and add your provider's brand gradient:

```kotlin
@Composable
private fun ApiKeyItem(
    apiKey: ApiKey,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardGradient = when (apiKey.provider.lowercase()) {
        "openai" -> listOf(
            Color(0xFF10A37F).copy(alpha = 0.25f),
            Color(0xFF1A7F64).copy(alpha = 0.25f)
        )
        "poe" -> listOf(
            Color(0xFFFF6B6B).copy(alpha = 0.25f),
            Color(0xFFEE5A6F).copy(alpha = 0.25f)
        )
        "google" -> listOf(
            Color(0xFF4285F4).copy(alpha = 0.25f),
            Color(0xFFEA4335).copy(alpha = 0.25f),
            Color(0xFFFBBC05).copy(alpha = 0.25f),
            Color(0xFF34A853).copy(alpha = 0.25f)
        )
        "anthropic" -> listOf(                           // ← ADD THIS
            Color(0xFFC6613F).copy(alpha = 0.25f),
            Color(0xFFC6613F).copy(alpha = 0.15f)
        )
        else -> listOf(SurfaceVariant, SurfaceVariant)
    }

    // ... rest of composable
}
```

### 4.2: ChatViewModel - Add Provider to Title Generation List

**File**: `app/src/main/java/com/example/ApI/ui/ChatViewModel.kt`

**CRITICAL**: Find the `getAvailableProvidersForTitleGeneration()` function and add your provider to the list:

```kotlin
fun getAvailableProvidersForTitleGeneration(): List<String> {
    val currentUser = _appSettings.value.current_user
    val apiKeys = repository.loadApiKeys(currentUser)
        .filter { it.isActive }
        .map { it.provider }

    return listOf("openai", "anthropic", "google", "poe", "cohere", "yourprovider").filter { provider ->
        apiKeys.contains(provider)
    }
}
```

**⚠️ WARNING**: If you skip this step, your provider will NOT appear in the title generation dropdown in Advanced Settings, even if you add it to `UsernameScreen.kt`!

### 4.3: Username Screen - Add Auto-Naming Option

**File**: `app/src/main/java/com/example/ApI/ui/screen/UsernameScreen.kt`

#### Update Provider Selector:

```kotlin
@Composable
private fun TitleGenerationSettingsSection(...) {
    // ... existing code ...

    // Inside the provider selector dropdown:
    if ("anthropic" in availableProviders) {
        ProviderOption(
            text = stringResource(R.string.anthropic_claude_haiku),
            isSelected = settings.provider == "anthropic",
            onClick = {
                onSettingsChange(settings.copy(provider = "anthropic"))
                showProviderSelector = false
            }
        )
    }
}
```

#### Update Display Name Function:

```kotlin
@Composable
private fun getProviderDisplayName(provider: String): String {
    return when (provider) {
        "openai" -> stringResource(R.string.openai_gpt5_nano)
        "poe" -> stringResource(R.string.poe_gpt5_nano)
        "google" -> stringResource(R.string.google_gemini_flash_lite)
        "anthropic" -> stringResource(R.string.anthropic_claude_haiku)  // ← ADD THIS
        else -> stringResource(R.string.auto_mode)
    }
}
```

### 4.4: Add String Resources

**File**: `app/src/main/res/values/strings.xml`

```xml
<resources>
    <!-- ... existing strings ... -->

    <!-- Provider names -->
    <string name="provider_anthropic">Anthropic</string>

    <!-- Auto-naming model options -->
    <string name="anthropic_claude_haiku">claude-3-5-haiku via Anthropic</string>
</resources>
```

### 4.5: Add Provider Display Name to ChatScreen

**File**: `app/src/main/java/com/example/ApI/ui/screen/ChatScreen.kt`

**CRITICAL**: Find the provider display name mapping (around line 384-390) and add your provider:

```kotlin
Text(
    text = uiState.currentProvider?.provider?.let { provider ->
        stringResource(id = when(provider) {
            "openai" -> R.string.provider_openai
            "poe" -> R.string.provider_poe
            "google" -> R.string.provider_google
            "anthropic" -> R.string.provider_anthropic
            "cohere" -> R.string.provider_cohere          // ← ADD THIS
            else -> R.string.provider_openai
        })
    } ?: "",
    // ... rest of Text properties
)
```

**⚠️ WARNING**: If you skip this step, your provider will display as "OpenAI" in the chat interface due to the fallback in the `else` clause!

### 4.6: Add Provider Display Name to Dialogs.kt

**File**: `app/src/main/java/com/example/ApI/ui/components/Dialogs.kt`

**CRITICAL**: There are two `when` statements in this file that map provider names to display strings. You must update both:

#### ProviderSelectorDialog (around line 286):

```kotlin
Text(
    text = stringResource(id = when(provider.provider) {
        "openai" -> R.string.provider_openai
        "poe" -> R.string.provider_poe
        "google" -> R.string.provider_google
        "anthropic" -> R.string.provider_anthropic
        "cohere" -> R.string.provider_cohere          // ← ADD THIS
        else -> R.string.provider_openai
    }),
    // ... rest of Text properties
)
```

#### AddApiKeyDialog (around line 494):

```kotlin
DropdownMenuItem(
    text = {
        Text(
            text = stringResource(id = when(provider.provider) {
                "openai" -> R.string.provider_openai
                "poe" -> R.string.provider_poe
                "google" -> R.string.provider_google
                "anthropic" -> R.string.provider_anthropic
                "cohere" -> R.string.provider_cohere  // ← ADD THIS
                else -> R.string.provider_openai
            }),
            color = OnSurface
        )
    },
    // ...
)
```

**⚠️ WARNING**: If you skip this step, your provider will display as "OpenAI" in:
- The provider selection dialog (when choosing which provider to use)
- The API key add dialog dropdown (when adding a new API key)

The correct name will only appear **after** selection, causing confusing UX!

---

## Step 5: Optional Features

### 5.1: Web Search Support

If your provider supports web search (like Anthropic's `web_search_20250305` tool), add it to the tools array:

```kotlin
// In makeAnthropicStreamingRequest():
if (enabledTools.isNotEmpty() || webSearchEnabled) {
    put("tools", buildJsonArray {
        // Web search tool
        if (webSearchEnabled) {
            add(buildJsonObject {
                put("type", "web_search_20250305")
                put("name", "web_search")
            })
        }
        // Custom tools
        if (enabledTools.isNotEmpty()) {
            convertToolsToAnthropicFormat(enabledTools).forEach { add(it) }
        }
    })
}
```

### 5.2: File Upload Endpoints

If your provider uses separate file upload endpoints (like OpenAI/Google):

1. **Add upload config** to `providers.json`:
```json
"upload_files_request": {
    "request_type": "POST",
    "base_url": "https://api.example.com/v1/files",
    "headers": {
        "Authorization": "Bearer {API_KEY_HERE}"
    }
}
```

2. **Implement upload method** in `ApiService.kt`:
```kotlin
suspend fun uploadFileToProvider(
    filePath: String,
    mimeType: String,
    apiKey: String
): String {
    // Implementation similar to uploadFileToOpenAI() or uploadFileToGoogle()
}
```

3. **Call from DataRepository** in `ensureFilesUploadedForProvider()`:
```kotlin
when (provider.provider.lowercase()) {
    "openai" -> uploadFileToOpenAI(...)
    "google" -> uploadFileToGoogle(...)
    "yourprovider" -> uploadFileToYourProvider(...)
}
```

### 5.3: Tool Calling

Tool calling is automatically supported if you:
1. Parse `tool_use` events in your streaming parser
2. Return `ToolCall` result type from your streaming method
3. Handle tool messages in `buildProviderMessages()`

The main provider method's while loop will handle the tool execution cycle.

---

## Testing

### Checklist:

1. **Basic Messaging**
   - [ ] Send a simple text message
   - [ ] Receive streaming response
   - [ ] Response displays correctly in UI

2. **File Attachments**
   - [ ] Upload image attachment
   - [ ] Upload PDF/document
   - [ ] Files are properly encoded/uploaded

3. **Tool Calling** (if supported)
   - [ ] Enable a tool (e.g., calculator)
   - [ ] Trigger tool use in conversation
   - [ ] Verify tool result is returned to model
   - [ ] Verify model responds to tool result

4. **Web Search** (if supported)
   - [ ] Enable web search toggle
   - [ ] Ask a question requiring current information
   - [ ] Verify search is performed
   - [ ] Verify results appear in response

5. **Multi-turn Conversations**
   - [ ] Have a 5+ message conversation
   - [ ] Edit and resend a message
   - [ ] Verify conversation context is maintained

6. **Error Handling**
   - [ ] Test with invalid API key
   - [ ] Test with network timeout
   - [ ] Test with malformed response
   - [ ] Verify error messages display correctly

7. **UI Components**
   - [ ] API key card has correct brand colors
   - [ ] Provider appears in auto-naming settings
   - [ ] Provider name displays correctly throughout app

### Build and Run:

```bash
# Clean build
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Install on device
./gradlew installDebug

# Run tests
./gradlew testDebugUnitTest
```

---

## Common Pitfalls

1. **SSE Parsing**: Make sure you correctly handle the event format. Some APIs use `event:` and `data:` lines, others use only `data:`.

2. **Role Mapping**: Different APIs use different role names (`user/assistant` vs `user/model` vs `user/bot`). Map them correctly.

3. **File Encoding**: Some providers (Anthropic) require inline base64, others (OpenAI/Google) use separate upload endpoints.

4. **Tool Format**: Tool specifications vary widely. Check your provider's docs for exact JSON schema.

5. **Empty Tool Input**: Some tools have no parameters. Handle empty `{}` input gracefully.

6. **Streaming Chunks**: Some providers send character-by-character, others send word-by-word or sentence-by-sentence.

7. **Error Events**: Always check for error events in the SSE stream and handle them appropriately.

---

## Reference Files

For complete implementation examples, see these commits:
- **21c59bd**: "adding anthropic as a provider!" - Main integration
- **52a8488**: "web search for anthropic models" - Web search feature
- **f7aac57**: "web search anthropic models 2" - Web search refinements
- **ba0c1c3**: "UI display for enabling web search for Anthropic" - UI updates

---

## Architecture Overview

```
User Input (ChatScreen)
    ↓
ChatViewModel.sendMessage()
    ↓
DataRepository.sendMessageStreaming()
    ↓
ApiService.sendMessageStreaming()
    ↓  (routes by provider)
    ↓
ApiService.sendAnthropicMessage()  ← Your implementation
    ↓  (tool calling loop)
    ↓
ApiService.makeAnthropicStreamingRequest()
    ↓  (HTTP + SSE parsing)
    ↓
StreamingCallback.onPartialResponse()
    ↓
ChatViewModel updates UI state
    ↓
UI renders streaming text (ChatScreen)
```

---

## Summary

Adding a new provider requires:

1. ✅ Define API contract in `providers.json`
2. ✅ Implement streaming parser in `ApiService.kt`
3. ✅ Register provider in `DataRepository.kt`
4. ✅ Add UI branding in `ApiKeysScreen.kt`
5. ✅ **Add provider to title generation list in `ChatViewModel.kt`** ⚠️ (Critical - provider won't appear in title generation dropdown if skipped!)
6. ✅ Add to settings UI in `UsernameScreen.kt`
7. ✅ Add string resources in `strings.xml`
8. ✅ **Add provider display name to `ChatScreen.kt`** ⚠️ (Critical - provider will show as "OpenAI" if skipped!)
9. ✅ **Add provider display name to `Dialogs.kt`** ⚠️ (Critical - provider will show as "OpenAI" in selection menus if skipped!)
10. ✅ Test thoroughly

The app's architecture handles the rest (chat history, tool calling, UI rendering, etc.) automatically once you implement these components correctly.
