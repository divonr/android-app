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

        // Find and stop the active request in the service
        val activeRequests = streamingService?.getActiveRequests()
        val request = activeRequests?.values?.find { it.chatId == chatId }
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

                // Save the message to chat history
                repository.addResponseToCurrentVariant(currentUser, chatId, assistantMessage)
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
        unbindFromStreamingService()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val settings = repository.loadAppSettings()
            _appSettings.value = settings

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
                webSearchEnabled = webSearchEnabled
            )

            // Update settings if we changed anything
            if (currentModel != settings.selected_model ||
                (currentProvider?.provider != settings.selected_provider)) {
                val updatedSettings = settings.copy(
                    selected_provider = currentProvider?.provider ?: "openai",
                    selected_model = currentModel
                )
                repository.saveAppSettings(updatedSettings)
                _appSettings.value = updatedSettings
            }

            // Show welcome screen if not skipped
            if (!settings.skipWelcomeScreen) {
                _currentScreen.value = Screen.Welcome
            }

            // Initialize integration tools if connected
            initializeGitHubToolsIfConnected()
            initializeGoogleWorkspaceToolsIfConnected()
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
    fun showProviderSelector() = modelSelectionManager.showProviderSelector()
    fun hideProviderSelector() = modelSelectionManager.hideProviderSelector()
    fun showModelSelector() = modelSelectionManager.showModelSelector()
    fun hideModelSelector() = modelSelectionManager.hideModelSelector()
    fun refreshModels() = modelSelectionManager.refreshModels()
    fun refreshAvailableProviders() = modelSelectionManager.refreshAvailableProviders()

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
        val updatedSettings = _appSettings.value.copy(multiMessageMode = enabled)
        repository.saveAppSettings(updatedSettings)
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
        val updatedSettings = _appSettings.value.copy(skipWelcomeScreen = skip)
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
    }

    // ==================== Full Chat History Export/Import (delegated to ExportImportManager) ====================
    fun exportChatHistory() = exportImportManager.exportChatHistory()
    fun importChatHistoryFromUri(uri: Uri) = exportImportManager.importChatHistoryFromUri(uri)

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

    // ==================== Branching System (delegated to BranchingManager) ====================
    fun getBranchInfoForMessage(message: Message): BranchInfo? = branchingManager.getBranchInfoForMessage(message)
    fun navigateToNextVariant(nodeId: String) = branchingManager.navigateToNextVariant(nodeId)
    fun navigateToPreviousVariant(nodeId: String) = branchingManager.navigateToPreviousVariant(nodeId)
    fun navigateToVariant(nodeId: String, variantIndex: Int) = branchingManager.navigateToVariant(nodeId, variantIndex)
    fun ensureBranchingStructure() = branchingManager.ensureBranchingStructure()

}






