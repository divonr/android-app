package com.example.ApI.ui.screen

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.min

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
                val chatIdForSearch = uiState.currentChat?.chat_id
                val hasThinkingForSearch = chatIdForSearch?.let {
                    uiState.isThinking(it) || uiState.getStreamingThoughts(it).isNotBlank()
                } ?: false
                val adjustedIndex = reversedIndex +
                    (if (uiState.isStreaming && (uiState.streamingText.isNotEmpty() || hasThinkingForSearch)) 1 else 0) +
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
                // Top bars container with floating arrow
                ChatTopBarContainer(
                    uiState = uiState,
                    viewModel = viewModel,
                    searchFocusRequester = searchFocusRequester
                )

                // Chat Messages - Hybrid Fix (Visual + Physical)
                Box(modifier = Modifier.weight(1f)) {
                    val view = LocalView.current
                    // משתנה לתיקון ויזואלי (Shift)
                    var listTranslationY by remember { mutableFloatStateOf(0f) }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            // שלב 1: ה-Modifier הזה מזיז את הרשימה ויזואלית
                            // בהתאם לערך שחישבנו, עוד לפני שהגלילה האמיתית קורית
                            .graphicsLayer { 
                                translationY = listTranslationY 
                            },
                        reverseLayout = true
                    ) {
                        // Streaming Message Bubble (show during thinking phase even if streamingText is empty)
                        val currentChatIdForStreaming = uiState.currentChat?.chat_id
                        val hasThinkingContent = currentChatIdForStreaming?.let {
                            uiState.isThinking(it) || uiState.getStreamingThoughts(it).isNotBlank()
                        } ?: false
                        if (uiState.isStreaming && (uiState.streamingText.isNotEmpty() || hasThinkingContent)) {
                            item {
                                var previousHeight by remember { mutableIntStateOf(0) }

                                val currentChatId = currentChatIdForStreaming
                                StreamingMessageBubble(
                                    text = uiState.streamingText,
                                    textDirectionMode = uiState.textDirectionMode,
                                    isThinking = currentChatId?.let { uiState.isThinking(it) } ?: false,
                                    streamingThoughts = currentChatId?.let { uiState.getStreamingThoughts(it) } ?: "",
                                    thinkingStartTime = currentChatId?.let { uiState.getThinkingStartTime(it) },
                                    completedThinkingDuration = currentChatId?.let { uiState.getCompletedThinkingDuration(it) },
                                    modifier = Modifier
                                        .padding(vertical = 4.dp)
                                        .onSizeChanged { size ->
                                            val currentHeight = size.height
                                            
                                            // בדיקה אם היה שינוי גובה חיובי (גדילה)
                                            if (previousHeight > 0 && currentHeight > previousHeight) {
                                                val diff = (currentHeight - previousHeight).toFloat()
                                                
                                                // האם המשתמש קורא היסטוריה?
                                                val isAtBottom = listState.firstVisibleItemIndex == 0 && 
                                                               listState.firstVisibleItemScrollOffset == 0
                                                
                                                if (!isAtBottom) {
                                                    // שלב 2: עדכון מיידי של התיקון הויזואלי.
                                                    // זה יגרום לרשימה להיות מצוירת נמוך יותר בפריים הנוכחי,
                                                    // ויבטל את הקפיצה למעלה שנוצרה מהגדילה.
                                                    listTranslationY += diff
                                                    
                                                    // שלב 3: תזמון התיקון הפיזיקלי לרגע הבטוח הבא
                                                    view.post {
                                                        // ביצוע הגלילה האמיתית
                                                        listState.dispatchRawDelta(diff)
                                                        
                                                        // ביטול התיקון הויזואלי (כי הגלילה האמיתית החליפה אותו)
                                                        // אנחנו מחסרים את מה שהוספנו
                                                        listTranslationY -= diff
                                                    }
                                                }
                                            }
                                            previousHeight = currentHeight
                                        }
                                )
                            }
                        }

                        // ... (שאר הקוד של הכפתורים וההודעות נשאר זהה לחלוטין) ...
                        
                        // Show temporary reply button bubble when multi-message mode is active
                        if (uiState.showReplyButton && !uiState.isStreaming && !uiState.isLoading) {
                            item {
                                ReplyPromptBubble(
                                    onClick = { viewModel.sendBufferedBatch() },
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                        
                        // Show tool execution loading indicator if a tool is executing
                        uiState.executingToolCall?.let { toolInfo ->
                            item {
                                ToolExecutionLoadingBubble(
                                    toolInfo = toolInfo,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                                )
                            }
                        }
                        
                        uiState.currentChat?.messages?.let { messages ->
                            val reversedMessages = messages.reversed()
                            itemsIndexed(reversedMessages) { index, message ->
                                if (message.role == "tool_call") return@itemsIndexed
                                
                                val isFirstMessage = index == 0
                                val previousMessage = if (index > 0) {
                                    var prevIndex = index - 1
                                    while (prevIndex >= 0 && reversedMessages[prevIndex].role == "tool_call") {
                                        prevIndex--
                                    }
                                    if (prevIndex >= 0) reversedMessages[prevIndex] else null
                                } else null
                                val isSameSpeaker = previousMessage?.role == message.role

                                val topPadding = if (isFirstMessage || !isSameSpeaker) 4.dp else 0.dp
                                val bottomPadding = if (!isSameSpeaker) 4.dp else 0.dp
                                val originalIndex = messages.size - 1 - index
                                
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

                    // Floating Scroll Buttons Logic
                    var showScrollButton by remember { mutableStateOf(false) }
                    var hideButtonJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                    
                    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
                        val isAtBottom = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                        
                        if (!isAtBottom && !showScrollButton) {
                            showScrollButton = true
                            hideButtonJob?.cancel()
                            hideButtonJob = launch {
                                kotlinx.coroutines.delay(3000)
                                showScrollButton = false
                            }
                        } else if (isAtBottom) {
                            hideButtonJob?.cancel()
                            showScrollButton = false
                        }
                    }

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
                                    hideButtonJob?.cancel()
                                },
                                shape = CircleShape,
                                containerColor = Primary,
                                contentColor = Color.White,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Previous",
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            FloatingActionButton(
                                onClick = {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(0)
                                    }
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
                                    contentDescription = "Bottom",
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // Selected Files Preview
                SelectedFilesPreview(
                    selectedFiles = uiState.selectedFiles,
                    onRemoveFile = { viewModel.removeSelectedFile(it) }
                )

                // Modern Message Input Area
                ChatInputArea(
                    currentMessage = uiState.currentMessage,
                    onMessageChange = { viewModel.updateMessage(it) },
                    selectedFiles = uiState.selectedFiles,
                    isEditMode = uiState.isEditMode,
                    isLoading = uiState.isLoading,
                    isStreaming = uiState.isStreaming,
                    webSearchSupport = uiState.webSearchSupport,
                    webSearchEnabled = uiState.webSearchEnabled,
                    onToggleWebSearch = { viewModel.toggleWebSearch() },
                    onSendMessage = { viewModel.sendMessage() },
                    onStopStreaming = { viewModel.stopStreamingAndSave() },
                    onFinishEditing = { viewModel.finishEditingMessage() },
                    onConfirmEditAndResend = { viewModel.confirmEditAndResend() },
                    onFileSelected = { uri, name, mime -> viewModel.addFileFromUri(uri, name, mime) },
                    onMultipleFilesSelected = { filesList -> viewModel.addMultipleFilesFromUris(filesList) }
                )
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
                    onDismiss = { viewModel.hideProviderSelector() },
                    onRefresh = { viewModel.refreshModels() }
                )
            }

            // Model Selector
            if (uiState.showModelSelector) {
                ModelSelectorDialog(
                    models = uiState.currentProvider?.models ?: emptyList(),
                    onModelSelected = { viewModel.selectModel(it) },
                    onDismiss = { viewModel.hideModelSelector() },
                    onRefresh = { viewModel.refreshModels() }
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
                                onNewChat = { viewModel.createNewChat("שיחה חדשה") },
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

            // Chat Export Dialog
            if (uiState.showChatExportDialog) {
                ChatExportDialog(
                    viewModel = viewModel,
                    uiState = uiState,
                    onDismiss = { viewModel.closeChatExportDialog() }
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
