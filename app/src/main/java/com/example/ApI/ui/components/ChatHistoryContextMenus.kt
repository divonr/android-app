package com.example.ApI.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.example.ApI.R
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupContextMenu(
    group: ChatGroup,
    position: DpOffset,
    onDismiss: () -> Unit,
    onRename: (ChatGroup) -> Unit,
    onMakeProject: (ChatGroup) -> Unit,
    onNewConversation: (ChatGroup) -> Unit,
    onDelete: (ChatGroup) -> Unit
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = position,
        properties = PopupProperties(focusable = true),
        modifier = Modifier
            .background(
                Surface,
                RoundedCornerShape(16.dp)
            )
            .width(220.dp)
    ) {
        // Rename group
        DropdownMenuItem(
            text = {
                Text(
                    "שנה שם קבוצה",
                    color = OnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onRename(group)
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        // Make project
        DropdownMenuItem(
            text = {
                Text(
                    "הפוך לפרויקט",
                    color = OnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onMakeProject(group)
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        HorizontalDivider(
            color = OnSurface.copy(alpha = 0.1f),
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        // New conversation
        DropdownMenuItem(
            text = {
                Text(
                    "שיחה חדשה",
                    color = OnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onNewConversation(group)
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = OnSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        HorizontalDivider(
            color = OnSurface.copy(alpha = 0.1f),
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        // Delete group
        DropdownMenuItem(
            text = {
                Text(
                    "מחק קבוצה ופזר שיחות",
                    color = AccentRed,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onDelete(group)
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
    }
}

@Composable
fun ChatContextMenu(
    chat: Chat,
    position: DpOffset,
    onDismiss: () -> Unit,
    onRename: (Chat) -> Unit,
    onAIRename: (Chat) -> Unit,
    onDelete: (Chat) -> Unit,
    groups: List<ChatGroup>,
    onAddToGroup: (String) -> Unit,
    onCreateNewGroup: (Chat) -> Unit,
    onRemoveFromGroup: () -> Unit
) {
    var showGroupSubmenu by remember { mutableStateOf(false) }

    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = position,
        properties = PopupProperties(focusable = true),
        modifier = Modifier
            .background(
                Surface,
                RoundedCornerShape(16.dp)
            )
            .width(200.dp)
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.update_chat_name),
                    color = OnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onRename(chat)
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.update_chat_name_ai),
                    color = OnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onAIRename(chat)
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        HorizontalDivider(
            color = OnSurface.copy(alpha = 0.1f),
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        // Group management section
        if (chat.group != null) {
            // Remove from group option
            DropdownMenuItem(
                text = {
                    Text(
                        "הסר מקבוצה",
                        color = OnSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    onRemoveFromGroup()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = null,
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        } else {
            // Add to group option with submenu
            val addToGroupItemHeight = 40.dp
            DropdownMenuItem(
                text = {
                    Text(
                        "הוסף לקבוצה",
                        color = OnSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    showGroupSubmenu = true
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = OnSurface,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = null,
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }

        HorizontalDivider(
            color = OnSurface.copy(alpha = 0.1f),
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.delete_chat),
                    color = AccentRed,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                onDelete(chat)
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
    }

    // Group submenu
    if (showGroupSubmenu) {
        // Align submenu to the left of the main menu and vertically align with the "הוסף לקבוצה" row
        val submenuX = position.x - 200.dp
        val submenuY = position.y + 0.dp
        DropdownMenu(
            expanded = true,
            onDismissRequest = { showGroupSubmenu = false },
            offset = DpOffset(submenuX, submenuY),
            properties = PopupProperties(focusable = true),
            modifier = Modifier
                .background(
                    Surface,
                    RoundedCornerShape(16.dp)
                )
                .width(200.dp)
        ) {
            // Existing groups
            groups.forEach { group ->
                DropdownMenuItem(
                    text = {
                        Text(
                            group.group_name,
                            color = OnSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onAddToGroup(group.group_id)
                        showGroupSubmenu = false
                        onDismiss()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = Secondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            // Create new group option
            HorizontalDivider(
                color = OnSurface.copy(alpha = 0.1f),
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            DropdownMenuItem(
                text = {
                    Text(
                        "צור קבוצה חדשה",
                        color = Primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    onCreateNewGroup(chat)
                    showGroupSubmenu = false
                    onDismiss()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}
