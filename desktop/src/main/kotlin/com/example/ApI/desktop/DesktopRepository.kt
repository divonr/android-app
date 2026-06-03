package com.example.ApI.desktop

import com.example.ApI.data.model.*
import com.example.ApI.data.network.GitHubApiService
import com.example.ApI.data.network.GmailApiService
import com.example.ApI.data.network.GoogleCalendarApiService
import com.example.ApI.data.network.GoogleDriveApiService
import com.example.ApI.data.network.LLMApiService
import com.example.ApI.data.repository.*
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.util.JsonConfig
import java.io.File

class DesktopRepository(private val appDir: File) {
    private val context = DesktopContext(File(appDir, "files").apply { mkdirs() })
    private val internalDir = File(context.filesDir, "llm_data").apply { mkdirs() }
    private val apiService = LLMApiService()

    private val modelsCacheManager = ModelsCacheManager(internalDir, JsonConfig.prettyPrint)
    private val localStorageManager = LocalStorageManager(internalDir, JsonConfig.prettyPrint)
    private val chatHistoryManager = ChatHistoryManager(internalDir, JsonConfig.prettyPrint)
    private val groupProjectManager = GroupProjectManager(chatHistoryManager)
    private val messageBranchingManager = MessageBranchingManager(chatHistoryManager)
    private val chatSearchService = ChatSearchService { username -> chatHistoryManager.loadChatHistory(username) }
    private val fileUploadManager = FileUploadManager(JsonConfig.prettyPrint) { username ->
        localStorageManager.loadApiKeys(username)
    }
    val skillsStorageManager = SkillsStorageManager(internalDir, JsonConfig.prettyPrint)
    private val externalConnectionsManager = ExternalConnectionsManager(internalDir, JsonConfig.prettyPrint, localStorageManager)
    private val titleGenerationService by lazy {
        TitleGenerationService(
            json = JsonConfig.prettyPrint,
            apiService = apiService,
            loadChatHistory = { username -> loadChatHistory(username) },
            loadProviders = { loadProviders() },
            loadApiKeys = { username -> localStorageManager.loadApiKeys(username) }
        )
    }

    init {
        modelsCacheManager.setCustomProvidersLoader {
            localStorageManager.loadCustomProviders(loadAppSettings().current_user)
        }
        modelsCacheManager.setFullCustomProvidersLoader {
            localStorageManager.loadFullCustomProviders(loadAppSettings().current_user)
        }
    }

    // ==================== Models ====================
    suspend fun refreshModelsIfNeeded(): Boolean = modelsCacheManager.refreshModelsIfNeeded()
    suspend fun forceRefreshModels(): Pair<Boolean, String?> = modelsCacheManager.forceRefreshModels()
    fun loadProviders(): List<Provider> = modelsCacheManager.buildProviders()

    // ==================== Chat History ====================
    fun loadChatHistory(username: String): UserChatHistory = chatHistoryManager.loadChatHistory(username)
    fun saveChatHistory(chatHistory: UserChatHistory) = chatHistoryManager.saveChatHistory(chatHistory)
    fun getChatJson(username: String, chatId: String): String? = chatHistoryManager.getChatJson(username, chatId)
    fun saveChatJsonToDownloads(chatId: String, content: String): String? = chatHistoryManager.saveChatJsonToDownloads(chatId, content)
    fun addMessageToChat(username: String, chatId: String, message: Message): Chat? = chatHistoryManager.addMessageToChat(username, chatId, message)
    fun createNewChat(username: String, previewName: String, systemPrompt: String = ""): Chat = chatHistoryManager.createNewChat(username, previewName, systemPrompt)
    fun createNewChatInGroup(username: String, previewName: String, groupId: String, systemPrompt: String = ""): Chat = chatHistoryManager.createNewChatInGroup(username, previewName, groupId, systemPrompt)
    fun updateChatSystemPrompt(username: String, chatId: String, systemPrompt: String): Chat? = chatHistoryManager.updateChatSystemPrompt(username, chatId, systemPrompt)
    fun updateChatShareLink(username: String, chatId: String, shareLink: String, shareId: String): Chat? = chatHistoryManager.updateChatShareLink(username, chatId, shareLink, shareId)
    fun replaceMessageInChat(username: String, chatId: String, oldMessage: Message, newMessage: Message): Chat? = chatHistoryManager.replaceMessageInChat(username, chatId, oldMessage, newMessage)
    fun deleteMessagesFromPoint(username: String, chatId: String, fromMessage: Message): Chat? = chatHistoryManager.deleteMessagesFromPoint(username, chatId, fromMessage)
    fun updateChatWithNewAttachments(username: String, chatId: String, updatedMessages: List<Message>) = chatHistoryManager.updateChatWithNewAttachments(username, chatId, updatedMessages)
    fun importSingleChat(jsonContent: String, username: String): String? = chatHistoryManager.importSingleChat(jsonContent, username)
    fun exportChatHistory(username: String): String? = chatHistoryManager.exportChatHistory(username)
    fun importChatHistoryJson(raw: ByteArray, targetUsername: String) = chatHistoryManager.importChatHistoryJson(raw, targetUsername)
    fun validateChatJson(jsonContent: String): Boolean = chatHistoryManager.validateChatJson(jsonContent)

