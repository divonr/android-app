package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ApI.data.model.ChatUiState
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*

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
    uiState: ChatUiState,
    onDismiss: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }

    // Dark button colors (black bg, purple icon) — for Edit, Share, Link (inactive)
    val darkButtonColors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFF1A1A1A),
        contentColor = Primary
    )
    // Active link button colors (purple bg, white icon)
    val activeLinkButtonColors = ButtonDefaults.buttonColors(
        containerColor = Primary,
        contentColor = Color.White
    )

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
                        // Edit/Save button — dark style (black bg, purple icon)
                        Button(
                            onClick = {
                                if (isEditing) {
                                    isEditing = false
                                } else {
                                    isEditing = true
                                }
                            },
                            modifier = Modifier.width(48.dp),
                            colors = darkButtonColors,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = if (isEditing) "Save changes" else "Edit",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Share button — dark style (black bg, purple icon)
                        Button(
                            onClick = { viewModel.shareChatExportContent() },
                            modifier = Modifier.width(48.dp),
                            colors = darkButtonColors,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Link button — with floating menu
                        Box {
                            Button(
                                onClick = {
                                    if (uiState.isShareLinkActive) {
                                        // Toggle floating menu
                                        viewModel.toggleShareLinkMenu()
                                    } else {
                                        // Create new link
                                        viewModel.createShareLink()
                                    }
                                },
                                modifier = Modifier.width(48.dp),
                                colors = if (uiState.isShareLinkActive) activeLinkButtonColors else darkButtonColors,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                enabled = !uiState.isShareLinkLoading
                            ) {
                                if (uiState.isShareLinkLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = if (uiState.isShareLinkActive) Color.White else Primary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Link,
                                        contentDescription = "Link",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Floating menu for link actions
                            DropdownMenu(
                                expanded = uiState.showShareLinkMenu,
                                onDismissRequest = { viewModel.dismissShareLinkMenu() }
                            ) {
                                // Copy
                                DropdownMenuItem(
                                    text = { Text("העתקה") },
                                    onClick = {
                                        viewModel.copyShareLink()
                                        viewModel.dismissShareLinkMenu()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copy link",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                                // Update
                                DropdownMenuItem(
                                    text = { Text("עדכון") },
                                    onClick = {
                                        viewModel.updateShareLink()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Update link",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                                // Delete
                                DropdownMenuItem(
                                    text = { Text("מחיקה") },
                                    onClick = {
                                        viewModel.deleteShareLink()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Delete link",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }
                        }

                        // Save to Downloads button — takes remaining space
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
                            Text("להורדות")
                        }
                    }
                }
            }
        }
    }
}
