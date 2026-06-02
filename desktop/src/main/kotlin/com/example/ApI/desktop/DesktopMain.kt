package com.example.ApI.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.ApI.data.model.*
import com.example.ApI.util.AppLogger
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

private val Primary = Color(0xFF6C7CE7)
private val Secondary = Color(0xFF5B6EF7)
private val Background = Color(0xFF0D0E14)
private val Surface = Color(0xFF1A1B26)
private val SurfaceVariant = Color(0xFF20212C)
private val OnSurface = Color(0xFFE8E8F2)
private val OnSurfaceVariant = Color(0xFFBDBDBD)
private val UserBubble = Color(0xFF6C7CE7)
private val AssistantBubble = Color(0xFF2A2B3A)
private val SystemBubble = Color(0xFF3A3B4A)
private val AccentRed = Color(0xFFEC7063)
private val AccentGreen = Color(0xFF58D68D)

fun main() = application {
    val appDir = remember {
        val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        File(base, "LLM api Desktop").apply { mkdirs() }
    }
    val viewModel = remember { DesktopChatViewModel(DesktopRepository(appDir)) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "LLM api Desktop",
        state = rememberWindowState(width = 1240.dp, height = 820.dp)
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Primary,
                secondary = Secondary,
                background = Background,
                surface = Surface,
                surfaceVariant = SurfaceVariant,
                onPrimary = Color.White,
                onBackground = OnSurface,
                onSurface = OnSurface,
                onSurfaceVariant = OnSurfaceVariant,
                error = AccentRed
            )
        ) {
            DesktopApp(viewModel)
        }
    }
}

@Composable
private fun DesktopApp(viewModel: DesktopChatViewModel) {
    val state = viewModel.state

    Box(Modifier.fillMaxSize().background(Background)) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(state, viewModel)
            VerticalDivider(color = SurfaceVariant)
            when (state.pane) {
                DesktopPane.Chat -> ChatPane(state, viewModel)
                DesktopPane.ApiKeys -> ApiKeysPane(state, viewModel)
                DesktopPane.Settings -> SettingsPane(state, viewModel)
                DesktopPane.Logs -> LogsPane()
            }
        }

        state.snackbarMessage?.let { message ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                action = {
                    TextButton(onClick = viewModel::clearSnackbar) {
                        Text("Close")
                    }
                }
            ) {
                Text(message)
            }
        }

        if (state.showApiKeyDialog) {
            ApiKeyDialog(state, viewModel)
        }
        if (state.showModelDialog) {
            ModelDialog(state, viewModel)
        }
        if (state.showSystemPromptDialog) {
            SystemPromptDialog(state, viewModel)
        }
        if (state.showExportDialog) {
            ExportDialog(state, viewModel)
        }
    }
}

@Composable
private fun Sidebar(state: DesktopChatState, viewModel: DesktopChatViewModel) {
    Column(
        Modifier.width(310.dp).fillMaxHeight().background(Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(
                painter = painterResource("ApI logo.png"),
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Column(Modifier.weight(1f)) {
                Text("LLM api", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(state.settings.current_user, color = OnSurfaceVariant, fontSize = 12.sp, maxLines = 1)
            }
        }

        Button(
            onClick = viewModel::newChat,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("New chat")
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::updateSearch,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search chats") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavigationChip(
                selected = state.pane == DesktopPane.Chat,
                label = "Chats",
                onClick = { viewModel.setPane(DesktopPane.Chat) },
                modifier = Modifier.weight(1f)
            )
            NavigationChip(
                selected = state.pane == DesktopPane.ApiKeys,
                label = "Keys",
                onClick = { viewModel.setPane(DesktopPane.ApiKeys) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavigationChip(
                selected = state.pane == DesktopPane.Settings,
                label = "Settings",
                onClick = { viewModel.setPane(DesktopPane.Settings) },
                modifier = Modifier.weight(1f)
            )
            NavigationChip(
                selected = state.pane == DesktopPane.Logs,
                label = "Logs",
                onClick = { viewModel.setPane(DesktopPane.Logs) },
                modifier = Modifier.weight(1f)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.filteredChats.asReversed(), key = { it.chat_id }) { chat ->
                ChatHistoryRow(
                    chat = chat,
                    selected = chat.chat_id == state.currentChat?.chat_id,
                    onClick = { viewModel.selectChat(chat) }
                )
            }
        }
    }
}

@Composable
private fun NavigationChip(selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(36.dp).clickable(onClick = onClick),
        color = if (selected) Primary.copy(alpha = 0.24f) else SurfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, color = if (selected) OnSurface else OnSurfaceVariant)
        }
    }
}

