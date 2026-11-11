package com.example.ApI.data.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Represents an active streaming API request.
 * Links a request ID to a specific chat for proper response routing.
 */
@Serializable
data class StreamingRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val chatId: String,
    val username: String,
    val providerName: String,
    val modelName: String,
    val systemPrompt: String = "",
    val webSearchEnabled: Boolean = false,
    val status: RequestStatus = RequestStatus.PENDING,
    val createdAt: String = Instant.now().toString(),
    val accumulatedText: String = ""  // Accumulated streaming text
)

/**
 * Status of a streaming request
 */
@Serializable
enum class RequestStatus {
    PENDING,      // Request created, not yet started
    UPLOADING,    // Uploading files to provider
    STREAMING,    // Receiving SSE response
    COMPLETED,    // Successfully completed
    FAILED,       // Failed with error
    CANCELLED     // Cancelled by user
}
