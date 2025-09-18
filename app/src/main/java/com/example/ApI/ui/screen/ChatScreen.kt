package com.example.ApI.ui.screen

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import coil.compose.AsyncImage
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.components.*
import com.example.ApI.ui.theme.*
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.min

/**
 * Scrolls to the previous message (one message up) in the chat.
 * Aligns the beginning of the message to the top of the screen.
 */
suspend fun scrollToPreviousMessage(
    listState: androidx.compose.foundation.lazy.LazyListState,
    messages: List<Message>?,
    isStreaming: Boolean = false,
    showReplyButton: Boolean = false
) {
    if (messages.isNullOrEmpty()) return

    val currentIndex = listState.firstVisibleItemIndex

    // Account for streaming message and reply button at the top
    val streamingOffset = if (isStreaming) 1 else 0
    val replyOffset = if (showReplyButton) 1 else 0
    val totalOffset = streamingOffset + replyOffset

    // Calculate the message index we want to scroll to
    // Messages are displayed in reverse order (newest first)
    val targetMessageIndex = currentIndex - totalOffset + 1

    // Make sure we don't go beyond the available messages
    val maxMessageIndex = messages.size - 1 + totalOffset
    val targetIndex = minOf(targetMessageIndex, maxMessageIndex)

    if (targetIndex >= 0) {
        try {
            // Use scrollOffset = 0 to pin the top of the message to the top of the screen
            listState.animateScrollToItem(index = targetIndex, scrollOffset = 0)
        } catch (e: Exception) {
            // If scrolling fails, just scroll to the first item
            listState.animateScrollToItem(index = 0, scrollOffset = 0)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    uiState: ChatUiState,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val searchFocusRequester = remember { FocusRequester() }
    
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
    
    // Handle search context - scroll to found message
    LaunchedEffect(uiState.searchContext, uiState.currentChat?.messages?.size) {
        val searchContext = uiState.searchContext
        val currentChat = uiState.currentChat
        
        if (searchContext != null && 
            currentChat != null && 
            searchContext.matchType == SearchMatchType.CONTENT && 
            searchContext.messageIndex >= 0 &&
            searchContext.messageIndex < currentChat.messages.size) {
            
            // Calculate the reversed index since messages are displayed in reverse order
            val reversedIndex = currentChat.messages.size - 1 - searchContext.messageIndex
            
            // Wait longer for the UI to fully settle and load
            kotlinx.coroutines.delay(500)
            
            // First try to scroll to the item
            try {
                // Account for streaming bubble (1) + reply button (1) if present
                val adjustedIndex = reversedIndex + 
                    (if (uiState.isStreaming && uiState.streamingText.isNotEmpty()) 1 else 0) +
                    (if (uiState.showReplyButton && !uiState.isStreaming && !uiState.isLoading) 1 else 0)
                
                println("DEBUG SEARCH: Scrolling to message index ${searchContext.messageIndex}, reversedIndex: $reversedIndex, adjustedIndex: $adjustedIndex, total messages: ${currentChat.messages.size}")
                
                // Try to scroll to the item
                if (adjustedIndex >= 0 && adjustedIndex < (currentChat.messages.size + 2)) {
                    listState.animateScrollToItem(adjustedIndex)
                } else {
                    // Fallback: just scroll to the original reversed index
                    listState.animateScrollToItem(reversedIndex)
                }
                
                // Keep highlighting for longer so user can see it
                kotlinx.coroutines.delay(8000) // Keep highlighting for 8 seconds
                viewModel.clearSearchContext()
            } catch (e: Exception) {
                println("DEBUG SEARCH: Error scrolling to message: ${e.message}")
                // If scrolling fails, still clear context after delay
                kotlinx.coroutines.delay(3000)
                viewModel.clearSearchContext()
            }
        }
    }
    
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Background)
        ) {
    // Back cancels edit mode if active
    BackHandler(enabled = uiState.isEditMode) {
        viewModel.cancelEditingMessage()
    }

    // Back exits search mode if active
    BackHandler(enabled = uiState.searchMode) {
        viewModel.exitSearchMode()
    }
            // Semi-transparent overlay for edit mode
            if (uiState.isEditMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .clickable { viewModel.cancelEditingMessage() }
                )
            }
            val sheetWidth = 320.dp
            var screenshot by remember { mutableStateOf<Bitmap?>(null) }
            val view = LocalView.current
            LaunchedEffect(uiState.showChatHistory) {
                if (uiState.showChatHistory) {
                    screenshot = view.drawToBitmap()
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(end = 0.dp)
            ) {
                // Modern Minimalist Top Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Surface,
                    shadowElevation = 1.dp
                ) {
                    if (uiState.searchMode) {
                        // Full-width search bar covering entire top line
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Back arrow (always visible)
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
                            
                            // Search text field spanning the entire remaining width
                            OutlinedTextField(
                                value = uiState.searchQuery,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(searchFocusRequester),
                                placeholder = {
                                    Text(
                                        text = "חפש בשיחה...",
                                        color = OnSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedTextColor = OnSurface,
                                    unfocusedTextColor = OnSurface,
                                    unfocusedContainerColor = Surface,
                                    focusedContainerColor = Surface
                                ),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions.Default.copy(
                                    keyboardType = KeyboardType.Text
                                ),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                    onSearch = { viewModel.performConversationSearch() }
                                ),
                                shape = MaterialTheme.shapes.medium,
                                trailingIcon = {
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = Color.Transparent,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable {
                                                if (uiState.searchQuery.isNotEmpty()) {
                                                    viewModel.exitSearchMode()
                                                } else {
                                                    viewModel.performConversationSearch()
                                                }
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (uiState.searchQuery.isNotEmpty()) Icons.Default.Close else Icons.Default.Search,
                                                contentDescription = if (uiState.searchQuery.isNotEmpty()) "Exit search" else "Search",
                                                tint = OnSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    } else {
                        // Normal top bar with all elements
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left side - Back arrow and Search icon
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
                                
                                // Search icon
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = SurfaceVariant,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable { viewModel.enterConversationSearchMode() }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "Search in conversation",
                                            tint = OnSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            
                            // Center - Model and Provider info
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = uiState.currentProvider?.provider?.let { provider ->
                                        stringResource(id = when(provider) {
                                            "openai" -> R.string.provider_openai
                                            "poe" -> R.string.provider_poe
                                            "google" -> R.string.provider_google
                                            else -> R.string.provider_openai
                                        })
                                    } ?: "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = OnSurfaceVariant,
                                    modifier = Modifier.clickable { viewModel.showProviderSelector() }
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = uiState.currentModel,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = OnSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { viewModel.showModelSelector() }
                                )
                            }
                            
                            // Right side - System Prompt and Delete icons
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // System Prompt icon
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = SurfaceVariant,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable { viewModel.showSystemPromptDialog() }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Build,
                                            contentDescription = "System Prompt",
                                            tint = OnSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                // Delete Chat
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = SurfaceVariant,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable { viewModel.showDeleteChatConfirmation() }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Chat",
                                            tint = OnSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Chat Messages
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        reverseLayout = true
                    ) {
                        // Show streaming text if currently streaming
                        if (uiState.isStreaming && uiState.streamingText.isNotEmpty()) {
                            item {
                                StreamingMessageBubble(
                                    text = uiState.streamingText,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                        // Show temporary reply button bubble when multi-message mode is active
                        if (uiState.showReplyButton && !uiState.isStreaming && !uiState.isLoading) {
                            item {
                                ReplyPromptBubble(
                                    onClick = { viewModel.sendBufferedBatch() },
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                        
                        uiState.currentChat?.messages?.let { messages ->
                            val reversedMessages = messages.reversed()
                            itemsIndexed(reversedMessages) { index, message ->
                                val isFirstMessage = index == 0
                                val previousMessage = if (index > 0) reversedMessages[index - 1] else null
                                val isSameSpeaker = previousMessage?.role == message.role

                                val topPadding = if (isFirstMessage || !isSameSpeaker) 4.dp else 0.dp
                                val bottomPadding = if (!isSameSpeaker) 4.dp else 0.dp

                                // Calculate original message index from reversed index
                                val originalIndex = messages.size - 1 - index
                                
                                // Check if this message should be highlighted (find all search results for this message)
                                val searchHighlight = if (uiState.searchMode && uiState.searchResults.isNotEmpty()) {
                                    uiState.searchResults.find { result ->
                                        result.matchType == SearchMatchType.CONTENT && 
                                        result.messageIndex == originalIndex
                                    }
                                } else null

                                MessageBubble(
                                    message = message,
                                    viewModel = viewModel,
                                    modifier = Modifier.padding(top = topPadding, bottom = bottomPadding),
                                    isEditMode = uiState.isEditMode,
                                    isBeingEdited = uiState.editingMessage == message,
                                    searchHighlight = searchHighlight
                                )
                            }
                        }
                    }

                    // Scroll-to-bottom button state management
                    var showScrollButton by remember { mutableStateOf(false) }
                    var hideButtonJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                    
                    // Detect scroll state - show button when user scrolls away from bottom
                    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
                        val isAtBottom = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                        
                        if (!isAtBottom && !showScrollButton) {
                            // User scrolled away from bottom, show button
                            showScrollButton = true
                            
                            // Start auto-hide timer
                            hideButtonJob?.cancel()
                            hideButtonJob = launch {
                                kotlinx.coroutines.delay(3000) // Hide after 3 seconds
                                showScrollButton = false
                            }
                        } else if (isAtBottom) {
                            // User is at bottom, hide button immediately
                            hideButtonJob?.cancel()
                            showScrollButton = false
                        }
                    }

                    // Animated floating scroll buttons (Up and Down)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollButton,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            // Up arrow button - scroll to previous message
                            FloatingActionButton(
                                onClick = {
                                    coroutineScope.launch {
                                        scrollToPreviousMessage(
                                            listState,
                                            uiState.currentChat?.messages,
                                            uiState.isStreaming && uiState.streamingText.isNotEmpty(),
                                            uiState.showReplyButton && !uiState.isStreaming && !uiState.isLoading
                                        )
                                    }
                                    // Cancel auto-hide timer when button is clicked
                                    hideButtonJob?.cancel()
                                },
                                shape = CircleShape,
                                containerColor = Primary,
                                contentColor = Color.White,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Scroll to previous message",
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Down arrow button - scroll to bottom (existing functionality)
                            FloatingActionButton(
                                onClick = {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(0) // Scroll to bottom (newest messages)
                                    }
                                    // Cancel auto-hide timer when button is clicked
                                    hideButtonJob?.cancel()
                                    showScrollButton = false
                                },
                                shape = CircleShape,
                                containerColor = Primary,
                                contentColor = Color.White,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Scroll to bottom",
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // Selected Files Preview - Grid Layout for Multiple Files
                if (uiState.selectedFiles.isNotEmpty()) {
                    if (uiState.selectedFiles.size == 1) {
                        // Single file - show as list item
                        LazyColumn(
                            modifier = Modifier
                                .heightIn(max = 120.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            items(uiState.selectedFiles) { file ->
                                FilePreview(
                                    file = file,
                                    onRemove = { viewModel.removeSelectedFile(file) }
                                )
                            }
                        }
                    } else {
                        // Multiple files - show as grid of thumbnails
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 80.dp),
                            modifier = Modifier
                                .heightIn(max = 160.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(uiState.selectedFiles) { file ->
                                FilePreviewThumbnail(
                                    file = file,
                                    onRemove = { viewModel.removeSelectedFile(file) }
                                )
                            }
                        }
                    }
                }

                // Modern Message Input Area
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
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Add Files (pin) Button with anchored dropdown
                                var showFileMenu by remember { mutableStateOf(false) }
                                Box {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = if (uiState.selectedFiles.isNotEmpty()) Primary.copy(alpha = 0.1f) else Color.Transparent,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clickable { showFileMenu = !showFileMenu }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Add files",
                                                tint = if (uiState.selectedFiles.isNotEmpty()) Primary else OnSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    FileSelectionDropdown(
                                        expanded = showFileMenu,
                                        onDismiss = { showFileMenu = false },
                                        onFileSelected = { uri, name, mime ->
                                            viewModel.addFileFromUri(uri, name, mime)
                                        },
                                        onMultipleFilesSelected = { filesList ->
                                            viewModel.addMultipleFilesFromUris(filesList)
                                        }
                                    )
                                }

                                // Message Input Field - Clean design
                                OutlinedTextField(
                                    value = uiState.currentMessage,
                                    onValueChange = { viewModel.updateMessage(it) },
                                    modifier = Modifier.weight(1f),
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

                                // Web Search Toggle Icon
                                if (uiState.webSearchSupport != WebSearchSupport.UNSUPPORTED) {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = when {
                                            uiState.webSearchEnabled -> Primary.copy(alpha = 0.15f)
                                            uiState.webSearchSupport == WebSearchSupport.REQUIRED -> Primary.copy(alpha = 0.1f)
                                            else -> Color.Transparent
                                        },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clickable { viewModel.toggleWebSearch() }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "🌐",
                                                fontSize = 20.sp,
                                                color = when {
                                                    uiState.webSearchEnabled -> Primary
                                                    uiState.webSearchSupport == WebSearchSupport.REQUIRED -> Primary.copy(alpha = 0.7f)
                                                    else -> OnSurfaceVariant.copy(alpha = 0.5f)
                                                },
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // Edit-confirm and Send buttons
                                if (uiState.isEditMode) {
                                    // Confirm edit (check)
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = if (uiState.currentMessage.isNotEmpty() && !uiState.isLoading && !uiState.isStreaming) Primary else Primary.copy(alpha = 0.3f),
                                        modifier = Modifier.size(40.dp).clickable(
                                            enabled = uiState.currentMessage.isNotEmpty() && !uiState.isLoading && !uiState.isStreaming
                                        ) { viewModel.finishEditingMessage() }
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
                                    // Small gap
                                    Spacer(modifier = Modifier.width(6.dp))
                                    // Send after edit (check + resend)
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = if (uiState.currentMessage.isNotEmpty() && !uiState.isLoading && !uiState.isStreaming) Primary else Primary.copy(alpha = 0.3f),
                                        modifier = Modifier.size(40.dp).clickable(
                                            enabled = uiState.currentMessage.isNotEmpty() && !uiState.isLoading && !uiState.isStreaming
                                        ) { viewModel.confirmEditAndResend() }
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
                                } else {
                                    // Regular send
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = if ((uiState.currentMessage.isNotEmpty() || uiState.selectedFiles.isNotEmpty()) && !uiState.isLoading && !uiState.isStreaming) 
                                            Primary else Primary.copy(alpha = 0.3f),
                                        modifier = Modifier.size(40.dp).clickable(
                                            enabled = (uiState.currentMessage.isNotEmpty() || uiState.selectedFiles.isNotEmpty()) && !uiState.isLoading && !uiState.isStreaming
                                        ) { viewModel.sendMessage() }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            if (uiState.isLoading || uiState.isStreaming) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    color = Color.White,
                                                    strokeWidth = 2.dp
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
                            }
                        }
                    }
                }
            }

            // System Prompt Dialog
            if (uiState.showSystemPromptDialog) {
                val projectGroup = viewModel.getCurrentChatProjectGroup()

                SystemPromptDialog(
                    currentPrompt = uiState.systemPrompt,
                    onConfirm = { viewModel.updateSystemPrompt(it) },
                    onDismiss = { viewModel.hideSystemPromptDialog() },
                    projectPrompt = projectGroup?.system_prompt,
                    projectName = projectGroup?.group_name,
                    initialOverrideEnabled = uiState.systemPromptOverrideEnabled,
                    onOverrideToggle = { enabled ->
                        viewModel.setSystemPromptOverride(enabled)
                    }
                )
            }

            // Provider Selector
            if (uiState.showProviderSelector) {
                ProviderSelectorDialog(
                    providers = uiState.availableProviders,
                    onProviderSelected = { viewModel.selectProvider(it) },
                    onDismiss = { viewModel.hideProviderSelector() }
                )
            }

            // Model Selector
            if (uiState.showModelSelector) {
                ModelSelectorDialog(
                    models = uiState.currentProvider?.models ?: emptyList(),
                    onModelSelected = { viewModel.selectModel(it) },
                    onDismiss = { viewModel.hideModelSelector() }
                )
            }

            // Delete Chat Confirmation Dialog
            uiState.showDeleteChatConfirmation?.let { chat ->
                AlertDialog(
                    onDismissRequest = { viewModel.hideDeleteChatConfirmation() },
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
                                viewModel.deleteCurrentChat()
                                viewModel.hideDeleteChatConfirmation()
                            }
                        ) {
                            Text(stringResource(R.string.delete), color = Color.Red)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.hideDeleteChatConfirmation() }) {
                            Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
                        }
                    },
                    containerColor = Surface,
                    tonalElevation = 0.dp
                )
            }

            // Side sheet panel for chat history (fills side space)
            if (uiState.showChatHistory) {
                // Overlay that captures clicks outside the panel
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .clickable { viewModel.hideChatHistory() }
                )
                
                // Overlay a thin screenshot strip to simulate content edge
                val cropWidthDp = 24.dp
                val density = LocalDensity.current
                val cropPx = with(density) { cropWidthDp.toPx().toInt() }
                val cropped = remember(screenshot, uiState.showChatHistory) {
                    screenshot?.let { bmp ->
                        val w = min(cropPx, bmp.width)
                        try {
                            Bitmap.createBitmap(bmp, bmp.width - w, 0, w, bmp.height)
                        } catch (t: Throwable) { null }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                ) {
                    Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                        // Screenshot strip (left of panel)
                        cropped?.let { strip ->
                            Image(
                                bitmap = strip.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(cropWidthDp)
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(sheetWidth)
                                .clickable(enabled = false) { /* Prevent click through */ },
                            color = Surface,
                            shadowElevation = 8.dp
                        ) {
                            ChatHistoryPanel(
                                chatHistory = uiState.chatHistory,
                                uiState = uiState,
                                viewModel = viewModel,
                                onChatSelected = { selected ->
                                    viewModel.selectChat(selected)
                                    viewModel.hideChatHistory()
                                },
                                onNewChat = { viewModel.createNewChat() },
                                onClose = { viewModel.hideChatHistory() }
                            )
                        }
                    }
                }
            }

            // File Selection Dialog
            if (uiState.showFileSelection) {
                FileSelectionDialog(
                    onFileSelected = { uri, fileName, mimeType ->
                        viewModel.addFileFromUri(uri, fileName, mimeType)
                    },
                    onMultipleFilesSelected = { filesList ->
                        viewModel.addMultipleFilesFromUris(filesList)
                    },
                    onDismiss = { viewModel.hideFileSelection() }
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 80.dp) // Position above the input area
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false,
    isBeingEdited: Boolean = false,
    searchHighlight: SearchResult? = null
) {
    // Handle tool call messages specially
    if (message.isToolCall || message.toolCall != null) {
        ToolCallBubble(
            message = message,
            modifier = modifier,
            isEditMode = isEditMode
        )
        return
    }
    val bubbleColor = when (message.role) {
        "user" -> UserMessageBubble
        "assistant" -> AssistantMessageBubble
        "system" -> SystemMessageBubble
        else -> AssistantMessageBubble
    }

    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    var showContextMenu by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
    // Format timestamp for display
    val timeString = remember(message.datetime) {
        message.datetime?.let {
            try {
                val instant = Instant.parse(it)
                val formatter = DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.systemDefault())
                formatter.format(instant)
            } catch (e: Exception) {
                null
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Row(
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Model avatar (only for assistant messages)
            if (!isUser && message.model != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Primary.copy(alpha = 0.15f),
                    modifier = Modifier
                        .size(32.dp)
                        .padding(end = 8.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = message.model.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            
            // Message bubble with modern design
            Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 20.dp else 6.dp,
                topEnd = if (isUser) 6.dp else 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp
            ),
            color = if (searchHighlight != null) {
                // Add a subtle glow effect for search results
                bubbleColor.copy(alpha = 0.9f)
            } else {
                bubbleColor
            },
            shadowElevation = if (isUser) 2.dp else 0.dp,
            border = if (searchHighlight != null) {
                // Add border for search highlighted messages
                androidx.compose.foundation.BorderStroke(2.dp, AccentBlue.copy(alpha = 0.7f))
            } else null,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = { /* Regular click - do nothing */ },
                    onLongClick = { showContextMenu = true }
                )
                .then(
                    if (isEditMode && !isBeingEdited) {
                        Modifier.alpha(0.3f) // Darken other messages during edit mode
                    } else {
                        Modifier
                    }
                )
        ) {
            Column {
                // Show model name for assistant messages (like WhatsApp group sender name)
                if (!isUser && message.model != null) {
                    Text(
                        text = message.model,
                        color = Primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 4.dp
                        )
                    )
                }
                
                // Show image attachments first (like WhatsApp) - fill the bubble
                val imageAttachments = message.attachments.filter { 
                    it.mime_type.startsWith("image/") && it.local_file_path != null 
                }
                val nonImageAttachments = message.attachments.filter { 
                    !it.mime_type.startsWith("image/") || it.local_file_path == null 
                }
                
                imageAttachments.forEach { attachment ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        AsyncImage(
                            model = java.io.File(attachment.local_file_path!!),
                            contentDescription = attachment.file_name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 200.dp)
                                .clickable {
                                    attachment.local_file_path?.let { path ->
                                        val file = File(path)
                                        try {
                                            val uri = FileProvider.getUriForFile(context, context.applicationContext.packageName + ".fileprovider", file)
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, attachment.mime_type)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            // Handle exception if file provider fails or no app can handle the intent
                                        }
                                    }
                                },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                
                // Text and non-image content with padding
                val hasText = message.text.isNotEmpty() && message.text != "[קובץ מצורף]"
                if (hasText || nonImageAttachments.isNotEmpty() || timeString != null) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = if (imageAttachments.isNotEmpty()) 8.dp else 12.dp
                        )
                    ) {
                        // Show text if exists and is not just placeholder
                        if (hasText) {
                            if (searchHighlight != null) {
                                // Show highlighted text for search results
                                val highlightedText = createHighlightedText(
                                    text = message.text,
                                    highlightRanges = searchHighlight.highlightRanges,
                                    highlightColor = AccentBlue
                                )
                                Text(
                                    text = highlightedText,
                                    style = TextStyle(
                                        color = if (isUser) Color.White else OnSurface,
                                        fontSize = 15.sp,
                                        lineHeight = 18.sp,
                                        letterSpacing = 0.sp
                                    )
                                )
                            } else {
                                // Normal markdown rendering
                                MarkdownText(
                                    markdown = message.text,
                                    style = TextStyle(
                                        color = if (isUser) Color.White else OnSurface,
                                        fontSize = 15.sp,
                                        lineHeight = 18.sp,
                                        letterSpacing = 0.sp
                                    )
                                )
                            }
                        }

                        // Show non-image attachments
                        nonImageAttachments.forEach { attachment ->
                            if (hasText || imageAttachments.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isUser) Color.White.copy(alpha = 0.1f) else SurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        attachment.local_file_path?.let { path ->
                                            val file = File(path)
                                            try {
                                                val uri = FileProvider.getUriForFile(context, context.applicationContext.packageName + ".fileprovider", file)
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, attachment.mime_type)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                // Handle exception
                                            }
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add, // Use as attachment icon
                                        contentDescription = "Attachment",
                                        tint = if (isUser) Color.White.copy(alpha = 0.8f) else OnSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = attachment.file_name,
                                        color = if (isUser) Color.White.copy(alpha = 0.9f) else OnSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        
                        // Show timestamp at the bottom (like WhatsApp)
                        if (timeString != null) {
                            Text(
                                text = timeString,
                                color = if (isUser) Color.White.copy(alpha = 0.7f) else OnSurfaceVariant,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .align(if (isUser) Alignment.End else Alignment.Start)
                                    .padding(top = if (hasText || nonImageAttachments.isNotEmpty()) 4.dp else 0.dp)
                            )
                        }
                    }
                }
            }
        }
        }

        // Context menu
        Box {
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false },
                modifier = Modifier.background(
                    Surface,
                    MaterialTheme.shapes.medium
                )
            ) {
                DropdownMenuItem(
                    text = { 
                        Text(
                            stringResource(R.string.copy), 
                            color = OnSurface,
                            style = MaterialTheme.typography.bodyMedium
                        ) 
                    },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.text))
                        showContextMenu = false
                    }
                )
                // Show Edit option only for user messages
                if (message.role == "user") {
                    DropdownMenuItem(
                        text = { 
                            Text(
                                stringResource(R.string.edit), 
                                color = OnSurface,
                                style = MaterialTheme.typography.bodyMedium
                            ) 
                        },
                        onClick = {
                            viewModel.startEditingMessage(message)
                            showContextMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { 
                            Text(
                                stringResource(R.string.resend), 
                                color = OnSurface,
                                style = MaterialTheme.typography.bodyMedium
                            ) 
                        },
                        onClick = {
                            viewModel.resendFromMessage(message)
                            showContextMenu = false
                        }
                    )
                }
                DropdownMenuItem(
                    text = { 
                        Text(
                            stringResource(R.string.delete), 
                            color = AccentRed,
                            style = MaterialTheme.typography.bodyMedium
                        ) 
                    },
                    onClick = {
                        viewModel.deleteMessage(message)
                        showContextMenu = false
                    }
                )
            }
        }
    }
}

