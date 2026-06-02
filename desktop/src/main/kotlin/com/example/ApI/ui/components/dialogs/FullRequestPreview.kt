package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.data.model.BodyTemplatePlaceholders
import com.example.ApI.data.model.MessageFieldsConfig
import com.example.ApI.ui.theme.*
import kotlinx.serialization.json.*

/**
 * Read-only preview that shows a full expanded request based on the user's configuration.
 * Uses hardcoded example values to demonstrate how the final request will look.
 *
 * @param bodyTemplate The body template (static part)
 * @param messageFields The message fields configuration (dynamic part)
 * @param modifier Optional modifier
 */
@Composable
fun FullRequestPreview(
    bodyTemplate: String,
    messageFields: MessageFieldsConfig?,
    modifier: Modifier = Modifier
) {
    val previewJson = remember(bodyTemplate, messageFields) {
        buildPreviewJson(bodyTemplate, messageFields)
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier = modifier.fillMaxWidth()) {
            // Section header
            Text(
                text = "דוגמה לבקשה מלאה",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Preview surface
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Background.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth()
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp, max = 300.dp)
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = previewJson,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            ),
                            color = AccentGreen
                        )
                    }
                }
            }
        }
    }
}

/**
 * Builds the preview JSON by expanding the body template with example values.
 */
private fun buildPreviewJson(bodyTemplate: String, messageFields: MessageFieldsConfig?): String {
    try {
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        // Parse the base template
        val rootObject = try {
            json.parseToJsonElement(bodyTemplate).jsonObject.toMutableMap()
        } catch (e: Exception) {
            return "// JSON לא תקין בתבנית הבקשה"
        }

        // Replace simple placeholders in the template
        var result = bodyTemplate
            .replace(BodyTemplatePlaceholders.MODEL, "example-model")
            .replace(BodyTemplatePlaceholders.KEY, "sk-example-api-key")

        // If messageFields is configured, inject example messages
        if (messageFields != null && messageFields.hasAnyFields()) {
            result = injectExampleMessages(result, messageFields, json)
        } else {
            // Simple replacement for templates without messageFields
            result = result
                .replace(BodyTemplatePlaceholders.SYSTEM, "Answer in the most concise and shortest way imaginable")
                .replace(BodyTemplatePlaceholders.PROMPT, "Hi, how are you?")
                .replace(BodyTemplatePlaceholders.ASSISTANT, "Good")
        }

        // Pretty print the result
        return try {
            val parsed = json.parseToJsonElement(result)
            json.encodeToString(JsonElement.serializer(), parsed)
        } catch (e: Exception) {
            result
        }
    } catch (e: Exception) {
        return "// שגיאה ביצירת הדוגמה: ${e.message}"
    }
}

/**
 * Injects example messages using the messageFields configuration.
 */
