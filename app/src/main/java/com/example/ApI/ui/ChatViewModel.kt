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

    // Service binding for streaming requests
    private var streamingService: StreamingService? = null
    private var serviceBound = false

    // Group management delegate
    private val groupManager: GroupManager by lazy {
        GroupManager(
            repository = repository,
            context = context,
            scope = viewModelScope,
            appSettings = _appSettings,
            uiState = _uiState,
            updateUiState = { newState -> _uiState.value = newState },
            navigateToScreen = { screen -> navigateToScreen(screen) }
        )
    }

    // Integration management delegate (GitHub + Google Workspace)
    private val authManager: AuthManager by lazy {
        AuthManager(
            repository = repository,
            context = context,
            scope = viewModelScope,
            appSettings = _appSettings,
            updateAppSettings = { newSettings -> _appSettings.value = newSettings },
            showSnackbar = { message -> showSnackbar(message) }
        )
    }

    // Search functionality delegate
    private val searchManager: SearchManager by lazy {
        SearchManager(
            repository = repository,
            appSettings = _appSettings,
            uiState = _uiState,
            updateUiState = { newState -> _uiState.value = newState },
            getCurrentScreen = { _currentScreen.value }
        )
    }

    // Child lock (parental controls) delegate
    private val childLockManager: ChildLockManager by lazy {
        ChildLockManager(
            repository = repository,
            context = context,
            scope = viewModelScope,
            appSettings = _appSettings,
            uiState = _uiState,
            updateAppSettings = { newSettings -> _appSettings.value = newSettings },
            updateUiState = { newState -> _uiState.value = newState }
        )
    }

    // File management delegate
    private val attachmentManager: AttachmentManager by lazy {
        AttachmentManager(
            repository = repository,
            context = context,
            scope = viewModelScope,
            uiState = _uiState,
            updateUiState = { newState -> _uiState.value = newState }
        )
    }

    // Export/Import functionality delegate
    private val exportImportManager: ExportImportManager by lazy {
        ExportImportManager(
            repository = repository,
            context = context,
            scope = viewModelScope,
            appSettings = _appSettings,
            uiState = _uiState,
            updateUiState = { newState -> _uiState.value = newState },
            selectChat = { chat -> selectChat(chat) },
            navigateToScreen = { screen -> navigateToScreen(screen) },
            addFileFromUri = { uri, name, mime -> attachmentManager.addFileFromUri(uri, name, mime) }
        )
    }

    // Branching/variant navigation delegate
    private val branchingManager: BranchingManager by lazy {
        BranchingManager(
            repository = repository,
            scope = viewModelScope,
            appSettings = _appSettings,
            uiState = _uiState,
            updateUiState = { newState -> _uiState.value = newState }
        )
    }

    // Top bar controls delegate (temperature, thinking budget, text direction)
    private val topBarManager: TopBarManager by lazy {
        TopBarManager(
            context = context,
            uiState = _uiState,
            updateUiState = { newState -> _uiState.value = newState }
        )
    }

    // Navigation and chat history management delegate
    private val navigationManager: NavigationManager by lazy {
        NavigationManager(
            repository = repository,
            context = context,
            scope = viewModelScope,
            appSettings = _appSettings,
            uiState = _uiState,
            currentScreen = _currentScreen,
            updateAppSettings = { newSettings -> _appSettings.value = newSettings },
            updateUiState = { newState -> _uiState.value = newState },
            refreshAvailableProviders = { refreshAvailableProviders() }
        )
    }

    // Provider and model selection management delegate
    private val modelSelectionManager: ModelSelectionManager by lazy {
        ModelSelectionManager(
            repository = repository,
            context = context,
            scope = viewModelScope,
            appSettings = _appSettings,
            uiState = _uiState,
            updateAppSettings = { newSettings -> _appSettings.value = newSettings },
            updateUiState = { newState -> _uiState.value = newState }
        )
    }

    // Message operations delegate (send, edit, delete, resend)
    private val messageOperationsManager: MessageOperationsManager by lazy {
        MessageOperationsManager(
            repository = repository,
            context = context,
            scope = viewModelScope,
            appSettings = _appSettings,
            uiState = _uiState,
            updateUiState = { newState -> _uiState.value = newState },
            getCurrentDateTimeISO = { getCurrentDateTimeISO() },
            getEffectiveSystemPrompt = { getEffectiveSystemPrompt() },
            getCurrentChatProjectGroup = { getCurrentChatProjectGroup() },
            getEnabledToolSpecifications = { getEnabledToolSpecifications() },
            startStreamingRequest = { requestId, chatId, username, provider, modelName, messages, systemPrompt, webSearchEnabled, projectAttachments, enabledTools, thinkingBudget, temperature ->
                startStreamingRequest(requestId, chatId, username, provider, modelName, messages, systemPrompt, webSearchEnabled, projectAttachments, enabledTools, thinkingBudget, temperature)
            },
            createNewChat = { previewName -> createNewChat(previewName) }
        )
    }

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
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
                    handleStreamingEvent(event)
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
        handleSharedFiles()
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
     * Handle streaming events from the StreamingService
     */
    private fun handleStreamingEvent(event: StreamingEvent) {
        when (event) {
            is StreamingEvent.PartialResponse -> {
                // Update per-chat streaming text
                val chatId = event.chatId
                val currentText = _uiState.value.streamingTextByChat[chatId] ?: ""
                _uiState.value = _uiState.value.copy(
                    streamingTextByChat = _uiState.value.streamingTextByChat + (chatId to currentText + event.text)
                )
            }

            is StreamingEvent.Complete -> {
                val chatId = event.chatId
                Log.d("ChatViewModel", "Streaming complete for chat: $chatId")

                viewModelScope.launch {
                    // Reload chat history to get the saved message
                    val currentUser = _appSettings.value.current_user
                    val refreshedHistory = repository.loadChatHistory(currentUser)
                    val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

                    // Clear streaming state for this chat (including thoughts state)
                    _uiState.value = _uiState.value.copy(
                        loadingChatIds = _uiState.value.loadingChatIds - chatId,
                        streamingChatIds = _uiState.value.streamingChatIds - chatId,
                        streamingTextByChat = _uiState.value.streamingTextByChat - chatId,
                        streamingThoughtsTextByChat = _uiState.value.streamingThoughtsTextByChat - chatId,
                        completedThinkingDurationByChat = _uiState.value.completedThinkingDurationByChat - chatId,
                        chatHistory = refreshedHistory.chat_history,
                        currentChat = if (_uiState.value.currentChat?.chat_id == chatId) refreshedChat else _uiState.value.currentChat
                    )

                    // Handle title generation if this is the current chat
                    if (refreshedChat != null && _uiState.value.currentChat?.chat_id == chatId) {
                        handleTitleGeneration(refreshedChat)
                    }
                }
            }

            is StreamingEvent.Error -> {
                val chatId = event.chatId
                Log.e("ChatViewModel", "Streaming error for chat: $chatId - ${event.error}")

                viewModelScope.launch {
                    // Reload chat history
                    val currentUser = _appSettings.value.current_user
                    val refreshedHistory = repository.loadChatHistory(currentUser)
                    val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

                    // Check for Cohere image not supported error
                    if (event.error.contains("image content is not supported for this model")) {
                        Toast.makeText(
                            context,
                            "בחרו מודל שתומך בתמונה, כמו command-a-vision-07-2025",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            snackbarMessage = "שגיאה: ${event.error}"
                        )
                    }

                    // Clear streaming state for this chat (including thoughts state)
                    _uiState.value = _uiState.value.copy(
                        loadingChatIds = _uiState.value.loadingChatIds - chatId,
                        streamingChatIds = _uiState.value.streamingChatIds - chatId,
                        streamingTextByChat = _uiState.value.streamingTextByChat - chatId,
                        streamingThoughtsTextByChat = _uiState.value.streamingThoughtsTextByChat - chatId,
                        completedThinkingDurationByChat = _uiState.value.completedThinkingDurationByChat - chatId,
                        thinkingChatIds = _uiState.value.thinkingChatIds - chatId,
                        thinkingStartTimeByChat = _uiState.value.thinkingStartTimeByChat - chatId,
                        chatHistory = refreshedHistory.chat_history,
                        currentChat = if (_uiState.value.currentChat?.chat_id == chatId) refreshedChat else _uiState.value.currentChat
                    )
                }
            }

            is StreamingEvent.StatusChange -> {
                // Status changes are mainly for logging/debugging
                Log.d("ChatViewModel", "Status change for ${event.chatId}: ${event.status}")
            }

            is StreamingEvent.ToolCallRequest -> {
                val chatId = event.chatId
                val requestId = event.requestId
                Log.d("ChatViewModel", "Tool call request for chat: $chatId, tool: ${event.toolCall.toolId}")

                viewModelScope.launch {
                    // Show tool execution indicator
                    _uiState.value = _uiState.value.copy(
                        executingToolCall = ExecutingToolInfo(
                            toolId = event.toolCall.toolId,
                            toolName = event.toolCall.toolId,
                            startTime = getCurrentDateTimeISO()
                        )
                    )

                    // Execute the tool
                    val result = executeToolCall(event.toolCall)

                    // Clear tool execution indicator
                    _uiState.value = _uiState.value.copy(executingToolCall = null)

                    // Provide result back to service
                    streamingService?.provideToolResult(requestId, result)
                }
            }

            is StreamingEvent.MessagesAdded -> {
                // Messages were saved mid-stream (preceding text + tool messages)
                // Reload chat history and clear streaming text so UI shows them separately
                val chatId = event.chatId
                Log.d("ChatViewModel", "Messages added mid-stream for chat: $chatId")

                viewModelScope.launch {
                    // Reload chat history to show the newly saved messages
                    val currentUser = _appSettings.value.current_user
                    val refreshedHistory = repository.loadChatHistory(currentUser)
                    val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

                    // Clear streaming text but keep streaming state active
                    // This ensures saved messages appear as separate bubbles
                    // and new streaming content starts fresh
                    _uiState.value = _uiState.value.copy(
                        streamingTextByChat = _uiState.value.streamingTextByChat + (chatId to ""),
                        chatHistory = refreshedHistory.chat_history,
                        currentChat = if (_uiState.value.currentChat?.chat_id == chatId) refreshedChat else _uiState.value.currentChat
                    )
                }
            }

            is StreamingEvent.ThinkingStarted -> {
                val chatId = event.chatId
                Log.d("ChatViewModel", "Thinking started for chat: $chatId")
                _uiState.value = _uiState.value.copy(
                    thinkingChatIds = _uiState.value.thinkingChatIds + chatId,
                    thinkingStartTimeByChat = _uiState.value.thinkingStartTimeByChat + (chatId to System.currentTimeMillis())
                )
            }

            is StreamingEvent.ThinkingPartial -> {
                val chatId = event.chatId
                val currentThoughts = _uiState.value.streamingThoughtsTextByChat[chatId] ?: ""
                _uiState.value = _uiState.value.copy(
                    streamingThoughtsTextByChat = _uiState.value.streamingThoughtsTextByChat + (chatId to currentThoughts + event.text)
                )
            }

            is StreamingEvent.ThinkingComplete -> {
                val chatId = event.chatId
                Log.d("ChatViewModel", "Thinking complete for chat: $chatId, duration: ${event.durationSeconds}s, status: ${event.status}")
                // Mark thinking as done but KEEP the thoughts text visible during response streaming
                // Store the completed duration for display (no longer live counting)
                _uiState.value = _uiState.value.copy(
                    thinkingChatIds = _uiState.value.thinkingChatIds - chatId,
                    thinkingStartTimeByChat = _uiState.value.thinkingStartTimeByChat - chatId,
                    // DON'T clear streamingThoughtsTextByChat - keep it visible during response streaming
                    completedThinkingDurationByChat = _uiState.value.completedThinkingDurationByChat + (chatId to event.durationSeconds)
                )
            }
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
            putExtra(StreamingService.EXTRA_PROVIDER_JSON, json.encodeToString(provider))
            putExtra(StreamingService.EXTRA_MODEL_NAME, modelName)
            putExtra(StreamingService.EXTRA_SYSTEM_PROMPT, systemPrompt)
            putExtra(StreamingService.EXTRA_WEB_SEARCH_ENABLED, webSearchEnabled)
            putExtra(StreamingService.EXTRA_MESSAGES_JSON, json.encodeToString(messages))
            putExtra(StreamingService.EXTRA_PROJECT_ATTACHMENTS_JSON, json.encodeToString(projectAttachments))
            putExtra(StreamingService.EXTRA_ENABLED_TOOLS_JSON, json.encodeToString(enabledTools))
            // Add thinking budget if not None
            if (thinkingBudget != ThinkingBudgetValue.None) {
                putExtra(StreamingService.EXTRA_THINKING_BUDGET_JSON, json.encodeToString(thinkingBudget))
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

            // Reload chat history
            val refreshedHistory = repository.loadChatHistory(currentUser)
            val refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

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
                handleTitleGeneration(refreshedChat)
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
            val chatHistory = repository.loadChatHistory(settings.current_user)
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
                showChatHistory = true
            )
            
            // Debug log
            println("DEBUG: Loaded ${providers.size} providers")
            println("DEBUG: Current provider: ${currentProvider?.provider}")
            println("DEBUG: Current model: $currentModel")
            println("DEBUG: Loaded ${chatHistory.chat_history.size} existing chats")

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

    // ==================== Message Operations (delegated to MessageOperationsManager) ====================
    fun sendMessage() = messageOperationsManager.sendMessage()
    fun sendBufferedBatch() = messageOperationsManager.sendBufferedBatch()
    fun deleteMessage(message: Message) = messageOperationsManager.deleteMessage(message)
    fun startEditingMessage(message: Message) = messageOperationsManager.startEditingMessage(message)
    fun finishEditingMessage() = messageOperationsManager.finishEditingMessage()
    fun confirmEditAndResend() = messageOperationsManager.confirmEditAndResend()
    fun cancelEditingMessage() = messageOperationsManager.cancelEditingMessage()
    fun resendFromMessage(message: Message) = messageOperationsManager.resendFromMessage(message)

    
    // Title Generation and User Settings methods
    
    fun updateTitleGenerationSettings(newSettings: TitleGenerationSettings) {
        val currentSettings = _appSettings.value
        val updatedSettings = currentSettings.copy(titleGenerationSettings = newSettings)
        
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
    }
    
    fun getAvailableProvidersForTitleGeneration(): List<String> {
        val currentUser = _appSettings.value.current_user
        val apiKeys = repository.loadApiKeys(currentUser)
            .filter { it.isActive }
            .map { it.provider }
        
        return listOf("openai", "anthropic", "google", "poe", "cohere", "openrouter").filter { provider ->
            apiKeys.contains(provider)
        }
    }
    
    private suspend fun handleTitleGeneration(chat: Chat) {
        val titleGenerationSettings = _appSettings.value.titleGenerationSettings
        
        // Skip if title generation is disabled
        if (!titleGenerationSettings.enabled) return
        
        // Count assistant messages in this conversation
        val assistantMessages = chat.messages.filter { it.role == "assistant" }
        val assistantMessageCount = assistantMessages.size
        
        val shouldGenerateTitle = when {
            // First model response - always generate title
            assistantMessageCount == 1 -> true
            // Third model response and update on extension is enabled
            assistantMessageCount == 3 && titleGenerationSettings.updateOnExtension -> true
            else -> false
        }
        
        if (!shouldGenerateTitle) return
        
        try {
            val currentUser = _appSettings.value.current_user
            val providerToUse = if (titleGenerationSettings.provider == "auto") null else titleGenerationSettings.provider
            
            // Generate title using our helper function
            val generatedTitle = repository.generateConversationTitle(
                username = currentUser,
                conversationId = chat.chat_id,
                provider = providerToUse
            )
            
            // Update the conversation title if we got a valid response
            if (generatedTitle.isNotBlank() && generatedTitle != "שיחה חדשה") {
                updateChatPreviewName(chat.chat_id, generatedTitle)
            }
            
        } catch (e: Exception) {
            // If title generation fails, just continue without updating the title
            println("Title generation failed: ${e.message}")
        }
    }
    
    private suspend fun updateChatPreviewName(chatId: String, newTitle: String) {
        val currentUser = _appSettings.value.current_user
        val chatHistory = repository.loadChatHistory(currentUser)
        
        // Update the chat with the new preview name
        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.chat_id == chatId) {
                chat.copy(preview_name = newTitle)
            } else {
                chat
            }
        }
        
        val updatedHistory = chatHistory.copy(chat_history = updatedChats)
        repository.saveChatHistory(updatedHistory)
        
        // Update UI state
        val finalChatHistory = repository.loadChatHistory(currentUser).chat_history
        val updatedCurrentChat = finalChatHistory.find { it.chat_id == chatId }
        
        _uiState.value = _uiState.value.copy(
            currentChat = updatedCurrentChat,
            chatHistory = finalChatHistory
        )
    }
    
    // Chat Context Menu methods
    
    fun showChatContextMenu(chat: Chat, position: androidx.compose.ui.unit.DpOffset) {
        _uiState.value = _uiState.value.copy(
            chatContextMenu = ChatContextMenuState(chat, position)
        )
    }
    
    fun hideChatContextMenu() {
        _uiState.value = _uiState.value.copy(
            chatContextMenu = null
        )
    }
    
    fun showRenameDialog(chat: Chat) {
        _uiState.value = _uiState.value.copy(
            showRenameDialog = chat,
            chatContextMenu = null
        )
    }
    
    fun hideRenameDialog() {
        _uiState.value = _uiState.value.copy(
            showRenameDialog = null
        )
    }
    
    fun showDeleteConfirmation(chat: Chat) {
        _uiState.value = _uiState.value.copy(
            showDeleteConfirmation = chat,
            chatContextMenu = null
        )
    }
    
    fun hideDeleteConfirmation() {
        _uiState.value = _uiState.value.copy(
            showDeleteConfirmation = null
        )
    }

    fun showDeleteChatConfirmation() {
        _uiState.value = _uiState.value.copy(
            showDeleteChatConfirmation = _uiState.value.currentChat
        )
    }

    fun hideDeleteChatConfirmation() {
        _uiState.value = _uiState.value.copy(
            showDeleteChatConfirmation = null
        )
    }

    fun deleteCurrentChat() {
        val currentChat = _uiState.value.currentChat ?: return
        val currentUser = _appSettings.value.current_user

        viewModelScope.launch {
            val chatHistory = repository.loadChatHistory(currentUser)

            // Remove the chat from history
            val updatedChats = chatHistory.chat_history.filter { it.chat_id != currentChat.chat_id }
            val updatedHistory = chatHistory.copy(chat_history = updatedChats)

            // Save updated history
            repository.saveChatHistory(updatedHistory)

            // Update UI
            val finalChatHistory = repository.loadChatHistory(currentUser).chat_history

            // If we're deleting the current chat, switch to the most recent one or null
            val newCurrentChat = if (finalChatHistory.isNotEmpty()) {
                finalChatHistory.last() // Load the most recent chat
            } else {
                null
            }

            _uiState.value = _uiState.value.copy(
                chatHistory = finalChatHistory,
                currentChat = null, // Always set to null when deleting current chat
                systemPrompt = "",
                showDeleteChatConfirmation = null
            )

            // Always navigate back to chat history screen when deleting current chat
            navigateToScreen(Screen.ChatHistory)
        }
    }
    
    fun renameChat(chat: Chat, newName: String) {
        if (newName.isBlank()) return
        
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            updateChatPreviewName(chat.chat_id, newName.trim())
            hideRenameDialog()
        }
    }
    
    fun renameChatWithAI(chat: Chat) {
        // Close the menu immediately
        hideChatContextMenu()

        // Add chat to renaming set to show loading indicator
        _uiState.value = _uiState.value.copy(
            renamingChatIds = _uiState.value.renamingChatIds + chat.chat_id
        )

        viewModelScope.launch {
            try {
                val currentUser = _appSettings.value.current_user
                val titleGenerationSettings = _appSettings.value.titleGenerationSettings
                val providerToUse = if (titleGenerationSettings.provider == "auto") null else titleGenerationSettings.provider

                // Generate title using our helper function
                val generatedTitle = repository.generateConversationTitle(
                    username = currentUser,
                    conversationId = chat.chat_id,
                    provider = providerToUse
                )

                // Update the conversation title if we got a valid response
                if (generatedTitle.isNotBlank() && generatedTitle != "שיחה חדשה") {
                    updateChatPreviewName(chat.chat_id, generatedTitle)
                }

            } catch (e: Exception) {
                // If title generation fails, just log the error
                println("AI rename failed: ${e.message}")
            } finally {
                // Remove chat from renaming set
                _uiState.value = _uiState.value.copy(
                    renamingChatIds = _uiState.value.renamingChatIds - chat.chat_id
                )
            }
        }
    }
    
    fun deleteChat(chat: Chat) {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            val chatHistory = repository.loadChatHistory(currentUser)
            
            // Remove the chat from history
            val updatedChats = chatHistory.chat_history.filter { it.chat_id != chat.chat_id }
            val updatedHistory = chatHistory.copy(chat_history = updatedChats)
            
            // Save updated history
            repository.saveChatHistory(updatedHistory)
            
            // Update UI
            val finalChatHistory = repository.loadChatHistory(currentUser).chat_history
            
            // If we're deleting the current chat, switch to the most recent one or null
            val newCurrentChat = if (_uiState.value.currentChat?.chat_id == chat.chat_id) {
                finalChatHistory.lastOrNull()
            } else {
                _uiState.value.currentChat
            }
            
            _uiState.value = _uiState.value.copy(
                chatHistory = finalChatHistory,
                groups = chatHistory.groups,
                currentChat = newCurrentChat,
                showDeleteConfirmation = null
            )
        }
    }
    
    // Web Search functionality
    fun toggleWebSearch() {
        val currentSupport = _uiState.value.webSearchSupport
        val currentProvider = _uiState.value.currentProvider?.provider ?: ""
        val currentModel = _uiState.value.currentModel
        
        when (currentSupport) {
            WebSearchSupport.REQUIRED -> {
                // Show message that it cannot be disabled
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "לא ניתן לכבות את החיבור לאינטרנט עבור מודל $currentModel דרך הספק $currentProvider"
                )
            }
            WebSearchSupport.OPTIONAL -> {
                // Toggle the state
                _uiState.value = _uiState.value.copy(
                    webSearchEnabled = !_uiState.value.webSearchEnabled
                )
            }
            WebSearchSupport.UNSUPPORTED -> {
                // This shouldn't happen as the icon should be hidden, but just in case
                // Do nothing
            }
        }
    }

    private fun handleSharedFiles() {
        sharedIntent?.let { intent ->
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    // Handle shared text
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                        _uiState.value = _uiState.value.copy(currentMessage = sharedText)
                    }
                    // Handle single file sharing
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                        val fileName = getFileName(context, uri) ?: "shared_file"
                        val mimeType = resolveMimeType(context, uri, intent.type, fileName)
                        checkAndHandleJsonFile(uri, fileName, mimeType)
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    // Multiple files sharing
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                        val fileName = getFileName(context, uri) ?: "shared_file"
                        val mimeType = resolveMimeType(context, uri, intent.type, fileName)
                        checkAndHandleJsonFile(uri, fileName, mimeType)
                    }
                }
            }
        }
    }

    /**
     * Checks IMMEDIATELY if a shared file is a valid chat JSON.
     * If it is, shows the import dialog right away.
     * Otherwise, adds it as a regular file attachment (which waits for chat selection).
     */
    private fun checkAndHandleJsonFile(uri: Uri, fileName: String, mimeType: String) {
        // Check if it's a JSON file by extension or MIME type
        val isJsonFile = fileName.endsWith(".json", ignoreCase = true) ||
                        mimeType == "application/json" ||
                        mimeType == "text/json"

        if (isJsonFile) {
            viewModelScope.launch {
                try {
                    // Read the JSON content
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val jsonContent = inputStream?.bufferedReader()?.use { it.readText() }

                    if (jsonContent != null && repository.validateChatJson(jsonContent)) {
                        // Valid chat JSON - show the import choice dialog IMMEDIATELY
                        // Navigate to ChatHistory screen if not already there
                        _currentScreen.value = Screen.ChatHistory

                        _uiState.value = _uiState.value.copy(
                            pendingChatImport = PendingChatImport(
                                uri = uri,
                                fileName = fileName,
                                mimeType = mimeType,
                                jsonContent = jsonContent
                            )
                        )
                    } else {
                        // Invalid chat JSON - treat as regular file attachment
                        addFileFromUri(uri, fileName, mimeType)
                    }
                } catch (e: Exception) {
                    // Error reading file - treat as regular file attachment
                    addFileFromUri(uri, fileName, mimeType)
                }
            }
        } else {
            // Not a JSON file - treat as regular file attachment
            addFileFromUri(uri, fileName, mimeType)
        }
    }
    
    private fun getFileName(context: Context, uri: Uri): String? {
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName
    }

    /**
     * Resolves a specific MIME type from potentially wildcarded or incomplete intent type.
     * Falls back to contentResolver and then file extension inference.
     */
    private fun resolveMimeType(context: Context, uri: Uri, intentType: String?, fileName: String?): String {
        // Helper to check if MIME type is valid (has "/" and no wildcard)
        fun isValidMimeType(type: String?): Boolean {
            return !type.isNullOrBlank() && type.contains("/") && !type.contains("*")
        }

        // First try contentResolver which usually gives specific type
        val resolvedType = context.contentResolver.getType(uri)

        // If contentResolver gives a valid specific type, use it
        if (isValidMimeType(resolvedType)) {
            return resolvedType!!
        }

        // If intentType is specific (not wildcard), use it
        if (isValidMimeType(intentType)) {
            return intentType!!
        }

        // Infer from file extension
        val extension = fileName?.substringAfterLast('.', "")?.lowercase()
        val inferredType = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> null
        }

        if (inferredType != null) {
            return inferredType
        }

        // Last resort: use intentType if available (even if wildcard), otherwise default
        return intentType ?: "application/octet-stream"
    }

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

    // ==================== Tool Management ====================
    fun enableTool(toolId: String) {
        val currentSettings = _appSettings.value
        if (!currentSettings.enabledTools.contains(toolId)) {
            val updatedSettings = currentSettings.copy(
                enabledTools = currentSettings.enabledTools + toolId
            )
            repository.saveAppSettings(updatedSettings)
            _appSettings.value = updatedSettings
        }
    }

    fun disableTool(toolId: String) {
        val currentSettings = _appSettings.value
        val updatedSettings = currentSettings.copy(
            enabledTools = currentSettings.enabledTools - toolId
        )
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
    }

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






