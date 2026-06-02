package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.ui.theme.*
import kotlinx.serialization.json.Json

/**
 * Simplified composable for editing body templates.
 * Shows a multi-line text field for JSON template editing with monospace font
 * and validates that the content is valid JSON.
 *
 * @param template The current body template string
 * @param onTemplateChange Callback when template changes
 * @param onJsonValidityChange Callback when JSON validity changes
 * @param modifier Optional modifier
 */
@Composable
fun BodyTemplateEditor(
    template: String,
    onTemplateChange: (String) -> Unit,
    onJsonValidityChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Validate JSON on every template change
    val isValidJson = remember(template) {
        try {
            if (template.isBlank()) {
                false
            } else {
                Json.parseToJsonElement(template)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // Notify parent of validity changes
    LaunchedEffect(isValidJson) {
        onJsonValidityChange(isValidJson)
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier = modifier.fillMaxWidth()) {
            // Section header
            Text(
                text = "מבנה גוף הבקשה - חלק קבוע",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Multi-line text field for body template
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = template,
                    onValueChange = onTemplateChange,
                    label = { Text("Body Template (JSON)", color = OnSurface.copy(alpha = 0.7f)) },
                    placeholder = {
                        Text(
                            "{\n  \"model\": \"{model}\",\n  \"messages\": [...]\n}",
                            color = OnSurface.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    isError = template.isNotBlank() && !isValidJson,
                    supportingText = if (template.isNotBlank() && !isValidJson) {
                        { Text("JSON לא תקין", color = AccentRed) }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp),
                    minLines = 8,
                    maxLines = 20,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (template.isNotBlank() && !isValidJson) AccentRed else Primary,
                        unfocusedBorderColor = if (template.isNotBlank() && !isValidJson) AccentRed.copy(alpha = 0.5f) else OnSurface.copy(alpha = 0.3f),
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface,
                        errorBorderColor = AccentRed
                    )
                )
            }
        }
    }
}
