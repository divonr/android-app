package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ApI.R
import com.example.ApI.data.model.CustomProviderConfig
import com.example.ApI.data.model.generateProviderKey
import com.example.ApI.ui.theme.*

/**
 * Dialog for creating or editing a custom OpenAI-compatible provider.
 *
 * @param existingConfig If provided, the dialog is in edit mode; otherwise in create mode
 * @param onConfirm Callback when user confirms with the provider config
 * @param onDismiss Callback when user dismisses the dialog
 */
@Composable
fun CustomProviderDialog(
    existingConfig: CustomProviderConfig? = null,
    onConfirm: (CustomProviderConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val isEditing = existingConfig != null

    var name by remember { mutableStateOf(existingConfig?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existingConfig?.baseUrl ?: "https://") }
    var defaultModel by remember { mutableStateOf(existingConfig?.defaultModel ?: "") }
    var authHeaderName by remember { mutableStateOf(existingConfig?.authHeaderName ?: "Authorization") }
    var authHeaderFormat by remember { mutableStateOf(existingConfig?.authHeaderFormat ?: "Bearer {key}") }
    var extraHeaders by remember {
        mutableStateOf(existingConfig?.extraHeaders?.toList() ?: emptyList())
    }
    var showAdvanced by remember { mutableStateOf(false) }

    val isValid = name.isNotBlank() && baseUrl.isNotBlank() && defaultModel.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = if (isEditing) stringResource(R.string.edit_custom_provider)
                               else stringResource(R.string.create_custom_provider),
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Provider Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.provider_name), color = OnSurface.copy(alpha = 0.7f)) },
                        placeholder = { Text("e.g., My Local LLM", color = OnSurface.copy(alpha = 0.5f)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = textFieldColors()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Base URL
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.api_base_url), color = OnSurface.copy(alpha = 0.7f)) },
                        placeholder = { Text("https://api.example.com/v1/chat/completions", color = OnSurface.copy(alpha = 0.5f)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = textFieldColors()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Default Model
                    OutlinedTextField(
                        value = defaultModel,
                        onValueChange = { defaultModel = it },
                        label = { Text(stringResource(R.string.default_model), color = OnSurface.copy(alpha = 0.7f)) },
                        placeholder = { Text("e.g., gpt-4, llama-3.1", color = OnSurface.copy(alpha = 0.5f)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = textFieldColors()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Advanced Settings Toggle
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
                                text = if (showAdvanced) stringResource(R.string.hide_advanced)
                                       else stringResource(R.string.show_advanced),
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

                        // Auth Header Name
                        OutlinedTextField(
                            value = authHeaderName,
                            onValueChange = { authHeaderName = it },
                            label = { Text(stringResource(R.string.auth_header_name), color = OnSurface.copy(alpha = 0.7f)) },
                            placeholder = { Text("Authorization or x-api-key", color = OnSurface.copy(alpha = 0.5f)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = textFieldColors()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Auth Header Format
                        OutlinedTextField(
                            value = authHeaderFormat,
                            onValueChange = { authHeaderFormat = it },
                            label = { Text(stringResource(R.string.auth_header_format), color = OnSurface.copy(alpha = 0.7f)) },
                            placeholder = { Text("Bearer {key} or {key}", color = OnSurface.copy(alpha = 0.5f)) },
                            supportingText = { Text(stringResource(R.string.auth_format_hint), color = OnSurface.copy(alpha = 0.6f)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = textFieldColors()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Extra Headers Section
                        Text(
                            text = stringResource(R.string.extra_headers),
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
                                        extraHeaders = extraHeaders.toMutableList().apply {
                                            set(index, newName to headerValue)
                                        }
                                    },
                                    label = { Text("Header", color = OnSurface.copy(alpha = 0.7f)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    colors = textFieldColors()
                                )
                                OutlinedTextField(
                                    value = headerValue,
                                    onValueChange = { newValue ->
                                        extraHeaders = extraHeaders.toMutableList().apply {
                                            set(index, headerName to newValue)
                                        }
                                    },
                                    label = { Text("Value", color = OnSurface.copy(alpha = 0.7f)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    colors = textFieldColors()
                                )
                                IconButton(
                                    onClick = {
                                        extraHeaders = extraHeaders.toMutableList().apply {
                                            removeAt(index)
                                        }
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
                                extraHeaders = extraHeaders + ("" to "")
                            }
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.add_header))
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

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
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val config = CustomProviderConfig(
                                    id = existingConfig?.id ?: java.util.UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    providerKey = existingConfig?.providerKey ?: generateProviderKey(name),
                                    baseUrl = baseUrl.trim(),
                                    defaultModel = defaultModel.trim(),
                                    authHeaderName = authHeaderName.trim(),
                                    authHeaderFormat = authHeaderFormat.trim(),
                                    extraHeaders = extraHeaders
                                        .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                                        .toMap(),
                                    createdAt = existingConfig?.createdAt ?: System.currentTimeMillis(),
                                    isEnabled = existingConfig?.isEnabled ?: true
                                )
                                onConfirm(config)
                            },
                            enabled = isValid,
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text(
                                if (isEditing) stringResource(R.string.save)
                                else stringResource(R.string.create)
                            )
                        }
                    }
                }
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
