package com.example.ApI.data.repository

import android.content.Context
import com.example.ApI.data.model.*
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.network.LLMApiService
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.util.JsonConfig
import kotlinx.serialization.json.*
import java.io.File

class DataRepository(private val context: Context) {

    private val apiService = LLMApiService(context)

    private val internalDir = File(context.filesDir, "llm_data")

    // Managers
    private val modelsCacheManager = ModelsCacheManager(internalDir, JsonConfig.prettyPrint)
    private val localStorageManager = LocalStorageManager(internalDir, JsonConfig.prettyPrint)
    private val chatHistoryManager = ChatHistoryManager(internalDir, JsonConfig.prettyPrint)
    private val groupProjectManager = GroupProjectManager(chatHistoryManager)
    private val messageBranchingManager = MessageBranchingManager(chatHistoryManager)
    private val externalConnectionsManager = ExternalConnectionsManager(internalDir, JsonConfig.prettyPrint, localStorageManager)
    private val fileUploadManager = FileUploadManager(JsonConfig.prettyPrint) { username -> localStorageManager.loadApiKeys(username) }
    private val chatSearchService = ChatSearchService { username -> loadChatHistory(username) }
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
        if (!internalDir.exists()) {
            internalDir.mkdirs()
        }

        // Wire up custom providers loader for ModelsCacheManager
        modelsCacheManager.setCustomProvidersLoader {
            val username = loadAppSettings().current_user
            localStorageManager.loadCustomProviders(username)
        }
    }

    // ============ Models Cache (delegated to ModelsCacheManager) ============

    suspend fun refreshModelsIfNeeded(): Boolean = modelsCacheManager.refreshModelsIfNeeded()

    suspend fun forceRefreshModels(): Pair<Boolean, String?> = modelsCacheManager.forceRefreshModels()

    fun loadProviders(): List<Provider> = modelsCacheManager.buildProviders()

    // ============ Chat History (delegated to ChatHistoryManager) ============

    fun loadChatHistory(username: String): UserChatHistory = chatHistoryManager.loadChatHistory(username)
    fun saveChatHistory(chatHistory: UserChatHistory) = chatHistoryManager.saveChatHistory(chatHistory)
    fun getChatJson(username: String, chatId: String): String? = chatHistoryManager.getChatJson(username, chatId)
    fun saveChatJsonToDownloads(chatId: String, content: String): String? = chatHistoryManager.saveChatJsonToDownloads(chatId, content)
    fun addMessageToChat(username: String, chatId: String, message: Message): Chat? = chatHistoryManager.addMessageToChat(username, chatId, message)
    fun createNewChat(username: String, previewName: String, systemPrompt: String = ""): Chat = chatHistoryManager.createNewChat(username, previewName, systemPrompt)
    fun createNewChatInGroup(username: String, previewName: String, groupId: String, systemPrompt: String = ""): Chat = chatHistoryManager.createNewChatInGroup(username, previewName, groupId, systemPrompt)
    fun updateChatSystemPrompt(username: String, chatId: String, systemPrompt: String): Chat? = chatHistoryManager.updateChatSystemPrompt(username, chatId, systemPrompt)

    /**
     * Clean up empty chats (chats with no messages) from the chat history.
     * This removes chats that were created but never had any content added to them.
     */
    fun cleanupEmptyChats(username: String): Int {
        val chatHistory = loadChatHistory(username)

        // Filter out chats with no messages
        val nonEmptyChats = chatHistory.chat_history.filter { chat ->
            chat.messages.isNotEmpty() || chat.messageNodes.isNotEmpty()
        }

        val removedCount = chatHistory.chat_history.size - nonEmptyChats.size

        if (removedCount > 0) {
            val updatedHistory = chatHistory.copy(chat_history = nonEmptyChats)
            saveChatHistory(updatedHistory)
        }

        return removedCount
    }

    // ============ Group Management (delegated to GroupProjectManager) ============

    fun createNewGroup(username: String, groupName: String): ChatGroup = groupProjectManager.createNewGroup(username, groupName)
    fun addChatToGroup(username: String, chatId: String, groupId: String): Boolean = groupProjectManager.addChatToGroup(username, chatId, groupId)
    fun removeChatFromGroup(username: String, chatId: String): Boolean = groupProjectManager.removeChatFromGroup(username, chatId)
    fun deleteGroup(username: String, groupId: String): Boolean = groupProjectManager.deleteGroup(username, groupId)
    fun renameGroup(username: String, groupId: String, newName: String): Boolean = groupProjectManager.renameGroup(username, groupId, newName)
    fun updateGroupProjectStatus(username: String, groupId: String, isProject: Boolean): Boolean = groupProjectManager.updateGroupProjectStatus(username, groupId, isProject)
    fun addAttachmentToGroup(username: String, groupId: String, attachment: Attachment): Boolean = groupProjectManager.addAttachmentToGroup(username, groupId, attachment)
    fun removeAttachmentFromGroup(username: String, groupId: String, attachmentIndex: Int): Boolean = groupProjectManager.removeAttachmentFromGroup(username, groupId, attachmentIndex)
    
    // ============ Local Storage (delegated to LocalStorageManager) ============

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

    // ============ Custom Providers (delegated to LocalStorageManager) ============

    fun loadCustomProviders(username: String): List<CustomProviderConfig> =
        localStorageManager.loadCustomProviders(username)

    fun saveCustomProviders(username: String, providers: List<CustomProviderConfig>) {
        localStorageManager.saveCustomProviders(username, providers)
        apiService.reloadCustomProviders(providers.filter { it.isEnabled })
    }

    fun addCustomProvider(username: String, provider: CustomProviderConfig) {
        localStorageManager.addCustomProvider(username, provider)
        if (provider.isEnabled) {
            apiService.registerCustomProvider(provider)
        }
    }

    fun updateCustomProvider(username: String, providerId: String, updated: CustomProviderConfig) {
        localStorageManager.updateCustomProvider(username, providerId, updated)
        // Reload all to handle key changes
        val all = localStorageManager.loadCustomProviders(username)
        apiService.reloadCustomProviders(all.filter { it.isEnabled })
    }

    fun deleteCustomProvider(username: String, providerId: String) {
        val toDelete = localStorageManager.loadCustomProviders(username).find { it.id == providerId }
        localStorageManager.deleteCustomProvider(username, providerId)
        toDelete?.let { apiService.unregisterCustomProvider(it.providerKey) }
    }

    /**
     * Initialize custom providers in LLMApiService.
     * Should be called when the repository is first used.
     */
    fun initializeCustomProviders(username: String) {
        val customConfigs = localStorageManager.loadCustomProviders(username)
        apiService.reloadCustomProviders(customConfigs.filter { it.isEnabled })
    }

    fun replaceMessageInChat(username: String, chatId: String, oldMessage: Message, newMessage: Message): Chat? = chatHistoryManager.replaceMessageInChat(username, chatId, oldMessage, newMessage)
    fun deleteMessagesFromPoint(username: String, chatId: String, fromMessage: Message): Chat? = chatHistoryManager.deleteMessagesFromPoint(username, chatId, fromMessage)
    
    // API Communication
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
        val apiKeys = loadApiKeys(username)
            .filter { it.isActive }
            .associate { it.provider to it.key }

        // Check and re-upload files if needed when provider has changed
        val (updatedMessages, hasUpdates) = ensureFilesUploadedForProvider(provider, messages, username)

        // If files were re-uploaded, update the chat history
        if (hasUpdates && chatId != null) {
            updateChatWithNewAttachments(username, chatId, updatedMessages)
        }

        // Handle project files - upload them for current provider if needed
        val updatedProjectAttachments = ensureProjectFilesUploadedForProvider(provider, projectAttachments, username)

        // Create final messages list with project files attached to a user message if needed
        val finalMessages = if (updatedProjectAttachments.isNotEmpty()) {
            // Create a user message with project attachments at the beginning
            val projectFilesMessage = Message(
                role = "user",
                text = "General files belonging to the project of which this conversation is a part are attached:",
                attachments = updatedProjectAttachments
            )
            listOf(projectFilesMessage) + updatedMessages
        } else {
            updatedMessages
        }

        apiService.sendMessage(provider, modelName, finalMessages, systemPrompt, apiKeys, webSearchEnabled, enabledTools, thinkingBudget, temperature, callback)
    }

    // ============ File Upload (delegated to FileUploadManager) ============

    private suspend fun ensureProjectFilesUploadedForProvider(
        provider: Provider,
        projectAttachments: List<Attachment>,
        username: String
    ): List<Attachment> = fileUploadManager.ensureProjectFilesUploadedForProvider(provider, projectAttachments, username)

    private suspend fun ensureFilesUploadedForProvider(
        provider: Provider,
        messages: List<Message>,
        username: String
    ): Pair<List<Message>, Boolean> = fileUploadManager.ensureFilesUploadedForProvider(provider, messages, username)
    
    fun updateChatWithNewAttachments(username: String, chatId: String, updatedMessages: List<Message>) = chatHistoryManager.updateChatWithNewAttachments(username, chatId, updatedMessages)
    fun exportChatHistory(username: String): String? = chatHistoryManager.exportChatHistory(username)
    fun importChatHistoryJson(raw: ByteArray, targetUsername: String) = chatHistoryManager.importChatHistoryJson(raw, targetUsername)
    fun validateChatJson(jsonContent: String): Boolean = chatHistoryManager.validateChatJson(jsonContent)
    fun importSingleChat(jsonContent: String, targetUsername: String): String? = chatHistoryManager.importSingleChat(jsonContent, targetUsername)
    
    suspend fun uploadFile(
        provider: Provider,
        filePath: String,
        fileName: String,
        mimeType: String,
        username: String
    ): Attachment? = fileUploadManager.uploadFile(provider, filePath, fileName, mimeType, username)
    
    // ============ Title Generation & Search (delegated) ============

    suspend fun generateConversationTitle(
        username: String,
        conversationId: String,
        provider: String? = null
    ): String = titleGenerationService.generateConversationTitle(username, conversationId, provider)

    fun searchChats(username: String, query: String): List<com.example.ApI.data.model.SearchResult> =
        chatSearchService.searchChats(username, query)

    // ============ External Connections (delegated to ExternalConnectionsManager) ============

    fun loadGitHubConnection(username: String): GitHubConnection? = externalConnectionsManager.loadGitHubConnection(username)
    fun saveGitHubConnection(username: String, connection: GitHubConnection) = externalConnectionsManager.saveGitHubConnection(username, connection)
    fun removeGitHubConnection(username: String) = externalConnectionsManager.removeGitHubConnection(username)
    fun isGitHubConnected(username: String): Boolean = externalConnectionsManager.isGitHubConnected(username)
    fun getGitHubApiService(username: String): Pair<com.example.ApI.data.network.GitHubApiService, String>? = externalConnectionsManager.getGitHubApiService(username)
    fun updateGitHubLastUsed(username: String) = externalConnectionsManager.updateGitHubLastUsed(username)

    fun loadGoogleWorkspaceConnection(username: String): com.example.ApI.data.model.GoogleWorkspaceConnection? = externalConnectionsManager.loadGoogleWorkspaceConnection(username)
    fun saveGoogleWorkspaceConnection(username: String, connection: com.example.ApI.data.model.GoogleWorkspaceConnection) = externalConnectionsManager.saveGoogleWorkspaceConnection(username, connection)
    fun removeGoogleWorkspaceConnection(username: String) = externalConnectionsManager.removeGoogleWorkspaceConnection(username)
    fun isGoogleWorkspaceConnected(username: String): Boolean = externalConnectionsManager.isGoogleWorkspaceConnected(username)
    fun updateGoogleWorkspaceEnabledServices(username: String, services: com.example.ApI.data.model.EnabledGoogleServices) = externalConnectionsManager.updateGoogleWorkspaceEnabledServices(username, services)
    fun getGoogleWorkspaceApiServices(username: String): Triple<com.example.ApI.data.network.GmailApiService?, com.example.ApI.data.network.GoogleCalendarApiService?, com.example.ApI.data.network.GoogleDriveApiService?>? = externalConnectionsManager.getGoogleWorkspaceApiServices(username)
    fun updateGoogleWorkspaceLastUsed(username: String) = externalConnectionsManager.updateGoogleWorkspaceLastUsed(username)

    // ============ Branching System (delegated to MessageBranchingManager) ============

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
}