private fun injectExampleMessages(
    template: String,
    messageFields: MessageFieldsConfig,
    json: Json
): String {
    try {
        val rootObject = json.parseToJsonElement(template).jsonObject.toMutableMap()

        // Collect messages by path
        val messagesByPath = mutableMapOf<String, MutableList<JsonElement>>()

        // Add system message
        if (messageFields.systemField != null) {
            val systemJson = messageFields.systemField.template.replace(
                BodyTemplatePlaceholders.SYSTEM,
                escapeJsonString("Answer in the most concise and shortest way imaginable")
            )
            val path = messageFields.systemField.path
            try {
                messagesByPath.getOrPut(path) { mutableListOf() }
                    .add(json.parseToJsonElement(systemJson))
            } catch (e: Exception) { /* skip invalid */ }
        }

        // Add first user message
        if (messageFields.userField != null) {
            val userJson = messageFields.userField.template.replace(
                BodyTemplatePlaceholders.PROMPT,
                escapeJsonString("Hi, how are you?")
            )
            val path = messageFields.userField.path
            try {
                messagesByPath.getOrPut(path) { mutableListOf() }
                    .add(json.parseToJsonElement(userJson))
            } catch (e: Exception) { /* skip invalid */ }
        }

        // Add first assistant message
        if (messageFields.assistantField != null) {
            val assistantJson = messageFields.assistantField.template.replace(
                BodyTemplatePlaceholders.ASSISTANT,
                escapeJsonString("Good")
            )
            val path = messageFields.assistantField.path
            try {
                messagesByPath.getOrPut(path) { mutableListOf() }
                    .add(json.parseToJsonElement(assistantJson))
            } catch (e: Exception) { /* skip invalid */ }
        }

        // If tool fields are defined, add tool example
        if (messageFields.hasToolFields()) {
            // Add second user message asking to test tool
            if (messageFields.userField != null) {
                val userJson = messageFields.userField.template.replace(
                    BodyTemplatePlaceholders.PROMPT,
                    escapeJsonString("Test the tool")
                )
                val path = messageFields.userField.path
                try {
                    messagesByPath.getOrPut(path) { mutableListOf() }
                        .add(json.parseToJsonElement(userJson))
                } catch (e: Exception) { /* skip invalid */ }
            }

            // Add tool definition
            if (messageFields.toolDefinitionField != null) {
                val toolDefJson = messageFields.toolDefinitionField.template
                    .replace(BodyTemplatePlaceholders.TOOL_NAME, "test_tool")
                    .replace(BodyTemplatePlaceholders.TOOL_DESCRIPTION, escapeJsonString("This tool is intended to test tool functionality. Use it when the user asks for"))
                    .replace(BodyTemplatePlaceholders.TOOL_PARAMETERS, """{"type":"object","properties":{"test_parameter":{"type":"string"}},"required":["test_parameter"]}""")
                val path = messageFields.toolDefinitionField.path
                try {
                    messagesByPath.getOrPut(path) { mutableListOf() }
                        .add(json.parseToJsonElement(toolDefJson))
                } catch (e: Exception) { /* skip invalid */ }
            }

            // Add tool call
            if (messageFields.toolCallField != null) {
                val toolCallJson = messageFields.toolCallField.template
                    .replace(BodyTemplatePlaceholders.TOOL_NAME, "test_tool")
                    .replace(BodyTemplatePlaceholders.TOOL_ID, "call_example_123")
                    .replace(BodyTemplatePlaceholders.TOOL_PARAMETERS, """{"test_parameter":"example_value"}""")
                val path = messageFields.toolCallField.path
                try {
                    messagesByPath.getOrPut(path) { mutableListOf() }
                        .add(json.parseToJsonElement(toolCallJson))
                } catch (e: Exception) { /* skip invalid */ }
            }

            // Add tool response
            if (messageFields.toolResponseField != null) {
                val toolResponseJson = messageFields.toolResponseField.template
                    .replace(BodyTemplatePlaceholders.TOOL_RESPONSE, escapeJsonString("Tool executed successfully"))
                    .replace(BodyTemplatePlaceholders.TOOL_ID, "call_example_123")
                val path = messageFields.toolResponseField.path
                try {
                    messagesByPath.getOrPut(path) { mutableListOf() }
                        .add(json.parseToJsonElement(toolResponseJson))
                } catch (e: Exception) { /* skip invalid */ }
            }
        }

        // Inject messages at each path
        for ((path, messages) in messagesByPath) {
            setByPath(rootObject, path, JsonArray(messages))
        }

        // Apply simple substitutions to the final result
        var result = Json.encodeToString(JsonObject.serializer(), JsonObject(rootObject))
        result = result
            .replace(BodyTemplatePlaceholders.MODEL, "example-model")
            .replace(BodyTemplatePlaceholders.KEY, "sk-example-api-key")
            .replace(BodyTemplatePlaceholders.SYSTEM, "")
            .replace(BodyTemplatePlaceholders.PROMPT, "")
            .replace(BodyTemplatePlaceholders.ASSISTANT, "")

        return result
    } catch (e: Exception) {
        return template
    }
}

/**
 * Sets a value at a dot-notation path in a mutable JSON object map.
 */
private fun setByPath(root: MutableMap<String, JsonElement>, path: String, value: JsonElement) {
    val parts = path.split(".")
    if (parts.isEmpty()) return

    if (parts.size == 1) {
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
                val newObj = mutableMapOf<String, JsonElement>()
                current[part] = JsonObject(newObj)
                newObj
            }
            else -> {
                current[path] = value
                return
            }
        }
    }

    current[parts.last()] = value
    rebuildPath(root, parts, value)
}

/**
 * Rebuilds the path in the root object after setting a value.
 */
private fun rebuildPath(root: MutableMap<String, JsonElement>, parts: List<String>, finalValue: JsonElement) {
    if (parts.isEmpty()) return

    if (parts.size == 1) {
        root[parts[0]] = finalValue
        return
    }

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
