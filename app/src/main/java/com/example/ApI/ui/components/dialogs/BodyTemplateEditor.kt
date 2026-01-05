package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.example.ApI.ui.theme.*

/**
 * Composable for editing body templates with placeholder indicators.
 * Shows a multi-line text field for JSON template editing with monospace font,
 * displays placeholder status chips, and provides helper text with an example.
 *
 * @param template The current body template string
 * @param onTemplateChange Callback when template changes
 * @param modifier Optional modifier
 */
@Composable
fun BodyTemplateEditor(
    template: String,
    onTemplateChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showExample by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier = modifier.fillMaxWidth()) {
            // Section header
            Text(
                text = "תבנית גוף הבקשה",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Helper text explaining placeholders
            Text(
                text = "הזן תבנית JSON עם placeholders. הפרסר יחליף את ה-placeholders בערכים בזמן ריצה.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Placeholder indicators
            PlaceholderIndicators(template = template)

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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp),
                    minLines = 8,
                    maxLines = 20,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    colors = templateTextFieldColors()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Collapsible example section
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showExample = !showExample }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (showExample) "הסתר דוגמה" else "הצג תבנית לדוגמה",
                        style = MaterialTheme.typography.labelLarge,
                        color = Primary
                    )
                    Icon(
                        imageVector = if (showExample) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Primary
                    )
                }
            }

            if (showExample) {
                Spacer(modifier = Modifier.height(12.dp))
                ExampleTemplateSection()
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Placeholder legend
            PlaceholderLegend()
        }
    }
}

/**
 * Row of chips showing placeholder status in the template.
 */
@Composable
private fun PlaceholderIndicators(template: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Required placeholders
        Text(
            text = "Placeholders נדרשים:",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurface.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(6.dp))

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BodyTemplatePlaceholders.REQUIRED.forEach { placeholder ->
                    PlaceholderChip(
                        placeholder = placeholder,
                        isPresent = template.contains(placeholder),
                        isRequired = true
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Optional placeholders
        Text(
            text = "Placeholders אופציונליים:",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurface.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(6.dp))

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BodyTemplatePlaceholders.OPTIONAL.forEach { placeholder ->
                    PlaceholderChip(
                        placeholder = placeholder,
                        isPresent = template.contains(placeholder),
                        isRequired = false
                    )
                }
            }
        }
    }
}

/**
 * Individual chip showing placeholder status.
 */
@Composable
private fun PlaceholderChip(
    placeholder: String,
    isPresent: Boolean,
    isRequired: Boolean
) {
    val backgroundColor = when {
        isPresent -> Primary.copy(alpha = 0.2f)
        isRequired -> AccentRed.copy(alpha = 0.2f)
        else -> Gray500.copy(alpha = 0.2f)
    }

    val borderColor = when {
        isPresent -> Primary
        isRequired -> AccentRed
        else -> Gray500
    }

    val textColor = when {
        isPresent -> Primary
        isRequired -> AccentRed
        else -> Gray500
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(borderColor.copy(alpha = 0.6f))
        ),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = placeholder,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            ),
            color = textColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Example template section showing a sample JSON body template.
 */
@Composable
private fun ExampleTemplateSection() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Background.copy(alpha = 0.8f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = EXAMPLE_BODY_TEMPLATE,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                ),
                color = AccentGreen,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/**
 * Legend explaining what each placeholder represents.
 */
@Composable
private fun PlaceholderLegend() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "הסבר Placeholders:",
                style = MaterialTheme.typography.labelMedium,
                color = OnSurface,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PlaceholderExplanation("{key}", "API key for authentication")
                    PlaceholderExplanation("{model}", "Model name/identifier")
                    PlaceholderExplanation("{prompt}", "User message content")
                    PlaceholderExplanation("{assistant}", "Assistant message content")
                    PlaceholderExplanation("{system}", "System prompt (optional)")
                    PlaceholderExplanation("{thoughts}", "Thinking/reasoning content")
                    PlaceholderExplanation("{thoughts_signature}", "Thinking signature/marker")
                    PlaceholderExplanation("{tool_name}", "Tool/function name")
                    PlaceholderExplanation("{tool_description}", "Tool description")
                    PlaceholderExplanation("{tool_parameters}", "Tool parameters JSON")
                    PlaceholderExplanation("{tool_response}", "Tool execution response")
                }
            }
        }
    }
}

@Composable
private fun PlaceholderExplanation(placeholder: String, description: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = placeholder,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = Primary,
            modifier = Modifier.width(140.dp)
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun templateTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Primary,
    unfocusedBorderColor = OnSurface.copy(alpha = 0.3f),
    focusedTextColor = OnSurface,
    unfocusedTextColor = OnSurface
)

/**
 * Example body template for OpenAI-style API.
 */
private const val EXAMPLE_BODY_TEMPLATE = """{
  "model": "{model}",
  "stream": true,
  "messages": [
    {
      "role": "system",
      "content": "{system}"
    },
    {
      "role": "user",
      "content": "{prompt}"
    },
    {
      "role": "assistant",
      "content": "{assistant}"
    }
  ],
  "max_tokens": 4096,
  "temperature": 0.7
}"""
