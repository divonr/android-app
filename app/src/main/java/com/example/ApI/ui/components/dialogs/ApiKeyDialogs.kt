package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ApI.R
import com.example.ApI.data.model.CustomProviderConfig
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.getDisplayNameFromProviderKey
import com.example.ApI.data.model.isCustomProvider
import com.example.ApI.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddApiKeyDialog(
    providers: List<Provider>,
    customProviders: List<CustomProviderConfig> = emptyList(),
    onConfirm: (String, String, String?) -> Unit,
    onDismiss: () -> Unit,
    onCreateCustomProvider: (CustomProviderConfig) -> Unit = {},
    onEditCustomProvider: (CustomProviderConfig) -> Unit = {},
    onDeleteCustomProvider: (CustomProviderConfig) -> Unit = {},
    initialProvider: String? = null,
    initialApiKey: String? = null
) {
    var selectedProvider by remember { mutableStateOf(initialProvider ?: "") }
    var apiKey by remember { mutableStateOf(initialApiKey ?: "") }
    var customName by remember { mutableStateOf("") }
    var showProviderDropdown by remember { mutableStateOf(false) }

    // Custom provider dialog state
    var showCreateCustomProviderDialog by remember { mutableStateOf(false) }
    var customProviderToEdit by remember { mutableStateOf<CustomProviderConfig?>(null) }

    // Get display name for selected provider
    val selectedProviderDisplayName = remember(selectedProvider, customProviders) {
        if (selectedProvider.isEmpty()) {
            ""
        } else if (isCustomProvider(selectedProvider)) {
            customProviders.find { it.providerKey == selectedProvider }?.name
                ?: getDisplayNameFromProviderKey(selectedProvider)
        } else {
            selectedProvider
        }
    }

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
                            value = selectedProviderDisplayName,
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
                            // Built-in providers
                            providers.filter { !isCustomProvider(it.provider) }.forEach { provider ->
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
                                                "llmstats" -> R.string.provider_llmstats
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

                            // Custom providers with edit/delete icons
                            if (customProviders.isNotEmpty()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = OnSurface.copy(alpha = 0.1f)
                                )

                                customProviders.forEach { customProvider ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = customProvider.name,
                                                    color = Color(0xFF2196F3),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Row {
                                                    IconButton(
                                                        onClick = {
                                                            showProviderDropdown = false
                                                            customProviderToEdit = customProvider
                                                        },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Edit,
                                                            contentDescription = "Edit",
                                                            tint = OnSurfaceVariant,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            showProviderDropdown = false
                                                            onDeleteCustomProvider(customProvider)
                                                        },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Delete,
                                                            contentDescription = "Delete",
                                                            tint = Color.Red.copy(alpha = 0.7f),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedProvider = customProvider.providerKey
                                            showProviderDropdown = false
                                        }
                                    )
                                }
                            }

                            // "Create new provider" option
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = OnSurface.copy(alpha = 0.1f)
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = null,
                                            tint = Primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.define_new_provider),
                                            color = Primary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                },
                                onClick = {
                                    showProviderDropdown = false
                                    showCreateCustomProviderDialog = true
                                }
                            )
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

    // Create/Edit Custom Provider Dialog
    if (showCreateCustomProviderDialog || customProviderToEdit != null) {
        CustomProviderDialog(
            existingConfig = customProviderToEdit,
            onConfirm = { config ->
                if (customProviderToEdit != null) {
                    onEditCustomProvider(config)
                } else {
                    onCreateCustomProvider(config)
                    // Auto-select the newly created provider
                    selectedProvider = config.providerKey
                }
                showCreateCustomProviderDialog = false
                customProviderToEdit = null
            },
            onDismiss = {
                showCreateCustomProviderDialog = false
                customProviderToEdit = null
            }
        )
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
