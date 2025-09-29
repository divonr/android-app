# A Developer's Guide to Streaming Function Calls with the OpenAI Responses API

This guide provides a detailed, step-by-step walkthrough of the event stream you receive from the OpenAI `/v1/responses` endpoint when `stream: true` is enabled and the model decides to use a classic function call (defined in the `tools` parameter). By the end of this guide, you will have a precise understanding of the event lifecycle, enabling you to build a robust listener that can reliably identify a tool call, assemble its arguments, and execute it.

## The Big Picture: The Lifecycle of a Streaming Tool Call

Before diving into the specific JSON events, it's crucial to understand the conceptual flow. When the model decides to call a function, it doesn't send a single, complete message. Instead, it emits a sequence of events that, when combined, describe the full tool call.

The lifecycle looks like this:

1.  **Response Initiation:** The API acknowledges the request and signals that the response generation has begun.
2.  **Tool Call Declaration:** The API sends an event declaring that an output item of type `tool_call` is being added. This event contains the **name** of the function the model wants to call and a unique ID for this specific call.
3.  **Argument Streaming:** The API sends a series of "delta" events. Each event contains a small piece (a string fragment) of the JSON arguments for the function. Your code must collect and concatenate these fragments.
4.  **Argument Finalization:** The API sends a final event indicating that all argument fragments have been sent. This event contains the complete, final JSON string of arguments. **This is the trigger to execute your function.**
5.  **Response Completion:** The API sends a final event to signal that the entire model turn is complete.

Now, let's map this lifecycle to the concrete events you will receive in the stream.

## The Anatomy of the Stream: Key Events in Order

Here are the specific events you must listen for to handle a function call, presented in the order they will appear in the stream.

### 1. The Beginning: `response.created` & `response.in_progress`

These are standard lifecycle events that signal the start of the response generation. While important for state management, they don't contain tool-call-specific information.

### 2. The Declaration: `response.output_item.added`

**This is the most critical event for identifying that a tool call is about to happen.**

When the model decides to call a function, it emits this event. You must inspect the `item` object within this event's payload.

*   **Purpose:** To announce a new `tool_call` output. It provides the unique `id` for this call and the `name` of the function to be executed.
*   **What to look for:** An `item` where `type` is `"tool_call"`.
*   **Action:**
    1.  Recognize that a tool call has been initiated.
    2.  Store the `item.id` (e.g., `"call_abc123"`) and `item.function.name` (e.g., `"get_current_weather"`).
    3.  Prepare a buffer or variable associated with this `item.id` to start accumulating the argument string fragments.

**Example Payload:**

```json
{
  "type": "response.output_item.added",
  "output_index": 0,
  "item": {
    "id": "call_abc123", // <-- Crucial ID to track this specific call
    "type": "tool_call",
    "function": {
      "name": "get_current_weather", // <-- The name of your function
      "arguments": "" // Arguments will be streamed next, starts empty
    }
  },
  "sequence_number": 3 
}
```

### 3. The Arguments Stream: `response.function_call_arguments.delta`

Immediately following the declaration, the API will send one or more of these events. They will arrive in rapid succession.

*   **Purpose:** To deliver a small chunk of the JSON arguments string.
*   **What to look for:** The `item_id` in this event will match the `id` from the `response.output_item.added` event. The `delta` field contains the string fragment.
*   **Action:**
    1.  Use the `item_id` to find the correct tool call buffer you created in the previous step.
    2.  Append the string from the `delta` field to your buffer.
    3.  **Do not** attempt to parse the JSON yet, as it is incomplete.

**Example Sequence of Payloads:**

```json
// Event 1
{
  "type": "response.function_call_arguments.delta",
  "item_id": "call_abc123",
  "output_index": 0,
  "delta": "{\"location\":",
  "sequence_number": 4
}
// Event 2
{
  "type": "response.function_call_arguments.delta",
  "item_id": "call_abc123",
  "output_index": 0,
  "delta": "\"San Francisco, CA\"",
  "sequence_number": 5
}
// Event 3
{
  "type": "response.function_call_arguments.delta",
  "item_id": "call_abc123",
  "output_index": 0,
  "delta": "}",
  "sequence_number": 6
}
```

After these three events, your buffer for `call_abc123` would contain: `{"location":"San Francisco, CA"}`.

### 4. The Trigger: `response.function_call_arguments.done`

**This event is your signal to act.**

It confirms that all argument fragments for a specific tool call have been sent and the arguments are now complete.

*   **Purpose:** To finalize the arguments for a tool call and provide the complete JSON string.
*   **What to look for:** The `item_id` will match the call you are tracking. The `arguments` field contains the final, complete string.
*   **Action:**
    1.  You can optionally use the `arguments` field from this event directly, or simply use the string you have already assembled in your buffer. They will be identical.
    2.  Parse the complete arguments string into a JSON object.
    3.  Using the function name you stored earlier and the parsed arguments, **execute your local function**.
    4.  You can now consider this specific tool call "in-flight" and prepare to send the result back to the model in a subsequent API call.

**Example Payload:**

```json
{
  "type": "response.function_call_arguments.done",
  "item_id": "call_abc123",
  "output_index": 0,
  "arguments": "{\"location\":\"San Francisco, CA\"}", // <-- The final, complete arguments
  "sequence_number": 7
}
```

### 5. The Cleanup: `response.output_item.done` & `response.completed`

These events finalize the stream.

*   `response.output_item.done`: Marks the specific `tool_call` item as complete from the API's perspective.
*   `response.completed`: Signals the entire response from the API is finished. If the model had decided to send a text message *after* the tool call, this event would come after those text delta events.

## Implementation Logic (Pseudocode)

Here is a simple logic for a listener class or function that handles the stream.

```
class ToolCallListener:
    def __init__(self):
        # A dictionary to hold tool calls being streamed
        # Key: tool_call_id (e.g., "call_abc123")
        # Value: { name: "function_name", arguments: "accumulated_string" }
        self.active_tool_calls = {}

    def process_event(self, event):
        event_type = event.get("type")

        if event_type == "response.output_item.added":
            item = event.get("item")
            if item and item.get("type") == "tool_call":
                tool_call_id = item.get("id")
                function_name = item.get("function", {}).get("name")
                if tool_call_id and function_name:
                    print(f"Tool call started: ID={tool_call_id}, Name={function_name}")
                    self.active_tool_calls[tool_call_id] = {
                        "name": function_name,
                        "arguments": ""
                    }
        
        elif event_type == "response.function_call_arguments.delta":
            tool_call_id = event.get("item_id")
            if tool_call_id in self.active_tool_calls:
                argument_delta = event.get("delta", "")
                self.active_tool_calls[tool_call_id]["arguments"] += argument_delta

        elif event_type == "response.function_call_arguments.done":
            tool_call_id = event.get("item_id")
            if tool_call_id in self.active_tool_calls:
                print(f"Tool call arguments complete for ID={tool_call_id}")
                
                call_info = self.active_tool_calls[tool_call_id]
                function_name = call_info["name"]
                complete_arguments = call_info["arguments"]
                
                # You can also use event.get("arguments") which is the final string
                
                print(f"Executing function '{function_name}' with arguments: {complete_arguments}")
                
                # --- EXECUTE YOUR CODE HERE ---
                # 1. Parse `complete_arguments` string to JSON
                # 2. Call your local function with the parsed arguments
                # --------------------------------

                # Clean up the completed call
                del self.active_tool_calls[tool_call_id]

```

By following this event sequence and logic, you can perfectly implement a listener that reliably handles streaming function calls from the OpenAI Responses API. You will not need any additional information to achieve this.