@Composable
private fun ChatHistoryRow(chat: Chat, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) Primary.copy(alpha = 0.18f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                chat.preview_name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
            val last = visibleMessages(chat).lastOrNull { it.role != "tool_call" && it.role != "tool_response" }
            Text(
                last?.text?.replace('\n', ' ')?.take(96) ?: "No messages yet",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = OnSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ChatPane(state: DesktopChatState, viewModel: DesktopChatViewModel) {
    Column(Modifier.fillMaxSize()) {
        ChatTopBar(state, viewModel)
        HorizontalDivider(color = SurfaceVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val chat = state.currentChat
            if (chat == null) {
                EmptyChat(viewModel)
            } else {
                MessageList(chat, state)
            }
        }
        ChatInput(state, viewModel)
    }
}

@Composable
private fun ChatTopBar(state: DesktopChatState, viewModel: DesktopChatViewModel) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Surface).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                state.currentChat?.preview_name ?: "Chat",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${state.currentProvider?.provider ?: "provider"} / ${state.currentModel}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = OnSurfaceVariant,
                fontSize = 12.sp
            )
        }

        AssistChip(
            onClick = viewModel::showModelDialog,
            label = { Text(state.currentModel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
        )

        DirectionButtons(state.textDirectionMode, viewModel::setTextDirection)

        val model = state.currentProvider?.models?.find { it.name == state.currentModel }
        if (model?.webSearch == "optional" || model?.webSearch == "required") {
            FilterChip(
                selected = state.webSearchEnabled,
                onClick = { if (model.webSearch != "required") viewModel.setWebSearchEnabled(!state.webSearchEnabled) },
                label = { Text("Web") },
                enabled = model.webSearch != "required"
            )
        }

        IconButton(onClick = viewModel::showSystemPromptDialog) {
            Icon(Icons.Default.Settings, contentDescription = "System prompt")
        }
        IconButton(onClick = viewModel::saveCurrentChatToDownloads, enabled = state.currentChat != null) {
            Icon(Icons.Default.Download, contentDescription = "Export chat")
        }
        IconButton(onClick = viewModel::deleteCurrentChat, enabled = state.currentChat != null && !state.isStreaming) {
            Icon(Icons.Default.Delete, contentDescription = "Delete chat")
        }
    }
}

@Composable
private fun DirectionButtons(mode: TextDirectionMode, onChange: (TextDirectionMode) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = mode == TextDirectionMode.RTL,
            onClick = { onChange(TextDirectionMode.RTL) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
            label = { Text("RTL", fontSize = 12.sp) }
        )
        SegmentedButton(
            selected = mode == TextDirectionMode.AUTO,
            onClick = { onChange(TextDirectionMode.AUTO) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
            label = { Text("A", fontSize = 12.sp) }
        )
        SegmentedButton(
            selected = mode == TextDirectionMode.LTR,
            onClick = { onChange(TextDirectionMode.LTR) },
            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
            label = { Text("LTR", fontSize = 12.sp) }
        )
    }
}

@Composable
private fun EmptyChat(viewModel: DesktopChatViewModel) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = viewModel::newChat, shape = RoundedCornerShape(8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Start a chat")
        }
    }
}

