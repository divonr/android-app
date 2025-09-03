package com.example.ApI.data.repository

import android.content.Context
import android.os.Environment
import com.example.ApI.data.model.*
import com.example.ApI.data.network.ApiService
import com.example.ApI.data.network.ApiResponse
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class DataRepository(private val context: Context) {
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    
    private val apiService = ApiService(context)
    
    private val internalDir = File(context.filesDir, "llm_data")
    
    init {
        if (!internalDir.exists()) {
            internalDir.mkdirs()
        }
    }
    
    // Providers
    fun loadProviders(): List<Provider> {
        return listOf(
            Provider(
                provider = "openai",
                models = listOf(
                    Model.SimpleModel("gpt-4o"),
                    Model.SimpleModel("gpt-5"),
                    Model.SimpleModel("gpt-4.1"),
                    Model.SimpleModel("o1"),
                    Model.SimpleModel("o1-pro"),
                    Model.SimpleModel("o3"),
                    Model.SimpleModel("o4-mini"),
                    Model.SimpleModel("o4-mini-deep-research"),
                ),
                request = ApiRequest(
                    request_type = "POST",
                    base_url = "https://api.openai.com/v1/responses",
                    headers = mapOf(
                        "Authorization" to "Bearer {OPENAI_API_KEY_HERE}",
                        "Content-Type" to "application/json"
                    ),
                    body = null
                ),
                response_important_fields = ResponseFields(
                    id = "{response_id}",
                    model = "{model_name}",
                    output = emptyList()
                ),
                upload_files_request = UploadRequest(
                    request_type = "POST",
                    base_url = "https://api.openai.com/v1/files",
                    headers = mapOf(
                        "Authorization" to "Bearer {OPENAI_API_KEY_HERE}",
                        "Content-Type" to "multipart/form-data"
                    ),
                    data = mapOf(
                        "purpose" to "assistants"
                    )
                ),
                upload_files_response_important_fields = UploadResponseFields(
                    id = "{file_ID}"
                )
            ),
            Provider(
                provider = "poe",
                models = listOf(
                    Model.ComplexModel(name = "GPT-5", min_points = 250),
                    Model.ComplexModel(name = "GPT-4.1", min_points = 206),
                    Model.ComplexModel(name = "o3", min_points = 401),
                    Model.ComplexModel(name = "o4-mini", min_points = 243),
                    Model.ComplexModel(name = "Claude-Sonnet-4-Search", min_points = 870),
                    Model.ComplexModel(name = "Claude-Sonnet-4-Reasoning", min_points = 1628),
                    Model.ComplexModel(name = "Claude-Sonnet-3.5", min_points = 270),
                    Model.ComplexModel(name = "GLM-4.5", min_points = 180),
                    Model.ComplexModel(name = "Gemini-2.5-Pro", min_points = 335),
                    Model.ComplexModel(name = "Grok-3", min_points = 856),
                    Model.ComplexModel(name = "Grok-4", min_points = 773),
                    Model.ComplexModel(name = "GPT-OSS-120B-T", min_points = 50),
                    Model.ComplexModel(name = "DeepSeek-V3-FW", min_points = 300),
                    Model.ComplexModel(name = "DeepSeek-R1", min_points = 600),
                    Model.ComplexModel(name = "Kimi-K2", min_points = 200)
                ),
                request = ApiRequest(
                    request_type = "POST",
                    base_url = "https://api.poe.com/bot/{model_name}",
                    headers = mapOf(
                        "Authorization" to "Bearer {POE_API_KEY_HERE}",
                        "Content-Type" to "application/json",
                        "Accept" to "application/json"
                    ),
                    body = null
                ),
                response_important_fields = ResponseFields(
                    response_format = "server_sent_events"
                ),
                upload_files_request = UploadRequest(
                    request_type = "POST",
                    base_url = "https://www.quora.com/poe_api/file_upload_3RD_PARTY_POST",
                    headers = mapOf(
                        "Authorization" to "{POE_API_KEY_HERE}"
                    )
                ),
                upload_files_response_important_fields = UploadResponseFields(
                    file_id = "{file_ID}",
                    attachment_url = "{file_URL}",
                    mime_type = "{mime_type}"
                )
            ),
            Provider(
                provider = "google",
                models = listOf(
                    Model.SimpleModel("gemini-2.5-pro"),
                    Model.SimpleModel("gemini-2.5-flash"),
                    Model.SimpleModel("gemini-1.5-pro-latest"),
                    Model.SimpleModel("gemini-1.5-flash-latest")
                ),
                request = ApiRequest(
                    request_type = "POST",
                    base_url = "https://generativelanguage.googleapis.com/v1beta/models/{model_name}:generateContent",
                    headers = mapOf(
                        "Content-Type" to "application/json"
                    ),
                    params = mapOf(
                        "key" to "{GOOGLE_API_KEY_HERE}"
                    ),
                    body = null
                ),
                response_important_fields = ResponseFields(
                    candidates = emptyList(),
                    usageMetadata = null,
                    modelVersion = "{model_name}",
                    responseId = "{response_id}"
                ),
                upload_files_request = UploadRequest(
                    request_type = "POST",
                    base_url = "https://generativelanguage.googleapis.com/upload/v1beta/files",
                    headers = mapOf(
                        "Content-Type" to "{mime_type}"
                    ),
                    params = mapOf(
                        "key" to "{GOOGLE_API_KEY_HERE}"
                    )
                ),
                upload_files_response_important_fields = UploadResponseFields(
                    file = null
                )
            )
        )
    }
    
    // Chat History
    fun loadChatHistory(username: String): UserChatHistory {
        val file = File(internalDir, "chat_history_$username.json")
        return if (file.exists()) {
            try {
                val content = file.readText()
                json.decodeFromString<UserChatHistory>(content)
            } catch (e: Exception) {
                UserChatHistory(username, emptyList(), emptyList())
            }
        } else {
            UserChatHistory(username, emptyList(), emptyList())
        }
    }
    
    fun saveChatHistory(chatHistory: UserChatHistory) {
        val file = File(internalDir, "chat_history_${chatHistory.user_name}.json")
        try {
            file.writeText(json.encodeToString(chatHistory))
        } catch (e: IOException) {
            // Handle error
        }
    }
    
    fun addMessageToChat(username: String, chatId: String, message: Message): Chat? {
        val chatHistory = loadChatHistory(username)
        val targetChat = chatHistory.chat_history.find { it.chat_id == chatId }?.copy(
            messages = chatHistory.chat_history.find { it.chat_id == chatId }?.messages.orEmpty() + message
        )
        
        val otherChats = chatHistory.chat_history.filter { it.chat_id != chatId }
        val updatedHistory = chatHistory.copy(
            chat_history = if (targetChat != null) {
                otherChats + targetChat // Add the updated chat at the end
            } else {
                chatHistory.chat_history
            }
        )
        
        saveChatHistory(updatedHistory)
        return targetChat
    }
    
    fun createNewChat(username: String, previewName: String, systemPrompt: String = ""): Chat {
        val chatId = UUID.randomUUID().toString()
        val newChat = Chat(
            chat_id = chatId,
            preview_name = previewName,
            messages = emptyList(),
            systemPrompt = systemPrompt
        )

        val chatHistory = loadChatHistory(username)
        val updatedHistory = chatHistory.copy(
            chat_history = chatHistory.chat_history + newChat
        )
        saveChatHistory(updatedHistory)

        return newChat
    }

    fun createNewChatInGroup(username: String, previewName: String, groupId: String, systemPrompt: String = ""): Chat {
        val chatId = UUID.randomUUID().toString()
        val newChat = Chat(
            chat_id = chatId,
            preview_name = previewName,
            messages = emptyList(),
            systemPrompt = systemPrompt,
            group = groupId
        )

        val chatHistory = loadChatHistory(username)
        val updatedHistory = chatHistory.copy(
            chat_history = chatHistory.chat_history + newChat
        )
        saveChatHistory(updatedHistory)

        return newChat
    }
    
    fun updateChatSystemPrompt(username: String, chatId: String, systemPrompt: String): Chat? {
        val chatHistory = loadChatHistory(username)
        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.chat_id == chatId) {
                chat.copy(systemPrompt = systemPrompt)
            } else {
                chat
            }
        }

        val updatedHistory = chatHistory.copy(chat_history = updatedChats)
        saveChatHistory(updatedHistory)

        return updatedChats.find { it.chat_id == chatId }
    }

    // Group Management
    fun createNewGroup(username: String, groupName: String): ChatGroup {
        val chatHistory = loadChatHistory(username)
        val groupId = UUID.randomUUID().toString()
        val newGroup = ChatGroup(
            group_id = groupId,
            group_name = groupName
        )

        val updatedHistory = chatHistory.copy(groups = chatHistory.groups + newGroup)
        saveChatHistory(updatedHistory)

        return newGroup
    }

    fun addChatToGroup(username: String, chatId: String, groupId: String): Boolean {
        val chatHistory = loadChatHistory(username)

        // Check if group exists
        val groupExists = chatHistory.groups.any { it.group_id == groupId }
        if (!groupExists) return false

        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.chat_id == chatId) {
                chat.copy(group = groupId)
            } else {
                chat
            }
        }

        val updatedHistory = chatHistory.copy(chat_history = updatedChats)
        saveChatHistory(updatedHistory)

        return true
    }

    fun removeChatFromGroup(username: String, chatId: String): Boolean {
        val chatHistory = loadChatHistory(username)

        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.chat_id == chatId) {
                chat.copy(group = null)
            } else {
                chat
            }
        }

        val updatedHistory = chatHistory.copy(chat_history = updatedChats)
        saveChatHistory(updatedHistory)

        return true
    }

    fun deleteGroup(username: String, groupId: String): Boolean {
        val chatHistory = loadChatHistory(username)

        // Remove all chats from this group
        val updatedChats = chatHistory.chat_history.map { chat ->
            if (chat.group == groupId) {
                chat.copy(group = null)
            } else {
                chat
            }
        }

        // Remove the group
        val updatedGroups = chatHistory.groups.filter { it.group_id != groupId }

        val updatedHistory = chatHistory.copy(
            chat_history = updatedChats,
            groups = updatedGroups
        )
        saveChatHistory(updatedHistory)

        return true
    }

    fun renameGroup(username: String, groupId: String, newName: String): Boolean {
        val chatHistory = loadChatHistory(username)

        val updatedGroups = chatHistory.groups.map { group ->
            if (group.group_id == groupId) {
                group.copy(group_name = newName)
            } else {
                group
            }
        }

        val updatedHistory = chatHistory.copy(groups = updatedGroups)
        saveChatHistory(updatedHistory)

        return true
    }

    fun updateGroupProjectStatus(username: String, groupId: String, isProject: Boolean): Boolean {
        val chatHistory = loadChatHistory(username)

        val updatedGroups = chatHistory.groups.map { group ->
            if (group.group_id == groupId) {
                group.copy(is_project = isProject)
            } else {
                group
            }
        }

        val updatedHistory = chatHistory.copy(groups = updatedGroups)
        saveChatHistory(updatedHistory)

        return true
    }

    fun addAttachmentToGroup(username: String, groupId: String, attachment: Attachment): Boolean {
        val chatHistory = loadChatHistory(username)

        val updatedGroups = chatHistory.groups.map { group ->
            if (group.group_id == groupId) {
                group.copy(group_attachments = group.group_attachments + attachment)
            } else {
                group
            }
        }

        val updatedHistory = chatHistory.copy(groups = updatedGroups)
        saveChatHistory(updatedHistory)

        return true
    }

    fun removeAttachmentFromGroup(username: String, groupId: String, attachmentIndex: Int): Boolean {
        val chatHistory = loadChatHistory(username)

        val updatedGroups = chatHistory.groups.map { group ->
            if (group.group_id == groupId && attachmentIndex >= 0 && attachmentIndex < group.group_attachments.size) {
                val updatedAttachments = group.group_attachments.toMutableList()
                updatedAttachments.removeAt(attachmentIndex)
                group.copy(group_attachments = updatedAttachments)
            } else {
                group
            }
        }

        val updatedHistory = chatHistory.copy(groups = updatedGroups)
        saveChatHistory(updatedHistory)

        return true
    }
    
    // API Keys
    fun loadApiKeys(username: String): List<ApiKey> {
        val file = File(internalDir, "api_keys_$username.json")
        return if (file.exists()) {
            try {
                val content = file.readText()
                json.decodeFromString<List<ApiKey>>(content)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
    
    fun saveApiKeys(username: String, apiKeys: List<ApiKey>) {
        val file = File(internalDir, "api_keys_$username.json")
        try {
            file.writeText(json.encodeToString(apiKeys))
        } catch (e: IOException) {
            // Handle error
        }
    }
    
    fun addApiKey(username: String, apiKey: ApiKey) {
        val currentKeys = loadApiKeys(username).toMutableList()
        
        // If adding a new key for same provider and it should be active, 
        // deactivate all other keys for this provider
        if (apiKey.isActive) {
            for (i in currentKeys.indices) {
                if (currentKeys[i].provider == apiKey.provider) {
                    currentKeys[i] = currentKeys[i].copy(isActive = false)
                }
            }
        }
        
        currentKeys.add(apiKey)
        saveApiKeys(username, currentKeys)
    }
    
    fun toggleApiKeyStatus(username: String, keyId: String) {
        val currentKeys = loadApiKeys(username)
        val targetKey = currentKeys.find { it.id == keyId } ?: return
        val updatedKeys = currentKeys.map { key ->
            when {
                key.id == keyId -> {
                    // Toggle this key
                    val newActiveState = !key.isActive
                    // If activating this key, deactivate all other keys for same provider
                    if (newActiveState) {
                        key.copy(isActive = true)
                    } else {
                        key.copy(isActive = false)
                    }
                }
                key.provider == targetKey.provider && key.id != keyId && !targetKey.isActive -> {
                    // If we're activating the target key, deactivate others of same provider
                    key.copy(isActive = false)
                }
                else -> key
            }
        }
        saveApiKeys(username, updatedKeys)
    }
    
    fun deleteApiKey(username: String, keyId: String) {
        val currentKeys = loadApiKeys(username)
        val updatedKeys = currentKeys.filter { it.id != keyId }
        saveApiKeys(username, updatedKeys)
    }
    
    // App Settings
    fun loadAppSettings(): AppSettings {
        val file = File(internalDir, "app_settings.json")
        return if (file.exists()) {
            try {
                val content = file.readText()
                json.decodeFromString<AppSettings>(content)
            } catch (e: Exception) {
                AppSettings(
                    current_user = "default",
                    selected_provider = "openai",
                    selected_model = "gpt-4o"
                )
            }
        } else {
            AppSettings(
                current_user = "default",
                selected_provider = "openai",
                selected_model = "gpt-4o"
            )
        }
    }
    
    fun saveAppSettings(settings: AppSettings) {
        val file = File(internalDir, "app_settings.json")
        try {
            file.writeText(json.encodeToString(settings))
        } catch (e: IOException) {
            // Handle error
        }
    }
    
    // File management
    fun saveFileLocally(fileName: String, data: ByteArray): String? {
        val filesDir = File(internalDir, "attachments")
        if (!filesDir.exists()) {
            filesDir.mkdirs()
        }
        
        val file = File(filesDir, "${UUID.randomUUID()}_$fileName")
        return try {
            file.writeBytes(data)
            file.absolutePath
        } catch (e: IOException) {
            null
        }
    }
    
    fun deleteFile(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            false
        }
    }
    
    // Replace a specific message in a chat
    fun replaceMessageInChat(username: String, chatId: String, oldMessage: Message, newMessage: Message): Chat? {
        val chatHistory = loadChatHistory(username)
        val targetChat = chatHistory.chat_history.find { it.chat_id == chatId } ?: return null
        
        val updatedMessages = targetChat.messages.map { message ->
            if (message == oldMessage) newMessage else message
        }
        
        val updatedChat = targetChat.copy(messages = updatedMessages)
        val otherChats = chatHistory.chat_history.filter { it.chat_id != chatId }
        
        val updatedHistory = chatHistory.copy(
            chat_history = otherChats + updatedChat
        )
        
        saveChatHistory(updatedHistory)
        return updatedChat
    }
    
    // Delete all messages from a specific message onwards (including the message)
    fun deleteMessagesFromPoint(username: String, chatId: String, fromMessage: Message): Chat? {
        val chatHistory = loadChatHistory(username)
        val targetChat = chatHistory.chat_history.find { it.chat_id == chatId } ?: return null
        
        // Find the index of the message to delete from
        val messageIndex = targetChat.messages.indexOf(fromMessage)
        if (messageIndex == -1) return targetChat // Message not found
        
        // Keep only messages before this index
        val updatedMessages = targetChat.messages.take(messageIndex)
        
        val updatedChat = targetChat.copy(messages = updatedMessages)
        val otherChats = chatHistory.chat_history.filter { it.chat_id != chatId }
        
        val updatedHistory = chatHistory.copy(
            chat_history = otherChats + updatedChat
        )
        
        saveChatHistory(updatedHistory)
        return updatedChat
    }
    
    // API Communication
    suspend fun sendMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        username: String,
        chatId: String? = null,
        webSearchEnabled: Boolean = false
    ): ApiResponse {
        val apiKeys = loadApiKeys(username)
            .filter { it.isActive }
            .associate { it.provider to it.key }
        
        // Check and re-upload files if needed when provider has changed
        val (updatedMessages, hasUpdates) = ensureFilesUploadedForProvider(provider, messages, username)
        
        // If files were re-uploaded, update the chat history
        if (hasUpdates && chatId != null) {
            updateChatWithNewAttachments(username, chatId, updatedMessages)
        }
        
        return apiService.sendMessage(provider, modelName, updatedMessages, systemPrompt, apiKeys, webSearchEnabled)
    }

    suspend fun sendMessageStreaming(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        username: String,
        chatId: String? = null,
        webSearchEnabled: Boolean = false,
        callback: com.example.ApI.data.network.StreamingCallback
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
        
        apiService.sendMessageStreaming(provider, modelName, updatedMessages, systemPrompt, apiKeys, webSearchEnabled, callback)
    }
    
    // Check and re-upload files if provider has changed
    private suspend fun ensureFilesUploadedForProvider(
        provider: Provider, 
        messages: List<Message>, 
        username: String
    ): Pair<List<Message>, Boolean> {
        val updatedMessages = mutableListOf<Message>()
        var hasUpdates = false
        
        for (message in messages) {
            if (message.attachments.isEmpty()) {
                // No attachments, keep message as is
                updatedMessages.add(message)
                continue
            }
            
            val updatedAttachments = mutableListOf<Attachment>()
            
            for (attachment in message.attachments) {
                val needsReupload = when (provider.provider) {
                    "openai" -> attachment.file_OPENAI_id == null
                    "poe" -> attachment.file_POE_url == null
                    "google" -> attachment.file_GOOGLE_uri == null
                    else -> false
                }
                
                if (needsReupload && attachment.local_file_path != null) {
                    // Need to re-upload file for current provider
                    val uploadedAttachment = uploadFile(
                        provider = provider,
                        filePath = attachment.local_file_path,
                        fileName = attachment.file_name,
                        mimeType = attachment.mime_type,
                        username = username
                    )
                    
                    if (uploadedAttachment != null) {
                        // Merge the new ID with existing attachment data
                        val mergedAttachment = when (provider.provider) {
                            "openai" -> attachment.copy(file_OPENAI_id = uploadedAttachment.file_OPENAI_id)
                            "poe" -> attachment.copy(file_POE_url = uploadedAttachment.file_POE_url)
                            "google" -> attachment.copy(file_GOOGLE_uri = uploadedAttachment.file_GOOGLE_uri)
                            else -> attachment
                        }
                        updatedAttachments.add(mergedAttachment)
                        hasUpdates = true
                    } else {
                        // Upload failed, keep original attachment
                        updatedAttachments.add(attachment)
                    }
                } else {
                    // Already has correct ID for current provider, or no local file
                    updatedAttachments.add(attachment)
                }
            }
            
            updatedMessages.add(message.copy(attachments = updatedAttachments))
        }
        
        return Pair(
            if (hasUpdates) updatedMessages else messages,
            hasUpdates
        )
    }
    
    // Update specific chat with new file attachments after re-upload
    fun updateChatWithNewAttachments(username: String, chatId: String, updatedMessages: List<Message>) {
        try {
            val chatHistory = loadChatHistory(username)
            val updatedChats = chatHistory.chat_history.map { chat ->
                if (chat.chat_id == chatId) {
                    chat.copy(messages = updatedMessages)
                } else {
                    chat
                }
            }
            val updatedHistory = chatHistory.copy(chat_history = updatedChats)
            saveChatHistory(updatedHistory)
        } catch (e: Exception) {
            println("Failed to update chat with new file IDs: ${e.message}")
        }
    }
    
    fun exportChatHistory(username: String): String? {
        val chatHistory = loadChatHistory(username)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val exportFile = File(downloadsDir, "chat_history_${username}_${System.currentTimeMillis()}.json")
        
        return try {
            exportFile.writeText(json.encodeToString(chatHistory))
            exportFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Import chat history from raw JSON bytes. The JSON must conform to internal
     * UserChatHistory schema. All attachment references are stripped.
     */
    fun importChatHistoryJson(raw: ByteArray, targetUsername: String) {
        try {
            val text = raw.toString(Charsets.UTF_8)
            val imported = json.decodeFromString<UserChatHistory>(text)
            // Sanitize: drop attachments info (local and remote) for every message
            val sanitizedChats = imported.chat_history.map { chat ->
                val sanitizedMessages = chat.messages.map { msg ->
                    msg.copy(
                        attachments = emptyList()
                    )
                }
                chat.copy(messages = sanitizedMessages)
            }
            val sanitized = imported.copy(user_name = targetUsername, chat_history = sanitizedChats)
            saveChatHistory(sanitized)
        } catch (e: Exception) {
            // Ignore invalid import
        }
    }
    
    // File Upload functionality
    suspend fun uploadFile(
        provider: Provider,
        filePath: String,
        fileName: String,
        mimeType: String,
        username: String
    ): Attachment? {
        val apiKeys = loadApiKeys(username)
            .filter { it.isActive }
            .associate { it.provider to it.key }
        val apiKey = apiKeys[provider.provider] ?: return null
        val uploadRequest = provider.upload_files_request ?: return null
        
        return try {
            when (provider.provider) {
                "openai" -> uploadFileToOpenAI(uploadRequest, filePath, fileName, mimeType, apiKey)
                "poe" -> uploadFileToPoe(uploadRequest, filePath, fileName, mimeType, apiKey)
                "google" -> uploadFileToGoogle(uploadRequest, filePath, fileName, mimeType, apiKey)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun uploadFileToOpenAI(
        uploadRequest: UploadRequest,
        filePath: String,
        fileName: String,
        mimeType: String,
        apiKey: String
    ): Attachment? = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext null
            
            val url = URL(uploadRequest.base_url)
            val connection = url.openConnection() as HttpURLConnection
            
            val boundary = "----FormBoundary${System.currentTimeMillis()}"
            
            // Set up the connection
            connection.requestMethod = uploadRequest.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.doOutput = true
            
            val outputStream = connection.outputStream
            
            // Write multipart form data
            outputStream.write("--$boundary\r\n".toByteArray())
            outputStream.write("Content-Disposition: form-data; name=\"purpose\"\r\n\r\n".toByteArray())
            outputStream.write("assistants\r\n".toByteArray())
            
            outputStream.write("--$boundary\r\n".toByteArray())
            outputStream.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
            outputStream.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
            
            // Write file content
            file.inputStream().use { input ->
                input.copyTo(outputStream)
            }
            
            outputStream.write("\r\n--$boundary--\r\n".toByteArray())
            outputStream.close()
            
            // Read response
            val responseCode = connection.responseCode
            val reader = if (responseCode >= 400) {
                BufferedReader(InputStreamReader(connection.errorStream))
            } else {
                BufferedReader(InputStreamReader(connection.inputStream))
            }
            
            val response = reader.readText()
            reader.close()
            connection.disconnect()
            
            if (responseCode >= 400) {
                return@withContext null
            }
            
            // Parse response to get file ID
            val responseJson = json.parseToJsonElement(response).jsonObject
            val fileId = responseJson["id"]?.jsonPrimitive?.content
            
            if (fileId != null) {
                Attachment(
                    local_file_path = filePath,
                    file_name = fileName,
                    mime_type = mimeType,
                    file_OPENAI_id = fileId
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun uploadFileToPoe(
        uploadRequest: UploadRequest,
        filePath: String,
        fileName: String,
        mimeType: String,
        apiKey: String
    ): Attachment? = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext null
            
            val url = URL(uploadRequest.base_url)
            val connection = url.openConnection() as HttpURLConnection
            
            val boundary = "----FormBoundary${System.currentTimeMillis()}"
            
            // Set up the connection
            connection.requestMethod = uploadRequest.request_type
            connection.setRequestProperty("Authorization", apiKey)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.doOutput = true
            
            val outputStream = connection.outputStream
            
            // Write multipart form data
            outputStream.write("--$boundary\r\n".toByteArray())
            outputStream.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
            outputStream.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
            
            // Write file content
            file.inputStream().use { input ->
                input.copyTo(outputStream)
            }
            
            outputStream.write("\r\n--$boundary--\r\n".toByteArray())
            outputStream.close()
            
            // Read response
            val responseCode = connection.responseCode
            val reader = if (responseCode >= 400) {
                BufferedReader(InputStreamReader(connection.errorStream))
            } else {
                BufferedReader(InputStreamReader(connection.inputStream))
            }
            
            val response = reader.readText()
            reader.close()
            connection.disconnect()
            
            if (responseCode >= 400) {
                return@withContext null
            }
            
            // Parse response to get file ID and URL
            val responseJson = json.parseToJsonElement(response).jsonObject
            val fileId = responseJson["file_id"]?.jsonPrimitive?.content
            val attachmentUrl = responseJson["attachment_url"]?.jsonPrimitive?.content
            
            if (fileId != null && attachmentUrl != null) {
                Attachment(
                    local_file_path = filePath,
                    file_name = fileName,
                    mime_type = mimeType,
                    file_POE_url = attachmentUrl
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun uploadFileToGoogle(
        uploadRequest: UploadRequest,
        filePath: String,
        fileName: String,
        mimeType: String,
        apiKey: String
    ): Attachment? = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext null
            
            val baseUrl = uploadRequest.base_url
            val url = URL("$baseUrl?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            
            // Set up the connection
            connection.requestMethod = uploadRequest.request_type
            connection.setRequestProperty("Content-Type", mimeType)
            connection.doOutput = true
            
            // Write file content directly (binary upload)
            val outputStream = connection.outputStream
            file.inputStream().use { input ->
                input.copyTo(outputStream)
            }
            outputStream.close()
            
            // Read response
            val responseCode = connection.responseCode
            val reader = if (responseCode >= 400) {
                BufferedReader(InputStreamReader(connection.errorStream))
            } else {
                BufferedReader(InputStreamReader(connection.inputStream))
            }
            
            val response = reader.readText()
            reader.close()
            connection.disconnect()
            
            if (responseCode >= 400) {
                return@withContext null
            }
            
            // Parse response to get file URI
            val responseJson = json.parseToJsonElement(response).jsonObject
            val fileObj = responseJson["file"]?.jsonObject
            val fileUri = fileObj?.get("uri")?.jsonPrimitive?.content
            
            if (fileUri != null) {
                Attachment(
                    local_file_path = filePath,
                    file_name = fileName,
                    mime_type = mimeType,
                    file_GOOGLE_uri = fileUri
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Generates a display name for a conversation using LLM API
     * @param conversationId The ID of the conversation to generate a title for
     * @param provider Optional provider name. If null, will use priority: OpenAI > Google > POE > default
     * @return Generated title string or default "שיחה חדשה" if generation fails
     */
    suspend fun generateConversationTitle(
        username: String,
        conversationId: String,
        provider: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            // Load chat history and find the specified conversation
            val chatHistory = loadChatHistory(username)
            val conversation = chatHistory.chat_history.find { it.chat_id == conversationId }
                ?: return@withContext "שיחה חדשה"
            
            // If conversation has no messages, return default
            if (conversation.messages.isEmpty()) {
                return@withContext "שיחה חדשה"
            }
            
            // Load available providers and API keys
            val availableProviders = loadProviders()
            val apiKeys = loadApiKeys(username)
                .filter { it.isActive }
                .associate { it.provider to it.key }
            
            // Select provider and model based on priority
            val (selectedProvider, modelName) = selectProviderAndModel(
                provider, availableProviders, apiKeys
            ) ?: return@withContext "שיחה חדשה"
            
            // Format messages as JSON for the prompt
            val messagesJson = formatMessagesForPrompt(conversation.messages)
            
            // Create the prompt
            val prompt = buildTitleGenerationPrompt(messagesJson)
            
            // Create a simple message list with just the prompt
            val promptMessages = listOf(
                Message(
                    role = "user",
                    text = prompt,
                    attachments = emptyList()
                )
            )
            
            // Make API call
            val response = apiService.sendMessage(
                provider = selectedProvider,
                modelName = modelName,
                messages = promptMessages,
                systemPrompt = "",
                apiKeys = apiKeys,
                webSearchEnabled = false // No web search for title generation
            )
            
            // Extract title from response based on provider
            val extractedTitle = extractTitleFromResponse(response, selectedProvider.provider)
            
            // Return extracted title or default
            return@withContext extractedTitle?.takeIf { it.isNotBlank() } ?: "שיחה חדשה"
            
        } catch (e: Exception) {
            // If anything fails, return default
            return@withContext "שיחה חדשה"
        }
    }
    
    /**
     * Select provider and model based on priority and availability
     */
    private fun selectProviderAndModel(
        preferredProvider: String?,
        availableProviders: List<Provider>,
        apiKeys: Map<String, String>
    ): Pair<Provider, String>? {
        // Helper to pick the first model name defined for a provider
        fun firstModelName(p: Provider): String? = p.models.firstOrNull()?.name

        // If preferred provider is specified, try to use it when key exists
        if (preferredProvider != null && apiKeys.containsKey(preferredProvider)) {
            val provider = availableProviders.find { it.provider == preferredProvider }
            val model = provider?.let { firstModelName(it) }
            if (provider != null && model != null) return Pair(provider, model)
        }

        // Priority order: OpenAI > Google > POE
        val priorityOrder = listOf("openai", "google", "poe")
        for (providerName in priorityOrder) {
            if (!apiKeys.containsKey(providerName)) continue
            val provider = availableProviders.find { it.provider == providerName } ?: continue
            val model = firstModelName(provider) ?: continue
            return Pair(provider, model)
        }

        return null
    }
    
    /**
     * Format conversation messages as JSON string for the prompt
     */
    private fun formatMessagesForPrompt(messages: List<Message>): String {
        return try {
            val messagesJson = messages.map { message ->
                buildJsonObject {
                    put("role", message.role)
                    put("text", message.text)
                    if (message.attachments.isNotEmpty()) {
                        put("attachments", buildJsonArray {
                            message.attachments.forEach { attachment ->
                                add(buildJsonObject {
                                    put("file_name", attachment.file_name)
                                    put("mime_type", attachment.mime_type)
                                })
                            }
                        })
                    }
                }
            }
            json.encodeToString(JsonArray(messagesJson))
        } catch (e: Exception) {
            "[]"
        }
    }
    
    /**
     * Build the title generation prompt
     */
    private fun buildTitleGenerationPrompt(messagesJson: String): String {
        return """I am about to make a request, and I expect that in response you will return only the relevant answer. Do not precede anything, do not explain anything, do not refer to me beyond returning the response I requested.   The question is: Attached are messages that constitute a conversation between a user and LLM. Give a title for the conversation, which will remind the user in the future what the conversation is about. The title will be in the same language in which the conversation is taking place, and will contain up to 6 words, with a preference for a shorter title (2-3 words).    I remind you: In response, I expect to receive from you only the title you set, without any further introductions or explanations. That is, your response should be a maximum of 6 words.    Below are the messages, in json format: $messagesJson"""
    }
    
    /**
     * Extract title from API response based on provider
     */
    private fun extractTitleFromResponse(response: ApiResponse, providerName: String): String? {
        return try {
            when (response) {
                is ApiResponse.Success -> {
                    // For all providers, the message contains the generated title
                    response.message.trim()
                }
                is ApiResponse.Error -> {
                    // If there was an error, return null
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
