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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatHistoryScreen(
    viewModel: ChatViewModel,
    uiState: ChatUiState,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
            viewModel.clearSnackbar()
        }
    }
    
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(Background),
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("A") }
                                    withStyle(SpanStyle(fontWeight = FontWeight.Normal)) { append("p") }
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("I") }
                                },
                                color = OnSurface,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                                modifier = Modifier.padding(start = 42.dp)
                            )
                            Row {
                                IconButton(onClick = { viewModel.navigateToScreen(Screen.ApiKeys) }) {
                                    Icon(
                                        Icons.Default.Key,
                                        contentDescription = "API Keys",
                                        tint = OnSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { viewModel.navigateToScreen(Screen.UserSettings) }) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = "Settings",
                                        tint = OnSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.createNewChat() },
                containerColor = Primary,
                contentColor = Color.White,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New Chat",
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = Background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.chatHistory.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Forum,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = OnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "אין עדיין שיחות",
                        color = OnSurfaceVariant,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "לחץ על + כדי להתחיל שיחה חדשה",
                        color = OnSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // Sort: chats with timestamp first (desc), then those without timestamp last
                    val sortedChats = uiState.chatHistory.sortedWith(
                        compareByDescending<com.example.ApI.data.model.Chat> { getLastTimestampOrNull(it) != null }
                            .thenByDescending { getLastTimestampOrNull(it) ?: Long.MIN_VALUE }
                    )
                    items(
                        items = sortedChats,
                        key = { it.id }
                    ) { chat ->
                        ChatHistoryItem(
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatHistoryItem(
    chat: Chat,
    onClick: () -> Unit,
    onLongClick: (DpOffset) -> Unit,
    modifier: Modifier = Modifier
) {
    var itemPosition by remember { mutableStateOf(DpOffset.Zero) }
    
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .onGloballyPositioned { coordinates ->
                itemPosition = DpOffset(
                    x = coordinates.size.width.dp / 2,
                    y = coordinates.size.height.dp / 2
                )
            },
        colors = CardDefaults.cardColors(
            containerColor = Surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { onLongClick(itemPosition) }
                )
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

fun getModelInitial(model: String): String {
    return when {
        model.contains("gpt-4", ignoreCase = true) -> "G4"
        model.contains("gpt-3", ignoreCase = true) -> "G3"
        model.contains("claude", ignoreCase = true) -> "C"
        model.contains("gemini", ignoreCase = true) -> "Gm"
        model.contains("llama", ignoreCase = true) -> "L"
        model.contains("mistral", ignoreCase = true) -> "M"
        else -> model.take(1).uppercase()
    }
}

fun formatTimestamp(timestamp: Long): String {
    val instant = Instant.ofEpochMilli(timestamp)
    val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    
    return when {
        date.isEqual(today) -> {
            val time = instant.atZone(ZoneId.systemDefault()).toLocalTime()
            time.format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        date.isEqual(today.minusDays(1)) -> "אתמול"
        ChronoUnit.DAYS.between(date, today) < 7 -> {
            val dayOfWeek = date.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL,
                java.util.Locale.forLanguageTag("he")
            )
            dayOfWeek
        }
        else -> date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }
}

@Composable
private fun ChatContextMenu(
    chat: Chat,
    position: DpOffset,
    onDismiss: () -> Unit,
    onRename: (Chat) -> Unit,
    onAIRename: (Chat) -> Unit,
    onDelete: (Chat) -> Unit
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = position,
        properties = PopupProperties(focusable = true),
        modifier = Modifier
            .background(
                Surface,
                RoundedCornerShape(16.dp)
            )
            .width(200.dp)
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.update_chat_name),
                    color = OnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onRename(chat)
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.update_chat_name_ai),
                    color = OnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onAIRename(chat)
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        HorizontalDivider(
            color = OnSurface.copy(alpha = 0.1f),
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.delete_chat),
                    color = AccentRed,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onDelete(chat)
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
    }
}

// Helper: returns epoch millis of last message if present, otherwise null
private fun getLastTimestampOrNull(chat: Chat): Long? {
    val iso = chat.messages.lastOrNull()?.datetime ?: return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
