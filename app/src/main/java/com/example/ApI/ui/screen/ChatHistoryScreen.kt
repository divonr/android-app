package com.example.ApI.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.components.ChatImportChoiceDialog
import com.example.ApI.ui.components.ChatContextMenu
import com.example.ApI.ui.components.GroupContextMenu
import com.example.ApI.ui.components.DeleteChatConfirmationDialog
import com.example.ApI.ui.components.RenameChatDialog
import com.example.ApI.ui.components.CreateGroupDialog
import com.example.ApI.ui.components.RenameGroupDialog
import com.example.ApI.ui.components.DeleteGroupConfirmationDialog
import com.example.ApI.ui.theme.*
import com.example.ApI.ui.screen.createHighlightedText
import com.example.ApI.ui.screen.getModelInitial
import com.example.ApI.ui.screen.formatTimestamp
import com.example.ApI.ui.screen.getLastTimestampOrNull
import com.example.ApI.ui.screen.ChatListItem
import com.example.ApI.ui.screen.organizeAndSortAllItems
import com.example.ApI.ui.components.ChatHistoryItem
import com.example.ApI.ui.components.GroupItem
import com.example.ApI.ui.components.SearchResultItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatHistoryScreen(
    viewModel: ChatViewModel,
    uiState: ChatUiState,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocusRequester = remember { FocusRequester() }
    
    // Handle back button press to exit search mode
    BackHandler(enabled = uiState.searchMode) {
        viewModel.exitSearchMode()
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

    // Auto-focus search field when entering search mode
    LaunchedEffect(uiState.searchMode) {
        if (uiState.searchMode) {
            kotlinx.coroutines.delay(100) // Small delay to ensure UI is ready
            searchFocusRequester.requestFocus()
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
                        if (uiState.searchMode) {
                            // Search mode UI
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
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                                
                                androidx.compose.runtime.CompositionLocalProvider(
                                    androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl
                                ) {
                                    OutlinedTextField(
                                        value = uiState.searchQuery,
                                        onValueChange = { viewModel.updateSearchQuery(it) },
                                        placeholder = { 
                                            Text(
                                                "חיפוש בשיחות...", 
                                                color = OnSurfaceVariant
                                            ) 
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = { viewModel.performSearch() }
                                        ),
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { 
                                                    if (uiState.searchResults.isNotEmpty() || uiState.searchQuery.isNotEmpty()) {
                                                        viewModel.exitSearchMode()
                                                    } else {
                                                        viewModel.performSearch()
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    if (uiState.searchResults.isNotEmpty() || uiState.searchQuery.isNotEmpty()) {
                                                        Icons.Default.Close
                                                    } else {
                                                        Icons.Default.Search
                                                    },
                                                    contentDescription = if (uiState.searchResults.isNotEmpty() || uiState.searchQuery.isNotEmpty()) {
                                                        "סגור חיפוש"
                                                    } else {
                                                        "חפש"
                                                    },
                                                    tint = OnSurfaceVariant
                                                )
                                            }
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = OnSurface,
                                            unfocusedTextColor = OnSurface,
                                            focusedBorderColor = Color.Transparent,
                                            unfocusedBorderColor = Color.Transparent,
                                            cursorColor = Primary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp)
                                            .focusRequester(searchFocusRequester)
                                    )
                                }
                            }
                        } else {
                            // Normal mode UI
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
                                    IconButton(onClick = { viewModel.enterSearchMode() }) {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = "חיפוש",
                                            tint = OnSurfaceVariant
                                        )
                                    }
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
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.createNewChat("שיחה חדשה") },
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
            if (uiState.searchMode) {
                // Search mode content
                if (uiState.searchQuery.isEmpty()) {
                    // Search instruction
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = OnSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "הזן מילות חיפוש למציאת שיחות",
                            color = OnSurfaceVariant,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "החיפוש כולל כותרות שיחות, תוכן הודעות ושמות קבצים",
                            color = OnSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                } else if (uiState.searchResults.isEmpty()) {
                    // No search results
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = OnSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "לא נמצאו תוצאות",
                            color = OnSurfaceVariant,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "נסה מילות חיפוש אחרות",
                            color = OnSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    // Show search results
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(
                            items = uiState.searchResults,
                            key = { "search_result_${it.chat.chat_id}" }
                        ) { searchResult ->
                            SearchResultItem(
                                searchResult = searchResult,
                                onClick = {
                                    viewModel.selectChatFromSearch(searchResult.chat)
                                    // Note: selectChatFromSearch now handles navigation and search mode
                                },
                                onLongClick = { offset ->
                                    viewModel.showChatContextMenu(searchResult.chat, offset)
                                },
                                isRenaming = uiState.renamingChatIds.contains(searchResult.chat.chat_id)
                            )
                        }
                    }
                }
            } else {
                // Normal mode content - show chat history
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
                        // Organize and sort all items (groups and individual chats) by most recent activity
                        val sortedItems = organizeAndSortAllItems(uiState.chatHistory, uiState.groups)

                        sortedItems.forEach { item ->
                            when (item) {
                                is ChatListItem.GroupItem -> {
                                    val isExpanded = uiState.expandedGroups.contains(item.group.group_id)

                                    item(key = "group_${item.group.group_id}") {
                                        GroupItem(
                                            group = item.group,
                                            isExpanded = isExpanded,
                                            chatCount = item.chats.size,
                                            onGroupClick = { viewModel.navigateToGroup(item.group.group_id) },
                                            onToggleExpansion = { viewModel.toggleGroupExpansion(item.group.group_id) },
                                            onLongPress = { offset ->
                                                viewModel.showGroupContextMenu(item.group, offset)
                                            }
                                        )
                                    }

                                    if (isExpanded) {
                                        // Sort chats within group by last message timestamp
                                        val sortedGroupChats = item.chats.sortedWith(
                                            compareByDescending<com.example.ApI.data.model.Chat> { getLastTimestampOrNull(it) != null }
                                                .thenByDescending { getLastTimestampOrNull(it) ?: Long.MIN_VALUE }
                                        )

                                        items(
                                            items = sortedGroupChats,
                                            key = { "grouped_chat_${it.id}" }
                                        ) { chat ->
                                            ChatHistoryItem(
                                                chat = chat,
                                                onClick = {
                                                    viewModel.selectChat(chat)
                                                    viewModel.navigateToScreen(Screen.Chat)
                                                },
                                                onLongClick = { offset ->
                                                    viewModel.showChatContextMenu(chat, offset)
                                                },
                                                modifier = Modifier.padding(start = 32.dp), // Indent grouped chats
                                                isRenaming = uiState.renamingChatIds.contains(chat.chat_id),
                                                isStreaming = uiState.isStreamingChat(chat.chat_id)
                                            )
                                        }
                                    }
                                }

                                is ChatListItem.ChatItem -> {
                                    item(key = "chat_${item.chat.id}") {
                                        ChatHistoryItem(
                                            chat = item.chat,
                                            onClick = {
                                                viewModel.selectChat(item.chat)
                                                viewModel.navigateToScreen(Screen.Chat)
                                            },
                                            onLongClick = { offset ->
                                                viewModel.showChatContextMenu(item.chat, offset)
                                            },
                                            isRenaming = uiState.renamingChatIds.contains(item.chat.chat_id),
                                            isStreaming = uiState.isStreamingChat(item.chat.chat_id)
                                        )
                                    }
                                }
                            }
                        }
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
                    onCreateNewGroup = { chat ->
                        viewModel.showGroupDialog(chat)
                    },
                    onRemoveFromGroup = {
                        viewModel.removeChatFromGroup(menuState.chat.chat_id)
                    }
                )
            }

            // Context menu for group actions
            uiState.groupContextMenu?.let { menuState ->
                GroupContextMenu(
                    group = menuState.group,
                    position = menuState.position,
                    onDismiss = { viewModel.hideGroupContextMenu() },
                    onRename = { group ->
                        viewModel.showGroupRenameDialog(group)
                    },
                    onMakeProject = { group ->
                        viewModel.makeGroupProject(group)
                    },
                    onNewConversation = { group ->
                        viewModel.createNewConversationInGroup(group)
                    },
                    onDelete = { group ->
                        viewModel.showGroupDeleteConfirmation(group)
                    }
                )
            }
            
            // Delete confirmation dialog
            uiState.showDeleteConfirmation?.let { chat ->
                DeleteChatConfirmationDialog(
                    chat = chat,
                    onDismiss = { viewModel.hideDeleteConfirmation() },
                    onConfirm = { viewModel.deleteChat(it) }
                )
            }

            // Rename dialog
            uiState.showRenameDialog?.let { chat ->
                RenameChatDialog(
                    chat = chat,
                    onDismiss = { viewModel.hideRenameDialog() },
                    onConfirm = { c, newTitle -> viewModel.renameChat(c, newTitle) }
                )
            }

            // Group creation dialog
            if (uiState.showGroupDialog) {
                CreateGroupDialog(
                    onDismiss = { viewModel.hideGroupDialog() },
                    onCreate = { groupName -> viewModel.createNewGroup(groupName) }
                )
            }

            // Group rename dialog
            uiState.showGroupRenameDialog?.let { group ->
                RenameGroupDialog(
                    group = group,
                    onDismiss = { viewModel.hideGroupRenameDialog() },
                    onConfirm = { g, newGroupName -> viewModel.renameGroup(g, newGroupName) }
                )
            }

            // Group delete confirmation dialog
            uiState.showDeleteGroupConfirmation?.let { group ->
                DeleteGroupConfirmationDialog(
                    group = group,
                    onDismiss = { viewModel.hideGroupDeleteConfirmation() },
                    onConfirm = { viewModel.deleteGroup(it) }
                )
            }

            // Chat Import Choice Dialog
            uiState.pendingChatImport?.let { pending ->
                ChatImportChoiceDialog(
                    fileName = pending.fileName,
                    onLoadAsChat = { viewModel.importPendingChatJson() },
                    onAttachAsFile = { viewModel.attachPendingJsonAsFile() },
                    onDismiss = { viewModel.dismissChatImportDialog() }
                )
            }
        }
    }
}
