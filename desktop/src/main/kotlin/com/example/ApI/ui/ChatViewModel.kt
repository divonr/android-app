package com.example.ApI.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.desktop.DesktopContext
import com.example.ApI.desktop.DesktopRepository
import com.example.ApI.desktop.DesktopStreamingCoordinator
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.ui.managers.ManagerDependencies
import com.example.ApI.ui.managers.chat.*
import com.example.ApI.ui.managers.integration.*
import com.example.ApI.ui.managers.io.*
import com.example.ApI.ui.managers.organization.*
import com.example.ApI.ui.managers.provider.*
import com.example.ApI.ui.managers.settings.*
import com.example.ApI.ui.managers.streaming.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant

/**
 * Desktop ChatViewModel - faithful port of Android ChatViewModel.
 * Differences from Android:
 * - No ViewModel base class (no lifecycle binding)
 * - CoroutineScope instead of viewModelScope
 * - DesktopStreamingCoordinator instead of StreamingService
 * - addFileFromPath instead of addFileFromUri
 * - Google Sign-In is a stub (not available on desktop)
 */
class ChatViewModel(
    val repository: DesktopRepository,
    val context: Context
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _currentScreen = MutableStateFlow<Screen>(Screen.ChatHistory)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _appSettings = MutableStateFlow(AppSettings("default", "openai", "gpt-4o"))
    val appSettings: StateFlow<AppSettings> = _appSettings.asStateFlow()

    private val streamingCoordinator = DesktopStreamingCoordinator(repository)

    private val managerDeps by lazy {
        ManagerDependencies(
            repository = repository,
            context = context,
            scope = scope,
            appSettings = _appSettings,
            uiState = _uiState,
            updateUiState = { newState -> _uiState.value = newState }
        )
    }

    private val groupManager: GroupManager by lazy {
        GroupManager(deps = managerDeps, navigateToScreen = { screen -> navigateToScreen(screen) })
    }

    private val authManager: AuthManager by lazy {
        AuthManager(
            deps = managerDeps,
            updateAppSettings = { newSettings -> _appSettings.value = newSettings },
            showSnackbar = { message -> showSnackbar(message) }
        )
    }

    private val searchManager: SearchManager by lazy {
        SearchManager(deps = managerDeps, getCurrentScreen = { _currentScreen.value })
    }

    private val childLockManager: ChildLockManager by lazy {
        ChildLockManager(deps = managerDeps, updateAppSettings = { newSettings -> _appSettings.value = newSettings })
    }

    private val attachmentManager: AttachmentManager by lazy {
        AttachmentManager(deps = managerDeps)
    }

    private val exportImportManager: ExportImportManager by lazy {
        ExportImportManager(
            deps = managerDeps,
            selectChat = { chat -> selectChat(chat) },
            navigateToScreen = { screen -> navigateToScreen(screen) }
        )
    }

    private val branchingManager: BranchingManager by lazy {
        BranchingManager(deps = managerDeps)
    }

    private val topBarManager: TopBarManager by lazy {
        TopBarManager(deps = managerDeps)
    }

    private val modelSelectionManager: ModelSelectionManager by lazy {
        ModelSelectionManager(deps = managerDeps, updateAppSettings = { newSettings -> _appSettings.value = newSettings })
    }

    private val messageSendingManager: MessageSendingManager by lazy {
        MessageSendingManager(
            deps = managerDeps,
            getCurrentDateTimeISO = { getCurrentDateTimeISO() },
            getEffectiveSystemPrompt = { systemPromptManager.getEffectiveSystemPrompt() },
            getCurrentChatProjectGroup = { systemPromptManager.getCurrentChatProjectGroup() },
            getEnabledToolSpecifications = { toolManager.getEnabledToolSpecifications() },
            startStreamingRequest = { requestId, chatId, username, provider, modelName, messages, systemPrompt,
                webSearchEnabled, projectAttachments, enabledTools, thinkingBudget, temperature ->
                startStreamingRequest(requestId, chatId, username, provider, modelName, messages,
                    systemPrompt, webSearchEnabled, projectAttachments, enabledTools, thinkingBudget, temperature)
            },
            createNewChat = { previewName -> createNewChat(previewName) }
        )
    }

    private val messageEditingManager: MessageEditingManager by lazy {
        MessageEditingManager(
            deps = managerDeps,
            getCurrentDateTimeISO = { getCurrentDateTimeISO() },
            sendApiRequestForBranch = { chat -> messageSendingManager.sendApiRequestForCurrentBranch(chat) }
        )
    }

    private val toolManager: ToolManager by lazy {
        ToolManager(deps = managerDeps, updateAppSettings = { newSettings -> _appSettings.value = newSettings })
    }

    private val titleGenerationManager: TitleGenerationManager by lazy {
        TitleGenerationManager(deps = managerDeps, updateAppSettings = { newSettings -> _appSettings.value = newSettings })
    }

    private val systemPromptManager: SystemPromptManager by lazy {
        SystemPromptManager(deps = managerDeps)
    }

    private val chatContextMenuManager: ChatContextMenuManager by lazy {
        ChatContextMenuManager(
            deps = managerDeps,
            navigateToScreen = { screen -> navigateToScreen(screen) },
            updateChatPreviewName = { chatId, newTitle -> titleGenerationManager.updateChatPreviewName(chatId, newTitle) }
        )
    }

    private val sharedIntentManager: SharedIntentManager by lazy {
        SharedIntentManager(deps = managerDeps, currentScreen = _currentScreen)
    }

    private val streamingEventManager: StreamingEventManager by lazy {
        StreamingEventManager(
            deps = managerDeps,
            getCurrentDateTimeISO = { getCurrentDateTimeISO() },
            handleTitleGeneration = { chat -> titleGenerationManager.handleTitleGeneration(chat) },
            executeToolCall = { toolCall -> toolManager.executeToolCall(toolCall) },
            provideToolResult = { requestId, result -> streamingCoordinator.provideToolResult(requestId, result) }
        )
    }

    init {
        val defaultProvider = Provider(
            provider = "openai",
            models = listOf(Model.SimpleModel("gpt-4o")),
            request = ApiRequest("", "", emptyMap()),
            response_important_fields = ResponseFields()
        )
        _uiState.value = _uiState.value.copy(currentProvider = defaultProvider, currentModel = "gpt-4o")
        loadInitialData()
        sharedIntentManager.handleSharedFiles()
    }

    private fun getCurrentDateTimeISO(): String = Instant.now().toString()

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
        scope.launch {
            streamingCoordinator.startStreamingRequest(
                requestId = requestId,
                chatId = chatId,
                username = username,
                provider = provider,
                modelName = modelName,
                messages = messages,
                systemPrompt = systemPrompt,
                webSearchEnabled = webSearchEnabled,
                projectAttachments = projectAttachments,
                enabledTools = enabledTools,
                thinkingBudget = thinkingBudget,
                temperature = temperature,
                onEvent = { event -> streamingEventManager.handleStreamingEvent(event) }
            )
        }
    }

    fun cancelStreamingRequest(chatId: String) {
        // Find active request for this chat - desktop doesn't track requestIds externally
        // Just clear the streaming state
        _uiState.value = _uiState.value.copy(
            loadingChatIds = _uiState.value.loadingChatIds - chatId,
            streamingChatIds = _uiState.value.streamingChatIds - chatId,
            streamingTextByChat = _uiState.value.streamingTextByChat - chatId
        )
    }

    fun stopStreamingAndSave() {
        val currentChat = _uiState.value.currentChat ?: return
        val chatId = currentChat.chat_id
        val currentUser = _appSettings.value.current_user
        val currentModel = _uiState.value.currentModel
        val accumulatedText = _uiState.value.streamingTextByChat[chatId] ?: ""

        scope.launch {
            if (accumulatedText.isNotEmpty()) {
                val assistantMessage = Message(
                    role = "assistant",
                    text = accumulatedText,
                    attachments = emptyList(),
                    model = currentModel,
                    datetime = getCurrentDateTimeISO()
                )
                repository.addResponseToCurrentVariant(currentUser, chatId, assistantMessage)
            }

            var refreshedHistory = repository.loadChatHistory(currentUser)
            var refreshedChat = refreshedHistory.chat_history.find { it.chat_id == chatId }

            _uiState.value = _uiState.value.copy(
                loadingChatIds = _uiState.value.loadingChatIds - chatId,
                streamingChatIds = _uiState.value.streamingChatIds - chatId,
                streamingTextByChat = _uiState.value.streamingTextByChat - chatId,
                chatHistory = refreshedHistory.chat_history,
                currentChat = refreshedChat ?: currentChat
            )

            if (refreshedChat != null && accumulatedText.isNotEmpty()) {
                titleGenerationManager.handleTitleGeneration(refreshedChat)
            }
        }
    }

    fun onCleared() {
        scope.cancel()
    }

    private fun loadInitialData() {
        scope.launch {
            val settings = repository.loadAppSettings()
            _appSettings.value = settings

            repository.initializeCustomProviders(settings.current_user)
            repository.initializeFullCustomProviders(settings.current_user)
            repository.refreshModelsIfNeeded()

            val allProviders = repository.loadProviders()
            val activeApiKeyProviders = repository.loadApiKeys(settings.current_user)
                .filter { it.isActive }.map { it.provider }
            val providers = allProviders.filter { activeApiKeyProviders.contains(it.provider) }

            val currentProvider = providers.find { it.provider == settings.selected_provider } ?: providers.firstOrNull()
            val currentModel = if (settings.selected_model.isEmpty()) {
                currentProvider?.models?.firstOrNull()?.name ?: "gpt-4o"
            } else settings.selected_model

            var chatHistory = repository.loadChatHistory(settings.current_user)
            val currentChat = if (chatHistory.chat_history.isNotEmpty()) chatHistory.chat_history.last() else null

            val webSearchSupport = modelSelectionManager.getWebSearchSupport(currentProvider?.provider ?: "", currentModel)
            val webSearchEnabled = webSearchSupport == WebSearchSupport.REQUIRED

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

            if (currentModel != settings.selected_model || (currentProvider?.provider != settings.selected_provider)) {
                val updatedSettings = settings.copy(
                    selected_provider = currentProvider?.provider ?: "openai",
                    selected_model = currentModel
                )
                repository.saveAppSettings(updatedSettings)
                _appSettings.value = updatedSettings
            }

            if (!settings.skipWelcomeScreen) {
                _currentScreen.value = Screen.Welcome
            }

            initializeGitHubToolsIfConnected()
            initializeGoogleWorkspaceToolsIfConnected()
            initializeSkillTools()

            // Start remote sync (no-op if disabled in settings)
            repository.startSync()
        }
        // Start observing sync change ticks so pulled files cause a UI reload
        observeSyncChangeTick()
    }

    private fun initializeSkillTools() {
        val toolRegistry = ToolRegistry.getInstance()
        toolRegistry.registerSkillTools(repository.skillsStorageManager)

        val hasSkills = repository.getInstalledSkills().any { it.isEnabled }
        if (hasSkills) {
            val settings = _appSettings.value
            val skillToolIds = toolRegistry.getSkillToolIds()
            val currentEnabled = settings.enabledTools.toMutableList()
            var changed = false
            for (toolId in skillToolIds) {
                if (toolId !in currentEnabled) { currentEnabled.add(toolId); changed = true }
            }
            if (changed) {
                val updatedSettings = settings.copy(enabledTools = currentEnabled)
                repository.saveAppSettings(updatedSettings)
                _appSettings.value = updatedSettings
            }
        }
    }

    fun updateMessage(message: String) {
        _uiState.value = _uiState.value.copy(currentMessage = message)
    }

    // ==================== Message Sending ====================
    fun sendMessage() = messageSendingManager.sendMessage()
    fun sendBufferedBatch() = messageSendingManager.sendBufferedBatch()

    // ==================== Message Editing ====================
    fun deleteMessage(message: Message) = messageEditingManager.deleteMessage(message)
    fun startEditingMessage(message: Message) = messageEditingManager.startEditingMessage(message)
    fun finishEditingMessage() = messageEditingManager.finishEditingMessage()
    fun confirmEditAndResend() = messageEditingManager.confirmEditAndResend()
    fun cancelEditingMessage() = messageEditingManager.cancelEditingMessage()
    fun resendFromMessage(message: Message) = messageEditingManager.resendFromMessage(message)

    // ==================== Chat Selection & Management ====================
    fun selectChat(chat: Chat) {
        scope.launch {
            _uiState.value = _uiState.value.copy(currentChat = chat, systemPrompt = chat.systemPrompt)
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
        _uiState.value = _uiState.value.copy(currentChat = newChat, chatHistory = updatedChatHistory)
        navigateToScreen(Screen.Chat)
        return newChat
    }

    fun createNewChatInGroup(groupId: String) {
        val currentUser = _appSettings.value.current_user
        val newChat = repository.createNewChatInGroup(currentUser, "New chat", groupId, "")
        val updatedChatHistory = repository.loadChatHistory(currentUser).chat_history
        _uiState.value = _uiState.value.copy(
            currentChat = newChat, chatHistory = updatedChatHistory,
            groups = repository.loadChatHistory(currentUser).groups
        )
        navigateToScreen(Screen.Chat)
    }

    // ==================== Provider/Model Selection ====================
    fun selectProvider(provider: Provider) = modelSelectionManager.selectProvider(provider)
    fun selectModel(modelName: String) = modelSelectionManager.selectModel(modelName)
    fun selectModelWithProvider(provider: Provider, modelName: String) = modelSelectionManager.selectModelWithProvider(provider, modelName)
    fun showModelSelector() = modelSelectionManager.showModelSelector()
    fun hideModelSelector() = modelSelectionManager.hideModelSelector()
    fun refreshModels() = modelSelectionManager.refreshModels()
    fun refreshAvailableProviders() = modelSelectionManager.refreshAvailableProviders()
    fun toggleStarredModel(providerKey: String, modelName: String) = modelSelectionManager.toggleStarredModel(providerKey, modelName)

    fun selectModelByName(modelName: String) {
        val availableProviders = _uiState.value.availableProviders
        val routerProviders = setOf("poe", "openrouter")
        val matchingProviders = mutableListOf<Pair<Provider, String>>()
        for (provider in availableProviders) {
            val matchingModel = provider.models.find { it.name == modelName }
            if (matchingModel != null) matchingProviders.add(provider to matchingModel.name!!)
        }
        if (matchingProviders.isEmpty()) return
        val selectedProvider = matchingProviders.firstOrNull { (p, _) -> !routerProviders.contains(p.provider.lowercase()) }?.first ?: matchingProviders.first().first
        val selectedModelName = matchingProviders.first { (p, _) -> p == selectedProvider }.second
        selectProvider(selectedProvider)
        selectModel(selectedModelName)
    }

    // ==================== System Prompt Management ====================
    fun updateSystemPrompt(prompt: String) = systemPromptManager.updateSystemPrompt(prompt)
    fun showSystemPromptDialog() = systemPromptManager.showSystemPromptDialog()
    fun hideSystemPromptDialog() = systemPromptManager.hideSystemPromptDialog()
    fun toggleSystemPromptOverride() = systemPromptManager.toggleSystemPromptOverride()
    fun setSystemPromptOverride(enabled: Boolean) = systemPromptManager.setSystemPromptOverride(enabled)
    fun getCurrentChatProjectGroup(): ChatGroup? = systemPromptManager.getCurrentChatProjectGroup()
    fun getEffectiveSystemPrompt(): String = systemPromptManager.getEffectiveSystemPrompt()

    // ==================== Snackbar ====================
    fun showSnackbar(message: String) { _uiState.value = _uiState.value.copy(snackbarMessage = message) }
    fun clearSnackbar() { _uiState.value = _uiState.value.copy(snackbarMessage = null) }

    // ==================== Empty Chat Cleanup ====================
    fun cleanupEmptyChatsOnScreen() {
        scope.launch {
            val currentUser = _appSettings.value.current_user
            val updatedHistory = repository.loadChatHistory(currentUser)
            _uiState.value = _uiState.value.copy(chatHistory = updatedHistory.chat_history, groups = updatedHistory.groups)
        }
    }

    // ==================== File/Attachment Management ====================
    /** Desktop: use addFileFromPath instead of addFileFromUri. */
    fun addFileFromPath(file: File, mimeType: String) = attachmentManager.addFileFromPath(file, mimeType)
    fun addMultipleFiles(filesList: List<Pair<File, String>>) = attachmentManager.addMultipleFiles(filesList)
    fun removeSelectedFile(file: SelectedFile) = attachmentManager.removeSelectedFile(file)
    fun showFileSelection() = attachmentManager.showFileSelection()
    fun hideFileSelection() = attachmentManager.hideFileSelection()
    /** Android compatibility shim - uri.path is used as file path on desktop. */
    fun addFileFromUri(uri: Uri?, fileName: String, mimeType: String) {
        uri?.path?.let { path -> addFileFromPath(File(path), mimeType) }
    }

    /** Android compatibility: addMultipleFilesFromUris -> addMultipleFiles via Uri.path */
    fun addMultipleFilesFromUris(filesList: List<Triple<Uri?, String, String>>) {
        addMultipleFiles(filesList.mapNotNull { (uri, _, mime) ->
            uri?.path?.let { path -> File(path) to mime }
        })
    }

    // ==================== Settings ====================
    fun updateMultiMessageMode(enabled: Boolean) {
        val updatedSettings = _appSettings.value.copy(multiMessageMode = enabled)
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
    }

    // ==================== Remote Sync ====================

    /** Observe syncChangeTick; reload data whenever a pull overwrites local files. */
    private fun observeSyncChangeTick() {
        scope.launch {
            repository.syncChangeTick.collect { tick ->
                if (tick > 0L) {
                    val currentUser = _appSettings.value.current_user
                    val updatedHistory = repository.loadChatHistory(currentUser)
                    val currentChatId = _uiState.value.currentChat?.chat_id
                    val refreshedCurrentChat = if (currentChatId != null) {
                        updatedHistory.chat_history.find { it.chat_id == currentChatId }
                    } else null
                    _uiState.value = _uiState.value.copy(
                        chatHistory = updatedHistory.chat_history,
                        groups = updatedHistory.groups,
                        currentChat = refreshedCurrentChat ?: _uiState.value.currentChat
                    )
                    val updatedSettings = repository.loadAppSettings()
                    _appSettings.value = updatedSettings
                }
            }
        }
    }

    /** Call on window focus to pull latest changes from the server. */
    fun onWindowFocused() {
        repository.pullNow()
    }

    /** Update the "Enable remote sync" toggle. Persists and starts sync if turned on. */
    fun updateRemoteSyncEnabled(enabled: Boolean) {
        val updatedSettings = _appSettings.value.copy(
            remoteSync = _appSettings.value.remoteSync.copy(enabled = enabled)
        )
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
        if (enabled) repository.startSync()
    }

    /** Update the remote sync server URL. */
    fun updateRemoteSyncServerUrl(url: String) {
        val updatedSettings = _appSettings.value.copy(
            remoteSync = _appSettings.value.remoteSync.copy(serverBaseUrl = url)
        )
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
    }

    /** Update the remote sync auth token. */
    fun updateRemoteSyncAuthToken(token: String) {
        val updatedSettings = _appSettings.value.copy(
            remoteSync = _appSettings.value.remoteSync.copy(authToken = token)
        )
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
    }

    /** Update the "Also sync API keys" toggle. */
    fun updateRemoteSyncApiKeys(syncApiKeys: Boolean) {
        val updatedSettings = _appSettings.value.copy(
            remoteSync = _appSettings.value.remoteSync.copy(syncApiKeys = syncApiKeys)
        )
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
    }

    /** Trigger an immediate pull from the server. */
    fun triggerSyncNow() {
        repository.pullNow()
    }

    /** Test the sync connection. Returns true on success, false on failure. */
    suspend fun testSyncConnection(): Boolean = repository.testSyncConnection()

    // ==================== Navigation ====================
    fun navigateToScreen(screen: Screen) {
        if (_currentScreen.value == Screen.ApiKeys && screen != Screen.ApiKeys) refreshAvailableProviders()
        _currentScreen.value = screen
    }

    fun updateSkipWelcomeScreen(skip: Boolean) {
        val updatedSettings = _appSettings.value.copy(skipWelcomeScreen = skip)
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
    }

    // ==================== Full Chat History Export/Import ====================
    fun exportChatHistory() = exportImportManager.exportChatHistory()
    fun importChatHistoryFromFile(file: File) = exportImportManager.importChatHistoryFromFile(file)
    fun importChatHistoryFromUri(uri: Uri?) {
        uri?.path?.let { path -> importChatHistoryFromFile(File(path)) }
    }

    fun selectAndExportChat(chat: Chat) {
        scope.launch {
            _uiState.value = _uiState.value.copy(currentChat = chat, systemPrompt = chat.systemPrompt)
            exportImportManager.openChatExportDialog()
        }
    }

    // Text direction settings
    fun toggleTextDirection() = topBarManager.toggleTextDirection()
    fun setTextDirectionMode(mode: TextDirectionMode) = topBarManager.setTextDirectionMode(mode)

    // ==================== Title Generation ====================
    fun updateTitleGenerationSettings(newSettings: TitleGenerationSettings) = titleGenerationManager.updateTitleGenerationSettings(newSettings)
    fun getAvailableProvidersForTitleGeneration(): List<String> = titleGenerationManager.getAvailableProvidersForTitleGeneration()
    fun renameChatWithAI(chat: Chat) { chatContextMenuManager.hideChatContextMenu(); titleGenerationManager.renameChatWithAI(chat) }

    // ==================== Chat Context Menu ====================
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

    // ==================== Group Management ====================
    fun createNewGroup(groupName: String) = groupManager.createNewGroup(groupName)
    fun addChatToGroup(chatId: String, groupId: String) = groupManager.addChatToGroup(chatId, groupId)
    fun removeChatFromGroup(chatId: String) = groupManager.removeChatFromGroup(chatId)
    fun toggleGroupExpansion(groupId: String) = groupManager.toggleGroupExpansion(groupId)
    fun showGroupDialog(chat: Chat? = null) = groupManager.showGroupDialog(chat)
    fun hideGroupDialog() = groupManager.hideGroupDialog()
    fun refreshChatHistoryAndGroups() = groupManager.refreshChatHistoryAndGroups()
    fun toggleGroupProjectStatus(groupId: String) = groupManager.toggleGroupProjectStatus(groupId)
    fun addFileToProject(groupId: String, file: File, mimeType: String) = groupManager.addFileToProject(groupId, file, mimeType)
    fun addFileToProjectFromUri(groupId: String, uri: Uri?, fileName: String, mimeType: String) {
        uri?.path?.let { path -> addFileToProject(groupId, File(path), mimeType) }
    }
    fun openProjectInstructionsDialog() { _uiState.value = _uiState.value.copy(showSystemPromptDialog = true) }
    fun removeFileFromProject(groupId: String, attachmentIndex: Int) = groupManager.removeFileFromProject(groupId, attachmentIndex)
    fun navigateToGroup(groupId: String) = groupManager.navigateToGroup(groupId)
    fun updateGroupSystemPrompt(systemPrompt: String) = groupManager.updateGroupSystemPrompt(systemPrompt)
    fun showGroupContextMenu(group: ChatGroup, position: androidx.compose.ui.unit.DpOffset) = groupManager.showGroupContextMenu(group, position)
    fun hideGroupContextMenu() = groupManager.hideGroupContextMenu()
    fun showGroupRenameDialog(group: ChatGroup) = groupManager.showGroupRenameDialog(group)
    fun hideGroupRenameDialog() = groupManager.hideGroupRenameDialog()
    fun renameGroup(group: ChatGroup, newName: String) = groupManager.renameGroup(group, newName)
    fun makeGroupProject(group: ChatGroup) = groupManager.makeGroupProject(group)
    fun createNewConversationInGroup(group: ChatGroup) = groupManager.createNewConversationInGroup(group)
    fun showGroupDeleteConfirmation(group: ChatGroup) = groupManager.showGroupDeleteConfirmation(group)
    fun hideGroupDeleteConfirmation() = groupManager.hideGroupDeleteConfirmation()
    fun deleteGroup(group: ChatGroup) {
        groupManager.deleteGroup(group)
        if (_currentScreen.value is Screen.Group && (_currentScreen.value as Screen.Group).groupId == group.group_id) {
            navigateToScreen(Screen.ChatHistory)
        }
    }

    // ==================== Quick Settings & Chat Export ====================
    fun toggleQuickSettings() { _uiState.value = _uiState.value.copy(quickSettingsExpanded = !_uiState.value.quickSettingsExpanded) }

    // ==================== Tool Toggle ====================
    fun toggleToolDropdown() { _uiState.value = _uiState.value.copy(showToolToggleDropdown = !_uiState.value.showToolToggleDropdown) }
    fun dismissToolDropdown() { _uiState.value = _uiState.value.copy(showToolToggleDropdown = false) }
    fun toggleToolExclusion(toolId: String, exclude: Boolean) {
        val currentExcluded = _appSettings.value.excludedToolIds
        val updatedExcluded = if (exclude) {
            if (toolId !in currentExcluded) currentExcluded + toolId else currentExcluded
        } else currentExcluded - toolId
        val updatedSettings = _appSettings.value.copy(excludedToolIds = updatedExcluded)
        repository.saveAppSettings(updatedSettings)
        _appSettings.value = updatedSettings
        _uiState.value = _uiState.value.copy(excludedToolIds = updatedExcluded)
    }

    fun getEnabledToolIds(): List<String> {
        val tools = _appSettings.value.enabledTools.toMutableList()
        if (_uiState.value.currentChat?.group != null) {
            if (!tools.contains("get_current_group_conversations")) tools.add("get_current_group_conversations")
        }
        return tools
    }

    // ==================== Thinking Budget ====================
    fun onThinkingBudgetButtonClick() = topBarManager.onThinkingBudgetButtonClick()
    fun showThinkingBudgetPopup() = topBarManager.showThinkingBudgetPopup()
    fun hideThinkingBudgetPopup() = topBarManager.hideThinkingBudgetPopup()
    fun setThinkingBudgetValue(value: ThinkingBudgetValue) = topBarManager.setThinkingBudgetValue(value)
    fun setThinkingEffort(level: String) = topBarManager.setThinkingEffort(level)
    fun setThinkingTokenBudget(tokens: Int) = topBarManager.setThinkingTokenBudget(tokens)
    fun resetThinkingBudgetToDefault() = topBarManager.resetThinkingBudgetToDefault()

    // ==================== Temperature ====================
    fun onTemperatureButtonClick() = topBarManager.onTemperatureButtonClick()
    fun hideTemperaturePopup() = topBarManager.hideTemperaturePopup()
    fun setTemperatureValue(value: Float?) = topBarManager.setTemperatureValue(value)
    fun resetTemperatureToDefault() = topBarManager.resetTemperatureToDefault()

    // ==================== Search ====================
    fun enterSearchMode() = searchManager.enterSearchMode()
    fun enterConversationSearchMode() = searchManager.enterConversationSearchMode()
    fun enterSearchModeWithQuery(query: String) = searchManager.enterSearchModeWithQuery(query)
    fun exitSearchMode() = searchManager.exitSearchMode()
    fun updateSearchQuery(query: String) = searchManager.updateSearchQuery(query)
    fun performSearch() = searchManager.performSearch()
    fun performConversationSearch() = searchManager.performConversationSearch()
    fun clearSearchContext() = searchManager.clearSearchContext()

    // ==================== Child Lock ====================
    fun setupChildLock(password: String, startTime: String, endTime: String, deviceId: String) = childLockManager.setupChildLock(password, startTime, endTime, deviceId)
    fun verifyAndDisableChildLock(password: String, deviceId: String): Boolean = childLockManager.verifyAndDisableChildLock(password, deviceId)
    fun updateChildLockSettings(enabled: Boolean, password: String, startTime: String, endTime: String) = childLockManager.updateChildLockSettings(enabled, password, startTime, endTime)
    fun isChildLockActive(): Boolean = childLockManager.isChildLockActive()
    fun getLockEndTime(): String = childLockManager.getLockEndTime()

    // ==================== Integration Management ====================
    fun connectGitHub(): String = authManager.connectGitHub()
    fun getGitHubAuthUrl(): Pair<String, String> = authManager.getGitHubAuthUrl()
    fun openGitHubAuthInBrowser() = authManager.openGitHubAuthInBrowser()
    fun handleGitHubCallback(code: String, state: String): String = authManager.handleGitHubCallback(code, state)
    fun disconnectGitHub() = authManager.disconnectGitHub()
    fun isGitHubConnected(): Boolean = authManager.isGitHubConnected()
    fun getGitHubConnection(): GitHubConnection? = authManager.getGitHubConnection()
    fun initializeGitHubToolsIfConnected() = authManager.initializeGitHubToolsIfConnected()
    fun isGoogleWorkspaceConnected(): Boolean = authManager.isGoogleWorkspaceConnected()
    fun getGoogleWorkspaceConnection(): GoogleWorkspaceConnection? = authManager.getGoogleWorkspaceConnection()
    fun connectGoogleWorkspace() = authManager.connectGoogleWorkspace()
    fun disconnectGoogleWorkspace() = authManager.disconnectGoogleWorkspace()
    fun updateGoogleWorkspaceServices(gmail: Boolean, calendar: Boolean, drive: Boolean) = authManager.updateGoogleWorkspaceServices(gmail, calendar, drive)
    fun initializeGoogleWorkspaceToolsIfConnected() = authManager.initializeGoogleWorkspaceToolsIfConnected()

    // ==================== Tool Management ====================
    fun enableTool(toolId: String) = toolManager.enableTool(toolId)
    fun disableTool(toolId: String) = toolManager.disableTool(toolId)

    // ==================== Skills ====================
    fun getInstalledSkills() = repository.getInstalledSkills()
    fun createSkill(name: String, description: String, body: String = "") = repository.createSkill(name, description, body)
    fun deleteSkill(skillName: String): Boolean {
        val result = repository.deleteSkill(skillName)
        if (result && repository.getInstalledSkills().none { it.isEnabled }) {
            val toolRegistry = ToolRegistry.getInstance()
            for (toolId in toolRegistry.getSkillToolIds()) toolManager.disableTool(toolId)
        }
        return result
    }
    fun setSkillEnabled(skillName: String, enabled: Boolean) {
        repository.setSkillEnabled(skillName, enabled)
        val hasEnabledSkills = repository.getInstalledSkills().any { it.isEnabled }
        val toolRegistry = ToolRegistry.getInstance()
        if (hasEnabledSkills) {
            for (toolId in toolRegistry.getSkillToolIds()) toolManager.enableTool(toolId)
        } else {
            for (toolId in toolRegistry.getSkillToolIds()) toolManager.disableTool(toolId)
        }
    }
    fun getSkillMdContent(skillName: String) = repository.getSkillMdContent(skillName)
    fun importSkillFromText(content: String): InstalledSkill? {
        val skill = repository.importSkillFromText(content)
        if (skill != null) { val toolRegistry = ToolRegistry.getInstance(); for (toolId in toolRegistry.getSkillToolIds()) toolManager.enableTool(toolId) }
        return skill
    }
    fun importSkillFromZip(inputStream: java.util.zip.ZipInputStream): InstalledSkill? {
        val skill = repository.skillsStorageManager.importFromZip(inputStream)
        if (skill != null) { val toolRegistry = ToolRegistry.getInstance(); for (toolId in toolRegistry.getSkillToolIds()) toolManager.enableTool(toolId) }
        return skill
    }
    fun getSkillsStorageManager() = repository.skillsStorageManager

    // ==================== Export/Import Methods ====================
    fun openChatExportDialog() = exportImportManager.openChatExportDialog()
    fun closeChatExportDialog() = exportImportManager.closeChatExportDialog()
    fun enableChatExportEditing() = exportImportManager.enableChatExportEditing()
    fun updateChatExportContent(content: String) = exportImportManager.updateChatExportContent(content)
    fun shareChatExportContent() = exportImportManager.shareChatExportContent()
    fun saveChatExportToDownloads() = exportImportManager.saveChatExportToDownloads()
    fun importPendingChatJson() = exportImportManager.importPendingChatJson()
    fun attachPendingJsonAsFile() = exportImportManager.attachPendingJsonAsFile()
    fun dismissChatImportDialog() = exportImportManager.dismissChatImportDialog()
    fun createShareLink() = exportImportManager.createShareLink()
    fun deleteShareLink() = exportImportManager.deleteShareLink()
    fun updateShareLink() = exportImportManager.updateShareLink()
    fun copyShareLink() = exportImportManager.copyShareLink()
    fun toggleShareLinkMenu() = exportImportManager.toggleShareLinkMenu()
    fun dismissShareLinkMenu() = exportImportManager.dismissShareLinkMenu()

    // ==================== Branching ====================
    fun getBranchInfoForMessage(message: Message): BranchInfo? = branchingManager.getBranchInfoForMessage(message)
    fun navigateToNextVariant(nodeId: String) = branchingManager.navigateToNextVariant(nodeId)
    fun navigateToPreviousVariant(nodeId: String) = branchingManager.navigateToPreviousVariant(nodeId)
    fun navigateToVariant(nodeId: String, variantIndex: Int) = branchingManager.navigateToVariant(nodeId, variantIndex)
    fun ensureBranchingStructure() = branchingManager.ensureBranchingStructure()
}
