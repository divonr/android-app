# OpenAI API Streaming Guide

This guide explains how to request streaming responses from the OpenAI API and handle the server-sent events to collect the response chunk by chunk.

## Requesting Streaming

To enable streaming, set the `stream` parameter to `true` in your API request:

```json
{
  "model": "gpt-4o-2024-08-06",
  "messages": [
    {
      "role": "user", 
      "content": "Tell me a story about a unicorn"
    }
  ],
  "stream": true,
  "stream_options": {
    // Optional streaming configuration
  }
}
```

### Key Parameters

- **`stream`** (boolean): Set to `true` to enable streaming. Defaults to `false`.
- **`stream_options`** (object, optional): Additional configuration for streaming responses. Only set when `stream: true`.

## Server-Sent Events

When streaming is enabled, the server emits server-sent events as the response is generated. Here are the events you'll receive:

### 1. Response Lifecycle Events

#### `response.created`
Emitted when a response is first created.

```json
{
  "type": "response.created",
  "response": {
    "id": "resp_67ccfcdd16748190a91872c75d38539e",
    "object": "response",
    "created_at": 1741487325,
    "status": "in_progress",
    "model": "gpt-4o-2024-08-06",
    "output": [],
    // ... other response properties
  },
  "sequence_number": 1
}
```

#### `response.in_progress`
Emitted periodically while the response is being generated.

```json
{
  "type": "response.in_progress",
  "response": {
    "id": "resp_67ccfcdd16748190a91872c75d38539e",
    "status": "in_progress",
    // ... updated response properties
  },
  "sequence_number": 2
}
```

#### `response.completed`
Emitted when the response is successfully completed.

```json
{
  "type": "response.completed",
  "response": {
    "id": "resp_123",
    "status": "completed",
    "output": [
      {
        "id": "msg_123",
        "type": "message",
        "role": "assistant",
        "content": [
          {
            "type": "output_text",
            "text": "In a shimmering forest under a sky full of stars, a lonely unicorn...",
            "annotations": []
          }
        ]
      }
    ],
    "usage": {
      "input_tokens": 0,
      "output_tokens": 0,
      "total_tokens": 0
    }
  },
  "sequence_number": 10
}
```

#### Error States

**`response.failed`** - Emitted when the response fails:
```json
{
  "type": "response.failed",
  "response": {
    "status": "failed",
    "error": {
      "code": "server_error",
      "message": "The model failed to generate a response."
    }
  }
}
```

**`response.incomplete`** - Emitted when the response is incomplete:
```json
{
  "type": "response.incomplete", 
  "response": {
    "status": "incomplete",
    "incomplete_details": {
      "reason": "max_tokens"
    }
  }
}
```

### 2. Output Item Events

#### `response.output_item.added`
Emitted when a new output item (like a message) is added.

```json
{
  "type": "response.output_item.added",
  "output_index": 0,
  "item": {
    "id": "msg_123",
    "status": "in_progress",
    "type": "message",
    "role": "assistant",
    "content": []
  },
  "sequence_number": 3
}
```

#### `response.output_item.done`
Emitted when an output item is complete.

```json
{
  "type": "response.output_item.done",
  "output_index": 0,
  "item": {
    "id": "msg_123",
    "status": "completed",
    "type": "message", 
    "role": "assistant",
    "content": [
      {
        "type": "output_text",
        "text": "In a shimmering forest under a sky full of stars, a lonely unicorn...",
        "annotations": []
      }
    ]
  },
  "sequence_number": 9
}
```

### 3. Content Events

#### `response.content_part.added`
Emitted when a new content part is added to a message.

```json
{
  "type": "response.content_part.added",
  "item_id": "msg_123",
  "output_index": 0,
  "content_index": 0,
  "part": {
    "type": "output_text",
    "text": "",
    "annotations": []
  },
  "sequence_number": 4
}
```

#### `response.content_part.done`
Emitted when a content part is complete.

```json
{
  "type": "response.content_part.done",
  "item_id": "msg_123",
  "output_index": 0,
  "content_index": 0,
  "part": {
    "type": "output_text",
    "text": "In a shimmering forest under a sky full of stars, a lonely unicorn...",
    "annotations": []
  },
  "sequence_number": 8
}
```

### 4. Text Delta Events

#### `response.output_text.delta`
Emitted for each text chunk as it's generated. **This is the main event for collecting streaming text.**

```json
{
  "type": "response.output_text.delta",
  "item_id": "msg_123",
  "output_index": 0,
  "content_index": 0,
  "delta": "In",
  "sequence_number": 5
}
```

#### `response.output_text.done`
Emitted when text generation is complete for a content part.

```json
{
  "type": "response.output_text.done",
  "item_id": "msg_123",
  "output_index": 0,
  "content_index": 0,
  "text": "In a shimmering forest under a sky full of stars, a lonely unicorn named Luna...",
  "sequence_number": 8
}
```

### 5. Refusal Events

#### `response.refusal.delta`
Emitted when the model refuses to respond (partial refusal text).

```json
{
  "type": "response.refusal.delta",
  "item_id": "msg_123",
  "output_index": 0,
  "content_index": 0,
  "delta": "I cannot",
  "sequence_number": 5
}
```

## Implementation Guide

### Basic Streaming Handler

Here's how to handle streaming responses:

```javascript
async function handleStreamingResponse(response) {
  let fullText = '';
  
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      
      const chunk = decoder.decode(value, { stream: true });
      const events = chunk.split('\n\n').filter(Boolean);
      
      for (const event of events) {
        if (event.startsWith('data: ')) {
          const data = JSON.parse(event.slice(6));
          
          // Handle text deltas - the main content
          if (data.type === 'response.output_text.delta') {
            fullText += data.delta;
            console.log('Delta:', data.delta);
          }
          
          // Handle completion
          else if (data.type === 'response.completed') {
            console.log('Response completed');
            console.log('Final text:', fullText);
            console.log('Usage:', data.response.usage);
          }
          
          // Handle errors
          else if (data.type === 'response.failed') {
            console.error('Response failed:', data.response.error);
          }
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
  
  return fullText;
}
```

### Event Processing Order

Events typically arrive in this sequence:

1. `response.created` - Response starts
2. `response.output_item.added` - Message item created
3. `response.content_part.added` - Text content part created  
4. Multiple `response.output_text.delta` - Text chunks arrive
5. `response.output_text.done` - Text generation complete
6. `response.content_part.done` - Content part complete
7. `response.output_item.done` - Message item complete
8. `response.completed` - Full response complete

### Key Points

- **Collect deltas**: Listen for `response.output_text.delta` events and concatenate the `delta` values to build the complete response
- **Track sequence numbers**: Use `sequence_number` to ensure events are processed in order
- **Handle multiple outputs**: Use `output_index` and `content_index` to handle responses with multiple parts
- **Monitor status**: Watch for completion, failure, or incomplete states
- **Parse carefully**: Each server-sent event is formatted as `data: {json}` followed by double newlines

This streaming approach allows you to display responses to users in real-time as they're generated, providing a better user experience for longer responses.