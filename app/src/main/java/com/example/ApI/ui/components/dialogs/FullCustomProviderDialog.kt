package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import com.example.ApI.data.model.*
import com.example.ApI.ui.theme.*
import com.example.ApI.util.TemplateExpander

/**
 * Dialog for creating or editing a full custom provider with complete streaming configuration.
 * Provides three tabs: Request, Streaming, and Model configuration.
 *
 * @param existingConfig If provided, the dialog is in edit mode; otherwise in create mode
 * @param onConfirm Callback when user confirms with the provider config
 * @param onSwitchToOpenAICompatible Callback when user checks the OpenAI-compatible checkbox
 * @param onDismiss Callback when user dismisses the dialog
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullCustomProviderDialog(
    existingConfig: FullCustomProviderConfig? = null,
    onConfirm: (FullCustomProviderConfig) -> Unit,
    onSwitchToOpenAICompatible: () -> Unit,
    onDismiss: () -> Unit
) {
    val isEditing = existingConfig != null
    val coroutineScope = rememberCoroutineScope()

    // Tab state with pager
    val tabs = listOf("Request", "Streaming", "Model")
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    // Request tab state
    var name by remember { mutableStateOf(existingConfig?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existingConfig?.baseUrl ?: "https://") }
    var authHeaderName by remember { mutableStateOf(existingConfig?.authHeaderName ?: "Authorization") }
    var authHeaderFormat by remember { mutableStateOf(existingConfig?.authHeaderFormat ?: "Bearer {key}") }
    var extraHeaders by remember {
        mutableStateOf(existingConfig?.extraHeaders?.toList() ?: emptyList())
    }
    var bodyTemplate by remember { mutableStateOf(existingConfig?.bodyTemplate ?: DEFAULT_BODY_TEMPLATE) }
    var messageFields by remember { mutableStateOf(existingConfig?.messageFields) }
    var streamingConfirmed by remember { mutableStateOf(existingConfig != null) }
    var showAdvancedHeaders by remember { mutableStateOf(false) }

    // Streaming tab state
    var parserType by remember { mutableStateOf(existingConfig?.parserType ?: StreamParserType.DATA_ONLY) }
    var parserConfig by remember { mutableStateOf(existingConfig?.parserConfig ?: ParserConfig()) }
    var eventMappings by remember {
        mutableStateOf(existingConfig?.eventMappings ?: emptyMap())
    }

    // Model tab state
    var defaultModel by remember { mutableStateOf(existingConfig?.defaultModel ?: "") }

    // Validation: Calculate which placeholders are present anywhere in the config
    // Include messageFields templates in the placeholder search
    val messageFieldsPlaceholders = TemplateExpander.findPlaceholdersInMessageFields(messageFields)
    val presentPlaceholders = remember(bodyTemplate, baseUrl, authHeaderFormat, extraHeaders, messageFields) {
        val bodyAndHeaderPlaceholders = BodyTemplatePlaceholders.ALL.filter { placeholder ->
            bodyTemplate.contains(placeholder) ||
                    baseUrl.contains(placeholder) ||
                    authHeaderFormat.contains(placeholder) ||
                    extraHeaders.any { (k, v) -> k.contains(placeholder) || v.contains(placeholder) }
        }.toSet()
        bodyAndHeaderPlaceholders + messageFieldsPlaceholders
    }

    // When messageFields is enabled, {prompt}, {assistant}, {system} are expected there, not in bodyTemplate
    // But {key} and {model} are still required in bodyTemplate or headers
    val requiredInBodyOrHeaders = if (messageFields?.hasAnyFields() == true) {
        setOf(BodyTemplatePlaceholders.KEY, BodyTemplatePlaceholders.MODEL)
    } else {
        BodyTemplatePlaceholders.REQUIRED
    }

    // Validate that messageFields templates contain their required placeholders
    val messageFieldsValid = messageFields?.let { fields ->
        TemplateExpander.validateMessageFields(fields).isEmpty()
    } ?: true

    val allRequiredPlaceholdersPresent = requiredInBodyOrHeaders.all { it in presentPlaceholders } && messageFieldsValid

    val isValid = name.isNotBlank() &&
            baseUrl.isNotBlank() &&
            defaultModel.isNotBlank() &&
            allRequiredPlaceholdersPresent &&
            streamingConfirmed

    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                ) {
                    // Title
                    Text(
                        text = if (isEditing) "עריכת ספק מותאם אישית" else "הגדרת ספק מותאם אישית",
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // OpenAI-compatible checkbox
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSwitchToOpenAICompatible() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Checkbox(
                            checked = false,
                            onCheckedChange = { if (it) onSwitchToOpenAICompatible() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Primary,
                                uncheckedColor = OnSurface.copy(alpha = 0.6f)
                            )
                        )
                        Text(
                            text = "ספק תומך פורמט OpenAI",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tab Row
                    TabRow(
                        selectedTabIndex = pagerState.currentPage,
                        containerColor = Surface,
                        contentColor = Primary,
                        divider = {}
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                                text = {
                                    Text(
                                        text = when (title) {
                                            "Request" -> "בקשה"
                                            "Streaming" -> "Streaming"
                                            "Model" -> "מודל"
                                            else -> title
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                selectedContentColor = Primary,
                                unselectedContentColor = OnSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tab Content with HorizontalPager for swipe support
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) { pageIndex ->
                        when (pageIndex) {
                            0 -> RequestTabContent(
                                name = name,
                                onNameChange = { name = it },
                                baseUrl = baseUrl,
                                onBaseUrlChange = { baseUrl = it },
                                authHeaderName = authHeaderName,
                                onAuthHeaderNameChange = { authHeaderName = it },
                                authHeaderFormat = authHeaderFormat,
                                onAuthHeaderFormatChange = { authHeaderFormat = it },
                                extraHeaders = extraHeaders,
                                onExtraHeadersChange = { extraHeaders = it },
                                showAdvancedHeaders = showAdvancedHeaders,
                                onShowAdvancedHeadersChange = { showAdvancedHeaders = it },
                                bodyTemplate = bodyTemplate,
                                onBodyTemplateChange = { bodyTemplate = it },
                                messageFields = messageFields,
                                onMessageFieldsChange = { messageFields = it },
                                streamingConfirmed = streamingConfirmed,
                                onStreamingConfirmedChange = { streamingConfirmed = it },
                                presentPlaceholders = presentPlaceholders
                            )
                            1 -> StreamingTabContent(
                                parserType = parserType,
                                onParserTypeChange = { parserType = it },
                                parserConfig = parserConfig,
                                onParserConfigChange = { parserConfig = it },
                                eventMappings = eventMappings,
                                onEventMappingsChange = { eventMappings = it }
                            )
                            2 -> ModelTabContent(
                                defaultModel = defaultModel,
                                onDefaultModelChange = { defaultModel = it }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = OnSurface.copy(alpha = 0.7f)
                            )
                        ) {
                            Text("ביטול")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val config = FullCustomProviderConfig(
                                    id = existingConfig?.id ?: java.util.UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    providerKey = existingConfig?.providerKey ?: generateFullCustomProviderKey(name),
                                    baseUrl = baseUrl.trim(),
                                    defaultModel = defaultModel.trim(),
                                    authHeaderName = authHeaderName.trim(),
                                    authHeaderFormat = authHeaderFormat.trim(),
                                    extraHeaders = extraHeaders
                                        .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                                        .toMap(),
                                    bodyTemplate = bodyTemplate,
                                    messageFields = messageFields,
                                    parserType = parserType,
                                    parserConfig = parserConfig,
                                    eventMappings = eventMappings,
                                    isOpenAICompatible = false,
                                    createdAt = existingConfig?.createdAt ?: System.currentTimeMillis(),
                                    isEnabled = existingConfig?.isEnabled ?: true
                                )
                                onConfirm(config)
                            },
                            enabled = isValid,
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text(if (isEditing) "שמור" else "צור")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Content for the Request tab
 */
