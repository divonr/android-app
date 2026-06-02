package com.example.ApI.data.model

import kotlinx.serialization.Serializable

/**
 * Google Workspace OAuth authentication data
 */
@Serializable
data class GoogleWorkspaceAuth(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,  // Unix timestamp in milliseconds
    val scopes: List<String>,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Check if the access token is expired
     */
    fun isExpired(): Boolean {
        return System.currentTimeMillis() >= expiresAt
    }

    /**
     * Check if token will expire within the next 5 minutes
     */
    fun isExpiringSoon(): Boolean {
        return System.currentTimeMillis() + (5 * 60 * 1000) >= expiresAt
    }
}

/**
 * Google Workspace user information
 */
@Serializable
data class GoogleWorkspaceUser(
    val id: String,
    val email: String,
    val displayName: String?,
    val photoUrl: String?
)

/**
 * Enabled Google Workspace services (Gmail, Calendar, Drive)
 */
@Serializable
data class EnabledGoogleServices(
    val gmail: Boolean = true,
    val calendar: Boolean = true,
    val drive: Boolean = true
) {
    /**
     * Check if any service is enabled
     */
    fun hasAnyEnabled(): Boolean = gmail || calendar || drive

    /**
     * Get list of enabled service names
     */
    fun getEnabledServices(): List<String> {
        return buildList {
            if (gmail) add("Gmail")
            if (calendar) add("Calendar")
            if (drive) add("Drive")
        }
    }
}

/**
 * Complete Google Workspace connection state for a user
 */
@Serializable
data class GoogleWorkspaceConnection(
    val auth: GoogleWorkspaceAuth,
    val user: GoogleWorkspaceUser,
    val connectedAt: Long = System.currentTimeMillis(),
    val enabledServices: EnabledGoogleServices = EnabledGoogleServices()
)

/**
 * Google Workspace connection information stored in app settings
 */
@Serializable
data class GoogleWorkspaceConnectionInfo(
    val username: String,        // App username (not Google email)
    val googleEmail: String,      // Google account email
    val connectedAt: Long,
    val lastUsed: Long = System.currentTimeMillis()
)
