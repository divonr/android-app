# Generating Text with Streaming using the Gemini API

This document explains how to request and handle streaming responses from the Google Gemini API using the REST interface.

Streaming allows you to receive parts of the generated text as they become available, rather than waiting for the entire response to be completed. This can significantly improve the user experience for applications. [1]

## Making a Streaming Request

To make a streaming request, you need to use the `streamGenerateContent` method instead of `generateContent`. Additionally, you must add the query parameter `alt=sse` (Server-Sent Events) to the URL.

The request is sent as a `POST` request with the same JSON body that you would use for a non-streaming request.

### cURL Example

Here is a complete `curl` command for a streaming request. The `--no-buffer` flag is recommended to process the output as it arrives. [1]

```bash
curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:streamGenerateContent?alt=sse" \
    -H "x-goog-api-key: $GEMINI_API_KEY" \
    -H 'Content-Type: application/json' \
    --no-buffer \
    -d '{
          "contents": [
            { "parts": [
                { "text": "Write a short story about a magic backpack." }
              ]
            }
          ]
        }'
```

## Understanding the Streaming Response

The response will not be a single JSON object. Instead, it will be a stream of data chunks. Each chunk is a Server-Sent Event (SSE).

Each event in the stream starts with `data: `, followed by a JSON object. The stream is terminated by a final empty line.

### Raw Stream Format Example

The raw output you receive will look something like this. Note the `data: ` prefix on each line that contains a JSON payload.

```
data: { ... JSON content of the first chunk ... }

data: { ... JSON content of the second chunk ... }

data: { ... JSON content of the third chunk ... }

...
```

### Example Response Chunks

To get the full response, you need to collect the `text` from the `parts` array within each JSON chunk and concatenate them. The final chunk in the stream will usually have a `finishReason` of `STOP`.

Here is an example of what the parsed JSON objects from a stream might look like:

```json
[
  {
    "candidates": [
      {
        "content": {
          "parts": [
            {
              "text": "Leo found the "
            }
          ],
          "role": "model"
        },
        "finishReason": "UNSPECIFIED",
        "index": 0,
        "safetyRatings": []
      }
    ]
  },
  {
    "candidates": [
      {
        "content": {
          "parts": [
            {
              "text": "backpack in his grandfather's attic. It was made of old, worn leather and "
            }
          ],
          "role": "model"
        },
        "finishReason": "UNSPECIFIED",
        "index": 0,
        "safetyRatings": []
      }
    ]
  },
  {
    "candidates": [
      {
        "content": {
          "parts": [
            {
              "text": "seemed to hum with a faint energy. When he put it on, he discovered it could create anything he could clearly imagine."
            }
          ],
          "role": "model"
        },
        "finishReason": "STOP",
        "index": 0,
        "safetyRatings": [
          {
            "category": "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "probability": "NEGLIGIBLE"
          },
          {
            "category": "HARM_CATEGORY_HATE_SPEECH",
            "probability": "NEGLIGIBLE"
          },
          {
            "category": "HARM_CATEGORY_HARASSMENT",
            "probability": "NEGLIGIBLE"
          },
          {
            "category": "HARM_CATEGORY_DANGEROUS_CONTENT",
            "probability": "NEGLIGIBLE"
          }
        ]
      }
    ]
  }
]
```