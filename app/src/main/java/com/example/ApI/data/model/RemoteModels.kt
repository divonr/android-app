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
    val min_points: Int? = null
)

/**
 * Cache metadata for tracking when models were last fetched
 */
@Serializable
data class ModelsCacheMetadata(
    val lastFetchTimestamp: Long,
    val version: String = "1"
)
