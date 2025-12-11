package com.example.ApI.tools

import com.example.ApI.data.model.EnabledGoogleServices
import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.data.network.GmailApiService
import com.example.ApI.data.network.GoogleCalendarApiService
import com.example.ApI.data.network.GoogleDriveApiService
import com.example.ApI.tools.github.*
import com.example.ApI.tools.google.gmail.*
import com.example.ApI.tools.google.calendar.*
import com.example.ApI.tools.google.drive.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * Registry that manages all available tools in the application
 */
class ToolRegistry {
    companion object {
        @Volatile
        private var INSTANCE: ToolRegistry? = null

        fun getInstance(): ToolRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ToolRegistry().also { INSTANCE = it }
            }
        }

        // GitHub tool IDs for easy reference
        const val GITHUB_READ_FILE = "github_read_file"
        const val GITHUB_WRITE_FILE = "github_write_file"
        const val GITHUB_LIST_FILES = "github_list_files"
        const val GITHUB_SEARCH_CODE = "github_search_code"
        const val GITHUB_CREATE_BRANCH = "github_create_branch"
        const val GITHUB_CREATE_PR = "github_create_pr"
        const val GITHUB_GET_REPO_INFO = "github_get_repo_info"
        const val GITHUB_LIST_REPOS = "github_list_repositories"

        // Google Workspace tool IDs
        const val GMAIL_READ_EMAIL = "gmail_read_email"
        const val GMAIL_SEND_EMAIL = "gmail_send_email"
        const val GMAIL_SEARCH_EMAILS = "gmail_search_emails"
        const val CALENDAR_LIST_EVENTS = "calendar_list_events"
        const val CALENDAR_CREATE_EVENT = "calendar_create_event"
        const val CALENDAR_GET_EVENT = "calendar_get_event"
        const val DRIVE_LIST_FILES = "drive_list_files"
        const val DRIVE_READ_FILE = "drive_read_file"
        const val DRIVE_SEARCH_FILES = "drive_search_files"
        const val DRIVE_UPLOAD_FILE = "drive_upload_file"
        const val DRIVE_CREATE_FOLDER = "drive_create_folder"
        const val DRIVE_DELETE_FILE = "drive_delete_file"
    }

    private val tools = mutableMapOf<String, Tool>()
    private var githubToolsRegistered = false
    private var googleWorkspaceToolsRegistered = false

    init {
        // Register all available tools
        registerTool(DateTimeTool())
        // Future tools can be registered here:
        // registerTool(WeatherTool())
        // registerTool(CalculatorTool())
    }

    /**
     * Register GitHub tools with authentication
     * This should be called when GitHub authentication is available
     * @param apiService The GitHub API service
     * @param accessToken The GitHub access token
     * @param githubUsername The authenticated GitHub username
     */
    fun registerGitHubTools(apiService: GitHubApiService, accessToken: String, githubUsername: String) {
        // Remove existing GitHub tools if any
        unregisterGitHubTools()

        // Register GitHub tools with authentication
        registerTool(GitHubReadFileTool(apiService, accessToken, githubUsername))
        registerTool(GitHubWriteFileTool(apiService, accessToken, githubUsername))
        registerTool(GitHubListFilesTool(apiService, accessToken, githubUsername))
        registerTool(GitHubSearchCodeTool(apiService, accessToken, githubUsername))
        registerTool(GitHubCreateBranchTool(apiService, accessToken, githubUsername))
        registerTool(GitHubCreatePRTool(apiService, accessToken, githubUsername))
        registerTool(GitHubGetRepoInfoTool(apiService, accessToken, githubUsername))
        registerTool(GitHubListRepositoriesTool(apiService, accessToken, githubUsername))

        githubToolsRegistered = true
    }

    /**
     * Unregister all GitHub tools
     * This should be called when GitHub authentication is removed/expired
     */
    fun unregisterGitHubTools() {
        tools.remove(GITHUB_READ_FILE)
        tools.remove(GITHUB_WRITE_FILE)
        tools.remove(GITHUB_LIST_FILES)
        tools.remove(GITHUB_SEARCH_CODE)
        tools.remove(GITHUB_CREATE_BRANCH)
        tools.remove(GITHUB_CREATE_PR)
        tools.remove(GITHUB_GET_REPO_INFO)
        tools.remove(GITHUB_LIST_REPOS)

        githubToolsRegistered = false
    }

    /**
     * Check if GitHub tools are currently registered
     */
    fun areGitHubToolsRegistered(): Boolean = githubToolsRegistered

    /**
     * Get all GitHub tool IDs
     */
    fun getGitHubToolIds(): List<String> = listOf(
        GITHUB_READ_FILE,
        GITHUB_WRITE_FILE,
        GITHUB_LIST_FILES,
        GITHUB_SEARCH_CODE,
        GITHUB_CREATE_BRANCH,
        GITHUB_CREATE_PR,
        GITHUB_GET_REPO_INFO,
        GITHUB_LIST_REPOS
    )

    /**
     * Register Google Workspace tools with authentication
     * @param gmailService Gmail API service (null if Gmail disabled)
     * @param calendarService Calendar API service (null if Calendar disabled)
     * @param driveService Drive API service (null if Drive disabled)
     * @param googleEmail The authenticated Google email
     * @param enabledServices Which services are enabled
     */
    fun registerGoogleWorkspaceTools(
        gmailService: GmailApiService?,
        calendarService: GoogleCalendarApiService?,
        driveService: GoogleDriveApiService?,
        googleEmail: String,
        enabledServices: EnabledGoogleServices
    ) {
        // Remove existing Google Workspace tools if any
        unregisterGoogleWorkspaceTools()

        // Register Gmail tools if enabled
        if (enabledServices.gmail && gmailService != null) {
            registerTool(GmailReadEmailTool(gmailService, googleEmail))
            registerTool(GmailSendEmailTool(gmailService, googleEmail))
            registerTool(GmailSearchEmailsTool(gmailService, googleEmail))
        }

        // Register Calendar tools if enabled
        if (enabledServices.calendar && calendarService != null) {
            registerTool(CalendarListEventsTool(calendarService, googleEmail))
            registerTool(CalendarCreateEventTool(calendarService, googleEmail))
            registerTool(CalendarGetEventTool(calendarService, googleEmail))
        }

        // Register Drive tools if enabled
        if (enabledServices.drive && driveService != null) {
            registerTool(DriveListFilesTool(driveService, googleEmail))
            registerTool(DriveReadFileTool(driveService, googleEmail))
            registerTool(DriveSearchFilesTool(driveService, googleEmail))
            registerTool(DriveUploadFileTool(driveService, googleEmail))
            registerTool(DriveCreateFolderTool(driveService, googleEmail))
            registerTool(DriveDeleteFileTool(driveService, googleEmail))
        }

        googleWorkspaceToolsRegistered = enabledServices.hasAnyEnabled()
    }

    /**
     * Unregister all Google Workspace tools
     */
    fun unregisterGoogleWorkspaceTools() {
        // Gmail tools
        tools.remove(GMAIL_READ_EMAIL)
        tools.remove(GMAIL_SEND_EMAIL)
        tools.remove(GMAIL_SEARCH_EMAILS)

        // Calendar tools
        tools.remove(CALENDAR_LIST_EVENTS)
        tools.remove(CALENDAR_CREATE_EVENT)
        tools.remove(CALENDAR_GET_EVENT)

        // Drive tools
        tools.remove(DRIVE_LIST_FILES)
        tools.remove(DRIVE_READ_FILE)
        tools.remove(DRIVE_SEARCH_FILES)
        tools.remove(DRIVE_UPLOAD_FILE)
        tools.remove(DRIVE_CREATE_FOLDER)
        tools.remove(DRIVE_DELETE_FILE)

        googleWorkspaceToolsRegistered = false
    }

    /**
     * Check if Google Workspace tools are currently registered
     */
    fun areGoogleWorkspaceToolsRegistered(): Boolean = googleWorkspaceToolsRegistered

    /**
     * Get Gmail tool IDs
     */
    fun getGmailToolIds(): List<String> = listOf(
        GMAIL_READ_EMAIL,
        GMAIL_SEND_EMAIL,
        GMAIL_SEARCH_EMAILS
    )

    /**
     * Get Calendar tool IDs
     */
    fun getCalendarToolIds(): List<String> = listOf(
        CALENDAR_LIST_EVENTS,
        CALENDAR_CREATE_EVENT,
        CALENDAR_GET_EVENT
    )

    /**
     * Get Drive tool IDs
     */
    fun getDriveToolIds(): List<String> = listOf(
        DRIVE_LIST_FILES,
        DRIVE_READ_FILE,
        DRIVE_SEARCH_FILES,
        DRIVE_UPLOAD_FILE,
        DRIVE_CREATE_FOLDER,
        DRIVE_DELETE_FILE
    )

    /**
     * Get all Google Workspace tool IDs
     */
    fun getGoogleWorkspaceToolIds(): List<String> =
        getGmailToolIds() + getCalendarToolIds() + getDriveToolIds()
    
    /**
     * Register a tool with the registry
     */
    fun registerTool(tool: Tool) {
        tools[tool.id] = tool
    }
    
    /**
     * Get a tool by its ID
     */
    fun getTool(id: String): Tool? = tools[id]

    /**
     * Get the display name for a tool by its ID
     * This is the authoritative source for tool names shown in the UI
     * @param toolId The tool ID (often same as tool name from provider)
     * @return The tool's display name, or the toolId if tool not found
     */
    fun getToolDisplayName(toolId: String): String {
        return tools[toolId]?.name ?: toolId
    }

    /**
     * Get all registered tools
     */
    fun getAllTools(): List<Tool> = tools.values.toList()
    
    /**
     * Get tools that are enabled for the given provider
     * @param enabledToolIds List of tool IDs that are enabled by user
     * @param provider The provider name ("openai", "poe", "google")
     */
    fun getEnabledToolsSpecifications(enabledToolIds: List<String>, provider: String): List<ToolSpecification> {
        return enabledToolIds.mapNotNull { toolId ->
            tools[toolId]?.getSpecification(provider)
        }
    }
    
    /**
     * Execute a tool call
     * @param toolCall Information about the tool call from the model
     * @param enabledToolIds List of tool IDs that are currently enabled
     */
    suspend fun executeTool(toolCall: ToolCall, enabledToolIds: List<String>): ToolExecutionResult {
        // Check if tool is enabled
        if (toolCall.toolId !in enabledToolIds) {
            return ToolExecutionResult.Error("Tool '${toolCall.toolId}' is not enabled")
        }
        
        // Get the tool
        val tool = tools[toolCall.toolId] 
            ?: return ToolExecutionResult.Error("Tool '${toolCall.toolId}' not found")
        
        // Execute the tool
        return try {
            tool.execute(toolCall.parameters)
        } catch (e: Exception) {
            ToolExecutionResult.Error("Failed to execute tool '${toolCall.toolId}': ${e.message}")
        }
    }
    
    /**
     * Parse a tool call from model response for the given provider
     * @param provider The provider name
     * @param responseData The response data containing tool call information
     */
    fun parseToolCall(provider: String, responseData: JsonElement): ToolCall? {
        return when (provider) {
            "openai" -> parseOpenAIToolCall(responseData)
            "poe" -> parsePoeToolCall(responseData)
            "google" -> parseGoogleToolCall(responseData)
            else -> null
        }
    }
    
    /**
     * Parse tool call from OpenAI response format
     */
    private fun parseOpenAIToolCall(responseData: JsonElement): ToolCall? {
        // Based on providers.json OpenAI response format
        // Looking for tool_calls in the response
        try {
            if (responseData is JsonObject) {
                val toolCalls = responseData["tool_calls"]
                // TODO: Implement OpenAI tool call parsing based on response format
                // This will be implemented when we update the ApiService
            }
        } catch (e: Exception) {
            // Handle parsing error
        }
        return null
    }
    
    /**
     * Parse tool call from Poe response format (SSE events)
     */
    private fun parsePoeToolCall(responseData: JsonElement): ToolCall? {
        // Based on the Poe SSE example provided
        // Looking for tool_calls in delta choices
        try {
            if (responseData is JsonObject) {
                val choices = responseData["choices"]
                // TODO: Implement Poe tool call parsing based on SSE format
                // This will be implemented when we update the ApiService
            }
        } catch (e: Exception) {
            // Handle parsing error
        }
        return null
    }
    
    /**
     * Parse tool call from Google response format
     */
    private fun parseGoogleToolCall(responseData: JsonElement): ToolCall? {
        // Based on providers.json Google response format
        // TODO: Implement Google tool call parsing based on response format
        // This will be implemented when we update the ApiService
        return null
    }
}
