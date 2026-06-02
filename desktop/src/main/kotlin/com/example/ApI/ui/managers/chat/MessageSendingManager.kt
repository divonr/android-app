package com.example.ApI.ui.managers.chat

import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages all message sending operations and API communication.
 */
class MessageSendingManager(
    private val deps: ManagerDependencies,
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

    fun sendMessage() {
        val message = deps.uiState.value.currentMessage.trim()
        val hasFiles = deps.uiState.value.selectedFiles.isNotEmpty()
        if (message.isEmpty() && !hasFiles) return

        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: createNewChat(
            when {
                message.length > 30 -> "${message.take(30)}..."
                message.isNotEmpty() -> message
                hasFiles -> deps.uiState.value.selectedFiles.firstOrNull()?.name ?: "Attached file"
                else -> "New chat"
            }
        )

        val chatId = currentChat.chat_id

        deps.scope.launch {
            val multiModeEnabled = deps.appSettings.value.multiMessageMode
            if (!multiModeEnabled) {
                deps.updateUiState(deps.uiState.value.copy(
                    loadingChatIds = deps.uiState.value.loadingChatIds + chatId,
                    streamingChatIds = deps.uiState.value.streamingChatIds + chatId,
                    streamingTextByChat = deps.uiState.value.streamingTextByChat + (chatId to ""),
                    currentMessage = ""
                ))
            } else {
                deps.updateUiState(deps.uiState.value.copy(currentMessage = ""))
            }

            val userMessage = Message(
                role = "user",
                text = if (message.isNotEmpty()) message else "[Attached file]",
                attachments = deps.uiState.value.selectedFiles.map { file ->
                    Attachment(
                        local_file_path = file.localPath,
                        file_name = file.name,
                        mime_type = file.mimeType
                    )
                },
                model = null,
                datetime = getCurrentDateTimeISO()
            )

            var updatedChat = deps.repository.addUserMessageAsNewNode(currentUser, currentChat.chat_id, userMessage)
            val updatedChatHistory = deps.repository.loadChatHistory(currentUser).chat_history

            deps.updateUiState(deps.uiState.value.copy(
                currentChat = updatedChat,
                selectedFiles = emptyList(),
                chatHistory = updatedChatHistory,
                showReplyButton = multiModeEnabled || deps.uiState.value.showReplyButton
            ))

            if (multiModeEnabled) return@launch

            val currentProvider = deps.uiState.value.currentProvider
            val currentModel = deps.uiState.value.currentModel
            val systemPrompt = getEffectiveSystemPrompt()

            if (currentProvider != null && currentModel.isNotEmpty()) {
                try {
                    var finalUserMessage = userMessage
                    if (userMessage.attachments.isNotEmpty()) {
                        val uploadedAttachments = mutableListOf<Attachment>()
                        for (attachment in userMessage.attachments) {
                            attachment.local_file_path?.let { localPath ->
                                val uploadedAttachment = deps.repository.uploadFile(
                                    provider = currentProvider,
                                    filePath = localPath,
                                    fileName = attachment.file_name,
                                    mimeType = attachment.mime_type,
                                    username = currentUser
                                )
                                uploadedAttachments.add(uploadedAttachment ?: attachment)
                            }
                        }
                        finalUserMessage = userMessage.copy(attachments = uploadedAttachments)
                        val allMessages = updatedChat!!.messages.dropLast(1) + finalUserMessage
                        val updatedChatWithFiles = updatedChat.copy(messages = allMessages)
                        val chatHistory = deps.repository.loadChatHistory(currentUser)
                        val updatedHistoryChats = chatHistory.chat_history.map { chat ->
                            if (chat.chat_id == updatedChatWithFiles.chat_id) updatedChatWithFiles else chat
                        }
                        deps.repository.saveChatHistory(chatHistory.copy(chat_history = updatedHistoryChats))
                        updatedChat = updatedChatWithFiles
                    }

                    val projectAttachments = getCurrentChatProjectGroup()?.group_attachments ?: emptyList()
                    val chatForRequest = updatedChat!!
                    val requestId = UUID.randomUUID().toString()

                    startStreamingRequest(
                        requestId, chatForRequest.chat_id, currentUser, currentProvider, currentModel,
                        chatForRequest.messages, systemPrompt, deps.uiState.value.webSearchEnabled,
                        projectAttachments, getEnabledToolSpecifications(),
                        deps.uiState.value.thinkingBudgetValue, deps.uiState.value.temperatureValue
                    )

                    val refreshedChatHistory = deps.repository.loadChatHistory(currentUser)
                    val refreshedCurrentChat = refreshedChatHistory.chat_history.find { it.chat_id == chatForRequest.chat_id } ?: chatForRequest
                    deps.updateUiState(deps.uiState.value.copy(currentChat = refreshedCurrentChat, chatHistory = refreshedChatHistory.chat_history))

                } catch (e: Exception) {
                    Log.e("MessageSendingManager", "Error starting streaming request", e)
                    deps.updateUiState(deps.uiState.value.copy(
                        loadingChatIds = deps.uiState.value.loadingChatIds - chatId,
                        streamingChatIds = deps.uiState.value.streamingChatIds - chatId,
                        streamingTextByChat = deps.uiState.value.streamingTextByChat - chatId,
                        snackbarMessage = "Error: ${e.message}"
                    ))
                }
            } else {
                deps.updateUiState(deps.uiState.value.copy(
                    loadingChatIds = deps.uiState.value.loadingChatIds - chatId,
                    streamingChatIds = deps.uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = deps.uiState.value.streamingTextByChat - chatId
                ))
            }
        }
    }

    fun sendBufferedBatch() {
        val currentUser = deps.appSettings.value.current_user
        val currentChat = deps.uiState.value.currentChat ?: return
        val chatId = currentChat.chat_id

        deps.scope.launch {
            deps.updateUiState(deps.uiState.value.copy(
                loadingChatIds = deps.uiState.value.loadingChatIds + chatId,
                streamingChatIds = deps.uiState.value.streamingChatIds + chatId,
                streamingTextByChat = deps.uiState.value.streamingTextByChat + (chatId to ""),
                showReplyButton = false
            ))

            val currentProvider = deps.uiState.value.currentProvider
            val currentModel = deps.uiState.value.currentModel
            val systemPrompt = getEffectiveSystemPrompt()

            if (currentProvider != null && currentModel.isNotEmpty()) {
                try {
                    val projectAttachments = getCurrentChatProjectGroup()?.group_attachments ?: emptyList()
                    val requestId = UUID.randomUUID().toString()

                    startStreamingRequest(
                        requestId, chatId, currentUser, currentProvider, currentModel,
                        currentChat.messages, systemPrompt, deps.uiState.value.webSearchEnabled,
                        projectAttachments, getEnabledToolSpecifications(),
                        deps.uiState.value.thinkingBudgetValue, deps.uiState.value.temperatureValue
                    )

                    val refreshed = deps.repository.loadChatHistory(currentUser)
                    val refreshedCurrentChat = refreshed.chat_history.find { it.chat_id == chatId } ?: currentChat
                    deps.updateUiState(deps.uiState.value.copy(currentChat = refreshedCurrentChat, chatHistory = refreshed.chat_history))
                } catch (e: Exception) {
                    Log.e("MessageSendingManager", "Error starting buffered batch request", e)
                    deps.updateUiState(deps.uiState.value.copy(
                        loadingChatIds = deps.uiState.value.loadingChatIds - chatId,
                        streamingChatIds = deps.uiState.value.streamingChatIds - chatId,
                        streamingTextByChat = deps.uiState.value.streamingTextByChat - chatId,
                        snackbarMessage = "Error: ${e.message}"
                    ))
                }
            } else {
                deps.updateUiState(deps.uiState.value.copy(
                    loadingChatIds = deps.uiState.value.loadingChatIds - chatId,
                    streamingChatIds = deps.uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = deps.uiState.value.streamingTextByChat - chatId
                ))
            }
        }
    }

    fun sendApiRequestForCurrentBranch(chat: Chat) {
        val currentUser = deps.appSettings.value.current_user
        val currentProvider = deps.uiState.value.currentProvider
        val currentModel = deps.uiState.value.currentModel
        val systemPrompt = getEffectiveSystemPrompt()
        val chatId = chat.chat_id

        if (currentProvider == null || currentModel.isEmpty()) return

        deps.updateUiState(deps.uiState.value.copy(
            loadingChatIds = deps.uiState.value.loadingChatIds + chatId,
            streamingChatIds = deps.uiState.value.streamingChatIds + chatId,
            streamingTextByChat = deps.uiState.value.streamingTextByChat + (chatId to "")
        ))

        deps.scope.launch {
            try {
                val projectAttachments = getCurrentChatProjectGroup()?.group_attachments ?: emptyList()
                val requestId = UUID.randomUUID().toString()

                startStreamingRequest(
                    requestId, chatId, currentUser, currentProvider, currentModel,
                    chat.messages, systemPrompt, deps.uiState.value.webSearchEnabled,
                    projectAttachments, getEnabledToolSpecifications(),
                    deps.uiState.value.thinkingBudgetValue, deps.uiState.value.temperatureValue
                )
            } catch (e: Exception) {
                Log.e("MessageSendingManager", "Error starting branch request", e)
                deps.updateUiState(deps.uiState.value.copy(
                    loadingChatIds = deps.uiState.value.loadingChatIds - chatId,
                    streamingChatIds = deps.uiState.value.streamingChatIds - chatId,
                    streamingTextByChat = deps.uiState.value.streamingTextByChat - chatId,
                    snackbarMessage = "Error: ${e.message}"
                ))
            }
        }
    }
}
