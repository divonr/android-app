package com.example.ApI.data.network

import android.content.Context
import com.example.ApI.data.model.*
import com.example.ApI.data.model.CustomProviderConfig
import com.example.ApI.data.network.providers.*
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main API service that coordinates message sending across different LLM providers.
 *
 * This is a thin coordinator that delegates to provider-specific implementations:
 * - OpenAI: OpenAIProvider
 * - Google Gemini: GoogleProvider
 * - Anthropic Claude: AnthropicProvider
 * - Poe: PoeProvider
 * - Cohere: CohereProvider
 * - OpenRouter: OpenRouterProvider
 *
 * Each provider handles its own:
 * - Request building and formatting
 * - Streaming response parsing
 * - Tool call execution and chaining
 * - Thinking/reasoning support
 */
class LLMApiService(private val context: Context) {

    // Lazy-initialized provider instances
    private val openAIProvider by lazy { OpenAIProvider(context) }
    private val googleProvider by lazy { GoogleProvider(context) }
    private val anthropicProvider by lazy { AnthropicProvider(context) }
    private val poeProvider by lazy { PoeProvider(context) }
    private val cohereProvider by lazy { CohereProvider(context) }
    private val openRouterProvider by lazy { OpenRouterProvider(context) }
    private val llmStatsProvider by lazy { LLMStatsProvider(context) }

    // Cache for dynamic custom providers (by providerKey)
    private val customProviderCache = mutableMapOf<String, DynamicCustomProvider>()

    /**
     * Registers a custom provider configuration.
     * Creates a DynamicCustomProvider instance and caches it.
     */
    fun registerCustomProvider(config: CustomProviderConfig) {
        customProviderCache[config.providerKey] = DynamicCustomProvider(context, config)
    }

    /**
     * Unregisters a custom provider by its key.
     */
    fun unregisterCustomProvider(providerKey: String) {
        customProviderCache.remove(providerKey)
    }

    /**
     * Clears and reloads all custom providers.
     * Should be called when custom providers are updated.
     */
    fun reloadCustomProviders(configs: List<CustomProviderConfig>) {
        customProviderCache.clear()
        configs.forEach { config ->
            customProviderCache[config.providerKey] = DynamicCustomProvider(context, config)
        }
    }

    /**
     * Send a message to the specified LLM provider.
     *
     * @param provider The provider configuration from providers.json
     * @param modelName The model to use (e.g., "gpt-4", "claude-3-opus")
     * @param messages The conversation history
     * @param systemPrompt Optional system prompt for the conversation
     * @param apiKeys Map of provider names to API keys
     * @param webSearchEnabled Whether to enable web search capability
     * @param enabledTools List of tool specifications for function calling
     * @param thinkingBudget Thinking/reasoning budget configuration
     * @param temperature Optional temperature parameter for response randomness
     * @param callback Callback for streaming responses, errors, and tool calls
     */
    suspend fun sendMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKeys: Map<String, String>,
        webSearchEnabled: Boolean = false,
        enabledTools: List<ToolSpecification> = emptyList(),
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
        temperature: Float? = null,
        callback: StreamingCallback
    ): Unit = withContext(Dispatchers.IO) {
        // Get the API key for this provider
        val apiKey = apiKeys[provider.provider]

        if (apiKey == null) {
            callback.onError("${provider.provider.replaceFirstChar { it.uppercase() }} API key is required")
            return@withContext
        }

        // Delegate to the appropriate provider
        when (provider.provider) {
            "openai" -> openAIProvider.sendMessage(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, temperature, callback
            )
            "google" -> googleProvider.sendMessage(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, temperature, callback
            )
            "anthropic" -> anthropicProvider.sendMessage(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, temperature, callback
            )
            "poe" -> poeProvider.sendMessage(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, temperature, callback
            )
            "cohere" -> cohereProvider.sendMessage(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, temperature, callback
            )
            "openrouter" -> openRouterProvider.sendMessage(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, temperature, callback
            )
            "llmstats" -> llmStatsProvider.sendMessage(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, temperature, callback
            )
            else -> {
                // Check if it's a custom provider
                val customProvider = customProviderCache[provider.provider]
                if (customProvider != null) {
                    customProvider.sendMessage(
                        provider, modelName, messages, systemPrompt, apiKey,
                        webSearchEnabled, enabledTools, thinkingBudget, temperature, callback
                    )
                } else {
                    callback.onError("Unknown provider: ${provider.provider}")
                }
            }
        }
    }
}

/**
 * Common response type for non-streaming API calls
 */
sealed class ApiResponse {
    data class Success(val message: String) : ApiResponse()
    data class Error(val error: String) : ApiResponse()
}
