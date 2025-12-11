package com.example.ApI.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptDialog(
    currentPrompt: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.system_prompt),
    projectPrompt: String? = null,
    projectName: String? = null,
    initialOverrideEnabled: Boolean = false,
    onOverrideToggle: ((Boolean) -> Unit)? = null
) {
    var prompt by remember { mutableStateOf(currentPrompt) }
    var showOverrideConfirmDialog by remember { mutableStateOf(false) }
    var pendingOverrideValue by remember { mutableStateOf(false) }
    val isProjectChat = projectPrompt != null && projectName != null
    var overrideEnabled by remember { mutableStateOf(initialOverrideEnabled) }

    // Sync overrideEnabled when initialOverrideEnabled changes (dialog reopened)
    LaunchedEffect(initialOverrideEnabled) {
        overrideEnabled = initialOverrideEnabled
    }

    // Update prompt when override state changes
    LaunchedEffect(overrideEnabled) {
        prompt = if (overrideEnabled && isProjectChat) {
            currentPrompt
        } else if (!overrideEnabled && isProjectChat) {
            projectPrompt ?: currentPrompt
        } else {
            currentPrompt
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Surface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(28.dp)
                        .fillMaxWidth()
                ) {
                    // Modern dialog header with optional override switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnSurface,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Override switch for project chats
                        if (isProjectChat) {
                            Switch(
                                checked = overrideEnabled,
                                onCheckedChange = { newValue ->
                                    if (newValue) {
                                        // Show confirmation dialog when turning on
                                        pendingOverrideValue = newValue
                                        showOverrideConfirmDialog = true
                                    } else {
                                        // Turn off directly
                                        overrideEnabled = newValue
                                        onOverrideToggle?.invoke(newValue)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Primary,
                                    checkedTrackColor = Primary.copy(alpha = 0.5f),
                                    uncheckedThumbColor = OnSurfaceVariant,
                                    uncheckedTrackColor = OnSurfaceVariant.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.height(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Modern input field
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isProjectChat && !overrideEnabled) SurfaceVariant.copy(alpha = 0.5f) else SurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { if (!(isProjectChat && !overrideEnabled)) prompt = it },
                            readOnly = isProjectChat && !overrideEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(4.dp),
                            placeholder = {
                                Text(
                                    text = if (isProjectChat && !overrideEnabled) "פרומפט מערכת לקריאה בלבד" else "Enter system prompt...",
                                    color = if (isProjectChat && !overrideEnabled) OnSurfaceVariant.copy(alpha = 0.5f) else OnSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = if (isProjectChat && !overrideEnabled) OnSurface.copy(alpha = 0.7f) else OnSurface,
                                unfocusedTextColor = if (isProjectChat && !overrideEnabled) OnSurface.copy(alpha = 0.7f) else OnSurface,
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                disabledTextColor = OnSurface.copy(alpha = 0.7f),
                                disabledContainerColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = if (isProjectChat && !overrideEnabled) OnSurface.copy(alpha = 0.7f) else OnSurface
                            ),
                            maxLines = 12,
                            enabled = !(isProjectChat && !overrideEnabled)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Modern action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            modifier = Modifier.clickable { onDismiss() }
                        ) {
                            Text(
                                text = if (isProjectChat && !overrideEnabled) "סגור" else stringResource(R.string.cancel),
                                color = OnSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                            )
                        }

                        if (!(isProjectChat && !overrideEnabled)) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Primary,
                                modifier = Modifier.clickable { onConfirm(prompt) }
                            ) {
                                Text(
                                    text = stringResource(R.string.approve),
                                    color = OnPrimary,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Override confirmation dialog
    if (showOverrideConfirmDialog && projectName != null) {
        AlertDialog(
            onDismissRequest = { showOverrideConfirmDialog = false },
            title = {
                Text(
                    text = "אישור הוספת פרומפט",
                    color = OnSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "שימו לב, הפרויקט \"$projectName\" מגדיר הנחיית מערכת (System Prompt) לשיחה זו. הפרומפט שתזינו ישורשר להנחיית המערכת של הפרויקט.",
                    color = OnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        overrideEnabled = pendingOverrideValue
                        onOverrideToggle?.invoke(pendingOverrideValue)
                        showOverrideConfirmDialog = false
                    }
                ) {
                    Text(
                        text = "אישור",
                        color = Primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverrideConfirmDialog = false }) {
                    Text(
                        text = "ביטול",
                        color = OnSurfaceVariant
                    )
                }
            },
            containerColor = Surface,
            tonalElevation = 0.dp
        )
    }
}

@Composable
fun ProviderSelectorDialog(
    providers: List<Provider>,
    onProviderSelected: (Provider) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Surface
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .widthIn(max = 300.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "בחירת ספק API",
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnSurface,
                            fontWeight = FontWeight.Bold
                        )

                        if (onRefresh != null) {
                            IconButton(
                                onClick = onRefresh,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh models",
                                    tint = Primary
                                )
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        items(providers) { provider ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onProviderSelected(provider) },
                                color = Surface
                            ) {
                                Text(
                                    text = stringResource(id = when(provider.provider) {
                                        "openai" -> R.string.provider_openai
                                        "poe" -> R.string.provider_poe
                                        "google" -> R.string.provider_google
                                        "anthropic" -> R.string.provider_anthropic
                                        "cohere" -> R.string.provider_cohere
                                        "openrouter" -> R.string.provider_openrouter
                                        else -> R.string.provider_openai
                                    }),
                                    color = OnSurface,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth()
                                )
                            }
                            HorizontalDivider(color = OnSurface.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorDialog(
    models: List<Model>,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    var customModelName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Surface
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .widthIn(max = 320.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "בחירת מודל",
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnSurface,
                            fontWeight = FontWeight.Bold
                        )

                        if (onRefresh != null) {
                            IconButton(
                                onClick = onRefresh,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh models",
                                    tint = Primary
                                )
                            }
                        }
                    }

                    // Custom model input section
                    Text(
                        text = "או הכנס שם מדויק:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    OutlinedTextField(
                        value = customModelName,
                        onValueChange = { customModelName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = "הכנס שם מדויק...",
                                color = OnSurface.copy(alpha = 0.7f)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = OnSurface.copy(alpha = 0.3f),
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface
                        ),
                        singleLine = true
                    )
                    
                    // Use custom model button
                    if (customModelName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onModelSelected(customModelName.trim()) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary,
                                contentColor = OnPrimary
                            )
                        ) {
                            Text("Use: ${customModelName.trim()}")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Divider
                    HorizontalDivider(color = OnSurface.copy(alpha = 0.3f))
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Available models section
                    Text(
                        text = "מודלים זמינים:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        items(models) { model ->
                            val modelName = model.name ?: model.toString()
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onModelSelected(modelName) },
                                color = Surface
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        text = modelName,
                                        color = OnSurface,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    // Display pricing information for Poe models
                                    model.pricing?.let { pricing ->
                                        PricingText(pricing)
                                    } ?: run {
                                        // Fallback to legacy min_points if no pricing object
                                        model.min_points?.let { points ->
                                            Text(
                                                text = "Min points: $points",
                                                color = OnSurface.copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = OnSurface.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddApiKeyDialog(
    providers: List<Provider>,
    onConfirm: (String, String, String?) -> Unit,
    onDismiss: () -> Unit,
    initialProvider: String? = null,
    initialApiKey: String? = null
) {
    var selectedProvider by remember { mutableStateOf(initialProvider ?: "") }
    var apiKey by remember { mutableStateOf(initialApiKey ?: "") }
    var customName by remember { mutableStateOf("") }
    var showProviderDropdown by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Surface
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.add_api_key),
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Provider Dropdown
                    ExposedDropdownMenuBox(
                        expanded = showProviderDropdown,
                        onExpandedChange = { showProviderDropdown = !showProviderDropdown }
                    ) {
                        OutlinedTextField(
                            value = selectedProvider,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                                .fillMaxWidth(),
                            label = { Text("Provider", color = OnSurface.copy(alpha = 0.7f)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showProviderDropdown) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = OnSurface.copy(alpha = 0.3f),
                                focusedTextColor = OnSurface,
                                unfocusedTextColor = OnSurface
                            )
                        )

                        ExposedDropdownMenu(
                            expanded = showProviderDropdown,
                            onDismissRequest = { showProviderDropdown = false }
                        ) {
                            providers.forEach { provider ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(id = when(provider.provider) {
                                                "openai" -> R.string.provider_openai
                                                "poe" -> R.string.provider_poe
                                                "google" -> R.string.provider_google
                                                "anthropic" -> R.string.provider_anthropic
                                                "cohere" -> R.string.provider_cohere
                                                "openrouter" -> R.string.provider_openrouter
                                                else -> R.string.provider_openai
                                            }),
                                            color = OnSurface
                                        )
                                    },
                                    onClick = {
                                        selectedProvider = provider.provider
                                        showProviderDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key", color = OnSurface.copy(alpha = 0.7f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = OnSurface.copy(alpha = 0.3f),
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Custom name field (optional)
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom Name (Optional)", color = OnSurface.copy(alpha = 0.7f)) },
                        placeholder = { Text("e.g., Work Account, Personal Key", color = OnSurface.copy(alpha = 0.5f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = OnSurface.copy(alpha = 0.3f),
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface
                        )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

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
                            Text(stringResource(R.string.cancel))
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { onConfirm(selectedProvider, apiKey, customName.takeIf { it.isNotBlank() }) },
                            enabled = selectedProvider.isNotEmpty() && apiKey.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary,
                                contentColor = OnPrimary
                            )
                        ) {
                            Text(stringResource(R.string.approve))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteApiKeyConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Surface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(28.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "מחיקת מפתח API",
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "פעולה זו תמחק את המפתח ולא ניתן יהיה לשחזר אותו!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnSurface
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            modifier = Modifier.clickable { onDismiss() }
                        ) {
                            Text(
                                text = "ביטול",
                                color = OnSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Red,
                            modifier = Modifier.clickable { onConfirm() }
                        ) {
                            Text(
                                text = "מחק",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatImportChoiceDialog(
    fileName: String,
    onLoadAsChat: () -> Unit,
    onAttachAsFile: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "קובץ צ'אט זוהה",
                color = OnSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "הקובץ ששותף מכיל צ'אט תקין. האם התכוונתם לטעון אותו להיסטוריית הצ'אט או להשתמש בו כקובץ מצורף?",
                    color = OnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilePresent,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = fileName,
                            color = OnSurface,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onLoadAsChat,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary
                )
            ) {
                Text(
                    text = "טעינה לצ'אט",
                    fontWeight = FontWeight.Medium
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onAttachAsFile,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = OnSurfaceVariant
                )
            ) {
                Text(text = "קובץ מצורף")
            }
        },
        containerColor = Surface,
        tonalElevation = 0.dp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatExportDialog(
    viewModel: ChatViewModel,
    uiState: com.example.ApI.data.model.ChatUiState,
    onDismiss: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header with title and close button
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ייצוא שיחה",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // JSON content text area - LTR for proper JSON formatting
                Column(modifier = Modifier.weight(1f)) {
                    // Edit mode indicator
                    if (isEditing) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "עריכה פעילה",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp),
                        color = if (isEditing)
                            MaterialTheme.colorScheme.surface
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        shape = MaterialTheme.shapes.medium,
                        border = if (isEditing)
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        else
                            null
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            TextField(
                                value = uiState.chatExportJson,
                                onValueChange = { viewModel.updateChatExportContent(it) },
                                modifier = Modifier.fillMaxSize(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                    unfocusedIndicatorColor = if (isEditing)
                                        MaterialTheme.colorScheme.outline
                                    else
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                                    cursorColor = MaterialTheme.colorScheme.primary
                                ),
                                readOnly = !isEditing,
                                placeholder = {
                                    if (uiState.chatExportJson.isEmpty()) {
                                        Text("No content to export")
                                    }
                                }
                            )
                        }
                    }
                }

                // Bottom buttons - RTL layout
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Edit/Save button - toggles between edit and save modes
                        OutlinedButton(
                            onClick = {
                                if (isEditing) {
                                    // Save changes and exit edit mode
                                    isEditing = false
                                    // Note: The actual content is already updated via onValueChange
                                } else {
                                    // Enter edit mode
                                    isEditing = true
                                }
                            },
                            modifier = Modifier.width(48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = if (isEditing) "Save changes" else "Edit",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Share button - fixed width for icon only
                        Button(
                            onClick = { viewModel.shareChatExportContent() },
                            modifier = Modifier.width(48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Save button - takes remaining space for text
                        Button(
                            onClick = { viewModel.saveChatExportToDownloads() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Save to downloads",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("שמירה להורדות")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Displays pricing information for Poe models.
 * Supports three pricing types:
 * - Fixed pricing: exact points per message
 * - Token-based pricing: points per 1k input/output tokens
 * - Legacy pricing: minimum points (deprecated)
 */
@Composable
private fun PricingText(pricing: PoePricing) {
    when {
        // Fixed pricing (exact points per message)
        pricing.isFixedPricing -> {
            Text(
                text = "Points: ${pricing.points}",
                color = OnSurface.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        // Token-based pricing (points per 1k tokens)
        pricing.isTokenBasedPricing -> {
            Column {
                pricing.input_points_per_1k?.let { inputPoints ->
                    Text(
                        text = "Input: ${formatPoints(inputPoints)} pts/1k tokens",
                        color = OnSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                pricing.output_points_per_1k?.let { outputPoints ->
                    Text(
                        text = "Output: ${formatPoints(outputPoints)} pts/1k tokens",
                        color = OnSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        // Legacy pricing (min_points)
        pricing.isLegacyPricing -> {
            Text(
                text = "Min points: ${pricing.min_points}",
                color = OnSurface.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Formats points value, removing unnecessary decimal places
 */
private fun formatPoints(points: Double): String {
    return if (points == points.toLong().toDouble()) {
        points.toLong().toString()
    } else {
        String.format("%.2f", points)
    }
}