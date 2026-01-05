package com.example.ApI.util

import com.example.ApI.data.model.BodyTemplatePlaceholders
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Utility for expanding body templates with actual values.
 * Handles placeholder substitution and message history pattern detection/expansion.
 */
object TemplateExpander {

    /**
     * Result of template validation
     */
    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class MissingPlaceholders(val missing: Set<String>) : ValidationResult()
    }

    /**
     * Detected message pattern from a template
     */
    data class MessagePattern(
        val userTemplate: String,       // JSON object containing {prompt}
        val assistantTemplate: String,  // JSON object containing {assistant}
        val systemTemplate: String?     // JSON object containing {system}, if present
    )

    /**
     * Validates that all required placeholders are present in the template.
     */
    fun validateTemplate(template: String): ValidationResult {
        val missingPlaceholders = BodyTemplatePlaceholders.REQUIRED.filter {
            !template.contains(it)
        }.toSet()

        return if (missingPlaceholders.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.MissingPlaceholders(missingPlaceholders)
        }
    }

    /**
     * Finds all placeholders present in the template.
     */
    fun findPresentPlaceholders(template: String): Set<String> {
        return BodyTemplatePlaceholders.ALL.filter { template.contains(it) }.toSet()
    }

    /**
     * Detects the message pattern in the template by finding JSON objects
     * containing {prompt}, {assistant}, and optionally {system}.
     *
     * This searches through the template to find array elements that contain
     * the respective placeholders.
     */
    fun detectMessagePattern(template: String): MessagePattern? {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(template).jsonObject

            // Find the messages array - typically named "messages", "contents", or similar
            val messagesArray = findMessagesArray(root)
            if (messagesArray == null || messagesArray.isEmpty()) return null

            var userTemplate: String? = null
            var assistantTemplate: String? = null
            var systemTemplate: String? = null

            for (element in messagesArray) {
                val elementStr = element.toString()
                when {
                    elementStr.contains(BodyTemplatePlaceholders.PROMPT) -> {
                        userTemplate = elementStr
                    }
                    elementStr.contains(BodyTemplatePlaceholders.ASSISTANT) -> {
                        assistantTemplate = elementStr
                    }
                    elementStr.contains(BodyTemplatePlaceholders.SYSTEM) -> {
                        systemTemplate = elementStr
                    }
                }
            }

            if (userTemplate != null && assistantTemplate != null) {
                return MessagePattern(userTemplate, assistantTemplate, systemTemplate)
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Finds the messages array in the JSON object.
     * Looks for common names like "messages", "contents", "prompt".
     */
    private fun findMessagesArray(obj: JsonObject): JsonArray? {
        val commonNames = listOf("messages", "contents", "content", "prompt", "input")
        for (name in commonNames) {
            val element = obj[name]
            if (element is JsonArray) return element
        }
        // If not found by common names, search for any array containing placeholders
        for ((_, value) in obj) {
            if (value is JsonArray) {
                val arrayStr = value.toString()
                if (arrayStr.contains(BodyTemplatePlaceholders.PROMPT) ||
                    arrayStr.contains(BodyTemplatePlaceholders.ASSISTANT)) {
                    return value
                }
            }
        }
        return null
    }

    /**
     * Expands a body template with actual values.
     *
     * @param template The body template with placeholders
     * @param apiKey The API key to substitute for {key}
     * @param model The model name to substitute for {model}
     * @param messages The conversation history
     * @param systemPrompt The system prompt (may be empty)
     * @param tools List of enabled tools (may be empty)
     * @param thoughts Previous thinking content (may be null)
     * @param thoughtsSignature Thoughts signature for continuity (may be null)
     * @return The expanded body string ready for API request
     */
    fun expandTemplate(
        template: String,
        apiKey: String,
        model: String,
        messages: List<Message>,
        systemPrompt: String,
        tools: List<ToolSpecification> = emptyList(),
        thoughts: String? = null,
        thoughtsSignature: String? = null
    ): String {
        val pattern = detectMessagePattern(template)

        if (pattern == null) {
            // Fallback: simple replacement without message expansion
            return simpleSubstitution(template, apiKey, model,
                messages.lastOrNull { !it.isUser }?.content ?: "",
                messages.lastOrNull { it.isUser }?.content ?: "",
                systemPrompt, tools, thoughts, thoughtsSignature)
        }

        // Build the messages array
        val messagesJson = buildMessagesArray(pattern, messages, systemPrompt)

        // Find and replace the original messages array in template
        val expandedTemplate = replaceMessagesArrayInTemplate(template, pattern, messagesJson)

        // Now do simple substitutions for remaining placeholders
        return simpleSubstitution(expandedTemplate, apiKey, model,
            "", "", systemPrompt, tools, thoughts, thoughtsSignature)
    }

    /**
     * Builds the expanded messages array from the pattern and history.
     */
    private fun buildMessagesArray(
        pattern: MessagePattern,
        messages: List<Message>,
        systemPrompt: String
    ): String {
        val result = StringBuilder()
        result.append("[")

        var first = true

        // Add system message if present in pattern and system prompt is not empty
        if (pattern.systemTemplate != null && systemPrompt.isNotBlank()) {
            val systemMsg = pattern.systemTemplate.replace(BodyTemplatePlaceholders.SYSTEM, escapeJsonString(systemPrompt))
            result.append(systemMsg)
            first = false
        }

        // Add user/assistant messages from history
        for (message in messages) {
            if (!first) result.append(",")
            first = false

            if (message.isUser) {
                val userMsg = pattern.userTemplate.replace(
                    BodyTemplatePlaceholders.PROMPT,
                    escapeJsonString(message.content)
                )
                result.append(userMsg)
            } else {
                val assistantMsg = pattern.assistantTemplate.replace(
                    BodyTemplatePlaceholders.ASSISTANT,
                    escapeJsonString(message.content)
                )
                result.append(assistantMsg)
            }
        }

        result.append("]")
        return result.toString()
    }

    /**
     * Replaces the messages array in the template with the expanded version.
     */
    private fun replaceMessagesArrayInTemplate(
        template: String,
        pattern: MessagePattern,
        expandedMessages: String
    ): String {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(template).jsonObject.toMutableMap()

            // Find which key contains the messages array
            val commonNames = listOf("messages", "contents", "content", "prompt", "input")
            for (name in commonNames) {
                val element = root[name]
                if (element is JsonArray) {
                    val arrayStr = element.toString()
                    if (arrayStr.contains(BodyTemplatePlaceholders.PROMPT) ||
                        arrayStr.contains(BodyTemplatePlaceholders.ASSISTANT)) {
                        // Replace this array with our expanded version
                        root[name] = json.parseToJsonElement(expandedMessages)
                        return Json.encodeToString(JsonObject.serializer(), JsonObject(root))
                    }
                }
            }

            // Fallback: search any array
            for ((key, value) in root) {
                if (value is JsonArray) {
                    val arrayStr = value.toString()
                    if (arrayStr.contains(BodyTemplatePlaceholders.PROMPT) ||
                        arrayStr.contains(BodyTemplatePlaceholders.ASSISTANT)) {
                        root[key] = json.parseToJsonElement(expandedMessages)
                        return Json.encodeToString(JsonObject.serializer(), JsonObject(root))
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback on error
        }
        return template
    }

    /**
     * Simple placeholder substitution for non-message placeholders.
     */
    private fun simpleSubstitution(
        template: String,
        apiKey: String,
        model: String,
        assistantContent: String,
        promptContent: String,
        systemPrompt: String,
        tools: List<ToolSpecification>,
        thoughts: String?,
        thoughtsSignature: String?
    ): String {
        var result = template

        result = result.replace(BodyTemplatePlaceholders.KEY, apiKey)
        result = result.replace(BodyTemplatePlaceholders.MODEL, model)

        // These may remain if not in a messages context
        if (result.contains(BodyTemplatePlaceholders.PROMPT)) {
            result = result.replace(BodyTemplatePlaceholders.PROMPT, escapeJsonString(promptContent))
        }
        if (result.contains(BodyTemplatePlaceholders.ASSISTANT)) {
            result = result.replace(BodyTemplatePlaceholders.ASSISTANT, escapeJsonString(assistantContent))
        }
        if (result.contains(BodyTemplatePlaceholders.SYSTEM)) {
            result = result.replace(BodyTemplatePlaceholders.SYSTEM, escapeJsonString(systemPrompt))
        }

        // Optional placeholders
        if (thoughts != null && result.contains(BodyTemplatePlaceholders.THOUGHTS)) {
            result = result.replace(BodyTemplatePlaceholders.THOUGHTS, escapeJsonString(thoughts))
        } else {
            result = result.replace(BodyTemplatePlaceholders.THOUGHTS, "")
        }

        if (thoughtsSignature != null && result.contains(BodyTemplatePlaceholders.THOUGHTS_SIGNATURE)) {
            result = result.replace(BodyTemplatePlaceholders.THOUGHTS_SIGNATURE, escapeJsonString(thoughtsSignature))
        } else {
            result = result.replace(BodyTemplatePlaceholders.THOUGHTS_SIGNATURE, "")
        }

        // Tool placeholders - for now, just clear them if tools are empty
        // Full tool support would require more complex logic
        if (tools.isEmpty()) {
            result = result.replace(BodyTemplatePlaceholders.TOOL_NAME, "")
            result = result.replace(BodyTemplatePlaceholders.TOOL_DESCRIPTION, "")
            result = result.replace(BodyTemplatePlaceholders.TOOL_PARAMETERS, "")
            result = result.replace(BodyTemplatePlaceholders.TOOL_RESPONSE, "")
        }

        return result
    }

    /**
     * Escapes a string for use in JSON.
     */
    private fun escapeJsonString(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Extracts a value from a JsonObject using dot notation path.
     * e.g., "delta.text" extracts obj["delta"]["text"]
     */
    fun extractByPath(json: JsonObject, path: String): String? {
        val parts = path.split(".")
        var current: JsonElement = json

        for (part in parts) {
            current = when {
                current is JsonObject -> current[part] ?: return null
                current is JsonArray && part.toIntOrNull() != null -> {
                    val index = part.toInt()
                    if (index < current.size) current[index] else return null
                }
                else -> return null
            }
        }

        return when {
            current is JsonPrimitive -> current.contentOrNull
            else -> current.toString()
        }
    }
}
