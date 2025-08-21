package com.example.ApI.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ChatHistoryDialog(
    chatHistory: List<Chat>,
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    onChatSelected: (Chat) -> Unit,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit
) {
    // Suppress legacy dialog content to avoid center-popup; side sheet replaces this.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Box {} }

    // Keep dialogs accessible if this function is ever called
    uiState.showRenameDialog?.let { chat ->
        val coroutineScope = rememberCoroutineScope()
        ChatRenameDialog(
            chat = chat,
            onDismiss = { viewModel.hideRenameDialog() },
            onConfirm = { newName ->
                coroutineScope.launch {
                    viewModel.renameChatManually(chat, newName)
                }
            }
        )
    }

    uiState.showDeleteConfirmation?.let { chat ->
        DeleteConfirmationDialog(
            chat = chat,
            onDismiss = { viewModel.hideDeleteConfirmation() },
            onConfirm = { viewModel.deleteChat(chat) }
        )
    }
}

@Composable
fun ChatHistoryPanel(
    chatHistory: List<Chat>,
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    onChatSelected: (Chat) -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "היסטוריית שיחות",
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnSurface,
                    fontWeight = FontWeight.Bold
                )
                
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SurfaceVariant,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onClose() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close",
                                tint = OnSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // New Chat Button (table-like style)
            OutlinedButton(
                onClick = onNewChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.new_chat))
            }

            HorizontalDivider(
                color = OnSurface.copy(alpha = 0.2f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // List
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(chatHistory.reversed()) { chat ->
                        var showLocalMenu by remember { mutableStateOf(false) }

                        Box {
                            ChatHistoryItem(
                                chat = chat,
                                onClick = { onChatSelected(chat) },
                                onLongClick = { showLocalMenu = true }
                            )

                            if (showLocalMenu) {
                                ChatContextMenu(
                                    chat = chat,
                                    position = DpOffset(x = 100.dp, y = (-20).dp),
                                    onDismiss = { showLocalMenu = false },
                                    onRename = {
                                        viewModel.showRenameDialog(it)
                                        showLocalMenu = false
                                    },
                                    onAIRename = {
                                        viewModel.renameChatWithAI(it)
                                        showLocalMenu = false
                                    },
                                    onDelete = {
                                        viewModel.showDeleteConfirmation(it)
                                        showLocalMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs used by context menu
    uiState.showRenameDialog?.let { chat ->
        val coroutineScope = rememberCoroutineScope()
        ChatRenameDialog(
            chat = chat,
            onDismiss = { viewModel.hideRenameDialog() },
            onConfirm = { newName ->
                coroutineScope.launch {
                    viewModel.renameChatManually(chat, newName)
                }
            }
        )
    }
    uiState.showDeleteConfirmation?.let { chat ->
        DeleteConfirmationDialog(
            chat = chat,
            onDismiss = { viewModel.hideDeleteConfirmation() },
            onConfirm = { viewModel.deleteChat(chat) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatHistoryItem(
    chat: Chat,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = { onLongClick() }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Text(
                text = chat.preview_name,
                color = OnSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            
            if (chat.messages.isNotEmpty()) {
                val lastMessage = chat.messages.lastOrNull { it.role == "user" }
                lastMessage?.let { msg ->
                    Text(
                        text = if (msg.text.length > 50) "${msg.text.take(50)}..." else msg.text,
                        color = OnSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            
            Text(
                text = "${chat.messages.size} הודעות",
                color = OnSurface.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        
        // Divider at the bottom
        HorizontalDivider(
            color = OnSurface.copy(alpha = 0.06f),
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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

@Composable
private fun ChatRenameDialog(
    chat: Chat,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(chat.preview_name) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_chat_name)) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.chat_name)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { 
                        if (newName.isNotBlank()) {
                            onConfirm(newName)
                        }
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { 
                    if (newName.isNotBlank()) {
                        onConfirm(newName)
                    }
                },
                enabled = newName.isNotBlank()
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DeleteConfirmationDialog(
    chat: Chat,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_confirmation_title)) },
        text = { 
            Column {
                Text(stringResource(R.string.delete_confirmation_message))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "\"${chat.preview_name}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