    fun cleanupEmptyChats(username: String): Int {
        val chatHistory = loadChatHistory(username)
        val nonEmptyChats = chatHistory.chat_history.filter { it.messages.isNotEmpty() || it.messageNodes.isNotEmpty() }
        val removedCount = chatHistory.chat_history.size - nonEmptyChats.size
        if (removedCount > 0) saveChatHistory(chatHistory.copy(chat_history = nonEmptyChats))
        return removedCount
    }

    // ==================== Groups ====================
    fun createNewGroup(username: String, groupName: String): ChatGroup = groupProjectManager.createNewGroup(username, groupName)
    fun deleteGroup(username: String, groupId: String): Boolean = groupProjectManager.deleteGroup(username, groupId)
    fun renameGroup(username: String, groupId: String, newName: String): Boolean = groupProjectManager.renameGroup(username, groupId, newName)
    fun addChatToGroup(username: String, chatId: String, groupId: String): Boolean = groupProjectManager.addChatToGroup(username, chatId, groupId)
    fun removeChatFromGroup(username: String, chatId: String): Boolean = groupProjectManager.removeChatFromGroup(username, chatId)
    fun updateGroupProjectStatus(username: String, groupId: String, isProject: Boolean): Boolean = groupProjectManager.updateGroupProjectStatus(username, groupId, isProject)
    fun addAttachmentToGroup(username: String, groupId: String, attachment: Attachment): Boolean = groupProjectManager.addAttachmentToGroup(username, groupId, attachment)
    fun removeAttachmentFromGroup(username: String, groupId: String, attachmentIndex: Int): Boolean = groupProjectManager.removeAttachmentFromGroup(username, groupId, attachmentIndex)

    // ==================== API Keys & Settings ====================
    fun loadApiKeys(username: String): List<ApiKey> = localStorageManager.loadApiKeys(username)
    fun saveApiKeys(username: String, apiKeys: List<ApiKey>) = localStorageManager.saveApiKeys(username, apiKeys)
    fun addApiKey(username: String, apiKey: ApiKey) = localStorageManager.addApiKey(username, apiKey)
    fun toggleApiKeyStatus(username: String, keyId: String) = localStorageManager.toggleApiKeyStatus(username, keyId)
    fun deleteApiKey(username: String, keyId: String) = localStorageManager.deleteApiKey(username, keyId)
    fun reorderApiKeys(username: String, fromIndex: Int, toIndex: Int) = localStorageManager.reorderApiKeys(username, fromIndex, toIndex)
    fun loadAppSettings(): AppSettings = localStorageManager.loadAppSettings()
    fun saveAppSettings(settings: AppSettings) = localStorageManager.saveAppSettings(settings)
    fun saveFileLocally(fileName: String, data: ByteArray): String? = localStorageManager.saveFileLocally(fileName, data)
    fun deleteFile(filePath: String): Boolean = localStorageManager.deleteFile(filePath)

