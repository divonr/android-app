package com.example.ApI.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.ApI.R
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.ui.theme.Gray500
import com.example.ApI.ui.theme.OnSurface
import com.example.ApI.ui.theme.OnSurfaceVariant
import com.example.ApI.ui.theme.Primary
import com.example.ApI.ui.theme.Surface

/**
 * Delete confirmation dialog for a chat
 */
@Composable
fun DeleteChatConfirmationDialog(
    chat: Chat,
    onDismiss: () -> Unit,
    onConfirm: (Chat) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
                    onConfirm(chat)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.delete), color = Color.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
            }
        },
        containerColor = Surface,
        tonalElevation = 0.dp
    )
}

/**
 * Rename dialog for a chat
 */
@Composable
fun RenameChatDialog(
    chat: Chat,
    onDismiss: () -> Unit,
    onConfirm: (Chat, String) -> Unit
) {
    var newTitle by remember { mutableStateOf(chat.title) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.rename),
                color = OnSurface
            )
        },
        text = {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text(stringResource(R.string.chat_title), color = OnSurfaceVariant) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Gray500,
                    focusedLabelColor = Primary,
                    unfocusedLabelColor = OnSurfaceVariant,
                    cursorColor = Primary
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(chat, newTitle)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.save), color = Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
            }
        },
        containerColor = Surface,
        tonalElevation = 0.dp
    )
}

/**
 * Group creation dialog
 */
@Composable
fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "צור קבוצה חדשה",
                color = OnSurface
            )
        },
        text = {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("שם הקבוצה", color = OnSurfaceVariant) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Gray500,
                    focusedLabelColor = Primary,
                    unfocusedLabelColor = OnSurfaceVariant,
                    cursorColor = Primary
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(groupName.trim())
                },
                enabled = groupName.isNotBlank()
            ) {
                Text("צור", color = if (groupName.isNotBlank()) Primary else OnSurfaceVariant)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
            }
        },
        containerColor = Surface,
        tonalElevation = 0.dp
    )
}

/**
 * Group rename dialog
 */
@Composable
fun RenameGroupDialog(
    group: ChatGroup,
    onDismiss: () -> Unit,
    onConfirm: (ChatGroup, String) -> Unit
) {
    var newGroupName by remember(group.group_id) { mutableStateOf(group.group_name) }

    // Reset the name when dialog opens with a new group
    LaunchedEffect(group.group_id, group.group_name) {
        newGroupName = group.group_name
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "שנה שם קבוצה",
                color = OnSurface
            )
        },
        text = {
            OutlinedTextField(
                value = newGroupName,
                onValueChange = { newGroupName = it },
                label = { Text("שם הקבוצה החדש", color = OnSurfaceVariant) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Gray500,
                    focusedLabelColor = Primary,
                    unfocusedLabelColor = OnSurfaceVariant,
                    cursorColor = Primary
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(group, newGroupName)
                },
                enabled = newGroupName.isNotBlank() && newGroupName != group.group_name
            ) {
                Text("שמור", color = if (newGroupName.isNotBlank() && newGroupName != group.group_name) Primary else OnSurfaceVariant)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
            }
        },
        containerColor = Surface,
        tonalElevation = 0.dp
    )
}

/**
 * Group delete confirmation dialog
 */
@Composable
fun DeleteGroupConfirmationDialog(
    group: ChatGroup,
    onDismiss: () -> Unit,
    onConfirm: (ChatGroup) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "מחיקת קבוצה",
                color = OnSurface
            )
        },
        text = {
            Text(
                "האם אתה בטוח שברצונך למחוק את הקבוצה \"${group.group_name}\"? כל השיחות בקבוצה זו יפוזרו ויתבטלו מהקבוצה.",
                color = OnSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(group)
                }
            ) {
                Text("מחק", color = Color.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
            }
        },
        containerColor = Surface,
        tonalElevation = 0.dp
    )
}
