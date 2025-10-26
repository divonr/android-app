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
    val multiMessageMode: Boolean = false,
    val childLockSettings: ChildLockSettings = ChildLockSettings(),
    val enabledTools: List<String> = emptyList(), // List of enabled tool IDs
    val githubConnections: Map<String, GitHubConnectionInfo> = emptyMap() // GitHub connections per user (username -> connection info)
)

/**
 * GitHub connection information stored in app settings
 */
@Serializable
data class GitHubConnectionInfo(
    val username: String, // App username (not GitHub username)
    val githubUsername: String, // GitHub username
    val connectedAt: Long,
    val lastUsed: Long = System.currentTimeMillis()
)

@Serializable
data class TitleGenerationSettings(
    val enabled: Boolean = true,
    val provider: String = "auto", // "auto", "openai", "poe", "google"
    val updateOnExtension: Boolean = true // Update title after 3rd model response
)

@Serializable
data class ChildLockSettings(
    val enabled: Boolean = false,
    val encryptedPassword: String = "",
    val startTime: String = "23:00", // Default start time
    val endTime: String = "07:00" // Default end time
)
