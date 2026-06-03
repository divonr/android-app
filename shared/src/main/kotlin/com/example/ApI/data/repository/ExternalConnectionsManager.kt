package com.example.ApI.data.repository

import com.example.ApI.data.model.GitHubConnection
import com.example.ApI.data.model.GitHubConnectionInfo
import com.example.ApI.data.model.GoogleWorkspaceConnection
import com.example.ApI.data.model.GoogleWorkspaceConnectionInfo
import com.example.ApI.data.model.EnabledGoogleServices
import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.data.network.GmailApiService
import com.example.ApI.data.network.GoogleCalendarApiService
import com.example.ApI.data.network.GoogleDriveApiService
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Manages external service connections (GitHub, Google Workspace).
 * Handles loading, saving, and managing connection state.
 */
class ExternalConnectionsManager(
    private val internalDir: File,
    private val json: Json,
    private val localStorageManager: LocalStorageManager
) {
    // ==================== GitHub Integration ====================

    /**
     * Load GitHub connection for a user
     * @param username The app username
     * @return GitHubConnection or null if not connected
     */
    fun loadGitHubConnection(username: String): GitHubConnection? {
        return try {
            val file = File(internalDir, "github_auth_${username}.json")
            if (!file.exists()) return null

            val jsonString = file.readText()
            json.decodeFromString<GitHubConnection>(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Save GitHub connection for a user
     * @param username The app username
     * @param connection The GitHub connection to save
     */
    fun saveGitHubConnection(username: String, connection: GitHubConnection) {
        try {
            val file = File(internalDir, "github_auth_${username}.json")
            val jsonString = json.encodeToString(connection)
            file.writeText(jsonString)

            // Update app settings to track connection
            val settings = localStorageManager.loadAppSettings()
            val updatedConnections = settings.githubConnections.toMutableMap()
            updatedConnections[username] = GitHubConnectionInfo(
                username = username,
                githubUsername = connection.user.login,
                connectedAt = connection.connectedAt,
                lastUsed = System.currentTimeMillis()
            )
            val updatedSettings = settings.copy(githubConnections = updatedConnections)
            localStorageManager.saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Remove GitHub connection for a user
     * @param username The app username
     */
    fun removeGitHubConnection(username: String) {
        try {
            // Delete the auth file
            val file = File(internalDir, "github_auth_${username}.json")
            if (file.exists()) {
                file.delete()
            }

            // Update app settings
            val settings = localStorageManager.loadAppSettings()
            val updatedConnections = settings.githubConnections.toMutableMap()
            updatedConnections.remove(username)
            val updatedSettings = settings.copy(githubConnections = updatedConnections)
            localStorageManager.saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Check if GitHub is connected for a user
     * @param username The app username
     * @return true if connected and auth is valid
     */
    fun isGitHubConnected(username: String): Boolean {
        val connection = loadGitHubConnection(username) ?: return false
        // Check if token is expired (if applicable)
        return !connection.auth.isExpired()
    }

    /**
     * Get GitHub API service with the user's access token
     * @param username The app username
     * @return GitHubApiService or null if not connected
     */
    fun getGitHubApiService(username: String): Pair<GitHubApiService, String>? {
        val connection = loadGitHubConnection(username) ?: return null
        if (connection.auth.isExpired()) return null

        val apiService = GitHubApiService()
        return Pair(apiService, connection.auth.accessToken)
    }

    /**
     * Update the last used timestamp for GitHub connection
     * @param username The app username
     */
    fun updateGitHubLastUsed(username: String) {
        try {
            val settings = localStorageManager.loadAppSettings()
            val connectionInfo = settings.githubConnections[username] ?: return

            val updatedConnections = settings.githubConnections.toMutableMap()
            updatedConnections[username] = connectionInfo.copy(lastUsed = System.currentTimeMillis())

            val updatedSettings = settings.copy(githubConnections = updatedConnections)
            localStorageManager.saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== Google Workspace Integration ====================

    /**
     * Load Google Workspace connection for a user
     * @param username The app username
     * @return GoogleWorkspaceConnection or null if not connected
     */
    fun loadGoogleWorkspaceConnection(username: String): GoogleWorkspaceConnection? {
        return try {
            val file = File(internalDir, "google_workspace_auth_${username}.json")
            if (!file.exists()) return null

            val jsonString = file.readText()
            json.decodeFromString<GoogleWorkspaceConnection>(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Save Google Workspace connection for a user
     * @param username The app username
     * @param connection The Google Workspace connection to save
     */
    fun saveGoogleWorkspaceConnection(username: String, connection: GoogleWorkspaceConnection) {
        try {
            val file = File(internalDir, "google_workspace_auth_${username}.json")
            val jsonString = json.encodeToString(connection)
            file.writeText(jsonString)

            // Update app settings to track connection
            val settings = localStorageManager.loadAppSettings()
            val updatedConnections = settings.googleWorkspaceConnections.toMutableMap()
            updatedConnections[username] = GoogleWorkspaceConnectionInfo(
                username = username,
                googleEmail = connection.user.email,
                connectedAt = connection.connectedAt,
                lastUsed = System.currentTimeMillis()
            )
            val updatedSettings = settings.copy(googleWorkspaceConnections = updatedConnections)
            localStorageManager.saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Remove Google Workspace connection for a user
     * @param username The app username
     */
    fun removeGoogleWorkspaceConnection(username: String) {
        try {
            // Delete the auth file
            val file = File(internalDir, "google_workspace_auth_${username}.json")
            if (file.exists()) {
                file.delete()
            }

            // Update app settings
            val settings = localStorageManager.loadAppSettings()
            val updatedConnections = settings.googleWorkspaceConnections.toMutableMap()
            updatedConnections.remove(username)
            val updatedSettings = settings.copy(googleWorkspaceConnections = updatedConnections)
            localStorageManager.saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Check if Google Workspace is connected for a user
     * @param username The app username
     * @return true if connected and auth is valid
     */
    fun isGoogleWorkspaceConnected(username: String): Boolean {
        val connection = loadGoogleWorkspaceConnection(username) ?: return false
        // Check if token is expired
        return !connection.auth.isExpired()
    }

    /**
     * Update enabled services for Google Workspace connection
     * @param username The app username
     * @param services Enabled services configuration
     */
    fun updateGoogleWorkspaceEnabledServices(username: String, services: EnabledGoogleServices) {
        try {
            val connection = loadGoogleWorkspaceConnection(username) ?: return
            val updatedConnection = connection.copy(enabledServices = services)
            saveGoogleWorkspaceConnection(username, updatedConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Get Google Workspace API services with the user's access token
     * @param username The app username
     * @return Triple of (GmailApiService?, GoogleCalendarApiService?, GoogleDriveApiService?) or null if not connected
     */
    fun getGoogleWorkspaceApiServices(username: String): Triple<GmailApiService?, GoogleCalendarApiService?, GoogleDriveApiService?>? {
        val connection = loadGoogleWorkspaceConnection(username) ?: return null
        if (connection.auth.isExpired()) return null

        val accessToken = connection.auth.accessToken
        val userEmail = connection.user.email

        val gmailService = if (connection.enabledServices.gmail) {
            GmailApiService(accessToken, userEmail)
        } else null

        val calendarService = if (connection.enabledServices.calendar) {
            GoogleCalendarApiService(accessToken, userEmail)
        } else null

        val driveService = if (connection.enabledServices.drive) {
            GoogleDriveApiService(accessToken, userEmail)
        } else null

        return Triple(gmailService, calendarService, driveService)
    }

    /**
     * Update the last used timestamp for Google Workspace connection
     * @param username The app username
     */
    fun updateGoogleWorkspaceLastUsed(username: String) {
        try {
            val settings = localStorageManager.loadAppSettings()
            val connectionInfo = settings.googleWorkspaceConnections[username] ?: return

            val updatedConnections = settings.googleWorkspaceConnections.toMutableMap()
            updatedConnections[username] = connectionInfo.copy(lastUsed = System.currentTimeMillis())

            val updatedSettings = settings.copy(googleWorkspaceConnections = updatedConnections)
            localStorageManager.saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
