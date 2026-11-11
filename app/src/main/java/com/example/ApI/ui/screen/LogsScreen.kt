package com.example.ApI.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Minimal black theme colors for logs screen
private val LogsBackground = Color(0xFF000000)
private val LogsHeaderBackground = Color(0xFF111111)
private val LogsBorderColor = Color(0xFF222222)
private val LogsTextColor = Color(0xFFCCCCCC)
private val LogsTimestampColor = Color(0xFF888888)

/**
 * Dead-simple viewer for the crash-persistent log file (filesDir/logs/app.log):
 * reads the whole file into a scrollable, selectable text and allows copying it all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var refreshTrigger by remember { mutableStateOf(0) }
    val logText by produceState(initialValue = "", refreshTrigger) {
        value = withContext(Dispatchers.IO) { AppLogger.readPersistentLog() }
    }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
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

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Refresh button
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = LogsBorderColor,
                            modifier = Modifier.clickable { refreshTrigger++ }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = LogsTextColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "רענן",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LogsTextColor
                                )
                            }
                        }

                        // Clear logs button
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = LogsBorderColor,
                            modifier = Modifier
                                .clickable {
                                    AppLogger.clearLogs()
                                    AppLogger.clearPersistentLog()
                                    refreshTrigger++
                                }
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
            }

            // Copy-all button
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(logText))
                    copied = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LogsBorderColor,
                    contentColor = LogsTextColor
                )
            ) {
                Text(
                    text = if (copied) "הועתק!" else "העתק הכל",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Divider(color = LogsBorderColor, thickness = 1.dp)

            // Log file content
            if (logText.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "אין לוגים עדיין",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LogsTimestampColor
                    )
                }
            } else {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    SelectionContainer {
                        Text(
                            text = logText,
                            style = MaterialTheme.typography.bodySmall,
                            color = LogsTextColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}
