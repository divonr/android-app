package com.example.ApI.ui.managers.chat

import android.content.Context
import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages all message sending operations and API communication.
 * Handles sending user messages, file uploads, batch sends, and API request coordination.
 * Split from MessageOperationsManager - this manager owns all actual API calls.
 */
class MessageSendingManager(
    private val repository: DataRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettings: StateFlow<AppSettings>,
    private val uiState: StateFlow<ChatUiState>,
    private val updateUiState: (ChatUiState) -> Unit,
    private val getCurrentDateTimeISO: () -> String,
    private val getEffectiveSystemPrompt: () -> String,
    private val getCurrentChatProjectGroup: () -> ChatGroup?,
    private val getEnabledToolSpecifications: () -> List<ToolSpecification>,
    private val startStreamingRequest: (
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
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?
    ) -> Unit,
    private val createNewChat: (String) -> Chat
) {

    /**
     * Send a new user message.
     * Handles file uploads, multi-message mode, and triggers API call in single-message mode.
     */
    fun sendMessage() {
        val message = uiState.value.currentMessage.trim()
        val hasFiles = uiState.value.selectedFiles.isNotEmpty()
        if (message.isEmpty() && !hasFiles) return

        val currentUser = appSettings.value.current_user
        val currentChat = uiState.value.currentChat ?: createNewChat(
            // Generate preview name from first part of message or file name
            when {
                message.length > 30 -> "${message.take(30)}..."
                message.isNotEmpty() -> message
                hasFiles -> uiState.value.selectedFiles.firstOrNull()?.name ?: "קובץ מצורף"
                else -> "שיחה חדשה"
            }
        )

        val chatId = currentChat.chat_id

        scope.launch {
            val multiModeEnabled = appSettings.value.multiMessageMode
            // In multi-message mode we do not start streaming immediately
            if (!multiModeEnabled) {
                // Use per-chat loading state
                updateUiState(uiState.value.copy(
                    loadingChatIds = uiState.value.loadingChatIds + chatId,
                    streamingChatIds = uiState.value.streamingChatIds + chatId,
                    streamingTextByChat = uiState.value.streamingTextByChat + (chatId to ""),
                    currentMessage = ""
                ))
            } else {
                updateUiState(uiState.value.copy(
                    currentMessage = ""
                ))
            }

            val userMessage = Message(
                role = "user",
                text = if (message.isNotEmpty()) message else "[קובץ מצורף]",
                attachments = uiState.value.selectedFiles.map { file ->
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

            updateUiState(uiState.value.copy(
                currentChat = updatedChat,
                selectedFiles = emptyList(),
                chatHistory = updatedChatHistory,
                showReplyButton = multiModeEnabled || uiState.value.showReplyButton
            ))

            // If multi-message mode is enabled, stop here and wait for user to press "reply"
            if (multiModeEnabled) {
                return@launch
            }

            // Send API request and handle response (single-message mode)
            val currentProvider = uiState.value.currentProvider
            val currentModel = uiState.value.currentModel
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
                        requestId,
                        chatForRequest.chat_id,
                        currentUser,
                        currentProvider,
                        currentModel,
                        chatForRequest.messages,
                        systemPrompt,
                        uiState.value.webSearchEnabled,
                        projectAttachments,
                        getEnabledToolSpecifications(),
                        uiState.value.thinkingBudgetValue,
                        uiState.value.temperatureValue
                    )

                    // Reload chat to get any updated file IDs from re-uploads
                    val refreshedChatHistory = repository.loadChatHistory(currentUser)
                    val refreshedCurrentChat = refreshedChatHistory.chat_history.find {
                        it.chat_id == chatForRequest.chat_id
                    } ?: chatForRequest

                    updateUiState(uiState.value.copy(
                        currentChat = refreshedCurrentChat,
                        chatHistory = refreshedChatHistory.chat_history
                    ))

                } catch (e: Exception) {
                    // Handle unexpected errors
                    Log.e("MessageSendingManager", "Error starting streaming request", e)

                    // Clear loading state for this chat
                    updateUiState(uiState.value.copy(
                        loadingChatIds = uiState.value.loadingChatIds - chatId,
                        streamingChatIds = uiState.value.streamingChatIds - chatId,
                        streamingTextByChat = uiState.value.streamingTextByChat - chatId,
                        snackbarMessage = "Error: ${e.message}"
                    ))
                }
            } else {
                // No provider selected, clear loading state
                updateUiState(uiState.value.copy(
                    loadingChatIds = uiState.value.loadingChatIds - chatId,
                    streamingChatIds = uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = uiState.value.streamingTextByChat - chatId
                ))
            }
        }
    }

    /**
     * Send buffered batch of messages.
     * Used in multi-message mode when user clicks "reply" button.
     */
    fun sendBufferedBatch() {
        val currentUser = appSettings.value.current_user
        val currentChat = uiState.value.currentChat ?: return
        val chatId = currentChat.chat_id

        scope.launch {
            // Use per-chat loading state
            updateUiState(uiState.value.copy(
                loadingChatIds = uiState.value.loadingChatIds + chatId,
                streamingChatIds = uiState.value.streamingChatIds + chatId,
                streamingTextByChat = uiState.value.streamingTextByChat + (chatId to ""),
                showReplyButton = false
            ))

            val currentProvider = uiState.value.currentProvider
            val currentModel = uiState.value.currentModel
            val systemPrompt = getEffectiveSystemPrompt()

            if (currentProvider != null && currentModel.isNotEmpty()) {
                try {
                    // Get project attachments if this chat belongs to a project
                    val projectAttachments = getCurrentChatProjectGroup()?.group_attachments ?: emptyList()

                    // Generate unique request ID for this API call
                    val requestId = UUID.randomUUID().toString()

                    // Start streaming request via the foreground service
                    startStreamingRequest(
                        requestId,
                        chatId,
                        currentUser,
                        currentProvider,
                        currentModel,
                        currentChat.messages,
                        systemPrompt,
                        uiState.value.webSearchEnabled,
                        projectAttachments,
                        getEnabledToolSpecifications(),
                        uiState.value.thinkingBudgetValue,
                        uiState.value.temperatureValue
                    )

                    // Optionally refresh chat after potential file re-uploads
                    val refreshed = repository.loadChatHistory(currentUser)
                    val refreshedCurrentChat = refreshed.chat_history.find { it.chat_id == chatId } ?: currentChat
                    updateUiState(uiState.value.copy(
                        currentChat = refreshedCurrentChat,
                        chatHistory = refreshed.chat_history
                    ))
                } catch (e: Exception) {
                    Log.e("MessageSendingManager", "Error starting buffered batch request", e)
                    updateUiState(uiState.value.copy(
                        loadingChatIds = uiState.value.loadingChatIds - chatId,
                        streamingChatIds = uiState.value.streamingChatIds - chatId,
                        streamingTextByChat = uiState.value.streamingTextByChat - chatId,
                        snackbarMessage = "Error: ${e.message}"
                    ))
                }
            } else {
                updateUiState(uiState.value.copy(
                    loadingChatIds = uiState.value.loadingChatIds - chatId,
                    streamingChatIds = uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = uiState.value.streamingTextByChat - chatId
                ))
            }
        }
    }

    /**
     * Send API request for the current branch of a chat using the streaming service.
     * This is called by MessageEditingManager when creating new branches or resending.
     */
    fun sendApiRequestForCurrentBranch(chat: Chat) {
        val currentUser = appSettings.value.current_user
        val currentProvider = uiState.value.currentProvider
        val currentModel = uiState.value.currentModel
        val systemPrompt = getEffectiveSystemPrompt()
        val chatId = chat.chat_id

        if (currentProvider == null || currentModel.isEmpty()) {
            return
        }

        // Use per-chat loading state
        updateUiState(uiState.value.copy(
            loadingChatIds = uiState.value.loadingChatIds + chatId,
            streamingChatIds = uiState.value.streamingChatIds + chatId,
            streamingTextByChat = uiState.value.streamingTextByChat + (chatId to "")
        ))

        scope.launch {
            try {
                val projectAttachments = getCurrentChatProjectGroup()?.group_attachments ?: emptyList()

                // Generate unique request ID for this API call
                val requestId = UUID.randomUUID().toString()

                // Start streaming request via the foreground service
                startStreamingRequest(
                    requestId,
                    chatId,
                    currentUser,
                    currentProvider,
                    currentModel,
                    chat.messages,
                    systemPrompt,
                    uiState.value.webSearchEnabled,
                    projectAttachments,
                    getEnabledToolSpecifications(),
                    uiState.value.thinkingBudgetValue,
                    uiState.value.temperatureValue
                )
            } catch (e: Exception) {
                Log.e("MessageSendingManager", "Error starting branch request", e)
                updateUiState(uiState.value.copy(
                    loadingChatIds = uiState.value.loadingChatIds - chatId,
                    streamingChatIds = uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = uiState.value.streamingTextByChat - chatId,
                    snackbarMessage = "Error: ${e.message}"
                ))
            }
        }
    }
}
