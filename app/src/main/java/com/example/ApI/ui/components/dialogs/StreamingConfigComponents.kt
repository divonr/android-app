package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.example.ApI.data.model.EventMapping
import com.example.ApI.data.model.ParserConfig
import com.example.ApI.data.model.StreamEventType
import com.example.ApI.data.model.StreamParserType
import com.example.ApI.ui.theme.*

/**
 * Radio button selector for choosing the streaming parser type.
 * Shows visual examples of each parser format.
 */
@Composable
fun ParserTypeSelector(
    selectedType: StreamParserType,
    onTypeSelected: (StreamParserType) -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                text = "סוג פרסר",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // EVENT_DATA option
            ParserTypeOption(
                type = StreamParserType.EVENT_DATA,
                title = "EVENT_DATA",
                description = "זוגות event:/data: (סגנון Anthropic, Cohere, Poe)",
                example = "event: content_block_delta\ndata: {\"delta\": {\"text\": \"Hello\"}}",
                isSelected = selectedType == StreamParserType.EVENT_DATA,
                onSelect = { onTypeSelected(StreamParserType.EVENT_DATA) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // DATA_ONLY option
            ParserTypeOption(
                type = StreamParserType.DATA_ONLY,
                title = "DATA_ONLY",
                description = "data: בלבד (סגנון OpenAI, Google)",
                example = "data: {\"type\": \"delta\", \"content\": \"Hello\"}\ndata: [DONE]",
                isSelected = selectedType == StreamParserType.DATA_ONLY,
                onSelect = { onTypeSelected(StreamParserType.DATA_ONLY) }
            )
        }
    }
}

@Composable
private fun ParserTypeOption(
    type: StreamParserType,
    title: String,
    description: String,
    example: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Primary.copy(alpha = 0.15f) else SurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = onSelect,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = Primary,
                        unselectedColor = OnSurface.copy(alpha = 0.6f)
                    )
                )
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) Primary else OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Code example
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Background.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = example,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = AccentGreen,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Editor for parser configuration fields based on parser type.
 */
@Composable
fun ParserConfigEditor(
    parserType: StreamParserType,
    config: ParserConfig,
    onConfigChange: (ParserConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                text = "הגדרות פרסר",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (parserType) {
                StreamParserType.EVENT_DATA -> {
                    EventDataParserConfig(config = config, onConfigChange = onConfigChange)
                }
                StreamParserType.DATA_ONLY -> {
                    DataOnlyParserConfig(config = config, onConfigChange = onConfigChange)
                }
            }
        }
    }
}

@Composable
private fun EventDataParserConfig(
    config: ParserConfig,
    onConfigChange: (ParserConfig) -> Unit
) {
    var stopEventsText by remember(config) {
        mutableStateOf(config.stopEvents.joinToString(", "))
    }

    OutlinedTextField(
        value = stopEventsText,
        onValueChange = { newValue ->
            stopEventsText = newValue
            val events = newValue.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            onConfigChange(config.copy(stopEvents = events))
        },
        label = { Text("אירועי עצירה", color = OnSurface.copy(alpha = 0.7f)) },
        placeholder = { Text("message_stop, done", color = OnSurface.copy(alpha = 0.5f)) },
        supportingText = { Text("הפרד באמצעות פסיקים", color = OnSurface.copy(alpha = 0.6f)) },
        modifier = Modifier.fillMaxWidth(),
        colors = streamingTextFieldColors()
    )
}

