package com.example.ApI.data.network.streaming

import java.io.BufferedReader

/**
 * Result of stream parsing
 */
sealed class StreamResult {
    object Success : StreamResult()
    data class Error(val message: String) : StreamResult()
}

/**
 * Base interface for SSE stream parsers
 */
interface SSEStreamParser {
    fun parse(reader: BufferedReader, handler: StreamEventHandler): StreamResult
}
