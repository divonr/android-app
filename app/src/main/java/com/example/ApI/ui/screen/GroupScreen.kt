package com.example.ApI.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import coil.compose.AsyncImage
import com.example.ApI.ui.components.*
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.min
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    viewModel: ChatViewModel,
    uiState: ChatUiState,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileNameFromUri(context, it) ?: "unknown_file"
            val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
            uiState.currentGroup?.let { group ->
                viewModel.addFileToProject(group.group_id, it, fileName, mimeType)
            }
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
            viewModel.clearSnackbar()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .background(Background),
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        uiState.currentGroup?.let { group ->
                            viewModel.createNewChatInGroup(group.group_id)
                        }
                    },
                    containerColor = Primary,
                    contentColor = Color.White,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New Chat in Group",
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            containerColor = Background
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(it)
            ) {
            // Back handler for Android back button
            BackHandler {
                viewModel.navigateToScreen(Screen.ChatHistory)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                // Modern Minimalist Top Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Surface,
                    shadowElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left side - Back arrow
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = SurfaceVariant,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable { viewModel.navigateToScreen(Screen.ChatHistory) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to chat history",
                                        tint = OnSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Center - Group name
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = uiState.currentGroup?.group_name ?: "קבוצה",
                                style = MaterialTheme.typography.titleSmall,
                                color = OnSurface,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Right side - Project Switch
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "פרויקט",
                                color = OnSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Switch(
                                checked = uiState.currentGroup?.is_project ?: false,
                                onCheckedChange = { isChecked ->
                                    uiState.currentGroup?.let { group ->
                                        viewModel.toggleGroupProjectStatus(group.group_id)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Primary,
                                    checkedTrackColor = Primary.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }

                // Project Area (shown when group is marked as project)
                uiState.currentGroup?.let { group ->
                    if (group.is_project) {
                        ProjectArea(
                            group = group,
                            viewModel = viewModel,
                            onInstructionsClick = { viewModel.openProjectInstructionsDialog() },
                            onAddFileClick = { filePickerLauncher.launch("*/*") },
                            onFileClick = { attachment ->
                                openAttachmentFile(context, attachment)
                            }
                        )
                    }
                }

                // Group Chats Content
                uiState.currentGroup?.let { group ->
                    val groupChats = uiState.chatHistory.filter { it.group == group.group_id }

                    if (groupChats.isEmpty()) {
                        // Empty state for group
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = OnSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "אין עדיין שיחות בקבוצה זו",
                                color = OnSurfaceVariant,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "לחץ לחיצה ארוכה על שיחה כדי להוסיפה לקבוצה",
                                color = OnSurfaceVariant,
                                fontSize = 14.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            // Sort chats by last message timestamp (descending)
                            val sortedChats = groupChats.sortedWith(
                                compareByDescending<Chat> { getLastTimestampOrNull(it) != null }
                                    .thenByDescending { getLastTimestampOrNull(it) ?: Long.MIN_VALUE }
                            )

                            items(
                                items = sortedChats,
                                key = { it.id }
                            ) { chat ->
                                GroupChatHistoryItem(
                                    chat = chat,
                                    onClick = {
                                        viewModel.selectChat(chat)
                                        viewModel.navigateToScreen(Screen.Chat)
                                    },
                                    onLongClick = { offset ->
                                        viewModel.showChatContextMenu(chat, offset)
                                    }
                                )
                            }
                        }
                    }
                } ?: run {
                    // Error state - group not found
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = OnSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "הקבוצה לא נמצאה",
                            color = OnSurfaceVariant,
                            fontSize = 18.sp
                        )
                    }
                }
            }

            // Context menu for chat actions
            uiState.chatContextMenu?.let { menuState ->
                ChatContextMenu(
                    chat = menuState.chat,
                    position = menuState.position,
                    onDismiss = { viewModel.hideChatContextMenu() },
                    onRename = {
                        viewModel.showRenameDialog(it)
                    },
                    onAIRename = {
                        viewModel.renameChatWithAI(it)
                    },
                    onDelete = {
                        viewModel.showDeleteConfirmation(it)
                    },
                    groups = uiState.groups,
                    onAddToGroup = { groupId ->
                        viewModel.addChatToGroup(menuState.chat.chat_id, groupId)
                    },
                    onCreateNewGroup = {
                        viewModel.showGroupDialog()
                    },
                    onRemoveFromGroup = {
                        viewModel.removeChatFromGroup(menuState.chat.chat_id)
                    }
                )
            }

            // Delete confirmation dialog
            uiState.showDeleteConfirmation?.let { chat ->
                AlertDialog(
                    onDismissRequest = { viewModel.hideDeleteConfirmation() },
                    title = {
                        Text(
                            stringResource(R.string.delete_confirmation_title),
                            color = OnSurface
                        )
                    },
                    text = {
                        Text(
                            stringResource(R.string.delete_confirmation_message),
                            color = OnSurfaceVariant
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteChat(chat)
                                viewModel.hideDeleteConfirmation()
                            }
                        ) {
                            Text(stringResource(R.string.delete), color = Color.Red)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.hideDeleteConfirmation() }) {
                            Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
                        }
                    },
                    containerColor = Surface,
                    tonalElevation = 0.dp
                )
            }

            // Rename dialog
            uiState.showRenameDialog?.let { chat ->
                var newTitle by remember { mutableStateOf(chat.title) }

                AlertDialog(
                    onDismissRequest = { viewModel.hideRenameDialog() },
                    title = {
                        Text(
                            stringResource(R.string.rename),
                            color = OnSurface
                        )
                    },
                    text = {
                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            label = { Text(stringResource(R.string.chat_title), color = OnSurfaceVariant) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = OnSurface,
                                unfocusedTextColor = OnSurface,
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = Gray500,
                                focusedLabelColor = Primary,
                                unfocusedLabelColor = OnSurfaceVariant,
                                cursorColor = Primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.renameChat(chat, newTitle)
                                viewModel.hideRenameDialog()
                            }
                        ) {
                            Text(stringResource(R.string.save), color = Primary)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.hideRenameDialog() }) {
                            Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
                        }
                    },
                    containerColor = Surface,
                    tonalElevation = 0.dp
                )
            }

            // Group creation dialog
            if (uiState.showGroupDialog) {
                var groupName by remember { mutableStateOf("") }

                AlertDialog(
                    onDismissRequest = { viewModel.hideGroupDialog() },
                    title = {
                        Text(
                            "צור קבוצה חדשה",
                            color = OnSurface
                        )
                    },
                    text = {
                        OutlinedTextField(
                            value = groupName,
                            onValueChange = { groupName = it },
                            label = { Text("שם הקבוצה", color = OnSurfaceVariant) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = OnSurface,
                                unfocusedTextColor = OnSurface,
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = Gray500,
                                focusedLabelColor = Primary,
                                unfocusedLabelColor = OnSurfaceVariant,
                                cursorColor = Primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.createNewGroup(groupName.trim())
                            },
                            enabled = groupName.isNotBlank()
                        ) {
                            Text("צור", color = if (groupName.isNotBlank()) Primary else OnSurfaceVariant)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.hideGroupDialog() }) {
                            Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
                        }
                    },
                    containerColor = Surface,
                    tonalElevation = 0.dp
                )
            }

                            // System Prompt Dialog
            if (uiState.showSystemPromptDialog) {
                SystemPromptDialog(
                    currentPrompt = uiState.systemPrompt,
                    onConfirm = { viewModel.updateGroupSystemPrompt(it) },
                    onDismiss = { viewModel.hideSystemPromptDialog() },
                    title = "הוראות"
                )
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupChatHistoryItem(
    chat: Chat,
    onClick: () -> Unit,
    onLongClick: (DpOffset) -> Unit,
    modifier: Modifier = Modifier
) {
    var itemTopLeft by remember { mutableStateOf(Offset.Zero) }
    var itemSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val density = LocalDensity.current

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .onGloballyPositioned { coordinates ->
                // Capture item bounds and approximate center in window coordinates
                val pos = coordinates.localToWindow(Offset.Zero)
                itemTopLeft = pos
                itemSize = coordinates.size.run { androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat()) }
            },
        colors = CardDefaults.cardColors(
            containerColor = Surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { /* Handle press if needed */ },
                        onLongPress = { pressOffset ->
                            // Position menu to the right of the item, vertically at press Y
                            val anchor = with(density) {
                                val anchorX = (itemTopLeft.x + itemSize.width - 8f).toDp()
                                val anchorY = (itemTopLeft.y + pressOffset.y).toDp()
                                DpOffset(anchorX, anchorY)
                            }
                            onLongClick(anchor)
                        },
                        onTap = { onClick() }
                    )
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Model initial circle
            val lastAssistantMessage = chat.messages.lastOrNull { it.role == "assistant" }
            val modelName = lastAssistantMessage?.model ?: chat.model
            val initial = getModelInitial(modelName)

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Chat content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = chat.title,
                    color = OnSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                val lastMessage = chat.messages.lastOrNull()
                if (lastMessage != null) {
                    Text(
                        text = lastMessage.content.take(100),
                        color = OnSurfaceVariant,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Timestamp
            Column(
                horizontalAlignment = Alignment.End
            ) {
                val ts = getLastTimestampOrNull(chat)
                if (ts != null) {
                    Text(
                        text = formatTimestamp(ts),
                        color = OnSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                if (chat.messages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Forum,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = OnSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = chat.messages.size.toString(),
                            color = OnSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectArea(
    group: ChatGroup,
    viewModel: ChatViewModel,
    onInstructionsClick: () -> Unit,
    onAddFileClick: () -> Unit,
    onFileClick: (Attachment) -> Unit,
    modifier: Modifier = Modifier
) {
    var showFileMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = Surface,
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Instructions section - Entire row is clickable
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onInstructionsClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Pen icon (visual indicator, not separately clickable)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Primary.copy(alpha = 0.1f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "הוראות",
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Instructions text
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "הוראות",
                        style = MaterialTheme.typography.titleSmall,
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = group.system_prompt ?: "לא הוגדרו הוראות",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Files section
            Text(
                text = "קבצים",
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (group.group_attachments.isEmpty()) {
                // Empty files area
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clickable(onClick = onAddFileClick)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "הוסיפו קבצים...",
                            color = OnSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                // Files grid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Add file button
                    item {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceVariant,
                            modifier = Modifier
                                .size(100.dp)
                                .clickable(onClick = onAddFileClick)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "הוסף קובץ",
                                    tint = OnSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    // File thumbnails with delete functionality
                    group.group_attachments.forEachIndexed { index, attachment ->
                        item {
                            FileThumbnail(
                                attachment = attachment,
                                onClick = { onFileClick(attachment) },
                                onDelete = { viewModel.removeFileFromProject(group.group_id, index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FileThumbnail(
    attachment: Attachment,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant,
        modifier = modifier
            .size(100.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // File icon based on type
                val icon = when {
                    attachment.mime_type.startsWith("image/") -> Icons.Default.Image
                    attachment.mime_type.startsWith("video/") -> Icons.Default.VideoFile
                    attachment.mime_type.startsWith("audio/") -> Icons.Default.AudioFile
                    attachment.mime_type.contains("pdf") -> Icons.Default.PictureAsPdf
                    else -> Icons.Default.InsertDriveFile
                }

                Icon(
                    imageVector = icon,
                    contentDescription = attachment.file_name,
                    tint = OnSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )

                Text(
                    text = attachment.file_name,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Delete button (X) in top-right corner
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .clickable(onClick = onDelete)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "מחק קובץ",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// Helper function to get file name from URI
private fun getFileNameFromUri(context: android.content.Context, uri: android.net.Uri): String? {
    var fileName: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            fileName = cursor.getString(nameIndex)
        }
    }
    return fileName
}

// Helper function to open attachment file
private fun openAttachmentFile(context: android.content.Context, attachment: Attachment) {
    attachment.local_file_path?.let { path ->
        val file = java.io.File(path)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                context.applicationContext.packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mime_type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Handle file opening error
        }
    }
}
