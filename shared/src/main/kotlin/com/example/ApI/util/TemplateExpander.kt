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

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class MissingPlaceholders(val missing: Set<String>) : ValidationResult()
    }

    data class MessagePattern(
        val userTemplate: String,
        val assistantTemplate: String,
        val systemTemplate: String?
    )

    fun validateTemplate(template: String): ValidationResult {
        val missingPlaceholders = BodyTemplatePlaceholders.REQUIRED.filter {
            !template.contains(it)
        }.toSet()
        return if (missingPlaceholders.isEmpty()) ValidationResult.Valid
        else ValidationResult.MissingPlaceholders(missingPlaceholders)
    }

    fun findPresentPlaceholders(template: String): Set<String> {
        return BodyTemplatePlaceholders.ALL.filter { template.contains(it) }.toSet()
    }

    fun detectMessagePattern(template: String): MessagePattern? {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(template).jsonObject
            val messagesArray = findMessagesArray(root)
            if (messagesArray == null || messagesArray.isEmpty()) return null

            var userTemplate: String? = null
            var assistantTemplate: String? = null
            var systemTemplate: String? = null

            for (element in messagesArray) {
                val elementStr = element.toString()
                when {
                    elementStr.contains(BodyTemplatePlaceholders.PROMPT) -> userTemplate = elementStr
                    elementStr.contains(BodyTemplatePlaceholders.ASSISTANT) -> assistantTemplate = elementStr
                    elementStr.contains(BodyTemplatePlaceholders.SYSTEM) -> systemTemplate = elementStr
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

    private fun findMessagesArray(obj: JsonObject): JsonArray? {
        val commonNames = listOf("messages", "contents", "content", "prompt", "input")
        for (name in commonNames) {
            val element = obj[name]
            if (element is JsonArray) return element
        }
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
            return simpleSubstitution(template, apiKey, model,
                messages.lastOrNull { it.role != "user" }?.content ?: "",
                messages.lastOrNull { it.role == "user" }?.content ?: "",
                systemPrompt, tools, thoughts, thoughtsSignature)
        }
        val messagesJson = buildMessagesArray(pattern, messages, systemPrompt)
        val expandedTemplate = replaceMessagesArrayInTemplate(template, pattern, messagesJson)
        return simpleSubstitution(expandedTemplate, apiKey, model, "", "", systemPrompt, tools, thoughts, thoughtsSignature)
    }

    private fun buildMessagesArray(pattern: MessagePattern, messages: List<Message>, systemPrompt: String): String {
        val result = StringBuilder()
        result.append("[")
        var first = true

        if (pattern.systemTemplate != null && systemPrompt.isNotBlank()) {
            val systemMsg = pattern.systemTemplate.replace(BodyTemplatePlaceholders.SYSTEM, escapeJsonString(systemPrompt))
            result.append(systemMsg)
            first = false
        }

        for (message in messages) {
            if (!first) result.append(",")
            first = false
            if (message.role == "user") {
                result.append(pattern.userTemplate.replace(BodyTemplatePlaceholders.PROMPT, escapeJsonString(message.content)))
            } else {
                result.append(pattern.assistantTemplate.replace(BodyTemplatePlaceholders.ASSISTANT, escapeJsonString(message.content)))
            }
        }
        result.append("]")
        return result.toString()
    }

    private fun replaceMessagesArrayInTemplate(template: String, pattern: MessagePattern, expandedMessages: String): String {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(template).jsonObject.toMutableMap()
            val commonNames = listOf("messages", "contents", "content", "prompt", "input")
            for (name in commonNames) {
                val element = root[name]
                if (element is JsonArray) {
                    val arrayStr = element.toString()
                    if (arrayStr.contains(BodyTemplatePlaceholders.PROMPT) || arrayStr.contains(BodyTemplatePlaceholders.ASSISTANT)) {
                        root[name] = json.parseToJsonElement(expandedMessages)
                        return Json.encodeToString(JsonObject.serializer(), JsonObject(root))
                    }
                }
            }
            for ((key, value) in root) {
                if (value is JsonArray) {
                    val arrayStr = value.toString()
                    if (arrayStr.contains(BodyTemplatePlaceholders.PROMPT) || arrayStr.contains(BodyTemplatePlaceholders.ASSISTANT)) {
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

    private fun simpleSubstitution(
        template: String, apiKey: String, model: String,
        assistantContent: String, promptContent: String, systemPrompt: String,
        tools: List<ToolSpecification>, thoughts: String?, thoughtsSignature: String?
    ): String {
        var result = template
        result = result.replace(BodyTemplatePlaceholders.KEY, apiKey)
        result = result.replace(BodyTemplatePlaceholders.MODEL, model)
        if (result.contains(BodyTemplatePlaceholders.PROMPT))
            result = result.replace(BodyTemplatePlaceholders.PROMPT, escapeJsonString(promptContent))
        if (result.contains(BodyTemplatePlaceholders.ASSISTANT))
            result = result.replace(BodyTemplatePlaceholders.ASSISTANT, escapeJsonString(assistantContent))
        if (result.contains(BodyTemplatePlaceholders.SYSTEM))
            result = result.replace(BodyTemplatePlaceholders.SYSTEM, escapeJsonString(systemPrompt))
        if (thoughts != null && result.contains(BodyTemplatePlaceholders.THOUGHTS))
            result = result.replace(BodyTemplatePlaceholders.THOUGHTS, escapeJsonString(thoughts))
        else result = result.replace(BodyTemplatePlaceholders.THOUGHTS, "")
        if (thoughtsSignature != null && result.contains(BodyTemplatePlaceholders.THOUGHTS_SIGNATURE))
            result = result.replace(BodyTemplatePlaceholders.THOUGHTS_SIGNATURE, escapeJsonString(thoughtsSignature))
        else result = result.replace(BodyTemplatePlaceholders.THOUGHTS_SIGNATURE, "")
        if (tools.isEmpty()) {
            result = result.replace(BodyTemplatePlaceholders.TOOL_NAME, "")
            result = result.replace(BodyTemplatePlaceholders.TOOL_ID, "")
            result = result.replace(BodyTemplatePlaceholders.TOOL_DESCRIPTION, "")
            result = result.replace(BodyTemplatePlaceholders.TOOL_PARAMETERS, "")
            result = result.replace(BodyTemplatePlaceholders.TOOL_RESPONSE, "")
        }
        return result
    }

    private fun escapeJsonString(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    }

    fun expandSimplePlaceholders(template: String, apiKey: String, model: String, systemPrompt: String = ""): String {
        var result = template
        result = result.replace(BodyTemplatePlaceholders.KEY, apiKey)
        result = result.replace(BodyTemplatePlaceholders.MODEL, model)
        result = result.replace(BodyTemplatePlaceholders.SYSTEM, systemPrompt)
        return result
    }

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
        val rootObject = try {
            json.parseToJsonElement(template).jsonObject.toMutableMap()
        } catch (e: Exception) {
            return simpleSubstitution(template, apiKey, model, "", "", systemPrompt, tools, thoughts, thoughtsSignature)
        }

        val messagesByPath = mutableMapOf<String, MutableList<String>>()
        val systemField = messageFields.systemField
        if (systemField != null && systemPrompt.isNotBlank()) {
            val systemJson = systemField.template.replace(BodyTemplatePlaceholders.SYSTEM, escapeJsonString(systemPrompt))
            messagesByPath.getOrPut(systemField.path) { mutableListOf() }.add(systemJson)
        }

        val userField = messageFields.userField
        val toolCallField = messageFields.toolCallField
        val toolResponseField = messageFields.toolResponseField
        val assistantField = messageFields.assistantField

        for (message in messages) {
            when (message.role) {
                "user" -> {
                    if (userField != null) {
                        val userJson = userField.template.replace(BodyTemplatePlaceholders.PROMPT, escapeJsonString(message.content))
                        messagesByPath.getOrPut(userField.path) { mutableListOf() }.add(userJson)
                    }
                }
                "tool_call" -> {
                    val toolCall = message.toolCall
                    if (toolCallField != null && toolCall != null) {
                        val toolCallJson = toolCallField.template
                            .replace(BodyTemplatePlaceholders.TOOL_NAME, escapeJsonString(toolCall.toolId))
                            .replace(BodyTemplatePlaceholders.TOOL_ID, escapeJsonString(message.toolCallId ?: ""))
                            .replace(BodyTemplatePlaceholders.TOOL_PARAMETERS, toolCall.parameters.toString())
                        messagesByPath.getOrPut(toolCallField.path) { mutableListOf() }.add(toolCallJson)
                    }
                }
                "tool_result", "tool_response", "tool" -> {
                    if (toolResponseField != null) {
                        val responseContent = message.toolResponseOutput ?: message.content
                        val toolResponseJson = toolResponseField.template
                            .replace(BodyTemplatePlaceholders.TOOL_RESPONSE, escapeJsonString(responseContent))
                            .replace(BodyTemplatePlaceholders.TOOL_ID, escapeJsonString(message.toolResponseCallId ?: ""))
                        messagesByPath.getOrPut(toolResponseField.path) { mutableListOf() }.add(toolResponseJson)
                    }
                }
                else -> {
                    if (assistantField != null) {
                        val assistantJson = assistantField.template.replace(BodyTemplatePlaceholders.ASSISTANT, escapeJsonString(message.content))
                        messagesByPath.getOrPut(assistantField.path) { mutableListOf() }.add(assistantJson)
                    }
                }
            }
        }

        for ((path, messagesJson) in messagesByPath) {
            val arrayJson = "[" + messagesJson.joinToString(",") + "]"
            setByPath(rootObject, path, json.parseToJsonElement(arrayJson))
        }

        val toolDefinitionField = messageFields.toolDefinitionField
        if (toolDefinitionField != null && tools.isNotEmpty()) {
            val toolDefinitions = tools.map { tool ->
                val parametersJson = tool.parameters?.get("properties")?.toString() ?: tool.parameters?.toString() ?: "{}"
                val requiredArray = tool.parameters?.get("required")
                var toolDefJson = toolDefinitionField.template
                    .replace(BodyTemplatePlaceholders.TOOL_NAME, escapeJsonString(tool.name))
                    .replace(BodyTemplatePlaceholders.TOOL_DESCRIPTION, escapeJsonString(tool.description))
                    .replace(BodyTemplatePlaceholders.TOOL_PARAMETERS, parametersJson)
                toolDefJson = injectRequiredArray(toolDefJson, requiredArray, json)
                toolDefJson
            }
            try {
                val toolsArrayJson = "[" + toolDefinitions.joinToString(",") + "]"
                setByPath(rootObject, toolDefinitionField.path, json.parseToJsonElement(toolsArrayJson))
            } catch (e: Exception) {
                // skip tool injection on error
            }
        }

        var result = Json.encodeToString(JsonObject.serializer(), JsonObject(rootObject))
        result = simpleSubstitution(result, apiKey, model, "", "", systemPrompt, tools, thoughts, thoughtsSignature)
        return result
    }

    private fun setByPath(root: MutableMap<String, JsonElement>, path: String, value: JsonElement) {
        val parts = path.split(".")
        if (parts.isEmpty()) return
        if (parts.size == 1) { root[parts[0]] = value; return }

        var current: MutableMap<String, JsonElement> = root
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            val existing = current[part]
            current = when {
                existing is JsonObject -> existing.toMutableMap().also { current[part] = JsonObject(it) }
                existing == null -> { val newObj = mutableMapOf<String, JsonElement>(); current[part] = JsonObject(newObj); newObj }
                else -> { current[path] = value; return }
            }
        }
        current[parts.last()] = value
        rebuildPath(root, parts, value)
    }

    private fun rebuildPath(root: MutableMap<String, JsonElement>, parts: List<String>, finalValue: JsonElement) {
        if (parts.isEmpty()) return
        if (parts.size == 1) { root[parts[0]] = finalValue; return }

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

    fun validateMessageFields(messageFields: MessageFieldsConfig): List<String> {
        val errors = mutableListOf<String>()
        messageFields.userField?.let { field ->
            if (!field.template.contains(BodyTemplatePlaceholders.PROMPT)) errors.add("User field template must contain {prompt}")
            if (field.path.isBlank()) errors.add("User field path cannot be empty")
        }
        messageFields.assistantField?.let { field ->
            if (!field.template.contains(BodyTemplatePlaceholders.ASSISTANT)) errors.add("Assistant field template must contain {assistant}")
            if (field.path.isBlank()) errors.add("Assistant field path cannot be empty")
        }
        messageFields.systemField?.let { field ->
            if (!field.template.contains(BodyTemplatePlaceholders.SYSTEM)) errors.add("System field template must contain {system}")
            if (field.path.isBlank()) errors.add("System field path cannot be empty")
        }
        messageFields.toolDefinitionField?.let { field ->
            val hasAllRequired = field.template.contains(BodyTemplatePlaceholders.TOOL_NAME) &&
                    field.template.contains(BodyTemplatePlaceholders.TOOL_DESCRIPTION) &&
                    field.template.contains(BodyTemplatePlaceholders.TOOL_PARAMETERS)
            if (!hasAllRequired) errors.add("Tool definition field template must contain {tool_name}, {tool_description}, and {tool_parameters}")
            if (field.path.isBlank()) errors.add("Tool definition field path cannot be empty")
        }
        messageFields.toolCallField?.let { field ->
            val hasRequired = field.template.contains(BodyTemplatePlaceholders.TOOL_NAME) && field.template.contains(BodyTemplatePlaceholders.TOOL_PARAMETERS)
            if (!hasRequired) errors.add("Tool call field template must contain {tool_name} and {tool_parameters}")
            if (field.path.isBlank()) errors.add("Tool call field path cannot be empty")
        }
        messageFields.toolResponseField?.let { field ->
            if (!field.template.contains(BodyTemplatePlaceholders.TOOL_RESPONSE)) errors.add("Tool response field template must contain {tool_response}")
            if (field.path.isBlank()) errors.add("Tool response field path cannot be empty")
        }
        return errors
    }

    fun findPlaceholdersInMessageFields(messageFields: MessageFieldsConfig?): Set<String> {
        if (messageFields == null) return emptySet()
        val allTemplates = listOfNotNull(
            messageFields.systemField?.template, messageFields.userField?.template,
            messageFields.assistantField?.template, messageFields.toolDefinitionField?.template,
            messageFields.toolCallField?.template, messageFields.toolResponseField?.template
        ).joinToString(" ")
        return BodyTemplatePlaceholders.ALL.filter { allTemplates.contains(it) }.toSet()
    }

    private fun injectRequiredArray(toolDefJson: String, requiredArray: JsonElement?, json: Json): String {
        return try {
            val element = json.parseToJsonElement(toolDefJson)
            val modified = injectRequiredInElement(element, requiredArray ?: JsonArray(emptyList()))
            json.encodeToString(JsonElement.serializer(), modified)
        } catch (e: Exception) {
            toolDefJson
        }
    }

    private fun injectRequiredInElement(element: JsonElement, requiredArray: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> {
                val mutableMap = element.toMutableMap()
                if (mutableMap.containsKey("properties")) mutableMap["required"] = requiredArray
                for ((key, value) in mutableMap.toMap()) mutableMap[key] = injectRequiredInElement(value, requiredArray)
                JsonObject(mutableMap)
            }
            is JsonArray -> JsonArray(element.map { injectRequiredInElement(it, requiredArray) })
            else -> element
        }
    }
}
