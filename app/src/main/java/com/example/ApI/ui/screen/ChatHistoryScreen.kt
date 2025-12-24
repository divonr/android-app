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
import com.example.ApI.ui.theme.*
import com.example.ApI.ui.screen.createHighlightedText
import com.example.ApI.ui.screen.getModelInitial
import com.example.ApI.ui.screen.formatTimestamp
import com.example.ApI.ui.screen.getLastTimestampOrNull
import com.example.ApI.ui.screen.ChatListItem
import com.example.ApI.ui.screen.organizeAndSortAllItems

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

            // Group rename dialog
            uiState.showGroupRenameDialog?.let { group ->
                var newGroupName by remember(group.group_id) { mutableStateOf(group.group_name) }

                // Reset the name when dialog opens with a new group
                LaunchedEffect(group.group_id, group.group_name) {
                    newGroupName = group.group_name
                }

                AlertDialog(
                    onDismissRequest = { viewModel.hideGroupRenameDialog() },
                    title = {
                        Text(
                            "שנה שם קבוצה",
                            color = OnSurface
                        )
                    },
                    text = {
                        OutlinedTextField(
                            value = newGroupName,
                            onValueChange = { newGroupName = it },
                            label = { Text("שם הקבוצה החדש", color = OnSurfaceVariant) },
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
                                viewModel.renameGroup(group, newGroupName)
                            },
                            enabled = newGroupName.isNotBlank() && newGroupName != group.group_name
                        ) {
                            Text("שמור", color = if (newGroupName.isNotBlank() && newGroupName != group.group_name) Primary else OnSurfaceVariant)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.hideGroupRenameDialog() }) {
                            Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
                        }
                    },
                    containerColor = Surface,
                    tonalElevation = 0.dp
                )
            }

            // Group delete confirmation dialog
            uiState.showDeleteGroupConfirmation?.let { group ->
                AlertDialog(
                    onDismissRequest = { viewModel.hideGroupDeleteConfirmation() },
                    title = {
                        Text(
                            "מחיקת קבוצה",
                            color = OnSurface
                        )
                    },
                    text = {
                        Text(
                            "האם אתה בטוח שברצונך למחוק את הקבוצה \"${group.group_name}\"? כל השיחות בקבוצה זו יפוזרו ויתבטלו מהקבוצה.",
                            color = OnSurfaceVariant
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteGroup(group)
                            }
                        ) {
                            Text("מחק", color = Color.Red)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.hideGroupDeleteConfirmation() }) {
                            Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
                        }
                    },
                    containerColor = Surface,
                    tonalElevation = 0.dp
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatHistoryItem(
    chat: Chat,
    onClick: () -> Unit,
    onLongClick: (DpOffset) -> Unit,
    modifier: Modifier = Modifier,
    isRenaming: Boolean = false,
    isStreaming: Boolean = false // Show loading indicator when streaming response
) {
    var itemPosition by remember { mutableStateOf(DpOffset.Zero) }
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
                val centerX = pos.x + itemSize.width - 16f
                val centerY = pos.y + itemSize.height / 2f
                itemPosition = with(density) { DpOffset(centerX.toDp(), centerY.toDp()) }
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
                if (isRenaming) {
                    // Show loading spinner when renaming
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(24.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Primary,
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    Text(
                        text = chat.title,
                        color = OnSurface,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
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

            // Streaming indicator - show when this chat is receiving a response
            if (isStreaming) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Primary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupItem(
    group: ChatGroup,
    isExpanded: Boolean,
    chatCount: Int,
    onGroupClick: () -> Unit,
    onToggleExpansion: () -> Unit,
    onLongPress: (DpOffset) -> Unit,
    modifier: Modifier = Modifier
) {
    var itemPosition by remember { mutableStateOf(DpOffset.Zero) }
    var itemTopLeft by remember { mutableStateOf(Offset.Zero) }
    var itemSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val density = LocalDensity.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .onGloballyPositioned { coordinates ->
                // Capture item bounds and approximate center in window coordinates
                val pos = coordinates.localToWindow(Offset.Zero)
                itemTopLeft = pos
                itemSize = coordinates.size.run { androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat()) }
                val centerX = pos.x + itemSize.width - 16f
                val centerY = pos.y + itemSize.height / 2f
                itemPosition = with(density) { DpOffset(centerX.toDp(), centerY.toDp()) }
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
                        onLongPress = { pressOffset ->
                            // Position menu to the right of the item, vertically at press Y
                            val anchor = with(density) {
                                val anchorX = (itemTopLeft.x + itemSize.width - 8f).toDp()
                                val anchorY = (itemTopLeft.y + pressOffset.y).toDp()
                                DpOffset(anchorX, anchorY)
                            }
                            onLongPress(anchor)
                        },
                        onTap = { onGroupClick() }
                    )
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Group icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Secondary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = Secondary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Group content (clickable to open group screen)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onGroupClick)
            ) {
                Text(
                    text = group.group_name,
                    color = OnSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "$chatCount שיחות",
                    color = OnSurfaceVariant,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Expansion arrow (only this toggles expansion)
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "כווץ קבוצה" else "הרחב קבוצה",
                tint = OnSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onToggleExpansion)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupContextMenu(
    group: ChatGroup,
    position: DpOffset,
    onDismiss: () -> Unit,
    onRename: (ChatGroup) -> Unit,
    onMakeProject: (ChatGroup) -> Unit,
    onNewConversation: (ChatGroup) -> Unit,
    onDelete: (ChatGroup) -> Unit
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
            .width(220.dp)
    ) {
        // Rename group
        DropdownMenuItem(
            text = {
                Text(
                    "שנה שם קבוצה",
                    color = OnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onRename(group)
                onDismiss()
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

        // Make project
        DropdownMenuItem(
            text = {
                Text(
                    "הפוך לפרויקט",
                    color = OnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onMakeProject(group)
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Star,
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

        // New conversation
        DropdownMenuItem(
            text = {
                Text(
                    "שיחה חדשה",
                    color = OnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onNewConversation(group)
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = OnSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        HorizontalDivider(
            color = OnSurface.copy(alpha = 0.1f),
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        // Delete group
        DropdownMenuItem(
            text = {
                Text(
                    "מחק קבוצה ופזר שיחות",
                    color = AccentRed,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onDelete(group)
                onDismiss()
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchResultItem(
    searchResult: SearchResult,
    onClick: () -> Unit,
    onLongClick: (DpOffset) -> Unit,
    modifier: Modifier = Modifier,
    isRenaming: Boolean = false
) {
    var itemPosition by remember { mutableStateOf(DpOffset.Zero) }
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
                val centerX = pos.x + itemSize.width - 16f
                val centerY = pos.y + itemSize.height / 2f
                itemPosition = with(density) { DpOffset(centerX.toDp(), centerY.toDp()) }
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
            val lastAssistantMessage = searchResult.chat.messages.lastOrNull { it.role == "assistant" }
            val modelName = lastAssistantMessage?.model ?: searchResult.chat.model
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
            
            // Chat content with highlighting
            Column(
                modifier = Modifier.weight(1f)
            ) {
                if (isRenaming) {
                    // Show loading spinner when renaming
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(24.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Primary,
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    // Title with highlighting if it's a title match
                    val titleText = when (searchResult.matchType) {
                        SearchMatchType.TITLE -> createHighlightedText(
                            searchResult.chat.title,
                            searchResult.highlightRanges,
                            AccentBlue
                        )
                        else -> AnnotatedString(searchResult.chat.title)
                    }

                    Text(
                        text = titleText,
                        color = OnSurface,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Show preview based on match type
                val previewText = when (searchResult.matchType) {
                    SearchMatchType.TITLE -> {
                        val lastMessage = searchResult.chat.messages.lastOrNull()
                        if (lastMessage != null) {
                            AnnotatedString(lastMessage.text.take(100))
                        } else {
                            AnnotatedString("")
                        }
                    }
                    SearchMatchType.CONTENT -> {
                        val message = searchResult.chat.messages.getOrNull(searchResult.messageIndex)
                        if (message != null) {
                            createHighlightedText(
                                message.text.take(150), 
                                searchResult.highlightRanges.filter { it.first < 150 }, 
                                AccentBlue
                            )
                        } else {
                            AnnotatedString("")
                        }
                    }
                    SearchMatchType.FILE_NAME -> {
                        val message = searchResult.chat.messages.getOrNull(searchResult.messageIndex)
                        val matchedFile = message?.attachments?.find { 
                            it.file_name.lowercase().contains(searchResult.searchQuery.lowercase())
                        }
                        if (matchedFile != null) {
                            buildAnnotatedString {
                                append("📎 ")
                                val fileText = createHighlightedText(
                                    matchedFile.file_name, 
                                    searchResult.highlightRanges, 
                                    AccentBlue
                                )
                                append(fileText)
                            }
                        } else {
                            AnnotatedString("")
                        }
                    }
                }
                
                if (previewText.isNotEmpty()) {
                    Text(
                        text = previewText,
                        color = OnSurfaceVariant,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Timestamp
            Column(
                horizontalAlignment = Alignment.End
            ) {
                val ts = getLastTimestampOrNull(searchResult.chat)
                if (ts != null) {
                    Text(
                        text = formatTimestamp(ts),
                        color = OnSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                
                if (searchResult.chat.messages.isNotEmpty()) {
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
                            text = searchResult.chat.messages.size.toString(),
                            color = OnSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatContextMenu(
    chat: Chat,
    position: DpOffset,
    onDismiss: () -> Unit,
    onRename: (Chat) -> Unit,
    onAIRename: (Chat) -> Unit,
    onDelete: (Chat) -> Unit,
    groups: List<ChatGroup>,
    onAddToGroup: (String) -> Unit,
    onCreateNewGroup: (Chat) -> Unit,
    onRemoveFromGroup: () -> Unit
) {
    var showGroupSubmenu by remember { mutableStateOf(false) }

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

        // Group management section
        if (chat.group != null) {
            // Remove from group option
            DropdownMenuItem(
                text = {
                    Text(
                        "הסר מקבוצה",
                        color = OnSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    onRemoveFromGroup()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = null,
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        } else {
            // Add to group option with submenu
            val addToGroupItemHeight = 40.dp
            DropdownMenuItem(
                text = {
                    Text(
                        "הוסף לקבוצה",
                        color = OnSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    showGroupSubmenu = true
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = OnSurface,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = null,
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }

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

    // Group submenu
    if (showGroupSubmenu) {
        // Align submenu to the left of the main menu and vertically align with the "הוסף לקבוצה" row
        val submenuX = position.x - 200.dp
        val submenuY = position.y + 0.dp
        DropdownMenu(
            expanded = true,
            onDismissRequest = { showGroupSubmenu = false },
            offset = DpOffset(submenuX, submenuY),
            properties = PopupProperties(focusable = true),
            modifier = Modifier
                .background(
                    Surface,
                    RoundedCornerShape(16.dp)
                )
                .width(200.dp)
        ) {
            // Existing groups
            groups.forEach { group ->
                DropdownMenuItem(
                    text = {
                        Text(
                            group.group_name,
                            color = OnSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onAddToGroup(group.group_id)
                        showGroupSubmenu = false
                        onDismiss()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = Secondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            // Create new group option
            HorizontalDivider(
                color = OnSurface.copy(alpha = 0.1f),
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            DropdownMenuItem(
                text = {
                    Text(
                        "צור קבוצה חדשה",
                        color = Primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    onCreateNewGroup(chat)
                    showGroupSubmenu = false
                    onDismiss()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}
