package com.example.ApI.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.example.ApI.ui.theme.*

@Composable
fun ContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    offset: DpOffset,
    items: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        properties = PopupProperties(focusable = true),
        modifier = modifier
            .background(
                Surface,
                RoundedCornerShape(12.dp)
            )
    ) {
        items.forEach { (text, action) ->
            DropdownMenuItem(
                text = { 
                    Text(
                        text = text,
                        color = OnSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    action()
                    onDismiss()
                }
            )
        }
    }
}
