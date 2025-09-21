package com.example.ApI.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.ParentalControlManager
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.charset.Charset
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.LocalTime
import java.time.LocalDateTime

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
    }
    
    private fun getCurrentDateTimeISO(): String {
        return Instant.now().toString()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val settings = repository.loadAppSettings()
            _appSettings.value = settings

            val providers = repository.loadProviders()
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

        viewModelScope.launch {
            val multiModeEnabled = _appSettings.value.multiMessageMode
            // In multi-message mode we do not start streaming immediately
            if (!multiModeEnabled) {
                _uiState.value = _uiState.value.copy(
                    isLoading = true, 
                    isStreaming = true,
                    streamingText = "",
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

            var updatedChat = repository.addMessageToChat(
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

                    // Send message with streaming - this will automatically re-upload files if provider changed
                    val streamingCallback = object : StreamingCallback {
                        override fun onPartialResponse(text: String) {
                            // Update UI with streaming text
                            val currentStreamingText = _uiState.value.streamingText
                            _uiState.value = _uiState.value.copy(
                                streamingText = currentStreamingText + text
                            )
                        }

                        override fun onComplete(fullText: String) {
                            // Stream is complete, add final message to chat
                            viewModelScope.launch {
                                val assistantMessage = Message(
                                    role = "assistant",
                                    text = fullText,
                                    attachments = emptyList(),
                                    model = currentModel,
                                    datetime = getCurrentDateTimeISO()
                                )

                                val finalUpdatedChat = repository.addMessageToChat(
                                    currentUser,
                                    updatedChat!!.chat_id,
                                    assistantMessage
                                )

                                val finalChatHistory = repository.loadChatHistory(currentUser).chat_history

                                _uiState.value = _uiState.value.copy(
                                    currentChat = finalUpdatedChat,
                                    isLoading = false,
                                    isStreaming = false,
                                    streamingText = "",
                                    chatHistory = finalChatHistory
                                )
                                
                                // Handle title generation after response completion
                                handleTitleGeneration(finalUpdatedChat!!)
                            }
                        }

                        override fun onError(error: String) {
                            // Handle streaming error
                            viewModelScope.launch {
                                val errorMessage = Message(
                                    role = "assistant",
                                    text = "שגיאה: $error",
                                    attachments = emptyList(),
                                    model = currentModel,
                                    datetime = getCurrentDateTimeISO()
                                )

                                val finalUpdatedChat = repository.addMessageToChat(
                                    currentUser,
                                    updatedChat!!.chat_id,
                                    errorMessage
                                )

                                val finalChatHistory = repository.loadChatHistory(currentUser).chat_history

                                _uiState.value = _uiState.value.copy(
                                    currentChat = finalUpdatedChat,
                                    isLoading = false,
                                    isStreaming = false,
                                    streamingText = "",
                                    chatHistory = finalChatHistory
                                )
                            }
                        }
                    }

                    // Get project attachments if this chat belongs to a project
                    val projectAttachments = getCurrentChatProjectGroup()?.group_attachments ?: emptyList()

                    repository.sendMessage(
                        provider = currentProvider,
                        modelName = currentModel,
                        messages = updatedChat!!.messages,
                        systemPrompt = systemPrompt,
                        username = currentUser,
                        chatId = updatedChat.chat_id,
                        projectAttachments = projectAttachments,
                        webSearchEnabled = _uiState.value.webSearchEnabled,
                        enabledTools = getEnabledToolSpecifications(),
                        callback = streamingCallback
                    )
                    
                    // Reload chat to get any updated file IDs from re-uploads
                    val refreshedChatHistory = repository.loadChatHistory(currentUser)
                    val refreshedCurrentChat = refreshedChatHistory.chat_history.find { 
                        it.chat_id == updatedChat.chat_id 
                    } ?: updatedChat
                    
                    _uiState.value = _uiState.value.copy(
                        currentChat = refreshedCurrentChat,
                        chatHistory = refreshedChatHistory.chat_history,
                        isLoading = false  // Set to false since streaming will handle the rest
                    )

                } catch (e: Exception) {
                    // Handle unexpected errors
                    val errorMessage = Message(
                        role = "assistant",
                        text = "שגיאה לא צפויה: ${e.message}",
                        attachments = emptyList(),
                        model = currentModel,
                        datetime = getCurrentDateTimeISO()
                    )

                    val finalUpdatedChat = repository.addMessageToChat(
                        currentUser,
                        updatedChat!!.chat_id,
                        errorMessage
                    )

                    val finalChatHistory = repository.loadChatHistory(currentUser).chat_history

                    _uiState.value = _uiState.value.copy(
                        currentChat = finalUpdatedChat,
                        isLoading = false,
                        isStreaming = false,
                        streamingText = "",
                        chatHistory = finalChatHistory
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isStreaming = false,
                    streamingText = ""
                )
            }
        }
    }

    fun sendBufferedBatch() {
        val currentUser = _appSettings.value.current_user
        val currentChat = _uiState.value.currentChat ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isStreaming = true,
                streamingText = "",
                showReplyButton = false
            )

            val currentProvider = _uiState.value.currentProvider
            val currentModel = _uiState.value.currentModel
            val systemPrompt = getEffectiveSystemPrompt()

            if (currentProvider != null && currentModel.isNotEmpty()) {
                try {
                    // Send existing conversation with streaming
                    val streamingCallback = object : StreamingCallback {
                        override fun onPartialResponse(text: String) {
                            val currentStreamingText = _uiState.value.streamingText
                            _uiState.value = _uiState.value.copy(
                                streamingText = currentStreamingText + text
                            )
                        }

                        override fun onComplete(fullText: String) {
                            viewModelScope.launch {
                                val assistantMessage = Message(
                                    role = "assistant",
                                    text = fullText,
                                    attachments = emptyList(),
                                    model = currentModel,
                                    datetime = getCurrentDateTimeISO()
                                )

                                val finalUpdatedChat = repository.addMessageToChat(
                                    currentUser,
                                    currentChat.chat_id,
                                    assistantMessage
                                )

                                val finalChatHistory = repository.loadChatHistory(currentUser).chat_history

                                _uiState.value = _uiState.value.copy(
                                    currentChat = finalUpdatedChat,
                                    isLoading = false,
                                    isStreaming = false,
                                    streamingText = "",
                                    chatHistory = finalChatHistory
                                )

                                // Handle title generation after response completion
                                handleTitleGeneration(finalUpdatedChat!!)
                            }
                        }

                        override fun onError(error: String) {
                            viewModelScope.launch {
                                val errorMessage = Message(
                                    role = "assistant",
                                    text = "שגיאה: $error",
                                    attachments = emptyList(),
                                    model = currentModel,
                                    datetime = getCurrentDateTimeISO()
                                )

                                val finalUpdatedChat = repository.addMessageToChat(
                                    currentUser,
                                    currentChat.chat_id,
                                    errorMessage
                                )

                                val finalChatHistory = repository.loadChatHistory(currentUser).chat_history

                                _uiState.value = _uiState.value.copy(
                                    currentChat = finalUpdatedChat,
                                    isLoading = false,
                                    isStreaming = false,
                                    streamingText = "",
                                    chatHistory = finalChatHistory
                                )
                            }
                        }
                    }

                    // Get project attachments if this chat belongs to a project
                    val projectAttachments = getCurrentChatProjectGroup()?.group_attachments ?: emptyList()

                    repository.sendMessage(
                        provider = currentProvider,
                        modelName = currentModel,
                        messages = currentChat.messages,
                        systemPrompt = systemPrompt,
                        username = currentUser,
                        chatId = currentChat.chat_id,
                        projectAttachments = projectAttachments,
                        webSearchEnabled = _uiState.value.webSearchEnabled,
                        enabledTools = getEnabledToolSpecifications(),
                        callback = streamingCallback
                    )

                    // Optionally refresh chat after potential file re-uploads
                    val refreshed = repository.loadChatHistory(currentUser)
                    val refreshedCurrentChat = refreshed.chat_history.find { it.chat_id == currentChat.chat_id } ?: currentChat
                    _uiState.value = _uiState.value.copy(
                        currentChat = refreshedCurrentChat,
                        chatHistory = refreshed.chat_history
                    )
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isStreaming = false,
                        streamingText = ""
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isStreaming = false,
                    streamingText = ""
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
        
        return enabledToolIds.mapNotNull { toolId ->
            toolRegistry.getTool(toolId)?.getSpecification(currentProvider)
        }
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
        _currentScreen.value = screen
    }
    
    fun exportChatHistory() {
        viewModelScope.launch {
            val currentUser = _appSettings.value.current_user
            val exportPath = repository.exportChatHistory(currentUser)
            
            if (exportPath != null) {
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "היסטוריית הצ'אט יוצאה בהצלחה ל: $exportPath"
                )
            }
        }
    }
    
    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
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

        // Remove message from chat
        val updatedMessages = currentChat.messages.filter { it != message }
        val updatedChat = currentChat.copy(messages = updatedMessages)

        // Update chat history
        val chatHistory = repository.loadChatHistory(currentUser)
        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.chat_id == currentChat.chat_id) updatedChat else chat
        }
        val updatedHistory = chatHistory.copy(chat_history = updatedChats)

        // Save changes
        repository.saveChatHistory(updatedHistory)

        // Update UI state
        _uiState.value = _uiState.value.copy(
            currentChat = updatedChat,
            chatHistory = updatedChats
        )
    }
    
    // Start editing a message
    fun startEditingMessage(message: Message) {
        _uiState.value = _uiState.value.copy(
            editingMessage = message,
            isEditMode = true,
            currentMessage = message.text
        )
    }
    
    // Finish editing a message and replace it in the chat
    fun finishEditingMessage() {
        val editingMessage = _uiState.value.editingMessage ?: return
        val currentChat = _uiState.value.currentChat ?: return
        val currentUser = _appSettings.value.current_user
        val newText = _uiState.value.currentMessage.trim()
        
        if (newText.isEmpty()) return
        
        // Create the updated message
        val updatedMessage = editingMessage.copy(text = newText)
        
        // Replace the message in the repository
        val updatedChat = repository.replaceMessageInChat(
            currentUser, 
            currentChat.chat_id, 
            editingMessage, 
            updatedMessage
        )
        
        // Update chat history
        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
        
        // Clear edit mode and update UI state
        _uiState.value = _uiState.value.copy(
            editingMessage = null,
            isEditMode = false,
            currentMessage = "",
            currentChat = updatedChat,
            chatHistory = updatedChatHistory
        )
    }

    // Confirm the edit and immediately resend from the edited message
    fun confirmEditAndResend() {
        val editingMessage = _uiState.value.editingMessage ?: return
        val currentChat = _uiState.value.currentChat ?: return
        val currentUser = _appSettings.value.current_user
        val newText = _uiState.value.currentMessage.trim()
        if (newText.isEmpty()) return

        // Create the updated message
        val updatedMessage = editingMessage.copy(text = newText)

        // Replace the message in the repository
        val updatedChat = repository.replaceMessageInChat(
            currentUser,
            currentChat.chat_id,
            editingMessage,
            updatedMessage
        ) ?: return

        // Update chat history in UI
        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
        _uiState.value = _uiState.value.copy(
            editingMessage = null,
            isEditMode = false,
            currentMessage = "",
            currentChat = updatedChat,
            chatHistory = updatedChatHistory
        )

        // Immediately resend from the updated message
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
    
    // Resend from a specific message point
    fun resendFromMessage(message: Message) {
        val currentChat = _uiState.value.currentChat ?: return
        val currentUser = _appSettings.value.current_user
        
        viewModelScope.launch {
            // Delete all messages from this point onwards
            val updatedChat = repository.deleteMessagesFromPoint(
                currentUser, 
                currentChat.chat_id, 
                message
            ) ?: return@launch
            
            // Update chat history
            val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
            
            // Update UI state
            _uiState.value = _uiState.value.copy(
                currentChat = updatedChat,
                chatHistory = updatedChatHistory
            )
            
            // Resend the message by adding it back and calling the API
            val messageToResend = message
            val resendUpdatedChat = repository.addMessageToChat(
                currentUser,
                updatedChat.chat_id,
                messageToResend
            )
            
            val finalChatHistory = repository.loadChatHistory(currentUser).chat_history
            
            _uiState.value = _uiState.value.copy(
                currentChat = resendUpdatedChat,
                chatHistory = finalChatHistory,
                isLoading = true,
                isStreaming = true,
                streamingText = ""
            )
            
            // Send API request for the resent message
            val currentProvider = _uiState.value.currentProvider
            val currentModel = _uiState.value.currentModel
            val systemPrompt = getEffectiveSystemPrompt()

            if (currentProvider != null && currentModel.isNotEmpty()) {
                try {
                    val streamingCallback = object : StreamingCallback {
                        override fun onPartialResponse(text: String) {
                            val currentStreamingText = _uiState.value.streamingText
                            _uiState.value = _uiState.value.copy(
                                streamingText = currentStreamingText + text
                            )
                        }

                        override fun onComplete(fullText: String) {
                            viewModelScope.launch {
                                val assistantMessage = Message(
                                    role = "assistant",
                                    text = fullText,
                                    attachments = emptyList(),
                                    model = currentModel,
                                    datetime = getCurrentDateTimeISO()
                                )

                                val finalUpdatedChat = repository.addMessageToChat(
                                    currentUser,
                                    resendUpdatedChat!!.chat_id,
                                    assistantMessage
                                )

                                val finalHistory = repository.loadChatHistory(currentUser).chat_history

                                _uiState.value = _uiState.value.copy(
                                    currentChat = finalUpdatedChat,
                                    isLoading = false,
                                    isStreaming = false,
                                    streamingText = "",
                                    chatHistory = finalHistory
                                )
                            }
                        }

                        override fun onError(error: String) {
                            viewModelScope.launch {
                                val errorMessage = Message(
                                    role = "assistant",
                                    text = "שגיאה: $error",
                                    attachments = emptyList(),
                                    model = currentModel,
                                    datetime = getCurrentDateTimeISO()
                                )

                                val finalUpdatedChat = repository.addMessageToChat(
                                    currentUser,
                                    resendUpdatedChat!!.chat_id,
                                    errorMessage
                                )

                                val finalHistory = repository.loadChatHistory(currentUser).chat_history

                                _uiState.value = _uiState.value.copy(
                                    currentChat = finalUpdatedChat,
                                    isLoading = false,
                                    isStreaming = false,
                                    streamingText = "",
                                    chatHistory = finalHistory
                                )
                            }
                        }
                    }

                    // Get project attachments if this chat belongs to a project
                    val projectAttachments = getCurrentChatProjectGroup()?.group_attachments ?: emptyList()

                    repository.sendMessage(
                        provider = currentProvider,
                        modelName = currentModel,
                        messages = resendUpdatedChat!!.messages,
                        systemPrompt = systemPrompt,
                        username = currentUser,
                        chatId = resendUpdatedChat.chat_id,
                        projectAttachments = projectAttachments,
                        webSearchEnabled = _uiState.value.webSearchEnabled,
                        enabledTools = getEnabledToolSpecifications(),
                        callback = streamingCallback
                    )

                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isStreaming = false,
                        streamingText = ""
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isStreaming = false,
                    streamingText = ""
                )
            }
        }
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
        
        return listOf("openai", "poe", "google").filter { provider ->
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
                
                hideChatContextMenu()
                
            } catch (e: Exception) {
                // If title generation fails, just hide the menu
                hideChatContextMenu()
                println("AI rename failed: ${e.message}")
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
                        addFileFromUri(uri, fileName, mimeType)
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    // Multiple files sharing
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                        val fileName = getFileName(context, uri) ?: "shared_file"
                        val mimeType = intent.type ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
                        addFileFromUri(uri, fileName, mimeType)
                    }
                }
            }
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
            val message = if (pendingChat != null) {
                "קבוצה חדשה נוצרה והשיחה נוספה אליה: ${newGroup.group_name}"
            } else {
                "קבוצה חדשה נוצרה: ${newGroup.group_name}"
            }
            _uiState.value = _uiState.value.copy(
                snackbarMessage = message
            )
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
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "השיחה נוספה לקבוצה"
                )
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
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "השיחה הוסרה מהקבוצה"
                )
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

                // Show success message
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "מצב נעילת ילדים הופעל בהצלחה"
                )
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

                // Show success message
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "נעילת ילדים בוטלה בהצלחה"
                )
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
            
            _uiState.value = _uiState.value.copy(
                snackbarMessage = "הכלי הופעל בהצלחה"
            )
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
            
            _uiState.value = _uiState.value.copy(
                snackbarMessage = "הכלי כובה בהצלחה"
            )
        }
    }

    fun getAvailableTools(): List<com.example.ApI.tools.Tool> {
        return ToolRegistry.getInstance().getAllTools()
    }

}
