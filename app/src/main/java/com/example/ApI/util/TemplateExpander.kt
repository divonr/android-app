package com.example.ApI.util

import com.example.ApI.data.model.BodyTemplatePlaceholders
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.MessageFieldsConfig
import com.example.ApI.tools.ToolSpecification
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
                messages.lastOrNull { it.role != "user" }?.content ?: "",
                messages.lastOrNull { it.role == "user" }?.content ?: "",
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

            if (message.role == "user") {
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

        // Tool placeholders - clear if not in tools context
        if (tools.isEmpty()) {
            result = result.replace(BodyTemplatePlaceholders.TOOL_NAME, "")
            result = result.replace(BodyTemplatePlaceholders.TOOL_ID, "")
            result = result.replace(BodyTemplatePlaceholders.TOOL_DESCRIPTION, "")
            result = result.replace(BodyTemplatePlaceholders.TOOL_PARAMETERS, "")
            result = result.replace(BodyTemplatePlaceholders.TOOL_RESPONSE, "")
        }
        // Individual tool placeholders are expanded per-tool in expandTemplateWithMessageFields

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
     * Expands simple placeholders in a string without JSON escaping.
     * Use this for URL and header value expansion where JSON escaping is not needed.
     *
     * @param template The string containing placeholders
     * @param apiKey The API key to substitute for {key}
     * @param model The model name to substitute for {model}
     * @param systemPrompt The system prompt to substitute for {system} (optional)
     * @return The expanded string with placeholders replaced
     */
    fun expandSimplePlaceholders(
        template: String,
        apiKey: String,
        model: String,
        systemPrompt: String = ""
    ): String {
        var result = template
        result = result.replace(BodyTemplatePlaceholders.KEY, apiKey)
        result = result.replace(BodyTemplatePlaceholders.MODEL, model)
        result = result.replace(BodyTemplatePlaceholders.SYSTEM, systemPrompt)
        return result
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

    // ==================== Path-Based Message Injection ====================

    /**
     * Expands a body template using explicit message field configuration.
     * This is the new approach where the user specifies separate paths and templates
     * for system/user/assistant messages, as well as tool-related fields.
     *
     * @param template The base body template (the "hard" parts that are always sent)
     * @param messageFields Configuration specifying paths and templates for message injection
     * @param apiKey The API key to substitute for {key}
     * @param model The model name to substitute for {model}
     * @param messages The conversation history
     * @param systemPrompt The system prompt (may be empty)
     * @param tools List of enabled tools (may be empty)
     * @param thoughts Previous thinking content (may be null)
     * @param thoughtsSignature Thoughts signature for continuity (may be null)
     * @return The expanded body string ready for API request
     */
    fun expandTemplateWithMessageFields(
        template: String,
        messageFields: MessageFieldsConfig,
        apiKey: String,
        model: String,
        messages: List<Message>,
        systemPrompt: String,
        tools: List<ToolSpecification> = emptyList(),
        thoughts: String? = null,
        thoughtsSignature: String? = null
    ): String {
        val json = Json { ignoreUnknownKeys = true }

        // Parse the base template as JSON
        val rootObject = try {
            json.parseToJsonElement(template).jsonObject.toMutableMap()
        } catch (e: Exception) {
            // If parsing fails, fall back to simple substitution
            return simpleSubstitution(
                template, apiKey, model, "", "", systemPrompt,
                tools, thoughts, thoughtsSignature
            )
        }

        // Collect all messages to inject, grouped by path
        val messagesByPath = mutableMapOf<String, MutableList<String>>()

        // Add system message if configured and system prompt is not empty
        if (messageFields.systemField != null && systemPrompt.isNotBlank()) {
            val systemJson = messageFields.systemField.template.replace(
                BodyTemplatePlaceholders.SYSTEM,
                escapeJsonString(systemPrompt)
            )
            val path = messageFields.systemField.path
            messagesByPath.getOrPut(path) { mutableListOf() }.add(systemJson)
        }

        // Add conversation messages (including tool calls and responses)
        for (message in messages) {
            when (message.role) {
                "user" -> {
                    if (messageFields.userField != null) {
                        val userJson = messageFields.userField.template.replace(
                            BodyTemplatePlaceholders.PROMPT,
                            escapeJsonString(message.content)
                        )
                        val path = messageFields.userField.path
                        messagesByPath.getOrPut(path) { mutableListOf() }.add(userJson)
                    }
                }
                "tool_call" -> {
                    // Handle tool call messages from assistant
                    if (messageFields.toolCallField != null && message.toolCall != null) {
                        // Use toolId (internal name like "get_date_time") for {tool_name}
                        val toolInternalName = message.toolCall.toolId
                        val toolArgs = message.toolCall.parameters.toString()
                        // Use toolCallId (unique call ID like "call_abc123") for {tool_id}
                        val callId = message.toolCallId ?: ""
                        val toolCallJson = messageFields.toolCallField.template
                            .replace(BodyTemplatePlaceholders.TOOL_NAME, escapeJsonString(toolInternalName))
                            .replace(BodyTemplatePlaceholders.TOOL_ID, escapeJsonString(callId))
                            .replace(BodyTemplatePlaceholders.TOOL_PARAMETERS, toolArgs)
                        val path = messageFields.toolCallField.path
                        messagesByPath.getOrPut(path) { mutableListOf() }.add(toolCallJson)
                    }
                }
                "tool_result", "tool_response", "tool" -> {
                    // Handle tool response messages
                    if (messageFields.toolResponseField != null) {
                        val responseContent = message.toolResponseOutput ?: message.content
                        // Use toolResponseCallId for {tool_id} - links response back to tool call
                        val callId = message.toolResponseCallId ?: ""
                        val toolResponseJson = messageFields.toolResponseField.template
                            .replace(BodyTemplatePlaceholders.TOOL_RESPONSE, escapeJsonString(responseContent))
                            .replace(BodyTemplatePlaceholders.TOOL_ID, escapeJsonString(callId))
                        val path = messageFields.toolResponseField.path
                        messagesByPath.getOrPut(path) { mutableListOf() }.add(toolResponseJson)
                    }
                }
                else -> { // assistant
                    if (messageFields.assistantField != null) {
                        val assistantJson = messageFields.assistantField.template.replace(
                            BodyTemplatePlaceholders.ASSISTANT,
                            escapeJsonString(message.content)
                        )
                        val path = messageFields.assistantField.path
                        messagesByPath.getOrPut(path) { mutableListOf() }.add(assistantJson)
                    }
                }
            }
        }

        // Inject messages at each path
        for ((path, messagesJson) in messagesByPath) {
            val arrayJson = "[" + messagesJson.joinToString(",") + "]"
            val arrayElement = json.parseToJsonElement(arrayJson)
            setByPath(rootObject, path, arrayElement)
        }

        // Inject tool definitions if configured and tools are provided
        if (messageFields.toolDefinitionField != null && tools.isNotEmpty()) {
            val toolDefinitions = tools.map { tool ->
                // Extract just the properties from the parameters schema for flexibility
                val parametersJson = tool.parameters?.get("properties")?.toString()
                    ?: tool.parameters?.toString()
                    ?: "{}"

                messageFields.toolDefinitionField.template
                    .replace(BodyTemplatePlaceholders.TOOL_NAME, escapeJsonString(tool.name))
                    .replace(BodyTemplatePlaceholders.TOOL_DESCRIPTION, escapeJsonString(tool.description))
                    .replace(BodyTemplatePlaceholders.TOOL_PARAMETERS, parametersJson)
            }
            val path = messageFields.toolDefinitionField.path
            try {
                val toolsArrayJson = "[" + toolDefinitions.joinToString(",") + "]"
                val toolsElement = json.parseToJsonElement(toolsArrayJson)
                setByPath(rootObject, path, toolsElement)
            } catch (e: Exception) {
                // If parsing fails, skip tool injection
            }
        }

        // Build the result and apply simple substitutions
        var result = Json.encodeToString(JsonObject.serializer(), JsonObject(rootObject))

        // Apply remaining simple substitutions
        result = simpleSubstitution(
            result, apiKey, model, "", "", systemPrompt,
            tools, thoughts, thoughtsSignature
        )

        return result
    }

    /**
     * Sets a value at a dot-notation path in a mutable JSON object map.
     * Creates intermediate objects as needed.
     *
     * e.g., setByPath(root, "a.b.c", value) will create root["a"]["b"]["c"] = value
     *
     * If the path targets an existing array, the value (which should be a JsonArray)
     * will replace it. If the path targets a non-existent location, objects are created.
     *
     * @param root The root mutable map to modify
     * @param path The dot-notation path
     * @param value The JsonElement to set at the path
     */
    private fun setByPath(root: MutableMap<String, JsonElement>, path: String, value: JsonElement) {
        val parts = path.split(".")
        if (parts.isEmpty()) return

        if (parts.size == 1) {
            // Direct key at root level
            root[parts[0]] = value
            return
        }

        // Navigate/create intermediate objects
        var current: MutableMap<String, JsonElement> = root
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            val existing = current[part]

            current = when {
                existing is JsonObject -> existing.toMutableMap().also { current[part] = JsonObject(it) }
                existing == null -> {
                    // Create new object at this path
                    val newObj = mutableMapOf<String, JsonElement>()
                    current[part] = JsonObject(newObj)
                    newObj
                }
                else -> {
                    // Can't navigate through non-object (like primitive or array in middle of path)
                    // Just set at current level and return
                    current[path] = value
                    return
                }
            }
        }

        // Set the final value
        current[parts.last()] = value

        // Rebuild the path in root (since we created new mutable maps)
        rebuildPath(root, parts, value)
    }

    /**
     * Rebuilds the path in the root object after setting a value.
     * This is needed because we create new mutable maps while navigating,
     * and those need to be properly nested in the original root.
     */
    private fun rebuildPath(root: MutableMap<String, JsonElement>, parts: List<String>, finalValue: JsonElement) {
        if (parts.isEmpty()) return

        if (parts.size == 1) {
            root[parts[0]] = finalValue
            return
        }

        // Build from the end backwards
        var currentValue: JsonElement = finalValue
        for (i in parts.size - 1 downTo 1) {
            val existingAtParent = navigateTo(root, parts.take(i))
            val parentMap = when (existingAtParent) {
                is JsonObject -> existingAtParent.toMutableMap()
                else -> mutableMapOf()
            }
            parentMap[parts[i]] = currentValue
            currentValue = JsonObject(parentMap)
        }

        root[parts[0]] = currentValue
    }

    /**
     * Navigates to a path in the root and returns the element at that path.
     */
    private fun navigateTo(root: Map<String, JsonElement>, parts: List<String>): JsonElement? {
        var current: JsonElement = JsonObject(root)
        for (part in parts) {
            current = when (current) {
                is JsonObject -> current[part] ?: return null
                else -> return null
            }
        }
        return current
    }

    /**
     * Validates that message field templates contain the required placeholders.
     *
     * @return A list of validation errors, empty if valid
     */
    fun validateMessageFields(messageFields: MessageFieldsConfig): List<String> {
        val errors = mutableListOf<String>()

        messageFields.userField?.let { field ->
            if (!field.template.contains(BodyTemplatePlaceholders.PROMPT)) {
                errors.add("User field template must contain {prompt}")
            }
            if (field.path.isBlank()) {
                errors.add("User field path cannot be empty")
            }
        }

        messageFields.assistantField?.let { field ->
            if (!field.template.contains(BodyTemplatePlaceholders.ASSISTANT)) {
                errors.add("Assistant field template must contain {assistant}")
            }
            if (field.path.isBlank()) {
                errors.add("Assistant field path cannot be empty")
            }
        }

        messageFields.systemField?.let { field ->
            if (!field.template.contains(BodyTemplatePlaceholders.SYSTEM)) {
                errors.add("System field template must contain {system}")
            }
            if (field.path.isBlank()) {
                errors.add("System field path cannot be empty")
            }
        }

        // Tool field validations
        messageFields.toolDefinitionField?.let { field ->
            val hasAllRequired = field.template.contains(BodyTemplatePlaceholders.TOOL_NAME) &&
                    field.template.contains(BodyTemplatePlaceholders.TOOL_DESCRIPTION) &&
                    field.template.contains(BodyTemplatePlaceholders.TOOL_PARAMETERS)
            if (!hasAllRequired) {
                errors.add("Tool definition field template must contain {tool_name}, {tool_description}, and {tool_parameters}")
            }
            if (field.path.isBlank()) {
                errors.add("Tool definition field path cannot be empty")
            }
        }

        messageFields.toolCallField?.let { field ->
            val hasRequired = field.template.contains(BodyTemplatePlaceholders.TOOL_NAME) &&
                    field.template.contains(BodyTemplatePlaceholders.TOOL_PARAMETERS)
            if (!hasRequired) {
                errors.add("Tool call field template must contain {tool_name} and {tool_parameters}")
            }
            if (field.path.isBlank()) {
                errors.add("Tool call field path cannot be empty")
            }
        }

        messageFields.toolResponseField?.let { field ->
            if (!field.template.contains(BodyTemplatePlaceholders.TOOL_RESPONSE)) {
                errors.add("Tool response field template must contain {tool_response}")
            }
            if (field.path.isBlank()) {
                errors.add("Tool response field path cannot be empty")
            }
        }

        return errors
    }

    /**
     * Finds placeholders present across all message field templates (including tool fields).
     */
    fun findPlaceholdersInMessageFields(messageFields: MessageFieldsConfig?): Set<String> {
        if (messageFields == null) return emptySet()

        val allTemplates = listOfNotNull(
            messageFields.systemField?.template,
            messageFields.userField?.template,
            messageFields.assistantField?.template,
            messageFields.toolDefinitionField?.template,
            messageFields.toolCallField?.template,
            messageFields.toolResponseField?.template
        ).joinToString(" ")

        return BodyTemplatePlaceholders.ALL.filter { allTemplates.contains(it) }.toSet()
    }
}