@Composable
private fun DataOnlyParserConfig(
    config: ParserConfig,
    onConfigChange: (ParserConfig) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Event type field
        OutlinedTextField(
            value = config.eventTypeField ?: "",
            onValueChange = { newValue ->
                onConfigChange(config.copy(
                    eventTypeField = newValue.takeIf { it.isNotBlank() }
                ))
            },
            label = { Text("שדה סוג אירוע", color = OnSurface.copy(alpha = 0.7f)) },
            placeholder = { Text("type", color = OnSurface.copy(alpha = 0.5f)) },
            supportingText = { Text("השאר ריק עבור מבנה קבוע (כמו Google)", color = OnSurface.copy(alpha = 0.6f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = streamingTextFieldColors()
        )

        // Done marker
        OutlinedTextField(
            value = config.doneMarker,
            onValueChange = { newValue ->
                onConfigChange(config.copy(doneMarker = newValue))
            },
            label = { Text("סמן סיום", color = OnSurface.copy(alpha = 0.7f)) },
            placeholder = { Text("[DONE]", color = OnSurface.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = streamingTextFieldColors()
        )

        // Skip keepalives checkbox
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onConfigChange(config.copy(skipKeepalives = !config.skipKeepalives))
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Checkbox(
                checked = config.skipKeepalives,
                onCheckedChange = { checked ->
                    onConfigChange(config.copy(skipKeepalives = checked))
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = Primary,
                    uncheckedColor = OnSurface.copy(alpha = 0.6f)
                )
            )
            Text(
                text = "דלג על הודעות keepalive",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface
            )
        }
    }
}

/**
 * Editor for event mappings with essential events always visible
 * and advanced events in an expandable section.
 */
@Composable
fun EventMappingsEditor(
    mappings: Map<StreamEventType, EventMapping>,
    onMappingsChange: (Map<StreamEventType, EventMapping>) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAdvanced by remember { mutableStateOf(false) }

    val essentialEvents = listOf(StreamEventType.TEXT_CONTENT, StreamEventType.STREAM_END)
    val advancedEvents = listOf(
        StreamEventType.THINKING_START,
        StreamEventType.THINKING_CONTENT,
        StreamEventType.TOOL_CALL_START,
        StreamEventType.TOOL_CALL_DELTA,
        StreamEventType.TOOL_CALL_END
    )

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                text = "מיפויי אירועים",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "מפה אירועי הזרמה של הספק לסוגים סמנטיים",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Essential events section
            Text(
                text = "אירועים חיוניים",
                style = MaterialTheme.typography.labelMedium,
                color = Primary,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            essentialEvents.forEach { eventType ->
                EventMappingRow(
                    eventType = eventType,
                    mapping = mappings[eventType],
                    onMappingChange = { newMapping ->
                        val newMappings = mappings.toMutableMap()
                        if (newMapping != null) {
                            newMappings[eventType] = newMapping
                        } else {
                            newMappings.remove(eventType)
                        }
                        onMappingsChange(newMappings)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Advanced events expandable section
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvanced = !showAdvanced }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (showAdvanced) "הסתר אירועים מתקדמים" else "הצג אירועים מתקדמים",
                        style = MaterialTheme.typography.labelLarge,
                        color = Primary
                    )
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Primary
                    )
                }
            }

            if (showAdvanced) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "אירועים מתקדמים",
                    style = MaterialTheme.typography.labelMedium,
                    color = Tertiary,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                advancedEvents.forEach { eventType ->
                    EventMappingRow(
                        eventType = eventType,
                        mapping = mappings[eventType],
                        onMappingChange = { newMapping ->
                            val newMappings = mappings.toMutableMap()
                            if (newMapping != null) {
                                newMappings[eventType] = newMapping
                            } else {
                                newMappings.remove(eventType)
                            }
                            onMappingsChange(newMappings)
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun EventMappingRow(
    eventType: StreamEventType,
    mapping: EventMapping?,
    onMappingChange: (EventMapping?) -> Unit
) {
    val eventTypeLabels = mapOf(
        StreamEventType.TEXT_CONTENT to "תוכן טקסט",
        StreamEventType.STREAM_END to "סיום זרימה",
        StreamEventType.THINKING_START to "תחילת חשיבה",
        StreamEventType.THINKING_CONTENT to "תוכן חשיבה",
        StreamEventType.TOOL_CALL_START to "תחילת קריאת כלי",
        StreamEventType.TOOL_CALL_DELTA to "דלתא קריאת כלי",
        StreamEventType.TOOL_CALL_END to "סיום קריאת כלי"
    )

    val eventNamePlaceholders = mapOf(
        StreamEventType.TEXT_CONTENT to "content_block_delta",
        StreamEventType.STREAM_END to "message_stop",
        StreamEventType.THINKING_START to "thinking_start",
        StreamEventType.THINKING_CONTENT to "thinking_delta",
        StreamEventType.TOOL_CALL_START to "tool_use",
        StreamEventType.TOOL_CALL_DELTA to "tool_call_delta",
        StreamEventType.TOOL_CALL_END to "tool_call_end"
    )

    val fieldPathPlaceholders = mapOf(
        StreamEventType.TEXT_CONTENT to "delta.text",
        StreamEventType.STREAM_END to "",
        StreamEventType.THINKING_START to "",
        StreamEventType.THINKING_CONTENT to "delta.thinking",
        StreamEventType.TOOL_CALL_START to "tool.name",
        StreamEventType.TOOL_CALL_DELTA to "delta.arguments",
        StreamEventType.TOOL_CALL_END to "tool.id"
    )

    var eventName by remember(mapping) { mutableStateOf(mapping?.eventName ?: "") }
    var fieldPath by remember(mapping) { mutableStateOf(mapping?.fieldPath ?: "") }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${eventTypeLabels[eventType] ?: eventType.name} (${eventType.name})",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = eventName,
                    onValueChange = { newValue ->
                        eventName = newValue
                        updateMapping(eventName, fieldPath, onMappingChange)
                    },
                    label = { Text("שם אירוע", color = OnSurface.copy(alpha = 0.7f)) },
                    placeholder = {
                        Text(
                            eventNamePlaceholders[eventType] ?: "",
                            color = OnSurface.copy(alpha = 0.4f)
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = streamingTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = fieldPath,
                    onValueChange = { newValue ->
                        fieldPath = newValue
                        updateMapping(eventName, fieldPath, onMappingChange)
                    },
                    label = { Text("נתיב שדה", color = OnSurface.copy(alpha = 0.7f)) },
                    placeholder = {
                        Text(
                            fieldPathPlaceholders[eventType] ?: "",
                            color = OnSurface.copy(alpha = 0.4f)
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = streamingTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = "dot notation: e.g., delta.text",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun updateMapping(
    eventName: String,
    fieldPath: String,
    onMappingChange: (EventMapping?) -> Unit
) {
    if (eventName.isBlank()) {
        onMappingChange(null)
    } else {
        onMappingChange(EventMapping(eventName = eventName, fieldPath = fieldPath))
    }
}

@Composable
private fun streamingTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Primary,
    unfocusedBorderColor = OnSurface.copy(alpha = 0.3f),
    focusedTextColor = OnSurface,
    unfocusedTextColor = OnSurface
)
