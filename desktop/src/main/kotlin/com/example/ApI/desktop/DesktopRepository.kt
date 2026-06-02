package com.example.ApI.desktop

import com.example.ApI.data.model.*
import com.example.ApI.data.network.LLMApiService
import com.example.ApI.data.repository.*
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.util.JsonConfig
import java.io.File

class DesktopRepository(private val appDir: File) {
    private val context = DesktopContext(File(appDir, "files").apply { mkdirs() })
    private val internalDir = File(context.filesDir, "llm_data").apply { mkdirs() }
    private val apiService = LLMApiService(context)

    private val modelsCacheManager = ModelsCacheManager(internalDir, JsonConfig.prettyPrint)
    private val localStorageManager = LocalStorageManager(internalDir, JsonConfig.prettyPrint)
    private val chatHistoryManager = ChatHistoryManager(internalDir, JsonConfig.prettyPrint)
    private val groupProjectManager = GroupProjectManager(chatHistoryManager)
    private val fileUploadManager = FileUploadManager(JsonConfig.prettyPrint) { username ->
        localStorageManager.loadApiKeys(username)
    }
    val skillsStorageManager = SkillsStorageManager(internalDir, JsonConfig.prettyPrint)
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

    suspend fun refreshModelsIfNeeded(): Boolean = modelsCacheManager.refreshModelsIfNeeded()
    suspend fun forceRefreshModels(): Pair<Boolean, String?> = modelsCacheManager.forceRefreshModels()
    fun loadProviders(): List<Provider> = modelsCacheManager.buildProviders()

    fun loadChatHistory(username: String): UserChatHistory = chatHistoryManager.loadChatHistory(username)
    fun saveChatHistory(chatHistory: UserChatHistory) = chatHistoryManager.saveChatHistory(chatHistory)
    fun getChatJson(username: String, chatId: String): String? = chatHistoryManager.getChatJson(username, chatId)
    fun saveChatJsonToDownloads(chatId: String, content: String): String? =
        chatHistoryManager.saveChatJsonToDownloads(chatId, content)
    fun addMessageToChat(username: String, chatId: String, message: Message): Chat? =
        chatHistoryManager.addMessageToChat(username, chatId, message)
    fun createNewChat(username: String, previewName: String, systemPrompt: String = ""): Chat =
        chatHistoryManager.createNewChat(username, previewName, systemPrompt)
    fun updateChatSystemPrompt(username: String, chatId: String, systemPrompt: String): Chat? =
        chatHistoryManager.updateChatSystemPrompt(username, chatId, systemPrompt)
    fun replaceMessageInChat(username: String, chatId: String, oldMessage: Message, newMessage: Message): Chat? =
        chatHistoryManager.replaceMessageInChat(username, chatId, oldMessage, newMessage)
    fun deleteMessagesFromPoint(username: String, chatId: String, fromMessage: Message): Chat? =
        chatHistoryManager.deleteMessagesFromPoint(username, chatId, fromMessage)
    fun updateChatWithNewAttachments(username: String, chatId: String, updatedMessages: List<Message>) =
        chatHistoryManager.updateChatWithNewAttachments(username, chatId, updatedMessages)
    fun importSingleChat(jsonContent: String, username: String): String? =
        chatHistoryManager.importSingleChat(jsonContent, username)
    fun exportChatHistory(username: String): String? = chatHistoryManager.exportChatHistory(username)

    fun createNewGroup(username: String, groupName: String): ChatGroup =
        groupProjectManager.createNewGroup(username, groupName)
    fun deleteGroup(username: String, groupId: String): Boolean = groupProjectManager.deleteGroup(username, groupId)
    fun renameGroup(username: String, groupId: String, newName: String): Boolean =
        groupProjectManager.renameGroup(username, groupId, newName)
    fun addChatToGroup(username: String, chatId: String, groupId: String): Boolean =
        groupProjectManager.addChatToGroup(username, chatId, groupId)
    fun removeChatFromGroup(username: String, chatId: String): Boolean =
        groupProjectManager.removeChatFromGroup(username, chatId)