@Composable
private fun RequestTabContent(
    name: String,
    onNameChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    authHeaderName: String,
    onAuthHeaderNameChange: (String) -> Unit,
    authHeaderFormat: String,
    onAuthHeaderFormatChange: (String) -> Unit,
    extraHeaders: List<Pair<String, String>>,
    onExtraHeadersChange: (List<Pair<String, String>>) -> Unit,
    showAdvancedHeaders: Boolean,
    onShowAdvancedHeadersChange: (Boolean) -> Unit,
    bodyTemplate: String,
    onBodyTemplateChange: (String) -> Unit,
    messageFields: MessageFieldsConfig?,
    onMessageFieldsChange: (MessageFieldsConfig?) -> Unit,
    streamingConfirmed: Boolean,
    onStreamingConfirmedChange: (Boolean) -> Unit,
    presentPlaceholders: Set<String>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Provider Name
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("שם הספק", color = OnSurface.copy(alpha = 0.7f)) },
            placeholder = { Text("e.g., My Custom API", color = OnSurface.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = textFieldColors()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Base URL
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("כתובת API", color = OnSurface.copy(alpha = 0.7f)) },
            placeholder = { Text("https://api.example.com/v1/chat", color = OnSurface.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = textFieldColors()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Advanced Settings Toggle (Auth Headers)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onShowAdvancedHeadersChange(!showAdvancedHeaders) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (showAdvancedHeaders) "הסתר הגדרות Headers" else "הצג הגדרות Headers",
                    style = MaterialTheme.typography.labelLarge,
                    color = Primary
                )
                Icon(
                    imageVector = if (showAdvancedHeaders) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Primary
                )
            }
        }

        if (showAdvancedHeaders) {
            Spacer(modifier = Modifier.height(16.dp))

            // Auth Header Name
            OutlinedTextField(
                value = authHeaderName,
                onValueChange = onAuthHeaderNameChange,
                label = { Text("שם Header אימות", color = OnSurface.copy(alpha = 0.7f)) },
                placeholder = { Text("Authorization or x-api-key", color = OnSurface.copy(alpha = 0.5f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = textFieldColors()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Auth Header Format
            OutlinedTextField(
                value = authHeaderFormat,
                onValueChange = onAuthHeaderFormatChange,
                label = { Text("פורמט Header אימות", color = OnSurface.copy(alpha = 0.7f)) },
                placeholder = { Text("Bearer {key} or {key}", color = OnSurface.copy(alpha = 0.5f)) },
                supportingText = { Text("השתמש ב-{key} כ-placeholder למפתח API", color = OnSurface.copy(alpha = 0.6f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = textFieldColors()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Extra Headers Section
            Text(
                text = "Headers נוספים",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            extraHeaders.forEachIndexed { index, (headerName, headerValue) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = headerName,
                        onValueChange = { newName ->
                            onExtraHeadersChange(extraHeaders.toMutableList().apply {
                                set(index, newName to headerValue)
                            })
                        },
                        label = { Text("Header", color = OnSurface.copy(alpha = 0.7f)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = textFieldColors()
                    )
                    OutlinedTextField(
                        value = headerValue,
                        onValueChange = { newValue ->
                            onExtraHeadersChange(extraHeaders.toMutableList().apply {
                                set(index, headerName to newValue)
                            })
                        },
                        label = { Text("Value", color = OnSurface.copy(alpha = 0.7f)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = textFieldColors()
                    )
                    IconButton(
                        onClick = {
                            onExtraHeadersChange(extraHeaders.toMutableList().apply {
                                removeAt(index)
                            })
                        }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove header",
                            tint = Color.Red.copy(alpha = 0.7f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            TextButton(
                onClick = {
                    onExtraHeadersChange(extraHeaders + ("" to ""))
                }
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("הוסף Header")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Body Template Editor
        BodyTemplateEditor(
            template = bodyTemplate,
            onTemplateChange = onBodyTemplateChange,
            presentPlaceholders = presentPlaceholders
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Dynamic Message Fields Editor
        MessageFieldsEditor(
            messageFields = messageFields,
            onMessageFieldsChange = onMessageFieldsChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Streaming Configuration Confirmation Checkbox
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onStreamingConfirmedChange(!streamingConfirmed) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Checkbox(
                checked = streamingConfirmed,
                onCheckedChange = onStreamingConfirmedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = Primary,
                    uncheckedColor = OnSurface.copy(alpha = 0.6f)
                )
            )
            Text(
                text = "אישור הגדרות Streaming",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface
            )
        }

        if (!streamingConfirmed) {
            Text(
                text = "יש להגדיר את הגדרות ה-Streaming בלשונית המתאימה ולאשר",
                style = MaterialTheme.typography.bodySmall,
                color = AccentYellow.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * Content for the Streaming tab
 */
@Composable
private fun StreamingTabContent(
    parserType: StreamParserType,
    onParserTypeChange: (StreamParserType) -> Unit,
    parserConfig: ParserConfig,
    onParserConfigChange: (ParserConfig) -> Unit,
    eventMappings: Map<StreamEventType, EventMapping>,
    onEventMappingsChange: (Map<StreamEventType, EventMapping>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Parser Type Selector
        ParserTypeSelector(
            selectedType = parserType,
            onTypeSelected = onParserTypeChange
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Parser Config Editor
        ParserConfigEditor(
            parserType = parserType,
            config = parserConfig,
            onConfigChange = onParserConfigChange
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Event Mappings Editor
        EventMappingsEditor(
            mappings = eventMappings,
            onMappingsChange = onEventMappingsChange
        )
    }
}

/**
 * Content for the Model tab
 */
@Composable
private fun ModelTabContent(
    defaultModel: String,
    onDefaultModelChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "הגדרות מודל",
            style = MaterialTheme.typography.labelLarge,
            color = OnSurface,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "הזן את שם המודל שישמש כברירת מחדל עבור ספק זה.",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Default Model TextField
        OutlinedTextField(
            value = defaultModel,
            onValueChange = onDefaultModelChange,
            label = { Text("מודל ברירת מחדל", color = OnSurface.copy(alpha = 0.7f)) },
            placeholder = { Text("e.g., gpt-4, claude-3, llama-3.1", color = OnSurface.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = textFieldColors()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Info Surface
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "הערה:",
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "שם המודל יישלח לשרת ב-placeholder {model} בתבנית הבקשה.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Primary,
    unfocusedBorderColor = OnSurface.copy(alpha = 0.3f),
    focusedTextColor = OnSurface,
    unfocusedTextColor = OnSurface
)

/**
 * Default body template for new full custom providers
 */
private const val DEFAULT_BODY_TEMPLATE = """{
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
  "max_tokens": 4096
}"""
