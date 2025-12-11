package com.example.ApI.data.model

import kotlinx.serialization.Serializable

/**
 * Data classes for parsing the remote models.json file
 * fetched from GitHub
 */

@Serializable
data class RemoteProviderModels(
    val provider: String,
    val models: List<RemoteModel>
)

@Serializable
data class RemoteModel(
    val name: String,
    // Legacy field - minimum points (deprecated, use pricing fields below)
    val min_points: Int? = null,
    // New pricing fields for Poe:
    // Fixed pricing - exact points per message
    val points: Int? = null,
    // Token-based pricing - points per 1000 input tokens
    @kotlinx.serialization.SerialName("1k_input_points")
    val input_points_per_1k: Double? = null,
    // Token-based pricing - points per 1000 output tokens
    @kotlinx.serialization.SerialName("1k_output_points")
    val output_points_per_1k: Double? = null
)

/**
 * Cache metadata for tracking when models were last fetched
 */
@Serializable
data class ModelsCacheMetadata(
    val lastFetchTimestamp: Long,
    val version: String = "1"
)
