package com.example.ApI.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ApI.R
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.ui.screen.getLastTimestampOrNull
import com.example.ApI.ui.theme.OnSurface
import com.example.ApI.ui.theme.OnSurfaceVariant
import com.example.ApI.ui.theme.Primary

/**
 * [ChatPicker] as a window of its own, over whichever screen is shown (content shared into the
 * app from another app). Back and a tap outside the list cancel it.
 */
@Composable
fun ChatPickerDialog(
    title: String,
    chats: List<Chat>,
    groups: List<ChatGroup>,
    onDismiss: () -> Unit,
    onPick: (Chat?) -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A dialog is its own window: the app's (forced) right-to-left layout is set again here
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ChatPicker(title = title, chats = chats, groups = groups, busy = false, onDismiss = onDismiss, onPick = onPick)
        }
    }
}

/**
 * Choose a chat to put something in (a forwarded image, content shared into the app): "new chat"
 * and under it [chats], most recent first, each with the name of its group. [onPick] gets the
 * chosen chat, null for a new one. Fills the window it is placed in.
 */
@Composable
fun ChatPicker(
    title: String,
    chats: List<Chat>,
    groups: List<ChatGroup>,
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
    val groupNames = remember(groups) { groups.associate { it.group_id to it.group_name } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = com.example.ApI.ui.theme.Surface,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                // Taps inside the list do not close it
                .clickable(enabled = false) {}
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (busy) {
                        CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = OnSurfaceVariant)
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item(key = "new_chat") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { onPick(null) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.new_chat),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Primary
                            )
                        }
                    }
                    items(items = sorted, key = { it.chat_id }) { chat ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { onPick(chat) }
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = chat.preview_name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = OnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val groupName = chat.group?.let { groupNames[it] }
                            if (groupName != null) {
                                Text(
                                    text = groupName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
