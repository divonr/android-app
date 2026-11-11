package com.example.ApI.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.example.ApI.R
import com.example.ApI.data.attachments.AttachmentTransferState
import com.example.ApI.data.model.SelectedFile
import com.example.ApI.data.model.WebSearchSupport
import com.example.ApI.ui.components.emoji.EmojiKeyboardHost
import com.example.ApI.ui.components.emoji.EmojiKeyboardMode
import com.example.ApI.ui.components.emoji.EmojiKeyboardState
import com.example.ApI.ui.components.emoji.EmojiStore
import com.example.ApI.ui.components.emoji.deleteBeforeCursor
import com.example.ApI.ui.components.emoji.insertAtCursor
import com.example.ApI.ui.theme.*
import kotlinx.coroutines.awaitCancellation

/**
 * Preview area for selected files - shows as list for single file, grid for multiple
 */
@Composable
fun SelectedFilesPreview(
    selectedFiles: List<SelectedFile>,
    onRemoveFile: (SelectedFile) -> Unit,
    modifier: Modifier = Modifier,
    /** By local path: files uploading to the account's store, or whose upload failed. */
    transfers: Map<String, AttachmentTransferState> = emptyMap(),
    onRetryUpload: (SelectedFile) -> Unit = {}
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
                    onRemove = { onRemoveFile(file) },
                    transfer = file.localPath?.let { transfers[it] },
                    onRetryUpload = { onRetryUpload(file) }
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
                    onRemove = { onRemoveFile(file) },
                    transfer = file.localPath?.let { transfers[it] },
                    onRetryUpload = { onRetryUpload(file) }
                )
            }
        }
    }
}

/**
 * Main message input area: emoji keyboard toggle, text field, file attachment, web search and
 * send buttons, and under them the space of the system keyboard or of the emoji keyboard that
 * takes its place.
 */
