package com.example.ApI.data.repository

import com.example.ApI.data.model.*
import com.example.ApI.data.network.LLMApiService
import com.example.ApI.data.network.ApiResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Service for generating conversation titles using LLM APIs.
 */
class TitleGenerationService(
    private val json: Json,
    private val apiService: LLMApiService,
    private val loadChatHistory: (String) -> UserChatHistory,
    private val loadProviders: () -> List<Provider>,
    private val loadApiKeys: (String) -> List<ApiKey>
) {
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
}
