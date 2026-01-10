package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.data.model.BodyTemplatePlaceholders
import com.example.ApI.data.model.MessageFieldConfig
import com.example.ApI.data.model.MessageFieldsConfig
import com.example.ApI.ui.theme.*

/**
 * Editor for configuring dynamic message field injection.
 * Allows users to specify separate paths and templates for system, user, assistant messages,
 * as well as tool-related fields (tool definitions, tool calls, tool responses).
 *
 * All fields are optional - if a path is left blank, that field won't be injected.
 *
 * @param messageFields The current message fields configuration
 * @param onMessageFieldsChange Callback when configuration changes
 * @param modifier Optional modifier
 */
@Composable
fun MessageFieldsEditor(
    messageFields: MessageFieldsConfig?,
    onMessageFieldsChange: (MessageFieldsConfig?) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    // Message fields state
    var systemPath by remember { mutableStateOf(messageFields?.systemField?.path ?: "") }
    var systemTemplate by remember { mutableStateOf(messageFields?.systemField?.template ?: DEFAULT_SYSTEM_TEMPLATE) }
    var userPath by remember { mutableStateOf(messageFields?.userField?.path ?: "") }
    var userTemplate by remember { mutableStateOf(messageFields?.userField?.template ?: DEFAULT_USER_TEMPLATE) }
    var assistantPath by remember { mutableStateOf(messageFields?.assistantField?.path ?: "") }
    var assistantTemplate by remember { mutableStateOf(messageFields?.assistantField?.template ?: DEFAULT_ASSISTANT_TEMPLATE) }

    // Tool fields state
    var toolDefinitionPath by remember { mutableStateOf(messageFields?.toolDefinitionField?.path ?: "") }
    var toolDefinitionTemplate by remember { mutableStateOf(messageFields?.toolDefinitionField?.template ?: DEFAULT_TOOL_DEFINITION_TEMPLATE) }
    var toolCallPath by remember { mutableStateOf(messageFields?.toolCallField?.path ?: "") }
    var toolCallTemplate by remember { mutableStateOf(messageFields?.toolCallField?.template ?: DEFAULT_TOOL_CALL_TEMPLATE) }
    var toolResponsePath by remember { mutableStateOf(messageFields?.toolResponseField?.path ?: "") }
    var toolResponseTemplate by remember { mutableStateOf(messageFields?.toolResponseField?.template ?: DEFAULT_TOOL_RESPONSE_TEMPLATE) }

    // Update parent when local state changes
    fun updateParent() {
        val systemField = if (systemPath.isNotBlank() && systemTemplate.isNotBlank()) {
            MessageFieldConfig(systemPath.trim(), systemTemplate)
        } else null

        val userField = if (userPath.isNotBlank() && userTemplate.isNotBlank()) {
            MessageFieldConfig(userPath.trim(), userTemplate)
        } else null

        val assistantField = if (assistantPath.isNotBlank() && assistantTemplate.isNotBlank()) {
            MessageFieldConfig(assistantPath.trim(), assistantTemplate)
        } else null

        val toolDefinitionField = if (toolDefinitionPath.isNotBlank() && toolDefinitionTemplate.isNotBlank()) {
            MessageFieldConfig(toolDefinitionPath.trim(), toolDefinitionTemplate)
        } else null

        val toolCallField = if (toolCallPath.isNotBlank() && toolCallTemplate.isNotBlank()) {
            MessageFieldConfig(toolCallPath.trim(), toolCallTemplate)
        } else null

        val toolResponseField = if (toolResponsePath.isNotBlank() && toolResponseTemplate.isNotBlank()) {
            MessageFieldConfig(toolResponsePath.trim(), toolResponseTemplate)
        } else null

        val config = MessageFieldsConfig(
            systemField = systemField,
            userField = userField,
            assistantField = assistantField,
            toolDefinitionField = toolDefinitionField,
            toolCallField = toolCallField,
            toolResponseField = toolResponseField
        )

        val hasAnyField = config.hasAnyFields() || config.hasToolFields()
        onMessageFieldsChange(if (hasAnyField) config else null)
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier = modifier.fillMaxWidth()) {
            // Header
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "הזרקת הודעות דינמית",
                            style = MaterialTheme.typography.labelLarge,
                            color = Primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "הגדר נתיבים ותבניות נפרדות לכל סוג הודעה",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.6f)
                        )
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Primary
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))

                // Info box
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Primary.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "הסבר:",
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• נתיב: היכן להזריק בגוף הבקשה (לדוגמה: messages או system.parts)\n" +
                                    "• תבנית: JSON של הודעה בודדת עם placeholder מתאים\n" +
                                    "• נתיב עם נקודות (a.b) יוזרק בעומק (body.a.b)\n" +
                                    "• השאר נתיב ריק אם לא נדרש",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section: Message Fields
                Text(
                    text = "שדות הודעות",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnSurface,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // System Message Field
                MessageFieldEditor(
                    title = "הודעת מערכת (System)",
                    placeholder = BodyTemplatePlaceholders.SYSTEM,
                    path = systemPath,
                    onPathChange = { systemPath = it; updateParent() },
                    template = systemTemplate,
                    onTemplateChange = { systemTemplate = it; updateParent() },
                    pathPlaceholder = "system_instruction.parts",
                    templatePlaceholder = DEFAULT_SYSTEM_TEMPLATE,
                    isValid = systemPath.isBlank() || systemTemplate.contains(BodyTemplatePlaceholders.SYSTEM)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // User Message Field
                MessageFieldEditor(
                    title = "הודעת משתמש (User)",
                    placeholder = BodyTemplatePlaceholders.PROMPT,
                    path = userPath,
                    onPathChange = { userPath = it; updateParent() },
                    template = userTemplate,
                    onTemplateChange = { userTemplate = it; updateParent() },
                    pathPlaceholder = "messages",
                    templatePlaceholder = DEFAULT_USER_TEMPLATE,
                    isValid = userPath.isBlank() || userTemplate.contains(BodyTemplatePlaceholders.PROMPT)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Assistant Message Field
                MessageFieldEditor(
                    title = "הודעת עוזר (Assistant)",
                    placeholder = BodyTemplatePlaceholders.ASSISTANT,
                    path = assistantPath,
                    onPathChange = { assistantPath = it; updateParent() },
                    template = assistantTemplate,
                    onTemplateChange = { assistantTemplate = it; updateParent() },
                    pathPlaceholder = "messages",
                    templatePlaceholder = DEFAULT_ASSISTANT_TEMPLATE,
                    isValid = assistantPath.isBlank() || assistantTemplate.contains(BodyTemplatePlaceholders.ASSISTANT)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Divider
                HorizontalDivider(color = OnSurface.copy(alpha = 0.2f))

                Spacer(modifier = Modifier.height(16.dp))

                // Section: Tool Fields (Optional)
                Text(
                    text = "שדות כלים (אופציונלי)",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "הגדר רק אם הספק תומך בכלים",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Tool Definition Field
                MessageFieldEditor(
                    title = "הגדרת כלים (Tool Definitions)",
                    placeholder = "{tool_name}, {tool_description}, {tool_parameters}",
                    path = toolDefinitionPath,
                    onPathChange = { toolDefinitionPath = it; updateParent() },
                    template = toolDefinitionTemplate,
                    onTemplateChange = { toolDefinitionTemplate = it; updateParent() },
                    pathPlaceholder = "tools",
                    templatePlaceholder = DEFAULT_TOOL_DEFINITION_TEMPLATE,
                    isValid = toolDefinitionPath.isBlank() || (
                        toolDefinitionTemplate.contains(BodyTemplatePlaceholders.TOOL_NAME) &&
                        toolDefinitionTemplate.contains(BodyTemplatePlaceholders.TOOL_DESCRIPTION) &&
                        toolDefinitionTemplate.contains(BodyTemplatePlaceholders.TOOL_PARAMETERS)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Tool Call Field
                MessageFieldEditor(
                    title = "קריאת כלי (Tool Call)",
                    placeholder = "{tool_name}, {tool_parameters}",
                    path = toolCallPath,
                    onPathChange = { toolCallPath = it; updateParent() },
                    template = toolCallTemplate,
                    onTemplateChange = { toolCallTemplate = it; updateParent() },
                    pathPlaceholder = "messages",
                    templatePlaceholder = DEFAULT_TOOL_CALL_TEMPLATE,
                    isValid = toolCallPath.isBlank() || (
                        toolCallTemplate.contains(BodyTemplatePlaceholders.TOOL_NAME) &&
                        toolCallTemplate.contains(BodyTemplatePlaceholders.TOOL_PARAMETERS)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Tool Response Field
                MessageFieldEditor(
                    title = "תגובת כלי (Tool Response)",
                    placeholder = BodyTemplatePlaceholders.TOOL_RESPONSE,
                    path = toolResponsePath,
                    onPathChange = { toolResponsePath = it; updateParent() },
                    template = toolResponseTemplate,
                    onTemplateChange = { toolResponseTemplate = it; updateParent() },
                    pathPlaceholder = "messages",
                    templatePlaceholder = DEFAULT_TOOL_RESPONSE_TEMPLATE,
                    isValid = toolResponsePath.isBlank() || toolResponseTemplate.contains(BodyTemplatePlaceholders.TOOL_RESPONSE)
                )
            }
        }
    }
}

/**
 * Editor for a single message field (path + template).
 */
@Composable
private fun MessageFieldEditor(
    title: String,
    placeholder: String,
    path: String,
    onPathChange: (String) -> Unit,
    template: String,
    onTemplateChange: (String) -> Unit,
    pathPlaceholder: String,
    templatePlaceholder: String,
    isValid: Boolean
) {
    var isExpanded by remember { mutableStateOf(path.isNotBlank()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header row
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (path.isNotBlank()) {
                if (isValid) AccentGreen.copy(alpha = 0.1f) else AccentRed.copy(alpha = 0.1f)
            } else {
                SurfaceVariant.copy(alpha = 0.3f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Status icon
                    if (path.isNotBlank()) {
                        Icon(
                            imageVector = if (isValid) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (isValid) AccentGreen else AccentRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = OnSurface,
                        fontWeight = FontWeight.Medium
                    )
                    // Show required placeholder
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Primary.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            color = Primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = OnSurface.copy(alpha = 0.5f)
                )
            }
        }

        if (isExpanded) {
            Spacer(modifier = Modifier.height(8.dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp)
                ) {
                    // Path field
                    OutlinedTextField(
                        value = path,
                        onValueChange = onPathChange,
                        label = { Text("Path (נתיב)", color = OnSurface.copy(alpha = 0.7f)) },
                        placeholder = { Text(pathPlaceholder, color = OnSurface.copy(alpha = 0.4f)) },
                        supportingText = {
                            Text(
                                "e.g., messages, contents, system.parts",
                                color = OnSurface.copy(alpha = 0.5f)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = messageFieldColors()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Template field
                    OutlinedTextField(
                        value = template,
                        onValueChange = onTemplateChange,
                        label = { Text("Template (תבנית)", color = OnSurface.copy(alpha = 0.7f)) },
                        placeholder = {
                            Text(
                                templatePlaceholder,
                                color = OnSurface.copy(alpha = 0.4f),
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        supportingText = {
                            if (!isValid && path.isNotBlank()) {
                                Text(
                                    "Template must contain $placeholder",
                                    color = AccentRed
                                )
                            } else {
                                Text(
                                    "JSON template with $placeholder placeholder",
                                    color = OnSurface.copy(alpha = 0.5f)
                                )
                            }
                        },
                        isError = !isValid && path.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp),
                        minLines = 2,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        colors = messageFieldColors()
                    )
                }
            }
        }
    }
}

@Composable
private fun messageFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Primary,
    unfocusedBorderColor = OnSurface.copy(alpha = 0.3f),
    focusedTextColor = OnSurface,
    unfocusedTextColor = OnSurface,
    errorBorderColor = AccentRed
)

// Default templates for message types
private const val DEFAULT_SYSTEM_TEMPLATE = """{"role": "system", "content": "{system}"}"""
private const val DEFAULT_USER_TEMPLATE = """{"role": "user", "content": "{prompt}"}"""
private const val DEFAULT_ASSISTANT_TEMPLATE = """{"role": "assistant", "content": "{assistant}"}"""

// Default templates for tool types
private const val DEFAULT_TOOL_DEFINITION_TEMPLATE = """{"type": "function", "function": {"name": "{tool_name}", "description": "{tool_description}", "parameters": {tool_parameters}}}"""
private const val DEFAULT_TOOL_CALL_TEMPLATE = """{"role": "assistant", "tool_calls": [{"type": "function", "function": {"name": "{tool_name}", "arguments": "{tool_parameters}"}}]}"""
private const val DEFAULT_TOOL_RESPONSE_TEMPLATE = """{"role": "tool", "content": "{tool_response}"}"""
