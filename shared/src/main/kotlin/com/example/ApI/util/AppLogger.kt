package com.example.ApI.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
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
 * Stores logs in memory for display in the LogsScreen, and (once
 * [initPersistentLog] is called) also appends every line immediately and
 * synchronously to a log file on disk so it survives process crashes.
 * Platform implementations can forward logs via platformDelegate.
 */
object AppLogger {
    private const val TAG = "AppLogger"
    private const val MAX_LOGS = 500 // Maximum number of logs to keep in memory
    private const val MAX_LOG_FILE_BYTES = 1_000_000L // ~1MB cap for the log file

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val dateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    /**
     * Optional platform delegate for forwarding logs to native log systems (e.g. Logcat).
     * Leave null to use only the in-memory log store.
     */
    var platformDelegate: ((LogLevel, String, Throwable?) -> Unit)? = null

    // ---- Persistent file logging ----

    @Volatile
    private var logFile: File? = null
    private val fileLock = Any()

    /**
     * Enable crash-persistent logging into logsDir/app.log.
     * The file is never cleared on start: a session separator is appended instead,
     * and if the file exceeds ~1MB it is trimmed to its newest half.
     * Call once, as early as possible in app startup.
     */
    fun initPersistentLog(logsDir: File) {
        try {
            logsDir.mkdirs()
            val file = File(logsDir, "app.log")
            trimLogFileIfNeeded(file)
            synchronized(fileLock) { logFile = file }
            appendLineToFile("=== App start ${Instant.now()} ===")
        } catch (_: Throwable) {
            // Persistence must never break the app
        }
    }

    /** Write a FATAL uncaught-exception record synchronously to the persistent log file. */
    fun logFatal(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = dateFormatter.format(Instant.now())
        appendLineToFile("$timestamp [FATAL] uncaught exception in thread ${thread.name}\n$sw")
    }

    /** Read the persistent log file content (empty string if unavailable). */
    fun readPersistentLog(): String {
        val file = logFile ?: return ""
        return try {
            synchronized(fileLock) { if (file.exists()) file.readText() else "" }
        } catch (_: Throwable) {
            ""
        }
    }

    /** Clear the persistent log file content (keeps the file itself). */
    fun clearPersistentLog() {
        try {
            synchronized(fileLock) { logFile?.writeText("") }
        } catch (_: Throwable) {
        }
    }

    private fun trimLogFileIfNeeded(file: File) {
        try {
            if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                val text = file.readText()
                val mid = text.length / 2
                // Avoid cutting mid-line: start from the next newline
                val startAt = text.indexOf('\n', mid).let { if (it == -1) mid else it + 1 }
                file.writeText("... (older logs trimmed) ...\n" + text.substring(startAt))
            }
        } catch (_: Throwable) {
        }
    }

    private fun appendLineToFile(line: String) {
        val file = logFile ?: return
        try {
            synchronized(fileLock) { file.appendText(line + "\n") }
        } catch (_: Throwable) {
            // Persistence must never break the app
        }
    }

    // ---- Public logging API ----

    /**
     * Log a debug message.
     */
    fun d(message: String) {
        log(message, LogLevel.DEBUG)
        platformDelegate?.invoke(LogLevel.DEBUG, message, null)
    }

    /**
     * Log an info message.
     */
    fun i(message: String) {
        log(message, LogLevel.INFO)
        platformDelegate?.invoke(LogLevel.INFO, message, null)
    }

    /**
     * Log a warning message.
     */
    fun w(message: String) {
        log(message, LogLevel.WARNING)
        platformDelegate?.invoke(LogLevel.WARNING, message, null)
    }

    /**
     * Log an error message.
     */
    fun e(message: String) {
        log(message, LogLevel.ERROR)
        platformDelegate?.invoke(LogLevel.ERROR, message, null)
    }

    /**
     * Log an error message with exception.
     */
    fun e(message: String, throwable: Throwable) {
        log("$message: ${throwable.message}", LogLevel.ERROR)
        // Persist the full stack trace to the file for post-mortem diagnostics
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            appendLineToFile(sw.toString().trimEnd())
        } catch (_: Throwable) {
        }
        platformDelegate?.invoke(LogLevel.ERROR, message, throwable)
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

        // Crash-persistent: synchronous immediate append to the log file
        appendLineToFile("$timestamp [${level.name}] $message")
    }

}
