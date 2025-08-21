package com.example.ApI.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiKey(
    val id: String = java.util.UUID.randomUUID().toString(),
    val provider: String,
    val key: String,
    val isActive: Boolean = true,
    val customName: String? = null
)

@Serializable
data class AppSettings(
    val current_user: String,
    val selected_provider: String,
    val selected_model: String,
    val temperature: Double = 1.0,
    val titleGenerationSettings: TitleGenerationSettings = TitleGenerationSettings(),
    val multiMessageMode: Boolean = false
)

@Serializable
data class TitleGenerationSettings(
    val enabled: Boolean = true,
    val provider: String = "auto", // "auto", "openai", "poe", "google"
    val updateOnExtension: Boolean = true // Update title after 3rd model response
)
