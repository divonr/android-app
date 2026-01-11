package com.example.ApI.ui.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.example.ApI.R
import com.example.ApI.data.model.SelectedFile
import com.example.ApI.data.model.WebSearchSupport
import com.example.ApI.ui.theme.*

/**
 * Preview area for selected files - shows as list for single file, grid for multiple
 */
@Composable
fun SelectedFilesPreview(
    selectedFiles: List<SelectedFile>,
    onRemoveFile: (SelectedFile) -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedFiles.isEmpty()) return

    if (selectedFiles.size == 1) {
        // Single file - show as list item
        LazyColumn(
            modifier = modifier
                .heightIn(max = 120.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            items(selectedFiles) { file ->
                FilePreview(
                    file = file,
                    onRemove = { onRemoveFile(file) }
                )
            }
        }
    } else {
        // Multiple files - show as grid of thumbnails
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 80.dp),
            modifier = modifier
                .heightIn(max = 160.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(selectedFiles) { file ->
                FilePreviewThumbnail(
                    file = file,
                    onRemove = { onRemoveFile(file) }
                )
            }
        }
    }
}

/**
 * Main message input area with file attachment, text field, web search, and send buttons
 */
@Composable
fun ChatInputArea(
    currentMessage: String,
    onMessageChange: (String) -> Unit,
    selectedFiles: List<SelectedFile>,
    isEditMode: Boolean,
    isLoading: Boolean,
    isStreaming: Boolean,
    webSearchSupport: WebSearchSupport,
    webSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    onSendMessage: () -> Unit,
    onStopStreaming: () -> Unit,
    onFinishEditing: () -> Unit,
    onConfirmEditAndResend: () -> Unit,
    onFileSelected: (Uri, String, String) -> Unit,
    onMultipleFilesSelected: (List<Triple<Uri, String, String>>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Estimate line count based on newlines and text length
    // Count explicit newlines + estimate wrapped lines (approx 35 chars per line)
    val estimatedLines = remember(currentMessage) {
        val newlineCount = currentMessage.count { it == '\n' }
        val longestLineLength = currentMessage.split('\n').maxOfOrNull { it.length } ?: 0
        val wrappedLines = if (longestLineLength > 35) (longestLineLength / 35) else 0
        newlineCount + 1 + wrappedLines
    }
    val isExpanded = estimatedLines >= 3
    val showWebSearch = webSearchSupport != WebSearchSupport.UNSUPPORTED

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SurfaceVariant,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 20.dp,
                vertical = 16.dp
            )
        ) {
            // Input container
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Add Files Button
                    FileAttachmentButton(
                        hasFiles = selectedFiles.isNotEmpty(),
                        onFileSelected = onFileSelected,
                        onMultipleFilesSelected = onMultipleFilesSelected
                    )

                    // Message Input Field
                    MessageTextField(
                        value = currentMessage,
                        onValueChange = onMessageChange,
                        modifier = Modifier.weight(1f)
                    )

                    // Action buttons area - stacked vertically when expanded
                    if (isExpanded && showWebSearch) {
                        // Vertical layout: web search on top, then edit buttons or send
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            WebSearchToggle(
                                enabled = webSearchEnabled,
                                required = webSearchSupport == WebSearchSupport.REQUIRED,
                                onClick = onToggleWebSearch
                            )
                            if (isEditMode) {
                                // Stack all 3 buttons vertically: web search, confirm, resend
                                ConfirmEditButton(
                                    enabled = currentMessage.isNotEmpty() && !isLoading && !isStreaming,
                                    onClick = onFinishEditing
                                )
                                ConfirmEditAndResendButton(
                                    enabled = currentMessage.isNotEmpty() && !isLoading && !isStreaming,
                                    onClick = onConfirmEditAndResend
                                )
                            } else {
                                SendButton(
                                    isLoading = isLoading,
                                    isStreaming = isStreaming,
                                    hasContent = currentMessage.isNotEmpty() || selectedFiles.isNotEmpty(),
                                    onSend = onSendMessage,
                                    onStop = onStopStreaming
                                )
                            }
                        }
                    } else {
                        // Horizontal layout: web search and send button side by side
                        if (showWebSearch) {
                            WebSearchToggle(
                                enabled = webSearchEnabled,
                                required = webSearchSupport == WebSearchSupport.REQUIRED,
                                onClick = onToggleWebSearch
                            )
                        }

                        // Action Buttons (Edit mode or Send/Stop)
                        if (isEditMode) {
                            EditModeButtons(
                                enabled = currentMessage.isNotEmpty() && !isLoading && !isStreaming,
                                onFinishEditing = onFinishEditing,
                                onConfirmEditAndResend = onConfirmEditAndResend
                            )
                        } else {
                            SendButton(
                                isLoading = isLoading,
                                isStreaming = isStreaming,
                                hasContent = currentMessage.isNotEmpty() || selectedFiles.isNotEmpty(),
                                onSend = onSendMessage,
                                onStop = onStopStreaming
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * File attachment button with dropdown menu
 */
@Composable
fun FileAttachmentButton(
    hasFiles: Boolean,
    onFileSelected: (Uri, String, String) -> Unit,
    onMultipleFilesSelected: (List<Triple<Uri, String, String>>) -> Unit,
    modifier: Modifier = Modifier
) {
    var showFileMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (hasFiles) Primary.copy(alpha = 0.1f) else Color.Transparent,
            modifier = Modifier
                .size(24.dp)
                .clickable { showFileMenu = !showFileMenu }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add files",
                    tint = if (hasFiles) Primary else OnSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        FileSelectionDropdown(
            expanded = showFileMenu,
            onDismiss = { showFileMenu = false },
            onFileSelected = onFileSelected,
            onMultipleFilesSelected = onMultipleFilesSelected
        )
    }
}

/**
 * Clean message text field
 * Uses TextFieldValue internally to maintain cursor position and prevent scroll jumping on focus
 */
@Composable
fun MessageTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Use TextFieldValue to maintain cursor position and prevent scroll reset on focus
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value)) }

    // Sync external value changes (e.g., when entering edit mode with message content)
    LaunchedEffect(value) {
        if (textFieldValue.text != value) {
            // When external value changes, update text but place cursor at end
            textFieldValue = TextFieldValue(
                text = value,
                selection = TextRange(value.length)
            )
        }
    }

    OutlinedTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            textFieldValue = newValue
            onValueChange(newValue.text)
        },
        modifier = modifier,
        placeholder = {
            Text(
                text = stringResource(R.string.type_message),
                color = OnSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = OnSurface,
            unfocusedTextColor = OnSurface,
            unfocusedContainerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions.Default.copy(
            keyboardType = KeyboardType.Text
        ),
        maxLines = 6,
        minLines = 1
    )
}

