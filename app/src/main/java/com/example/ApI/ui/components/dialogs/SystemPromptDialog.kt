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
