package com.example.ApI.server.runs

import com.example.ApI.data.attachments.digest
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.GoogleWorkspaceConnection
import com.example.ApI.data.model.Message
import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.data.network.GmailApiService
import com.example.ApI.data.network.GoogleCalendarApiService
import com.example.ApI.data.network.GoogleDriveApiService
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.ServerPlatformStorage
import com.example.ApI.server.attachments.UserAttachments
import com.example.ApI.server.oauth.GoogleRefreshRejectedException
import com.example.ApI.server.oauth.GoogleTokenRefresher
import com.example.ApI.server.oauth.RealGoogleTokenRefresher
import com.example.ApI.tools.GroupConversationsTool
import com.example.ApI.tools.PythonInterpreterTool
import com.example.ApI.tools.PythonToolFiles
import com.example.ApI.tools.SandboxConfig
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The tools of ONE run: a private [ToolRegistry] built for one user and one chat by
 * [UserTools.forRun].  Everything a run does with tools goes through it — the capability check,
 * the specifications sent to the provider, the display name and the execution — so a run can
 * only ever reach the tools (and the tokens) of its own user.
 *
 * The server never uses the process-wide `ToolRegistry.getInstance()`: that singleton belongs
 * to the single-user clients (Android, desktop).
 */
class RunTools internal constructor(private val registry: ToolRegistry) {

    /** The ids of [toolIds] this run has no tool for. */
    fun missing(toolIds: List<String>): List<String> = toolIds.filter { registry.getTool(it) == null }

    /** The specifications of the tools of [toolIds] this run has, in [provider]'s dialect. */
    fun specifications(toolIds: List<String>, provider: String): List<ToolSpecification> =
        registry.getEnabledToolsSpecifications(toolIds, provider)

    fun displayName(toolId: String): String = registry.getToolDisplayName(toolId)

    suspend fun execute(toolCall: ToolCall, enabledToolIds: List<String>): ToolExecutionResult =
        registry.executeTool(toolCall, enabledToolIds)

    companion object {
        /** Only the tools that need nothing of a user (date and time). */
        fun builtIn(): RunTools = RunTools(ToolRegistry())
    }
}

/**
 * Creates the tools of the integrations in a run's registry.  A seam: tests replace it so that
 * no tool calls a real API (and to see which token each tool was created with).
 */
interface IntegrationTools {
    fun registerGitHub(registry: ToolRegistry, accessToken: String, githubLogin: String)
    fun registerGoogleWorkspace(registry: ToolRegistry, connection: GoogleWorkspaceConnection)
}

/** The real tools (the same classes Android registers). */
object RealIntegrationTools : IntegrationTools {
    override fun registerGitHub(registry: ToolRegistry, accessToken: String, githubLogin: String) {
        registry.registerGitHubTools(GitHubApiService(), accessToken, githubLogin)
    }

    override fun registerGoogleWorkspace(registry: ToolRegistry, connection: GoogleWorkspaceConnection) {
        val token = connection.auth.accessToken
        val email = connection.user.email
        val services = connection.enabledServices
        registry.registerGoogleWorkspaceTools(
            gmailService = if (services.gmail) GmailApiService(token, email) else null,
            calendarService = if (services.calendar) GoogleCalendarApiService(token, email) else null,
            driveService = if (services.drive) GoogleDriveApiService(token, email) else null,
            googleEmail = email,
            enabledServices = services
        )
    }
}

/**
 * The tools one user's runs can use on this server, resolved from the user's own data at the
 * start of every run ([forRun]) — so a connection that just synced, a disconnect and a refreshed
 * token are picked up, and nothing of one user can serve another user's run.
 *
 * What a run can have (each only when the request enables one of its tool ids):
 *  - date and time: always;
 *  - GitHub: the user's `github_auth_*` connection, when its token is not expired;
 *  - Google Workspace (Gmail / Calendar / Drive, each if enabled in the connection): the user's
 *    `google_workspace_auth_*` connection, when its access token is valid — an expiring token is
 *    refreshed first when the connection has a refresh token this server can use, and the new
 *    token is saved to the connection (and so synced to the user's devices);
 *  - the group-conversations tool: when the run's chat is in a group of the user;
 *  - the Python interpreter: when the user has an attachment store (sync is on) — its input
 *    files come from the store and its output files go there ([pythonTool]).
 *
 * Not available here (their tool ids are reported as missing): the skill tools (skill folders
 * are not synced; only their enabled / source lists are).
 */
