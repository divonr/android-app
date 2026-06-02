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
 */
class ExternalConnectionsManager(
    private val internalDir: File,
    private val json: Json,
    private val localStorageManager: LocalStorageManager
) {
    fun loadGitHubConnection(username: String): GitHubConnection? {
        return try {
            val file = File(internalDir, "github_auth_${username}.json")
            if (!file.exists()) return null
            json.decodeFromString<GitHubConnection>(file.readText())
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    fun saveGitHubConnection(username: String, connection: GitHubConnection) {
        try {
            val file = File(internalDir, "github_auth_${username}.json")
            file.writeText(json.encodeToString(connection))
            val settings = localStorageManager.loadAppSettings()
            val updatedConnections = settings.githubConnections.toMutableMap()
            updatedConnections[username] = GitHubConnectionInfo(username = username, githubUsername = connection.user.login, connectedAt = connection.connectedAt, lastUsed = System.currentTimeMillis())
            localStorageManager.saveAppSettings(settings.copy(githubConnections = updatedConnections))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun removeGitHubConnection(username: String) {
        try {
            File(internalDir, "github_auth_${username}.json").takeIf { it.exists() }?.delete()
            val settings = localStorageManager.loadAppSettings()
            val updatedConnections = settings.githubConnections.toMutableMap()
            updatedConnections.remove(username)
            localStorageManager.saveAppSettings(settings.copy(githubConnections = updatedConnections))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun isGitHubConnected(username: String): Boolean {
        val connection = loadGitHubConnection(username) ?: return false
        return !connection.auth.isExpired()
    }

    fun getGitHubApiService(username: String): Pair<GitHubApiService, String>? {
        val connection = loadGitHubConnection(username) ?: return null
        if (connection.auth.isExpired()) return null
        return Pair(GitHubApiService(), connection.auth.accessToken)
    }

    fun updateGitHubLastUsed(username: String) {
        try {
            val settings = localStorageManager.loadAppSettings()
            val connectionInfo = settings.githubConnections[username] ?: return
            val updatedConnections = settings.githubConnections.toMutableMap()
            updatedConnections[username] = connectionInfo.copy(lastUsed = System.currentTimeMillis())
            localStorageManager.saveAppSettings(settings.copy(githubConnections = updatedConnections))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun loadGoogleWorkspaceConnection(username: String): GoogleWorkspaceConnection? {
        return try {
            val file = File(internalDir, "google_workspace_auth_${username}.json")
            if (!file.exists()) return null
            json.decodeFromString<GoogleWorkspaceConnection>(file.readText())
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    fun saveGoogleWorkspaceConnection(username: String, connection: GoogleWorkspaceConnection) {
        try {
            val file = File(internalDir, "google_workspace_auth_${username}.json")
            file.writeText(json.encodeToString(connection))
            val settings = localStorageManager.loadAppSettings()
            val updatedConnections = settings.googleWorkspaceConnections.toMutableMap()
            updatedConnections[username] = GoogleWorkspaceConnectionInfo(username = username, googleEmail = connection.user.email, connectedAt = connection.connectedAt, lastUsed = System.currentTimeMillis())
            localStorageManager.saveAppSettings(settings.copy(googleWorkspaceConnections = updatedConnections))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun removeGoogleWorkspaceConnection(username: String) {
        try {
            File(internalDir, "google_workspace_auth_${username}.json").takeIf { it.exists() }?.delete()
            val settings = localStorageManager.loadAppSettings()
            val updatedConnections = settings.googleWorkspaceConnections.toMutableMap()
            updatedConnections.remove(username)
            localStorageManager.saveAppSettings(settings.copy(googleWorkspaceConnections = updatedConnections))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun isGoogleWorkspaceConnected(username: String): Boolean {
        val connection = loadGoogleWorkspaceConnection(username) ?: return false
        return !connection.auth.isExpired()
    }

    fun updateGoogleWorkspaceEnabledServices(username: String, services: EnabledGoogleServices) {
        try {
            val connection = loadGoogleWorkspaceConnection(username) ?: return
            saveGoogleWorkspaceConnection(username, connection.copy(enabledServices = services))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getGoogleWorkspaceApiServices(username: String): Triple<GmailApiService?, GoogleCalendarApiService?, GoogleDriveApiService?>? {
        val connection = loadGoogleWorkspaceConnection(username) ?: return null
        if (connection.auth.isExpired()) return null
        val accessToken = connection.auth.accessToken
        val userEmail = connection.user.email
        val gmailService = if (connection.enabledServices.gmail) GmailApiService(accessToken, userEmail) else null
        val calendarService = if (connection.enabledServices.calendar) GoogleCalendarApiService(accessToken, userEmail) else null
        val driveService = if (connection.enabledServices.drive) GoogleDriveApiService(accessToken, userEmail) else null
        return Triple(gmailService, calendarService, driveService)
    }

    fun updateGoogleWorkspaceLastUsed(username: String) {
        try {
            val settings = localStorageManager.loadAppSettings()
            val connectionInfo = settings.googleWorkspaceConnections[username] ?: return
            val updatedConnections = settings.googleWorkspaceConnections.toMutableMap()
            updatedConnections[username] = connectionInfo.copy(lastUsed = System.currentTimeMillis())
            localStorageManager.saveAppSettings(settings.copy(googleWorkspaceConnections = updatedConnections))
        } catch (e: Exception) { e.printStackTrace() }
    }
}
