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
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None
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
            
            val webSearchSupport = getWebSearchSupport(currentProvider?.provider ?: "", currentModel)
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

    fun sendMessage() {
        val message = _uiState.value.currentMessage.trim()
        val hasFiles = _uiState.value.selectedFiles.isNotEmpty()
        if (message.isEmpty() && !hasFiles) return

        val currentUser = _appSettings.value.current_user
        val currentChat = _uiState.value.currentChat ?: createNewChat(
            // Generate preview name from first part of message or file name
            when {
                message.length > 30 -> "${message.take(30)}..."
                message.isNotEmpty() -> message
                hasFiles -> _uiState.value.selectedFiles.firstOrNull()?.name ?: "קובץ מצורף"
                else -> "שיחה חדשה"
            }
        )

        val chatId = currentChat.chat_id

        viewModelScope.launch {
            val multiModeEnabled = _appSettings.value.multiMessageMode
            // In multi-message mode we do not start streaming immediately
            if (!multiModeEnabled) {
                // Use per-chat loading state
                _uiState.value = _uiState.value.copy(
                    loadingChatIds = _uiState.value.loadingChatIds + chatId,
                    streamingChatIds = _uiState.value.streamingChatIds + chatId,
                    streamingTextByChat = _uiState.value.streamingTextByChat + (chatId to ""),
                    currentMessage = ""
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    currentMessage = ""
                )
            }

            val userMessage = Message(
                role = "user",
                text = if (message.isNotEmpty()) message else "[קובץ מצורף]",
                attachments = _uiState.value.selectedFiles.map { file ->
                    Attachment(
                        local_file_path = file.localPath,
                        file_name = file.name,
                        mime_type = file.mimeType
                    )
                },
                model = null, // User messages don't have a model
                datetime = getCurrentDateTimeISO()
            )

            // Always use branching-aware method - it will migrate if needed
            var updatedChat = repository.addUserMessageAsNewNode(
                currentUser,
                currentChat.chat_id,
                userMessage
            )

            // Update chat history as well
            val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history

            _uiState.value = _uiState.value.copy(
                currentChat = updatedChat,
                selectedFiles = emptyList(),
                chatHistory = updatedChatHistory,
                showReplyButton = multiModeEnabled || _uiState.value.showReplyButton
            )

            // If multi-message mode is enabled, stop here and wait for user to press "reply"
            if (multiModeEnabled) {
                return@launch
            }

            // Send API request and handle response (single-message mode)
            val currentProvider = _uiState.value.currentProvider
            val currentModel = _uiState.value.currentModel
            val systemPrompt = getEffectiveSystemPrompt()

            if (currentProvider != null && currentModel.isNotEmpty()) {
                try {
                    // Handle file uploads first (lazy upload)
                    var finalUserMessage = userMessage
                    if (userMessage.attachments.isNotEmpty()) {
                        val uploadedAttachments = mutableListOf<Attachment>()
                        
                        for (attachment in userMessage.attachments) {
                            attachment.local_file_path?.let { localPath ->
                                // Upload file to the selected provider and get file ID
                                val uploadedAttachment = repository.uploadFile(
                                    provider = currentProvider,
                                    filePath = localPath,
                                    fileName = attachment.file_name,
                                    mimeType = attachment.mime_type,
                                    username = currentUser
                                )
                                
                                if (uploadedAttachment != null) {
                                    uploadedAttachments.add(uploadedAttachment)
                                } else {
                                    // Upload failed, keep original attachment
                                    uploadedAttachments.add(attachment)
                                }
                            }
                        }
                        
                        finalUserMessage = userMessage.copy(attachments = uploadedAttachments)
                        
                        // Update the message in the chat with the uploaded attachments
                        val allMessages = updatedChat!!.messages.dropLast(1) + finalUserMessage
                        val updatedChatWithFiles = updatedChat.copy(messages = allMessages)
                        
                        // Save the chat with the updated file IDs
                        val chatHistory = repository.loadChatHistory(currentUser)
                        val updatedHistoryChats = chatHistory.chat_history.map { chat ->
                            if (chat.chat_id == updatedChatWithFiles.chat_id) {
                                updatedChatWithFiles
                            } else {
                                chat
                            }
                        }
                        val finalChatHistory = chatHistory.copy(chat_history = updatedHistoryChats)
                        repository.saveChatHistory(finalChatHistory)
                        
                        updatedChat = updatedChatWithFiles
                    }

                    // Get project attachments if this chat belongs to a project
                    val projectAttachments = getCurrentChatProjectGroup()?.group_attachments ?: emptyList()

                    // Capture chat reference before starting service request
                    val chatForRequest = updatedChat!!

                    // Generate unique request ID for this API call
                    val requestId = UUID.randomUUID().toString()

                    // Start streaming request via the foreground service
                    // The service handles the API call and broadcasts events back to us
                    startStreamingRequest(
                        requestId = requestId,
                        chatId = chatForRequest.chat_id,
                        username = currentUser,
                        provider = currentProvider,
                        modelName = currentModel,
                        messages = chatForRequest.messages,
                        systemPrompt = systemPrompt,
                        webSearchEnabled = _uiState.value.webSearchEnabled,
                        projectAttachments = projectAttachments,
                        enabledTools = getEnabledToolSpecifications(),
                        thinkingBudget = _uiState.value.thinkingBudgetValue
                    )

                    // Reload chat to get any updated file IDs from re-uploads
                    val refreshedChatHistory = repository.loadChatHistory(currentUser)
                    val refreshedCurrentChat = refreshedChatHistory.chat_history.find {
                        it.chat_id == chatForRequest.chat_id
                    } ?: chatForRequest

                    _uiState.value = _uiState.value.copy(
                        currentChat = refreshedCurrentChat,
                        chatHistory = refreshedChatHistory.chat_history
                    )

                } catch (e: Exception) {
                    // Handle unexpected errors
                    Log.e("ChatViewModel", "Error starting streaming request", e)

                    // Clear loading state for this chat
                    _uiState.value = _uiState.value.copy(
                        loadingChatIds = _uiState.value.loadingChatIds - chatId,
                        streamingChatIds = _uiState.value.streamingChatIds - chatId,
                        streamingTextByChat = _uiState.value.streamingTextByChat - chatId,
                        snackbarMessage = "Error: ${e.message}"
                    )
                }
            } else {
                // No provider selected, clear loading state
                _uiState.value = _uiState.value.copy(
                    loadingChatIds = _uiState.value.loadingChatIds - chatId,
                    streamingChatIds = _uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = _uiState.value.streamingTextByChat - chatId
                )
            }
        }
    }

    fun sendBufferedBatch() {
        val currentUser = _appSettings.value.current_user
        val currentChat = _uiState.value.currentChat ?: return
        val chatId = currentChat.chat_id

        viewModelScope.launch {
            // Use per-chat loading state
            _uiState.value = _uiState.value.copy(
                loadingChatIds = _uiState.value.loadingChatIds + chatId,
                streamingChatIds = _uiState.value.streamingChatIds + chatId,
                streamingTextByChat = _uiState.value.streamingTextByChat + (chatId to ""),
                showReplyButton = false
            )

            val currentProvider = _uiState.value.currentProvider
            val currentModel = _uiState.value.currentModel
            val systemPrompt = getEffectiveSystemPrompt()

            if (currentProvider != null && currentModel.isNotEmpty()) {
                try {
                    // Get project attachments if this chat belongs to a project
                    val projectAttachments = getCurrentChatProjectGroup()?.group_attachments ?: emptyList()

                    // Generate unique request ID for this API call
                    val requestId = UUID.randomUUID().toString()

                    // Start streaming request via the foreground service
                    startStreamingRequest(
                        requestId = requestId,
                        chatId = chatId,
                        username = currentUser,
                        provider = currentProvider,
                        modelName = currentModel,
                        messages = currentChat.messages,
                        systemPrompt = systemPrompt,
                        webSearchEnabled = _uiState.value.webSearchEnabled,
                        projectAttachments = projectAttachments,
                        enabledTools = getEnabledToolSpecifications(),
                        thinkingBudget = _uiState.value.thinkingBudgetValue
                    )

                    // Optionally refresh chat after potential file re-uploads
                    val refreshed = repository.loadChatHistory(currentUser)
                    val refreshedCurrentChat = refreshed.chat_history.find { it.chat_id == chatId } ?: currentChat
                    _uiState.value = _uiState.value.copy(
                        currentChat = refreshedCurrentChat,
                        chatHistory = refreshed.chat_history
                    )
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error starting buffered batch request", e)
                    _uiState.value = _uiState.value.copy(
                        loadingChatIds = _uiState.value.loadingChatIds - chatId,
                        streamingChatIds = _uiState.value.streamingChatIds - chatId,
                        streamingTextByChat = _uiState.value.streamingTextByChat - chatId,
                        snackbarMessage = "Error: ${e.message}"
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    loadingChatIds = _uiState.value.loadingChatIds - chatId,
                    streamingChatIds = _uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = _uiState.value.streamingTextByChat - chatId
                )
            }
        }
    }

    fun updateMultiMessageMode(enabled: Boolean) {
        val updatedSettings = _appSettings.value.copy(multiMessageMode = enabled)
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings

        // Clear any pending UI temp button when turning off
        if (!enabled) {
            _uiState.value = _uiState.value.copy(showReplyButton = false)
        }
    }

    fun createNewChat(): Chat {
        return createNewChat("שיחה חדשה")
    }
    
    fun createNewChat(previewName: String = "שיחה חדשה"): Chat {
        val currentUser = _appSettings.value.current_user
        val newChat = repository.createNewChat(currentUser, previewName, "")  // Reset system prompt
        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
        
        _uiState.value = _uiState.value.copy(
            currentChat = newChat,
            systemPrompt = "",  // Reset system prompt in UI
            chatHistory = updatedChatHistory,
            showChatHistory = false
        )
        
        // Navigate to the Chat screen to start the new chat
        navigateToScreen(Screen.Chat)
        
        return newChat
    }

    fun createNewChatInGroup(groupId: String): Chat {
        return createNewChatInGroup(groupId, "שיחה חדשה")
    }

    fun createNewChatInGroup(groupId: String, previewName: String = "שיחה חדשה"): Chat {
        val currentUser = _appSettings.value.current_user

        // Create new chat with empty system prompt (group system prompt will be handled separately)
        val newChat = repository.createNewChatInGroup(currentUser, previewName, groupId, "")
        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history

        _uiState.value = _uiState.value.copy(
            currentChat = newChat,
            systemPrompt = "",  // Start with empty system prompt
            chatHistory = updatedChatHistory,
            showChatHistory = false
        )

        // Navigate to the Chat screen to start the new chat
        navigateToScreen(Screen.Chat)

        return newChat
    }

    fun selectChat(chat: Chat) {
        _uiState.value = _uiState.value.copy(
            currentChat = chat,
            systemPrompt = chat.systemPrompt,  // Load system prompt from selected chat
            showChatHistory = false,
            searchContext = null // Clear search context when selecting normally
        )
    }
    
    fun selectChatFromSearch(searchResult: SearchResult) {
        _uiState.value = _uiState.value.copy(
            currentChat = searchResult.chat,
            systemPrompt = searchResult.chat.systemPrompt,
            showChatHistory = false,
            searchContext = null // Clear old search context
        )
        
        // Enter conversation search mode with the same query
        enterSearchModeWithQuery(searchResult.searchQuery)
        
        // Navigate to the chat screen
        navigateToScreen(Screen.Chat)
    }
    
    fun refreshChatHistory() {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            val chatHistory = repository.loadChatHistory(currentUser)
            _uiState.value = _uiState.value.copy(
                chatHistory = chatHistory.chat_history,
                groups = chatHistory.groups
            )
        }
    }

    fun selectProvider(provider: Provider) {
        val firstModel = provider.models.firstOrNull()?.name ?: "Unknown Model"
        
        val updatedSettings = _appSettings.value.copy(
            selected_provider = provider.provider,
            selected_model = firstModel
        )
        
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
        
        val webSearchSupport = getWebSearchSupport(provider.provider, firstModel)
        val webSearchEnabled = when (webSearchSupport) {
            WebSearchSupport.REQUIRED -> true
            WebSearchSupport.OPTIONAL -> _uiState.value.webSearchEnabled
            WebSearchSupport.UNSUPPORTED -> false
        }
        
        _uiState.value = _uiState.value.copy(
            currentProvider = provider,
            currentModel = firstModel,
            showProviderSelector = false,
            webSearchSupport = webSearchSupport,
            webSearchEnabled = webSearchEnabled
        )
    }

    fun selectModel(modelName: String) {
        val newSettings = _appSettings.value.copy(selected_model = modelName)
        repository.saveAppSettings(newSettings)
        _appSettings.value = newSettings
        
        val webSearchSupport = getWebSearchSupport(_uiState.value.currentProvider?.provider ?: "", modelName)
        val webSearchEnabled = when (webSearchSupport) {
            WebSearchSupport.REQUIRED -> true
            WebSearchSupport.OPTIONAL -> _uiState.value.webSearchEnabled
            WebSearchSupport.UNSUPPORTED -> false
        }
        
        _uiState.value = _uiState.value.copy(
            currentModel = modelName,
            showModelSelector = false,
            webSearchSupport = webSearchSupport,
            webSearchEnabled = webSearchEnabled
        )
    }

    fun updateSystemPrompt(prompt: String) {
        val currentUser = _appSettings.value.current_user
        val currentChat = _uiState.value.currentChat
        
        if (currentChat != null) {
            // Update system prompt for current chat
            repository.updateChatSystemPrompt(currentUser, currentChat.chat_id, prompt)
            
            // Update the current chat in UI state
            val updatedChat = currentChat.copy(systemPrompt = prompt)
            val updatedChatHistory = _uiState.value.chatHistory.map { chat ->
                if (chat.chat_id == currentChat.chat_id) updatedChat else chat
            }
            
            _uiState.value = _uiState.value.copy(
                currentChat = updatedChat,
                systemPrompt = prompt,
                chatHistory = updatedChatHistory,
                showSystemPromptDialog = false
            )
        } else {
            // If no current chat, create a new one with the system prompt
            val newChat = repository.createNewChat(currentUser, "שיחה חדשה", prompt)
            val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
            
            _uiState.value = _uiState.value.copy(
                currentChat = newChat,
                systemPrompt = prompt,
                chatHistory = updatedChatHistory,
                showSystemPromptDialog = false
            )
        }
    }

    fun showProviderSelector() {
        _uiState.value = _uiState.value.copy(showProviderSelector = true)
    }

    fun hideProviderSelector() {
        _uiState.value = _uiState.value.copy(showProviderSelector = false)
    }

    fun showModelSelector() {
        _uiState.value = _uiState.value.copy(showModelSelector = true)
    }

    fun hideModelSelector() {
        _uiState.value = _uiState.value.copy(showModelSelector = false)
    }

    fun refreshModels() {
        viewModelScope.launch {
            try {
                // Show loading message
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "מעדכן את רשימת המודלים הזמינים...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }

                android.util.Log.d("ChatViewModel", "Starting forceRefreshModels()")
                val (success, errorMessage) = repository.forceRefreshModels()
                android.util.Log.d("ChatViewModel", "forceRefreshModels() returned: success=$success, error=$errorMessage")

                if (success) {
                    // Reload providers with updated models
                    val settings = repository.loadAppSettings()
                    val allProviders = repository.loadProviders()
                    val activeApiKeyProviders = repository.loadApiKeys(settings.current_user)
                        .filter { it.isActive }
                        .map { it.provider }
                    val providers = allProviders.filter { provider ->
                        activeApiKeyProviders.contains(provider.provider)
                    }

                    // Update UI state with refreshed providers
                    _uiState.value = _uiState.value.copy(availableProviders = providers)

                    // Update current provider with refreshed models
                    val currentProvider = providers.find { it.provider == _uiState.value.currentProvider?.provider }
                    if (currentProvider != null) {
                        _uiState.value = _uiState.value.copy(currentProvider = currentProvider)
                    }

                    // Show success message
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "הרשימה עודכנה בהצלחה!",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    // Show failure message with details
                    withContext(Dispatchers.Main) {
                        val message = if (errorMessage != null) {
                            "שגיאה בעדכון הרשימה: $errorMessage"
                        } else {
                            "שגיאה בעדכון הרשימה. אנא נסה שוב."
                        }
                        android.widget.Toast.makeText(
                            context,
                            message,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Failed to refresh models", e)
                // Show error message
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "שגיאה בעדכון הרשימה: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun showSystemPromptDialog() {
        _uiState.value = _uiState.value.copy(showSystemPromptDialog = true)
    }

    fun hideSystemPromptDialog() {
        _uiState.value = _uiState.value.copy(showSystemPromptDialog = false)
    }

    fun toggleSystemPromptOverride() {
        _uiState.value = _uiState.value.copy(
            systemPromptOverrideEnabled = !_uiState.value.systemPromptOverrideEnabled
        )
    }

    fun setSystemPromptOverride(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            systemPromptOverrideEnabled = enabled
        )
    }

    /**
     * Get the project group for the current chat if it belongs to a project group
     */
    fun getCurrentChatProjectGroup(): ChatGroup? {
        val currentChat = _uiState.value.currentChat
        val groups = _uiState.value.groups

        return currentChat?.group?.let { groupId ->
            groups.find { it.group_id == groupId && it.is_project }
        }
    }

    /**
     * Get the effective system prompt based on project settings and override switch
     */
    fun getEffectiveSystemPrompt(): String {
        val currentChat = _uiState.value.currentChat ?: return ""
        val projectGroup = getCurrentChatProjectGroup()

        return when {
            // If chat belongs to a project group
            projectGroup != null -> {
                val projectPrompt = projectGroup.system_prompt ?: ""
                val chatPrompt = currentChat.systemPrompt

                when {
                    // If override is enabled, concatenate both prompts
                    _uiState.value.systemPromptOverrideEnabled && chatPrompt.isNotEmpty() ->
                        "$projectPrompt\n\n$chatPrompt"
                    // Otherwise, use only project prompt
                    else -> projectPrompt
                }
            }
            // If not in project, use regular chat system prompt
            else -> currentChat.systemPrompt
        }
    }

    /**
     * Get enabled tool specifications based on current app settings
     */
    private fun getEnabledToolSpecifications(): List<ToolSpecification> {
        val enabledToolIds = _appSettings.value.enabledTools
        val toolRegistry = ToolRegistry.getInstance()
        val currentProvider = _uiState.value.currentProvider?.provider ?: "openai"

        val specifications = mutableListOf<ToolSpecification>()

        // Add standard tools from registry
        enabledToolIds.forEach { toolId ->
            // Skip group conversations tool - it's handled separately below
            if (toolId == "get_current_group_conversations") {
                return@forEach
            }

            toolRegistry.getTool(toolId)?.getSpecification(currentProvider)?.let {
                specifications.add(it)
            }
        }

        // Handle group conversations tool specially - only add if chat is in a group
        if (enabledToolIds.contains("get_current_group_conversations")) {
            val currentChat = _uiState.value.currentChat
            val groups = _uiState.value.groups
            val currentUser = _appSettings.value.current_user

            // Check if current chat belongs to a group
            currentChat?.group?.let { groupId ->
                // Find the group to get its name
                groups.find { it.group_id == groupId }?.let { group ->
                    // Create dynamic tool instance with current context
                    val groupConversationsTool = com.example.ApI.tools.GroupConversationsTool(
                        repository = repository,
                        username = currentUser,
                        currentChatId = currentChat.chat_id,
                        groupId = groupId,
                        groupName = group.group_name
                    )

                    // Add its specification
                    specifications.add(groupConversationsTool.getSpecification(currentProvider))
                }
            }
        }

        return specifications
    }

    /**
     * Execute a tool call, handling both standard registry tools and dynamic tools
     */
    private suspend fun executeToolCall(toolCall: com.example.ApI.tools.ToolCall): com.example.ApI.tools.ToolExecutionResult {
        // Special handling for group conversations tool (dynamic instance)
        if (toolCall.toolId == "get_current_group_conversations") {
            val currentChat = _uiState.value.currentChat
            val groups = _uiState.value.groups
            val currentUser = _appSettings.value.current_user

            return currentChat?.group?.let { groupId ->
                groups.find { it.group_id == groupId }?.let { group ->
                    val groupConversationsTool = com.example.ApI.tools.GroupConversationsTool(
                        repository = repository,
                        username = currentUser,
                        currentChatId = currentChat.chat_id,
                        groupId = groupId,
                        groupName = group.group_name
                    )
                    try {
                        groupConversationsTool.execute(toolCall.parameters)
                    } catch (e: Exception) {
                        com.example.ApI.tools.ToolExecutionResult.Error(
                            "Failed to execute group conversations tool: ${e.message}"
                        )
                    }
                }
            } ?: com.example.ApI.tools.ToolExecutionResult.Error(
                "Group conversations tool can only be used in a group chat"
            )
        }

        // Standard tools - use registry
        val enabledToolIds = getEnabledToolSpecifications().map { it.name }
        return ToolRegistry.getInstance().executeTool(toolCall, enabledToolIds)
    }

    fun showChatHistory() {
        _uiState.value = _uiState.value.copy(showChatHistory = true)
    }

    fun hideChatHistory() {
        _uiState.value = _uiState.value.copy(showChatHistory = false)
    }

    fun addSelectedFile(file: SelectedFile) {
        _uiState.value = _uiState.value.copy(
            selectedFiles = _uiState.value.selectedFiles + file
        )
    }

    fun removeSelectedFile(file: SelectedFile) {
        _uiState.value = _uiState.value.copy(
            selectedFiles = _uiState.value.selectedFiles.filter { it != file }
        )
        
        // Delete the local file if it exists
        file.localPath?.let { path ->
            repository.deleteFile(path)
        }
    }

    fun navigateToScreen(screen: Screen) {
        // Refresh available providers when navigating away from API keys screen
        // since user may have added/removed/toggled keys
        if (_currentScreen.value == Screen.ApiKeys && screen != Screen.ApiKeys) {
            refreshAvailableProviders()
        }
        _currentScreen.value = screen
    }

    fun updateSkipWelcomeScreen(skip: Boolean) {
        val updatedSettings = _appSettings.value.copy(skipWelcomeScreen = skip)
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
    }

    /**
     * Refresh the available providers list based on active API keys.
     * Called when returning from API keys screen to update provider selection.
     */
    fun refreshAvailableProviders() {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            val allProviders = repository.loadProviders()
            val activeApiKeyProviders = repository.loadApiKeys(currentUser)
                .filter { it.isActive }
                .map { it.provider }
            val filteredProviders = allProviders.filter { provider ->
                activeApiKeyProviders.contains(provider.provider)
            }

            // Check if current provider is still available
            val currentProvider = _uiState.value.currentProvider
            val newCurrentProvider = if (currentProvider != null &&
                activeApiKeyProviders.contains(currentProvider.provider)) {
                currentProvider
            } else {
                filteredProviders.firstOrNull()
            }

            // Update model if provider changed
            val newCurrentModel = if (newCurrentProvider?.provider != currentProvider?.provider) {
                newCurrentProvider?.models?.firstOrNull()?.name ?: ""
            } else {
                _uiState.value.currentModel
            }

            _uiState.value = _uiState.value.copy(
                availableProviders = filteredProviders,
                currentProvider = newCurrentProvider,
                currentModel = newCurrentModel
            )

            // Update app settings if provider/model changed
            if (newCurrentProvider?.provider != _appSettings.value.selected_provider ||
                newCurrentModel != _appSettings.value.selected_model) {
                val updatedSettings = _appSettings.value.copy(
                    selected_provider = newCurrentProvider?.provider ?: "",
                    selected_model = newCurrentModel
                )
                repository.saveAppSettings(updatedSettings)
                _appSettings.value = updatedSettings
            }
        }
    }
    
    fun exportChatHistory() {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            val exportPath = repository.exportChatHistory(currentUser)

            if (exportPath != null) {
                Toast.makeText(
                    context,
                    "היסטוריית הצ'אט יוצאה בהצלחה ל: $exportPath",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }
    
    fun toggleTextDirection() {
        val currentMode = _uiState.value.textDirectionMode
        val nextMode = when (currentMode) {
            TextDirectionMode.AUTO -> TextDirectionMode.RTL
            TextDirectionMode.RTL -> TextDirectionMode.LTR
            TextDirectionMode.LTR -> TextDirectionMode.AUTO
        }
        _uiState.value = _uiState.value.copy(textDirectionMode = nextMode)
    }
    
    fun importChatHistoryFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val data = stream.readBytes()
                    val currentUser = _appSettings.value.current_user
                    repository.importChatHistoryJson(data, currentUser)
                    // Refresh UI state after import
                    val chatHistory = repository.loadChatHistory(currentUser)
                    val currentChat = chatHistory.chat_history.lastOrNull()
                    _uiState.value = _uiState.value.copy(
                        chatHistory = chatHistory.chat_history,
                        groups = chatHistory.groups,
                        currentChat = currentChat
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "שגיאה בייבוא היסטוריית צ'אט"
                )
            }
        }
    }
    
    // File handling methods
    fun addFileFromUri(uri: Uri, fileName: String, mimeType: String) {
        viewModelScope.launch {
            try {
                // Copy file to internal storage for lazy upload
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val fileData = stream.readBytes()
                    val localPath = repository.saveFileLocally(fileName, fileData)
                    
                    if (localPath != null) {
                        val selectedFile = SelectedFile(
                            uri = uri,
                            name = fileName,
                            mimeType = mimeType,
                            localPath = localPath
                        )
                        
                        _uiState.value = _uiState.value.copy(
                            selectedFiles = _uiState.value.selectedFiles + selectedFile
                        )
                    }
                }
            } catch (e: Exception) {
                // Handle file error
                println("Error adding file: ${e.message}")
            }
        }
    }

    fun addMultipleFilesFromUris(filesList: List<Triple<Uri, String, String>>) {
        viewModelScope.launch {
            val newSelectedFiles = mutableListOf<SelectedFile>()
            
            for ((uri, fileName, mimeType) in filesList) {
                try {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                    inputStream?.use { stream ->
                        val fileData = stream.readBytes()
                        val localPath = repository.saveFileLocally(fileName, fileData)
                        
                        if (localPath != null) {
                            val selectedFile = SelectedFile(
                                uri = uri,
                                name = fileName,
                                mimeType = mimeType,
                                localPath = localPath
                            )
                            newSelectedFiles.add(selectedFile)
                        }
                    }
                } catch (e: Exception) {
                    println("Error adding file $fileName: ${e.message}")
                }
            }
            
            if (newSelectedFiles.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    selectedFiles = _uiState.value.selectedFiles + newSelectedFiles
                )
            }
        }
    }
    
    fun showFileSelection() {
        _uiState.value = _uiState.value.copy(showFileSelection = true)
    }
    
    fun hideFileSelection() {
        _uiState.value = _uiState.value.copy(showFileSelection = false)
    }

    fun deleteMessage(message: Message) {
        val currentUser = _appSettings.value.current_user
        val currentChat = _uiState.value.currentChat ?: return

        viewModelScope.launch {
            // Use branching-aware deletion
            val result = repository.deleteMessageFromBranch(
                currentUser,
                currentChat.chat_id,
                message.id
            )
            
            when (result) {
                is DeleteMessageResult.Success -> {
                    val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
                    _uiState.value = _uiState.value.copy(
                        currentChat = result.updatedChat,
                        chatHistory = updatedChatHistory
                    )
                }
                is DeleteMessageResult.CannotDeleteBranchPoint -> {
                    _uiState.value = _uiState.value.copy(
                        snackbarMessage = result.message
                    )
                }
                is DeleteMessageResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        snackbarMessage = "שגיאה במחיקת ההודעה: ${result.message}"
                    )
                }
            }
        }
    }
    
    // Start editing a message
    fun startEditingMessage(message: Message) {
        _uiState.value = _uiState.value.copy(
            editingMessage = message,
            isEditMode = true,
            currentMessage = message.text
        )
    }
    
    // Finish editing a message - creates a new branch with the edited message (no API call)
    fun finishEditingMessage() {
        val editingMessage = _uiState.value.editingMessage ?: return
        val currentChat = _uiState.value.currentChat ?: return
        val currentUser = _appSettings.value.current_user
        val newText = _uiState.value.currentMessage.trim()
        
        if (newText.isEmpty()) return
        
        // If text hasn't changed, just exit edit mode
        if (newText == editingMessage.text) {
            _uiState.value = _uiState.value.copy(
                editingMessage = null,
                isEditMode = false,
                currentMessage = ""
            )
            return
        }
        
        // Ensure branching structure exists
        val chatWithBranching = repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
            ?: return
        
        // Find the node for this message
        val nodeId = repository.findNodeForMessage(chatWithBranching, editingMessage)
        
        if (nodeId != null) {
            // Create a new branch with the edited message (no responses yet)
            val editedMessage = editingMessage.copy(
                text = newText,
                datetime = getCurrentDateTimeISO()
            )
            
            val result = repository.createBranch(
                currentUser,
                currentChat.chat_id,
                nodeId,
                editedMessage
            )
            
            if (result != null) {
                val (updatedChat, _) = result
                val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
                
                _uiState.value = _uiState.value.copy(
                    editingMessage = null,
                    isEditMode = false,
                    currentMessage = "",
                    currentChat = updatedChat,
                    chatHistory = updatedChatHistory
                )
                return
            }
        }
        
        // Fallback: if branching fails, use the old behavior
        val updatedMessage = editingMessage.copy(text = newText)
        val updatedChat = repository.replaceMessageInChat(
            currentUser, 
            currentChat.chat_id, 
            editingMessage, 
            updatedMessage
        )
        
        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
        
        _uiState.value = _uiState.value.copy(
            editingMessage = null,
            isEditMode = false,
            currentMessage = "",
            currentChat = updatedChat,
            chatHistory = updatedChatHistory
        )
    }

    // Confirm the edit and immediately resend from the edited message (creates new branch with API call)
    fun confirmEditAndResend() {
        val editingMessage = _uiState.value.editingMessage ?: return
        val currentChat = _uiState.value.currentChat ?: return
        val currentUser = _appSettings.value.current_user
        val newText = _uiState.value.currentMessage.trim()
        if (newText.isEmpty()) return

        // Ensure branching structure exists
        val chatWithBranching = repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
            ?: return

        // Find the node for this message
        val nodeId = repository.findNodeForMessage(chatWithBranching, editingMessage)

        if (nodeId != null) {
            // Create the edited message
            val editedMessage = editingMessage.copy(
                text = newText,
                datetime = getCurrentDateTimeISO()
            )

            // Create a new branch
            val result = repository.createBranch(
                currentUser,
                currentChat.chat_id,
                nodeId,
                editedMessage
            )

            if (result != null) {
                val (updatedChat, _) = result
                val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history

                _uiState.value = _uiState.value.copy(
                    editingMessage = null,
                    isEditMode = false,
                    currentMessage = "",
                    currentChat = updatedChat,
                    chatHistory = updatedChatHistory
                )

                // Send API request for the new branch
                sendApiRequestForCurrentBranch(updatedChat)
                return
            }
        }

        // Fallback: use old behavior if branching fails
        val updatedMessage = editingMessage.copy(text = newText)
        val updatedChat = repository.replaceMessageInChat(
            currentUser,
            currentChat.chat_id,
            editingMessage,
            updatedMessage
        ) ?: return

        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
        _uiState.value = _uiState.value.copy(
            editingMessage = null,
            isEditMode = false,
            currentMessage = "",
            currentChat = updatedChat,
            chatHistory = updatedChatHistory
        )

        resendFromMessage(updatedMessage)
    }
    
    // Cancel editing without saving changes
    fun cancelEditingMessage() {
        _uiState.value = _uiState.value.copy(
            editingMessage = null,
            isEditMode = false,
            currentMessage = ""
        )
    }
    
    // Resend from a specific message point - creates a new branch with the same message
    fun resendFromMessage(message: Message) {
        val currentChat = _uiState.value.currentChat ?: return
        val currentUser = _appSettings.value.current_user
        
        viewModelScope.launch {
            // Ensure branching structure exists
            val chatWithBranching = repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
                ?: return@launch
            
            // Find the node for this message
            val nodeId = repository.findNodeForMessage(chatWithBranching, message)
            
            if (nodeId != null) {
                // Create a new branch with the same message but new timestamp
                val resendMessage = message.copy(
                    datetime = getCurrentDateTimeISO()
                )
                
                val result = repository.createBranch(
                    currentUser,
                    currentChat.chat_id,
                    nodeId,
                    resendMessage
                )
                
                if (result != null) {
                    val (updatedChat, _) = result
                    val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
                    
                    _uiState.value = _uiState.value.copy(
                        currentChat = updatedChat,
                        chatHistory = updatedChatHistory
                    )
                    
                    // Send API request for the new branch
                    sendApiRequestForCurrentBranch(updatedChat)
                    return@launch
                }
            }
            
            // Fallback: old behavior if branching fails
            val deletedChat = repository.deleteMessagesFromPoint(
                currentUser, 
                currentChat.chat_id, 
                message
            ) ?: return@launch
            
            val resendUpdatedChat = repository.addMessageToChat(
                currentUser,
                deletedChat.chat_id,
                message
            )
            
            val finalChatHistory = repository.loadChatHistory(currentUser).chat_history
            
            val chatId = resendUpdatedChat!!.chat_id
            _uiState.value = _uiState.value.copy(
                currentChat = resendUpdatedChat,
                chatHistory = finalChatHistory,
                loadingChatIds = _uiState.value.loadingChatIds + chatId,
                streamingChatIds = _uiState.value.streamingChatIds + chatId,
                streamingTextByChat = _uiState.value.streamingTextByChat + (chatId to "")
            )

            sendApiRequestForChat(resendUpdatedChat)
        }
    }
    
    /**
     * Send API request for the current branch of a chat using the streaming service.
     * This is used when creating new branches or resending messages.
     */
    private fun sendApiRequestForCurrentBranch(chat: Chat) {
        val currentUser = _appSettings.value.current_user
        val currentProvider = _uiState.value.currentProvider
        val currentModel = _uiState.value.currentModel
        val systemPrompt = getEffectiveSystemPrompt()
        val chatId = chat.chat_id

        if (currentProvider == null || currentModel.isEmpty()) {
            return
        }

        // Use per-chat loading state
        _uiState.value = _uiState.value.copy(
            loadingChatIds = _uiState.value.loadingChatIds + chatId,
            streamingChatIds = _uiState.value.streamingChatIds + chatId,
            streamingTextByChat = _uiState.value.streamingTextByChat + (chatId to "")
        )

        viewModelScope.launch {
            try {
                val projectAttachments = getCurrentChatProjectGroup()?.group_attachments ?: emptyList()

                // Generate unique request ID for this API call
                val requestId = UUID.randomUUID().toString()

                // Start streaming request via the foreground service
                startStreamingRequest(
                    requestId = requestId,
                    chatId = chatId,
                    username = currentUser,
                    provider = currentProvider,
                    modelName = currentModel,
                    messages = chat.messages,
                    systemPrompt = systemPrompt,
                    webSearchEnabled = _uiState.value.webSearchEnabled,
                    projectAttachments = projectAttachments,
                    enabledTools = getEnabledToolSpecifications(),
                    thinkingBudget = _uiState.value.thinkingBudgetValue
                )
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error starting branch request", e)
                _uiState.value = _uiState.value.copy(
                    loadingChatIds = _uiState.value.loadingChatIds - chatId,
                    streamingChatIds = _uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = _uiState.value.streamingTextByChat - chatId,
                    snackbarMessage = "Error: ${e.message}"
                )
            }
        }
    }

    /**
     * Send API request for a chat (fallback for non-branching scenarios).
     */
    private fun sendApiRequestForChat(chat: Chat) {
        sendApiRequestForCurrentBranch(chat)
    }
    
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
    
    private fun getWebSearchSupport(providerName: String, modelName: String): WebSearchSupport {
        // Based on the providers.json specifications from the user
        return when (providerName.lowercase()) {
            "openai" -> {
                when (modelName) {
                    "gpt-5", "gpt-4.1", "gpt-4o", "o3", "o4-mini" -> WebSearchSupport.OPTIONAL
                    "o4-mini-deep-research" -> WebSearchSupport.REQUIRED
                    "o1", "o1-pro" -> WebSearchSupport.UNSUPPORTED
                    else -> WebSearchSupport.OPTIONAL
                }
            }
            "poe" -> {
                when (modelName) {
                    "Claude-Sonnet-4-Search" -> WebSearchSupport.REQUIRED
                    "Gemini-2.5-Pro" -> WebSearchSupport.REQUIRED
                    else -> WebSearchSupport.UNSUPPORTED
                }
            }
            "google" -> {
                when (modelName) {
                    "gemini-2.5-pro", "gemini-2.5-flash", "gemini-1.5-pro-latest", "gemini-1.5-flash-latest" -> WebSearchSupport.OPTIONAL
                    else -> WebSearchSupport.OPTIONAL
                }
            }
            "anthropic" -> {
                // All Claude models support web search as optional
                WebSearchSupport.OPTIONAL
            }
            "openrouter" -> {
                // OpenRouter web search depends on the underlying model
                WebSearchSupport.UNSUPPORTED
            }
            else -> WebSearchSupport.UNSUPPORTED
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
                        val mimeType = intent.type ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
                        checkAndHandleJsonFile(uri, fileName, mimeType)
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    // Multiple files sharing
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                        val fileName = getFileName(context, uri) ?: "shared_file"
                        val mimeType = intent.type ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
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

    // Group Management Methods

    fun createNewGroup(groupName: String) {
        if (groupName.isBlank()) return

        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            val newGroup = repository.createNewGroup(currentUser, groupName.trim())

            // If there's a pending chat, add it to the new group
            val pendingChat = _uiState.value.pendingChatForGroup
            if (pendingChat != null) {
                repository.addChatToGroup(currentUser, pendingChat.chat_id, newGroup.group_id)
            }

            // Update UI state with new group
            val chatHistory = repository.loadChatHistory(currentUser)
            _uiState.value = _uiState.value.copy(
                groups = chatHistory.groups,
                chatHistory = chatHistory.chat_history,
                expandedGroups = _uiState.value.expandedGroups + newGroup.group_id,
                pendingChatForGroup = null
            )

            hideGroupDialog()
        }
    }

    fun addChatToGroup(chatId: String, groupId: String) {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            val success = repository.addChatToGroup(currentUser, chatId, groupId)

            if (success) {
                // Update UI state
                val chatHistory = repository.loadChatHistory(currentUser)
                _uiState.value = _uiState.value.copy(
                    chatHistory = chatHistory.chat_history,
                    groups = chatHistory.groups
                )

                hideChatContextMenu()
            } else {
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "שגיאה בהוספת השיחה לקבוצה"
                )
            }
        }
    }

    fun removeChatFromGroup(chatId: String) {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            val success = repository.removeChatFromGroup(currentUser, chatId)

            if (success) {
                // Update UI state
                val chatHistory = repository.loadChatHistory(currentUser)
                _uiState.value = _uiState.value.copy(
                    chatHistory = chatHistory.chat_history,
                    groups = chatHistory.groups
                )

                hideChatContextMenu()
            }
        }
    }

    fun toggleGroupExpansion(groupId: String) {
        val currentExpanded = _uiState.value.expandedGroups
        val newExpanded = if (currentExpanded.contains(groupId)) {
            currentExpanded - groupId
        } else {
            currentExpanded + groupId
        }

        _uiState.value = _uiState.value.copy(expandedGroups = newExpanded)
    }

    fun showGroupDialog(chat: Chat? = null) {
        _uiState.value = _uiState.value.copy(
            showGroupDialog = true,
            pendingChatForGroup = chat
        )
    }

    fun hideGroupDialog() {
        _uiState.value = _uiState.value.copy(
            showGroupDialog = false,
            pendingChatForGroup = null
        )
    }

    fun refreshChatHistoryAndGroups() {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            val chatHistory = repository.loadChatHistory(currentUser)
            _uiState.value = _uiState.value.copy(
                chatHistory = chatHistory.chat_history,
                groups = chatHistory.groups
            )
        }
    }

    fun toggleGroupProjectStatus(groupId: String) {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            val currentGroup = _uiState.value.groups.find { it.group_id == groupId }
            val newProjectStatus = !(currentGroup?.is_project ?: false)

            repository.updateGroupProjectStatus(currentUser, groupId, newProjectStatus)

            // Update UI state
            val updatedGroups = _uiState.value.groups.map { group ->
                if (group.group_id == groupId) {
                    group.copy(is_project = newProjectStatus)
                } else {
                    group
                }
            }

            _uiState.value = _uiState.value.copy(groups = updatedGroups)

            // Update current group if it's the one being modified
            if (_uiState.value.currentGroup?.group_id == groupId) {
                val updatedCurrentGroup = updatedGroups.find { it.group_id == groupId }
                _uiState.value = _uiState.value.copy(currentGroup = updatedCurrentGroup)
            }
        }
    }

    fun addFileToProject(groupId: String, uri: Uri, fileName: String, mimeType: String) {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user

            try {
                // Copy file to internal storage
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val fileData = stream.readBytes()
                    val localPath = repository.saveFileLocally(fileName, fileData)

                    if (localPath != null) {
                        val attachment = Attachment(
                            local_file_path = localPath,
                            file_name = fileName,
                            mime_type = mimeType
                        )

                        repository.addAttachmentToGroup(currentUser, groupId, attachment)

                        // Update UI state
                        val updatedGroups = _uiState.value.groups.map { group ->
                            if (group.group_id == groupId) {
                                group.copy(group_attachments = group.group_attachments + attachment)
                            } else {
                                group
                            }
                        }

                        _uiState.value = _uiState.value.copy(groups = updatedGroups)

                        // Update current group if it's the one being modified
                        if (_uiState.value.currentGroup?.group_id == groupId) {
                            val updatedCurrentGroup = updatedGroups.find { it.group_id == groupId }
                            _uiState.value = _uiState.value.copy(currentGroup = updatedCurrentGroup)
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "שגיאה בהעלאת הקובץ: ${e.message}"
                )
            }
        }
    }

    fun openProjectInstructionsDialog() {
        _uiState.value = _uiState.value.copy(showSystemPromptDialog = true)
    }

    fun removeFileFromProject(groupId: String, attachmentIndex: Int) {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user

            // Get the attachment before removing it (to delete the file)
            val chatHistory = repository.loadChatHistory(currentUser)
            val group = chatHistory.groups.find { it.group_id == groupId }
            val attachmentToRemove = group?.group_attachments?.getOrNull(attachmentIndex)

            // Remove from JSON registry
            repository.removeAttachmentFromGroup(currentUser, groupId, attachmentIndex)

            // Delete the actual file from internal storage
            attachmentToRemove?.local_file_path?.let { path ->
                repository.deleteFile(path)
            }

            // Update UI state
            val updatedGroups = _uiState.value.groups.map { groupItem ->
                if (groupItem.group_id == groupId) {
                    val updatedAttachments = groupItem.group_attachments.toMutableList()
                    if (attachmentIndex >= 0 && attachmentIndex < updatedAttachments.size) {
                        updatedAttachments.removeAt(attachmentIndex)
                    }
                    groupItem.copy(group_attachments = updatedAttachments)
                } else {
                    groupItem
                }
            }

            _uiState.value = _uiState.value.copy(groups = updatedGroups)

            // Update current group if it's the one being modified
            if (_uiState.value.currentGroup?.group_id == groupId) {
                val updatedCurrentGroup = updatedGroups.find { it.group_id == groupId }
                _uiState.value = _uiState.value.copy(currentGroup = updatedCurrentGroup)
            }
        }
    }

    fun navigateToGroup(groupId: String) {
        val group = _uiState.value.groups.find { it.group_id == groupId }
        if (group != null) {
            _uiState.value = _uiState.value.copy(
                currentGroup = group,
                systemPrompt = group.system_prompt ?: ""
            )
            navigateToScreen(Screen.Group(groupId))
        }
    }

    fun updateGroupSystemPrompt(systemPrompt: String) {
        val currentGroup = _uiState.value.currentGroup ?: return
        val currentUser = _appSettings.value.current_user

        // Update system prompt in repository
        val updatedGroups = _uiState.value.groups.map { group ->
            if (group.group_id == currentGroup.group_id) {
                group.copy(system_prompt = systemPrompt)
            } else {
                group
            }
        }

        // Update chat history JSON
        val chatHistory = repository.loadChatHistory(currentUser)
        val updatedHistory = chatHistory.copy(groups = updatedGroups)
        repository.saveChatHistory(updatedHistory)

        // Update UI state
        _uiState.value = _uiState.value.copy(
            groups = updatedGroups,
            currentGroup = updatedGroups.find { it.group_id == currentGroup.group_id },
            systemPrompt = systemPrompt,
            showSystemPromptDialog = false
        )
    }

    // Group context menu functions
    fun showGroupContextMenu(group: ChatGroup, position: androidx.compose.ui.unit.DpOffset) {
        _uiState.value = _uiState.value.copy(
            groupContextMenu = GroupContextMenuState(group, position)
        )
    }

    fun hideGroupContextMenu() {
        _uiState.value = _uiState.value.copy(
            groupContextMenu = null
        )
    }

    fun showGroupRenameDialog(group: ChatGroup) {
        _uiState.value = _uiState.value.copy(
            showGroupRenameDialog = group,
            groupContextMenu = null
        )
    }

    fun hideGroupRenameDialog() {
        _uiState.value = _uiState.value.copy(
            showGroupRenameDialog = null
        )
    }

    fun renameGroup(group: ChatGroup, newName: String) {
        if (newName.isBlank()) return

        val currentUser = _appSettings.value.current_user
        val success = repository.renameGroup(currentUser, group.group_id, newName.trim())

        if (success) {
            // Update local state
            val updatedGroups = _uiState.value.groups.map {
                if (it.group_id == group.group_id) {
                    it.copy(group_name = newName.trim())
                } else {
                    it
                }
            }

            _uiState.value = _uiState.value.copy(
                groups = updatedGroups,
                currentGroup = if (_uiState.value.currentGroup?.group_id == group.group_id) {
                    _uiState.value.currentGroup?.copy(group_name = newName.trim())
                } else {
                    _uiState.value.currentGroup
                },
                showGroupRenameDialog = null
            )
        } else {
            _uiState.value = _uiState.value.copy(
                showGroupRenameDialog = null
            )
        }
    }

    fun makeGroupProject(group: ChatGroup) {
        val currentUser = _appSettings.value.current_user
        val success = repository.updateGroupProjectStatus(currentUser, group.group_id, true)

        if (success) {
            // Update local state
            val updatedGroups = _uiState.value.groups.map {
                if (it.group_id == group.group_id) {
                    it.copy(is_project = true)
                } else {
                    it
                }
            }

            _uiState.value = _uiState.value.copy(
                groups = updatedGroups,
                currentGroup = if (_uiState.value.currentGroup?.group_id == group.group_id) {
                    _uiState.value.currentGroup?.copy(is_project = true)
                } else {
                    _uiState.value.currentGroup
                }
            )

            // Navigate to group screen with project mode enabled
            navigateToGroup(group.group_id)
        } else {
            // No action needed for error case
        }
    }

    fun createNewConversationInGroup(group: ChatGroup) {
        val currentUser = _appSettings.value.current_user
        val newChat = repository.createNewChatInGroup(currentUser, "שיחה חדשה", group.group_id)

        // Update local state
        val updatedChatHistory = _uiState.value.chatHistory + newChat
        _uiState.value = _uiState.value.copy(
            chatHistory = updatedChatHistory,
            currentChat = newChat
        )

        // Navigate to the new chat
        navigateToScreen(Screen.Chat)
    }

    fun showGroupDeleteConfirmation(group: ChatGroup) {
        _uiState.value = _uiState.value.copy(
            showDeleteGroupConfirmation = group,
            groupContextMenu = null
        )
    }

    fun hideGroupDeleteConfirmation() {
        _uiState.value = _uiState.value.copy(
            showDeleteGroupConfirmation = null
        )
    }

    fun deleteGroup(group: ChatGroup) {
        val currentUser = _appSettings.value.current_user
        val success = repository.deleteGroup(currentUser, group.group_id)

        if (success) {
            // Update local state - remove group and unassign chats
            val updatedGroups = _uiState.value.groups.filter { it.group_id != group.group_id }
            val updatedChatHistory = _uiState.value.chatHistory.map { chat ->
                if (chat.group == group.group_id) {
                    chat.copy(group = null)
                } else {
                    chat
                }
            }

            _uiState.value = _uiState.value.copy(
                groups = updatedGroups,
                chatHistory = updatedChatHistory,
                currentGroup = if (_uiState.value.currentGroup?.group_id == group.group_id) null else _uiState.value.currentGroup,
                showDeleteGroupConfirmation = null
            )

            // Navigate back to chat history if we were in the group screen
            if (_currentScreen.value is Screen.Group && (_currentScreen.value as Screen.Group).groupId == group.group_id) {
                navigateToScreen(Screen.ChatHistory)
            }
        } else {
            _uiState.value = _uiState.value.copy(
                showDeleteGroupConfirmation = null
            )
        }
    }

    // Quick Settings & Chat Export
    fun toggleQuickSettings() {
        _uiState.value = _uiState.value.copy(
            quickSettingsExpanded = !_uiState.value.quickSettingsExpanded
        )
    }

    // Thinking Budget Control
    /**
     * Handle click on the thinking budget control button.
     * Shows appropriate UI based on current provider/model thinking support.
     */
    fun onThinkingBudgetButtonClick() {
        val budgetType = _uiState.value.getThinkingBudgetType()

        when (budgetType) {
            is ThinkingBudgetType.NotSupported -> {
                // Show toast that this model doesn't support thinking
                Toast.makeText(context, "מודל זה אינו תומך במצב חשיבה", Toast.LENGTH_SHORT).show()
            }
            is ThinkingBudgetType.InDevelopment -> {
                // Show toast that support is in development
                Toast.makeText(context, "נכון לעכשיו התמיכה בפרמטר למודל זה עדיין בפיתוח", Toast.LENGTH_SHORT).show()
            }
            is ThinkingBudgetType.Discrete, is ThinkingBudgetType.Continuous -> {
                // Toggle popup visibility
                _uiState.value = _uiState.value.copy(
                    showThinkingBudgetPopup = !_uiState.value.showThinkingBudgetPopup
                )

                // Initialize with default value if currently None
                if (_uiState.value.thinkingBudgetValue == ThinkingBudgetValue.None) {
                    val provider = _uiState.value.currentProvider?.provider ?: return
                    val defaultValue = ThinkingBudgetConfig.getDefaultValue(provider, _uiState.value.currentModel)
                    _uiState.value = _uiState.value.copy(thinkingBudgetValue = defaultValue)
                }
            }
        }
    }

    /**
     * Show the thinking budget popup.
     */
    fun showThinkingBudgetPopup() {
        _uiState.value = _uiState.value.copy(showThinkingBudgetPopup = true)
    }

    /**
     * Hide the thinking budget popup.
     */
    fun hideThinkingBudgetPopup() {
        _uiState.value = _uiState.value.copy(showThinkingBudgetPopup = false)
    }

    /**
     * Set the thinking budget value.
     */
    fun setThinkingBudgetValue(value: ThinkingBudgetValue) {
        _uiState.value = _uiState.value.copy(thinkingBudgetValue = value)
    }

    /**
     * Set discrete thinking effort level.
     */
    fun setThinkingEffort(level: String) {
        _uiState.value = _uiState.value.copy(
            thinkingBudgetValue = ThinkingBudgetValue.Effort(level)
        )
    }

    /**
     * Set continuous thinking token budget.
     */
    fun setThinkingTokenBudget(tokens: Int) {
        _uiState.value = _uiState.value.copy(
            thinkingBudgetValue = ThinkingBudgetValue.Tokens(tokens)
        )
    }

    /**
     * Reset thinking budget to model default when provider/model changes.
     */
    fun resetThinkingBudgetToDefault() {
        val provider = _uiState.value.currentProvider?.provider
        val model = _uiState.value.currentModel

        if (provider != null) {
            val defaultValue = ThinkingBudgetConfig.getDefaultValue(provider, model)
            _uiState.value = _uiState.value.copy(
                thinkingBudgetValue = defaultValue,
                showThinkingBudgetPopup = false
            )
        }
    }

    fun openChatExportDialog() {
        val currentChat = _uiState.value.currentChat ?: return
        val currentUser = _appSettings.value.current_user
        viewModelScope.launch {
            val chatJson = withContext(Dispatchers.IO) {
                repository.getChatJson(currentUser, currentChat.chat_id)
            }.orEmpty()

            _uiState.value = _uiState.value.copy(
                showChatExportDialog = true,
                chatExportJson = chatJson,
                isChatExportEditable = false
            )

            if (chatJson.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = context.getString(R.string.no_content_to_export)
                )
            }
        }
    }

    fun closeChatExportDialog() {
        _uiState.value = _uiState.value.copy(
            showChatExportDialog = false,
            isChatExportEditable = false
        )
    }

    fun enableChatExportEditing() {
        _uiState.value = _uiState.value.copy(isChatExportEditable = true)
    }

    fun updateChatExportContent(content: String) {
        _uiState.value = _uiState.value.copy(chatExportJson = content)
    }

    fun shareChatExportContent() {
        val content = _uiState.value.chatExportJson
        val chatId = _uiState.value.currentChat?.chat_id

        if (content.isBlank()) {
            _uiState.value = _uiState.value.copy(
                snackbarMessage = context.getString(R.string.no_content_to_export)
            )
            return
        }

        if (chatId == null) {
            _uiState.value = _uiState.value.copy(
                snackbarMessage = context.getString(R.string.error_sending_message)
            )
            return
        }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.saveChatJsonToDownloads(chatId, content)
                }

                if (result != null) {
                    // File was saved successfully, now share it
                    withContext(Dispatchers.Main) {
                        try {
                            val file = File(result)
                            val uri = FileProvider.getUriForFile(
                                context,
                                context.applicationContext.packageName + ".fileprovider",
                                file
                            )

                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }

                            val chooserTitle = context.getString(R.string.share_chat_title)
                            val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(chooser)
                        } catch (e: Exception) {
                            _uiState.value = _uiState.value.copy(
                                snackbarMessage = context.getString(R.string.error_sending_message)
                            )
                        }
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        snackbarMessage = context.getString(R.string.export_failed)
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = context.getString(R.string.error_sending_message)
                )
            }
        }
    }

    fun saveChatExportToDownloads() {
        val content = _uiState.value.chatExportJson
        val chatId = _uiState.value.currentChat?.chat_id

        if (content.isBlank()) {
            _uiState.value = _uiState.value.copy(
                snackbarMessage = context.getString(R.string.no_content_to_export)
            )
            return
        }

        if (chatId == null) {
            _uiState.value = _uiState.value.copy(
                snackbarMessage = context.getString(R.string.error_sending_message)
            )
            return
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.saveChatJsonToDownloads(chatId, content)
            }

            if (result != null) {
                // Show success notification
                showDownloadNotification(chatId)

                _uiState.value = _uiState.value.copy(
                    snackbarMessage = context.getString(R.string.export_success)
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = context.getString(R.string.export_failed)
                )
            }
        }
    }

    private fun showDownloadNotification(chatId: String) {
        android.util.Log.d("ChatExport", "Attempting to show notification for chat: $chatId on Android ${android.os.Build.VERSION.SDK_INT}")

        try {
            // Check if POST_NOTIFICATIONS permission is required (Android 13+)
            val needsPermission = android.os.Build.VERSION.SDK_INT >= 33
            android.util.Log.d("ChatExport", "POST_NOTIFICATIONS permission required: $needsPermission")

            if (needsPermission) {
                // Check if we have the permission
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

                android.util.Log.d("ChatExport", "Has POST_NOTIFICATIONS permission: $hasPermission")

                if (!hasPermission) {
                    android.util.Log.w("ChatExport", "POST_NOTIFICATIONS permission not granted, cannot show notification")
                    return
                }
            }

            val notificationManager = NotificationManagerCompat.from(context)

            // Create notification channel for Android 8.0+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "chat_export_channel",
                    "התראות ייצוא שיחה",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "התראות עבור פעולות ייצוא שיחה"
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Create intent to open the downloaded file
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${chatId}.json")
            android.util.Log.d("ChatExport", "File path: ${file.absolutePath}")
            android.util.Log.d("ChatExport", "File exists: ${file.exists()}")

            if (file.exists()) {
                val fileUri = FileProvider.getUriForFile(
                    context,
                    context.applicationContext.packageName + ".fileprovider",
                    file
                )
                android.util.Log.d("ChatExport", "File URI: $fileUri")

                val openFileIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "application/json")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                try {
                    @Suppress("WrongConstant") // FLAG_IMMUTABLE is required for Android 12+
                    val pendingIntent = PendingIntentCompat.getActivity(
                        context,
                        "chat_export_${chatId}".hashCode(),
                        openFileIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        false
                    )

                    val notification = NotificationCompat.Builder(context, "chat_export_channel")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("שיחה הורדה בהצלחה")
                        .setContentText("הקובץ ${chatId}.json נשמר בתיקיית ההורדות")
                        .setPriority(NotificationCompat.PRIORITY_HIGH) // Higher priority for downloads
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent) // Open file when clicked
                        .build()

                    // Use a unique ID for each notification to avoid conflicts
                    val notificationId = "chat_export_${chatId}_${System.currentTimeMillis()}".hashCode()
                    notificationManager.notify(notificationId, notification)

                    android.util.Log.d("ChatExport", "Notification sent for chat: $chatId with file URI: $fileUri")
                } catch (e: Exception) {
                    android.util.Log.e("ChatExport", "Failed to create pending intent: ${e.message}")
                    // Create notification without click action if pending intent fails
                    val fallbackNotification = NotificationCompat.Builder(context, "chat_export_channel")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("שיחה הורדה בהצלחה")
                        .setContentText("הקובץ ${chatId}.json נשמר בתיקיית ההורדות")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()

                    val notificationId = "chat_export_${chatId}_${System.currentTimeMillis()}".hashCode()
                    notificationManager.notify(notificationId, fallbackNotification)
                }
            } else {
                android.util.Log.w("ChatExport", "File does not exist at path: ${file.absolutePath}")
                // Create notification without click action if file doesn't exist
                val fallbackNotification = NotificationCompat.Builder(context, "chat_export_channel")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("שיחה הורדה בהצלחה")
                    .setContentText("הקובץ ${chatId}.json נשמר בתיקיית ההורדות (אך לא ניתן לפתוח)")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()

                val notificationId = "chat_export_${chatId}_${System.currentTimeMillis()}".hashCode()
                notificationManager.notify(notificationId, fallbackNotification)
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatExport", "Failed to send notification: ${e.message}")
            // If notification fails, just continue silently
            // The snackbar message will still show the success
        }
    }

    // Search Methods
    
    fun enterSearchMode() {
        _uiState.value = _uiState.value.copy(searchMode = true, searchQuery = "")
    }
    
    fun enterConversationSearchMode() {
        _uiState.value = _uiState.value.copy(searchMode = true, searchQuery = "")
    }
    
    fun enterSearchModeWithQuery(query: String) {
        _uiState.value = _uiState.value.copy(
            searchMode = true, 
            searchQuery = query
        )
        // Perform search immediately with the given query
        performConversationSearch()
    }
    
    fun exitSearchMode() {
        _uiState.value = _uiState.value.copy(
            searchMode = false,
            searchQuery = "",
            searchResults = emptyList(),
            searchContext = null // Clear search context when exiting
        )
    }
    
    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        // Always use conversation search when in search mode in chat screen
        if (_uiState.value.searchMode && _currentScreen.value == Screen.Chat) {
            performConversationSearch()
        } else if (!_uiState.value.searchMode) {
            // Only perform general search if not in conversation search mode
            performSearch()
        }
    }
    
    fun performSearch() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
            return
        }
        
        val currentUser = _appSettings.value.current_user
        val results = repository.searchChats(currentUser, query)
        _uiState.value = _uiState.value.copy(searchResults = results)
    }

    fun performConversationSearch() {
        val query = _uiState.value.searchQuery.trim()
        val currentChat = _uiState.value.currentChat
        
        if (query.isEmpty() || currentChat == null) {
            _uiState.value = _uiState.value.copy(searchContext = null)
            return
        }
        
        // Search in current conversation messages and attachments
        val searchResults = mutableListOf<SearchResult>()
        
        currentChat.messages.forEachIndexed { messageIndex, message ->
            val highlightRanges = mutableListOf<IntRange>()
            
            // Search in message text
            if (message.text.contains(query, ignoreCase = true)) {
                // Find all occurrences of the search term
                var startIndex = 0
                while (true) {
                    val index = message.text.indexOf(query, startIndex, ignoreCase = true)
                    if (index == -1) break
                    highlightRanges.add(IntRange(index, index + query.length - 1))
                    startIndex = index + 1
                }
                
                if (highlightRanges.isNotEmpty()) {
                    searchResults.add(
                        SearchResult(
                            chat = currentChat,
                            searchQuery = query,
                            matchType = SearchMatchType.CONTENT,
                            messageIndex = messageIndex,
                            highlightRanges = highlightRanges
                        )
                    )
                }
            }
            
            // Search in attachment file names
            message.attachments.forEach { attachment ->
                if (attachment.file_name.contains(query, ignoreCase = true)) {
                    searchResults.add(
                        SearchResult(
                            chat = currentChat,
                            searchQuery = query,
                            matchType = SearchMatchType.FILE_NAME,
                            messageIndex = messageIndex,
                            highlightRanges = emptyList() // File name highlighting would be in a separate UI element
                        )
                    )
                }
            }
        }
        
        // Update the search context to highlight matches
        _uiState.value = _uiState.value.copy(
            searchResults = searchResults,
            searchContext = if (searchResults.isNotEmpty()) searchResults.first() else null
        )
    }
    
    fun clearSearchContext() {
        _uiState.value = _uiState.value.copy(searchContext = null)
    }

    // Child Lock Methods

    fun setupChildLock(password: String, startTime: String, endTime: String, deviceId: String) {
        viewModelScope.launch {
            try {
                val parentalControlManager = ParentalControlManager(context)
                parentalControlManager.setParentalPassword(password, deviceId)

                // Update settings with child lock enabled
                val updatedSettings = _appSettings.value.copy(
                    childLockSettings = ChildLockSettings(
                        enabled = true,
                        encryptedPassword = parentalControlManager.getEncryptedPassword(),
                        startTime = startTime,
                        endTime = endTime
                    )
                )

                repository.saveAppSettings(updatedSettings)
                _appSettings.value = updatedSettings
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "שגיאה בהגדרת נעילת ילדים: ${e.message}"
                )
            }
        }
    }

    fun verifyAndDisableChildLock(password: String, deviceId: String): Boolean {
        return try {
            val parentalControlManager = ParentalControlManager(context)
            val isValidPassword = parentalControlManager.verifyParentalPassword(password, deviceId)

            if (isValidPassword) {
                // Disable child lock
                val updatedSettings = _appSettings.value.copy(
                    childLockSettings = ChildLockSettings(
                        enabled = false,
                        encryptedPassword = "",
                        startTime = "23:00",
                        endTime = "07:00"
                    )
                )

                repository.saveAppSettings(updatedSettings)
                _appSettings.value = updatedSettings
                true
            } else {
                // Show error message
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "סיסמה שגויה"
                )
                false
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                snackbarMessage = "שגיאה בביטול נעילת ילדים: ${e.message}"
            )
            false
        }
    }

    fun updateChildLockSettings(enabled: Boolean, password: String, startTime: String, endTime: String) {
        val updatedSettings = _appSettings.value.copy(
            childLockSettings = ChildLockSettings(
                enabled = enabled,
                encryptedPassword = password,
                startTime = startTime,
                endTime = endTime
            )
        )

        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
    }

    fun isChildLockActive(): Boolean {
        val settings = _appSettings.value.childLockSettings
        if (!settings.enabled) return false

        return isCurrentTimeInLockRange(settings.startTime, settings.endTime)
    }

    private fun isCurrentTimeInLockRange(startTime: String, endTime: String): Boolean {
        try {
            val now = LocalTime.now()
            val start = LocalTime.parse(startTime)
            val end = LocalTime.parse(endTime)

            // Handle case where end time is next day (e.g., 23:00 to 07:00)
            return if (start.isBefore(end)) {
                // Same day range (e.g., 09:00 to 17:00)
                now.isAfter(start) && now.isBefore(end)
            } else {
                // Overnight range (e.g., 23:00 to 07:00)
                now.isAfter(start) || now.isBefore(end)
            }
        } catch (e: Exception) {
            // If parsing fails, default to not locked
            return false
        }
    }

    fun getLockEndTime(): String {
        val settings = _appSettings.value.childLockSettings
        return settings.endTime
    }

    // Tool Management Methods

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
        if (currentSettings.enabledTools.contains(toolId)) {
            val updatedSettings = currentSettings.copy(
                enabledTools = currentSettings.enabledTools.filter { it != toolId }
            )
            repository.saveAppSettings(updatedSettings)
            _appSettings.value = updatedSettings
        }
    }

    fun getAvailableTools(): List<com.example.ApI.tools.Tool> {
        return ToolRegistry.getInstance().getAllTools()
    }

    // GitHub Integration Methods

    /**
     * Start GitHub OAuth flow (opens external browser)
     * @return OAuth state parameter for verification
     */
    fun connectGitHub(): String {
        val oauthService = com.example.ApI.data.network.GitHubOAuthService(context)
        return oauthService.startAuthorizationFlow()
    }

    /**
     * Get GitHub OAuth URL and state for in-app WebView authentication.
     * @return Pair of (authUrl, state)
     */
    fun getGitHubAuthUrl(): Pair<String, String> {
        val oauthService = com.example.ApI.data.network.GitHubOAuthService(context)
        return oauthService.getAuthorizationUrlAndState()
    }

    /**
     * Handle OAuth callback and save connection
     * @param code Authorization code from GitHub
     * @param state State parameter for verification
     * @return Success/failure message
     */
    fun handleGitHubCallback(code: String, state: String): String {
        viewModelScope.launch {
            try {
                val oauthService = com.example.ApI.data.network.GitHubOAuthService(context)

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
                                val username = _appSettings.value.current_user
                                repository.saveGitHubConnection(username, connection)

                                // Register GitHub tools
                                val toolRegistry = ToolRegistry.getInstance()
                                toolRegistry.registerGitHubTools(apiService, auth.accessToken, user.login)

                                // Reload appSettings to get the updated githubConnections, then add tool IDs
                                val freshSettings = repository.loadAppSettings()
                                val githubToolIds = toolRegistry.getGitHubToolIds()
                                val updatedEnabledTools = (freshSettings.enabledTools + githubToolIds).distinct()
                                val updatedSettings = freshSettings.copy(enabledTools = updatedEnabledTools)
                                repository.saveAppSettings(updatedSettings)
                                _appSettings.value = updatedSettings
                            },
                            onFailure = { error ->
                                _uiState.value = _uiState.value.copy(
                                    snackbarMessage = "Failed to get GitHub user info: ${error.message}"
                                )
                            }
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            snackbarMessage = "GitHub authentication failed: ${error.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "Error connecting to GitHub: ${e.message}"
                )
            }
        }
        return "Processing GitHub connection..."
    }

    /**
     * Disconnect GitHub and remove all GitHub tools
     */
    fun disconnectGitHub() {
        viewModelScope.launch {
            try {
                val username = _appSettings.value.current_user
                val connection = repository.loadGitHubConnection(username)

                if (connection != null) {
                    // Revoke token on GitHub
                    val oauthService = com.example.ApI.data.network.GitHubOAuthService(context)
                    oauthService.revokeToken(connection.auth.accessToken)
                }

                // Remove local connection data
                repository.removeGitHubConnection(username)

                // Unregister GitHub tools
                val toolRegistry = ToolRegistry.getInstance()
                toolRegistry.unregisterGitHubTools()

                // Reload appSettings to get the updated githubConnections (with removal), then remove tool IDs
                val freshSettings = repository.loadAppSettings()
                val githubToolIds = toolRegistry.getGitHubToolIds()
                val updatedEnabledTools = freshSettings.enabledTools.filter { it !in githubToolIds }
                val updatedSettings = freshSettings.copy(enabledTools = updatedEnabledTools)
                repository.saveAppSettings(updatedSettings)
                _appSettings.value = updatedSettings
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "Error disconnecting GitHub: ${e.message}"
                )
            }
        }
    }

    /**
     * Check if GitHub is connected for current user
     */
    fun isGitHubConnected(): Boolean {
        val username = _appSettings.value.current_user
        return repository.isGitHubConnected(username)
    }

    /**
     * Get GitHub connection info for current user
     */
    fun getGitHubConnection(): GitHubConnection? {
        val username = _appSettings.value.current_user
        return repository.loadGitHubConnection(username)
    }

    /**
     * Initialize GitHub tools if already connected
     * Should be called during app startup
     */
    fun initializeGitHubToolsIfConnected() {
        viewModelScope.launch {
            try {
                val username = _appSettings.value.current_user
                val serviceAndToken = repository.getGitHubApiService(username)

                if (serviceAndToken != null) {
                    val (apiService, accessToken) = serviceAndToken
                    val connection = repository.loadGitHubConnection(username)

                    if (connection != null) {
                        val toolRegistry = ToolRegistry.getInstance()
                        toolRegistry.registerGitHubTools(apiService, accessToken, connection.user.login)

                        // Ensure GitHub tools are enabled in settings
                        val currentSettings = _appSettings.value
                        val githubToolIds = toolRegistry.getGitHubToolIds()
                        if (!currentSettings.enabledTools.containsAll(githubToolIds)) {
                            val updatedEnabledTools = (currentSettings.enabledTools + githubToolIds).distinct()
                            val updatedSettings = currentSettings.copy(enabledTools = updatedEnabledTools)
                            repository.saveAppSettings(updatedSettings)
                            _appSettings.value = updatedSettings
                        }
                    }
                }
            } catch (e: Exception) {
                // Silent fail - GitHub tools won't be available
            }
        }
    }

    /**
     * Show a snackbar message to the user
     */
    fun showSnackbar(message: String) {
        _uiState.value = _uiState.value.copy(snackbarMessage = message)
    }

    // ==================== Google Workspace Integration ====================

    private var googleWorkspaceAuthService: com.example.ApI.data.network.GoogleWorkspaceAuthService? = null

    /**
     * Initialize Google Workspace auth service
     */
    private fun initGoogleWorkspaceAuthService() {
        if (googleWorkspaceAuthService == null) {
            googleWorkspaceAuthService = com.example.ApI.data.network.GoogleWorkspaceAuthService(context)
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
        viewModelScope.launch {
            try {
                initGoogleWorkspaceAuthService()
                android.util.Log.d("ChatViewModel", "Handling Google Sign-In result intent")
                val result = googleWorkspaceAuthService!!.handleSignInResult(data)

                result.fold(
                    onSuccess = { (auth, user) ->
                        android.util.Log.d("ChatViewModel", "Google Sign-In success: ${user.email}")
                        // Save connection
                        val connection = GoogleWorkspaceConnection(auth = auth, user = user)
                        val username = _appSettings.value.current_user
                        
                        // Save and reload settings to update UI
                        val updatedSettings = withContext(Dispatchers.IO) {
                            android.util.Log.d("ChatViewModel", "Saving connection to repository")
                            repository.saveGoogleWorkspaceConnection(username, connection)
                            android.util.Log.d("ChatViewModel", "Reloading app settings")
                            repository.loadAppSettings()
                        }
                        
                        _appSettings.value = updatedSettings
                        android.util.Log.d("ChatViewModel", "Settings updated with new connection")

                        // Register tools
                        initializeGoogleWorkspaceToolsIfConnected()
                    },
                    onFailure = { error ->
                        android.util.Log.e("ChatViewModel", "Google Sign-In failed", error)
                        showSnackbar("שגיאת התחברות: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Exception in handleGoogleSignInResult", e)
                showSnackbar("שגיאה: ${e.message}")
            }
        }
    }

    /**
     * Check if Google Workspace is connected
     */
    fun isGoogleWorkspaceConnected(): Boolean {
        val username = _appSettings.value.current_user
        return repository.isGoogleWorkspaceConnected(username)
    }

    /**
     * Get current Google Workspace connection
     */
    fun getGoogleWorkspaceConnection(): GoogleWorkspaceConnection? {
        val username = _appSettings.value.current_user
        return repository.loadGoogleWorkspaceConnection(username)
    }

    /**
     * Disconnect Google Workspace
     */
    fun disconnectGoogleWorkspace() {
        viewModelScope.launch {
            try {
                initGoogleWorkspaceAuthService()
                googleWorkspaceAuthService!!.signOut()

                val username = _appSettings.value.current_user
                repository.removeGoogleWorkspaceConnection(username)

                // Unregister tools
                val toolRegistry = ToolRegistry.getInstance()
                toolRegistry.unregisterGoogleWorkspaceTools()

                // Reload appSettings to get the updated googleWorkspaceConnections (with removal), then remove tool IDs
                val freshSettings = repository.loadAppSettings()
                val googleToolIds = toolRegistry.getGoogleWorkspaceToolIds()
                val updatedEnabledTools = freshSettings.enabledTools.filter { it !in googleToolIds }
                val updatedSettings = freshSettings.copy(enabledTools = updatedEnabledTools)
                repository.saveAppSettings(updatedSettings)
                _appSettings.value = updatedSettings
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
        viewModelScope.launch {
            try {
                val username = _appSettings.value.current_user
                val services = EnabledGoogleServices(gmail, calendar, drive)
                repository.updateGoogleWorkspaceEnabledServices(username, services)

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
        viewModelScope.launch {
            try {
                val username = _appSettings.value.current_user
                val connection = repository.loadGoogleWorkspaceConnection(username) ?: return@launch

                if (connection.auth.isExpired()) {
                    // Token expired - user needs to reconnect
                    return@launch
                }

                // Get API services based on enabled services
                val apiServices = repository.getGoogleWorkspaceApiServices(username) ?: return@launch
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

                // Add enabled Google Workspace tool IDs to appSettings.enabledTools
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
                val freshSettings = repository.loadAppSettings()
                val cleanedTools = freshSettings.enabledTools.filter { it !in allGoogleToolIds }
                val updatedEnabledTools = (cleanedTools + enabledGoogleToolIds).distinct()
                val updatedSettings = freshSettings.copy(enabledTools = updatedEnabledTools)
                repository.saveAppSettings(updatedSettings)
                _appSettings.value = updatedSettings
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ==================== Chat Import from JSON ====================

    /**
     * Handle user choice to import JSON as a chat
     */
    fun importPendingChatJson() {
        val pending = _uiState.value.pendingChatImport ?: return

        viewModelScope.launch {
            try {
                val currentUser = _appSettings.value.current_user
                val importedChatId = repository.importSingleChat(pending.jsonContent, currentUser)

                if (importedChatId != null) {
                    // Reload chat history
                    val chatHistory = repository.loadChatHistory(currentUser)
                    _uiState.value = _uiState.value.copy(
                        chatHistory = chatHistory.chat_history,
                        groups = chatHistory.groups,
                        pendingChatImport = null
                    )

                    // Find and select the imported chat
                    val importedChat = chatHistory.chat_history.find { it.chat_id == importedChatId }
                    if (importedChat != null) {
                        selectChat(importedChat)
                        navigateToScreen(Screen.Chat)
                    }

                    Toast.makeText(context, "הצ'אט יובא בהצלחה", Toast.LENGTH_SHORT).show()
                } else {
                    _uiState.value = _uiState.value.copy(pendingChatImport = null)
                    showSnackbar("שגיאה בייבוא הצ'אט")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(pendingChatImport = null)
                showSnackbar("שגיאה בייבוא הצ'אט: ${e.message}")
            }
        }
    }

    /**
     * Handle user choice to attach JSON as a regular file
     */
    fun attachPendingJsonAsFile() {
        val pending = _uiState.value.pendingChatImport ?: return

        // Clear the pending import
        _uiState.value = _uiState.value.copy(pendingChatImport = null)

        // Add as regular file attachment
        addFileFromUri(pending.uri, pending.fileName, pending.mimeType)
    }

    /**
     * Dismiss the chat import dialog without action
     */
    fun dismissChatImportDialog() {
        _uiState.value = _uiState.value.copy(pendingChatImport = null)
    }

    // ==================== Branching System ====================

    /**
     * Get branch info for a specific message.
     * Returns info about available variants at the node containing this message.
     */
    fun getBranchInfoForMessage(message: Message): BranchInfo? {
        val currentChat = _uiState.value.currentChat ?: return null
        return repository.getBranchInfoForMessage(currentChat, message.id)
    }

    /**
     * Navigate to the next variant at a specific node.
     */
    fun navigateToNextVariant(nodeId: String) {
        val currentUser = _appSettings.value.current_user
        val currentChat = _uiState.value.currentChat ?: return

        // Get current branch info
        val branchInfo = repository.getBranchInfo(currentChat, nodeId) ?: return
        if (!branchInfo.hasNext) return

        // Switch to next variant
        val updatedChat = repository.switchVariant(
            currentUser,
            currentChat.chat_id,
            nodeId,
            branchInfo.currentVariantIndex + 1
        ) ?: return

        // Update UI
        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
        _uiState.value = _uiState.value.copy(
            currentChat = updatedChat,
            chatHistory = updatedChatHistory
        )
    }

    /**
     * Navigate to the previous variant at a specific node.
     */
    fun navigateToPreviousVariant(nodeId: String) {
        val currentUser = _appSettings.value.current_user
        val currentChat = _uiState.value.currentChat ?: return

        // Get current branch info
        val branchInfo = repository.getBranchInfo(currentChat, nodeId) ?: return
        if (!branchInfo.hasPrevious) return

        // Switch to previous variant
        val updatedChat = repository.switchVariant(
            currentUser,
            currentChat.chat_id,
            nodeId,
            branchInfo.currentVariantIndex - 1
        ) ?: return

        // Update UI
        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
        _uiState.value = _uiState.value.copy(
            currentChat = updatedChat,
            chatHistory = updatedChatHistory
        )
    }

    /**
     * Navigate to a specific variant by index at a node.
     */
    fun navigateToVariant(nodeId: String, variantIndex: Int) {
        val currentUser = _appSettings.value.current_user
        val currentChat = _uiState.value.currentChat ?: return

        val updatedChat = repository.switchVariant(
            currentUser,
            currentChat.chat_id,
            nodeId,
            variantIndex
        ) ?: return

        // Update UI
        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
        _uiState.value = _uiState.value.copy(
            currentChat = updatedChat,
            chatHistory = updatedChatHistory
        )
    }

    /**
     * Ensure the current chat has branching structure (migrate if needed).
     */
    fun ensureBranchingStructure() {
        val currentUser = _appSettings.value.current_user
        val currentChat = _uiState.value.currentChat ?: return

        if (!currentChat.hasBranchingStructure) {
            val migratedChat = repository.ensureBranchingStructure(currentUser, currentChat.chat_id)
            if (migratedChat != null) {
                val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
                _uiState.value = _uiState.value.copy(
                    currentChat = migratedChat,
                    chatHistory = updatedChatHistory
                )
            }
        }
    }

}






