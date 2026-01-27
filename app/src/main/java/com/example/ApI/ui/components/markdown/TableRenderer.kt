package com.example.ApI.ui.components.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ApI.data.model.TextDirectionMode
import com.example.ApI.ui.utils.TextDirectionUtils
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
    // 1. Parse table into a grid
    val rows = remember(table) {
        val extractedRows = mutableListOf<List<TableCell>>()
        val headerFlags = mutableListOf<Boolean>()

        var section = table.firstChild
        while (section != null) {
            if (section is TableHead || section is TableBody) {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        val cells = mutableListOf<TableCell>()
                        var cell = row.firstChild
                        while (cell != null) {
                            if (cell is TableCell) {
                                cells.add(cell)
                            }
                            cell = cell.next
                        }
                        extractedRows.add(cells)
                        headerFlags.add(section is TableHead)
                    }
                    row = row.next
                }
            }
            section = section.next
        }
        Pair(extractedRows, headerFlags)
    }

    val tableRows = rows.first
    val isHeaderRow = rows.second
    
    if (tableRows.isEmpty()) return

    val maxCols = tableRows.maxOfOrNull { it.size } ?: 0
    val uriHandler = LocalUriHandler.current

    val scrollState = rememberScrollState()

    // 2. Render container
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            // Use a defined background or border if desired, but user just wants row colors
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
        ) {
            // 3. Render by Column to ensure correct width alignment
            for (colIndex in 0 until maxCols) {
                Column(
                    modifier = Modifier.width(IntrinsicSize.Max)
                ) {
                    for (rowIndex in tableRows.indices) {
                        val rowCells = tableRows[rowIndex]
                        val cell = rowCells.getOrNull(colIndex)
                        val isHeader = isHeaderRow.getOrElse(rowIndex) { false }

                        // Styling
                        val backgroundColor = when {
                            isHeader -> style.color.copy(alpha = 0.15f) // Slightly darker for header
                            rowIndex % 2 != 0 -> style.color.copy(alpha = 0.05f) // Alternating grey
                            else -> Color.Transparent
                        }

                        // Border for header
                        val bottomBorderModifier = if (isHeader) {
                             Modifier.border(
                                 width = 1.dp,
                                 color = style.color.copy(alpha = 0.2f),
                                 shape = RectangleShape // Bottom border via box modifier is tricky, use simple delimiter
                             )
                             // Actually, simple border surrounds the box. We can just use the background.
                             Modifier
                        } else {
                            Modifier
                        }

                        // Render Cell
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(backgroundColor)
                                .then(bottomBorderModifier)
                                .padding(8.dp)
                        ) {
                             if (cell != null) {
                                 val cellStyle = if (isHeader) {
                                     style.copy(fontWeight = FontWeight.Bold)
                                 } else {
                                     style
                                 }

                                 val inlineLatexContent = remember(cell) { mutableMapOf<String, String>() }
                                 val cellText = buildAnnotatedString {
                                     appendInlineContent(cell, cellStyle, enableInlineLatex, inlineLatexContent)
                                 }

                                 val cellDirection = when (textDirectionMode) {
                                     TextDirectionMode.AUTO -> TextDirectionUtils.inferTextDirection(cellText.text)
                                     TextDirectionMode.RTL -> LayoutDirection.Rtl
                                     TextDirectionMode.LTR -> LayoutDirection.Ltr
                                 }

                                 CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
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
                             } else {
                                 // Empty cell placeholder
                                 Spacer(modifier = Modifier.height(20.dp)) // Min height
                             }
                        }
                        
                        // Add a thin divider line at the bottom of the header row if desired, 
                        // or for all rows. 
                        // To perfectly mimic the previous table look (divider lines), we can separate logic.
                        // But alternating colors usually suffice. 
                        // Let's add a Divider if it's the specific header row for better separation.
                        if (isHeader) {
                            HorizontalDivider(color = style.color.copy(alpha = 0.3f), thickness = 1.dp)
                        } else {
                             // Optional: lighter divider for other rows
                             // HorizontalDivider(color = style.color.copy(alpha = 0.1f), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

