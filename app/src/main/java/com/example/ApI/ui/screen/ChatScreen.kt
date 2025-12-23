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
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Top bars column (without padding between them)
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                                // Normal top bar with all elements (original layout)
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
                                                    "anthropic" -> R.string.provider_anthropic
                                                    "google" -> R.string.provider_google
                                                    "poe" -> R.string.provider_poe
                                                    "cohere" -> R.string.provider_cohere
                                                    "openrouter" -> R.string.provider_openrouter
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

                        // Expandable bar positioned directly below the top bar (no padding)
                        AnimatedVisibility(
                            visible = uiState.quickSettingsExpanded && !uiState.searchMode,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Surface,
                                shadowElevation = 1.dp
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 16.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Spacer(modifier = Modifier.weight(1f))

                                    // Thinking Budget Control Button
                                    Box {
                                        val budgetType = uiState.getThinkingBudgetType()
                                        val isThinkingSupported = budgetType is ThinkingBudgetType.Discrete ||
                                                                  budgetType is ThinkingBudgetType.Continuous
                                        // Lightbulb is "on" only when thinking is supported AND actually enabled
                                        val isThinkingActive = isThinkingSupported &&
                                                               ThinkingBudgetConfig.isThinkingEnabled(uiState.thinkingBudgetValue)

                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = if (isThinkingActive) Primary.copy(alpha = 0.15f) else SurfaceVariant,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clickable { viewModel.onThinkingBudgetButtonClick() }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Lightbulb,
                                                    contentDescription = "Thinking budget",
                                                    tint = if (isThinkingActive) Primary else OnSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        // Thinking Budget Popup
                                        ThinkingBudgetPopup(
                                            visible = uiState.showThinkingBudgetPopup,
                                            budgetType = budgetType,
                                            currentValue = uiState.thinkingBudgetValue,
                                            onValueChange = { value ->
                                                when (value) {
                                                    is ThinkingBudgetValue.Effort -> viewModel.setThinkingEffort(value.level)
                                                    is ThinkingBudgetValue.Tokens -> viewModel.setThinkingTokenBudget(value.count)
                                                    ThinkingBudgetValue.None -> {}
                                                }
                                            },
                                            onDismiss = { viewModel.hideThinkingBudgetPopup() }
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Temperature Control Button
                                    Box {
                                        val tempConfig = uiState.getTemperatureConfig()
                                        val isTemperatureSupported = tempConfig != null
                                        val isTemperatureActive = isTemperatureSupported && uiState.temperatureValue != null

                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = if (isTemperatureActive) Primary.copy(alpha = 0.15f) else SurfaceVariant,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clickable { viewModel.onTemperatureButtonClick() }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Thermostat,
                                                    contentDescription = "Temperature",
                                                    tint = if (isTemperatureActive) Primary else OnSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        // Temperature Popup with Slider
                                        TemperaturePopup(
                                            visible = uiState.showTemperaturePopup,
                                            config = tempConfig,
                                            currentValue = uiState.temperatureValue,
                                            onValueChange = { viewModel.setTemperatureValue(it) },
                                            onDismiss = { viewModel.hideTemperaturePopup() }
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Text Direction Toggle Button with Popup Menu
                                    var showTextDirectionMenu by remember { mutableStateOf(false) }

                                    Box {
                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = SurfaceVariant,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clickable { showTextDirectionMenu = !showTextDirectionMenu }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.FormatAlignRight,
                                                    contentDescription = "Text Direction",
                                                    tint = OnSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        // Popup menu for text direction options
                                        DropdownMenu(
                                            expanded = showTextDirectionMenu,
                                            onDismissRequest = { showTextDirectionMenu = false }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                // RTL button (left in code, right on screen due to RTL layout)
                                                Surface(
                                                    shape = MaterialTheme.shapes.small,
                                                    color = if (uiState.textDirectionMode == TextDirectionMode.RTL)
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    else
                                                        SurfaceVariant,
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clickable {
                                                            viewModel.setTextDirectionMode(TextDirectionMode.RTL)
                                                            showTextDirectionMenu = false
                                                        }
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Default.FormatAlignRight,
                                                            contentDescription = "RTL",
                                                            tint = if (uiState.textDirectionMode == TextDirectionMode.RTL)
                                                                MaterialTheme.colorScheme.onPrimaryContainer
                                                            else
                                                                OnSurfaceVariant,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }

                                                // AUTO button (middle)
                                                Surface(
                                                    shape = MaterialTheme.shapes.small,
                                                    color = if (uiState.textDirectionMode == TextDirectionMode.AUTO)
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    else
                                                        SurfaceVariant,
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clickable {
                                                            viewModel.setTextDirectionMode(TextDirectionMode.AUTO)
                                                            showTextDirectionMenu = false
                                                        }
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text(
                                                            text = "A",
                                                            color = if (uiState.textDirectionMode == TextDirectionMode.AUTO)
                                                                MaterialTheme.colorScheme.onPrimaryContainer
                                                            else
                                                                OnSurfaceVariant,
                                                            fontSize = 16.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }

                                                // LTR button (right in code, left on screen due to RTL layout)
                                                Surface(
                                                    shape = MaterialTheme.shapes.small,
                                                    color = if (uiState.textDirectionMode == TextDirectionMode.LTR)
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    else
                                                        SurfaceVariant,
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clickable {
                                                            viewModel.setTextDirectionMode(TextDirectionMode.LTR)
                                                            showTextDirectionMenu = false
                                                        }
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Default.FormatAlignLeft,
                                                            contentDescription = "LTR",
                                                            tint = if (uiState.textDirectionMode == TextDirectionMode.LTR)
                                                                MaterialTheme.colorScheme.onPrimaryContainer
                                                            else
                                                                OnSurfaceVariant,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))
                                    
                                    // Download/Export Button
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = SurfaceVariant,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clickable { viewModel.openChatExportDialog() }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "Download chat",
                                                tint = OnSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(45.dp))
                                }
                            }
                        }
                    }

                    // Floating arrow positioned absolutely over the bars at their junction
                    if (!uiState.searchMode) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 68.dp, end = 20.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = SurfaceVariant,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable { viewModel.toggleQuickSettings() },
                                shadowElevation = 4.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (uiState.quickSettingsExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                        contentDescription = if (uiState.quickSettingsExpanded) "Collapse quick settings" else "Expand quick settings",
                                        tint = OnSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

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
                                    // Regular send or stop button
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = if (uiState.isLoading || uiState.isStreaming) {
                                            // Active/clickable when streaming (for stop)
                                            Primary
                                        } else if (uiState.currentMessage.isNotEmpty() || uiState.selectedFiles.isNotEmpty()) {
                                            Primary
                                        } else {
                                            Primary.copy(alpha = 0.3f)
                                        },
                                        modifier = Modifier.size(40.dp).clickable(
                                            enabled = (uiState.isLoading || uiState.isStreaming) ||
                                                (uiState.currentMessage.isNotEmpty() || uiState.selectedFiles.isNotEmpty())
                                        ) {
                                            if (uiState.isLoading || uiState.isStreaming) {
                                                // Stop streaming and save accumulated text
                                                viewModel.stopStreamingAndSave()
                                            } else {
                                                viewModel.sendMessage()
                                            }
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            if (uiState.isLoading || uiState.isStreaming) {
                                                // Loading circle with stop icon inside
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(28.dp),
                                                    color = Color.White,
                                                    strokeWidth = 2.dp
                                                )
                                                // Stop icon (square) inside the loading circle
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

/**
 * Composable for displaying model thoughts/thinking in an expandable area
 */
@Composable
fun ThoughtsBubble(
    thoughts: String?,
    durationSeconds: Float?,
    status: ThoughtsStatus,
    isStreaming: Boolean = false,
    streamingThoughts: String = "",
    streamingStartTime: Long? = null,
    textDirectionMode: TextDirectionMode,
    modifier: Modifier = Modifier
) {
    // Don't show anything if no thoughts
    if (status == ThoughtsStatus.NONE) return

    var isExpanded by remember { mutableStateOf(false) }

    // Calculate live duration for streaming
    val currentTime by produceState(initialValue = System.currentTimeMillis()) {
        while (isStreaming && streamingStartTime != null) {
            delay(100) // Update every 100ms
            value = System.currentTimeMillis()
        }
    }

    val displayDuration = when {
        isStreaming && streamingStartTime != null -> (currentTime - streamingStartTime) / 1000f
        durationSeconds != null -> durationSeconds
        else -> 0f
    }

    // Use streamingThoughts if available (even after thinking is done but response is streaming)
    // Fall back to thoughts for completed messages from history
    val displayThoughts = streamingThoughts.ifBlank { thoughts }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Primary.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Primary.copy(alpha = 0.2f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = status == ThoughtsStatus.PRESENT && !isStreaming,
                onClick = { isExpanded = !isExpanded }
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Thinking icon
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = "מחשבות",
                    tint = Primary,
                    modifier = Modifier.size(20.dp)
                )

                // Title with duration
                Text(
                    text = "מחשבות... (${String.format("%.1f", displayDuration)} שניות)",
                    style = MaterialTheme.typography.titleSmall,
                    color = Primary,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.weight(1f))

                // Expand/collapse icon (only for PRESENT status and not streaming)
                if (status == ThoughtsStatus.PRESENT && !isStreaming) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "כווץ" else "הרחב",
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Expandable content
            AnimatedVisibility(
                visible = (isExpanded && status == ThoughtsStatus.PRESENT && !isStreaming) || status == ThoughtsStatus.UNAVAILABLE || (isStreaming && !displayThoughts.isNullOrBlank()),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    when {
                        status == ThoughtsStatus.UNAVAILABLE -> {
                            Text(
                                text = "(מחשבות מודל זה לא זמינות)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceVariant.copy(alpha = 0.7f),
                                fontStyle = FontStyle.Italic
                            )
                        }
                        status == ThoughtsStatus.PRESENT && !displayThoughts.isNullOrBlank() -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    MarkdownText(
                                        markdown = displayThoughts,
                                        style = TextStyle(
                                            color = OnSurfaceVariant,
                                            fontSize = 14.sp,
                                            lineHeight = 18.sp
                                        ),
                                        textDirectionMode = textDirectionMode,
                                        modifier = Modifier.padding(12.dp)
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
    val uiState by viewModel.uiState.collectAsState()
    
    // Handle tool call and tool response messages specially
    if (message.isToolCall || message.toolCall != null || message.isToolResponse) {
        ToolCallBubble(
            message = message,
            viewModel = viewModel,
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
    
    // Get branch info for this message (only for user messages)
    val branchInfo = remember(message.id, uiState.currentChat?.currentVariantPath) {
        if (isUser) {
            viewModel.getBranchInfoForMessage(message)
        } else {
            null
        }
    }
    
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
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { showContextMenu = true }
                    )
                }
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

                // Show thoughts area for assistant messages
                if (!isUser && message.thoughtsStatus != ThoughtsStatus.NONE) {
                    ThoughtsBubble(
                        thoughts = message.thoughts,
                        durationSeconds = message.thinkingDurationSeconds,
                        status = message.thoughtsStatus,
                        textDirectionMode = uiState.textDirectionMode,
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = if (message.model != null) 4.dp else 12.dp,
                            bottom = 8.dp
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
                                    ),
                                    textDirectionMode = uiState.textDirectionMode,
                                    onLongPress = { showContextMenu = true }
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

        // Show branch navigator BELOW user messages if there are multiple variants
        if (isUser && branchInfo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                ConditionalBranchNavigator(
                    branchInfo = branchInfo,
                    onPrevious = { 
                        branchInfo.nodeId.let { nodeId ->
                            viewModel.navigateToPreviousVariant(nodeId)
                        }
                    },
                    onNext = {
                        branchInfo.nodeId.let { nodeId ->
                            viewModel.navigateToNextVariant(nodeId)
                        }
                    },
                    compact = true
                )
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
fun StreamingMessageBubble(
    text: String,
    textDirectionMode: TextDirectionMode,
    modifier: Modifier = Modifier,
    isThinking: Boolean = false,
    streamingThoughts: String = "",
    thinkingStartTime: Long? = null,
    completedThinkingDuration: Float? = null
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
                // Show thoughts area during thinking phase and while response is streaming
                if (isThinking || streamingThoughts.isNotBlank()) {
                    ThoughtsBubble(
                        thoughts = null,
                        durationSeconds = completedThinkingDuration,  // Use completed duration when available
                        status = ThoughtsStatus.PRESENT,
                        isStreaming = isThinking,  // Only live timer if still thinking
                        streamingThoughts = streamingThoughts,
                        streamingStartTime = thinkingStartTime,
                        textDirectionMode = textDirectionMode,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                MarkdownText(
                    markdown = text,
                    style = TextStyle(
                        color = OnSurface,
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                        letterSpacing = 0.sp
                    ),
                    textDirectionMode = textDirectionMode,
                    onLongPress = {} // No long press action for streaming messages
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
