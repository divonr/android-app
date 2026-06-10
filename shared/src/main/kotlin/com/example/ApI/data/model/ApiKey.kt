package com.example.ApI.data.model

import kotlinx.serialization.Serializable

/**
 * Remote-sync configuration stored inside [AppSettings].
 *
 * The entire object is STRIPPED before uploading `app_settings.json` to the server so that
 * the user's sync credentials never leave the device.  When pulling, we MERGE the local copy
 * back in so the remote blob can never clobber sync settings.
 *
 * ### v2 semantics (Google Sign-In)
 * - [authToken] is now the **server-minted opaque token** returned by `POST /auth/google`,
 *   NOT a user-entered bearer token.  It is obtained via [DataRepository.signInToSync] and
 *   is never displayed or manually edited.
 * - [accountEmail] holds the Google account email for display purposes only (not used in
 *   any API call).
 * - [enabled] is set to `true` by [DataRepository.signInToSync] and `false` by
 *   [DataRepository.signOutOfSync].
 *
 * Default values make a fresh install or a pre-v2 JSON file behave identically to the
 * pre-sync codebase (`coerceInputValues = true` handles missing fields on deserialization).
 */
@Serializable
data class RemoteSyncSettings(
    val enabled: Boolean = false,
    val serverBaseUrl: String = "https://sync.api-divonr.xyz",
    /** Server-minted opaque token (NOT user-entered). Cleared on sign-out. */
    val authToken: String = "",
    /** Google account email — display only. Cleared on sign-out. */
    val accountEmail: String = "",
    val syncApiKeys: Boolean = false
)

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
    val enabledTools: List<String> = emptyList(), // List of enabled tool IDs from integrations
    val excludedToolIds: List<String> = emptyList(), // Tools excluded via chat screen shortcut (overrides enabledTools)
    val githubConnections: Map<String, GitHubConnectionInfo> = emptyMap(), // GitHub connections per user (username -> connection info)
    val googleWorkspaceConnections: Map<String, GoogleWorkspaceConnectionInfo> = emptyMap(), // Google Workspace connections per user
    val skipWelcomeScreen: Boolean = false, // Whether to skip the welcome/onboarding screen
    val starredModels: List<StarredModel> = emptyList(), // User's favorite models for quick access
    val remoteSync: RemoteSyncSettings = RemoteSyncSettings() // Remote sync configuration (stripped before upload)
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

/**
 * Represents a starred/favorite model for quick access.
 * Stores both provider and model name since the same model name
 * can exist across multiple providers.
 */
@Serializable
data class StarredModel(
    val provider: String,
    val modelName: String
)
