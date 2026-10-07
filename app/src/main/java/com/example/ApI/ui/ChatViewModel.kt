package com.example.ApI.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.data.repository.DeleteMessageResult
import com.example.ApI.data.repository.ReplyAnchor
import com.example.ApI.data.sync.ForegroundSyncTicker
import com.example.ApI.data.sync.SyncReload
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.ParentalControlManager
import com.example.ApI.service.StreamingService
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.ui.managers.ManagerDependencies
import com.example.ApI.ui.managers.chat.MessageSendingManager
import com.example.ApI.ui.managers.chat.MessageEditingManager
import com.example.ApI.ui.managers.chat.BranchingManager
import com.example.ApI.ui.managers.chat.ChatContextMenuManager
import com.example.ApI.ui.managers.chat.SystemPromptManager
import com.example.ApI.ui.managers.organization.GroupManager
import com.example.ApI.ui.managers.organization.SearchManager
import com.example.ApI.ui.managers.provider.ModelSelectionManager
import com.example.ApI.ui.managers.provider.TopBarManager
import com.example.ApI.ui.managers.streaming.StreamingEventManager
import com.example.ApI.ui.managers.streaming.TitleGenerationManager
import com.example.ApI.ui.managers.io.AttachmentManager
import com.example.ApI.ui.managers.io.ExportImportManager
import com.example.ApI.ui.managers.io.SharedIntentManager
import com.example.ApI.ui.managers.integration.AuthManager
import com.example.ApI.ui.managers.integration.ToolManager
import com.example.ApI.ui.managers.settings.ChildLockManager
import com.example.ApI.data.network.SyncGoogleSignInProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.core.content.FileProvider
import androidx.core.app.PendingIntentCompat
import android.app.PendingIntent
import android.os.Environment
import com.example.ApI.util.JsonConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.LocalTime
import java.time.LocalDateTime
import java.util.UUID

