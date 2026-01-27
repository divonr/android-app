package com.example.ApI.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.data.model.*
import com.example.ApI.ui.theme.*
import com.example.ApI.ui.screen.getModelInitial
import com.example.ApI.ui.screen.formatTimestamp
import com.example.ApI.ui.screen.getLastTimestampOrNull
import com.example.ApI.ui.screen.createHighlightedText

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
            // Model initial circle or logo
            val lastAssistantMessage = chat.messages.lastOrNull { it.role == "assistant" }
            val modelName = lastAssistantMessage?.model ?: chat.model
            val logoPath = ModelLogoUtils.getModelLogoPath(modelName)
            val initial = getModelInitial(modelName)

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (logoPath != null) {
                   AsyncImage(
                        model = logoPath,
                        contentDescription = "Model Logo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                   )
                } else {
                    Text(
                        text = initial,
                        color = Primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
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
            // Model initial circle or logo
            val lastAssistantMessage = searchResult.chat.messages.lastOrNull { it.role == "assistant" }
            val modelName = lastAssistantMessage?.model ?: searchResult.chat.model
            val logoPath = ModelLogoUtils.getModelLogoPath(modelName)
            val initial = getModelInitial(modelName)

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (logoPath != null) {
                    AsyncImage(
                        model = logoPath,
                        contentDescription = "Model Logo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = initial,
                        color = Primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
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
