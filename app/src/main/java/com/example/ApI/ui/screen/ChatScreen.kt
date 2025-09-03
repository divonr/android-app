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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import coil.compose.AsyncImage
import com.example.ApI.R
import com.example.ApI.data.model.*
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
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Background)
        ) {
            // Back cancels edit mode if active
            BackHandler(enabled = uiState.isEditMode) {
                viewModel.cancelEditingMessage()
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

                                MessageBubble(
                                    message = message,
                                    viewModel = viewModel,
                                    modifier = Modifier.padding(top = topPadding, bottom = bottomPadding),
                                    isEditMode = uiState.isEditMode,
                                    isBeingEdited = uiState.editingMessage == message
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

                    // Animated floating scroll button
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollButton,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
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
                            modifier = Modifier
                                .padding(bottom = 16.dp)
                                .size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Selected Files Preview
                if (uiState.selectedFiles.isNotEmpty()) {
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
    isBeingEdited: Boolean = false
) {
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
            color = bubbleColor,
            shadowElevation = if (isUser) 2.dp else 0.dp,
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