class ChatViewModel(
    private val repository: DataRepository,
    private val context: Context,
    private val sharedIntent: Intent? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _currentScreen = MutableStateFlow<Screen>(Screen.ChatHistory)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _appSettings = MutableStateFlow(AppSettings("default", "openai", "gpt-4o"))
    val appSettings: StateFlow<AppSettings> = _appSettings.asStateFlow()

    // Remote sync sign-in provider and in-progress indicator
    private val syncSignInProvider: SyncGoogleSignInProvider by lazy { SyncGoogleSignInProvider(context) }
    private val _syncSignInInProgress = MutableStateFlow(false)
    val syncSignInInProgress: StateFlow<Boolean> = _syncSignInInProgress.asStateFlow()

    /** True when the sync token has expired and the user must re-authenticate. */
    val syncNeedsReauth: StateFlow<Boolean> get() = repository.needsReauth

    /** True when the sync server is too old (no compare-and-swap uploads): nothing syncs until it is updated. */
    val syncServerLacksCas: StateFlow<Boolean> get() = repository.syncServerLacksCas

    /** Periodic pull while the app is resumed (started/stopped by [onAppResume] / [onAppPause]). */
    private val foregroundSyncTicker = ForegroundSyncTicker(viewModelScope) { repository.pullNow() }

    // Shared dependencies for managers
    private val managerDeps by lazy {
        ManagerDependencies(
            repository = repository,
            context = context,
            scope = viewModelScope,
            appSettings = _appSettings,
            uiState = _uiState,
            updateUiState = { newState -> _uiState.value = newState }
        )
    }

    // Service binding for streaming requests
    private var streamingService: StreamingService? = null
    private var serviceBound = false

    // Group management delegate
    private val groupManager: GroupManager by lazy {
        GroupManager(
            deps = managerDeps,
            navigateToScreen = { screen -> navigateToScreen(screen) }
        )
    }

    // Integration management delegate (GitHub + Google Workspace)
    private val authManager: AuthManager by lazy {
        AuthManager(
            deps = managerDeps,
            updateAppSettings = { newSettings -> _appSettings.value = newSettings },
            showSnackbar = { message -> showSnackbar(message) }
        )
    }

    // Search functionality delegate
    private val searchManager: SearchManager by lazy {
        SearchManager(
            deps = managerDeps,
            getCurrentScreen = { _currentScreen.value }
        )
    }

    // Child lock (parental controls) delegate
    private val childLockManager: ChildLockManager by lazy {
        ChildLockManager(
            deps = managerDeps,
            updateAppSettings = { newSettings -> _appSettings.value = newSettings }
        )
    }

    // File management delegate
    private val attachmentManager: AttachmentManager by lazy {
        AttachmentManager(
            deps = managerDeps
        )
    }

    // Export/Import functionality delegate
    private val exportImportManager: ExportImportManager by lazy {
        ExportImportManager(
            deps = managerDeps,
            selectChat = { chat -> selectChat(chat) },
            navigateToScreen = { screen -> navigateToScreen(screen) },
            addFileFromUri = { uri, name, mime -> attachmentManager.addFileFromUri(uri, name, mime) }
        )
    }

    // Branching/variant navigation delegate
    private val branchingManager: BranchingManager by lazy {
        BranchingManager(
            deps = managerDeps
        )
    }

    // Top bar controls delegate (temperature, thinking budget, text direction)
    private val topBarManager: TopBarManager by lazy {
        TopBarManager(
            deps = managerDeps
        )
    }


    // Provider and model selection management delegate
    private val modelSelectionManager: ModelSelectionManager by lazy {
        ModelSelectionManager(
            deps = managerDeps,
            updateAppSettings = { newSettings -> _appSettings.value = newSettings }
        )
    }

    // Message sending delegate (send, batch send, API calls)
    private val messageSendingManager: MessageSendingManager by lazy {
        MessageSendingManager(
            deps = managerDeps,
            getCurrentDateTimeISO = { getCurrentDateTimeISO() },
            getEffectiveSystemPrompt = { systemPromptManager.getEffectiveSystemPrompt() },
            getCurrentChatProjectGroup = { systemPromptManager.getCurrentChatProjectGroup() },
            getEnabledToolSpecifications = { toolManager.getEnabledToolSpecifications() },
            startStreamingRequest = { requestId, chatId, username, provider, modelName, messages, systemPrompt, webSearchEnabled, projectAttachments, enabledTools, thinkingBudget, temperature ->
                startStreamingRequest(requestId, chatId, username, provider, modelName, messages, systemPrompt, webSearchEnabled, projectAttachments, enabledTools, thinkingBudget, temperature)
            },
            createNewChat = { previewName -> createNewChat(previewName) }
        )
    }

    // Message editing delegate (edit, delete, resend - delegates API calls to messageSendingManager)
    private val messageEditingManager: MessageEditingManager by lazy {
        MessageEditingManager(
            deps = managerDeps,
            getCurrentDateTimeISO = { getCurrentDateTimeISO() },
            sendApiRequestForBranch = { chat -> messageSendingManager.sendApiRequestForCurrentBranch(chat) }
        )
    }

    // Tool management delegate
    private val toolManager: ToolManager by lazy {
        ToolManager(
            deps = managerDeps,
            updateAppSettings = { newSettings -> _appSettings.value = newSettings }
        )
    }

    // Title generation delegate
    private val titleGenerationManager: TitleGenerationManager by lazy {
        TitleGenerationManager(
            deps = managerDeps,
            updateAppSettings = { newSettings -> _appSettings.value = newSettings }
        )
    }

    // System prompt management delegate
    private val systemPromptManager: SystemPromptManager by lazy {
        SystemPromptManager(
            deps = managerDeps
        )
    }

    // Chat context menu delegate
    private val chatContextMenuManager: ChatContextMenuManager by lazy {
        ChatContextMenuManager(
            deps = managerDeps,
            navigateToScreen = { screen -> navigateToScreen(screen) },
            updateChatPreviewName = { chatId, newTitle -> titleGenerationManager.updateChatPreviewName(chatId, newTitle) }
        )
    }

    // Shared intent handling delegate
    private val sharedIntentManager: SharedIntentManager by lazy {
        SharedIntentManager(
            deps = managerDeps,
            sharedIntent = sharedIntent,
            currentScreen = _currentScreen,
            addFileFromUri = { uri, name, mime -> attachmentManager.addFileFromUri(uri, name, mime) }
        )
    }

    // Streaming event handling delegate
    private val streamingEventManager: StreamingEventManager by lazy {
        StreamingEventManager(
            deps = managerDeps,
            getCurrentDateTimeISO = { getCurrentDateTimeISO() },
            handleTitleGeneration = { chat -> titleGenerationManager.handleTitleGeneration(chat) },
            executeToolCall = { toolCall -> toolManager.executeToolCall(toolCall) },
            getStreamingService = { streamingService }
        )
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as StreamingService.LocalBinder
            streamingService = localBinder.getService()
            serviceBound = true
            Log.d("ChatViewModel", "StreamingService connected")

            // Start observing streaming events
            viewModelScope.launch {
                streamingService?.streamingEvents?.collect { event ->
                    streamingEventManager.handleStreamingEvent(event)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            serviceBound = false
            Log.d("ChatViewModel", "StreamingService disconnected")
        }
    }

    init {
        // Set a default provider immediately
        val defaultProvider = Provider(
            provider = "openai",
            models = listOf(Model.SimpleModel("gpt-4o")),
            request = ApiRequest("", "", emptyMap()),
            response_important_fields = ResponseFields()
        )
        _uiState.value = _uiState.value.copy(
            currentProvider = defaultProvider,
            currentModel = "gpt-4o"
        )

        loadInitialData()
        sharedIntentManager.handleSharedFiles()
        bindToStreamingService()
        observeSyncChangeTick()
    }

    private fun getCurrentDateTimeISO(): String {
        return Instant.now().toString()
    }

    /**
     * Bind to the StreamingService to receive streaming events
     */
    fun bindToStreamingService() {
        if (!serviceBound) {
            val intent = Intent(context, StreamingService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * Unbind from the streaming service
     */
    fun unbindFromStreamingService() {
        if (serviceBound) {
            context.unbindService(serviceConnection)
            serviceBound = false
            streamingService = null
        }
    }

    /**
     * Start a streaming request via the foreground service
     */
    private fun startStreamingRequest(
        requestId: String,
        chatId: String,
        username: String,
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        webSearchEnabled: Boolean,
        projectAttachments: List<Attachment>,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
        temperature: Float? = null
    ) {
        val intent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START_REQUEST
            putExtra(StreamingService.EXTRA_REQUEST_ID, requestId)
            putExtra(StreamingService.EXTRA_CHAT_ID, chatId)
            putExtra(StreamingService.EXTRA_USERNAME, username)
            putExtra(StreamingService.EXTRA_PROVIDER_JSON, JsonConfig.standard.encodeToString(provider))
            putExtra(StreamingService.EXTRA_MODEL_NAME, modelName)
            putExtra(StreamingService.EXTRA_SYSTEM_PROMPT, systemPrompt)
            putExtra(StreamingService.EXTRA_WEB_SEARCH_ENABLED, webSearchEnabled)
            putExtra(StreamingService.EXTRA_MESSAGES_JSON, JsonConfig.standard.encodeToString(messages))
            // Pin the reply to the variant of the message it answers, after that message
            // (survives a variant switch or a sync merge while streaming)
            messages.lastOrNull()?.let { last ->
                last.variantId?.let { putExtra(StreamingService.EXTRA_TARGET_VARIANT_ID, it) }
                putExtra(StreamingService.EXTRA_EXPECTED_TAIL_ID, last.id)
            }
            putExtra(StreamingService.EXTRA_PROJECT_ATTACHMENTS_JSON, JsonConfig.standard.encodeToString(projectAttachments))
            putExtra(StreamingService.EXTRA_ENABLED_TOOLS_JSON, JsonConfig.standard.encodeToString(enabledTools))
            // Add thinking budget if not None
            if (thinkingBudget != ThinkingBudgetValue.None) {
                putExtra(StreamingService.EXTRA_THINKING_BUDGET_JSON, JsonConfig.standard.encodeToString(thinkingBudget))
            }
            // Add temperature if set
            if (temperature != null) {
                putExtra(StreamingService.EXTRA_TEMPERATURE, temperature)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Cancel a streaming request
     */
    fun cancelStreamingRequest(chatId: String) {
        // Find the request ID for this chat
        val activeRequests = streamingService?.getActiveRequests() ?: return
        val request = activeRequests.values.find { it.chatId == chatId } ?: return

        streamingService?.cancelRequest(request.requestId)

        // Update UI state
        _uiState.value = _uiState.value.copy(
            loadingChatIds = _uiState.value.loadingChatIds - chatId,
            streamingChatIds = _uiState.value.streamingChatIds - chatId,
            streamingTextByChat = _uiState.value.streamingTextByChat - chatId
        )
    }

    /**
     * Stop streaming for current chat and save the accumulated text as a completed response.
     * This treats the partial response as if it completed successfully.
     */
    fun stopStreamingAndSave() {
        val currentChat = _uiState.value.currentChat ?: return
        val chatId = currentChat.chat_id
        val currentUser = _appSettings.value.current_user
        val currentModel = _uiState.value.currentModel

        // Get the accumulated streaming text for this chat
        val accumulatedText = _uiState.value.streamingTextByChat[chatId] ?: ""

        // Find and stop the active request in the service; its reply anchor says where the
        // partial reply belongs (the pinned variant, after what the request already saved)
        val activeRequests = streamingService?.getActiveRequests()
        val request = activeRequests?.values?.find { it.chatId == chatId }
        val anchor = request?.let { streamingService?.replyAnchor(it.requestId) }
            ?: ReplyAnchor.forRequest(currentChat.messages)
        if (request != null) {
            streamingService?.stopAndComplete(request.requestId)
        }

        viewModelScope.launch {
            // Only save if there's accumulated text
            if (accumulatedText.isNotEmpty()) {
                // Create assistant message with the accumulated text
                val assistantMessage = Message(
                    role = "assistant",
                    text = accumulatedText,
                    attachments = emptyList(),
                    model = currentModel,
                    datetime = getCurrentDateTimeISO()
                )

                // Save the message to chat history, pinned to the request's variant
                repository.addAnchoredResponse(currentUser, chatId, assistantMessage, anchor)
            }

            // Clean up empty chats
            repository.cleanupEmptyChats(currentUser)

            // Reload chat history
            var refreshedHistory = repository.loadChatHistory(currentUser)
            var refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

            // Clear streaming state for this chat
            _uiState.value = _uiState.value.copy(
                loadingChatIds = _uiState.value.loadingChatIds - chatId,
                streamingChatIds = _uiState.value.streamingChatIds - chatId,
                streamingTextByChat = _uiState.value.streamingTextByChat - chatId,
                chatHistory = refreshedHistory.chat_history,
                currentChat = refreshedChat ?: currentChat
            )

            // Handle title generation if needed
            if (refreshedChat != null && accumulatedText.isNotEmpty()) {
                titleGenerationManager.handleTitleGeneration(refreshedChat)
            }
        }
    }

    /**
     * Called when the ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()
        foregroundSyncTicker.stop()
        unbindFromStreamingService()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val settings = repository.loadAppSettings()
            _appSettings.value = settings

            // Initialize custom providers in LLMApiService
            repository.initializeCustomProviders(settings.current_user)
            repository.initializeFullCustomProviders(settings.current_user)

            // Refresh models from remote if needed (24-hour cache)
            repository.refreshModelsIfNeeded()

            // Load providers and filter by active API keys
            val allProviders = repository.loadProviders()
            val activeApiKeyProviders = repository.loadApiKeys(settings.current_user)
                .filter { it.isActive }
                .map { it.provider }
            val providers = allProviders.filter { provider ->
                activeApiKeyProviders.contains(provider.provider)
            }

            val currentProvider = providers.find { it.provider == settings.selected_provider }
                ?: providers.firstOrNull()
            
            // Ensure we have a valid model
            val currentModel = if (settings.selected_model.isEmpty()) {
                currentProvider?.models?.firstOrNull()?.name ?: "gpt-4o"
            } else {
                settings.selected_model
            }
            
                         // Load existing chat history
            var chatHistory = repository.loadChatHistory(settings.current_user)

            // Clean up empty chats
            repository.cleanupEmptyChats(settings.current_user)

            // Reload chat history after cleanup
            chatHistory = repository.loadChatHistory(settings.current_user)

            val currentChat = if (chatHistory.chat_history.isNotEmpty()) {
                chatHistory.chat_history.last() // Load the most recent chat
            } else {
                null
            }
            
            val webSearchSupport = modelSelectionManager.getWebSearchSupport(currentProvider?.provider ?: "", currentModel)
            val webSearchEnabled = when (webSearchSupport) {
                WebSearchSupport.REQUIRED -> true
                WebSearchSupport.OPTIONAL -> false // Default to off for optional models
                WebSearchSupport.UNSUPPORTED -> false
            }
            
            _uiState.value = _uiState.value.copy(
                availableProviders = providers,
                currentProvider = currentProvider,
                currentModel = currentModel,
                systemPrompt = currentChat?.systemPrompt ?: "",
                currentChat = currentChat,
                chatHistory = chatHistory.chat_history,
                groups = chatHistory.groups,
                webSearchSupport = webSearchSupport,
                webSearchEnabled = webSearchEnabled,
                excludedToolIds = settings.excludedToolIds
            )

            // Update settings if we changed anything
            if (currentModel != settings.selected_model ||
                (currentProvider?.provider != settings.selected_provider)) {
                val updatedSettings = repository.updateAppSettings {
                    it.copy(
                        selected_provider = currentProvider?.provider ?: "openai",
                        selected_model = currentModel
                    )
                }
                _appSettings.value = updatedSettings
            }

            // Show welcome screen if not skipped
            if (!settings.skipWelcomeScreen) {
                _currentScreen.value = Screen.Welcome
            }

            // Initialize integration tools if connected
            initializeGitHubToolsIfConnected()
            initializeGoogleWorkspaceToolsIfConnected()

            // Initialize Skills tools
            initializeSkillTools()

            // Start remote sync (no-op if disabled)
            repository.startSync()
        }
    }

    /**
     * Register skill tools and auto-enable them if there are installed skills.
     */
    private fun initializeSkillTools() {
        val toolRegistry = com.example.ApI.tools.ToolRegistry.getInstance()
        toolRegistry.registerSkillTools(repository.skillsStorageManager)

        // Auto-enable skill tools if there are any installed skills
        val hasSkills = repository.getInstalledSkills().any { it.isEnabled }
        if (hasSkills) {
            val settings = _appSettings.value
            val skillToolIds = toolRegistry.getSkillToolIds()
            val currentEnabled = settings.enabledTools.toMutableList()
            var changed = false
            for (toolId in skillToolIds) {
                if (toolId !in currentEnabled) {
                    currentEnabled.add(toolId)
                    changed = true
                }
            }
            if (changed) {
                val updatedSettings = repository.updateAppSettings { fresh ->
                    fresh.copy(enabledTools = (fresh.enabledTools + skillToolIds).distinct())
                }
                _appSettings.value = updatedSettings
            }
        }
    }

    fun updateMessage(message: String) {
        _uiState.value = _uiState.value.copy(currentMessage = message)
    }

    // ==================== Message Sending (delegated to MessageSendingManager) ====================
    fun sendMessage() = messageSendingManager.sendMessage()
    fun sendBufferedBatch() = messageSendingManager.sendBufferedBatch()

    // ==================== Message Editing (delegated to MessageEditingManager) ====================
    fun deleteMessage(message: Message) = messageEditingManager.deleteMessage(message)
    fun startEditingMessage(message: Message) = messageEditingManager.startEditingMessage(message)
    fun finishEditingMessage() = messageEditingManager.finishEditingMessage()
    fun confirmEditAndResend() = messageEditingManager.confirmEditAndResend()
    fun cancelEditingMessage() = messageEditingManager.cancelEditingMessage()
    fun resendFromMessage(message: Message) = messageEditingManager.resendFromMessage(message)

    // ==================== Chat Selection & Management ====================
    fun selectChat(chat: Chat) {
        val currentUser = _appSettings.value.current_user
        viewModelScope.launch {
            // Update current chat
            _uiState.value = _uiState.value.copy(
                currentChat = chat,
                systemPrompt = chat.systemPrompt
            )
        }
    }

    fun selectChatFromSearch(chat: Chat) {
        selectChat(chat)
        navigateToScreen(Screen.Chat)
    }

    fun createNewChat(previewName: String): Chat {
        val currentUser = _appSettings.value.current_user
        val newChat = repository.createNewChat(currentUser, previewName)
        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history

        _uiState.value = _uiState.value.copy(
            currentChat = newChat,
            chatHistory = updatedChatHistory
        )
        navigateToScreen(Screen.Chat)
        return newChat
    }

    fun createNewChatInGroup(groupId: String) {
        val currentUser = _appSettings.value.current_user
        val newChat = repository.createNewChatInGroup(currentUser, "שיחה חדשה", groupId, "")
        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history

        _uiState.value = _uiState.value.copy(
            currentChat = newChat,
            chatHistory = updatedChatHistory,
            groups = repository.loadChatHistory(currentUser).groups
        )
        navigateToScreen(Screen.Chat)
    }

    // ==================== Provider/Model Selection (delegated to ModelSelectionManager) ====================
    fun selectProvider(provider: Provider) = modelSelectionManager.selectProvider(provider)
    fun selectModel(modelName: String) = modelSelectionManager.selectModel(modelName)
    fun selectModelWithProvider(provider: Provider, modelName: String) =
        modelSelectionManager.selectModelWithProvider(provider, modelName)
    fun showModelSelector() = modelSelectionManager.showModelSelector()
    fun hideModelSelector() = modelSelectionManager.hideModelSelector()
    fun refreshModels() = modelSelectionManager.refreshModels()
    fun refreshAvailableProviders() = modelSelectionManager.refreshAvailableProviders()
    fun toggleStarredModel(providerKey: String, modelName: String) =
        modelSelectionManager.toggleStarredModel(providerKey, modelName)

    /**
     * Search for a model by name (case-sensitive) across all available providers and select it.
     * Prioritizes direct providers over routers (Poe and OpenRouter).
     * If the model is not found, does nothing.
     */
    fun selectModelByName(modelName: String) {
        val availableProviders = _uiState.value.availableProviders

        // Router providers that should have lower priority
        val routerProviders = setOf("poe", "openrouter")

        // Search for the model in all providers
        val matchingProviders = mutableListOf<Pair<Provider, String>>()

        for (provider in availableProviders) {
            val matchingModel = provider.models.find { model ->
                model.name == modelName  // Case-sensitive comparison
            }

            if (matchingModel != null) {
                matchingProviders.add(provider to matchingModel.name!!)
            }
        }

        // If no matching model found, do nothing
        if (matchingProviders.isEmpty()) {
            return
        }

        // Prioritize direct providers over routers
        val selectedProvider = matchingProviders.firstOrNull { (provider, _) ->
            !routerProviders.contains(provider.provider.lowercase())
        }?.first ?: matchingProviders.first().first

        val selectedModelName = matchingProviders.first { (provider, _) ->
            provider == selectedProvider
        }.second

        // Select the provider and model
        selectProvider(selectedProvider)
        selectModel(selectedModelName)
    }

    // ==================== System Prompt Management (delegated to SystemPromptManager) ====================
    fun updateSystemPrompt(prompt: String) = systemPromptManager.updateSystemPrompt(prompt)
    fun showSystemPromptDialog() = systemPromptManager.showSystemPromptDialog()
    fun hideSystemPromptDialog() = systemPromptManager.hideSystemPromptDialog()
    fun toggleSystemPromptOverride() = systemPromptManager.toggleSystemPromptOverride()
    fun setSystemPromptOverride(enabled: Boolean) = systemPromptManager.setSystemPromptOverride(enabled)
    fun getCurrentChatProjectGroup(): ChatGroup? = systemPromptManager.getCurrentChatProjectGroup()
    fun getEffectiveSystemPrompt(): String = systemPromptManager.getEffectiveSystemPrompt()

    // ==================== Snackbar Management ====================
    fun showSnackbar(message: String) {
        _uiState.value = _uiState.value.copy(snackbarMessage = message)
    }

    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    // ==================== Empty Chat Cleanup ====================
    fun cleanupEmptyChatsOnScreen() {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            repository.cleanupEmptyChats(currentUser)

            // Reload chat history after cleanup
            val updatedHistory = repository.loadChatHistory(currentUser)
            _uiState.value = _uiState.value.copy(
                chatHistory = updatedHistory.chat_history,
                groups = updatedHistory.groups
            )
        }
    }

    // ==================== File/Attachment Management (delegated to AttachmentManager) ====================
    fun addFileFromUri(uri: Uri, fileName: String, mimeType: String) = attachmentManager.addFileFromUri(uri, fileName, mimeType)
    fun addMultipleFilesFromUris(filesList: List<Triple<Uri, String, String>>) = attachmentManager.addMultipleFilesFromUris(filesList)
    fun removeSelectedFile(file: SelectedFile) = attachmentManager.removeSelectedFile(file)
    fun showFileSelection() = attachmentManager.showFileSelection()
    fun hideFileSelection() = attachmentManager.hideFileSelection()

    // ==================== Settings Management ====================
    fun updateMultiMessageMode(enabled: Boolean) {
        val updatedSettings = repository.updateAppSettings { it.copy(multiMessageMode = enabled) }
        _appSettings.value = updatedSettings
    }

    // ==================== Navigation ====================
    /**
     * Navigate to a different screen.
     * Refreshes available providers when leaving API keys screen.
     */
    fun navigateToScreen(screen: Screen) {
        // Refresh available providers when navigating away from API keys screen
        // since user may have added/removed/toggled keys
        if (_currentScreen.value == Screen.ApiKeys && screen != Screen.ApiKeys) {
            refreshAvailableProviders()
        }
        _currentScreen.value = screen
    }

    /**
     * Update the skip welcome screen setting.
     */
    fun updateSkipWelcomeScreen(skip: Boolean) {
        val updatedSettings = repository.updateAppSettings { it.copy(skipWelcomeScreen = skip) }
        _appSettings.value = updatedSettings
    }

    // ==================== Full Chat History Export/Import (delegated to ExportImportManager) ====================
    fun exportChatHistory() = exportImportManager.exportChatHistory()
    fun importChatHistoryFromUri(uri: Uri) = exportImportManager.importChatHistoryFromUri(uri)

    // ==================== Single Chat Export ====================
    fun selectAndExportChat(chat: Chat) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                currentChat = chat,
                systemPrompt = chat.systemPrompt
            )
            exportImportManager.openChatExportDialog()
        }
    }

    // Text direction settings (delegated to TopBarManager)
    fun toggleTextDirection() = topBarManager.toggleTextDirection()
    fun setTextDirectionMode(mode: TextDirectionMode) = topBarManager.setTextDirectionMode(mode)


    // ==================== Title Generation (delegated to TitleGenerationManager) ====================
    fun updateTitleGenerationSettings(newSettings: TitleGenerationSettings) = titleGenerationManager.updateTitleGenerationSettings(newSettings)
    fun getAvailableProvidersForTitleGeneration(): List<String> = titleGenerationManager.getAvailableProvidersForTitleGeneration()
    fun renameChatWithAI(chat: Chat) {
        chatContextMenuManager.hideChatContextMenu()
        titleGenerationManager.renameChatWithAI(chat)
    }

    // ==================== Chat Context Menu (delegated to ChatContextMenuManager) ====================
    fun showChatContextMenu(chat: Chat, position: androidx.compose.ui.unit.DpOffset) = chatContextMenuManager.showChatContextMenu(chat, position)
    fun hideChatContextMenu() = chatContextMenuManager.hideChatContextMenu()
    fun showRenameDialog(chat: Chat) = chatContextMenuManager.showRenameDialog(chat)
    fun hideRenameDialog() = chatContextMenuManager.hideRenameDialog()
    fun showDeleteConfirmation(chat: Chat) = chatContextMenuManager.showDeleteConfirmation(chat)
    fun hideDeleteConfirmation() = chatContextMenuManager.hideDeleteConfirmation()
    fun showDeleteChatConfirmation() = chatContextMenuManager.showDeleteChatConfirmation()
    fun hideDeleteChatConfirmation() = chatContextMenuManager.hideDeleteChatConfirmation()
    fun deleteCurrentChat() = chatContextMenuManager.deleteCurrentChat()
    fun renameChat(chat: Chat, newName: String) = chatContextMenuManager.renameChat(chat, newName)
    fun deleteChat(chat: Chat) = chatContextMenuManager.deleteChat(chat)
    fun toggleWebSearch() = chatContextMenuManager.toggleWebSearch()

    // Group Management Methods

    fun createNewGroup(groupName: String) = groupManager.createNewGroup(groupName)

    fun addChatToGroup(chatId: String, groupId: String) = groupManager.addChatToGroup(chatId, groupId)

    fun removeChatFromGroup(chatId: String) = groupManager.removeChatFromGroup(chatId)

    fun toggleGroupExpansion(groupId: String) = groupManager.toggleGroupExpansion(groupId)

    fun showGroupDialog(chat: Chat? = null) = groupManager.showGroupDialog(chat)

    fun hideGroupDialog() = groupManager.hideGroupDialog()

    fun refreshChatHistoryAndGroups() = groupManager.refreshChatHistoryAndGroups()

    fun toggleGroupProjectStatus(groupId: String) = groupManager.toggleGroupProjectStatus(groupId)

    fun addFileToProject(groupId: String, uri: Uri, fileName: String, mimeType: String) =
        groupManager.addFileToProject(groupId, uri, fileName, mimeType)

    fun openProjectInstructionsDialog() {
        _uiState.value = _uiState.value.copy(showSystemPromptDialog = true)
    }

    fun removeFileFromProject(groupId: String, attachmentIndex: Int) =
        groupManager.removeFileFromProject(groupId, attachmentIndex)

    fun navigateToGroup(groupId: String) = groupManager.navigateToGroup(groupId)

    fun updateGroupSystemPrompt(systemPrompt: String) = groupManager.updateGroupSystemPrompt(systemPrompt)

    // Group context menu functions
    fun showGroupContextMenu(group: ChatGroup, position: androidx.compose.ui.unit.DpOffset) =
        groupManager.showGroupContextMenu(group, position)

    fun hideGroupContextMenu() = groupManager.hideGroupContextMenu()

    fun showGroupRenameDialog(group: ChatGroup) = groupManager.showGroupRenameDialog(group)

    fun hideGroupRenameDialog() = groupManager.hideGroupRenameDialog()

    fun renameGroup(group: ChatGroup, newName: String) = groupManager.renameGroup(group, newName)

    fun makeGroupProject(group: ChatGroup) = groupManager.makeGroupProject(group)

    fun createNewConversationInGroup(group: ChatGroup) = groupManager.createNewConversationInGroup(group)

    fun showGroupDeleteConfirmation(group: ChatGroup) = groupManager.showGroupDeleteConfirmation(group)

    fun hideGroupDeleteConfirmation() = groupManager.hideGroupDeleteConfirmation()

    fun deleteGroup(group: ChatGroup) {
        // Delegate to GroupManager
        groupManager.deleteGroup(group)

        // Handle screen navigation check (GroupManager doesn't have access to _currentScreen)
        if (_currentScreen.value is Screen.Group && (_currentScreen.value as Screen.Group).groupId == group.group_id) {
            navigateToScreen(Screen.ChatHistory)
        }
    }

    // Quick Settings & Chat Export
    fun toggleQuickSettings() {
        _uiState.value = _uiState.value.copy(
            quickSettingsExpanded = !_uiState.value.quickSettingsExpanded
        )
    }

    // ==================== Tool Toggle Dropdown ====================
    fun toggleToolDropdown() {
        _uiState.value = _uiState.value.copy(
            showToolToggleDropdown = !_uiState.value.showToolToggleDropdown
        )
    }

    fun dismissToolDropdown() {
        _uiState.value = _uiState.value.copy(
            showToolToggleDropdown = false
        )
    }

    /**
     * Toggle tool exclusion. If exclude is true, adds the tool to excluded list.
     * If exclude is false, removes it from excluded list.
     */
    fun toggleToolExclusion(toolId: String, exclude: Boolean) {
        // Applied to the stored settings (the in-memory copy may predate a sync)
        val updatedSettings = repository.updateAppSettings { settings ->
            val currentExcluded = settings.excludedToolIds
            val updatedExcluded = if (exclude) {
                if (toolId !in currentExcluded) currentExcluded + toolId else currentExcluded
            } else {
                currentExcluded - toolId
            }
            settings.copy(excludedToolIds = updatedExcluded)
        }
        _appSettings.value = updatedSettings

        // Also update UI state
        _uiState.value = _uiState.value.copy(excludedToolIds = updatedSettings.excludedToolIds)
    }

    /**
     * Get the list of enabled tool IDs (from app settings)
     */
    fun getEnabledToolIds(): List<String> {
        val tools = _appSettings.value.enabledTools.toMutableList()
        // Add group conversations tool if in a group
        if (_uiState.value.currentChat?.group != null) {
            if (!tools.contains("get_current_group_conversations")) {
                tools.add("get_current_group_conversations")
            }
        }
        return tools
    }

    // ==================== Thinking Budget Settings (delegated to TopBarManager) ====================
    fun onThinkingBudgetButtonClick() = topBarManager.onThinkingBudgetButtonClick()
    fun showThinkingBudgetPopup() = topBarManager.showThinkingBudgetPopup()
    fun hideThinkingBudgetPopup() = topBarManager.hideThinkingBudgetPopup()
    fun setThinkingBudgetValue(value: ThinkingBudgetValue) = topBarManager.setThinkingBudgetValue(value)
    fun setThinkingEffort(level: String) = topBarManager.setThinkingEffort(level)
    fun setThinkingTokenBudget(tokens: Int) = topBarManager.setThinkingTokenBudget(tokens)
    fun resetThinkingBudgetToDefault() = topBarManager.resetThinkingBudgetToDefault()

    // ==================== Temperature Settings (delegated to TopBarManager) ====================
    fun onTemperatureButtonClick() = topBarManager.onTemperatureButtonClick()
    fun hideTemperaturePopup() = topBarManager.hideTemperaturePopup()
    fun setTemperatureValue(value: Float?) = topBarManager.setTemperatureValue(value)
    fun resetTemperatureToDefault() = topBarManager.resetTemperatureToDefault()

    // ==================== Search Methods (delegated to SearchManager) ====================
    fun enterSearchMode() = searchManager.enterSearchMode()
    fun enterConversationSearchMode() = searchManager.enterConversationSearchMode()
    fun enterSearchModeWithQuery(query: String) = searchManager.enterSearchModeWithQuery(query)
    fun exitSearchMode() = searchManager.exitSearchMode()
    fun updateSearchQuery(query: String) = searchManager.updateSearchQuery(query)
    fun performSearch() = searchManager.performSearch()
    fun performConversationSearch() = searchManager.performConversationSearch()
    fun clearSearchContext() = searchManager.clearSearchContext()

    // ==================== Child Lock Methods (delegated to ChildLockManager) ====================
    fun setupChildLock(password: String, startTime: String, endTime: String, deviceId: String) =
        childLockManager.setupChildLock(password, startTime, endTime, deviceId)
    fun verifyAndDisableChildLock(password: String, deviceId: String): Boolean =
        childLockManager.verifyAndDisableChildLock(password, deviceId)
    fun updateChildLockSettings(enabled: Boolean, password: String, startTime: String, endTime: String) =
        childLockManager.updateChildLockSettings(enabled, password, startTime, endTime)
    fun isChildLockActive(): Boolean = childLockManager.isChildLockActive()
    fun getLockEndTime(): String = childLockManager.getLockEndTime()

    // ==================== Integration Management (delegated to AuthManager) ====================
    // GitHub Integration
    fun connectGitHub(): String = authManager.connectGitHub()
    fun getGitHubAuthUrl(): Pair<String, String> = authManager.getGitHubAuthUrl()
    fun handleGitHubCallback(code: String, state: String): String = authManager.handleGitHubCallback(code, state)
    fun disconnectGitHub() = authManager.disconnectGitHub()
    fun isGitHubConnected(): Boolean = authManager.isGitHubConnected()
    fun getGitHubConnection(): GitHubConnection? = authManager.getGitHubConnection()
    fun initializeGitHubToolsIfConnected() = authManager.initializeGitHubToolsIfConnected()

    // Google Workspace Integration
    fun getGoogleSignInIntent(): Intent = authManager.getGoogleSignInIntent()
    fun handleGoogleSignInResult(data: Intent) = authManager.handleGoogleSignInResult(data)
    fun isGoogleWorkspaceConnected(): Boolean = authManager.isGoogleWorkspaceConnected()
    fun getGoogleWorkspaceConnection(): GoogleWorkspaceConnection? = authManager.getGoogleWorkspaceConnection()
    fun disconnectGoogleWorkspace() = authManager.disconnectGoogleWorkspace()
    fun updateGoogleWorkspaceServices(gmail: Boolean, calendar: Boolean, drive: Boolean) =
        authManager.updateGoogleWorkspaceServices(gmail, calendar, drive)
    fun initializeGoogleWorkspaceToolsIfConnected() = authManager.initializeGoogleWorkspaceToolsIfConnected()

    // ==================== Tool Management (delegated to ToolManager) ====================
    fun enableTool(toolId: String) = toolManager.enableTool(toolId)
    fun disableTool(toolId: String) = toolManager.disableTool(toolId)

    // ==================== Skills Management ====================
    fun getInstalledSkills() = repository.getInstalledSkills()
    fun createSkill(name: String, description: String, body: String = "") = repository.createSkill(name, description, body)
    fun deleteSkill(skillName: String): Boolean {
        val result = repository.deleteSkill(skillName)
        // Disable skill tools if no more skills
        if (result && repository.getInstalledSkills().none { it.isEnabled }) {
            val toolRegistry = com.example.ApI.tools.ToolRegistry.getInstance()
            for (toolId in toolRegistry.getSkillToolIds()) {
                toolManager.disableTool(toolId)
            }
        }
        return result
    }
    fun setSkillEnabled(skillName: String, enabled: Boolean) {
        repository.setSkillEnabled(skillName, enabled)
        // Ensure skill tools are enabled if any skills are active
        val hasEnabledSkills = repository.getInstalledSkills().any { it.isEnabled }
        val toolRegistry = com.example.ApI.tools.ToolRegistry.getInstance()
        if (hasEnabledSkills) {
            for (toolId in toolRegistry.getSkillToolIds()) {
                toolManager.enableTool(toolId)
            }
        } else {
            for (toolId in toolRegistry.getSkillToolIds()) {
                toolManager.disableTool(toolId)
            }
        }
    }
    fun getSkillMdContent(skillName: String) = repository.getSkillMdContent(skillName)
    fun importSkillFromText(content: String): com.example.ApI.data.model.InstalledSkill? {
        val skill = repository.importSkillFromText(content)
        if (skill != null) {
            // Auto-enable skill tools
            val toolRegistry = com.example.ApI.tools.ToolRegistry.getInstance()
            for (toolId in toolRegistry.getSkillToolIds()) {
                toolManager.enableTool(toolId)
            }
        }
        return skill
    }
    fun importSkillFromZip(inputStream: java.util.zip.ZipInputStream): com.example.ApI.data.model.InstalledSkill? {
        val skill = repository.skillsStorageManager.importFromZip(inputStream)
        if (skill != null) {
            val toolRegistry = com.example.ApI.tools.ToolRegistry.getInstance()
            for (toolId in toolRegistry.getSkillToolIds()) {
                toolManager.enableTool(toolId)
            }
        }
        return skill
    }
    fun getSkillsStorageManager() = repository.skillsStorageManager

    // ==================== Export/Import Methods (delegated to ExportImportManager) ====================
    fun openChatExportDialog() = exportImportManager.openChatExportDialog()
    fun closeChatExportDialog() = exportImportManager.closeChatExportDialog()
    fun enableChatExportEditing() = exportImportManager.enableChatExportEditing()
    fun updateChatExportContent(content: String) = exportImportManager.updateChatExportContent(content)
    fun shareChatExportContent() = exportImportManager.shareChatExportContent()
    fun saveChatExportToDownloads() = exportImportManager.saveChatExportToDownloads()
    fun importPendingChatJson() = exportImportManager.importPendingChatJson()
    fun attachPendingJsonAsFile() = exportImportManager.attachPendingJsonAsFile()
    fun dismissChatImportDialog() = exportImportManager.dismissChatImportDialog()
    // Share Link
    fun createShareLink() = exportImportManager.createShareLink()
    fun deleteShareLink() = exportImportManager.deleteShareLink()
    fun updateShareLink() = exportImportManager.updateShareLink()
    fun copyShareLink() = exportImportManager.copyShareLink()
    fun toggleShareLinkMenu() = exportImportManager.toggleShareLinkMenu()
    fun dismissShareLinkMenu() = exportImportManager.dismissShareLinkMenu()

    // ==================== Branching System (delegated to BranchingManager) ====================
    fun getBranchInfoForMessage(message: Message): BranchInfo? = branchingManager.getBranchInfoForMessage(message)
    fun navigateToNextVariant(nodeId: String) = branchingManager.navigateToNextVariant(nodeId)
    fun navigateToPreviousVariant(nodeId: String) = branchingManager.navigateToPreviousVariant(nodeId)
    fun navigateToVariant(nodeId: String, variantIndex: Int) = branchingManager.navigateToVariant(nodeId, variantIndex)
    fun ensureBranchingStructure() = branchingManager.ensureBranchingStructure()

    // ==================== Remote Sync ====================

    /**
     * Observe syncChangeTick from the repository. When it increments, reload what a sync may
     * have changed so pulled-in changes appear without a manual refresh.
     */
    private fun observeSyncChangeTick() {
        viewModelScope.launch {
            repository.syncChangeTick.collect { tick ->
                if (tick > 0L) {
                    try {
                        reloadAfterSync()
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Reload after sync failed", e)
                    }
                }
            }
        }
    }

    /**
     * Refresh settings, the chat list, groups, the current chat and the current group (and the
     * system prompt shown for them) from disk. A current chat deleted elsewhere falls back
     * gracefully ([SyncReload]); an active stream's state (loading/streaming ids, streamed text)
     * and the unsent draft are left alone.
     */
    private suspend fun reloadAfterSync() {
        repeat(RELOAD_ATTEMPTS) {
            val before = _uiState.value
            val settingsBefore = _appSettings.value
            val (settings, history) = withContext(Dispatchers.IO) {
                val settings = repository.loadAppSettings()
                settings to repository.loadChatHistory(settings.current_user)
            }
            while (true) {
                when (applyReload(before, settingsBefore, settings, history)) {
                    true -> {
                        authManager.refreshIntegrationToolsAfterSync()
                        return
                    }
                    false -> break  // re-read
                    null -> continue  // another state update raced the apply: recompute
                }
            }
        }
        Log.w("ChatViewModel", "Reload after sync skipped: local changes kept racing it")
    }

    /**
     * Apply a reload read from disk while the UI state was [before] and the settings
     * [settingsBefore]. False: the chat state or settings changed meanwhile (a local write the
     * read may predate — e.g. a new chat, a buffered message — must not be overlaid with an
     * older disk copy; re-read). Null: another state update raced this one (recompute).
     */
    private fun applyReload(before: ChatUiState, settingsBefore: AppSettings, settings: AppSettings, history: UserChatHistory): Boolean? {
        val state = _uiState.value
        if (_appSettings.value !== settingsBefore || state.chatHistory !== before.chatHistory ||
            state.currentChat !== before.currentChat || state.groups !== before.groups ||
            state.currentGroup !== before.currentGroup
        ) {
            return false  // written meanwhile: the disk copy read may predate it
        }
        val busyChatIds = state.loadingChatIds + state.streamingChatIds
        val hasDraft = state.currentMessage.isNotBlank() || state.selectedFiles.isNotEmpty()
        val currentChat = SyncReload.currentChatAfterReload(history.chat_history, state.currentChat, busyChatIds, hasDraft)
        val currentGroup = SyncReload.currentGroupAfterReload(history.groups, state.currentGroup)
        val chatSwitched = currentChat?.chat_id != state.currentChat?.chat_id
        val onGroupScreen = _currentScreen.value is Screen.Group

        val systemPrompt = when {
            state.showSystemPromptDialog -> state.systemPrompt  // being edited: leave it
            onGroupScreen -> currentGroup?.system_prompt ?: state.systemPrompt
            currentChat != null -> currentChat.systemPrompt
            chatSwitched -> ""
            else -> state.systemPrompt
        }

        // The selected model is an account setting another device may have changed
        val settingsProvider = state.availableProviders.find { it.provider == settings.selected_provider }
            ?.takeIf { p -> p.models.any { it.name == settings.selected_model } }
        val modelChanged = settingsProvider != null &&
            (settingsProvider.provider != state.currentProvider?.provider || settings.selected_model != state.currentModel)

        var updated = state.copy(
            chatHistory = history.chat_history,
            groups = history.groups,
            currentChat = currentChat,
            currentGroup = currentGroup,
            systemPrompt = systemPrompt,
            excludedToolIds = settings.excludedToolIds
        )
        if (chatSwitched && state.isEditMode) {
            updated = updated.copy(editingMessage = null, isEditMode = false)
        }
        if (modelChanged) {
            val webSearchSupport = modelSelectionManager.getWebSearchSupport(settingsProvider!!.provider, settings.selected_model)
            updated = updated.copy(
                currentProvider = settingsProvider,
                currentModel = settings.selected_model,
                webSearchSupport = webSearchSupport,
                webSearchEnabled = when (webSearchSupport) {
                    WebSearchSupport.REQUIRED -> true
                    WebSearchSupport.OPTIONAL -> state.webSearchEnabled
                    WebSearchSupport.UNSUPPORTED -> false
                }
            )
        }
        if (!_uiState.compareAndSet(state, updated)) return null
        _appSettings.value = settings

        if (onGroupScreen && currentGroup == null) {
            navigateToScreen(Screen.ChatHistory)
        }
        return true
    }

    /** Called on Activity onResume: pull latest changes now and periodically while resumed. */
    fun onAppResume() {
        repository.pullNow()
        foregroundSyncTicker.start()
    }

    /** Called on Activity onPause: stop the periodic foreground pull. */
    fun onAppPause() {
        foregroundSyncTicker.stop()
    }

    /** Update the "Enable remote sync" toggle. Persists the change and starts sync if turned on. */
    fun updateRemoteSyncEnabled(enabled: Boolean) {
        val updatedSettings = repository.updateAppSettings {
            it.copy(
                remoteSync = it.remoteSync.copy(enabled = enabled)
            )
        }
        _appSettings.value = updatedSettings
        if (enabled) repository.startSync()
    }

    /** Update the remote sync server URL. */
    fun updateRemoteSyncServerUrl(url: String) {
        val updatedSettings = repository.updateAppSettings {
            it.copy(
                remoteSync = it.remoteSync.copy(serverBaseUrl = url)
            )
        }
        _appSettings.value = updatedSettings
    }

    /** Update the "Also sync API keys" toggle. */
    fun updateRemoteSyncApiKeys(syncApiKeys: Boolean) {
        val updatedSettings = repository.updateAppSettings {
            it.copy(
                remoteSync = it.remoteSync.copy(syncApiKeys = syncApiKeys)
            )
        }
        _appSettings.value = updatedSettings
    }

    /** Trigger an immediate pull from the server. */
    fun triggerSyncNow() {
        repository.pullNow()
    }

    /** Test the sync connection. Returns true on success, false on failure. */
    suspend fun testSyncConnection(): Boolean = repository.testSyncConnection()

    // ── Google Sign-In for sync ───────────────────────────────────────────────

    /**
     * Returns the Google Sign-In intent to launch via an ActivityResultLauncher.
     * Signs out any existing cached session first so the account chooser always
     * appears (allowing account switching).
     */
    fun getSyncSignInIntent(): android.content.Intent = syncSignInProvider.getSignInIntent()

    /**
     * Processes the ActivityResult returned after the user completes (or cancels)
     * the Google sign-in flow.  On success, exchanges the ID token for a
     * server-minted sync token, runs the one-time local user migration, and
     * reloads all user data so the UI reflects the (potentially new) username.
     */
    fun handleSyncSignInResult(data: android.content.Intent) {
        viewModelScope.launch {
            _syncSignInInProgress.value = true
            try {
                val identityResult = syncSignInProvider.handleSignInResult(data)
                identityResult.fold(
                    onSuccess = { identity ->
                        val signInResult = repository.signInToSync(identity)
                        signInResult.fold(
                            onSuccess = { username ->
                                reloadUserDataAfterSignIn()
                                showSnackbar(
                                    context.getString(R.string.remote_sync_sign_in_success, username)
                                )
                            },
                            onFailure = { error ->
                                showSnackbar(
                                    error.message
                                        ?: context.getString(R.string.remote_sync_sign_in_error)
                                )
                            }
                        )
                    },
                    onFailure = { error ->
                        showSnackbar(
                            error.message
                                ?: context.getString(R.string.remote_sync_sign_in_error)
                        )
                    }
                )
            } finally {
                _syncSignInInProgress.value = false
            }
        }
    }

    /**
     * Signs out of remote sync: disables sync, clears the server-minted token
     * and account email.  Local data and the current username are preserved.
     */
    fun signOutOfSync() {
        repository.signOutOfSync()
        val updatedSettings = repository.loadAppSettings()
        _appSettings.value = updatedSettings
    }

    /**
     * Reloads all user-scoped data after a successful sign-in (current_user may
     * have changed due to the one-time default→username migration).
     */
    private fun reloadUserDataAfterSignIn() {
        viewModelScope.launch {
            val settings = repository.loadAppSettings()
            _appSettings.value = settings

            repository.initializeCustomProviders(settings.current_user)
            repository.initializeFullCustomProviders(settings.current_user)

            val allProviders = repository.loadProviders()
            val activeProviders = repository.loadApiKeys(settings.current_user)
                .filter { it.isActive }.map { it.provider }
            val providers = allProviders.filter { it.provider in activeProviders }
            val currentProvider = providers.find { it.provider == settings.selected_provider }
                ?: providers.firstOrNull()

            val chatHistory = repository.loadChatHistory(settings.current_user)
            repository.cleanupEmptyChats(settings.current_user)
            val refreshedHistory = repository.loadChatHistory(settings.current_user)

            _uiState.value = _uiState.value.copy(
                availableProviders = providers,
                currentProvider = currentProvider,
                chatHistory = refreshedHistory.chat_history,
                groups = refreshedHistory.groups,
                currentChat = refreshedHistory.chat_history.lastOrNull()
            )
        }
    }

}







/** Disk reads a sync reload makes before giving up on local writes racing it (the next tick retries). */
private const val RELOAD_ATTEMPTS = 5
