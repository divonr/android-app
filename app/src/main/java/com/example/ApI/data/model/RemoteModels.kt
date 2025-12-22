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
    val output_points_per_1k: Double? = null,
    // Thinking budget configuration
    val thinking: RemoteThinkingConfig? = null,
    // Temperature configuration
    val temperature: RemoteTemperatureConfig? = null
)

/**
 * Remote thinking budget configuration for a model.
 * Only one of 'discrete' or 'continuous' should be set.
 * If neither is set, thinking is not supported for this model.
 */
@Serializable
data class RemoteThinkingConfig(
    // Discrete thinking options (e.g., "low", "medium", "high")
    val discrete: RemoteDiscreteThinking? = null,
    // Continuous token budget
    val continuous: RemoteContinuousThinking? = null
)

/**
 * Discrete thinking configuration (effort levels)
 */
@Serializable
data class RemoteDiscreteThinking(
    // Available options (e.g., ["low", "medium", "high"])
    val options: List<String>,
    // Default option
    val default: String
)

/**
 * Continuous thinking configuration (token budget)
 */
@Serializable
data class RemoteContinuousThinking(
    // Minimum tokens
    val min: Int,
    // Maximum tokens
    val max: Int,
    // Default tokens
    val default: Int,
    // Slider step size (optional, defaults to calculated value)
    val step: Int? = null,
    // Whether 0 is allowed to disable thinking entirely
    val supports_off: Boolean = false
)

/**
 * Remote temperature configuration for a model.
 * If not provided, temperature control is not available for this model.
 */
@Serializable
data class RemoteTemperatureConfig(
    // Minimum temperature value
    val min: Float,
    // Maximum temperature value
    val max: Float,
    // Default temperature value (null = use API default, don't send parameter)
    val default: Float? = null,
    // Slider step size (optional, defaults to 0.1)
    val step: Float = 0.1f
)

/**
 * Cache metadata for tracking when models were last fetched
 */
@Serializable
data class ModelsCacheMetadata(
    val lastFetchTimestamp: Long,
    val version: String = "1"
)
