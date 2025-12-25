package com.example.ApI.ui.components.markdown

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ApI.data.model.TextDirectionMode
import org.commonmark.node.BulletList
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList

@Composable
internal fun RenderBulletList(
    node: BulletList,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {},
    renderNode: @Composable (Node, TextStyle, LayoutDirection, TextDirectionMode, Boolean, () -> Unit) -> Unit
) {
    var child = node.firstChild
    while (child != null) {
        if (child is ListItem) {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = if (layoutDirection == LayoutDirection.Ltr) 16.dp else 0.dp,
                            end = if (layoutDirection == LayoutDirection.Rtl) 16.dp else 0.dp,
                            bottom = 4.dp
                        )
                ) {
                    Text(
                        text = "• ",
                        style = style,
                        modifier = Modifier.padding(
                            end = if (layoutDirection == LayoutDirection.Ltr) 8.dp else 0.dp,
                            start = if (layoutDirection == LayoutDirection.Rtl) 8.dp else 0.dp
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        renderNode(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
                    }
                }
            }
        }
        child = child.next
    }
}

@Composable
internal fun RenderOrderedList(
    node: OrderedList,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {},
    renderNode: @Composable (Node, TextStyle, LayoutDirection, TextDirectionMode, Boolean, () -> Unit) -> Unit
) {
    var child = node.firstChild
    var index = node.startNumber
    while (child != null) {
        if (child is ListItem) {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = if (layoutDirection == LayoutDirection.Ltr) 16.dp else 0.dp,
                            end = if (layoutDirection == LayoutDirection.Rtl) 16.dp else 0.dp,
                            bottom = 4.dp
                        )
                ) {
                    Text(
                        text = "$index. ",
                        style = style,
                        modifier = Modifier.padding(
                            end = if (layoutDirection == LayoutDirection.Ltr) 8.dp else 0.dp,
                            start = if (layoutDirection == LayoutDirection.Rtl) 8.dp else 0.dp
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        renderNode(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
                    }
                }
            }
            index++
        }
        child = child.next
    }
}

@Composable
internal fun RenderListItem(
    node: ListItem,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {},
    renderNode: @Composable (Node, TextStyle, LayoutDirection, TextDirectionMode, Boolean, () -> Unit) -> Unit
) {
    renderNode(node, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
}