@Composable
fun DateDivider(
    dateString: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceVariant.copy(alpha = 0.8f),
            shadowElevation = 1.dp
        ) {
            Text(
                text = dateString,
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun FilePreview(
    file: SelectedFile,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Modern file icon/thumbnail
            if (file.mimeType.startsWith("image/")) {
                AsyncImage(
                    model = file.uri,
                    contentDescription = file.name,
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            color = Surface,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(2.dp)
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Primary.copy(alpha = 0.1f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add, // Use as document icon
                            contentDescription = "File",
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // File info with modern typography
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = file.name,
                    color = OnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = file.mimeType,
                    color = OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }

            // Modern remove button
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = AccentRed.copy(alpha = 0.1f),
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onRemove() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.remove_file),
                        tint = AccentRed,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StreamingMessageBubble(
    text: String,
    modifier: Modifier = Modifier
) {
    val bubbleColor = AssistantMessageBubble
    val alignment = Alignment.Start

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 6.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp
            ),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                )
            ) {
                MarkdownText(
                    markdown = text,
                    style = TextStyle(
                        color = OnSurface,
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                        letterSpacing = 0.sp
                    )
                )
                
                // Modern typing indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Primary.copy(alpha = 0.1f),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            repeat(3) { index ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Primary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(4.dp)
                                ) {}
                            }
                        }
                    }
                    Text(
                        text = "Generating...",
                        color = OnSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun ReplyPromptBubble(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleColor = AssistantMessageBubble
    val alignment = Alignment.Start

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 6.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp
            ),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Primary,
                    modifier = Modifier
                        .clickable { onClick() }
                ) {
                    Text(
                        text = stringResource(id = R.string.reply_now),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ToolCallBubble(
    message: Message,
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false
) {
    val toolCallInfo = message.toolCall ?: return
    var isExpanded by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Tool call container with modern design
        Surface(
            shape = RoundedCornerShape(
                topStart = 6.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp
            ),
            color = Primary.copy(alpha = 0.1f), // Subtle primary color background
            border = BorderStroke(1.dp, Primary.copy(alpha = 0.2f)),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clickable { isExpanded = !isExpanded }
                .then(
                    if (isEditMode) {
                        Modifier.alpha(0.3f)
                    } else {
                        Modifier
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header with tool icon and name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Tool icon with modern styling
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Build, // Tool/function icon
                                contentDescription = "Tool",
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    
                    // Tool name and status
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "🔧 ${toolCallInfo.toolName}",
                            style = MaterialTheme.typography.titleSmall,
                            color = Primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        // Status indicator based on result
                        val (statusText, statusColor) = when (toolCallInfo.result) {
                            is ToolExecutionResult.Success -> "✅ Completed" to AccentGreen
                            is ToolExecutionResult.Error -> "❌ Failed" to AccentRed
                        }
                        
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    // Expand/collapse indicator
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isExpanded) Primary.copy(alpha = 0.1f) else Color.Transparent,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = OnSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                
                // Quick result preview (always visible)
                Spacer(modifier = Modifier.height(8.dp))
                
                val resultPreview = when (val result = toolCallInfo.result) {
                    is ToolExecutionResult.Success -> {
                        if (result.result.length > 80) {
                            "${result.result.take(80)}..."
                        } else result.result
                    }
                    is ToolExecutionResult.Error -> result.error
                }
                
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = resultPreview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        modifier = Modifier.padding(12.dp),
                        maxLines = if (isExpanded) Int.MAX_VALUE else 2
                    )
                }
                
                // Expanded details
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn() + androidx.compose.animation.expandVertically(),
                    exit = fadeOut() + androidx.compose.animation.shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Parameters section if any
                        if (toolCallInfo.parameters.isNotEmpty()) {
                            Text(
                                text = "Parameters:",
                                style = MaterialTheme.typography.labelMedium,
                                color = OnSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = toolCallInfo.parameters.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceVariant,
                                    modifier = Modifier.padding(12.dp),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                        
                        // Full result section
                        Text(
                            text = "Result:",
                            style = MaterialTheme.typography.labelMedium,
                            color = OnSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when (toolCallInfo.result) {
                                is ToolExecutionResult.Success -> AccentGreen.copy(alpha = 0.1f)
                                is ToolExecutionResult.Error -> AccentRed.copy(alpha = 0.1f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            when (val result = toolCallInfo.result) {
                                is ToolExecutionResult.Success -> {
                                    Text(
                                        text = result.result,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnSurface,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                                is ToolExecutionResult.Error -> {
                                    Text(
                                        text = result.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AccentRed,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                        
                        // Timestamp
                        Text(
                            text = "Executed at ${formatToolCallTimestamp(toolCallInfo.timestamp)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Format timestamp for tool call display
 */
private fun formatToolCallTimestamp(timestamp: String): String {
    return try {
        val instant = Instant.parse(timestamp)
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (e: Exception) {
        timestamp // Fallback to original string
    }
}

/**
 * Create highlighted text with search terms highlighted
 */
fun createHighlightedText(text: String, highlightRanges: List<IntRange>, highlightColor: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (highlightRanges.isEmpty() || text.isEmpty()) {
        return AnnotatedString(text)
    }
    
    return buildAnnotatedString {
        var lastIndex = 0
        
        // Sort ranges to process them in order
        val sortedRanges = highlightRanges.sortedBy { it.first }
        
        for (range in sortedRanges) {
            // Skip if range is out of bounds
            if (range.first >= text.length || range.last >= text.length) continue
            
            // Add text before highlight
            if (lastIndex < range.first) {
                append(text.substring(lastIndex, range.first))
            }
            
            // Add highlighted text with more visible highlighting
            withStyle(androidx.compose.ui.text.SpanStyle(background = highlightColor.copy(alpha = 0.6f))) {
                append(text.substring(range.first, minOf(range.last + 1, text.length)))
            }
            
            lastIndex = minOf(range.last + 1, text.length)
        }
        
        // Add remaining text
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

@Composable
fun FilePreviewThumbnail(
    file: SelectedFile,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .background(
                color = SurfaceVariant,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        // File thumbnail/icon
        if (file.mimeType.startsWith("image/")) {
            AsyncImage(
                model = file.uri,
                contentDescription = file.name,
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = Surface,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentScale = ContentScale.Crop
            )
        } else {
            // Non-image file icon
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Primary.copy(alpha = 0.1f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Add, // Use as document icon
                            contentDescription = "File",
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // File name overlay (bottom)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = Color.Black.copy(alpha = 0.7f),
            shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        ) {
            Text(
                text = file.name.take(8) + if (file.name.length > 8) "..." else "",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                maxLines = 1
            )
        }

        // Remove button (top-right corner)
        Surface(
            shape = CircleShape,
            color = Color.Red.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clickable { onRemove() }
                .padding(2.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove file",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
