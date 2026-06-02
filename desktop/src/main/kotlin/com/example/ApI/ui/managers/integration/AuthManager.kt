package com.example.ApI.ui.managers.integration

import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/**
 * Manages all third-party integrations (GitHub and Google Workspace).
 * Desktop adaptation: GitHub uses system browser for OAuth; Google Workspace
 * uses system browser for OAuth (no Android Sign-In SDK).
 */
class AuthManager(
    private val deps: ManagerDependencies,
    private val updateAppSettings: (AppSettings) -> Unit,
    private val showSnackbar: (String) -> Unit
) {

    // ==================== GitHub Integration ====================

    fun connectGitHub(): String {
        val oauthService = com.example.ApI.data.network.GitHubOAuthService(deps.context)
        return oauthService.startAuthorizationFlow()
    }

    fun getGitHubAuthUrl(): Pair<String, String> {
        val oauthService = com.example.ApI.data.network.GitHubOAuthService(deps.context)
        return oauthService.getAuthorizationUrlAndState()
    }

    /** Open GitHub auth URL in system browser (desktop equivalent of WebView). */
    fun openGitHubAuthInBrowser() {
        try {
            val (url, _) = getGitHubAuthUrl()
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (e: Exception) {
            showSnackbar("Failed to open browser: ${e.message}")
        }
    }

    fun handleGitHubCallback(code: String, state: String): String {
        deps.scope.launch {
            try {
                val oauthService = com.example.ApI.data.network.GitHubOAuthService(deps.context)
                val authResult = oauthService.exchangeCodeForToken(code)

                authResult.fold(
                    onSuccess = { auth ->
                        val apiService = com.example.ApI.data.network.GitHubApiService()
                        val userResult = apiService.getAuthenticatedUser(auth.accessToken)
                        userResult.fold(
                            onSuccess = { user ->
                                val connection = GitHubConnection(auth = auth, user = user)
                                val username = deps.appSettings.value.current_user
                                deps.repository.saveGitHubConnection(username, connection)

                                val toolRegistry = ToolRegistry.getInstance()
                                toolRegistry.registerGitHubTools(apiService, auth.accessToken, user.login)

                                val freshSettings = deps.repository.loadAppSettings()
                                val githubToolIds = toolRegistry.getGitHubToolIds()
                                val updatedEnabledTools = (freshSettings.enabledTools + githubToolIds).distinct()
                                val updatedSettings = freshSettings.copy(enabledTools = updatedEnabledTools)
                                deps.repository.saveAppSettings(updatedSettings)
                                updateAppSettings(updatedSettings)
                                showSnackbar("GitHub connected as ${user.login}")
                            },
                            onFailure = { error -> showSnackbar("Failed to get GitHub user info: ${error.message}") }
                        )
                    },
                    onFailure = { error -> showSnackbar("GitHub authentication failed: ${error.message}") }
                )
            } catch (e: Exception) {
                showSnackbar("Error connecting to GitHub: ${e.message}")
            }
        }
        return "Processing GitHub connection..."
    }

    fun disconnectGitHub() {
        deps.scope.launch {
            try {
                val username = deps.appSettings.value.current_user
                val connection = deps.repository.loadGitHubConnection(username)
                if (connection != null) {
                    val oauthService = com.example.ApI.data.network.GitHubOAuthService(deps.context)
                    oauthService.revokeToken(connection.auth.accessToken)
                }
                deps.repository.removeGitHubConnection(username)
                val toolRegistry = ToolRegistry.getInstance()
                toolRegistry.unregisterGitHubTools()
                val freshSettings = deps.repository.loadAppSettings()
                val githubToolIds = toolRegistry.getGitHubToolIds()
                val updatedEnabledTools = freshSettings.enabledTools.filter { it !in githubToolIds }
                val updatedSettings = freshSettings.copy(enabledTools = updatedEnabledTools)
                deps.repository.saveAppSettings(updatedSettings)
                updateAppSettings(updatedSettings)
            } catch (e: Exception) {
                showSnackbar("Error disconnecting GitHub: ${e.message}")
            }
        }
    }

    fun isGitHubConnected(): Boolean {
        val username = deps.appSettings.value.current_user
        return deps.repository.isGitHubConnected(username)
    }

    fun getGitHubConnection(): GitHubConnection? {
        val username = deps.appSettings.value.current_user
        return deps.repository.loadGitHubConnection(username)
    }

    fun initializeGitHubToolsIfConnected() {
        deps.scope.launch {
            try {
                val username = deps.appSettings.value.current_user
                val serviceAndToken = deps.repository.getGitHubApiService(username)
                if (serviceAndToken != null) {
                    val (apiService, accessToken) = serviceAndToken
                    val connection = deps.repository.loadGitHubConnection(username)
                    if (connection != null) {
                        val toolRegistry = ToolRegistry.getInstance()
                        toolRegistry.registerGitHubTools(apiService, accessToken, connection.user.login)
                        val currentSettings = deps.appSettings.value
                        val githubToolIds = toolRegistry.getGitHubToolIds()
                        if (!currentSettings.enabledTools.containsAll(githubToolIds)) {
                            val updatedEnabledTools = (currentSettings.enabledTools + githubToolIds).distinct()
                            val updatedSettings = currentSettings.copy(enabledTools = updatedEnabledTools)
                            deps.repository.saveAppSettings(updatedSettings)
                            updateAppSettings(updatedSettings)
                        }
                    }
                }
            } catch (e: Exception) {
                // Silent fail
            }
        }
    }

    // ==================== Google Workspace Integration ====================
    // Desktop: No Android Google Sign-In SDK. We provide a stub that
    // shows a message directing the user to configure tokens manually.

    fun isGoogleWorkspaceConnected(): Boolean {
        val username = deps.appSettings.value.current_user
        return deps.repository.isGoogleWorkspaceConnected(username)
    }

    fun getGoogleWorkspaceConnection(): GoogleWorkspaceConnection? {
        val username = deps.appSettings.value.current_user
        return deps.repository.loadGoogleWorkspaceConnection(username)
    }

    /** Desktop: open Google OAuth consent in system browser. */
    fun connectGoogleWorkspace() {
        showSnackbar("Google Workspace OAuth: please use the Android app to connect, then sync your data.")
    }

    fun disconnectGoogleWorkspace() {
        deps.scope.launch {
            try {
                val username = deps.appSettings.value.current_user
                deps.repository.removeGoogleWorkspaceConnection(username)

                val toolRegistry = ToolRegistry.getInstance()
                toolRegistry.unregisterGoogleWorkspaceTools()

                val freshSettings = deps.repository.loadAppSettings()
                val googleToolIds = toolRegistry.getGoogleWorkspaceToolIds()
                val updatedEnabledTools = freshSettings.enabledTools.filter { it !in googleToolIds }
                val updatedSettings = freshSettings.copy(enabledTools = updatedEnabledTools)
                deps.repository.saveAppSettings(updatedSettings)
                updateAppSettings(updatedSettings)
            } catch (e: Exception) {
                showSnackbar("Error disconnecting Google Workspace: ${e.message}")
            }
        }
    }

    fun updateGoogleWorkspaceServices(gmail: Boolean, calendar: Boolean, drive: Boolean) {
        deps.scope.launch {
            try {
                val username = deps.appSettings.value.current_user
                val services = EnabledGoogleServices(gmail, calendar, drive)
                deps.repository.updateGoogleWorkspaceEnabledServices(username, services)
                initializeGoogleWorkspaceToolsIfConnected()
            } catch (e: Exception) {
                showSnackbar("Error updating Google services: ${e.message}")
            }
        }
    }

    fun initializeGoogleWorkspaceToolsIfConnected() {
        deps.scope.launch {
            try {
                val username = deps.appSettings.value.current_user
                val connection = deps.repository.loadGoogleWorkspaceConnection(username) ?: return@launch
                if (connection.auth.isExpired()) return@launch

                val apiServices = deps.repository.getGoogleWorkspaceApiServices(username) ?: return@launch
                val (gmailService, calendarService, driveService) = apiServices

                val toolRegistry = ToolRegistry.getInstance()
                toolRegistry.registerGoogleWorkspaceTools(
                    gmailService = gmailService,
                    calendarService = calendarService,
                    driveService = driveService,
                    googleEmail = connection.user.email,
                    enabledServices = connection.enabledServices
                )

                val enabledGoogleToolIds = mutableListOf<String>()
                if (connection.enabledServices.gmail) enabledGoogleToolIds.addAll(toolRegistry.getGmailToolIds())
                if (connection.enabledServices.calendar) enabledGoogleToolIds.addAll(toolRegistry.getCalendarToolIds())
                if (connection.enabledServices.drive) enabledGoogleToolIds.addAll(toolRegistry.getDriveToolIds())

                val allGoogleToolIds = toolRegistry.getGoogleWorkspaceToolIds()
                val freshSettings = deps.repository.loadAppSettings()
                val cleanedTools = freshSettings.enabledTools.filter { it !in allGoogleToolIds }
                val updatedEnabledTools = (cleanedTools + enabledGoogleToolIds).distinct()
                val updatedSettings = freshSettings.copy(enabledTools = updatedEnabledTools)
                deps.repository.saveAppSettings(updatedSettings)
                updateAppSettings(updatedSettings)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
