package com.example.ApI.data.repository

import com.example.ApI.data.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages remote models fetching and caching.
 * Handles fetching models from GitHub and caching them locally.
 */
class ModelsCacheManager(
    private val internalDir: File,
    private val json: Json
) {
    companion object {
        private const val MODELS_JSON_URL = "https://raw.githubusercontent.com/divonr/android-app/main/models.json"
        private const val MODELS_CACHE_FILE = "models_cache.json"
        private const val MODELS_CACHE_METADATA_FILE = "models_cache_metadata.json"
        private const val CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
    }

    // ============ Cache Metadata ============

    /**
     * Loads the cached models metadata to check last fetch time
     */
    private fun loadModelsCacheMetadata(): ModelsCacheMetadata? {
        val file = File(internalDir, MODELS_CACHE_METADATA_FILE)
        return if (file.exists()) {
            try {
                json.decodeFromString<ModelsCacheMetadata>(file.readText())
            } catch (e: Exception) {
                android.util.Log.e("ModelsCacheManager", "Failed to load models cache metadata", e)
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
            android.util.Log.e("ModelsCacheManager", "Failed to save models cache metadata", e)
        }
    }

    // ============ Cache Storage ============

    /**
     * Loads cached models from local storage
     */
    private fun loadCachedModels(): List<RemoteProviderModels>? {
        val file = File(internalDir, MODELS_CACHE_FILE)
        return if (file.exists()) {
            try {
                json.decodeFromString<List<RemoteProviderModels>>(file.readText())
            } catch (e: Exception) {
                android.util.Log.e("ModelsCacheManager", "Failed to load cached models", e)
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
            android.util.Log.e("ModelsCacheManager", "Failed to save cached models", e)
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
     * Gets the cached remote models, or null if not available
     */
    private fun getRemoteModels(): List<RemoteProviderModels>? {
        return loadCachedModels()
    }

    // ============ Remote Fetching ============

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
                android.util.Log.i("ModelsCacheManager", "Successfully fetched ${models.size} providers from remote")
                models
            } else {
                android.util.Log.e("ModelsCacheManager", "Failed to fetch models: HTTP $responseCode")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("ModelsCacheManager", "Failed to fetch models from remote", e)
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
            android.util.Log.i("ModelsCacheManager", "Models cache is still valid, skipping refresh")
            return@withContext false
        }

        android.util.Log.i("ModelsCacheManager", "Models cache expired or missing, fetching from remote...")
        val remoteModels = fetchModelsFromRemote()

        if (remoteModels != null) {
            saveCachedModels(remoteModels)
            saveModelsCacheMetadata()
            android.util.Log.i("ModelsCacheManager", "Models cache updated successfully")
            return@withContext true
        }

        android.util.Log.w("ModelsCacheManager", "Failed to refresh models, will use existing cache or defaults")
        return@withContext false
    }

    /**
     * Force refresh models from remote regardless of cache status
     * Returns a Pair of (success: Boolean, errorMessage: String?)
     */
    suspend fun forceRefreshModels(): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        android.util.Log.i("ModelsCacheManager", "Force refreshing models from remote...")

        try {
            val url = URL(MODELS_JSON_URL)
            android.util.Log.d("ModelsCacheManager", "Fetching from URL: $url")

            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val responseCode = connection.responseCode
            android.util.Log.d("ModelsCacheManager", "Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val jsonString = response.toString().trim()
                android.util.Log.d("ModelsCacheManager", "Response length: ${jsonString.length}")
                android.util.Log.d("ModelsCacheManager", "First 200 chars: ${jsonString.take(200)}")
                android.util.Log.d("ModelsCacheManager", "Last 200 chars: ${jsonString.takeLast(200)}")

                // Check if response is HTML instead of JSON
                if (jsonString.startsWith("<") || jsonString.contains("<!DOCTYPE") || jsonString.contains("<html")) {
                    val errorMsg = "Received HTML instead of JSON. Check URL and network access."
                    android.util.Log.e("ModelsCacheManager", errorMsg)
                    return@withContext Pair(false, errorMsg)
                }

                val remoteModels = try {
                    json.decodeFromString<List<RemoteProviderModels>>(jsonString)
                } catch (e: Exception) {
                    val errorMsg = "JSON parsing failed: ${e.message}"
                    android.util.Log.e("ModelsCacheManager", errorMsg)
                    android.util.Log.e("ModelsCacheManager", "JSON stacktrace:", e)
                    // Log first part of JSON to see what's wrong
                    android.util.Log.e("ModelsCacheManager", "JSON preview (first 500 chars):\n${jsonString.take(500)}")
                    return@withContext Pair(false, errorMsg)
                }

                android.util.Log.i("ModelsCacheManager", "Successfully parsed ${remoteModels.size} providers from remote")

                saveCachedModels(remoteModels)
                saveModelsCacheMetadata()
                android.util.Log.i("ModelsCacheManager", "Models force refreshed successfully")

                return@withContext Pair(true, null)
            } else {
                val errorMsg = "HTTP $responseCode"
                android.util.Log.e("ModelsCacheManager", "Failed to fetch models: $errorMsg")
                return@withContext Pair(false, errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            android.util.Log.e("ModelsCacheManager", "Failed to fetch models from remote: $errorMsg", e)
            return@withContext Pair(false, errorMsg)
        }
    }

    // ============ Model Conversion ============

    /**
     * Converts remote temperature config to TemperatureConfig
     */
    private fun convertTemperatureConfig(config: RemoteTemperatureConfig?): TemperatureConfig? {
        if (config == null) return null
        return TemperatureConfig(
            min = config.min,
            max = config.max,
            default = config.default,
            step = config.step
        )
    }

    /**
     * Converts remote thinking config to ThinkingBudgetType
     */
    private fun convertThinkingConfig(config: RemoteThinkingConfig?): ThinkingBudgetType? {
        if (config == null) return null

        // Check for discrete config
        config.discrete?.let { discrete ->
            return ThinkingBudgetType.Discrete(
                options = discrete.options,
                default = discrete.default,
                displayNames = mapOf(
                    "none" to "ללא",
                    "minimal" to "מינימלי",
                    "low" to "נמוך",
                    "medium" to "בינוני",
                    "high" to "גבוה",
                    "xhigh" to "גבוה מאוד"
                )
            )
        }

        // Check for continuous config
        config.continuous?.let { continuous ->
            // Calculate default step if not provided
            val step = continuous.step ?: run {
                val range = continuous.max - continuous.min
                when {
                    range >= 100000 -> 1024
                    range >= 10000 -> 256
                    range >= 1000 -> 128
                    else -> 64
                }
            }
            return ThinkingBudgetType.Continuous(
                minTokens = continuous.min,
                maxTokens = continuous.max,
                default = continuous.default,
                step = step,
                supportsOff = continuous.supports_off
            )
        }

        return null
    }

    /**
     * Converts remote models to internal Model format
     */
    private fun remoteModelsToModels(remoteModels: List<RemoteModel>): List<Model> {
        return remoteModels.map { remote ->
            // Check if any pricing info is available (Poe points or USD)
            val hasPricing = remote.min_points != null || remote.points != null ||
                    remote.input_points_per_1k != null || remote.output_points_per_1k != null ||
                    remote.input_price_per_1k != null || remote.output_price_per_1k != null

            // Convert thinking config
            val thinkingConfig = convertThinkingConfig(remote.thinking)
            // Convert temperature config
            val temperatureConfig = convertTemperatureConfig(remote.temperature)

            if (hasPricing) {
                val pricing = ModelPricing(
                    min_points = remote.min_points,
                    points = remote.points,
                    input_points_per_1k = remote.input_points_per_1k,
                    output_points_per_1k = remote.output_points_per_1k,
                    input_price_per_1k = remote.input_price_per_1k,
                    output_price_per_1k = remote.output_price_per_1k
                )
                Model.ComplexModel(
                    name = remote.name,
                    min_points = remote.min_points,
                    pricing = pricing,
                    thinkingConfig = thinkingConfig,
                    temperatureConfig = temperatureConfig,
                    releaseOrder = remote.release_order
                )
            } else {
                Model.SimpleModel(
                    name = remote.name,
                    thinkingConfig = thinkingConfig,
                    temperatureConfig = temperatureConfig,
                    releaseOrder = remote.release_order
                )
            }
        }
    }

    /**
     * Gets models for a specific provider from cache, or returns default models
     */
    fun getModelsForProvider(providerName: String, defaultModels: List<Model>): List<Model> {
        val cachedModels = getRemoteModels()
        val providerModels = cachedModels?.find { it.provider == providerName }

        return if (providerModels != null && providerModels.models.isNotEmpty()) {
            android.util.Log.d("ModelsCacheManager", "Using cached models for $providerName: ${providerModels.models.size} models")
            remoteModelsToModels(providerModels.models)
        } else {
            android.util.Log.d("ModelsCacheManager", "Using default models for $providerName: ${defaultModels.size} models")
            defaultModels
        }
    }

    // ============ Default Models (Fallback) ============

    val defaultOpenAIModels = listOf(
        Model.SimpleModel("gpt-4o"),
        Model.SimpleModel("gpt-4-turbo")
    )

    val defaultAnthropicModels = listOf(
        Model.SimpleModel("claude-sonnet-4-5"),
        Model.SimpleModel("claude-3-7-sonnet-latest")
    )

    val defaultGoogleModels = listOf(
        Model.SimpleModel("gemini-2.5-pro"),
        Model.SimpleModel("gemini-1.5-pro-latest")
    )

    val defaultPoeModels = listOf(
        Model.ComplexModel(name = "GPT-4o", min_points = 250, pricing = PoePricing(min_points = 250)),
        Model.ComplexModel(name = "Claude-Sonnet-3.5", min_points = 270, pricing = PoePricing(min_points = 270))
    )

    val defaultCohereModels = listOf(
        Model.SimpleModel("command-a-03-2025"),
        Model.SimpleModel("command-r-plus-08-2024")
    )

    val defaultOpenRouterModels = listOf(
        Model.SimpleModel("openai/gpt-4o"),
        Model.SimpleModel("anthropic/claude-3.5-sonnet")
    )

    val defaultLLMStatsModels = listOf(
        Model.SimpleModel("gpt-5-nano"),
        Model.SimpleModel("gpt-4o-mini"),
        Model.SimpleModel("claude-3-haiku-20240307")
    )

    // ============ Provider Building ============

    /**
     * Builds the list of all providers with their models
     */
    fun buildProviders(): List<Provider> {
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
            ),
            Provider(
                provider = "llmstats",
                models = getModelsForProvider("llmstats", defaultLLMStatsModels),
                request = ApiRequest(
                    request_type = "POST",
                    base_url = "https://api.zeroeval.com/v1/chat/completions",
                    headers = mapOf(
                        "Authorization" to "Bearer {LLMSTATS_API_KEY_HERE}",
                        "Content-Type" to "application/json"
                    ),
                    body = null
                ),
                response_important_fields = ResponseFields(
                    response_format = "server_sent_events"
                ),
                upload_files_request = null,
                upload_files_response_important_fields = null
            )
        )
    }
}
