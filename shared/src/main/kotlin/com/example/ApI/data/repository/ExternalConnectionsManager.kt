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
import com.example.ApI.util.AtomicFiles
import com.example.ApI.util.FileLocks
import java.io.File

/**
 * Manages external service connections (GitHub, Google Workspace).
 * Handles loading, saving, and managing connection state.
 *
 * Disconnecting does not delete the auth file: sync does not propagate file deletions (a pull
 * would bring the server's copy back), so the file is overwritten with [DISCONNECTED] (JSON
 * `null`) through the normal synced write path. Loaders treat it like a missing file, and the
 * merge policy treats it as an ordinary value (disconnect on one device vs. an untouched
 * connection elsewhere → disconnected).
 */
class ExternalConnectionsManager(
    private val internalDir: File,
    private val json: Json,
    private val localStorageManager: LocalStorageManager,
    private val onFileWritten: (java.io.File) -> Unit = {}
) {
    companion object {
        /** Content of an auth file whose connection was removed (see the class comment). */
        const val DISCONNECTED = "null"

        /** True when [text] (an auth file's content) holds no connection. */
        fun isDisconnected(text: String): Boolean = text.isBlank() || text.trim() == DISCONNECTED
    }

    /** Atomically write [content] to [file], then notify the sync engine. */
    private fun writeAndNotify(file: File, content: String) {
        AtomicFiles.write(file, content)
        onFileWritten(file)
    }

    private fun gitHubFile(username: String) = File(internalDir, "github_auth_${username}.json")
    private fun googleWorkspaceFile(username: String) = File(internalDir, "google_workspace_auth_${username}.json")

    /** Mark the connection stored in [file] as removed (synced like any other change). */
    private fun writeDisconnected(file: File) {
        FileLocks.withLock(file) {
            if (!file.exists() || isDisconnected(file.readText())) return
            AtomicFiles.write(file, DISCONNECTED)
        }
        onFileWritten(file)
    }

    // ==================== GitHub Integration ====================

    /**
     * Load GitHub connection for a user
     * @param username The app username
     * @return GitHubConnection or null if not connected
     */
    fun loadGitHubConnection(username: String): GitHubConnection? {
        return try {
            val file = gitHubFile(username)
            if (!file.exists()) return null

            val jsonString = file.readText()
            if (isDisconnected(jsonString)) return null
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
            val file = gitHubFile(username)
            val jsonString = json.encodeToString(connection)
            writeAndNotify(file, jsonString)

            // Update app settings to track connection
            val info = GitHubConnectionInfo(
                username = username,
                githubUsername = connection.user.login,
                connectedAt = connection.connectedAt,
                lastUsed = System.currentTimeMillis()
            )
            localStorageManager.updateAppSettings { settings ->
                settings.copy(githubConnections = settings.githubConnections + (username to info))
            }
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
            // Mark the auth file disconnected (a deletion would not sync)
            writeDisconnected(gitHubFile(username))

            // Update app settings
            localStorageManager.updateAppSettings { settings ->
                settings.copy(githubConnections = settings.githubConnections - username)
            }
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
            val now = System.currentTimeMillis()
            localStorageManager.updateAppSettings { settings ->
                val connectionInfo = settings.githubConnections[username] ?: return@updateAppSettings settings
                settings.copy(githubConnections = settings.githubConnections + (username to connectionInfo.copy(lastUsed = now)))
            }
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
            val file = googleWorkspaceFile(username)
            if (!file.exists()) return null

            val jsonString = file.readText()
            if (isDisconnected(jsonString)) return null
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
            val file = googleWorkspaceFile(username)
            val jsonString = json.encodeToString(connection)
            writeAndNotify(file, jsonString)
            trackGoogleWorkspaceConnection(username, connection)
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
            // Mark the auth file disconnected (a deletion would not sync)
            writeDisconnected(googleWorkspaceFile(username))

            // Update app settings
            localStorageManager.updateAppSettings { settings ->
                settings.copy(googleWorkspaceConnections = settings.googleWorkspaceConnections - username)
            }
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
            // Load-modify-save under the auth file's lock (a pull may be merging it)
            val file = googleWorkspaceFile(username)
            val updatedConnection = FileLocks.withLock(file) {
                val connection = loadGoogleWorkspaceConnection(username) ?: return
                connection.copy(enabledServices = services).also {
                    AtomicFiles.write(file, json.encodeToString(it))
                }
            }
            onFileWritten(file)
            trackGoogleWorkspaceConnection(username, updatedConnection)
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
            val now = System.currentTimeMillis()
            localStorageManager.updateAppSettings { settings ->
                val connectionInfo = settings.googleWorkspaceConnections[username] ?: return@updateAppSettings settings
                settings.copy(
                    googleWorkspaceConnections = settings.googleWorkspaceConnections + (username to connectionInfo.copy(lastUsed = now))
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Record [connection] in the app settings' connection list. */
    private fun trackGoogleWorkspaceConnection(username: String, connection: GoogleWorkspaceConnection) {
        val info = GoogleWorkspaceConnectionInfo(
            username = username,
            googleEmail = connection.user.email,
            connectedAt = connection.connectedAt,
            lastUsed = System.currentTimeMillis()
        )
        localStorageManager.updateAppSettings { settings ->
            settings.copy(googleWorkspaceConnections = settings.googleWorkspaceConnections + (username to info))
        }
    }
}
