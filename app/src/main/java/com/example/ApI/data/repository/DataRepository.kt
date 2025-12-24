package com.example.ApI.data.repository

import android.content.Context
import com.example.ApI.data.model.*
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.network.LLMApiService
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

class DataRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val apiService = LLMApiService(context)

    private val internalDir = File(context.filesDir, "llm_data")

    // Managers
    private val modelsCacheManager = ModelsCacheManager(internalDir, json)
    private val localStorageManager = LocalStorageManager(internalDir, json)
    private val chatHistoryManager = ChatHistoryManager(internalDir, json)
    private val groupProjectManager = GroupProjectManager(chatHistoryManager)
    private val externalConnectionsManager = ExternalConnectionsManager(internalDir, json, localStorageManager)
    private val fileUploadManager = FileUploadManager(json) { username -> localStorageManager.loadApiKeys(username) }
    private val chatSearchService = ChatSearchService { username -> loadChatHistory(username) }
    private val titleGenerationService by lazy {
        TitleGenerationService(
            json = json,
            apiService = apiService,
            loadChatHistory = { username -> loadChatHistory(username) },
            loadProviders = { loadProviders() },
            loadApiKeys = { username -> localStorageManager.loadApiKeys(username) }
        )
    }

    init {
        if (!internalDir.exists()) {
            internalDir.mkdirs()
        }
    }

    // ============ Models Cache (delegated to ModelsCacheManager) ============

    suspend fun refreshModelsIfNeeded(): Boolean = modelsCacheManager.refreshModelsIfNeeded()

    suspend fun forceRefreshModels(): Pair<Boolean, String?> = modelsCacheManager.forceRefreshModels()

    fun loadProviders(): List<Provider> = modelsCacheManager.buildProviders()

    // ============ Chat History (delegated to ChatHistoryManager) ============

    fun loadChatHistory(username: String): UserChatHistory = chatHistoryManager.loadChatHistory(username)
    fun saveChatHistory(chatHistory: UserChatHistory) = chatHistoryManager.saveChatHistory(chatHistory)
    fun getChatJson(username: String, chatId: String): String? = chatHistoryManager.getChatJson(username, chatId)
    fun saveChatJsonToDownloads(chatId: String, content: String): String? = chatHistoryManager.saveChatJsonToDownloads(chatId, content)
    fun addMessageToChat(username: String, chatId: String, message: Message): Chat? = chatHistoryManager.addMessageToChat(username, chatId, message)
    fun createNewChat(username: String, previewName: String, systemPrompt: String = ""): Chat = chatHistoryManager.createNewChat(username, previewName, systemPrompt)
    fun createNewChatInGroup(username: String, previewName: String, groupId: String, systemPrompt: String = ""): Chat = chatHistoryManager.createNewChatInGroup(username, previewName, groupId, systemPrompt)
    fun updateChatSystemPrompt(username: String, chatId: String, systemPrompt: String): Chat? = chatHistoryManager.updateChatSystemPrompt(username, chatId, systemPrompt)

    // ============ Group Management (delegated to GroupProjectManager) ============

    fun createNewGroup(username: String, groupName: String): ChatGroup = groupProjectManager.createNewGroup(username, groupName)
    fun addChatToGroup(username: String, chatId: String, groupId: String): Boolean = groupProjectManager.addChatToGroup(username, chatId, groupId)
    fun removeChatFromGroup(username: String, chatId: String): Boolean = groupProjectManager.removeChatFromGroup(username, chatId)
    fun deleteGroup(username: String, groupId: String): Boolean = groupProjectManager.deleteGroup(username, groupId)
    fun renameGroup(username: String, groupId: String, newName: String): Boolean = groupProjectManager.renameGroup(username, groupId, newName)
    fun updateGroupProjectStatus(username: String, groupId: String, isProject: Boolean): Boolean = groupProjectManager.updateGroupProjectStatus(username, groupId, isProject)
    fun addAttachmentToGroup(username: String, groupId: String, attachment: Attachment): Boolean = groupProjectManager.addAttachmentToGroup(username, groupId, attachment)
    fun removeAttachmentFromGroup(username: String, groupId: String, attachmentIndex: Int): Boolean = groupProjectManager.removeAttachmentFromGroup(username, groupId, attachmentIndex)
    
    // ============ Local Storage (delegated to LocalStorageManager) ============

    fun loadApiKeys(username: String): List<ApiKey> = localStorageManager.loadApiKeys(username)
    fun saveApiKeys(username: String, apiKeys: List<ApiKey>) = localStorageManager.saveApiKeys(username, apiKeys)
    fun addApiKey(username: String, apiKey: ApiKey) = localStorageManager.addApiKey(username, apiKey)
    fun toggleApiKeyStatus(username: String, keyId: String) = localStorageManager.toggleApiKeyStatus(username, keyId)
    fun deleteApiKey(username: String, keyId: String) = localStorageManager.deleteApiKey(username, keyId)
    fun reorderApiKeys(username: String, fromIndex: Int, toIndex: Int) = localStorageManager.reorderApiKeys(username, fromIndex, toIndex)
    fun loadAppSettings(): AppSettings = localStorageManager.loadAppSettings()
    fun saveAppSettings(settings: AppSettings) = localStorageManager.saveAppSettings(settings)
    fun saveFileLocally(fileName: String, data: ByteArray): String? = localStorageManager.saveFileLocally(fileName, data)
    fun deleteFile(filePath: String): Boolean = localStorageManager.deleteFile(filePath)
    
    fun replaceMessageInChat(username: String, chatId: String, oldMessage: Message, newMessage: Message): Chat? = chatHistoryManager.replaceMessageInChat(username, chatId, oldMessage, newMessage)
    fun deleteMessagesFromPoint(username: String, chatId: String, fromMessage: Message): Chat? = chatHistoryManager.deleteMessagesFromPoint(username, chatId, fromMessage)
    
    // API Communication
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

        // Check and re-upload files if needed when provider has changed
        val (updatedMessages, hasUpdates) = ensureFilesUploadedForProvider(provider, messages, username)

        // If files were re-uploaded, update the chat history
        if (hasUpdates && chatId != null) {
            updateChatWithNewAttachments(username, chatId, updatedMessages)
        }

        // Handle project files - upload them for current provider if needed
        val updatedProjectAttachments = ensureProjectFilesUploadedForProvider(provider, projectAttachments, username)

        // Create final messages list with project files attached to a user message if needed
        val finalMessages = if (updatedProjectAttachments.isNotEmpty()) {
            // Create a user message with project attachments at the beginning
            val projectFilesMessage = Message(
                role = "user",
                text = "General files belonging to the project of which this conversation is a part are attached:",
                attachments = updatedProjectAttachments
            )
            listOf(projectFilesMessage) + updatedMessages
        } else {
            updatedMessages
        }

        apiService.sendMessage(provider, modelName, finalMessages, systemPrompt, apiKeys, webSearchEnabled, enabledTools, thinkingBudget, temperature, callback)
    }

    // ============ File Upload (delegated to FileUploadManager) ============

    private suspend fun ensureProjectFilesUploadedForProvider(
        provider: Provider,
        projectAttachments: List<Attachment>,
        username: String
    ): List<Attachment> = fileUploadManager.ensureProjectFilesUploadedForProvider(provider, projectAttachments, username)

    private suspend fun ensureFilesUploadedForProvider(
        provider: Provider,
        messages: List<Message>,
        username: String
    ): Pair<List<Message>, Boolean> = fileUploadManager.ensureFilesUploadedForProvider(provider, messages, username)
    
    fun updateChatWithNewAttachments(username: String, chatId: String, updatedMessages: List<Message>) = chatHistoryManager.updateChatWithNewAttachments(username, chatId, updatedMessages)
    fun exportChatHistory(username: String): String? = chatHistoryManager.exportChatHistory(username)
    fun importChatHistoryJson(raw: ByteArray, targetUsername: String) = chatHistoryManager.importChatHistoryJson(raw, targetUsername)
    fun validateChatJson(jsonContent: String): Boolean = chatHistoryManager.validateChatJson(jsonContent)
    fun importSingleChat(jsonContent: String, targetUsername: String): String? = chatHistoryManager.importSingleChat(jsonContent, targetUsername)
    
    suspend fun uploadFile(
        provider: Provider,
        filePath: String,
        fileName: String,
        mimeType: String,
        username: String
    ): Attachment? = fileUploadManager.uploadFile(provider, filePath, fileName, mimeType, username)
    
    // ============ Title Generation & Search (delegated) ============

    suspend fun generateConversationTitle(
        username: String,
        conversationId: String,
        provider: String? = null
    ): String = titleGenerationService.generateConversationTitle(username, conversationId, provider)

    fun searchChats(username: String, query: String): List<com.example.ApI.data.model.SearchResult> =
        chatSearchService.searchChats(username, query)

    // ============ External Connections (delegated to ExternalConnectionsManager) ============

    fun loadGitHubConnection(username: String): GitHubConnection? = externalConnectionsManager.loadGitHubConnection(username)
    fun saveGitHubConnection(username: String, connection: GitHubConnection) = externalConnectionsManager.saveGitHubConnection(username, connection)
    fun removeGitHubConnection(username: String) = externalConnectionsManager.removeGitHubConnection(username)
    fun isGitHubConnected(username: String): Boolean = externalConnectionsManager.isGitHubConnected(username)
    fun getGitHubApiService(username: String): Pair<com.example.ApI.data.network.GitHubApiService, String>? = externalConnectionsManager.getGitHubApiService(username)
    fun updateGitHubLastUsed(username: String) = externalConnectionsManager.updateGitHubLastUsed(username)

    fun loadGoogleWorkspaceConnection(username: String): com.example.ApI.data.model.GoogleWorkspaceConnection? = externalConnectionsManager.loadGoogleWorkspaceConnection(username)
    fun saveGoogleWorkspaceConnection(username: String, connection: com.example.ApI.data.model.GoogleWorkspaceConnection) = externalConnectionsManager.saveGoogleWorkspaceConnection(username, connection)
    fun removeGoogleWorkspaceConnection(username: String) = externalConnectionsManager.removeGoogleWorkspaceConnection(username)
    fun isGoogleWorkspaceConnected(username: String): Boolean = externalConnectionsManager.isGoogleWorkspaceConnected(username)
    fun updateGoogleWorkspaceEnabledServices(username: String, services: com.example.ApI.data.model.EnabledGoogleServices) = externalConnectionsManager.updateGoogleWorkspaceEnabledServices(username, services)
    fun getGoogleWorkspaceApiServices(username: String): Triple<com.example.ApI.data.network.GmailApiService?, com.example.ApI.data.network.GoogleCalendarApiService?, com.example.ApI.data.network.GoogleDriveApiService?>? = externalConnectionsManager.getGoogleWorkspaceApiServices(username)
    fun updateGoogleWorkspaceLastUsed(username: String) = externalConnectionsManager.updateGoogleWorkspaceLastUsed(username)

    // ==================== Branching System ====================

    /**
     * Migrate a chat from linear structure to branching structure.
     * Each user message becomes a node with a single variant.
     */
    fun migrateChatToBranchingStructure(chat: Chat): Chat {
        if (chat.hasBranchingStructure) return chat
        if (chat.messages.isEmpty()) return chat

        val nodes = mutableListOf<MessageNode>()
        val variantPath = mutableListOf<String>()
        var currentNodeId: String? = null
        var currentVariant: MessageVariant? = null
        var pendingResponses = mutableListOf<Message>()

        for (message in chat.messages) {
            when (message.role) {
                "user" -> {
                    // Save previous variant if exists
                    if (currentVariant != null && currentNodeId != null) {
                        val newNodeId = UUID.randomUUID().toString()
                        val updatedVariant = currentVariant.copy(
                            responses = pendingResponses.toList(),
                            childNodeId = newNodeId
                        )
                        val node = nodes.find { it.nodeId == currentNodeId }
                        if (node != null) {
                            val nodeIndex = nodes.indexOf(node)
                            nodes[nodeIndex] = node.copy(
                                variants = node.variants.map {
                                    if (it.variantId == updatedVariant.variantId) updatedVariant else it
                                }
                            )
                        }
                        currentNodeId = newNodeId
                    } else {
                        currentNodeId = UUID.randomUUID().toString()
                    }

                    // Create new variant for this user message
                    val variantId = UUID.randomUUID().toString()
                    val userMessageWithRefs = message.copy(
                        id = if (message.id.isBlank()) UUID.randomUUID().toString() else message.id,
                        nodeId = currentNodeId,
                        variantId = variantId
                    )
                    currentVariant = MessageVariant(
                        variantId = variantId,
                        userMessage = userMessageWithRefs,
                        responses = emptyList()
                    )
                    variantPath.add(variantId)
                    pendingResponses = mutableListOf()

                    // Create or update node
                    val existingNode = nodes.find { it.nodeId == currentNodeId }
                    if (existingNode != null) {
                        val nodeIndex = nodes.indexOf(existingNode)
                        nodes[nodeIndex] = existingNode.copy(
                            variants = existingNode.variants + currentVariant
                        )
                    } else {
                        val parentNodeId = if (nodes.isEmpty()) null else nodes.lastOrNull()?.nodeId
                        nodes.add(MessageNode(
                            nodeId = currentNodeId,
                            parentNodeId = parentNodeId,
                            variants = listOf(currentVariant)
                        ))
                    }
                }
                "assistant", "tool_call", "tool_response", "system" -> {
                    // Add to pending responses
                    if (currentNodeId != null && currentVariant != null) {
                        val responseWithRefs = message.copy(
                            id = if (message.id.isBlank()) UUID.randomUUID().toString() else message.id,
                            nodeId = currentNodeId,
                            variantId = currentVariant.variantId
                        )
                        pendingResponses.add(responseWithRefs)
                    }
                }
            }
        }

        // Save final variant's responses
        if (currentVariant != null && currentNodeId != null && pendingResponses.isNotEmpty()) {
            val updatedVariant = currentVariant.copy(responses = pendingResponses.toList())
            val node = nodes.find { it.nodeId == currentNodeId }
            if (node != null) {
                val nodeIndex = nodes.indexOf(node)
                nodes[nodeIndex] = node.copy(
                    variants = node.variants.map {
                        if (it.variantId == updatedVariant.variantId) updatedVariant else it
                    }
                )
            }
        }

        return chat.copy(
            messageNodes = nodes,
            currentVariantPath = variantPath
        )
    }

    /**
     * Ensure chat has branching structure, migrating if necessary.
     */
    fun ensureBranchingStructure(username: String, chatId: String): Chat? {
        val chatHistory = loadChatHistory(username)
        val chat = chatHistory.chat_history.find { it.chat_id == chatId } ?: return null

        if (chat.hasBranchingStructure) return chat

        val migratedChat = migrateChatToBranchingStructure(chat)
        val updatedChats = chatHistory.chat_history.map {
            if (it.chat_id == chatId) migratedChat else it
        }
        saveChatHistory(chatHistory.copy(chat_history = updatedChats))
        return migratedChat
    }

    /**
     * Create a new branch (variant) at a specific node.
     * Used when user edits or resends a message.
     * 
     * @param username The current user
     * @param chatId The chat ID
     * @param nodeId The node where to create the branch
     * @param newUserMessage The new/edited user message
     * @return Pair of updated Chat and the new variantId, or null if failed
     */
    fun createBranch(
        username: String,
        chatId: String,
        nodeId: String,
        newUserMessage: Message
    ): Pair<Chat, String>? {
        val chat = ensureBranchingStructure(username, chatId) ?: return null

        val nodeIndex = chat.messageNodes.indexOfFirst { it.nodeId == nodeId }
        if (nodeIndex == -1) return null

        val node = chat.messageNodes[nodeIndex]
        val newVariantId = UUID.randomUUID().toString()

        // Create new variant with the new user message
        val messageWithRefs = newUserMessage.copy(
            id = if (newUserMessage.id.isBlank()) UUID.randomUUID().toString() else newUserMessage.id,
            nodeId = nodeId,
            variantId = newVariantId
        )
        val newVariant = MessageVariant(
            variantId = newVariantId,
            userMessage = messageWithRefs,
            responses = emptyList()
        )

        // Update node with new variant
        val updatedNode = node.copy(variants = node.variants + newVariant)
        val updatedNodes = chat.messageNodes.toMutableList()
        updatedNodes[nodeIndex] = updatedNode

        // Update variant path to use the new variant from this point
        val nodePositionInPath = chat.currentVariantPath.indexOfFirst { variantId ->
            node.variants.any { it.variantId == variantId }
        }
        val newPath = if (nodePositionInPath >= 0) {
            chat.currentVariantPath.take(nodePositionInPath) + newVariantId
        } else {
            chat.currentVariantPath + newVariantId
        }

        // Rebuild messages list for the new path
        val newMessages = buildMessagesFromPath(updatedNodes, newPath)

        val updatedChat = chat.copy(
            messageNodes = updatedNodes,
            currentVariantPath = newPath,
            messages = newMessages
        )

        // Save to storage
        val chatHistory = loadChatHistory(username)
        val updatedChats = chatHistory.chat_history.map {
            if (it.chat_id == chatId) updatedChat else it
        }
        saveChatHistory(chatHistory.copy(chat_history = updatedChats))

        return Pair(updatedChat, newVariantId)
    }

    /**
     * Add a response message to the current variant of a node.
     * Used when receiving assistant responses.
     */
    fun addResponseToCurrentVariant(
        username: String,
        chatId: String,
        response: Message
    ): Chat? {
        val chat = ensureBranchingStructure(username, chatId) ?: return null
        
        // Fallback if no variant path yet (shouldn't happen but just in case)
        if (chat.currentVariantPath.isEmpty()) {
            return addMessageToChat(username, chatId, response)
        }

        val currentVariantId = chat.currentVariantPath.last()
        
        // Find the node containing this variant
        var targetNodeIndex = -1
        var targetVariantIndex = -1
        
        for ((nodeIndex, node) in chat.messageNodes.withIndex()) {
            val variantIndex = node.variants.indexOfFirst { it.variantId == currentVariantId }
            if (variantIndex >= 0) {
                targetNodeIndex = nodeIndex
                targetVariantIndex = variantIndex
                break
            }
        }

        if (targetNodeIndex == -1) return null

        val node = chat.messageNodes[targetNodeIndex]
        val variant = node.variants[targetVariantIndex]

        // Add response with references
        val responseWithRefs = response.copy(
            id = if (response.id.isBlank()) UUID.randomUUID().toString() else response.id,
            nodeId = node.nodeId,
            variantId = currentVariantId
        )
        val updatedVariant = variant.copy(responses = variant.responses + responseWithRefs)
        
        // Update node
        val updatedVariants = node.variants.toMutableList()
        updatedVariants[targetVariantIndex] = updatedVariant
        val updatedNode = node.copy(variants = updatedVariants)

        // Update nodes list
        val updatedNodes = chat.messageNodes.toMutableList()
        updatedNodes[targetNodeIndex] = updatedNode

        // Rebuild messages list
        val newMessages = buildMessagesFromPath(updatedNodes, chat.currentVariantPath)

        val updatedChat = chat.copy(
            messageNodes = updatedNodes,
            messages = newMessages
        )

        // Save to storage
        val chatHistory = loadChatHistory(username)
        val updatedChats = chatHistory.chat_history.map {
            if (it.chat_id == chatId) updatedChat else it
        }
        saveChatHistory(chatHistory.copy(chat_history = updatedChats))

        return updatedChat
    }

    /**
     * Switch to a different variant at a specific node.
     * 
     * @param username The current user
     * @param chatId The chat ID
     * @param nodeId The node where to switch variants
     * @param variantIndex The index of the variant to switch to (0-based)
     * @return Updated chat, or null if failed
     */
    fun switchVariant(
        username: String,
        chatId: String,
        nodeId: String,
        variantIndex: Int
    ): Chat? {
        val chat = ensureBranchingStructure(username, chatId) ?: return null

        val node = chat.messageNodes.find { it.nodeId == nodeId } ?: return null
        val variant = node.getVariant(variantIndex) ?: return null

        // Find position in path where this node's variant is
        val pathIndex = chat.currentVariantPath.indexOfFirst { variantId ->
            node.variants.any { it.variantId == variantId }
        }

        if (pathIndex == -1) return null

        // Build new path: keep everything before this node, then add the new variant
        val newPath = chat.currentVariantPath.take(pathIndex).toMutableList()
        newPath.add(variant.variantId)

        // Follow the chain of child nodes from this variant
        var currentVariant = variant
        while (currentVariant.childNodeId != null) {
            val childNode = chat.messageNodes.find { it.nodeId == currentVariant.childNodeId }
            if (childNode != null && childNode.variants.isNotEmpty()) {
                // First, try to find a variant in the child that has further continuations
                // that match our old path
                var bestVariant: MessageVariant? = null
                
                // Check if we had a variant from this child in our old path
                val oldVariantInChild = chat.currentVariantPath.find { variantId ->
                    childNode.variants.any { it.variantId == variantId }
                }
                
                if (oldVariantInChild != null) {
                    bestVariant = childNode.getVariantById(oldVariantInChild)
                }
                
                // If no old variant found, use the first one
                val childVariant = bestVariant ?: childNode.variants.first()
                newPath.add(childVariant.variantId)
                currentVariant = childVariant
            } else {
                break
            }
        }

        // Rebuild messages list for new path
        val newMessages = buildMessagesFromPath(chat.messageNodes, newPath)

        val updatedChat = chat.copy(
            currentVariantPath = newPath,
            messages = newMessages
        )

        // Save to storage
        val chatHistory = loadChatHistory(username)
        val updatedChats = chatHistory.chat_history.map {
            if (it.chat_id == chatId) updatedChat else it
        }
        saveChatHistory(chatHistory.copy(chat_history = updatedChats))

        return updatedChat
    }

    /**
     * Get branch info for a specific node.
     * Returns information about available variants for UI display.
     */
    fun getBranchInfo(chat: Chat, nodeId: String): BranchInfo? {
        if (!chat.hasBranchingStructure) return null

        val node = chat.messageNodes.find { it.nodeId == nodeId } ?: return null
        if (node.variants.size <= 1) return null  // No branching if only one variant

        // Find current variant for this node from path
        val currentVariantId = chat.currentVariantPath.find { variantId ->
            node.variants.any { it.variantId == variantId }
        } ?: return null

        val currentIndex = node.getVariantIndex(currentVariantId)
        if (currentIndex == -1) return null

        return BranchInfo(
            nodeId = nodeId,
            currentVariantIndex = currentIndex,
            totalVariants = node.variants.size,
            currentVariantId = currentVariantId
        )
    }

    /**
     * Get branch info for a message by its ID.
     */
    fun getBranchInfoForMessage(chat: Chat, messageId: String): BranchInfo? {
        if (!chat.hasBranchingStructure) return null

        // Find the message and its node
        for (node in chat.messageNodes) {
            for (variant in node.variants) {
                if (variant.userMessage.id == messageId) {
                    return getBranchInfo(chat, node.nodeId)
                }
            }
        }
        return null
    }

    /**
     * Build a flat messages list from the branching structure following a specific path.
     */
    private fun buildMessagesFromPath(
        nodes: List<MessageNode>,
        variantPath: List<String>
    ): List<Message> {
        val messages = mutableListOf<Message>()

        for (variantId in variantPath) {
            // Find the node containing this variant
            for (node in nodes) {
                val variant = node.getVariantById(variantId)
                if (variant != null) {
                    messages.add(variant.userMessage)
                    messages.addAll(variant.responses)
                    break
                }
            }
        }

        return messages
    }

    /**
     * Create a new node and variant for a new user message.
     * Used when continuing a conversation normally.
     */
    fun addUserMessageAsNewNode(
        username: String,
        chatId: String,
        userMessage: Message
    ): Chat? {
        var chat = ensureBranchingStructure(username, chatId) ?: return null

        val newNodeId = UUID.randomUUID().toString()
        val newVariantId = UUID.randomUUID().toString()

        // If this is the first message (no nodes yet), create initial structure
        if (chat.messageNodes.isEmpty()) {
            val messageWithRefs = userMessage.copy(
                id = if (userMessage.id.isBlank()) UUID.randomUUID().toString() else userMessage.id,
                nodeId = newNodeId,
                variantId = newVariantId
            )
            
            val newVariant = MessageVariant(
                variantId = newVariantId,
                userMessage = messageWithRefs,
                responses = emptyList()
            )
            
            val newNode = MessageNode(
                nodeId = newNodeId,
                parentNodeId = null,
                variants = listOf(newVariant)
            )
            
            val updatedChat = chat.copy(
                messageNodes = listOf(newNode),
                currentVariantPath = listOf(newVariantId),
                messages = listOf(messageWithRefs)
            )
            
            // Save
            val chatHistory = loadChatHistory(username)
            val updatedChats = chatHistory.chat_history.map {
                if (it.chat_id == chatId) updatedChat else it
            }
            saveChatHistory(chatHistory.copy(chat_history = updatedChats))
            
            return updatedChat
        }

        // Find the last node in current path - we need to find where to attach the new message
        var lastVariantInPath: MessageVariant? = null
        var lastNodeInPath: MessageNode? = null
        
        if (chat.currentVariantPath.isNotEmpty()) {
            val lastVariantId = chat.currentVariantPath.last()
            
            // Find the node and variant
            for (node in chat.messageNodes) {
                val variant = node.getVariantById(lastVariantId)
                if (variant != null) {
                    lastVariantInPath = variant
                    lastNodeInPath = node
                    break
                }
            }
        }

        // If the last variant already has a child, we need to follow the chain to the actual end
        // and add the message there as a new variant (fork point)
        if (lastVariantInPath?.childNodeId != null) {
            // Follow the chain to find the actual last node
            var currentVariant = lastVariantInPath
            var actualLastNode: MessageNode? = lastNodeInPath
            var actualLastVariant: MessageVariant? = lastVariantInPath
            
            while (currentVariant?.childNodeId != null) {
                val childNode = chat.messageNodes.find { it.nodeId == currentVariant?.childNodeId }
                if (childNode != null && childNode.variants.isNotEmpty()) {
                    // Find the variant in path, or use first
                    val variantInPath = chat.currentVariantPath.find { variantId ->
                        childNode.variants.any { it.variantId == variantId }
                    }
                    val childVariant = if (variantInPath != null) {
                        childNode.getVariantById(variantInPath)
                    } else {
                        childNode.variants.first()
                    }
                    if (childVariant != null) {
                        actualLastNode = childNode
                        actualLastVariant = childVariant
                        currentVariant = childVariant
                    } else {
                        break
                    }
                } else {
                    break
                }
            }
            
            // Now actualLastVariant is the real last variant, update it
            lastVariantInPath = actualLastVariant
            lastNodeInPath = actualLastNode
        }

        // Determine parent node ID
        val parentNodeId = lastNodeInPath?.nodeId

        // Update the last variant to point to the new node (if it doesn't have a child yet)
        if (lastVariantInPath != null && lastVariantInPath.childNodeId == null) {
            val updatedNodes = chat.messageNodes.map { node ->
                val variantIndex = node.variants.indexOfFirst { it.variantId == lastVariantInPath.variantId }
                if (variantIndex >= 0) {
                    val updatedVariant = node.variants[variantIndex].copy(childNodeId = newNodeId)
                    val updatedVariants = node.variants.toMutableList()
                    updatedVariants[variantIndex] = updatedVariant
                    node.copy(variants = updatedVariants)
                } else {
                    node
                }
            }
            chat = chat.copy(messageNodes = updatedNodes)
        }

        // Create message with references
        val messageWithRefs = userMessage.copy(
            id = if (userMessage.id.isBlank()) UUID.randomUUID().toString() else userMessage.id,
            nodeId = newNodeId,
            variantId = newVariantId
        )

        // Create new variant and node
        val newVariant = MessageVariant(
            variantId = newVariantId,
            userMessage = messageWithRefs,
            responses = emptyList()
        )

        val newNode = MessageNode(
            nodeId = newNodeId,
            parentNodeId = parentNodeId,
            variants = listOf(newVariant)
        )

        // Update chat
        val updatedNodes = chat.messageNodes + newNode
        val updatedPath = chat.currentVariantPath + newVariantId
        val newMessages = buildMessagesFromPath(updatedNodes, updatedPath)

        val updatedChat = chat.copy(
            messageNodes = updatedNodes,
            currentVariantPath = updatedPath,
            messages = newMessages
        )

        // Save to storage
        val chatHistory = loadChatHistory(username)
        val updatedChats = chatHistory.chat_history.map {
            if (it.chat_id == chatId) updatedChat else it
        }
        saveChatHistory(chatHistory.copy(chat_history = updatedChats))

        return updatedChat
    }

    /**
     * Find the node ID for a given message (by matching text and role).
     * Used when user wants to edit/resend a specific message.
     */
    fun findNodeForMessage(chat: Chat, message: Message): String? {
        if (!chat.hasBranchingStructure) return null

        for (node in chat.messageNodes) {
            for (variant in node.variants) {
                // Match by ID if available, otherwise by content
                if (variant.userMessage.id == message.id ||
                    (variant.userMessage.text == message.text && 
                     variant.userMessage.role == message.role &&
                     variant.userMessage.datetime == message.datetime)) {
                    return node.nodeId
                }
            }
        }
        return null
    }

    /**
     * Delete a message from the branching structure.
     * Handles different cases:
     * - Simple message (no branching) - just delete it
     * - Branch point with no children - delete the entire branch
     * - Branch point with children - show error message
     */
    fun deleteMessageFromBranch(
        username: String,
        chatId: String,
        messageId: String
    ): DeleteMessageResult {
        val chatHistory = loadChatHistory(username)
        var chat = chatHistory.chat_history.find { it.chat_id == chatId }
            ?: return DeleteMessageResult.Error("Chat not found")

        // If no branching structure, use simple deletion
        if (!chat.hasBranchingStructure || chat.messageNodes.isEmpty()) {
            val updatedMessages = chat.messages.filter { it.id != messageId }
            val updatedChat = chat.copy(messages = updatedMessages)
            
            val updatedChats = chatHistory.chat_history.map { c ->
                if (c.chat_id == chatId) updatedChat else c
            }
            saveChatHistory(chatHistory.copy(chat_history = updatedChats))
            return DeleteMessageResult.Success(updatedChat)
        }

        // Find the message in the branching structure
        // IMPORTANT: We need to find the variant that's in currentVariantPath, not just any variant
        var targetNode: MessageNode? = null
        var targetVariant: MessageVariant? = null
        var isUserMessage = false
        
        for (node in chat.messageNodes) {
            // First, find if this node has a variant in the current path
            val variantInPath = chat.currentVariantPath.find { variantId ->
                node.variants.any { it.variantId == variantId }
            }
            
            // Prefer the variant in path, otherwise check all variants
            val variantsToCheck = if (variantInPath != null) {
                // Only check the variant that's in the current path
                listOfNotNull(node.getVariantById(variantInPath))
            } else {
                node.variants
            }
            
            for (variant in variantsToCheck) {
                // Check if it's the user message
                if (variant.userMessage.id == messageId) {
                    targetNode = node
                    targetVariant = variant
                    isUserMessage = true
                    break
                }
                // Check if it's one of the responses
                val responseIndex = variant.responses.indexOfFirst { it.id == messageId }
                if (responseIndex >= 0) {
                    targetNode = node
                    targetVariant = variant
                    isUserMessage = false
                    break
                }
            }
            if (targetNode != null) break
        }

        if (targetNode == null || targetVariant == null) {
            // Message not found in branching structure, try simple deletion
            val updatedMessages = chat.messages.filter { it.id != messageId }
            val updatedChat = chat.copy(messages = updatedMessages)
            
            val updatedChats = chatHistory.chat_history.map { c ->
                if (c.chat_id == chatId) updatedChat else c
            }
            saveChatHistory(chatHistory.copy(chat_history = updatedChats))
            return DeleteMessageResult.Success(updatedChat)
        }

        val totalVariants = targetNode.variants.size
        
        // Check if this is a branch point (multiple variants at this node)
        val isBranchPoint = totalVariants > 1

        if (isUserMessage) {
            // Deleting a user message
            
            // Check if there are messages/children after this in the current branch
            val hasResponsesAfter = targetVariant.responses.isNotEmpty()
            
            // Check if child node actually exists and has content
            val childNode = if (targetVariant.childNodeId != null) {
                chat.messageNodes.find { it.nodeId == targetVariant.childNodeId }
            } else null
            val hasChildrenAfter = childNode != null && childNode.variants.isNotEmpty()
            
            val hasMessagesAfter = hasResponsesAfter || hasChildrenAfter
            
            if (isBranchPoint) {
                // This is a branch point with multiple variants
                if (hasMessagesAfter) {
                    return DeleteMessageResult.CannotDeleteBranchPoint(
                        "לא ניתן למחוק הודעת התפצלות שיש אחריה הודעות נוספות. למחיקת ההודעה, מחקו קודם כל את ההודעות שאחריה."
                    )
                }
                
                // No messages after - delete this entire variant/branch
                val currentVariantIndex = targetNode.variants.indexOf(targetVariant)
                val updatedVariants = targetNode.variants.toMutableList()
                updatedVariants.removeAt(currentVariantIndex)
                
                // Update the node with remaining variants
                val updatedNode = targetNode.copy(variants = updatedVariants)
                
                // Update messageNodes
                val updatedNodes = chat.messageNodes.map { node ->
                    if (node.nodeId == targetNode.nodeId) updatedNode else node
                }.filter { it.variants.isNotEmpty() } // Remove empty nodes
                
                // Update currentVariantPath to point to another variant
                val newVariantPath = chat.currentVariantPath.toMutableList()
                val pathIndex = newVariantPath.indexOf(targetVariant.variantId)
                if (pathIndex >= 0) {
                    // Replace with another variant from this node (prefer previous, then next)
                    val newVariantIndex = if (currentVariantIndex > 0) currentVariantIndex - 1 else 0
                    val newVariant = updatedVariants.getOrNull(newVariantIndex)
                    if (newVariant != null) {
                        newVariantPath[pathIndex] = newVariant.variantId
                    } else {
                        // No variants left, remove from path
                        newVariantPath.removeAt(pathIndex)
                    }
                }
                
                // Rebuild messages list based on new path
                val rebuiltMessages = rebuildMessagesFromPath(
                    chat.copy(messageNodes = updatedNodes),
                    newVariantPath
                )
                
                val updatedChat = chat.copy(
                    messageNodes = updatedNodes,
                    currentVariantPath = newVariantPath,
                    messages = rebuiltMessages
                )
                
                val updatedChats = chatHistory.chat_history.map { c ->
                    if (c.chat_id == chatId) updatedChat else c
                }
                saveChatHistory(chatHistory.copy(chat_history = updatedChats))
                return DeleteMessageResult.Success(updatedChat)
                
            } else {
                // Single variant at this node
                if (hasMessagesAfter) {
                    return DeleteMessageResult.CannotDeleteBranchPoint(
                        "לא ניתן למחוק הודעה שיש אחריה הודעות נוספות. למחיקת ההודעה, מחקו קודם כל את ההודעות שאחריה."
                    )
                }
                
                // Remove this node entirely
                val updatedNodes = chat.messageNodes.filter { it.nodeId != targetNode.nodeId }
                
                // Update parent's childNodeId to null
                val nodesWithUpdatedParent = updatedNodes.map { node ->
                    val updatedVariantsInNode = node.variants.map { variant ->
                        if (variant.childNodeId == targetNode.nodeId) {
                            variant.copy(childNodeId = null)
                        } else {
                            variant
                        }
                    }
                    node.copy(variants = updatedVariantsInNode)
                }
                
                // Update path - remove this variant
                val newVariantPath = chat.currentVariantPath.filter { it != targetVariant.variantId }
                
                val rebuiltMessages = rebuildMessagesFromPath(
                    chat.copy(messageNodes = nodesWithUpdatedParent),
                    newVariantPath
                )
                
                val updatedChat = chat.copy(
                    messageNodes = nodesWithUpdatedParent,
                    currentVariantPath = newVariantPath,
                    messages = rebuiltMessages
                )
                
                val updatedChats = chatHistory.chat_history.map { c ->
                    if (c.chat_id == chatId) updatedChat else c
                }
                saveChatHistory(chatHistory.copy(chat_history = updatedChats))
                return DeleteMessageResult.Success(updatedChat)
            }
            
        } else {
            // Deleting an assistant response
            val messageIndex = targetVariant.responses.indexOfFirst { it.id == messageId }
            
            // Check if there are messages after this response
            val hasMoreResponses = messageIndex < targetVariant.responses.size - 1
            
            // Check if child node actually exists and has content
            val childNodeForResponse = if (targetVariant.childNodeId != null) {
                chat.messageNodes.find { it.nodeId == targetVariant.childNodeId }
            } else null
            val hasChildren = childNodeForResponse != null && childNodeForResponse.variants.isNotEmpty()
            
            if (hasMoreResponses || hasChildren) {
                return DeleteMessageResult.CannotDeleteBranchPoint(
                    "לא ניתן למחוק הודעה שיש אחריה הודעות נוספות. למחיקת ההודעה, מחקו קודם כל את ההודעות שאחריה."
                )
            }
            
            // Delete this response
            val updatedResponses = targetVariant.responses.filter { it.id != messageId }
            val updatedVariant = targetVariant.copy(responses = updatedResponses)
            
            val updatedVariantsForNode = targetNode.variants.map { variant ->
                if (variant.variantId == targetVariant.variantId) updatedVariant else variant
            }
            
            val updatedNode = targetNode.copy(variants = updatedVariantsForNode)
            val updatedNodes = chat.messageNodes.map { node ->
                if (node.nodeId == targetNode.nodeId) updatedNode else node
            }
            
            val rebuiltMessages = rebuildMessagesFromPath(
                chat.copy(messageNodes = updatedNodes),
                chat.currentVariantPath
            )
            
            val updatedChat = chat.copy(
                messageNodes = updatedNodes,
                messages = rebuiltMessages
            )
            
            val updatedChats = chatHistory.chat_history.map { c ->
                if (c.chat_id == chatId) updatedChat else c
            }
            saveChatHistory(chatHistory.copy(chat_history = updatedChats))
            return DeleteMessageResult.Success(updatedChat)
        }
    }

    /**
     * Rebuild the flat messages list from the branching structure based on a variant path
     */
    private fun rebuildMessagesFromPath(chat: Chat, variantPath: List<String>): List<Message> {
        if (chat.messageNodes.isEmpty()) return emptyList()
        
        val messages = mutableListOf<Message>()
        
        // Find root node (no parent)
        var currentNode = chat.messageNodes.find { it.parentNodeId == null }
        
        while (currentNode != null) {
            // Find variant in path or use first
            val variantInPath = variantPath.find { variantId ->
                currentNode!!.variants.any { it.variantId == variantId }
            }
            
            val currentVariant = if (variantInPath != null) {
                currentNode.getVariantById(variantInPath)
            } else {
                currentNode.variants.firstOrNull()
            }
            
            if (currentVariant == null) break
            
            // Add user message
            messages.add(currentVariant.userMessage)
            
            // Add responses
            messages.addAll(currentVariant.responses)
            
            // Move to child node
            val childNodeId = currentVariant.childNodeId
            currentNode = if (childNodeId != null) {
                chat.messageNodes.find { it.nodeId == childNodeId }
            } else {
                null
            }
        }
        
        return messages
    }
}

/**
 * Result of attempting to delete a message from a branching structure.
 */
sealed class DeleteMessageResult {
    data class Success(val updatedChat: Chat) : DeleteMessageResult()
    data class CannotDeleteBranchPoint(val message: String) : DeleteMessageResult()
    data class Error(val message: String) : DeleteMessageResult()
}