    fun loadApiKeys(username: String): List<ApiKey> = localStorageManager.loadApiKeys(username)
    fun saveApiKeys(username: String, apiKeys: List<ApiKey>) = localStorageManager.saveApiKeys(username, apiKeys)
    fun addApiKey(username: String, apiKey: ApiKey) = localStorageManager.addApiKey(username, apiKey)
    fun toggleApiKeyStatus(username: String, keyId: String) = localStorageManager.toggleApiKeyStatus(username, keyId)
    fun deleteApiKey(username: String, keyId: String) = localStorageManager.deleteApiKey(username, keyId)
    fun reorderApiKeys(username: String, fromIndex: Int, toIndex: Int) =
        localStorageManager.reorderApiKeys(username, fromIndex, toIndex)

    fun loadAppSettings(): AppSettings = localStorageManager.loadAppSettings()
    fun saveAppSettings(settings: AppSettings) = localStorageManager.saveAppSettings(settings)
    fun saveFileLocally(fileName: String, data: ByteArray): String? = localStorageManager.saveFileLocally(fileName, data)
    fun deleteFile(filePath: String): Boolean = localStorageManager.deleteFile(filePath)

    fun loadCustomProviders(username: String): List<CustomProviderConfig> =
        localStorageManager.loadCustomProviders(username)
    fun saveCustomProviders(username: String, providers: List<CustomProviderConfig>) {
        localStorageManager.saveCustomProviders(username, providers)
        apiService.reloadCustomProviders(providers.filter { it.isEnabled })
    }
    fun addCustomProvider(username: String, provider: CustomProviderConfig) {
        localStorageManager.addCustomProvider(username, provider)
        if (provider.isEnabled) apiService.registerCustomProvider(provider)
    }
    fun deleteCustomProvider(username: String, providerId: String) {
        val toDelete = localStorageManager.loadCustomProviders(username).find { it.id == providerId }
        localStorageManager.deleteCustomProvider(username, providerId)
        toDelete?.let { apiService.unregisterCustomProvider(it.providerKey) }
    }
    fun initializeCustomProviders(username: String) {
        apiService.reloadCustomProviders(localStorageManager.loadCustomProviders(username).filter { it.isEnabled })
    }

    fun loadFullCustomProviders(username: String): List<FullCustomProviderConfig> =
        localStorageManager.loadFullCustomProviders(username)
    fun saveFullCustomProviders(username: String, providers: List<FullCustomProviderConfig>) {
        localStorageManager.saveFullCustomProviders(username, providers)
        apiService.reloadFullCustomProviders(providers.filter { it.isEnabled })
    }
    fun addFullCustomProvider(username: String, provider: FullCustomProviderConfig) {
        localStorageManager.addFullCustomProvider(username, provider)
        if (provider.isEnabled) apiService.registerFullCustomProvider(provider)
    }
    fun initializeFullCustomProviders(username: String) {
        apiService.reloadFullCustomProviders(localStorageManager.loadFullCustomProviders(username).filter { it.isEnabled })
    }

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

        val (updatedMessages, hasUpdates) = fileUploadManager.ensureFilesUploadedForProvider(provider, messages, username)
        if (hasUpdates && chatId != null) {
            updateChatWithNewAttachments(username, chatId, updatedMessages)
        }

        val updatedProjectAttachments = fileUploadManager.ensureProjectFilesUploadedForProvider(
            provider,
            projectAttachments,
            username
        )
        val finalMessages = if (updatedProjectAttachments.isNotEmpty()) {
            listOf(
                Message(
                    role = "user",
                    text = "General files belonging to the project of which this conversation is a part are attached:",
                    attachments = updatedProjectAttachments
                )
            ) + updatedMessages
        } else {
            updatedMessages
        }

        apiService.sendMessage(
            provider,
            modelName,
            finalMessages,
            systemPrompt,
            apiKeys,
            webSearchEnabled,
            enabledTools,
            thinkingBudget,
            temperature,
            callback
        )
    }

    suspend fun generateConversationTitle(
        username: String,
        conversationId: String,
        provider: String? = null
    ): String = titleGenerationService.generateConversationTitle(username, conversationId, provider)
}
