package com.example.ApI.ui.components.markdown

import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ApI.data.model.TextDirectionMode
import com.example.ApI.ui.utils.TextDirectionUtils
import org.commonmark.ext.gfm.tables.*
import kotlin.math.max
import kotlin.math.roundToInt

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
    val (tableRows, isHeaderRow) = remember(table) {
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

    if (tableRows.isEmpty()) return

    val maxCols = tableRows.maxOfOrNull { it.size } ?: 0
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()

    // 2. Render container with horizontal scroll, applying the requested layout direction
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Box(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .horizontalScroll(scrollState)
        ) {
            FixedGridTable(
                rows = tableRows,
                columns = maxCols,
                isHeaderRow = isHeaderRow,
                style = style,
                layoutDirection = layoutDirection,
                textDirectionMode = textDirectionMode,
                enableInlineLatex = enableInlineLatex,
                onLongPress = onLongPress,
                uriHandler = uriHandler
            )
        }
    }
}

@Composable
private fun FixedGridTable(
    rows: List<List<TableCell>>,
    columns: Int,
    isHeaderRow: List<Boolean>,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode,
    enableInlineLatex: Boolean,
    onLongPress: () -> Unit,
    uriHandler: androidx.compose.ui.platform.UriHandler
) {
    Layout(
        content = {
            // 1. Emit Row Backgrounds
            rows.forEachIndexed { index, _ ->
                val isHeader = isHeaderRow.getOrElse(index) { false }
                val color = when {
                    isHeader -> style.color.copy(alpha = 0.12f)
                    index % 2 != 0 -> style.color.copy(alpha = 0.04f)
                    else -> Color.Transparent
                }
                Box(
                    modifier = Modifier.background(color)
                ) {
                    if (isHeader) {
                        // Bottom border for header
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(style.color.copy(alpha = 0.2f))
                        )
                    }
                }
            }

            // 2. Emit Cells
            rows.forEachIndexed { rowIndex, rowCells ->
                val isHeader = isHeaderRow.getOrElse(rowIndex) { false }
                for (colIndex in 0 until columns) {
                    val cell = rowCells.getOrNull(colIndex)
                    if (cell != null) {
                        // Wrap cell content
                        Box(modifier = Modifier.padding(8.dp)) {
                            val cellStyle = if (isHeader) style.copy(fontWeight = FontWeight.Bold) else style
                            val inlineLatexContent = remember(cell) { mutableMapOf<String, String>() }
                            val cellText = buildAnnotatedString {
                                appendInlineContent(cell, cellStyle, enableInlineLatex, inlineLatexContent)
                            }
                            
                            val cellDirection = when (textDirectionMode) {
                                TextDirectionMode.AUTO -> TextDirectionUtils.inferTextDirection(cellText.text)
                                TextDirectionMode.RTL -> LayoutDirection.Rtl
                                TextDirectionMode.LTR -> LayoutDirection.Ltr
                            }

                            CompositionLocalProvider(LocalLayoutDirection provides cellDirection) {
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
                    } else {
                        Spacer(modifier = Modifier.padding(8.dp))
                    }
                }
            }
        },
        measurePolicy = { measurables, constraints ->
            val rowCount = rows.size
            
            // Split measurables
            val backgroundMeasurables = measurables.take(rowCount)
            val cellMeasurables = measurables.drop(rowCount)

            // 1. Measure Cells to determine grid dimensions
            // Use infinite max width to allow cells to claim what they need
            val cellPlaceables = cellMeasurables.map { 
                it.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity, minHeight = 0)) 
            }

            val colWidths = IntArray(columns) { 0 }
            val rowHeights = IntArray(rowCount) { 0 }

            for (r in 0 until rowCount) {
                for (c in 0 until columns) {
                    val index = r * columns + c
                    if (index < cellPlaceables.size) {
                        val placeable = cellPlaceables[index]
                        colWidths[c] = max(colWidths[c], placeable.width)
                        rowHeights[r] = max(rowHeights[r], placeable.height)
                    }
                }
            }

            val totalWidth = colWidths.sum()
            val totalHeight = rowHeights.sum()

            // 2. Measure Backgrounds using calculated dimensions
            val backgroundPlaceables = backgroundMeasurables.mapIndexed { index, measurable ->
                val height = rowHeights.getOrElse(index) { 0 }
                if (totalWidth > 0 && height > 0) {
                     measurable.measure(
                        Constraints.fixed(width = totalWidth, height = height)
                    )
                } else {
                    measurable.measure(Constraints.fixed(0, 0))
                }
            }

            // 3. Layout with support for RTL using placeRelative
            layout(totalWidth, totalHeight) {
                // Place Backgrounds (Z-index 0 implicitly by order)
                var yOffset = 0
                backgroundPlaceables.forEach { bg ->
                    bg.placeRelative(0, yOffset)
                    yOffset += bg.height
                }

                // Place Cells (Z-index 1)
                yOffset = 0 // Reset for cells
                for (r in 0 until rowCount) {
                    val rowHeight = rowHeights[r]
                    var xOffset = 0
                    for (c in 0 until columns) {
                        val colWidth = colWidths[c]
                        val index = r * columns + c
                        if (index < cellPlaceables.size) {
                            val placeable = cellPlaceables[index]
                            // Align content: Center vertically in the row? Top align usually for text.
                            // placeRelative handles RTL mirroring automatically (x=0 starts from right in RTL)
                            placeable.placeRelative(x = xOffset, y = yOffset)
                        }
                        xOffset += colWidth
                    }
                    yOffset += rowHeight
                }
            }
        }
    )
}



