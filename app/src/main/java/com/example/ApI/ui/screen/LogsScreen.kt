package com.example.ApI.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.util.AppLogger
import com.example.ApI.util.LogEntry
import com.example.ApI.util.LogLevel

// Minimal black theme colors for logs screen
private val LogsBackground = Color(0xFF000000)
private val LogsHeaderBackground = Color(0xFF111111)
private val LogsRowBackground = Color(0xFF0A0A0A)
private val LogsRowAlternate = Color(0xFF050505)
private val LogsBorderColor = Color(0xFF222222)
private val LogsTextColor = Color(0xFFCCCCCC)
private val LogsTimestampColor = Color(0xFF888888)
private val LogsErrorColor = Color(0xFFFF6B6B)
private val LogsWarningColor = Color(0xFFFFE066)
private val LogsDebugColor = Color(0xFF888888)
private val LogsInfoColor = Color(0xFFCCCCCC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logs by AppLogger.logs.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(LogsBackground)
                .systemBarsPadding()
        ) {
            // Top Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = LogsHeaderBackground,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = LogsBorderColor,
                            modifier = Modifier
                                .size(36.dp)
                                .clickable { onBackClick() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = LogsTextColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Text(
                            text = "לוגים",
                            style = MaterialTheme.typography.titleMedium,
                            color = LogsTextColor,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Clear logs button
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = LogsBorderColor,
                        modifier = Modifier
                            .clickable { AppLogger.clearLogs() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear",
                                tint = LogsTextColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "נקה",
                                style = MaterialTheme.typography.bodySmall,
                                color = LogsTextColor
                            )
                        }
                    }
                }
            }

            // Table Header
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LogsHeaderBackground)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = "Time",
                        style = MaterialTheme.typography.labelSmall,
                        color = LogsTimestampColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(70.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Log",
                        style = MaterialTheme.typography.labelSmall,
                        color = LogsTimestampColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Divider
                Divider(color = LogsBorderColor, thickness = 1.dp)

                // Logs List
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No logs yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LogsTimestampColor
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(logs.size) { index ->
                            val log = logs[index]
                            LogRow(
                                log = log,
                                isAlternate = index % 2 == 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(
    log: LogEntry,
    isAlternate: Boolean
) {
    val backgroundColor = if (isAlternate) LogsRowAlternate else LogsRowBackground
    val textColor = when (log.level) {
        LogLevel.ERROR -> LogsErrorColor
        LogLevel.WARNING -> LogsWarningColor
        LogLevel.DEBUG -> LogsDebugColor
        LogLevel.INFO -> LogsInfoColor
    }

    SelectionContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = log.timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = LogsTimestampColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.width(70.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
    }
}
