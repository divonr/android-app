# Complete Guide: Adding a New Provider to the Android LLM Chat App

This guide covers all the critical decisions and implementation details you'll face when adding any new LLM provider.

## Table of Contents
1. [Before You Start](#before-you-start)
2. [Files to Modify](#files-to-modify)
3. [Provider Implementation](#provider-implementation)
4. [Critical Decision Points](#critical-decision-points)
5. [Common Pitfalls](#common-pitfalls)
6. [Testing Checklist](#testing-checklist)

---

## Before You Start

### Understand Your Provider's API

Before writing any code, document these details about your provider:

1. **API Format**
   - Base URL and endpoint structure
   - Authentication method (Bearer token, API key header, custom)
   - Request body format

2. **Streaming Format**
   - Does it use Server-Sent Events (SSE)?
   - What's the event/data format? (`data: {...}` or custom)
   - How does streaming end? (`[DONE]` marker, stream close, or custom)

3. **Message Format**
   - Role names: `user/assistant` vs `user/model` vs `human/assistant`
   - Content structure: simple string or complex object with parts
   - How are system prompts handled? Separate field or first message?

4. **Tool/Function Calling** (if supported)
   - Request format for tool definitions
   - Response format for tool calls
   - How are tool results sent back?

5. **File Handling** (if supported)
   - Inline Base64 in messages?
   - Separate upload endpoint with file IDs?
   - URL references?

6. **Special Features**
   - Thinking/reasoning support?
   - Web search?
   - Special parameters?

---

## Files to Modify

### Required Files

| File | Purpose |
|------|---------|
| `data/network/providers/YourProvider.kt` | **NEW** - Provider implementation |
| `data/network/LLMApiService.kt` | Register provider routing |
| `data/repository/ModelsCacheManager.kt` | Provider config + default models |
| `res/values/strings.xml` | Display name strings |
| `ui/components/ChatTopBar.kt` | Provider name in header |
| `ui/screen/ApiKeysScreen.kt` | Brand colors for API key cards |
| `ui/components/dialogs/ApiKeyDialogs.kt` | Provider in dropdown |
| `ui/components/dialogs/ModelSelectionDialogs.kt` | Provider in model tabs |

### Optional Files (based on features)

| File | When Needed |
|------|-------------|
| `data/repository/FileUploadManager.kt` | Separate file upload endpoint |
| `data/model/ChatHistory.kt` | New field in Attachment for file IDs |
| `ui/managers/provider/ModelSelectionManager.kt` | Web search support |
| `ui/screen/UsernameScreen.kt` | Title generation option |

---

## Provider Implementation

### Architecture Overview

```
ChatViewModel.sendMessage()
    ↓
DataRepository.sendMessageStreaming()
    ↓
LLMApiService.sendMessage()   ← Routes by provider name
    ↓
YourProvider.sendMessage()    ← Your implementation
    ↓
StreamingCallback             ← Updates UI in real-time
```

### Step 1: Create Provider Class

Create `app/src/main/java/com/example/ApI/data/network/providers/YourProvider.kt`

```kotlin
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
        // Your implementation
    }
}
```

**BaseProvider utilities available:**
- `json` - Kotlinx JSON parser
- `readFileAsBase64(path)` - Read file as Base64 string
- `createToolCallMessage()` / `createToolResponseMessage()` - Create tool messages
- `handleToolCallChain()` - Handle multi-step tool calling loop
- `completeThinkingPhase()` - Notify thinking completion
- `MAX_TOOL_DEPTH` - Maximum tool call iterations (25)

### Step 2: Register in LLMApiService

```kotlin
// Add lazy instance
private val yourProvider by lazy { YourProvider(context) }

// Add to when statement
"yourprovider" -> yourProvider.sendMessage(
    provider, modelName, messages, systemPrompt, apiKey,
    webSearchEnabled, enabledTools, thinkingBudget, temperature, callback
)
```

### Step 3: Add to ModelsCacheManager

```kotlin
// Default models
val defaultYourProviderModels = listOf(
    Model.SimpleModel("model-name")
)

// In buildProviders()
Provider(
    provider = "yourprovider",
    models = getModelsForProvider("yourprovider", defaultYourProviderModels),
    request = ApiRequest(
        request_type = "POST",
        base_url = "https://api.yourprovider.com/v1/...",
        headers = mapOf(...),
        body = null
    ),
    response_important_fields = ResponseFields(response_format = "server_sent_events"),
    upload_files_request = null,
    upload_files_response_important_fields = null
)
```

### Step 4: Add UI Integration

**strings.xml:**
```xml
<string name="provider_yourprovider">YourProvider</string>
```

**All UI files with provider `when` statements:**
```kotlin
"yourprovider" -> R.string.provider_yourprovider
```

**ApiKeysScreen.kt (brand colors):**
```kotlin
"yourprovider" -> listOf(
    Color(0xFFYOURCOLOR).copy(alpha = 0.25f),
    Color(0xFFYOURCOLOR).copy(alpha = 0.15f)
)
```

---

## Critical Decision Points

### 1. Message Format Conversion

Different providers use different formats. You must convert the app's internal `Message` format:

| App Role | OpenAI | Anthropic | Google | Cohere |
|----------|--------|-----------|--------|--------|
| `user` | `user` | `user` | `user` | `USER` |
| `assistant` | `assistant` | `assistant` | `model` | `CHATBOT` |
| `system` | `system` | `system` (in messages) | `system_instruction` | `SYSTEM` |
| `tool_call` | `assistant` + `tool_calls` | `assistant` + `tool_use` | `model` + `functionCall` | `CHATBOT` + `tool_calls` |
| `tool_response` | `tool` | `user` + `tool_result` | `user` + `functionResponse` | `TOOL` |

**Key questions:**
- Does system prompt go in messages or separate field?
- How are multi-part messages (text + images) structured?
- How are tool calls/responses formatted?

### 2. Streaming Response Parsing

#### SSE Format Variations

**Standard SSE (OpenAI, most providers):**
```
data: {"choices":[{"delta":{"content":"Hello"}}]}
data: {"choices":[{"delta":{"content":" world"}}]}
data: [DONE]
```

**Google Gemini:**
```
data: {"candidates":[{"content":{"parts":[{"text":"Hello"}]}}]}
data: {"candidates":[{"content":{"parts":[{"text":" world"}]}}],"finishReason":"STOP"}
```

**Anthropic:**
```
event: content_block_delta
data: {"type":"content_block_delta","delta":{"text":"Hello"}}
```

**Key questions:**
- Does it use `data:` prefix?
- Are there `event:` lines to parse?
- How do you know streaming ended?
- What field contains the text content?

#### Handle Stream End

```kotlin
// Different providers end streams differently:

// Method 1: [DONE] marker
if (currentLine == "data: [DONE]") break

// Method 2: finishReason in chunk
if (finishReason == "STOP" || finishReason == "stop") break

// Method 3: Stream just closes
// readLine() returns null - loop exits naturally

// Method 4: Empty choices array
if (choices.isEmpty()) break
```

### 3. Tool Calling Implementation

#### Request Format

**OpenAI-style:**
```json
{
  "tools": [{
    "type": "function",
    "function": {
      "name": "tool_name",
      "description": "...",
      "parameters": { "type": "object", "properties": {...} }
    }
  }]
}
```

**Anthropic-style:**
```json
{
  "tools": [{
    "name": "tool_name",
    "description": "...",
    "input_schema": { "type": "object", "properties": {...} }
  }]
}
```

**Google-style:**
```json
{
  "tools": [{
    "functionDeclarations": [{
      "name": "tool_name",
      "description": "...",
      "parameters": { "type": "object", "properties": {...} }
    }]
  }]
}
```

#### Response Parsing

Tool calls often arrive in multiple streaming chunks:

```kotlin
// Chunk 1: ID and name
{"tool_calls":[{"index":0,"id":"call_123","function":{"name":"get_time"}}]}

// Chunk 2: Arguments (may be split across multiple chunks!)
{"tool_calls":[{"index":0,"function":{"arguments":"{\"timezone\":"}}]}
{"tool_calls":[{"index":0,"function":{"arguments":"\"UTC\"}"}}]}

// Chunk 3: Finish reason
{"finish_reason":"tool_calls"}
```

**Use a builder pattern to accumulate:**
```kotlin
private class ToolCallBuilder {
    var id: String? = null
    var name: String? = null
    val argumentsBuilder = StringBuilder()
    fun isComplete() = id != null && name != null
}
```

### 4. Thinking/Reasoning Support

If your provider supports thinking (like OpenAI o1, Claude thinking, Gemini thinking):

```kotlin
// Detect thinking content
val thinkingContent = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
    ?: delta["thinking"]?.jsonPrimitive?.contentOrNull

if (!thinkingContent.isNullOrEmpty()) {
    if (!isInThinkingPhase) {
        isInThinkingPhase = true
        thinkingStartTime = System.currentTimeMillis()
        callback.onThinkingStarted()
    }
    thinkingBuilder.append(thinkingContent)
    callback.onThinkingPartial(thinkingContent)
}

// When switching to regular content or stream ends:
if (isInThinkingPhase) {
    completeThinkingPhase(callback, thinkingStartTime, thinkingBuilder)
    isInThinkingPhase = false
}
```

### 5. File Attachment Handling

#### Option A: Inline Base64 (Anthropic, Cohere, OpenRouter)

```kotlin
message.attachments.filter { it.mime_type.startsWith("image/") }
    .forEach { attachment ->
        val base64 = readFileAsBase64(attachment.local_file_path)
        // Include in message content
    }
```

#### Option B: Separate Upload (OpenAI, Google)

Requires changes to `FileUploadManager.kt`:
```kotlin
// In ensureAttachmentUploadedForProvider()
"yourprovider" -> attachment.file_YOURPROVIDER_id == null

// Create upload method
private suspend fun uploadFileToYourProvider(...): String? {
    // Upload file, return ID
}
```

And add field to `Attachment` in `ChatHistory.kt`:
```kotlin
val file_YOURPROVIDER_id: String? = null
```

---

## Common Pitfalls

### 1. JsonNull vs Kotlin null

**Problem:** JSON `null` is not Kotlin `null`. This crashes:
```kotlin
// API sends: {"tool_calls": null}
val toolCalls = delta["tool_calls"]?.jsonArray  // CRASHES!
```

**Solution:**
```kotlin
val toolCalls = delta["tool_calls"]?.takeIf { it !is JsonNull }?.jsonArray
```

### 2. Tool Parameters Already Complete

**Problem:** `toolSpec.parameters` is already a complete JSON schema. Don't wrap it:
```kotlin
// WRONG - creates nested structure
put("parameters", buildJsonObject {
    put("type", "object")
    put("properties", toolSpec.parameters)  // This IS already a full schema!
})

// CORRECT
if (toolSpec.parameters != null) {
    put("parameters", toolSpec.parameters)
} else {
    put("parameters", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    })
}
```

### 3. Provider Dropdown Only Shows Active Keys

**Problem:** Users can't add keys for new providers.

**Solution:** In `MainActivity.kt`, ApiKeysScreen must receive `repository.loadProviders()` not `uiState.availableProviders` (which is filtered).

### 4. SSE Comment Lines

**Problem:** Some APIs send keepalive comments that break parsing.
```
: OPENROUTER PROCESSING
data: {"choices":[...]}
```

**Solution:**
```kotlin
if (currentLine.startsWith(":")) continue
```

### 5. Streaming Arguments Across Chunks

**Problem:** Tool arguments may be split:
```
{"arguments": "{\"query\":"}
{"arguments": "\"hello\"}"}
```

**Solution:** Use StringBuilder, parse only when complete:
```kotlin
builder.argumentsBuilder.append(args)
// Parse in finishReason == "tool_calls" block
```

### 6. Empty Response with Tools

**Problem:** Model calls a tool but no text content, causing "Empty response" error.

**Solution:** Check for tool calls BEFORE checking if response is empty:
```kotlin
return when {
    detectedToolCall != null -> ProviderStreamingResult.ToolCallDetected(...)
    fullResponse.isNotEmpty() -> ProviderStreamingResult.TextComplete(...)
    else -> ProviderStreamingResult.Error("Empty response")
}
```

### 7. Model-Specific Behaviors

**Problem:** Some models don't support tools, thinking, or have different behaviors.

**Solution:** Check model name or capabilities:
```kotlin
val supportsTools = !modelName.contains("o1")  // o1 doesn't support tools
```

### 8. Error Response Parsing

**Problem:** Error responses have different formats than success responses.

**Solution:** Check HTTP status first, parse error stream separately:
```kotlin
if (responseCode >= 400) {
    val errorStream = connection.errorStream  // NOT inputStream!
    val errorBody = BufferedReader(InputStreamReader(errorStream)).readText()
    // Parse error format
}
```

### 9. Connection Cleanup

**Problem:** Leaking connections on errors.

**Solution:** Always close in finally or use try-with-resources:
```kotlin
try {
    // ... parsing
} finally {
    reader.close()
    connection.disconnect()
}
```

### 10. Missing UI Updates

**Problem:** Forgot to add provider to one of the many `when` statements.

**Solution:** Search codebase for `else -> R.string.provider_openai` to find all places needing updates.

---

## Testing Checklist

### Basic Functionality
- [ ] API key can be added (appears in dropdown)
- [ ] Provider appears in model selector
- [ ] Simple message streams correctly
- [ ] Multi-turn conversation maintains context
- [ ] Provider name displays correctly everywhere

### Message Handling
- [ ] System prompt is sent correctly
- [ ] User messages format correctly
- [ ] Assistant messages format correctly
- [ ] Long messages work (no truncation issues)

### Tool Calling (if supported)
- [ ] Tools sent in correct format for this provider
- [ ] Tool calls detected in response
- [ ] Tool arguments parsed correctly (including split chunks)
- [ ] Tool results sent back correctly
- [ ] Multi-step tool chains work
- [ ] Empty tool parameters `{}` handled

### Thinking/Reasoning (if supported)
- [ ] Thinking indicator appears
- [ ] Thinking content captured
- [ ] Transition from thinking to response works
- [ ] Duration tracked correctly

### File Attachments (if supported)
- [ ] Images sent correctly (Base64 or upload)
- [ ] PDFs/documents handled
- [ ] Multiple attachments work

### Error Handling
- [ ] Invalid API key shows clear error
- [ ] Rate limiting handled gracefully
- [ ] Network timeout doesn't crash
- [ ] Malformed response doesn't crash
- [ ] Empty response shows error (not silent fail)

### Edge Cases
- [ ] Very long responses stream without issues
- [ ] Rapid messages don't cause race conditions
- [ ] Cancellation works mid-stream

---

## Reference: Existing Provider Templates

| Provider | Best For | Key Features |
|----------|----------|--------------|
| `OpenAIProvider.kt` | OpenAI Responses API | Full featured, web search, thinking |
| `OpenRouterProvider.kt` | OpenAI-compatible APIs | Simple chat/completions format |
| `AnthropicProvider.kt` | Anthropic-style APIs | Tool use blocks, thinking |
| `GoogleProvider.kt` | Google Gemini APIs | Parts-based content, function calling |
| `CohereProvider.kt` | Cohere v2 APIs | Different role names, tool format |
| `PoeProvider.kt` | Poe-style APIs | Points-based, tool results in messages |

---

## File Structure

```
app/src/main/java/com/example/ApI/
├── data/
│   ├── network/
│   │   ├── LLMApiService.kt          ← Register provider
│   │   └── providers/
│   │       ├── BaseProvider.kt       ← Shared utilities
│   │       ├── ProviderResult.kt     ← Result types
│   │       └── YourProvider.kt       ← NEW
│   ├── repository/
│   │   ├── ModelsCacheManager.kt     ← Provider config
│   │   └── FileUploadManager.kt      ← File uploads
│   └── model/
│       └── ChatHistory.kt            ← Attachment model
├── ui/
│   ├── components/
│   │   ├── ChatTopBar.kt             ← Header display
│   │   └── dialogs/
│   │       ├── ApiKeyDialogs.kt      ← Key entry
│   │       └── ModelSelectionDialogs.kt
│   ├── managers/provider/
│   │   └── ModelSelectionManager.kt  ← Web search config
│   └── screen/
│       ├── ApiKeysScreen.kt          ← Brand colors
│       └── UsernameScreen.kt         ← Title gen
└── res/values/
    └── strings.xml                   ← Display names
```

---

## Summary

Adding a provider requires understanding:

1. **Your API's specifics** - Message format, streaming format, tool format
2. **The conversion layer** - Map app's internal format to provider's format
3. **Error handling** - JsonNull, split chunks, HTTP errors
4. **UI integration** - All the `when` statements that need updating

Start by studying an existing provider similar to yours, then adapt carefully, testing each feature as you go.
