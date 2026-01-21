package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import com.example.ApI.data.model.ToolCallConfig
import com.example.ApI.ui.theme.*

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
                description = "זוגות event:/data:",
                example = "event: content_block_delta\ndata: {\"delta\": {\"text\": \"Hello\"}}",
                isSelected = selectedType == StreamParserType.EVENT_DATA,
                onSelect = { onTypeSelected(StreamParserType.EVENT_DATA) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // DATA_ONLY option
            ParserTypeOption(
                type = StreamParserType.DATA_ONLY,
                title = "DATA_ONLY",
                description = "data: בלבד",
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

@Composable
fun EventMappingsEditor(
    mappings: Map<StreamEventType, EventMapping>,
    onMappingsChange: (Map<StreamEventType, EventMapping>) -> Unit,
    toolCallConfig: ToolCallConfig,
    onToolCallConfigChange: (ToolCallConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddFieldMenu by remember { mutableStateOf(false) }

    // Track which optional fields are currently shown
    var showThinkingStart by remember {
        mutableStateOf(mappings.containsKey(StreamEventType.THINKING_START))
    }
    var showThinkingContent by remember {
        mutableStateOf(mappings.containsKey(StreamEventType.THINKING_CONTENT))
    }
    var showToolCalls by remember {
        mutableStateOf(toolCallConfig.isConfigured())
    }

    val essentialEvents = listOf(StreamEventType.TEXT_CONTENT, StreamEventType.STREAM_END)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                text = "מיפויי אירועים",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Essential events - always shown
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
                    },
                    onRemove = null // Essential fields cannot be removed
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Optional fields that have been added
            if (showThinkingStart) {
                EventMappingRow(
                    eventType = StreamEventType.THINKING_START,
                    mapping = mappings[StreamEventType.THINKING_START],
                    onMappingChange = { newMapping ->
                        val newMappings = mappings.toMutableMap()
                        if (newMapping != null) {
                            newMappings[StreamEventType.THINKING_START] = newMapping
                        } else {
                            newMappings.remove(StreamEventType.THINKING_START)
                        }
                        onMappingsChange(newMappings)
                    },
                    onRemove = {
                        showThinkingStart = false
                        val newMappings = mappings.toMutableMap()
                        newMappings.remove(StreamEventType.THINKING_START)
                        onMappingsChange(newMappings)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (showThinkingContent) {
                EventMappingRow(
                    eventType = StreamEventType.THINKING_CONTENT,
                    mapping = mappings[StreamEventType.THINKING_CONTENT],
                    onMappingChange = { newMapping ->
                        val newMappings = mappings.toMutableMap()
                        if (newMapping != null) {
                            newMappings[StreamEventType.THINKING_CONTENT] = newMapping
                        } else {
                            newMappings.remove(StreamEventType.THINKING_CONTENT)
                        }
                        onMappingsChange(newMappings)
                    },
                    onRemove = {
                        showThinkingContent = false
                        val newMappings = mappings.toMutableMap()
                        newMappings.remove(StreamEventType.THINKING_CONTENT)
                        onMappingsChange(newMappings)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (showToolCalls) {
                ToolCallConfigEditor(
                    config = toolCallConfig,
                    onConfigChange = onToolCallConfigChange,
                    onRemove = {
                        showToolCalls = false
                        onToolCallConfigChange(ToolCallConfig())
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Add field button with dropdown
            Box {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAddFieldMenu = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "הוספת שדה",
                            style = MaterialTheme.typography.labelLarge,
                            color = Primary
                        )
                    }
                }

                DropdownMenu(
                    expanded = showAddFieldMenu,
                    onDismissRequest = { showAddFieldMenu = false }
                ) {
                    if (!showThinkingStart) {
                        DropdownMenuItem(
                            text = { Text("תחילת חשיבה") },
                            onClick = {
                                showThinkingStart = true
                                showAddFieldMenu = false
                            }
                        )
                    }
                    if (!showThinkingContent) {
                        DropdownMenuItem(
                            text = { Text("תוכן חשיבה") },
                            onClick = {
                                showThinkingContent = true
                                showAddFieldMenu = false
                            }
                        )
                    }
                    if (!showToolCalls) {
                        DropdownMenuItem(
                            text = { Text("קריאות כלים") },
                            onClick = {
                                showToolCalls = true
                                showAddFieldMenu = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventMappingRow(
    eventType: StreamEventType,
    mapping: EventMapping?,
    onMappingChange: (EventMapping?) -> Unit,
    onRemove: (() -> Unit)?
) {
    val eventTypeLabels = mapOf(
        StreamEventType.TEXT_CONTENT to "תוכן טקסט",
        StreamEventType.STREAM_END to "סיום זרימה",
        StreamEventType.THINKING_START to "תחילת חשיבה",
        StreamEventType.THINKING_CONTENT to "תוכן חשיבה"
    )

    val eventNamePlaceholders = mapOf(
        StreamEventType.TEXT_CONTENT to "content_block_delta",
        StreamEventType.STREAM_END to "message_stop",
        StreamEventType.THINKING_START to "thinking_start",
        StreamEventType.THINKING_CONTENT to "thinking_delta"
    )

    val fieldPathPlaceholders = mapOf(
        StreamEventType.TEXT_CONTENT to "delta.text",
        StreamEventType.STREAM_END to "",
        StreamEventType.THINKING_START to "",
        StreamEventType.THINKING_CONTENT to "delta.thinking"
    )

    var eventName by remember(mapping) { mutableStateOf(mapping?.eventName ?: "") }
    var fieldPath by remember(mapping) { mutableStateOf(mapping?.fieldPath ?: "") }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = eventTypeLabels[eventType] ?: eventType.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                    fontWeight = FontWeight.Medium
                )
                if (onRemove != null) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "הסר",
                            tint = OnSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

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

@Composable
fun ToolCallConfigEditor(
    config: ToolCallConfig,
    onConfigChange: (ToolCallConfig) -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceVariant.copy(alpha = 0.3f),
            modifier = modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "קריאות כלים",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        fontWeight = FontWeight.Medium
                    )
                    if (onRemove != null) {
                        IconButton(
                            onClick = onRemove,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "הסר",
                                tint = OnSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Event name
                OutlinedTextField(
                    value = config.eventName,
                    onValueChange = { onConfigChange(config.copy(eventName = it)) },
                    label = { Text("שם אירוע", color = OnSurface.copy(alpha = 0.7f)) },
                    placeholder = { Text("content_block_start", color = OnSurface.copy(alpha = 0.4f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = streamingTextFieldColors()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Tool name path and Tool ID path in same row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = config.toolNamePath,
                        onValueChange = { onConfigChange(config.copy(toolNamePath = it)) },
                        label = { Text("נתיב שם כלי", color = OnSurface.copy(alpha = 0.7f)) },
                        placeholder = { Text("content_block.name", color = OnSurface.copy(alpha = 0.4f)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = streamingTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = config.toolIdPath,
                        onValueChange = { onConfigChange(config.copy(toolIdPath = it)) },
                        label = { Text("נתיב מזהה", color = OnSurface.copy(alpha = 0.7f)) },
                        placeholder = { Text("content_block.id", color = OnSurface.copy(alpha = 0.4f)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = streamingTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Parameters event name and path in same row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = config.parametersEventName,
                        onValueChange = { onConfigChange(config.copy(parametersEventName = it)) },
                        label = { Text("אירוע פרמטרים", color = OnSurface.copy(alpha = 0.7f)) },
                        placeholder = { Text("content_block_delta", color = OnSurface.copy(alpha = 0.4f)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = streamingTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = config.parametersPath,
                        onValueChange = { onConfigChange(config.copy(parametersPath = it)) },
                        label = { Text("נתיב פרמטרים", color = OnSurface.copy(alpha = 0.7f)) },
                        placeholder = { Text("delta.partial_json", color = OnSurface.copy(alpha = 0.4f)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = streamingTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
