package com.example.ApI.data.network.streaming

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedReader

/**
 * Parser for data-only SSE format:
 * data: {"type": "event_type", "content": "..."}
 * data: [DONE]
 *
 * Used by: OpenAI, Google, OpenAI-Compatible providers
 */
class DataOnlyStreamParser(
    private val json: Json,
    private val eventTypeField: String? = "type",  // null = structure-based (Google)
    private val doneMarker: String = "[DONE]",
    private val skipKeepalives: Boolean = false,   // For OpenRouter's ":" comment lines
    private val logTag: String = "DataOnlyStreamParser"
) : SSEStreamParser {

    override fun parse(reader: BufferedReader, handler: StreamEventHandler): StreamResult {
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            // Skip blank lines
            if (currentLine.isBlank()) continue

            // Skip keepalive comments if configured (e.g., ": OPENROUTER PROCESSING")
            if (skipKeepalives && currentLine.startsWith(":")) continue

            // Check for done marker (both "data: [DONE]" and just "[DONE]")
            if (currentLine == "data: $doneMarker" || currentLine == doneMarker) {
                handler.onStreamEnd()
                return StreamResult.Success
            }

            // Parse data line
            if (currentLine.startsWith("data:")) {
                val dataContent = currentLine.substring(5).trim()
                if (dataContent.isEmpty() || dataContent == doneMarker || dataContent == "{}") continue

                try {
                    val jsonObj = json.parseToJsonElement(dataContent).jsonObject

                    // Extract event type from JSON field if configured
                    val eventType = eventTypeField?.let {
                        jsonObj[it]?.jsonPrimitive?.contentOrNull
                    }

                    when (val action = handler.onEvent(eventType, jsonObj)) {
                        StreamAction.Continue -> { /* continue loop */ }
                        StreamAction.Stop -> {
                            handler.onStreamEnd()
                            return StreamResult.Success
                        }
                        is StreamAction.Error -> return StreamResult.Error(action.message)
                    }
                } catch (e: Exception) {
                    Log.w(logTag, "Parse error on line: $dataContent", e)
                    handler.onParseError(dataContent, e)
                }
            }
        }

        handler.onStreamEnd()
        return StreamResult.Success
    }
}
