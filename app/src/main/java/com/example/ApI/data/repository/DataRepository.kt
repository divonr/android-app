package com.example.ApI.data.repository

import android.content.Context
import android.os.Environment
import com.example.ApI.data.model.*
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.network.ApiService
import com.example.ApI.data.network.ApiResponse
import com.example.ApI.tools.ToolSpecification
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

    companion object {
        private const val MODELS_JSON_URL = "https://raw.githubusercontent.com/divonr/android-app/main/models.json"
        private const val MODELS_CACHE_FILE = "models_cache.json"
        private const val MODELS_CACHE_METADATA_FILE = "models_cache_metadata.json"
        private const val CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
    }
    
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

    // ============ Remote Models Fetching ============

    /**
     * Loads the cached models metadata to check last fetch time
     */
    private fun loadModelsCacheMetadata(): ModelsCacheMetadata? {
        val file = File(internalDir, MODELS_CACHE_METADATA_FILE)
        return if (file.exists()) {
            try {
                json.decodeFromString<ModelsCacheMetadata>(file.readText())
            } catch (e: Exception) {
                android.util.Log.e("DataRepository", "Failed to load models cache metadata", e)
                null
            }
        } else {
            null
        }
    }

    /**
     * Saves the models cache metadata with current timestamp
     */
    private fun saveModelsCacheMetadata() {
        val file = File(internalDir, MODELS_CACHE_METADATA_FILE)
        try {
            val metadata = ModelsCacheMetadata(
                lastFetchTimestamp = System.currentTimeMillis()
            )
            file.writeText(json.encodeToString(metadata))
        } catch (e: Exception) {
            android.util.Log.e("DataRepository", "Failed to save models cache metadata", e)
        }
    }

    /**
     * Loads cached models from local storage
     */
    private fun loadCachedModels(): List<RemoteProviderModels>? {
        val file = File(internalDir, MODELS_CACHE_FILE)
        return if (file.exists()) {
            try {
                json.decodeFromString<List<RemoteProviderModels>>(file.readText())
            } catch (e: Exception) {
                android.util.Log.e("DataRepository", "Failed to load cached models", e)
                null
            }
        } else {
            null
        }
    }

    /**
     * Saves fetched models to local cache
     */
    private fun saveCachedModels(models: List<RemoteProviderModels>) {
        val file = File(internalDir, MODELS_CACHE_FILE)
        try {
            file.writeText(json.encodeToString(models))
        } catch (e: Exception) {
            android.util.Log.e("DataRepository", "Failed to save cached models", e)
        }
    }

    /**
     * Checks if the cache is still valid (less than 24 hours old)
     */
    private fun isCacheValid(): Boolean {
        val metadata = loadModelsCacheMetadata() ?: return false
        val currentTime = System.currentTimeMillis()
        return (currentTime - metadata.lastFetchTimestamp) < CACHE_VALIDITY_MS
    }

    /**
     * Fetches models from the remote GitHub URL
     * Returns null if the fetch fails
     */
    private suspend fun fetchModelsFromRemote(): List<RemoteProviderModels>? = withContext(Dispatchers.IO) {
        try {
            val url = URL(MODELS_JSON_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val models = json.decodeFromString<List<RemoteProviderModels>>(response.toString())
                android.util.Log.i("DataRepository", "Successfully fetched ${models.size} providers from remote")
                models
            } else {
                android.util.Log.e("DataRepository", "Failed to fetch models: HTTP $responseCode")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("DataRepository", "Failed to fetch models from remote", e)
            null
        }
    }

    /**
     * Refreshes models from remote if cache is expired
     * Should be called on app startup
     * Returns true if models were refreshed, false otherwise
     */
    suspend fun refreshModelsIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (isCacheValid()) {
            android.util.Log.i("DataRepository", "Models cache is still valid, skipping refresh")
            return@withContext false
        }

        android.util.Log.i("DataRepository", "Models cache expired or missing, fetching from remote...")
        val remoteModels = fetchModelsFromRemote()

        if (remoteModels != null) {
            saveCachedModels(remoteModels)
            saveModelsCacheMetadata()
            android.util.Log.i("DataRepository", "Models cache updated successfully")
            return@withContext true
        }

        android.util.Log.w("DataRepository", "Failed to refresh models, will use existing cache or defaults")
        return@withContext false
    }

    /**
     * Force refresh models from remote regardless of cache status
     * Returns a Pair of (success: Boolean, errorMessage: String?)
     */
    suspend fun forceRefreshModels(): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        android.util.Log.i("DataRepository", "Force refreshing models from remote...")

        try {
            val url = URL(MODELS_JSON_URL)
            android.util.Log.d("DataRepository", "Fetching from URL: $url")

            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val responseCode = connection.responseCode
            android.util.Log.d("DataRepository", "Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val jsonString = response.toString().trim()
                android.util.Log.d("DataRepository", "Response length: ${jsonString.length}")
                android.util.Log.d("DataRepository", "First 200 chars: ${jsonString.take(200)}")
                android.util.Log.d("DataRepository", "Last 200 chars: ${jsonString.takeLast(200)}")

                // Check if response is HTML instead of JSON
                if (jsonString.startsWith("<") || jsonString.contains("<!DOCTYPE") || jsonString.contains("<html")) {
                    val errorMsg = "Received HTML instead of JSON. Check URL and network access."
                    android.util.Log.e("DataRepository", errorMsg)
                    return@withContext Pair(false, errorMsg)
                }

                val remoteModels = try {
                    json.decodeFromString<List<RemoteProviderModels>>(jsonString)
                } catch (e: Exception) {
                    val errorMsg = "JSON parsing failed: ${e.message}"
                    android.util.Log.e("DataRepository", errorMsg)
                    android.util.Log.e("DataRepository", "JSON stacktrace:", e)
                    // Log first part of JSON to see what's wrong
                    android.util.Log.e("DataRepository", "JSON preview (first 500 chars):\n${jsonString.take(500)}")
                    return@withContext Pair(false, errorMsg)
                }

                android.util.Log.i("DataRepository", "Successfully parsed ${remoteModels.size} providers from remote")

                saveCachedModels(remoteModels)
                saveModelsCacheMetadata()
                android.util.Log.i("DataRepository", "Models force refreshed successfully")

                return@withContext Pair(true, null)
            } else {
                val errorMsg = "HTTP $responseCode"
                android.util.Log.e("DataRepository", "Failed to fetch models: $errorMsg")
                return@withContext Pair(false, errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            android.util.Log.e("DataRepository", "Failed to fetch models from remote: $errorMsg", e)
            return@withContext Pair(false, errorMsg)
        }
    }

    /**
     * Gets the cached remote models, or null if not available
     */
    private fun getRemoteModels(): List<RemoteProviderModels>? {
        return loadCachedModels()
    }

    // ============ Providers ============

    /**
     * Converts remote models to internal Model format
     */
    private fun remoteModelsToModels(remoteModels: List<RemoteModel>): List<Model> {
        return remoteModels.map { remote ->
            // Check if any pricing info is available
            val hasPricing = remote.min_points != null || remote.points != null ||
                    remote.input_points_per_1k != null || remote.output_points_per_1k != null

            if (hasPricing) {
                val pricing = PoePricing(
                    min_points = remote.min_points,
                    points = remote.points,
                    input_points_per_1k = remote.input_points_per_1k,
                    output_points_per_1k = remote.output_points_per_1k
                )
                Model.ComplexModel(
                    name = remote.name,
                    min_points = remote.min_points,
                    pricing = pricing
                )
            } else {
                Model.SimpleModel(remote.name)
            }
        }
    }

    /**
     * Gets models for a specific provider from cache, or returns default models
     */
    private fun getModelsForProvider(providerName: String, defaultModels: List<Model>): List<Model> {
        val cachedModels = getRemoteModels()
        val providerModels = cachedModels?.find { it.provider == providerName }

        return if (providerModels != null && providerModels.models.isNotEmpty()) {
            android.util.Log.d("DataRepository", "Using cached models for $providerName: ${providerModels.models.size} models")
            remoteModelsToModels(providerModels.models)
        } else {
            android.util.Log.d("DataRepository", "Using default models for $providerName: ${defaultModels.size} models")
            defaultModels
        }
    }

    // Default models (fallback when cache is not available)
    private val defaultOpenAIModels = listOf(
        Model.SimpleModel("gpt-4o"),
        Model.SimpleModel("gpt-4-turbo")
    )

    private val defaultAnthropicModels = listOf(
        Model.SimpleModel("claude-sonnet-4-5"),
        Model.SimpleModel("claude-3-7-sonnet-latest")
    )

    private val defaultGoogleModels = listOf(
        Model.SimpleModel("gemini-2.5-pro"),
        Model.SimpleModel("gemini-1.5-pro-latest")
    )

    private val defaultPoeModels = listOf(
        Model.ComplexModel(name = "GPT-4o", min_points = 250, pricing = PoePricing(min_points = 250)),
        Model.ComplexModel(name = "Claude-Sonnet-3.5", min_points = 270, pricing = PoePricing(min_points = 270))
    )

    private val defaultCohereModels = listOf(
        Model.SimpleModel("command-a-03-2025"),
        Model.SimpleModel("command-r-plus-08-2024")
    )

    private val defaultOpenRouterModels = listOf(
        Model.SimpleModel("openai/gpt-4o"),
        Model.SimpleModel("anthropic/claude-3.5-sonnet")
    )

    // Providers
    fun loadProviders(): List<Provider> {
        return listOf(
            Provider(
                provider = "openai",
                models = getModelsForProvider("openai", defaultOpenAIModels),
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
                provider = "anthropic",
                models = getModelsForProvider("anthropic", defaultAnthropicModels),
                request = ApiRequest(
                    request_type = "POST",
                    base_url = "https://api.anthropic.com/v1/messages",
                    headers = mapOf(
                        "x-api-key" to "{ANTHROPIC_API_KEY_HERE}",
                        "anthropic-version" to "2023-06-01",
                        "Content-Type" to "application/json"
                    ),
                    body = null
                ),
                response_important_fields = ResponseFields(
                    id = "{message_id}",
                    model = "{model_name}"
                ),
                upload_files_request = null,
                upload_files_response_important_fields = null
            ),
            Provider(
                provider = "google",
                models = getModelsForProvider("google", defaultGoogleModels),
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
            ),
            Provider(
                provider = "poe",
                models = getModelsForProvider("poe", defaultPoeModels),
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
                provider = "cohere",
                models = getModelsForProvider("cohere", defaultCohereModels),
                request = ApiRequest(
                    request_type = "POST",
                    base_url = "https://api.cohere.ai/v2/chat",
                    headers = mapOf(
                        "Authorization" to "Bearer {COHERE_API_KEY_HERE}",
                        "Content-Type" to "application/json"
                    ),
                    body = null
                ),
                response_important_fields = ResponseFields(
                    response_format = "server_sent_events"
                ),
                upload_files_request = null,  // Cohere uses inline base64, no separate upload
                upload_files_response_important_fields = null
            ),
            Provider(
                provider = "openrouter",
                models = getModelsForProvider("openrouter", defaultOpenRouterModels),
                request = ApiRequest(
                    request_type = "POST",
                    base_url = "https://openrouter.ai/api/v1/chat/completions",
                    headers = mapOf(
                        "Authorization" to "Bearer {OPENROUTER_API_KEY_HERE}",
                        "Content-Type" to "application/json",
                        "HTTP-Referer" to "https://github.com/your-app",
                        "X-Title" to "LLM Chat App"
                    ),
                    body = null
                ),
                response_important_fields = ResponseFields(
                    response_format = "server_sent_events"
                ),
                upload_files_request = null,  // OpenRouter uses inline base64 for images
                upload_files_response_important_fields = null
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
                android.util.Log.e("DataRepository", "Failed to load chat history", e)
                UserChatHistory(username, emptyList(), emptyList())
            }
        } else {
            android.util.Log.e("DataRepository", "Failed to load chat history, file doesn't exist")
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
    
    fun getChatJson(username: String, chatId: String): String? {
        return try {
            val chat = loadChatHistory(username).chat_history.find { it.chat_id == chatId }
            chat?.let { json.encodeToString(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun saveChatJsonToDownloads(chatId: String, content: String): String? {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val exportFile = File(downloadsDir, "${chatId}.json")
            exportFile.writeText(content)
            exportFile.absolutePath
        } catch (e: Exception) {
            null
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
    
    fun reorderApiKeys(username: String, fromIndex: Int, toIndex: Int) {
        val currentKeys = loadApiKeys(username).toMutableList()
        if (fromIndex in currentKeys.indices && toIndex in currentKeys.indices) {
            val item = currentKeys.removeAt(fromIndex)
            currentKeys.add(toIndex, item)
            saveApiKeys(username, currentKeys)
        }
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
        projectAttachments: List<Attachment> = emptyList(),
        webSearchEnabled: Boolean = false,
        enabledTools: List<ToolSpecification> = emptyList(),
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
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

        apiService.sendMessage(provider, modelName, finalMessages, systemPrompt, apiKeys, webSearchEnabled, enabledTools, thinkingBudget, callback)
    }

    // Check and upload project files for current provider
    private suspend fun ensureProjectFilesUploadedForProvider(
        provider: Provider,
        projectAttachments: List<Attachment>,
        username: String
    ): List<Attachment> {
        if (projectAttachments.isEmpty()) return emptyList()

        val updatedAttachments = mutableListOf<Attachment>()

        for (attachment in projectAttachments) {
            val needsUpload = when (provider.provider) {
                "openai" -> attachment.file_OPENAI_id == null
                "poe" -> attachment.file_POE_url == null
                "google" -> attachment.file_GOOGLE_uri == null
                else -> false
            }

            if (needsUpload && attachment.local_file_path != null) {
                // Need to upload file for current provider
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
                } else {
                    // Upload failed, keep original attachment
                    updatedAttachments.add(attachment)
                }
            } else {
                // File already uploaded for this provider or no local path
                updatedAttachments.add(attachment)
            }
        }

        return updatedAttachments
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

    /**
     * Validates if JSON content represents a valid chat export.
     * Returns true if the JSON can be parsed as Chat or UserChatHistory.
     */
    fun validateChatJson(jsonContent: String): Boolean {
        return try {
            // Try parsing as a single Chat first
            json.decodeFromString<Chat>(jsonContent)
            true
        } catch (e: Exception) {
            try {
                // Try parsing as UserChatHistory
                json.decodeFromString<UserChatHistory>(jsonContent)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * Import a single chat from JSON content. Sanitizes attachments.
     * Returns the imported chat ID on success, null on failure.
     */
    fun importSingleChat(jsonContent: String, targetUsername: String): String? {
        return try {
            // Try parsing as a single Chat first
            val chat = try {
                json.decodeFromString<Chat>(jsonContent)
            } catch (e: Exception) {
                // If not a single chat, try as UserChatHistory and take first chat
                val history = json.decodeFromString<UserChatHistory>(jsonContent)
                history.chat_history.firstOrNull() ?: return null
            }

            // Sanitize: remove attachments
            val sanitizedMessages = chat.messages.map { msg ->
                msg.copy(attachments = emptyList())
            }
            val sanitizedChat = chat.copy(messages = sanitizedMessages)

            // Load current chat history
            val currentHistory = loadChatHistory(targetUsername)

            // Add the imported chat to history
            val updatedHistory = currentHistory.copy(
                chat_history = currentHistory.chat_history + sanitizedChat
            )

            saveChatHistory(updatedHistory)
            sanitizedChat.chat_id
        } catch (e: Exception) {
            println("Failed to import chat: ${e.message}")
            null
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
            
            // Make API call with streaming callback that collects full response
            var fullResponse = ""
            var responseError: String? = null

            val callback = object : StreamingCallback {
                override fun onPartialResponse(text: String) {
                    fullResponse += text
                }

                override fun onComplete(fullText: String) {
                    fullResponse = fullText
                }

                override fun onError(error: String) {
                    responseError = error
                }
            }

            apiService.sendMessage(
                provider = selectedProvider,
                modelName = modelName,
                messages = promptMessages,
                systemPrompt = "",
                apiKeys = apiKeys,
                webSearchEnabled = false, // No web search for title generation
                enabledTools = emptyList(), // No tools for title generation
                callback = callback
            )

            // Check if we got an error
            if (responseError != null) {
                return@withContext "שיחה חדשה"
            }
            
            // Extract title from the full response text
            val extractedTitle = fullResponse.trim().takeIf { it.isNotBlank() }
            
            // Return extracted title or default
            return@withContext extractedTitle?.takeIf { it.isNotBlank() } ?: "שיחה חדשה"
            
        } catch (e: Exception) {
            // If anything fails, return default
            return@withContext "שיחה חדשה"
        }
    }
    
    /**
     * Select provider and model based on priority and availability
     * Uses cheapest models for title generation
     */
    private fun selectProviderAndModel(
        preferredProvider: String?,
        availableProviders: List<Provider>,
        apiKeys: Map<String, String>
    ): Pair<Provider, String>? {
        // Map providers to their cheapest models for title generation
        val cheapestModels = mapOf(
            "openai" to "gpt-5-nano",
            "google" to "gemini-2.5-flash-lite",
            "poe" to "GPT-OSS-120B-T",
            "anthropic" to "claude-3-haiku-20240307",
            "cohere" to "command-r7b-12-2024",
            "openrouter" to "meta-llama/llama-3.1-8b-instruct"
        )

        // Helper to pick the cheapest model for a provider
        fun cheapestModelName(p: Provider): String? {
            val cheapest = cheapestModels[p.provider]
            // Check if the cheapest model exists in the provider's models
            val hasModel = p.models.any { it.name == cheapest }
            return if (hasModel) cheapest else p.models.firstOrNull()?.name
        }

        // If preferred provider is specified, try to use it when key exists
        if (preferredProvider != null && apiKeys.containsKey(preferredProvider)) {
            val provider = availableProviders.find { it.provider == preferredProvider }
            val model = provider?.let { cheapestModelName(it) }
            if (provider != null && model != null) return Pair(provider, model)
        }

        // Priority order: OpenAI > Google > Anthropic > Cohere > OpenRouter > POE
        val priorityOrder = listOf("openai", "google", "anthropic", "cohere", "openrouter", "poe")
        for (providerName in priorityOrder) {
            if (!apiKeys.containsKey(providerName)) continue
            val provider = availableProviders.find { it.provider == providerName } ?: continue
            val model = cheapestModelName(provider) ?: continue
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
    
    /**
     * Search chats by title and content with highlighting information
     */
    fun searchChats(username: String, query: String): List<com.example.ApI.data.model.SearchResult> {
        if (query.isBlank()) return emptyList()
        
        val chatHistory = loadChatHistory(username)
        val lowercaseQuery = query.lowercase()
        
        // Search results divided into categories
        val titleMatches = mutableListOf<com.example.ApI.data.model.SearchResult>()
        val contentMatches = mutableListOf<com.example.ApI.data.model.SearchResult>()
        val fileMatches = mutableListOf<com.example.ApI.data.model.SearchResult>()
        
        for (chat in chatHistory.chat_history) {
            var foundInTitle = false
            
            // Check title match - use preview_name as the title
            val titleText = chat.preview_name ?: "שיחה חדשה"
            val titleLowercase = titleText.lowercase()
            if (titleLowercase.contains(lowercaseQuery)) {
                val highlightRanges = findAllOccurrences(titleLowercase, lowercaseQuery)
                titleMatches.add(com.example.ApI.data.model.SearchResult(
                    chat = chat,
                    searchQuery = query,
                    matchType = com.example.ApI.data.model.SearchMatchType.TITLE,
                    highlightRanges = highlightRanges
                ))
                foundInTitle = true
            }
            
            // Check content and file name matches if not found in title
            if (!foundInTitle) {
                for ((messageIndex, message) in chat.messages.withIndex()) {
                    var foundInThisMessage = false
                    
                    // Check message text
                    val messageLowercase = message.text.lowercase()
                    if (messageLowercase.contains(lowercaseQuery)) {
                        val highlightRanges = findAllOccurrences(messageLowercase, lowercaseQuery)
                        contentMatches.add(com.example.ApI.data.model.SearchResult(
                            chat = chat,
                            searchQuery = query,
                            matchType = com.example.ApI.data.model.SearchMatchType.CONTENT,
                            messageIndex = messageIndex,
                            highlightRanges = highlightRanges
                        ))
                        foundInThisMessage = true
                        break
                    }
                    
                    // Check attachment file names
                    if (!foundInThisMessage) {
                        for (attachment in message.attachments) {
                            val fileNameLowercase = attachment.file_name.lowercase()
                            if (fileNameLowercase.contains(lowercaseQuery)) {
                                val highlightRanges = findAllOccurrences(fileNameLowercase, lowercaseQuery)
                                fileMatches.add(com.example.ApI.data.model.SearchResult(
                                    chat = chat,
                                    searchQuery = query,
                                    matchType = com.example.ApI.data.model.SearchMatchType.FILE_NAME,
                                    messageIndex = messageIndex,
                                    highlightRanges = highlightRanges
                                ))
                                foundInThisMessage = true
                                break
                            }
                        }
                    }
                    
                    if (foundInThisMessage) break
                }
            }
        }
        
        // Sort each category by date (most recent first) - using the same logic as main screen
        val sortedTitleMatches = titleMatches.sortedWith(
            compareByDescending<com.example.ApI.data.model.SearchResult> { getLastTimestampOrNull(it.chat) != null }
                .thenByDescending { getLastTimestampOrNull(it.chat) ?: Long.MIN_VALUE }
        )
        
        val sortedContentMatches = contentMatches.sortedWith(
            compareByDescending<com.example.ApI.data.model.SearchResult> { getLastTimestampOrNull(it.chat) != null }
                .thenByDescending { getLastTimestampOrNull(it.chat) ?: Long.MIN_VALUE }
        )
        
        val sortedFileMatches = fileMatches.sortedWith(
            compareByDescending<com.example.ApI.data.model.SearchResult> { getLastTimestampOrNull(it.chat) != null }
                .thenByDescending { getLastTimestampOrNull(it.chat) ?: Long.MIN_VALUE }
        )
        
        // Return title matches first, then content matches, then file matches
        return sortedTitleMatches + sortedContentMatches + sortedFileMatches
    }
    
    /**
     * Find all occurrences of a query in a text and return their ranges
     */
    private fun findAllOccurrences(text: String, query: String): List<IntRange> {
        if (query.isBlank() || text.isBlank()) return emptyList()
        
        val ranges = mutableListOf<IntRange>()
        var startIndex = 0
        
        while (true) {
            val index = text.indexOf(query, startIndex, ignoreCase = true)
            if (index == -1) break
            
            ranges.add(IntRange(index, index + query.length - 1))
            startIndex = index + 1
        }
        
        return ranges
    }
    
    // Helper function to get last timestamp from a chat (similar to the one in ChatHistoryScreen)
    private fun getLastTimestampOrNull(chat: Chat): Long? {
        val iso = chat.messages.lastOrNull()?.datetime ?: return null
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    // ==================== GitHub Integration ====================

    /**
     * Load GitHub connection for a user
     * @param username The app username
     * @return GitHubConnection or null if not connected
     */
    fun loadGitHubConnection(username: String): GitHubConnection? {
        return try {
            val file = File(internalDir, "github_auth_${username}.json")
            if (!file.exists()) return null

            val jsonString = file.readText()
            json.decodeFromString<GitHubConnection>(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Save GitHub connection for a user
     * @param username The app username
     * @param connection The GitHub connection to save
     */
    fun saveGitHubConnection(username: String, connection: GitHubConnection) {
        try {
            val file = File(internalDir, "github_auth_${username}.json")
            val jsonString = json.encodeToString(connection)
            file.writeText(jsonString)

            // Update app settings to track connection
            val settings = loadAppSettings()
            val updatedConnections = settings.githubConnections.toMutableMap()
            updatedConnections[username] = GitHubConnectionInfo(
                username = username,
                githubUsername = connection.user.login,
                connectedAt = connection.connectedAt,
                lastUsed = System.currentTimeMillis()
            )
            val updatedSettings = settings.copy(githubConnections = updatedConnections)
            saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Remove GitHub connection for a user
     * @param username The app username
     */
    fun removeGitHubConnection(username: String) {
        try {
            // Delete the auth file
            val file = File(internalDir, "github_auth_${username}.json")
            if (file.exists()) {
                file.delete()
            }

            // Update app settings
            val settings = loadAppSettings()
            val updatedConnections = settings.githubConnections.toMutableMap()
            updatedConnections.remove(username)
            val updatedSettings = settings.copy(githubConnections = updatedConnections)
            saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Check if GitHub is connected for a user
     * @param username The app username
     * @return true if connected and auth is valid
     */
    fun isGitHubConnected(username: String): Boolean {
        val connection = loadGitHubConnection(username) ?: return false
        // Check if token is expired (if applicable)
        return !connection.auth.isExpired()
    }

    /**
     * Get GitHub API service with the user's access token
     * @param username The app username
     * @return GitHubApiService or null if not connected
     */
    fun getGitHubApiService(username: String): Pair<com.example.ApI.data.network.GitHubApiService, String>? {
        val connection = loadGitHubConnection(username) ?: return null
        if (connection.auth.isExpired()) return null

        val apiService = com.example.ApI.data.network.GitHubApiService()
        return Pair(apiService, connection.auth.accessToken)
    }

    /**
     * Update the last used timestamp for GitHub connection
     * @param username The app username
     */
    fun updateGitHubLastUsed(username: String) {
        try {
            val settings = loadAppSettings()
            val connectionInfo = settings.githubConnections[username] ?: return

            val updatedConnections = settings.githubConnections.toMutableMap()
            updatedConnections[username] = connectionInfo.copy(lastUsed = System.currentTimeMillis())

            val updatedSettings = settings.copy(githubConnections = updatedConnections)
            saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== Google Workspace Integration ====================

    /**
     * Load Google Workspace connection for a user
     * @param username The app username
     * @return GoogleWorkspaceConnection or null if not connected
     */
    fun loadGoogleWorkspaceConnection(username: String): com.example.ApI.data.model.GoogleWorkspaceConnection? {
        return try {
            val file = File(internalDir, "google_workspace_auth_${username}.json")
            if (!file.exists()) return null

            val jsonString = file.readText()
            json.decodeFromString<com.example.ApI.data.model.GoogleWorkspaceConnection>(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Save Google Workspace connection for a user
     * @param username The app username
     * @param connection The Google Workspace connection to save
     */
    fun saveGoogleWorkspaceConnection(username: String, connection: com.example.ApI.data.model.GoogleWorkspaceConnection) {
        try {
            val file = File(internalDir, "google_workspace_auth_${username}.json")
            val jsonString = json.encodeToString(connection)
            file.writeText(jsonString)

            // Update app settings to track connection
            val settings = loadAppSettings()
            val updatedConnections = settings.googleWorkspaceConnections.toMutableMap()
            updatedConnections[username] = com.example.ApI.data.model.GoogleWorkspaceConnectionInfo(
                username = username,
                googleEmail = connection.user.email,
                connectedAt = connection.connectedAt,
                lastUsed = System.currentTimeMillis()
            )
            val updatedSettings = settings.copy(googleWorkspaceConnections = updatedConnections)
            saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Remove Google Workspace connection for a user
     * @param username The app username
     */
    fun removeGoogleWorkspaceConnection(username: String) {
        try {
            // Delete the auth file
            val file = File(internalDir, "google_workspace_auth_${username}.json")
            if (file.exists()) {
                file.delete()
            }

            // Update app settings
            val settings = loadAppSettings()
            val updatedConnections = settings.googleWorkspaceConnections.toMutableMap()
            updatedConnections.remove(username)
            val updatedSettings = settings.copy(googleWorkspaceConnections = updatedConnections)
            saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Check if Google Workspace is connected for a user
     * @param username The app username
     * @return true if connected and auth is valid
     */
    fun isGoogleWorkspaceConnected(username: String): Boolean {
        val connection = loadGoogleWorkspaceConnection(username) ?: return false
        // Check if token is expired
        return !connection.auth.isExpired()
    }

    /**
     * Update enabled services for Google Workspace connection
     * @param username The app username
     * @param services Enabled services configuration
     */
    fun updateGoogleWorkspaceEnabledServices(username: String, services: com.example.ApI.data.model.EnabledGoogleServices) {
        try {
            val connection = loadGoogleWorkspaceConnection(username) ?: return
            val updatedConnection = connection.copy(enabledServices = services)
            saveGoogleWorkspaceConnection(username, updatedConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Get Google Workspace API services with the user's access token
     * @param username The app username
     * @return Triple of (GmailApiService?, GoogleCalendarApiService?, GoogleDriveApiService?) or null if not connected
     */
    fun getGoogleWorkspaceApiServices(username: String): Triple<com.example.ApI.data.network.GmailApiService?, com.example.ApI.data.network.GoogleCalendarApiService?, com.example.ApI.data.network.GoogleDriveApiService?>? {
        val connection = loadGoogleWorkspaceConnection(username) ?: return null
        if (connection.auth.isExpired()) return null

        val accessToken = connection.auth.accessToken
        val userEmail = connection.user.email

        val gmailService = if (connection.enabledServices.gmail) {
            com.example.ApI.data.network.GmailApiService(accessToken, userEmail)
        } else null

        val calendarService = if (connection.enabledServices.calendar) {
            com.example.ApI.data.network.GoogleCalendarApiService(accessToken, userEmail)
        } else null

        val driveService = if (connection.enabledServices.drive) {
            com.example.ApI.data.network.GoogleDriveApiService(accessToken, userEmail)
        } else null

        return Triple(gmailService, calendarService, driveService)
    }

    /**
     * Update the last used timestamp for Google Workspace connection
     * @param username The app username
     */
    fun updateGoogleWorkspaceLastUsed(username: String) {
        try {
            val settings = loadAppSettings()
            val connectionInfo = settings.googleWorkspaceConnections[username] ?: return

            val updatedConnections = settings.googleWorkspaceConnections.toMutableMap()
            updatedConnections[username] = connectionInfo.copy(lastUsed = System.currentTimeMillis())

            val updatedSettings = settings.copy(googleWorkspaceConnections = updatedConnections)
            saveAppSettings(updatedSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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


