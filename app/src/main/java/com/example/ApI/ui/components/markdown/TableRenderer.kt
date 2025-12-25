package com.example.ApI.ui.components.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ApI.data.model.TextDirectionMode
import com.example.ApI.ui.screen.TextDirectionUtils
import org.commonmark.ext.gfm.tables.*

@Composable
internal fun RenderTable(
    table: TableBlock,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        var row = table.firstChild
        while (row != null) {
            if (row is TableHead || row is TableBody) {
                var tableRow = row.firstChild
                while (tableRow != null) {
                    if (tableRow is TableRow) {
                        RenderTableRow(tableRow, row is TableHead, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
                    }
                    tableRow = tableRow.next
                }
            }
            row = row.next
        }
    }
}

@Composable
internal fun RenderTableRow(
    row: TableRow,
    isHeader: Boolean,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {}
) {
    val uriHandler = LocalUriHandler.current

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isHeader) style.color.copy(alpha = 0.1f)
                    else Color.Transparent
                )
        ) {
            var cell = row.firstChild
            while (cell != null) {
                if (cell is TableCell) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(8.dp)
                    ) {
                        val cellStyle = if (isHeader) {
                            style.copy(fontWeight = FontWeight.Bold)
                        } else {
                            style
                        }

                        val inlineLatexContent = remember { mutableMapOf<String, String>() }
                        val cellText = buildAnnotatedString {
                            appendInlineContent(cell, cellStyle, enableInlineLatex, inlineLatexContent)
                        }

                        // Determine cell-specific direction based on mode
                        val cellDirection = when (textDirectionMode) {
                            TextDirectionMode.AUTO -> TextDirectionUtils.inferTextDirection(cellText.text)
                            TextDirectionMode.RTL -> LayoutDirection.Rtl
                            TextDirectionMode.LTR -> LayoutDirection.Ltr
                        }

                        RenderTextWithInlineLatex(
                            text = cellText,
                            style = cellStyle,
                            layoutDirection = cellDirection,
                            inlineLatexContent = inlineLatexContent,
                            modifier = Modifier,
                            uriHandler = uriHandler,
                            onLongPress = onLongPress
                        )
                    }
                }
                cell = cell.next
            }
        }

        // Border line after each row
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            color = style.color.copy(alpha = 0.2f),
            thickness = 1.dp
        )
    }
}