@OptIn(ExperimentalComposeUiApi::class)
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

    // The text with its cursor lives here (not inside the field) so the emoji keyboard can
    // insert and delete at the cursor
    var textFieldValue by remember { mutableStateOf(TextFieldValue(currentMessage)) }
    // Sync external value changes (e.g., when entering edit mode with message content)
    LaunchedEffect(currentMessage) {
        if (textFieldValue.text != currentMessage) {
            // When external value changes, update text but place cursor at end
            textFieldValue = TextFieldValue(
                text = currentMessage,
                selection = TextRange(currentMessage.length)
            )
        }
    }
    val applyEdit: (TextFieldValue) -> Unit = { newValue ->
        textFieldValue = newValue
        onMessageChange(newValue.text)
    }

    val emojiKeyboard = remember { EmojiKeyboardState() }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val fieldInteractions = remember { MutableInteractionSource() }
    var fieldFocused by remember { mutableStateOf(false) }

    // Focus requests wait for the composition that follows a mode change, so the field's input
    // session starts under the right rule below (kept from, or let through to, the system keyboard)
    var pendingFocus by remember { mutableStateOf(PendingFocus.NONE) }
    LaunchedEffect(pendingFocus) {
        when (pendingFocus) {
            PendingFocus.NONE -> return@LaunchedEffect
            PendingFocus.QUIET -> if (!fieldFocused) focusRequester.requestFocus()
            PendingFocus.WITH_KEYBOARD -> {
                if (!fieldFocused) focusRequester.requestFocus()
                keyboardController?.show()
            }
        }
        pendingFocus = PendingFocus.NONE
    }

    // While the emoji keyboard is up the field keeps its focus and cursor but may not start an
    // input session, which is what brings the system keyboard up. Swapping the rule restarts a
    // running session: closing it (keyboard goes down) or opening it (keyboard comes up).
    val keepSystemKeyboardAway = emojiKeyboard.isOpen
    val inputInterceptor = remember(keepSystemKeyboardAway) {
        PlatformTextInputInterceptor { request, nextHandler ->
            if (keepSystemKeyboardAway) awaitCancellation() else nextHandler.startInputMethod(request)
        }
    }

    // Tapping into the text field goes back to the system keyboard
    LaunchedEffect(fieldInteractions) {
        fieldInteractions.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release && emojiKeyboard.mode == EmojiKeyboardMode.Emoji) {
                emojiKeyboard.switchToSystemKeyboard()
                pendingFocus = PendingFocus.WITH_KEYBOARD
            }
        }
    }

    // Back closes the emoji keyboard first (the search goes back to the panel)
    BackHandler(enabled = emojiKeyboard.isOpen) {
        if (emojiKeyboard.mode == EmojiKeyboardMode.Search) {
            emojiKeyboard.mode = EmojiKeyboardMode.Emoji
            pendingFocus = PendingFocus.QUIET
        } else {
            // Without the focus the field starts no input session, so no keyboard comes up
            focusManager.clearFocus()
            emojiKeyboard.mode = EmojiKeyboardMode.Closed
        }
    }

    // Read the dataset ahead of the first opening
    val context = LocalContext.current
    LaunchedEffect(Unit) { EmojiStore.get(context).categories() }

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
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
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Emoji keyboard toggle (a keyboard icon while the emoji keyboard is up)
                        EmojiKeyboardToggle(
                            emojiKeyboardOpen = emojiKeyboard.isOpen,
                            onClick = {
                                if (emojiKeyboard.isOpen) {
                                    emojiKeyboard.switchToSystemKeyboard()
                                    pendingFocus = PendingFocus.WITH_KEYBOARD
                                } else {
                                    emojiKeyboard.mode = EmojiKeyboardMode.Emoji
                                    pendingFocus = PendingFocus.QUIET
                                }
                            }
                        )

                        // Message Input Field
                        InterceptPlatformTextInput(interceptor = inputInterceptor) {
                            MessageTextField(
                                value = textFieldValue,
                                onValueChange = applyEdit,
                                interactionSource = fieldInteractions,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { focusState ->
                                        fieldFocused = focusState.isFocused
                                        // Tapped while searching emojis: back to plain typing
                                        if (focusState.isFocused && emojiKeyboard.mode == EmojiKeyboardMode.Search) {
                                            emojiKeyboard.switchToSystemKeyboard()
                                        }
                                    }
                            )
                        }

                        // Add Files Button
                        FileAttachmentButton(
                            hasFiles = selectedFiles.isNotEmpty()
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
                            Spacer(modifier = Modifier.width(4.dp))

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

        EmojiKeyboardHost(
            state = emojiKeyboard,
            onEmojiPicked = { emoji -> applyEdit(textFieldValue.insertAtCursor(emoji)) },
            onBackspace = { applyEdit(textFieldValue.deleteBeforeCursor()) },
            onFocusComposer = { pendingFocus = PendingFocus.QUIET }
        )
    }
}

private enum class PendingFocus { NONE, QUIET, WITH_KEYBOARD }

/**
 * Opens the emoji keyboard; while it is open, shows a keyboard icon and brings the system
 * keyboard back instead.
 */
@Composable
fun EmojiKeyboardToggle(
    emojiKeyboardOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (emojiKeyboardOpen) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions,
            contentDescription = stringResource(
                if (emojiKeyboardOpen) R.string.emoji_keyboard_show_keyboard else R.string.emoji_keyboard_open
            ),
            tint = OnSurface,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * File attachment button with dropdown menu
 */
@Composable
fun FileAttachmentButton(
    hasFiles: Boolean,
    modifier: Modifier = Modifier
) {
    var showFileMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (hasFiles) Primary.copy(alpha = 0.1f) else Color.Transparent,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable { showFileMenu = !showFileMenu }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.attach_file),
                    tint = if (hasFiles) Primary else OnSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        // Anchored to the button: the menu opens over it and, the button now being near the
        // end edge, extends towards the text field
        FileSelectionDropdown(
            expanded = showFileMenu,
            onDismiss = { showFileMenu = false }
        )
    }
}

/**
 * Clean message text field. Its text and cursor are held by the caller.
 */
@Composable
fun MessageTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        interactionSource = interactionSource,
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
