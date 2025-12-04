# Cohere API Integration Reference

Complete technical reference for integrating Cohere's API into the Android LLM Chat App, based on official documentation research (December 2025).

---

## Table of Contents
1. [API Overview](#api-overview)
2. [Authentication](#authentication)
3. [Base URLs & Endpoints](#base-urls--endpoints)
4. [Available Models](#available-models)
5. [Request Structure](#request-structure)
6. [Streaming Response Format](#streaming-response-format)
7. [Tool Calling Support](#tool-calling-support)
8. [Web Search & Grounding](#web-search--grounding)
9. [Image/Vision Support](#imagevision-support)
10. [Implementation Checklist](#implementation-checklist)

---

## API Overview

Cohere provides a Chat API with streaming support, tool calling, web search grounding, and vision capabilities. The API uses Server-Sent Events (SSE) for streaming responses.

**Documentation**: [docs.cohere.com](https://docs.cohere.com/)

---

## Authentication

### Header Format

```
Authorization: Bearer YOUR_API_KEY
```

- **Header Name**: `Authorization`
- **Header Value**: `Bearer <token>` where `<token>` is your Cohere API key
- All requests must be POST requests with this authorization header

**Reference**: [Working with Cohere's API and SDK](https://docs.cohere.com/reference/about)

---

## Base URLs & Endpoints

### API Versions

Cohere has two API versions:

**v1 API (Legacy)**
```
POST https://api.cohere.com/v1/chat
```

**v2 API (Current - Recommended)**
```
POST https://api.cohere.ai/v2/chat
```

For streaming, both endpoints support the `stream` parameter:
- v1: `https://api.cohere.com/v1/chat` with `stream: true`
- v2: `https://api.cohere.ai/v2/chat` with `stream: true`

**References**:
- [Chat API Documentation](https://docs.cohere.com/docs/chat-api)
- [API v2 Migration Guide](https://docs.cohere.com/docs/migrating-v1-to-v2)

---

## Available Models

### Current Flagship Models (2025)

1. **Command A (Recommended)**
   - Model ID: `command-a-03-2025`
   - Parameters: 111B
   - Context Length: 256K tokens
   - Performance: Strongest-performing model across domains
   - Throughput: 150% higher than Command R+ 08-2024
   - **Best for**: Production applications requiring highest quality

2. **Command R+**
   - Model ID: `command-r-plus-08-2024`
   - Parameters: 104B
   - Features: Advanced RAG, tool use, multi-step reasoning
   - Improvements: 50% higher throughput, 25% lower latency vs previous version
   - **Best for**: Complex tasks with tool use and RAG

3. **Command R**
   - Model ID: `command-r-08-2024`
   - Features: RAG, tool use capabilities
   - **Best for**: Standard chat applications with moderate complexity

4. **Command A Vision** (Multimodal)
   - Model ID: `command-a-vision-07-2025`
   - Capabilities: Text + image understanding
   - **Best for**: Document analysis, chart interpretation, OCR tasks

**References**:
- [Command A Model Overview](https://docs.cohere.com/docs/models)
- [Command R+ Documentation](https://docs.cohere.com/docs/command-r-plus)
- [Command A Vision Announcement](https://docs.cohere.com/changelog/2025-07-31-command-a-vision)

---

## Request Structure

### Basic Chat Request (v2 API)

```json
{
  "model": "command-a-03-2025",
  "messages": [
    {
      "role": "system",
      "content": "You are a helpful assistant."
    },
    {
      "role": "user",
      "content": "Hello, how are you?"
    }
  ],
  "stream": true
}
```

### Request Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `model` | string | Yes | Model identifier (e.g., "command-a-03-2025") |
| `messages` | array | Yes | List of message objects with role and content |
| `stream` | boolean | No | Enable streaming (default: false) |
| `max_tokens` | integer | No | Max output tokens (defaults to model max) |
| `temperature` | float | No | Sampling temperature (0.0 to 1.0) |
| `tools` | array | No | List of tool definitions for function calling |
| `connectors` | array | No | List of connectors (e.g., web-search) |
| `strict_tools` | boolean | No | Enforce strict tool schema compliance |

### Message Roles (v2 API)

Cohere v2 API supports four message roles:

1. **system** - System instructions (replaces v1 `preamble`)
2. **user** - User messages/prompts
3. **assistant** - Model responses (can contain text + tool calls)
4. **tool** - Tool execution results

**Message Structure**:
```json
{
  "role": "user",
  "content": "Your message text here"
}
```

For vision models, content can include images:
```json
{
  "role": "user",
  "content": [
    {
      "type": "text",
      "text": "What's in this image?"
    },
    {
      "type": "image_url",
      "image_url": "https://example.com/image.jpg"
    }
  ]
}
```

**References**:
- [Chat API Documentation](https://docs.cohere.com/docs/chat-api)
- [v1 to v2 Migration Guide](https://docs.cohere.com/docs/migrating-v1-to-v2)

---

## Streaming Response Format

### SSE Event Types

Cohere uses Server-Sent Events (SSE) with the following event types:

#### Core Streaming Events

1. **stream-start**
   - **Timing**: First event emitted
   - **Frequency**: Once per request
   - **Contains**: Metadata (generation_id, etc.)
   ```json
   {
     "event_type": "stream-start",
     "generation_id": "abc123..."
   }
   ```

2. **content-delta** (v2) / **text-generation** (v1)
   - **Timing**: During text generation
   - **Frequency**: Multiple times (each text chunk)
   - **Contains**: Text delta/chunk
   ```json
   {
     "event_type": "content-delta",
     "delta": {
       "message": {
         "content": {
           "text": "partial text chunk"
         }
       }
     }
   }
   ```

3. **stream-end**
   - **Timing**: Final event
   - **Frequency**: Once per request
   - **Contains**: Complete response metadata, token usage
   ```json
   {
     "event_type": "stream-end",
     "finish_reason": "complete",
     "response": {
       "text": "complete response...",
       "meta": {
         "tokens": {
           "input_tokens": 100,
           "output_tokens": 50
         }
       }
     }
   }
   ```

#### Additional Events (v2 API)

4. **content-start**
   - Signals start of new content block

5. **content-end**
   - Signals end of content block

### Finish Reasons

- `complete` - Model finished generating
- `max_tokens` - Exceeded token limit
- `stop_sequence` - Hit stop sequence
- `tool_call` - Model wants to call a tool
- `error` - Internal error occurred
- `timeout` - Request timed out

**References**:
- [Streaming Guide](https://docs.cohere.com/v2/docs/streaming)
- [Chat Stream Reference](https://docs.cohere.com/v2/reference/chat-stream)

---

## Tool Calling Support

Cohere has **full tool/function calling support** with multi-step reasoning and parallel tool execution.

### Tool Definition Format

Tools are defined using JSON Schema:

```json
{
  "tools": [
    {
      "name": "calculate",
      "description": "Performs mathematical calculations",
      "parameter_definitions": {
        "expression": {
          "description": "The mathematical expression to evaluate",
          "type": "string",
          "required": true
        }
      }
    }
  ]
}
```

### Tool Schema Fields

- `name` (required): Tool identifier
- `description` (required): What the tool does
- `parameter_definitions` (required): JSON Schema of parameters
  - Each parameter has: `type`, `description`, `required`

### Strict Mode

Set `strict_tools: true` to guarantee:
- No tool name hallucinations
- Parameters match specified types
- All required parameters included
- Eliminates schema violations

### Tool Call Response

When the model wants to call a tool, it returns:

```json
{
  "tool_plan": "I need to calculate 5 + 3 using the calculate tool",
  "tool_calls": [
    {
      "id": "call_abc123",
      "type": "function",
      "function": {
        "name": "calculate",
        "arguments": "{\"expression\": \"5 + 3\"}"
      }
    }
  ]
}
```

### Sending Tool Results Back

After executing the tool, send results as a tool message:

```json
{
  "role": "tool",
  "tool_call_id": "call_abc123",
  "content": "8"
}
```

### Streaming Events for Tools

When streaming with tools enabled:

1. **tool-plan-delta** - Streams the tool plan text
2. **tool-call-start** - Tool call begins streaming
3. **tool-call-delta** - Streams tool call arguments
4. **tool-call-end** - Tool call finished streaming

For parallel tool calls, the sequence repeats for each tool.

### Parallel Tool Calling

Cohere supports calling multiple tools in parallel:
- Same tool multiple times
- Different tools in one request
- Any number of parallel calls

**References**:
- [Tool Use Overview](https://docs.cohere.com/docs/tools)
- [Basic Tool Use Guide](https://docs.cohere.com/docs/tool-use)
- [Tool Use Streaming](https://docs.cohere.com/docs/tool-use-streaming)
- [Parameter Types](https://docs.cohere.com/docs/tool-use-parameter-types)

---

## Web Search & Grounding

Cohere provides **built-in web search** through connectors for grounded generation.

### Enabling Web Search

Use the `connectors` parameter with the built-in web-search connector:

```json
{
  "model": "command-a-03-2025",
  "messages": [
    {
      "role": "user",
      "content": "What's the latest news about AI?"
    }
  ],
  "connectors": [
    {
      "id": "web-search"
    }
  ]
}
```

### How It Works

1. Model receives user query
2. Automatically searches the web for relevant information
3. Grounds response in search results
4. Returns response with **citations** to sources

### Citation Support

When using web search or RAG, responses include citations:
- Inline reference markers
- Source URLs in metadata
- Ability to trace claims back to sources

### Custom Connectors

Beyond web search, you can create custom connectors for:
- Internal documentation
- Knowledge bases
- Databases
- APIs

**Connector Framework**: Open-source reference code available at [cohere-ai/quick-start-connectors](https://github.com/cohere-ai/quick-start-connectors)

**References**:
- [RAG Documentation](https://docs.cohere.com/v2/docs/retrieval-augmented-generation-rag)
- [Connector Authentication](https://docs.cohere.com/v1/docs/connector-authentication)
- [RAG Quickstart](https://cohere.com/llmu/rag-connectors)

---

## Image/Vision Support

Cohere supports **multimodal vision capabilities** through specific models.

### Vision Models

1. **Command A Vision**
   - Model ID: `command-a-vision-07-2025`
   - First commercial Cohere model with vision
   - Supports text + image inputs
   - Use cases: Document analysis, chart interpretation, OCR

2. **Aya Vision**
   - Multilingual vision model
   - Supports multiple languages with images

### Image Input Formats

Cohere accepts images in **two formats**:

#### 1. Base64 Data URLs (Recommended for offline)

```json
{
  "role": "user",
  "content": [
    {
      "type": "text",
      "text": "What's in this image?"
    },
    {
      "type": "image_url",
      "image_url": "data:image/jpeg;base64,/9j/4AAQSkZJRg..."
    }
  ]
}
```

**Advantages**:
- Works in offline deployments
- No external hosting needed

#### 2. HTTP Image URLs (Faster)

```json
{
  "role": "user",
  "content": [
    {
      "type": "text",
      "text": "Describe this image"
    },
    {
      "type": "image_url",
      "image_url": "https://example.com/image.jpg"
    }
  ]
}
```

**Advantages**:
- Faster processing
- Smaller request payload

**Note**: HTTP URLs not available on Azure, Bedrock, and other third-party platforms

### Usage with Chat API

Vision models use the same Chat API endpoint as text-only models:

```
POST https://api.cohere.ai/v2/chat
```

The API structure is identical to text-only models - just include image content in messages.

### Important Limitations

- **Single image per message**: Only one image allowed per user message
- **Platform restrictions**: Vision not available on Cohere North platform
- **Embeddings limitation**: Cannot combine text and image in same embedding request

**References**:
- [Image Inputs Documentation](https://docs.cohere.com/docs/image-inputs)
- [Command A Vision Announcement](https://cohere.com/blog/command-a-vision)
- [Aya Vision Introduction](https://docs.cohere.com/page/aya-vision-intro)

---

## Implementation Checklist

### Core Integration

- [ ] **Authentication**: Implement Bearer token header format
- [ ] **Base URL**: Use v2 endpoint `https://api.cohere.ai/v2/chat`
- [ ] **Request Format**: Build messages array with proper roles
- [ ] **Model Selection**: Support Command A, Command R+, Command R models
- [ ] **Headers**: Set `Authorization` and `Content-Type: application/json`

### Streaming Implementation

- [ ] **Enable Streaming**: Set `stream: true` in request
- [ ] **Parse SSE Events**: Handle Server-Sent Events format
- [ ] **Event Types**: Implement handlers for:
  - [ ] `stream-start` - Initialize streaming state
  - [ ] `content-delta` - Append text chunks to UI
  - [ ] `stream-end` - Finalize response, show token usage
  - [ ] `content-start`/`content-end` - Track content blocks
- [ ] **Error Handling**: Handle error events and finish reasons
- [ ] **Finish Reasons**: Handle `complete`, `max_tokens`, `stop_sequence`, `tool_call`, `error`, `timeout`

### Message Handling

- [ ] **Role Mapping**: Support `system`, `user`, `assistant`, `tool` roles
- [ ] **Conversation History**: Pass entire chat history in `messages` array
- [ ] **System Prompts**: Use `role: "system"` for instructions (not v1 `preamble`)
- [ ] **Multi-turn**: Maintain conversation context across requests

### Tool Calling

- [ ] **Tool Definitions**: Build JSON Schema for tools
- [ ] **Tool Parameter**: Pass tools array in request
- [ ] **Strict Mode**: Option to enable `strict_tools: true`
- [ ] **Parse Tool Calls**: Extract `tool_plan` and `tool_calls` from response
- [ ] **Execute Tools**: Run tool functions with provided arguments
- [ ] **Send Results**: Return results as `role: "tool"` messages
- [ ] **Streaming Events**: Handle `tool-plan-delta`, `tool-call-start`, `tool-call-delta`, `tool-call-end`
- [ ] **Parallel Calls**: Support multiple simultaneous tool calls
- [ ] **Multi-step**: Loop until model stops calling tools

### Web Search & Grounding

- [ ] **Connectors Parameter**: Add `connectors: [{"id": "web-search"}]` when enabled
- [ ] **Citations**: Parse and display source citations
- [ ] **Toggle**: UI option to enable/disable web search
- [ ] **Cost Awareness**: Inform users web search may increase costs

### Vision Support (Optional)

- [ ] **Vision Models**: Add `command-a-vision-07-2025` to model list
- [ ] **Image Encoding**: Support base64 data URLs for images
- [ ] **Image URLs**: Support HTTP image URLs (faster option)
- [ ] **Content Array**: Build content array with text + image objects
- [ ] **File Type Detection**: Identify image attachments and encode appropriately
- [ ] **UI Display**: Show image previews in chat interface

### UI Components

- [ ] **Provider Name**: Add "Cohere" to provider list
- [ ] **Brand Colors**: Add Cohere brand colors to API keys screen
  - Suggested: Coral/pink tones (Cohere's brand identity)
- [ ] **Model Selection**: Dropdown with Command A, Command R+, Command R
- [ ] **String Resources**: Add `provider_cohere`, `cohere_command_a` strings
- [ ] **Auto-naming**: Add Cohere option to auto-generate chat titles

### DataRepository Integration

- [ ] **Provider Registration**: Add Cohere to `loadProviders()` list
- [ ] **Model List**: Include all supported models with proper IDs
- [ ] **API Configuration**: Set base URL, headers, request structure
- [ ] **No File Upload**: Cohere uses inline base64 (set `upload_files_request: null`)

### Error Handling & Edge Cases

- [ ] **Invalid API Key**: Show clear error message
- [ ] **Rate Limiting**: Handle 429 errors gracefully
- [ ] **Timeout Handling**: Set appropriate timeout for streaming
- [ ] **Empty Responses**: Handle edge case of empty `content-delta`
- [ ] **Malformed JSON**: Catch and report JSON parsing errors
- [ ] **Network Errors**: Retry logic for transient failures

### Testing

- [ ] **Basic Chat**: Send/receive simple messages
- [ ] **Streaming**: Verify chunks appear incrementally
- [ ] **Multi-turn**: Test 5+ message conversations
- [ ] **Tool Calling**: Test single and parallel tool calls
- [ ] **Web Search**: Enable connector and test grounded responses
- [ ] **Vision**: Upload image and test multimodal understanding
- [ ] **Citations**: Verify citations appear when using web search
- [ ] **Error Cases**: Test invalid key, network timeout, malformed requests

---

## Example cURL Request

### Basic Streaming Chat

```bash
curl --request POST \
  --url https://api.cohere.ai/v2/chat \
  --header 'Authorization: Bearer YOUR_API_KEY' \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "command-a-03-2025",
    "messages": [
      {
        "role": "user",
        "content": "Write a haiku about coding"
      }
    ],
    "stream": true
  }'
```

### With Tool Calling

```bash
curl --request POST \
  --url https://api.cohere.ai/v2/chat \
  --header 'Authorization: Bearer YOUR_API_KEY' \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "command-a-03-2025",
    "messages": [
      {
        "role": "user",
        "content": "What is 234 * 567?"
      }
    ],
    "tools": [
      {
        "name": "calculator",
        "description": "Performs mathematical calculations",
        "parameter_definitions": {
          "expression": {
            "type": "string",
            "description": "Mathematical expression",
            "required": true
          }
        }
      }
    ],
    "strict_tools": true,
    "stream": true
  }'
```

### With Web Search

```bash
curl --request POST \
  --url https://api.cohere.ai/v2/chat \
  --header 'Authorization: Bearer YOUR_API_KEY' \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "command-a-03-2025",
    "messages": [
      {
        "role": "user",
        "content": "What are the latest AI developments in December 2025?"
      }
    ],
    "connectors": [
      {
        "id": "web-search"
      }
    ],
    "stream": true
  }'
```

---

## Key Differences from Other Providers

### vs. OpenAI
- Uses `messages` array (similar) but different role names in v1 (`CHATBOT` vs `assistant`)
- Built-in web search via connectors (OpenAI requires custom function)
- `connectors` parameter instead of function calling for web search
- Different streaming event names (`content-delta` vs `choices[].delta`)

### vs. Anthropic
- No separate `max_tokens` requirement (optional)
- Uses `connectors` for web search instead of tools
- Different tool calling format (`parameter_definitions` vs `input_schema`)
- `tool_plan` field provides reasoning about tool use

### vs. Google Gemini
- Simpler message format (no `parts` arrays in v2)
- Better structured tool calling support
- Built-in web search connector
- No separate file upload endpoint (uses inline base64)

---

## Summary

Cohere API provides:

✅ **Full streaming support** via Server-Sent Events
✅ **Advanced tool calling** with parallel execution and strict mode
✅ **Built-in web search** through connectors
✅ **Vision capabilities** with Command A Vision model
✅ **Citation support** for grounded generation
✅ **Simple authentication** via Bearer token
✅ **Inline image encoding** (no separate upload endpoint)
✅ **Multi-step reasoning** with tool_plan field
✅ **High performance** models (Command A: 256K context, 111B params)

The integration is straightforward and follows similar patterns to other providers while offering unique features like built-in web search and comprehensive tool calling support.

---

## Sources

- [Cohere Chat API Documentation](https://docs.cohere.com/docs/chat-api)
- [Chat Streaming Reference](https://docs.cohere.com/v2/reference/chat-stream)
- [Streaming Guide](https://docs.cohere.com/v2/docs/streaming)
- [Tool Use Documentation](https://docs.cohere.com/docs/tool-use)
- [Tool Use Streaming](https://docs.cohere.com/docs/tool-use-streaming)
- [RAG Documentation](https://docs.cohere.com/v2/docs/retrieval-augmented-generation-rag)
- [Image Inputs Guide](https://docs.cohere.com/docs/image-inputs)
- [Command A Vision Blog](https://cohere.com/blog/command-a-vision)
- [Models Overview](https://docs.cohere.com/docs/models)
- [v1 to v2 Migration](https://docs.cohere.com/docs/migrating-v1-to-v2)
- [Working with API and SDK](https://docs.cohere.com/reference/about)
- [Quick Start Connectors (GitHub)](https://github.com/cohere-ai/quick-start-connectors)