/**
 * Web search toggle button
 */
@Composable
fun WebSearchToggle(
    enabled: Boolean,
    required: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = when {
            enabled -> Primary.copy(alpha = 0.15f)
            required -> Primary.copy(alpha = 0.1f)
            else -> Color.Transparent
        },
        modifier = modifier
            .size(40.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = "Web search",
                tint = when {
                    enabled -> Primary
                    required -> Primary.copy(alpha = 0.7f)
                    else -> OnSurfaceVariant
                },
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Confirm edit button (checkmark)
 */
@Composable
fun ConfirmEditButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (enabled) Primary else Primary.copy(alpha = 0.3f),
        modifier = modifier
            .size(40.dp)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "עדכן הודעה",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Confirm edit and resend button (send icon)
 */
@Composable
fun ConfirmEditAndResendButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (enabled) Primary else Primary.copy(alpha = 0.3f),
        modifier = modifier
            .size(40.dp)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.send_message),
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Edit mode action buttons (confirm edit + resend) - horizontal layout
 */
@Composable
fun EditModeButtons(
    enabled: Boolean,
    onFinishEditing: () -> Unit,
    onConfirmEditAndResend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ConfirmEditButton(
            enabled = enabled,
            onClick = onFinishEditing
        )
        ConfirmEditAndResendButton(
            enabled = enabled,
            onClick = onConfirmEditAndResend
        )
    }
}

/**
 * Send/Stop button with loading indicator
 */
@Composable
fun SendButton(
    isLoading: Boolean,
    isStreaming: Boolean,
    hasContent: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = isLoading || isStreaming
    val canSend = hasContent || isActive

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (canSend) Primary else Primary.copy(alpha = 0.3f),
        modifier = modifier
            .size(40.dp)
            .clickable(enabled = canSend) {
                if (isActive) {
                    onStop()
                } else {
                    onSend()
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isActive) {
                // Loading circle with stop icon inside
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop streaming",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_message),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