    // ==================== Custom Providers ====================
    fun loadCustomProviders(username: String): List<CustomProviderConfig> = localStorageManager.loadCustomProviders(username)
    fun saveCustomProviders(username: String, providers: List<CustomProviderConfig>) {
        localStorageManager.saveCustomProviders(username, providers)
        apiService.reloadCustomProviders(providers.filter { it.isEnabled })
    }
    fun addCustomProvider(username: String, provider: CustomProviderConfig) {
        localStorageManager.addCustomProvider(username, provider)
        if (provider.isEnabled) apiService.registerCustomProvider(provider)
    }
    fun updateCustomProvider(username: String, providerId: String, updated: CustomProviderConfig) {
        localStorageManager.updateCustomProvider(username, providerId, updated)
        apiService.reloadCustomProviders(loadCustomProviders(username).filter { it.isEnabled })
    }
    fun deleteCustomProvider(username: String, providerId: String) {
        val toDelete = localStorageManager.loadCustomProviders(username).find { it.id == providerId }
        localStorageManager.deleteCustomProvider(username, providerId)
        toDelete?.let { apiService.unregisterCustomProvider(it.providerKey) }
    }
    fun initializeCustomProviders(username: String) {
        apiService.reloadCustomProviders(localStorageManager.loadCustomProviders(username).filter { it.isEnabled })
    }

    fun loadFullCustomProviders(username: String): List<FullCustomProviderConfig> = localStorageManager.loadFullCustomProviders(username)
    fun saveFullCustomProviders(username: String, providers: List<FullCustomProviderConfig>) {
        localStorageManager.saveFullCustomProviders(username, providers)
        apiService.reloadFullCustomProviders(providers.filter { it.isEnabled })
    }
    fun addFullCustomProvider(username: String, provider: FullCustomProviderConfig) {
        localStorageManager.addFullCustomProvider(username, provider)
        if (provider.isEnabled) apiService.registerFullCustomProvider(provider)
    }
    fun updateFullCustomProvider(username: String, providerId: String, updated: FullCustomProviderConfig) {
        localStorageManager.updateFullCustomProvider(username, providerId, updated)
        apiService.reloadFullCustomProviders(loadFullCustomProviders(username).filter { it.isEnabled })
    }
    fun deleteFullCustomProvider(username: String, providerId: String) {
        val toDelete = localStorageManager.loadFullCustomProviders(username).find { it.id == providerId }
        localStorageManager.deleteFullCustomProvider(username, providerId)
        toDelete?.let { apiService.unregisterFullCustomProvider(it.providerKey) }
    }
    fun initializeFullCustomProviders(username: String) {
        apiService.reloadFullCustomProviders(localStorageManager.loadFullCustomProviders(username).filter { it.isEnabled })
    }

    // ==================== Message Branching ====================
    fun migrateChatToBranchingStructure(chat: Chat): Chat = messageBranchingManager.migrateChatToBranchingStructure(chat)
    fun ensureBranchingStructure(username: String, chatId: String): Chat? = messageBranchingManager.ensureBranchingStructure(username, chatId)
    fun createBranch(username: String, chatId: String, nodeId: String, newUserMessage: Message): Pair<Chat, String>? = messageBranchingManager.createBranch(username, chatId, nodeId, newUserMessage)
    fun addResponseToCurrentVariant(username: String, chatId: String, response: Message): Chat? = messageBranchingManager.addResponseToCurrentVariant(username, chatId, response)
    fun switchVariant(username: String, chatId: String, nodeId: String, variantIndex: Int): Chat? = messageBranchingManager.switchVariant(username, chatId, nodeId, variantIndex)
    fun getBranchInfo(chat: Chat, nodeId: String): BranchInfo? = messageBranchingManager.getBranchInfo(chat, nodeId)
    fun getBranchInfoForMessage(chat: Chat, messageId: String): BranchInfo? = messageBranchingManager.getBranchInfoForMessage(chat, messageId)
    fun addUserMessageAsNewNode(username: String, chatId: String, userMessage: Message): Chat? = messageBranchingManager.addUserMessageAsNewNode(username, chatId, userMessage)
    fun findNodeForMessage(chat: Chat, message: Message): String? = messageBranchingManager.findNodeForMessage(chat, message)
    fun deleteMessageFromBranch(username: String, chatId: String, messageId: String): DeleteMessageResult = messageBranchingManager.deleteMessageFromBranch(username, chatId, messageId)

    // ==================== Search ====================
    fun searchChats(username: String, query: String): List<SearchResult> = chatSearchService.searchChats(username, query)

    // ==================== File Upload ====================
    suspend fun uploadFile(provider: Provider, filePath: String, fileName: String, mimeType: String, username: String): Attachment? {
        return fileUploadManager.uploadFile(provider, filePath, fileName, mimeType, username)
    }

