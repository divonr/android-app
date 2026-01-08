package com.example.ApI.data.model

import kotlinx.serialization.Serializable

/**
 * Parser type for streaming responses
 */
@Serializable
enum class StreamParserType {
    EVENT_DATA,    // event:/data: pairs (Anthropic, Cohere, Poe style)
    DATA_ONLY      // data: only (OpenAI, Google style)
}

/**
 * Semantic event types that can be mapped to provider-specific events
 */
@Serializable
enum class StreamEventType {
    TEXT_CONTENT,       // Main text content delta
    STREAM_END,         // Stream completion signal
    THINKING_START,     // Start of thinking/reasoning phase
    THINKING_CONTENT,   // Thinking content delta
    TOOL_CALL_START,    // Tool call initialization
    TOOL_CALL_DELTA,    // Tool call argument delta
    TOOL_CALL_END       // Tool call completion
}

/**
 * Mapping from a semantic event to provider-specific event name and field path
 */
@Serializable
data class EventMapping(
    val eventName: String,        // Provider's event name (e.g., "content_block_delta")
    val fieldPath: String         // Dot-notation path to extract value (e.g., "delta.text")
)

/**
 * Configuration for the streaming parser
 */
@Serializable
data class ParserConfig(
    // For EventDataStreamParser
    val stopEvents: List<String> = listOf("message_stop", "done"),

    // For DataOnlyStreamParser
    val eventTypeField: String? = "type",  // null for structure-based (like Google)
    val doneMarker: String = "[DONE]",
    val skipKeepalives: Boolean = false
)

/**
 * Configuration for a single message field injection.
 * Defines where in the body to inject content and the template to use.
 *
 * @param path Dot-notation path where to inject (e.g., "messages", "contents", "system_instruction.parts")
 * @param template The template for each message, containing the appropriate placeholder.
 *                 For user messages: must contain {prompt}
 *                 For assistant messages: must contain {assistant}
 *                 For system messages: must contain {system}
 */
@Serializable
data class MessageFieldConfig(
    val path: String,
    val template: String
)

/**
 * Configuration for all message field injections.
 * When provided, the app will inject messages at the specified paths instead of
 * auto-detecting the messages array in the body template.
 *
 * The system, user, and assistant messages can have different paths if needed,
 * but typically user and assistant share the same path (the messages array).
 */
@Serializable
data class MessageFieldsConfig(
    val systemField: MessageFieldConfig? = null,
    val userField: MessageFieldConfig? = null,
    val assistantField: MessageFieldConfig? = null
) {
    /**
     * Checks if this config has any fields defined.
     */
    fun hasAnyFields(): Boolean = systemField != null || userField != null || assistantField != null
}

/**
 * Full custom provider configuration - extends beyond OpenAI-compatible
 * Stored in full_custom_providers_{username}.json
 */
@Serializable
data class FullCustomProviderConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,                                        // Display name
    val providerKey: String,                                 // Internal key (e.g., "fullcustom_my_provider")
    val baseUrl: String,                                     // API endpoint
    val defaultModel: String,                                // Default model name

    // Auth headers (same as existing CustomProviderConfig)
    val authHeaderName: String = "Authorization",            // Header name for API key
    val authHeaderFormat: String = "Bearer {key}",           // Format string ({key} placeholder)
    val extraHeaders: Map<String, String> = emptyMap(),      // Additional headers

    // Body template with placeholders (for static/hard parts)
    val bodyTemplate: String,

    // Dynamic message field injection configuration
    // When provided, messages are injected at specified paths instead of auto-detection
    val messageFields: MessageFieldsConfig? = null,

    // Streaming configuration
    val parserType: StreamParserType = StreamParserType.DATA_ONLY,
    val parserConfig: ParserConfig = ParserConfig(),
    val eventMappings: Map<StreamEventType, EventMapping> = emptyMap(),

    // Metadata
    val isOpenAICompatible: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val isEnabled: Boolean = true
)

/**
 * Placeholder definitions for body templates
 */
object BodyTemplatePlaceholders {
    // Required placeholders
    const val KEY = "{key}"
    const val MODEL = "{model}"
    const val PROMPT = "{prompt}"        // User message content
    const val ASSISTANT = "{assistant}"  // Assistant message content

    // Optional placeholders
    const val THOUGHTS = "{thoughts}"
    const val THOUGHTS_SIGNATURE = "{thoughts_signature}"
    const val SYSTEM = "{system}"
    const val TOOL_NAME = "{tool_name}"
    const val TOOL_DESCRIPTION = "{tool_description}"
    const val TOOL_PARAMETERS = "{tool_parameters}"
    const val TOOL_RESPONSE = "{tool_response}"

    val REQUIRED = setOf(KEY, MODEL, PROMPT, ASSISTANT)
    val OPTIONAL = setOf(THOUGHTS, THOUGHTS_SIGNATURE, SYSTEM,
                         TOOL_NAME, TOOL_DESCRIPTION, TOOL_PARAMETERS, TOOL_RESPONSE)
    val ALL = REQUIRED + OPTIONAL
}

/**
 * Checks if a provider key represents a full custom provider.
 */
fun isFullCustomProvider(providerKey: String): Boolean {
    return providerKey.startsWith("fullcustom_")
}

/**
 * Generates a unique provider key from the display name.
 * The key is prefixed with "fullcustom_" to distinguish from OpenAI-compatible custom providers.
 */
fun generateFullCustomProviderKey(name: String): String {
    val sanitized = name.lowercase()
        .replace(Regex("[^a-z0-9]"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
    return "fullcustom_$sanitized"
}

/**
 * Extracts a display-friendly name from a full custom provider key.
 */
fun getDisplayNameFromFullCustomProviderKey(providerKey: String): String {
    return if (providerKey.startsWith("fullcustom_")) {
        providerKey.removePrefix("fullcustom_")
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    } else {
        providerKey
    }
}
