package com.example.ApI.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ApI.data.model.*
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.util.JsonConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

enum class DesktopPane {
    Chat,
    ApiKeys,
    Settings,
    Logs
}

data class PendingDesktopFile(
    val file: File,
    val mimeType: String
)

data class DesktopChatState(
    val settings: AppSettings = AppSettings("default", "openai", "gpt-4o"),
    val providers: List<Provider> = emptyList(),
    val apiKeys: List<ApiKey> = emptyList(),
    val chats: List<Chat> = emptyList(),
    val groups: List<ChatGroup> = emptyList(),
    val currentChat: Chat? = null,
    val currentProvider: Provider? = null,
    val currentModel: String = "gpt-4o",
    val currentMessage: String = "",
    val systemPromptDraft: String = "",
    val selectedFiles: List<PendingDesktopFile> = emptyList(),
    val streamingText: String = "",
    val streamingThoughts: String = "",
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
    val pane: DesktopPane = DesktopPane.Chat,
    val showSystemPromptDialog: Boolean = false,
    val showApiKeyDialog: Boolean = false,
    val showModelDialog: Boolean = false,
    val showExportDialog: Boolean = false,
    val exportJson: String = "",
    val searchQuery: String = "",
    val textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    val webSearchEnabled: Boolean = false,
    val thinkingBudgetValue: ThinkingBudgetValue = ThinkingBudgetValue.None,
    val temperatureValue: Float? = null,
    val excludedToolIds: List<String> = emptyList()
) {
    val filteredChats: List<Chat>
        get() {
            val query = searchQuery.trim()
            if (query.isEmpty()) return chats
            return chats.filter { chat ->
                chat.preview_name.contains(query, ignoreCase = true) ||
                    visibleMessages(chat).any { it.text.contains(query, ignoreCase = true) }
            }
        }
}

fun visibleMessages(chat: Chat): List<Message> {
    if (!chat.hasBranchingStructure) return chat.messages
    val path = if (chat.currentVariantPath.isNotEmpty()) {
        chat.currentVariantPath
    } else {
        buildDefaultVariantPath(chat.messageNodes)
    }
    return buildMessagesFromVariantPath(chat.messageNodes, path)
}

private fun buildDefaultVariantPath(nodes: List<MessageNode>): List<String> {
    if (nodes.isEmpty()) return emptyList()
    val path = mutableListOf<String>()
    var current = nodes.find { it.parentNodeId == null } ?: nodes.firstOrNull()
    while (current != null && current.variants.isNotEmpty()) {
        val variant = current.variants.first()
        path += variant.variantId
        current = variant.childNodeId?.let { childId -> nodes.find { it.nodeId == childId } }
    }
    return path
}

private fun buildMessagesFromVariantPath(nodes: List<MessageNode>, path: List<String>): List<Message> {
    val messages = mutableListOf<Message>()
    path.forEach { variantId ->
        val variant = nodes.asSequence()
            .flatMap { it.variants.asSequence() }
            .find { it.variantId == variantId }
        if (variant != null) {
            messages += variant.userMessage
            messages += variant.responses
        }
    }
    return messages
}