    // ==================== Send Message ====================
    suspend fun sendMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        username: String,
        chatId: String? = null,
        projectAttachments: List<Attachment> = emptyList(),
        webSearchEnabled: Boolean = false,
        enabledTools: List<ToolSpecification> = emptyList(),
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
        temperature: Float? = null,
        callback: StreamingCallback
    ) {
        val apiKeys = loadApiKeys(username).filter { it.isActive }.associate { it.provider to it.key }
        val (updatedMessages, hasUpdates) = fileUploadManager.ensureFilesUploadedForProvider(provider, messages, username)
        if (hasUpdates && chatId != null) updateChatWithNewAttachments(username, chatId, updatedMessages)
        val updatedProjectAttachments = fileUploadManager.ensureProjectFilesUploadedForProvider(provider, projectAttachments, username)
        val finalMessages = if (updatedProjectAttachments.isNotEmpty()) {
            listOf(Message(role = "user", text = "General files belonging to the project of which this conversation is a part are attached:", attachments = updatedProjectAttachments)) + updatedMessages
        } else updatedMessages
        apiService.sendMessage(provider, modelName, finalMessages, systemPrompt, apiKeys, webSearchEnabled, enabledTools, thinkingBudget, temperature, callback)
    }

    // ==================== Title Generation ====================
    suspend fun generateConversationTitle(username: String, conversationId: String, provider: String? = null): String =
        titleGenerationService.generateConversationTitle(username, conversationId, provider)

    // ==================== GitHub ====================
    fun loadGitHubConnection(username: String): GitHubConnection? = externalConnectionsManager.loadGitHubConnection(username)
    fun saveGitHubConnection(username: String, connection: GitHubConnection) = externalConnectionsManager.saveGitHubConnection(username, connection)
    fun removeGitHubConnection(username: String) = externalConnectionsManager.removeGitHubConnection(username)
    fun isGitHubConnected(username: String): Boolean = externalConnectionsManager.isGitHubConnected(username)
    fun getGitHubApiService(username: String): Pair<GitHubApiService, String>? = externalConnectionsManager.getGitHubApiService(username)
    fun updateGitHubLastUsed(username: String) = externalConnectionsManager.updateGitHubLastUsed(username)

    // ==================== Google Workspace ====================
    fun loadGoogleWorkspaceConnection(username: String): GoogleWorkspaceConnection? = externalConnectionsManager.loadGoogleWorkspaceConnection(username)
    fun saveGoogleWorkspaceConnection(username: String, connection: GoogleWorkspaceConnection) = externalConnectionsManager.saveGoogleWorkspaceConnection(username, connection)
    fun removeGoogleWorkspaceConnection(username: String) = externalConnectionsManager.removeGoogleWorkspaceConnection(username)
    fun isGoogleWorkspaceConnected(username: String): Boolean = externalConnectionsManager.isGoogleWorkspaceConnected(username)
    fun updateGoogleWorkspaceEnabledServices(username: String, services: EnabledGoogleServices) = externalConnectionsManager.updateGoogleWorkspaceEnabledServices(username, services)
    fun getGoogleWorkspaceApiServices(username: String): Triple<GmailApiService?, GoogleCalendarApiService?, GoogleDriveApiService?>? = externalConnectionsManager.getGoogleWorkspaceApiServices(username)
    fun updateGoogleWorkspaceLastUsed(username: String) = externalConnectionsManager.updateGoogleWorkspaceLastUsed(username)

    // ==================== Skills ====================
    fun getInstalledSkills() = skillsStorageManager.getInstalledSkills()
    fun getEnabledSkillsMetadata() = skillsStorageManager.getEnabledSkillsMetadata()
    fun createSkill(name: String, description: String, body: String = "") = skillsStorageManager.createSkill(name, description, body)
    fun deleteSkill(skillName: String) = skillsStorageManager.deleteSkill(skillName)
    fun setSkillEnabled(skillName: String, enabled: Boolean) = skillsStorageManager.setSkillEnabled(skillName, enabled)
    fun getSkillMdContent(skillName: String) = skillsStorageManager.getSkillMdContent(skillName)
    fun importSkillFromText(content: String) = skillsStorageManager.importFromText(content)
}
