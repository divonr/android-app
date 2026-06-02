package com.example.ApI.tools

import com.example.ApI.data.model.EnabledGoogleServices
import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.data.network.GmailApiService
import com.example.ApI.data.network.GoogleCalendarApiService
import com.example.ApI.data.network.GoogleDriveApiService
import com.example.ApI.data.repository.SkillsStorageManager
import com.example.ApI.tools.github.*
import com.example.ApI.tools.google.gmail.*
import com.example.ApI.tools.google.calendar.*
import com.example.ApI.tools.google.drive.*
import com.example.ApI.tools.skills.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * Registry that manages all available tools in the application.
 */
class ToolRegistry {
    companion object {
        @Volatile private var INSTANCE: ToolRegistry? = null
        fun getInstance(): ToolRegistry = INSTANCE ?: synchronized(this) { INSTANCE ?: ToolRegistry().also { INSTANCE = it } }

        const val GITHUB_READ_FILE = "github_read_file"
        const val GITHUB_WRITE_FILE = "github_write_file"
        const val GITHUB_LIST_FILES = "github_list_files"
        const val GITHUB_SEARCH_CODE = "github_search_code"
        const val GITHUB_CREATE_BRANCH = "github_create_branch"
        const val GITHUB_CREATE_PR = "github_create_pr"
        const val GITHUB_GET_REPO_INFO = "github_get_repo_info"
        const val GITHUB_LIST_REPOS = "github_list_repositories"

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

        const val PYTHON_INTERPRETER = "python_interpreter"

        const val SKILL_READ = "read_skill"
        const val SKILL_READ_FILE = "read_skill_file"
        const val SKILL_WRITE_FILE = "write_skill_file"
        const val SKILL_EDIT_FILE = "edit_skill_file"
    }

    private val tools = mutableMapOf<String, Tool>()
    private var githubToolsRegistered = false
    private var googleWorkspaceToolsRegistered = false
    private var skillToolsRegistered = false

    init {
        registerTool(DateTimeTool())
    }

    fun registerGitHubTools(apiService: GitHubApiService, accessToken: String, githubUsername: String) {
        unregisterGitHubTools()
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

    fun unregisterGitHubTools() {
        listOf(GITHUB_READ_FILE, GITHUB_WRITE_FILE, GITHUB_LIST_FILES, GITHUB_SEARCH_CODE,
            GITHUB_CREATE_BRANCH, GITHUB_CREATE_PR, GITHUB_GET_REPO_INFO, GITHUB_LIST_REPOS).forEach { tools.remove(it) }
        githubToolsRegistered = false
    }

    fun areGitHubToolsRegistered(): Boolean = githubToolsRegistered

    fun getGitHubToolIds(): List<String> = listOf(GITHUB_READ_FILE, GITHUB_WRITE_FILE, GITHUB_LIST_FILES,
        GITHUB_SEARCH_CODE, GITHUB_CREATE_BRANCH, GITHUB_CREATE_PR, GITHUB_GET_REPO_INFO, GITHUB_LIST_REPOS)

    fun registerGoogleWorkspaceTools(
        gmailService: GmailApiService?,
        calendarService: GoogleCalendarApiService?,
        driveService: GoogleDriveApiService?,
        googleEmail: String,
        enabledServices: EnabledGoogleServices
    ) {
        unregisterGoogleWorkspaceTools()
        if (enabledServices.gmail && gmailService != null) {
            registerTool(GmailReadEmailTool(gmailService, googleEmail))
            registerTool(GmailSendEmailTool(gmailService, googleEmail))
            registerTool(GmailSearchEmailsTool(gmailService, googleEmail))
        }
        if (enabledServices.calendar && calendarService != null) {
            registerTool(CalendarListEventsTool(calendarService, googleEmail))
            registerTool(CalendarCreateEventTool(calendarService, googleEmail))
            registerTool(CalendarGetEventTool(calendarService, googleEmail))
        }
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

    fun unregisterGoogleWorkspaceTools() {
        (getGmailToolIds() + getCalendarToolIds() + getDriveToolIds()).forEach { tools.remove(it) }
        googleWorkspaceToolsRegistered = false
    }

    fun areGoogleWorkspaceToolsRegistered(): Boolean = googleWorkspaceToolsRegistered
    fun getGmailToolIds(): List<String> = listOf(GMAIL_READ_EMAIL, GMAIL_SEND_EMAIL, GMAIL_SEARCH_EMAILS)
    fun getCalendarToolIds(): List<String> = listOf(CALENDAR_LIST_EVENTS, CALENDAR_CREATE_EVENT, CALENDAR_GET_EVENT)
    fun getDriveToolIds(): List<String> = listOf(DRIVE_LIST_FILES, DRIVE_READ_FILE, DRIVE_SEARCH_FILES, DRIVE_UPLOAD_FILE, DRIVE_CREATE_FOLDER, DRIVE_DELETE_FILE)
    fun getGoogleWorkspaceToolIds(): List<String> = getGmailToolIds() + getCalendarToolIds() + getDriveToolIds()

    fun registerSkillTools(skillsManager: SkillsStorageManager) {
        unregisterSkillTools()
        registerTool(ReadSkillTool(skillsManager))
        registerTool(ReadSkillFileTool(skillsManager))
        registerTool(WriteSkillFileTool(skillsManager))
        registerTool(EditSkillFileTool(skillsManager))
        skillToolsRegistered = true
    }

    fun unregisterSkillTools() {
        listOf(SKILL_READ, SKILL_READ_FILE, SKILL_WRITE_FILE, SKILL_EDIT_FILE).forEach { tools.remove(it) }
        skillToolsRegistered = false
    }

    fun areSkillToolsRegistered(): Boolean = skillToolsRegistered
    fun getSkillToolIds(): List<String> = listOf(SKILL_READ, SKILL_READ_FILE, SKILL_WRITE_FILE, SKILL_EDIT_FILE)

    fun registerTool(tool: Tool) { tools[tool.id] = tool }
    fun getTool(id: String): Tool? = tools[id]
    fun getToolDisplayName(toolId: String): String = tools[toolId]?.name ?: toolId
    fun getAllTools(): List<Tool> = tools.values.toList()

    fun getEnabledToolsSpecifications(enabledToolIds: List<String>, provider: String): List<ToolSpecification> =
        enabledToolIds.mapNotNull { tools[it]?.getSpecification(provider) }

    suspend fun executeTool(toolCall: ToolCall, enabledToolIds: List<String>): ToolExecutionResult {
        if (toolCall.toolId !in enabledToolIds) return ToolExecutionResult.Error("Tool '${toolCall.toolId}' is not enabled")
        val tool = tools[toolCall.toolId] ?: return ToolExecutionResult.Error("Tool '${toolCall.toolId}' not found")
        return try { tool.execute(toolCall.parameters) } catch (e: Exception) {
            ToolExecutionResult.Error("Failed to execute tool '${toolCall.toolId}': ${e.message}")
        }
    }

    fun getToolSpecifications(provider: String, excludedToolIds: List<String> = emptyList()): List<ToolSpecification> =
        tools.values.filterNot { it.id in excludedToolIds }.map { it.getSpecification(provider) }
}