class DesktopChatViewModel(
    private val repository: DesktopRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    var state by mutableStateOf(DesktopChatState())
        private set

    init {
        reload()
        scope.launch {
            repository.refreshModelsIfNeeded()
            reload()
        }
    }

    fun reload() {
        val settings = repository.loadAppSettings()
        repository.initializeCustomProviders(settings.current_user)
        repository.initializeFullCustomProviders(settings.current_user)

        val providers = repository.loadProviders()
        val history = repository.loadChatHistory(settings.current_user)
        val provider = providers.find { it.provider == settings.selected_provider } ?: providers.firstOrNull()
        val model = provider?.models?.firstOrNull { it.name == settings.selected_model }?.name
            ?: provider?.models?.firstOrNull()?.name
            ?: settings.selected_model
        val currentChat = state.currentChat?.let { selected ->
            history.chat_history.find { it.chat_id == selected.chat_id }
        } ?: history.chat_history.lastOrNull()

        state = state.copy(
            settings = settings,
            providers = providers,
            apiKeys = repository.loadApiKeys(settings.current_user),
            chats = history.chat_history,
            groups = history.groups,
            currentChat = currentChat,
            currentProvider = provider,
            currentModel = model,
            systemPromptDraft = currentChat?.systemPrompt.orEmpty(),
            thinkingBudgetValue = settings.selected_provider.let {
                ThinkingBudgetConfig.getDefaultValue(settings.selected_provider, model)
            },
            temperatureValue = settings.temperature.toFloat().takeIf { it >= 0f },
            excludedToolIds = settings.excludedToolIds
        )
    }

    fun setPane(pane: DesktopPane) {
        state = state.copy(pane = pane)
    }

    fun updateMessage(message: String) {
        state = state.copy(currentMessage = message)
    }

    fun updateSearch(query: String) {
        state = state.copy(searchQuery = query)
    }

    fun clearSnackbar() {
        state = state.copy(snackbarMessage = null, errorMessage = null)
    }

    fun selectChat(chat: Chat) {
        state = state.copy(
            currentChat = chat,
            pane = DesktopPane.Chat,
            systemPromptDraft = chat.systemPrompt,
            streamingText = "",
            streamingThoughts = "",
            errorMessage = null
        )
    }

    fun newChat() {
        val chat = repository.createNewChat(
            username = state.settings.current_user,
            previewName = "שיחה חדשה",
            systemPrompt = ""
        )
        reload()
        selectChat(chat)
    }

    fun deleteCurrentChat() {
        val chat = state.currentChat ?: return
        val history = repository.loadChatHistory(state.settings.current_user)
        repository.saveChatHistory(
            history.copy(chat_history = history.chat_history.filterNot { it.chat_id == chat.chat_id })
        )
        reload()
    }

    fun selectProvider(provider: Provider) {
        val model = provider.models.firstOrNull()?.name ?: state.currentModel
        val settings = state.settings.copy(
            selected_provider = provider.provider,
            selected_model = model
        )
        repository.saveAppSettings(settings)
        state = state.copy(
            settings = settings,
            currentProvider = provider,
            currentModel = model,
            showModelDialog = false,
            thinkingBudgetValue = ThinkingBudgetConfig.getDefaultValue(provider.provider, model),
            webSearchEnabled = provider.models.firstOrNull { it.name == model }?.webSearch == "required"
        )
    }

    fun selectModel(modelName: String) {
        val provider = state.currentProvider ?: return
        val settings = state.settings.copy(selected_model = modelName)
        repository.saveAppSettings(settings)
        state = state.copy(
            settings = settings,
            currentModel = modelName,
            showModelDialog = false,
            thinkingBudgetValue = ThinkingBudgetConfig.getDefaultValue(
                provider.provider,
                modelName,
                provider.models.find { it.name == modelName }?.thinkingConfig
            ),
            webSearchEnabled = provider.models.find { it.name == modelName }?.webSearch == "required"
        )
    }

    fun setTextDirection(mode: TextDirectionMode) {
        state = state.copy(textDirectionMode = mode)
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        state = state.copy(webSearchEnabled = enabled)
    }

    fun setTemperature(value: Float?) {
        val settings = state.settings.copy(temperature = (value ?: -1f).toDouble())
        repository.saveAppSettings(settings)
        state = state.copy(settings = settings, temperatureValue = value)
    }

    fun setThinkingBudget(value: ThinkingBudgetValue) {
        state = state.copy(thinkingBudgetValue = value)
    }

    fun addFiles(files: List<File>) {
        val next = files.filter { it.isFile }.map {
            PendingDesktopFile(it, detectMimeType(it))
        }
        state = state.copy(selectedFiles = state.selectedFiles + next)
    }

    fun removeFile(file: PendingDesktopFile) {
        state = state.copy(selectedFiles = state.selectedFiles - file)
    }

    fun showSystemPromptDialog() {
        state = state.copy(
            showSystemPromptDialog = true,
            systemPromptDraft = state.currentChat?.systemPrompt.orEmpty()
        )
    }

    fun updateSystemPromptDraft(value: String) {
        state = state.copy(systemPromptDraft = value)
    }

    fun saveSystemPrompt() {
        val chat = state.currentChat ?: return
        val updated = repository.updateChatSystemPrompt(
            username = state.settings.current_user,
            chatId = chat.chat_id,
            systemPrompt = state.systemPromptDraft
        )
        reload()
        state = state.copy(
            currentChat = updated ?: state.currentChat,
            showSystemPromptDialog = false,
            snackbarMessage = "System prompt saved"
        )
    }

    fun closeSystemPromptDialog() {
        state = state.copy(showSystemPromptDialog = false)
    }

    fun showApiKeyDialog() {
        state = state.copy(showApiKeyDialog = true)
    }

    fun hideApiKeyDialog() {
        state = state.copy(showApiKeyDialog = false)
    }

    fun addApiKey(provider: String, key: String, customName: String?) {
        if (provider.isBlank() || key.isBlank()) return
        repository.addApiKey(
            state.settings.current_user,
            ApiKey(provider = provider.trim(), key = key.trim(), customName = customName?.trim()?.takeIf { it.isNotBlank() })
        )
        reload()
        state = state.copy(showApiKeyDialog = false, pane = DesktopPane.ApiKeys)
    }

    fun toggleApiKey(keyId: String) {
        repository.toggleApiKeyStatus(state.settings.current_user, keyId)
        reload()
    }

    fun deleteApiKey(keyId: String) {
        repository.deleteApiKey(state.settings.current_user, keyId)
        reload()
    }

    fun updateUsername(username: String) {
        val cleaned = username.trim().ifBlank { "default" }
        val settings = state.settings.copy(current_user = cleaned)
        repository.saveAppSettings(settings)
        reload()
    }

    fun toggleTitleGeneration(enabled: Boolean) {
        val settings = state.settings.copy(
            titleGenerationSettings = state.settings.titleGenerationSettings.copy(enabled = enabled)
        )
        repository.saveAppSettings(settings)
        state = state.copy(settings = settings)
    }

    fun toggleMultiMessage(enabled: Boolean) {
        val settings = state.settings.copy(multiMessageMode = enabled)
        repository.saveAppSettings(settings)
        state = state.copy(settings = settings)
    }

    fun showModelDialog() {
        state = state.copy(showModelDialog = true)
    }

    fun hideModelDialog() {
        state = state.copy(showModelDialog = false)
    }

    fun exportCurrentChat() {
        val chat = state.currentChat ?: return
        val json = repository.getChatJson(state.settings.current_user, chat.chat_id).orEmpty()
        state = state.copy(showExportDialog = true, exportJson = json)
    }

    fun saveCurrentChatToDownloads() {
        val chat = state.currentChat ?: return
        val json = repository.getChatJson(state.settings.current_user, chat.chat_id) ?: return
        val path = repository.saveChatJsonToDownloads(chat.chat_id, json)
        state = state.copy(snackbarMessage = path?.let { "Exported to $it" } ?: "Export failed")
    }

    fun closeExportDialog() {
        state = state.copy(showExportDialog = false)
    }

    fun importChat(file: File) {
        if (!file.isFile) return
        val importedChatId = repository.importSingleChat(file.readText(), state.settings.current_user)
        reload()
        val imported = state.chats.find { it.chat_id == importedChatId }
        if (imported != null) selectChat(imported)
        state = state.copy(snackbarMessage = if (importedChatId != null) "Chat imported" else "Import failed")
    }

    fun sendMessage() {
        if (state.isStreaming) return
        val provider = state.currentProvider ?: return
        val text = state.currentMessage.trim()
        if (text.isBlank() && state.selectedFiles.isEmpty()) return

        scope.launch {
            val username = state.settings.current_user
            val chat = state.currentChat ?: repository.createNewChat(
                username = username,
                previewName = text.take(36).ifBlank { "שיחה חדשה" },
                systemPrompt = state.systemPromptDraft
            )

            val attachments = withContext(Dispatchers.IO) {
                state.selectedFiles.mapNotNull { pending ->
                    val savedPath = repository.saveFileLocally(pending.file.name, pending.file.readBytes())
                    savedPath?.let {
                        Attachment(
                            local_file_path = it,
                            file_name = pending.file.name,
                            mime_type = pending.mimeType
                        )
                    }
                }
            }

            val userMessage = Message(
                role = "user",
                text = text,
                attachments = attachments,
                datetime = Instant.now().toString()
            )
            val updatedChat = repository.addMessageToChat(username, chat.chat_id, userMessage) ?: chat
            reload()
            state = state.copy(
                currentChat = updatedChat,
                currentMessage = "",
                selectedFiles = emptyList(),
                isStreaming = true,
                streamingText = "",
                streamingThoughts = "",
                errorMessage = null
            )

            val requestMessages = visibleMessages(updatedChat)
            val toolRegistry = ToolRegistry.getInstance()
            val enabledTools = toolRegistry.getToolSpecifications(provider.provider, state.excludedToolIds)
            var fullText = ""
            var responseError: String? = null
            var finalThoughts: String? = null
            var finalThoughtStatus = ThoughtsStatus.NONE
            var finalThinkingDuration: Float? = null

            val callback = object : StreamingCallback {
                override fun onPartialResponse(text: String) {
                    fullText += text
                    scope.launch {
                        state = state.copy(streamingText = state.streamingText + text)
                    }
                }

                override fun onComplete(responseText: String) {
                    fullText = responseText
                    this@DesktopChatViewModel.scope.launch {
                        state = state.copy(streamingText = responseText)
                    }
                }

                override fun onError(error: String) {
                    responseError = error
                    scope.launch {
                        state = state.copy(errorMessage = error)
                    }
                }

                override suspend fun onToolCall(toolCall: ToolCall, precedingText: String): ToolExecutionResult {
                    val tool = toolRegistry.getTool(toolCall.toolId)
                    return if (tool != null) {
                        try {
                            tool.execute(toolCall.parameters)
                        } catch (e: Exception) {
                            ToolExecutionResult.Error(e.message ?: "Tool failed")
                        }
                    } else {
                        ToolExecutionResult.Error("Tool not available on desktop: ${toolCall.toolId}")
                    }
                }

                override suspend fun onSaveToolMessages(
                    toolCallMessage: Message,
                    toolResponseMessage: Message,
                    precedingText: String
                ) {
                    if (precedingText.isNotBlank()) {
                        repository.addMessageToChat(
                            username,
                            chat.chat_id,
                            Message(
                                role = "assistant",
                                text = precedingText,
                                model = state.currentModel,
                                datetime = Instant.now().toString()
                            )
                        )
                    }
                    repository.addMessageToChat(username, chat.chat_id, toolCallMessage)
                    repository.addMessageToChat(username, chat.chat_id, toolResponseMessage)
                    scope.launch { reload() }
                }

                override fun onThinkingStarted() {
                    scope.launch { state = state.copy(streamingThoughts = "") }
                }

                override fun onThinkingPartial(text: String) {
                    scope.launch { state = state.copy(streamingThoughts = state.streamingThoughts + text) }
                }

                override fun onThinkingComplete(thoughts: String?, durationSeconds: Float, status: ThoughtsStatus) {
                    finalThoughts = thoughts
                    finalThinkingDuration = durationSeconds
                    finalThoughtStatus = status
                }
            }

            withContext(Dispatchers.IO) {
                repository.sendMessage(
                    provider = provider,
                    modelName = state.currentModel,
                    messages = requestMessages,
                    systemPrompt = updatedChat.systemPrompt,
                    username = username,
                    chatId = chat.chat_id,
                    webSearchEnabled = state.webSearchEnabled,
                    enabledTools = enabledTools,
                    thinkingBudget = state.thinkingBudgetValue,
                    temperature = state.temperatureValue,
                    callback = callback
                )
            }

            if (responseError == null && fullText.isNotBlank()) {
                val assistant = Message(
                    role = "assistant",
                    text = fullText,
                    model = state.currentModel,
                    datetime = Instant.now().toString(),
                    thoughts = finalThoughts,
                    thinkingDurationSeconds = finalThinkingDuration,
                    thoughtsStatus = finalThoughtStatus
                )
                repository.addMessageToChat(username, chat.chat_id, assistant)
                reload()
                val saved = state.chats.find { it.chat_id == chat.chat_id }
                if (saved != null) {
                    state = state.copy(currentChat = saved)
                    maybeGenerateTitle(saved)
                }
            }

            state = state.copy(isStreaming = false, streamingText = "", streamingThoughts = "")
        }
    }

    fun stopStreaming() {
        state = state.copy(isStreaming = false, streamingText = "")
    }

    private fun maybeGenerateTitle(chat: Chat) {
        val settings = state.settings.titleGenerationSettings
        if (!settings.enabled) return
        if (chat.preview_name != "שיחה חדשה" && chat.preview_name.isNotBlank()) return
        scope.launch {
            val provider = settings.provider.takeIf { it != "auto" }
            val title = withContext(Dispatchers.IO) {
                repository.generateConversationTitle(state.settings.current_user, chat.chat_id, provider)
            }
            if (title.isNotBlank()) {
                renameChat(chat.chat_id, title)
            }
        }
    }

    fun renameChat(chatId: String, title: String) {
        val history = repository.loadChatHistory(state.settings.current_user)
        val updated = history.copy(
            chat_history = history.chat_history.map { chat ->
                if (chat.chat_id == chatId) chat.copy(preview_name = title.trim()) else chat
            }
        )
        repository.saveChatHistory(updated)
        reload()
    }

    fun dispose() {
        scope.launch { }
    }
}

private fun detectMimeType(file: File): String {
    return when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "md", "kt", "java", "js", "ts", "py", "json", "xml", "html", "css", "csv" -> "text/plain"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }
}
