package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.ApI.data.model.Provider
import com.example.ApI.ui.theme.*

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
