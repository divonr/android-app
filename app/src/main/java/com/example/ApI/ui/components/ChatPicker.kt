package com.example.ApI.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ApI.R
import com.example.ApI.data.model.Chat
import com.example.ApI.ui.screen.getLastTimestampOrNull
import com.example.ApI.ui.theme.Background
import com.example.ApI.ui.theme.OnSurface
import com.example.ApI.ui.theme.OnSurfaceVariant
import com.example.ApI.ui.theme.Primary
import com.example.ApI.ui.theme.SurfaceVariant

/** What is selected in the picker when it is "new chat" (no chat has this id). */
private const val NEW_CHAT_KEY = "\u0000new_chat"

/**
 * [ChatPicker] as a window of its own, over whichever screen is shown (content shared into the
 * app from another app). Back cancels it.
 */
@Composable
fun ChatPickerDialog(
    title: String,
    chats: List<Chat>,
    onDismiss: () -> Unit,
    onPick: (Chat?) -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A dialog is its own window: the app's (forced) right-to-left layout is set again here
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ChatPicker(title = title, chats = chats, busy = false, onDismiss = onDismiss, onPick = onPick)
        }
    }
}

/**
 * Choose a chat to put something in (a forwarded image, content shared into the app), as a full
 * screen: a top bar with back ([onDismiss]) and [title], and one flat list — "new chat", then
 * [chats], most recent first — whose rows look like the main screen's, each ending in a circle.
 * A tap selects a row (one at a time; a second tap clears it), and the button that then appears
 * at the bottom confirms: [onPick] gets the chosen chat, null for a new one. Fills the window it
 * is placed in.
 */
@Composable
fun ChatPicker(
    title: String,
    chats: List<Chat>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPick: (Chat?) -> Unit
) {
    val sorted = remember(chats) {
        chats.sortedWith(
            compareByDescending<Chat> { getLastTimestampOrNull(it) != null }
                .thenByDescending { getLastTimestampOrNull(it) ?: Long.MIN_VALUE }
        )
    }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    // A selected chat that is no longer listed (deleted meanwhile) is not a selection
    val selectedChat = sorted.find { it.chat_id == selectedKey }
    val hasSelection = selectedKey == NEW_CHAT_KEY || selectedChat != null
    fun toggle(key: String) {
        selectedKey = if (selectedKey == key) null else key
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            // Taps between the rows stay here, not on what the picker covers
            .pointerInput(Unit) { detectTapGestures { } }
            .systemBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar, as on the app's other secondary screens
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = com.example.ApI.ui.theme.Surface,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                            tint = OnSurface
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup(),
                // Room under the last rows for the confirm button
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
            ) {
                item(key = NEW_CHAT_KEY) {
                    PickerRow(
                        selected = selectedKey == NEW_CHAT_KEY,
                        enabled = !busy,
                        onClick = { toggle(NEW_CHAT_KEY) }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Primary, modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.new_chat),
                            color = OnSurface,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                items(items = sorted, key = { it.chat_id }) { chat ->
                    PickerRow(
                        selected = selectedKey == chat.chat_id,
                        enabled = !busy,
                        onClick = { toggle(chat.chat_id) }
                    ) {
                        ChatRowContent(chat = chat)
                    }
                }
            }
        }

        // Confirm: there once something is selected
        AnimatedVisibility(
            visible = hasSelection,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    if (!busy && hasSelection) onPick(selectedChat)
                },
                containerColor = Primary,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                if (busy) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.chat_picker_confirm),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * A row of the picker: the main screen's chat card around [content], ending in the circle that
 * shows whether it is the selected one.
 */
@Composable
private fun PickerRow(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) SurfaceVariant else com.example.ApI.ui.theme.Surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) Modifier.background(Primary)
                        else Modifier.border(2.dp, OnSurfaceVariant.copy(alpha = 0.6f), CircleShape)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
