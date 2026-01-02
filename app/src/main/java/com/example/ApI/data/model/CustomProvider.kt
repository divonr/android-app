package com.example.ApI.data.model

import kotlinx.serialization.Serializable

/**
 * Configuration for a user-defined custom OpenAI-compatible provider.
 * Stored in custom_providers_{username}.json
 */
@Serializable
data class CustomProviderConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,                                        // Display name (e.g., "My Local LLM")
    val providerKey: String,                                 // Internal key (e.g., "custom_my_local_llm")
    val baseUrl: String,                                     // API endpoint (e.g., "http://localhost:1234/v1/chat/completions")
    val defaultModel: String,                                // Default model name (e.g., "local-model")
    val authHeaderName: String = "Authorization",            // Header name for API key
    val authHeaderFormat: String = "Bearer {key}",           // Format string ({key} placeholder)
    val extraHeaders: Map<String, String> = emptyMap(),      // Additional headers
    val createdAt: Long = System.currentTimeMillis(),
    val isEnabled: Boolean = true
)

/**
 * Generates a unique provider key from the display name.
 * The key is prefixed with "custom_" to avoid collision with built-in providers.
 */
fun generateProviderKey(name: String): String {
    val sanitized = name.lowercase()
        .replace(Regex("[^a-z0-9]"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
    return "custom_$sanitized"
}

/**
 * Checks if a provider key represents a custom provider.
 */
fun isCustomProvider(providerKey: String): Boolean {
    return providerKey.startsWith("custom_")
}

/**
 * Extracts a display-friendly name from a custom provider key.
 */
fun getDisplayNameFromProviderKey(providerKey: String): String {
    return if (providerKey.startsWith("custom_")) {
        providerKey.removePrefix("custom_")
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    } else {
        providerKey
    }
}
