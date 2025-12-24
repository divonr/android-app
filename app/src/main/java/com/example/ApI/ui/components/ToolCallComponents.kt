package com.example.ApI.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ApI.data.model.ExecutingToolInfo
import com.example.ApI.data.model.Message
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Loading indicator bubble shown while a tool is being executed.
 */
@Composable
fun ToolExecutionLoadingBubble(
    toolInfo: ExecutingToolInfo,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 6.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp
            ),
            color = Primary.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, Primary.copy(alpha = 0.3f)),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Tool icon with pulsing animation
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Router,
                                contentDescription = "MCP Tool",
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Tool name and loading status
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${toolInfo.toolName}",
                            style = MaterialTheme.typography.titleSmall,
                            color = Primary,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Primary
                            )
                            Text(
                                text = "Executing...",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Quick info about what's happening
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Running ${toolInfo.toolName}...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Bubble displaying a completed tool call with expandable details.
 */
@Composable
fun ToolCallBubble(
    message: Message,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false
) {
    // Collect UI state for accessing current chat
    val uiState by viewModel.uiState.collectAsState()

    // Handle both tool_call messages (with toolCall field) and tool_response messages
    val toolCallInfo = message.toolCall
    val toolResponseCallId = message.toolResponseCallId
    val toolResponseOutput = message.toolResponseOutput

    // If this is a tool_response message without toolCall info, create a basic display
    if (toolCallInfo == null && toolResponseCallId != null) {
        var isExpanded by remember { mutableStateOf(false) }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 6.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 20.dp
                ),
                color = Primary.copy(alpha = 0.1f),
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Extension,
                                    contentDescription = "MCP Tool",
                                    tint = Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Get tool name from ToolCallInfo (the authoritative source)
                            val toolName = if (message.role == "tool_response") {
                                // Find the corresponding tool_call message by toolResponseCallId
                                val currentChat = uiState.currentChat
                                val correspondingToolCall = currentChat?.messages?.find {
                                    it.role == "tool_call" && it.toolCallId == message.toolResponseCallId
                                }
                                // Use toolCall.toolName from the corresponding message, not text field
                                correspondingToolCall?.toolCall?.toolName ?: "Tool Call"
                            } else {
                                // For tool_call messages, use toolCall.toolName directly
                                message.toolCall?.toolName ?: "Tool Call"
                            }

                            Text(
                                text = "Tool Call",
                                style = MaterialTheme.typography.titleSmall,
                                color = Primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "✅ Completed",
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentGreen,
                                fontWeight = FontWeight.Medium
                            )
                        }

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

                    Spacer(modifier = Modifier.height(8.dp))

                    // Get tool name from ToolCallInfo (the authoritative source)
                    val toolName = if (message.role == "tool_response") {
                        // Find the corresponding tool_call message by toolResponseCallId
                        val currentChat = uiState.currentChat
                        val correspondingToolCall = currentChat?.messages?.find {
                            it.role == "tool_call" && it.toolCallId == message.toolResponseCallId
                        }
                        // Use toolCall.toolName from the corresponding message, not text field
                        correspondingToolCall?.toolCall?.toolName ?: "Tool Call"
                    } else {
                        // For tool_call messages, use toolCall.toolName directly
                        message.toolCall?.toolName ?: "Tool Call"
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = toolName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurface,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = isExpanded && toolResponseOutput != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = "Full Result:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = OnSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AccentGreen.copy(alpha = 0.1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = toolResponseOutput ?: "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnSurface,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // Original logic for messages with full toolCall info
    if (toolCallInfo == null) return
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
                                imageVector = Icons.Default.Extension, // Tool/function icon
                                contentDescription = "MCP Tool",
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
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = toolCallInfo.toolName,
                                style = MaterialTheme.typography.titleSmall,
                                color = Primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

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

                // Tool name display (always visible)
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = toolCallInfo.toolName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurface,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // Expanded details
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
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
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Full result section
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = "Result:",
                                style = MaterialTheme.typography.labelMedium,
                                color = OnSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

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
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Text(
                                            text = result.result,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = OnSurface,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                                is ToolExecutionResult.Error -> {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Text(
                                            text = result.error,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AccentRed,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
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
