package com.example.ApI.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Represents a single log entry in the app.
 */
data class LogEntry(
    val timestamp: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

enum class LogLevel {
    DEBUG, INFO, WARNING, ERROR
}

/**
 * Central logging utility for the application.
 * Stores logs in memory for display in the LogsScreen.
 * Also forwards logs to Android's standard Log system for debugging.
 */
object AppLogger {
    private const val TAG = "AppLogger"
    private const val MAX_LOGS = 500 // Maximum number of logs to keep in memory

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val dateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    /**
     * Log a debug message.
     */
    fun d(message: String) {
        log(message, LogLevel.DEBUG)
        Log.d(TAG, message)
    }

    /**
     * Log an info message.
     */
    fun i(message: String) {
        log(message, LogLevel.INFO)
        Log.i(TAG, message)
    }

    /**
     * Log a warning message.
     */
    fun w(message: String) {
        log(message, LogLevel.WARNING)
        Log.w(TAG, message)
    }

    /**
     * Log an error message.
     */
    fun e(message: String) {
        log(message, LogLevel.ERROR)
        Log.e(TAG, message)
    }

    /**
     * Log an error message with exception.
     */
    fun e(message: String, throwable: Throwable) {
        log("$message: ${throwable.message}", LogLevel.ERROR)
        Log.e(TAG, message, throwable)
    }

    /**
     * Log an API request with full details.
     */
    fun logApiRequest(
        conversationName: String,
        model: String,
        provider: String,
        baseUrl: String,
        headers: Map<String, String>,
        body: String
    ) {
        i("API request sent from conversation \"$conversationName\".")
        i("Model: $model, provider: $provider.")

        // Mask sensitive data in headers
        val maskedHeaders = headers.mapValues { (key, value) ->
            if (key.lowercase().contains("authorization") || key.lowercase().contains("api-key")) {
                "***MASKED***"
            } else {
                value
            }
        }
        i("Request structure; base url: $baseUrl, headers: $maskedHeaders, body: $body")
    }

    /**
     * Log an API response.
     */
    fun logApiResponse(responseCode: Int) {
        i("Response code: $responseCode.")
    }

    /**
     * Log an API error.
     */
    fun logApiError(errorContent: String) {
        e("Error content: $errorContent")
    }

    /**
     * Clear all logs.
     */
    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun log(message: String, level: LogLevel) {
        val timestamp = dateFormatter.format(Instant.now())
        val entry = LogEntry(timestamp, message, level)

        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(entry)

        // Keep only the most recent logs
        if (currentLogs.size > MAX_LOGS) {
            _logs.value = currentLogs.takeLast(MAX_LOGS)
        } else {
            _logs.value = currentLogs
        }
    }

}
