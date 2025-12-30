package com.example.ApI.ui.components

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.components.markdown.MarkdownText
import com.example.ApI.ui.screen.createHighlightedText
import com.example.ApI.ui.theme.*
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                // Select this model when clicked
                                viewModel.selectModelByName(message.model)
                            }
                            .padding(
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
