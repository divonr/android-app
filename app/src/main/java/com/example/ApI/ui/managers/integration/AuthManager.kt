package com.example.ApI.ui.managers.integration

import android.content.Intent
import com.example.ApI.data.model.*
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages all third-party integrations (GitHub and Google Workspace).
 * Handles OAuth flows, connection management, and tool registration.
 * Extracted from ChatViewModel to reduce complexity.
 */
class AuthManager(
    private val deps: ManagerDependencies,
    private val updateAppSettings: (AppSettings) -> Unit,
    private val showSnackbar: (String) -> Unit
) {

    private var googleWorkspaceAuthService: com.example.ApI.data.network.GoogleWorkspaceAuthService? = null

    // ==================== GitHub Integration ====================

    /**
     * Start GitHub OAuth flow
     * @return Authorization URL to open in browser
     */
    fun connectGitHub(): String {
        val oauthService = com.example.ApI.data.network.GitHubOAuthService(deps.context)
        return oauthService.startAuthorizationFlow()
    }

    /**
     * Get GitHub OAuth URL and state for in-app WebView authentication.
     * @return Pair of (authUrl, state)
     */
    fun getGitHubAuthUrl(): Pair<String, String> {
        val oauthService = com.example.ApI.data.network.GitHubOAuthService(deps.context)
        return oauthService.getAuthorizationUrlAndState()
    }

    /**
     * Handle OAuth callback and save connection
     * @param code Authorization code from GitHub
     * @param state State parameter for verification
     * @return Success/failure message
     */
    fun handleGitHubCallback(code: String, state: String): String {
        deps.scope.launch {
            try {
                val oauthService = com.example.ApI.data.network.GitHubOAuthService(deps.context)

                // Exchange code for token
                val authResult = oauthService.exchangeCodeForToken(code)

                authResult.fold(
                    onSuccess = { auth ->
                        // Get user info
                        val apiService = com.example.ApI.data.network.GitHubApiService()
                        val userResult = apiService.getAuthenticatedUser(auth.accessToken)

                        userResult.fold(
                            onSuccess = { user ->
                                // Save connection
                                val connection = GitHubConnection(auth = auth, user = user)
                                val username = deps.appSettings.value.current_user
                                deps.repository.saveGitHubConnection(username, connection)

                                // Register GitHub tools
                                val toolRegistry = ToolRegistry.getInstance()
                                toolRegistry.registerGitHubTools(apiService, auth.accessToken, user.login)

                                // Reload deps.appSettings to get the updated githubConnections, then add tool IDs
                                val freshSettings = deps.repository.loadAppSettings()
                                val githubToolIds = toolRegistry.getGitHubToolIds()
                                val updatedEnabledTools = (freshSettings.enabledTools + githubToolIds).distinct()
                                val updatedSettings = freshSettings.copy(enabledTools = updatedEnabledTools)
                                deps.repository.saveAppSettings(updatedSettings)
                                updateAppSettings(updatedSettings)
                            },
                            onFailure = { error ->
                                showSnackbar("Failed to get GitHub user info: ${error.message}")
                            }
                        )
                    },
                    onFailure = { error ->
                        showSnackbar("GitHub authentication failed: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                showSnackbar("Error connecting to GitHub: ${e.message}")
            }
        }
        return "Processing GitHub connection..."
    }

    /**
     * Disconnect GitHub and remove all GitHub tools
     */
    fun disconnectGitHub() {
        deps.scope.launch {
            try {
                val username = deps.appSettings.value.current_user
                val connection = deps.repository.loadGitHubConnection(username)

                if (connection != null) {
                    // Revoke token on GitHub
                    val oauthService = com.example.ApI.data.network.GitHubOAuthService(deps.context)
                    oauthService.revokeToken(connection.auth.accessToken)
                }

                // Remove local connection data
                deps.repository.removeGitHubConnection(username)

                // Unregister GitHub tools
                val toolRegistry = ToolRegistry.getInstance()
                toolRegistry.unregisterGitHubTools()

                // Reload deps.appSettings to get the updated githubConnections (with removal), then remove tool IDs
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

    /**
     * Check if GitHub is connected for current user
     */
    fun isGitHubConnected(): Boolean {
        val username = deps.appSettings.value.current_user
        return deps.repository.isGitHubConnected(username)
    }

    /**
     * Get GitHub connection info for current user
     */
    fun getGitHubConnection(): GitHubConnection? {
        val username = deps.appSettings.value.current_user
        return deps.repository.loadGitHubConnection(username)
    }

    /**
     * Initialize GitHub tools if already connected
     * Should be called during app startup
     */
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

                        // Ensure GitHub tools are enabled in settings
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
                // Silent fail - GitHub tools won't be available
            }
        }
    }

    // ==================== Google Workspace Integration ====================

    /**
     * Initialize Google Workspace auth service
     */
    private fun initGoogleWorkspaceAuthService() {
        if (googleWorkspaceAuthService == null) {
            googleWorkspaceAuthService = com.example.ApI.data.network.GoogleWorkspaceAuthService(deps.context)
        }
    }

    /**
     * Get Google Sign-In intent
     * @return Intent to launch for Google Sign-In
     */
    fun getGoogleSignInIntent(): Intent {
        initGoogleWorkspaceAuthService()
        return googleWorkspaceAuthService!!.getSignInIntent()
    }

    /**
     * Handle Google Sign-In result
     * @param data Intent data from ActivityResult
     */
    fun handleGoogleSignInResult(data: Intent) {
        deps.scope.launch {
            try {
                initGoogleWorkspaceAuthService()
                android.util.Log.d("IntegrationManager", "Handling Google Sign-In result intent")
                val result = googleWorkspaceAuthService!!.handleSignInResult(data)

                result.fold(
                    onSuccess = { (auth, user) ->
                        android.util.Log.d("IntegrationManager", "Google Sign-In success: ${user.email}")
                        // Save connection
                        val connection = GoogleWorkspaceConnection(auth = auth, user = user)
                        val username = deps.appSettings.value.current_user

                        // Save and reload settings to update UI
                        val updatedSettings = withContext(Dispatchers.IO) {
                            android.util.Log.d("IntegrationManager", "Saving connection to deps.repository")
                            deps.repository.saveGoogleWorkspaceConnection(username, connection)
                            android.util.Log.d("IntegrationManager", "Reloading app settings")
                            deps.repository.loadAppSettings()
                        }

                        updateAppSettings(updatedSettings)
                        android.util.Log.d("IntegrationManager", "Settings updated with new connection")

                        // Register tools
                        initializeGoogleWorkspaceToolsIfConnected()
                    },
                    onFailure = { error ->
                        android.util.Log.e("IntegrationManager", "Google Sign-In failed", error)
                        showSnackbar("שגיאת התחברות: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("IntegrationManager", "Exception in handleGoogleSignInResult", e)
                showSnackbar("שגיאה: ${e.message}")
            }
        }
    }

    /**
     * Check if Google Workspace is connected
     */
    fun isGoogleWorkspaceConnected(): Boolean {
        val username = deps.appSettings.value.current_user
        return deps.repository.isGoogleWorkspaceConnected(username)
    }

    /**
     * Get current Google Workspace connection
     */
    fun getGoogleWorkspaceConnection(): GoogleWorkspaceConnection? {
        val username = deps.appSettings.value.current_user
        return deps.repository.loadGoogleWorkspaceConnection(username)
    }

    /**
     * Disconnect Google Workspace
     */
    fun disconnectGoogleWorkspace() {
        deps.scope.launch {
            try {
                initGoogleWorkspaceAuthService()
                googleWorkspaceAuthService!!.signOut()

                val username = deps.appSettings.value.current_user
                deps.repository.removeGoogleWorkspaceConnection(username)

                // Unregister tools
                val toolRegistry = ToolRegistry.getInstance()
                toolRegistry.unregisterGoogleWorkspaceTools()

                // Reload deps.appSettings to get the updated googleWorkspaceConnections (with removal), then remove tool IDs
                val freshSettings = deps.repository.loadAppSettings()
                val googleToolIds = toolRegistry.getGoogleWorkspaceToolIds()
                val updatedEnabledTools = freshSettings.enabledTools.filter { it !in googleToolIds }
                val updatedSettings = freshSettings.copy(enabledTools = updatedEnabledTools)
                deps.repository.saveAppSettings(updatedSettings)
                updateAppSettings(updatedSettings)
            } catch (e: Exception) {
                showSnackbar("שגיאה בהתנתקות: ${e.message}")
            }
        }
    }

    /**
     * Update enabled Google Workspace services
     * @param gmail Enable Gmail tools
     * @param calendar Enable Calendar tools
     * @param drive Enable Drive tools
     */
    fun updateGoogleWorkspaceServices(gmail: Boolean, calendar: Boolean, drive: Boolean) {
        deps.scope.launch {
            try {
                val username = deps.appSettings.value.current_user
                val services = EnabledGoogleServices(gmail, calendar, drive)
                deps.repository.updateGoogleWorkspaceEnabledServices(username, services)

                // Re-register tools with new configuration
                initializeGoogleWorkspaceToolsIfConnected()
            } catch (e: Exception) {
                showSnackbar("שגיאה בעדכון: ${e.message}")
            }
        }
    }

    /**
     * Initialize Google Workspace tools if connected
     * Call this on app start and after connection/service changes
     */
    fun initializeGoogleWorkspaceToolsIfConnected() {
        deps.scope.launch {
            try {
                val username = deps.appSettings.value.current_user
                val connection = deps.repository.loadGoogleWorkspaceConnection(username) ?: return@launch

                if (connection.auth.isExpired()) {
                    // Token expired - user needs to reconnect
                    return@launch
                }

                // Get API services based on enabled services
                val apiServices = deps.repository.getGoogleWorkspaceApiServices(username) ?: return@launch
                val (gmailService, calendarService, driveService) = apiServices

                val toolRegistry = ToolRegistry.getInstance()

                // Register tools
                toolRegistry.registerGoogleWorkspaceTools(
                    gmailService = gmailService,
                    calendarService = calendarService,
                    driveService = driveService,
                    googleEmail = connection.user.email,
                    enabledServices = connection.enabledServices
                )

                // Add enabled Google Workspace tool IDs to deps.appSettings.enabledTools
                val enabledGoogleToolIds = mutableListOf<String>()
                if (connection.enabledServices.gmail) {
                    enabledGoogleToolIds.addAll(toolRegistry.getGmailToolIds())
                }
                if (connection.enabledServices.calendar) {
                    enabledGoogleToolIds.addAll(toolRegistry.getCalendarToolIds())
                }
                if (connection.enabledServices.drive) {
                    enabledGoogleToolIds.addAll(toolRegistry.getDriveToolIds())
                }

                // Update enabledTools: remove all Google Workspace IDs first, then add the currently enabled ones
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