@Composable
private fun MessageList(chat: Chat, state: DesktopChatState) {
    val messages = visibleMessages(chat).filterNot { it.role == "tool_call" || it.role == "tool_response" }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message, state.textDirectionMode)
        }
        if (state.streamingThoughts.isNotBlank()) {
            item {
                ThoughtBubble(state.streamingThoughts)
            }
        }
        if (state.streamingText.isNotBlank()) {
            item {
                MessageBubble(
                    Message(role = "assistant", text = state.streamingText, model = state.currentModel),
                    state.textDirectionMode,
                    isStreaming = true
                )
            }
        }
        state.errorMessage?.let { error ->
            item {
                Surface(color = AccentRed.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
                    Text(error, color = AccentRed, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message, mode: TextDirectionMode, isStreaming: Boolean = false) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text((message.model ?: "A").firstOrNull()?.uppercase() ?: "A", color = Primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            modifier = Modifier.widthIn(max = 780.dp),
            color = when {
                isUser -> UserBubble
                message.role == "system" -> SystemBubble
                else -> AssistantBubble
            },
            shape = if (isUser) RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp) else RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isUser && message.model != null) {
                    Text(message.model, color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                if (message.thoughtsStatus != ThoughtsStatus.NONE) {
                    ThoughtBubble(message.thoughts ?: "(thoughts unavailable)")
                }
                message.attachments.forEach { attachment ->
                    AttachmentRow(attachment)
                }
                SelectionContainer {
                    MarkdownLite(message.text, mode, color = if (isUser) Color.White else OnSurface)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isStreaming) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Primary)
                    }
                    message.datetime?.let {
                        Text(it.substringBefore("T").takeIf { date -> date.isNotBlank() } ?: it, fontSize = 10.sp, color = OnSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThoughtBubble(text: String) {
    Surface(color = Primary.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Thoughts", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(text, color = OnSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AttachmentRow(attachment: Attachment) {
    Surface(color = Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(attachment.file_name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MarkdownLite(text: String, mode: TextDirectionMode, color: Color) {
    val direction = when (mode) {
        TextDirectionMode.LTR -> LayoutDirection.Ltr
        TextDirectionMode.RTL -> LayoutDirection.Rtl
        TextDirectionMode.AUTO -> inferDirection(text)
    }
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val lines = text.split('\n')
            var inCode = false
            val codeBuffer = StringBuilder()
            lines.forEach { line ->
                if (line.trim().startsWith("```")) {
                    if (inCode) {
                        CodeBlock(codeBuffer.toString().trimEnd())
                        codeBuffer.clear()
                    }
                    inCode = !inCode
                    return@forEach
                }
                if (inCode) {
                    codeBuffer.appendLine(line)
                    return@forEach
                }
                when {
                    line.startsWith("# ") -> Text(line.removePrefix("# "), color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    line.startsWith("## ") -> Text(line.removePrefix("## "), color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    line.startsWith("### ") -> Text(line.removePrefix("### "), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    line.trimStart().startsWith("- ") -> Text("• ${line.trimStart().removePrefix("- ")}", color = color, lineHeight = 22.sp)
                    line.isBlank() -> Spacer(Modifier.height(4.dp))
                    else -> Text(line, color = color, lineHeight = 23.sp)
                }
            }
            if (codeBuffer.isNotBlank()) {
                CodeBlock(codeBuffer.toString().trimEnd())
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Surface(color = Color.Black.copy(alpha = 0.28f), shape = RoundedCornerShape(8.dp)) {
        Text(
            code,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
            color = OnSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ChatInput(state: DesktopChatState, viewModel: DesktopChatViewModel) {
    Column(Modifier.fillMaxWidth().background(Background).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.selectedFiles.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.selectedFiles.forEach { file ->
                    InputFileChip(file, onRemove = { viewModel.removeFile(file) })
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { viewModel.addFiles(chooseFiles()) }) {
                Icon(Icons.Default.AttachFile, contentDescription = "Attach")
            }
            OutlinedTextField(
                value = state.currentMessage,
                onValueChange = viewModel::updateMessage,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 160.dp),
                placeholder = { Text("Message") },
                shape = RoundedCornerShape(16.dp),
                enabled = !state.isStreaming
            )
            FilledIconButton(
                onClick = viewModel::sendMessage,
                enabled = !state.isStreaming && (state.currentMessage.isNotBlank() || state.selectedFiles.isNotEmpty())
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun InputFileChip(file: PendingDesktopFile, onRemove: () -> Unit) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(file.file.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp).clickable(onClick = onRemove))
        }
    }
}

@Composable
private fun ApiKeysPane(state: DesktopChatState, viewModel: DesktopChatViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("API keys", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = viewModel::showApiKeyDialog, shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Default.Key, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add key")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.apiKeys, key = { it.id }) { key ->
                Surface(color = Surface, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(key.customName ?: key.provider, fontWeight = FontWeight.Bold)
                            Text("${key.provider}  ${maskedKey(key.key)}", color = OnSurfaceVariant, fontSize = 12.sp)
                        }
                        FilterChip(
                            selected = key.isActive,
                            onClick = { viewModel.toggleApiKey(key.id) },
                            label = { Text(if (key.isActive) "Active" else "Off") }
                        )
                        IconButton(onClick = { viewModel.deleteApiKey(key.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPane(state: DesktopChatState, viewModel: DesktopChatViewModel) {
    var username by remember(state.settings.current_user) { mutableStateOf(state.settings.current_user) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Surface(color = Surface, shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("User", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.width(320.dp)
                    )
                    Button(onClick = { viewModel.updateUsername(username) }, shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Automatic title generation", modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.settings.titleGenerationSettings.enabled,
                        onCheckedChange = viewModel::toggleTitleGeneration
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Multi-message mode", modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.settings.multiMessageMode,
                        onCheckedChange = viewModel::toggleMultiMessage
                    )
                }
            }
        }

        Surface(color = Surface, shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Request controls", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Temperature", modifier = Modifier.width(120.dp))
                    Slider(
                        value = state.temperatureValue ?: 1f,
                        onValueChange = viewModel::setTemperature,
                        valueRange = 0f..2f,
                        modifier = Modifier.width(260.dp)
                    )
                    Text(String.format("%.2f", state.temperatureValue ?: 1f), color = OnSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LogsPane() {
    val logs by AppLogger.logs.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Logs", style = MaterialTheme.typography.headlineSmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(logs.reversed()) { log ->
                Surface(color = Surface, shape = RoundedCornerShape(8.dp)) {
                    Text(log.toString(), modifier = Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ApiKeyDialog(state: DesktopChatState, viewModel: DesktopChatViewModel) {
    var provider by remember { mutableStateOf(state.currentProvider?.provider ?: "openai") }
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = viewModel::hideApiKeyDialog,
        title = { Text("Add API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderDropdown(state.providers, provider, onProvider = { provider = it })
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API key") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.addApiKey(provider, key, name) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::hideApiKeyDialog) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ProviderDropdown(providers: List<Provider>, selected: String, onProvider: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(8.dp)) {
            Text(selected)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.provider) },
                    onClick = {
                        onProvider(provider.provider)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelDialog(state: DesktopChatState, viewModel: DesktopChatViewModel) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = viewModel::hideModelDialog,
        title = { Text("Choose model") },
        text = {
            Row(Modifier.width(820.dp).height(560.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.providers, key = { it.provider }) { provider ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.selectProvider(provider) },
                            color = if (provider.provider == state.currentProvider?.provider) Primary.copy(alpha = 0.24f) else SurfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(provider.provider, modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search models") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val models = state.currentProvider?.models.orEmpty()
                        .filter { it.name?.contains(query, ignoreCase = true) != false }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(models, key = { it.name ?: it.toString() }) { model ->
                            val name = model.name ?: "Unknown model"
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { viewModel.selectModel(name) },
                                color = if (name == state.currentModel) Primary.copy(alpha = 0.20f) else Surface,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name, fontWeight = FontWeight.Medium)
                                        PricingLine(model)
                                    }
                                    if (name == state.currentModel) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = AccentGreen)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::hideModelDialog) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun PricingLine(model: Model) {
    val text = when {
        model.pricing?.points != null -> "${model.pricing?.points} points"
        model.pricing?.input_price_per_1k != null || model.pricing?.output_price_per_1k != null ->
            "in ${model.pricing?.input_price_per_1k ?: "-"} / out ${model.pricing?.output_price_per_1k ?: "-"}"
        model.webSearch != null -> "web search: ${model.webSearch}"
        else -> ""
    }
    if (text.isNotBlank()) {
        Text(text, color = OnSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun SystemPromptDialog(state: DesktopChatState, viewModel: DesktopChatViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeSystemPromptDialog,
        title = { Text("System prompt") },
        text = {
            OutlinedTextField(
                value = state.systemPromptDraft,
                onValueChange = viewModel::updateSystemPromptDraft,
                modifier = Modifier.width(640.dp).height(360.dp),
                placeholder = { Text("No system prompt") }
            )
        },
        confirmButton = {
            Button(onClick = viewModel::saveSystemPrompt) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeSystemPromptDialog) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ExportDialog(state: DesktopChatState, viewModel: DesktopChatViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeExportDialog,
        title = { Text("Chat JSON") },
        text = {
            OutlinedTextField(
                value = state.exportJson,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.width(760.dp).height(460.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
        },
        confirmButton = {
            Button(onClick = {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(state.exportJson), null)
                viewModel.closeExportDialog()
            }) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeExportDialog) {
                Text("Close")
            }
        }
    )
}

private fun chooseFiles(): List<File> {
    val dialog = FileDialog(null as Frame?, "Choose files", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files?.toList().orEmpty()
}

private fun inferDirection(text: String): LayoutDirection {
    text.forEach { char ->
        if (!char.isLetter()) return@forEach
        val code = char.code
        return if (
            code in 0x0590..0x05FF ||
            code in 0x0600..0x06FF ||
            code in 0x0750..0x077F ||
            code in 0x08A0..0x08FF
        ) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    }
    return LayoutDirection.Ltr
}

private fun maskedKey(key: String): String {
    if (key.length <= 8) return "*".repeat(key.length)
    return "${key.take(4)}...${key.takeLast(4)}"
}