class UserTools(
    private val username: String,
    private val repository: DataRepository,
    private val integrations: IntegrationTools = RealIntegrationTools,
    private val googleRefresher: GoogleTokenRefresher = RealGoogleTokenRefresher(),
    /** The user's storage and attachment files; without them there is no Python tool. */
    private val storage: ServerPlatformStorage? = null,
    private val attachments: UserAttachments? = null,
    /** The remote executor of the Python tool and its key. */
    private val sandbox: SandboxConfig = SandboxConfig("", "")
) {
    private val log = LoggerFactory.getLogger(UserTools::class.java)

    /** One Google refresh at a time for the user (two runs starting together share its result). */
    private val refreshMutex = Mutex()

    /** A refresh token Google refused: not tried again (until the connection gets another one). */
    @Volatile
    private var rejectedRefreshToken: String? = null

    /**
     * The tools of a run of this user in [chatId] that enables [enabledToolIds].  [groupId]: the
     * chat's group as the requesting device knows it (null: what this server's copy says).
     * [messages] / [projectAttachments]: the request's own (the files the Python tool may be
     * given, in addition to the ones of this server's copy of the chat and its group).
     */
    suspend fun forRun(
        chatId: String,
        enabledToolIds: List<String>,
        groupId: String? = null,
        messages: List<Message> = emptyList(),
        projectAttachments: List<Attachment> = emptyList()
    ): RunTools {
        val registry = ToolRegistry()
        val wanted = enabledToolIds.toSet()

        if (registry.getGitHubToolIds().any { it in wanted }) {
            val connection = repository.loadGitHubConnection(username)
            if (connection != null && !connection.auth.isExpired() && connection.auth.accessToken.isNotBlank()) {
                integrations.registerGitHub(registry, connection.auth.accessToken, connection.user.login)
            }
        }

        if (registry.getGoogleWorkspaceToolIds().any { it in wanted }) {
            usableGoogleConnection()?.let { integrations.registerGoogleWorkspace(registry, it) }
        }

        if (GROUP_CONVERSATIONS_TOOL_ID in wanted) {
            groupTool(chatId, groupId)?.let { registry.registerTool(it) }
        }

        if (ToolRegistry.PYTHON_INTERPRETER in wanted) {
            pythonTool(chatId, groupId, messages, projectAttachments)?.let { registry.registerTool(it) }
        }

        return RunTools(registry)
    }

    /**
     * The group-conversations tool for a run in [chatId]: on Android it is built from the open
     * chat and its group; here from the run's chat.  The group is [groupId] when the device
     * sent it (this server's copy of a chat created a moment ago may not have arrived yet),
     * else the group of this server's copy of the chat; it must be a group of the user.
     */
    private fun groupTool(chatId: String, groupId: String?): GroupConversationsTool? {
        val history = try {
            repository.loadChatHistory(username)
        } catch (e: Exception) {
            log.warn("Reading the chats of '$username' for the group tool failed: ${e.message}")
            return null
        }
        val id = groupId?.takeIf { it.isNotBlank() }
            ?: history.chat_history.find { it.chat_id == chatId }?.group
            ?: return null
        val group = history.groups.find { it.group_id == id } ?: return null
        return GroupConversationsTool(
            loadChatHistory = { user -> repository.loadChatHistory(user) },
            username = username,
            currentChatId = chatId,
            groupId = group.group_id,
            groupName = group.group_name
        )
    }

    /**
     * The Python interpreter for a run in [chatId].  On Android it is built per request from the
     * open chat and its group and works with the attachments' device paths; here it is built for
     * the run.  Its input files are all the attachments of the request ([messages],
     * [projectAttachments]) and of this server's copy of the chat / its group that have a hash
     * (or are the user's own file here): the tool names them to the executor with the hash and
     * size recorded on them, and only a file the executor does not hold yet is fetched through
     * the user's resolver (own files, cache, store) and uploaded to it over loopback — also a
     * file that was attached on a device.  A file that has a hash but cannot be fetched fails
     * the call with its name; one without a hash that is not here is named in the result.
     * The files the code generates are kept in the user's cache under their hash, uploaded to
     * the user's store and committed, and described by hash + size only (no server path) — the
     * run attaches them to the next assistant message it saves, as Android's service does.
     *
     * null (→ the tool id is missing → the device runs the request) when the user has no
     * attachment store: a generated file could not reach the user's devices.
     */
    private fun pythonTool(
        chatId: String,
        groupId: String?,
        messages: List<Message>,
        projectAttachments: List<Attachment>
    ): PythonInterpreterTool? {
        val storage = storage ?: return null
        val attachments = attachments ?: return null
        if (!attachments.store.isConfigured) return null

        val history = try {
            repository.loadChatHistory(username)
        } catch (e: Exception) {
            log.warn("Reading the chats of '$username' for the Python tool failed: ${e.message}")
            null
        }
        val storedChat = history?.chat_history?.find { it.chat_id == chatId }
        val knownIds = storedChat?.messages?.map { it.id }?.toSet().orEmpty()
        val chat = Chat(
            chat_id = chatId,
            preview_name = storedChat?.preview_name.orEmpty(),
            // The request's messages may not have reached this server's copy yet
            messages = storedChat?.messages.orEmpty() + messages.filter { it.id !in knownIds }
        )
        val storedGroup = (groupId?.takeIf { it.isNotBlank() } ?: storedChat?.group)
            ?.let { id -> history?.groups?.find { it.group_id == id } }
        val groupFiles = (storedGroup?.group_attachments.orEmpty() + projectAttachments).distinct()
        val group = if (storedGroup == null && groupFiles.isEmpty()) null else ChatGroup(
            group_id = storedGroup?.group_id ?: groupId.orEmpty(),
            group_name = storedGroup?.group_name.orEmpty(),
            group_attachments = groupFiles
        )

        val files = object : PythonToolFiles {
            override fun isAvailable(attachment: Attachment): Boolean =
                attachment.digest != null || attachments.resolver.resolveLocal(attachment) != null

            override suspend fun inputFile(attachment: Attachment): File? =
                attachments.resolver.resolve(attachment).fileOrNull

            override suspend fun saveOutput(fileName: String, mimeType: String, bytes: ByteArray): Attachment? {
                val (file, digest) = try {
                    attachments.receive(bytes)
                } catch (e: java.io.IOException) {
                    log.warn("Keeping a generated file of '$username' failed: ${e.message}")
                    return null
                }
                // Stored + committed: the saved message references it (retried in the background
                // when the store cannot be reached; this server's cache has the file meanwhile)
                attachments.storeNow(file, digest)
                return Attachment(file_name = fileName, mime_type = mimeType, sha256 = digest.sha256, size = digest.size)
            }
        }
        return PythonInterpreterTool(storage, chat, group, sandbox, files)
    }

    /** The user's Google Workspace connection with a valid access token (refreshed if needed). */
    private suspend fun usableGoogleConnection(): GoogleWorkspaceConnection? {
        val connection = repository.loadGoogleWorkspaceConnection(username) ?: return null
        if (!connection.auth.isExpiringSoon()) return connection
        val current = refreshMutex.withLock { refreshGoogle() } ?: return null
        return current.takeIf { !it.auth.isExpired() }
    }

    /** Under [refreshMutex]: the connection after a refresh attempt (as it is when none was possible). */
    private suspend fun refreshGoogle(): GoogleWorkspaceConnection? {
        // Another run of the user (or one of the devices, through sync) may have refreshed it meanwhile
        val connection = repository.loadGoogleWorkspaceConnection(username) ?: return null
        if (!connection.auth.isExpiringSoon()) return connection
        val refreshToken = connection.auth.refreshToken?.takeIf { it.isNotBlank() } ?: return connection
        if (refreshToken == rejectedRefreshToken) return connection

        val answer = try {
            googleRefresher.refresh(refreshToken)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
        val refreshed = answer.getOrElse { error ->
            if (error is GoogleRefreshRejectedException) rejectedRefreshToken = refreshToken
            log.warn("Refreshing the Google Workspace token of '$username' failed: ${error.message}")
            return connection
        }
        // Saved through the synced write path: the user's devices get the valid token too
        val saved = repository.updateGoogleWorkspaceAuth(username) { auth ->
            // Unless the connection was replaced meanwhile (reconnected here or on a device)
            if (auth.refreshToken != refreshToken) auth
            else auth.copy(
                accessToken = refreshed.accessToken,
                expiresAt = refreshed.expiresAt,
                refreshToken = refreshed.refreshToken ?: auth.refreshToken,
                scopes = refreshed.scopes ?: auth.scopes
            )
        }
        log.info("Refreshed the Google Workspace token of '$username'")
        return saved
    }

    companion object {
        const val GROUP_CONVERSATIONS_TOOL_ID = "get_current_group_conversations"
    }
}
