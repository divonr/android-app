package com.example.ApI.data.network.streaming

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader

/**
 * Parser for event-data paired SSE format:
 * event: event_type
 * data: {"json": "content"}
 *
 * Used by: Anthropic, Cohere, Poe
 */
class EventDataStreamParser(
    private val json: Json,
    private val stopEvents: Set<String> = setOf("message_stop", "message-end", "done"),
    private val logTag: String = "EventDataStreamParser"
) : SSEStreamParser {

    override fun parse(reader: BufferedReader, handler: StreamEventHandler): StreamResult {
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            // Skip blank lines
            if (currentLine.isBlank()) continue

            // Look for event line
            if (currentLine.startsWith("event:")) {
                val eventType = currentLine.substring(6).trim()

                // Read corresponding data line
                val dataLine = reader.readLine() ?: continue
                if (!dataLine.startsWith("data:")) continue

                val dataContent = dataLine.substring(5).trim()
                if (dataContent.isEmpty()) continue

                // Check for stop events BEFORE parsing JSON
                if (eventType in stopEvents) {
                    handler.onStreamEnd()
                    return StreamResult.Success
                }

                try {
                    val jsonObj = json.parseToJsonElement(dataContent).jsonObject

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
