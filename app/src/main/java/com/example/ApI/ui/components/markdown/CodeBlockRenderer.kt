package com.example.ApI.ui.components.markdown

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node

@Composable
internal fun RenderInlineCode(
    node: Code,
    style: TextStyle
) {
    Text(
        text = node.literal,
        style = style.copy(
            fontFamily = FontFamily.Monospace,
            background = style.color.copy(alpha = 0.1f)
        ),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RenderCodeBlock(
    node: Node,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    onLongPress: () -> Unit = {}
) {
    val code = when (node) {
        is FencedCodeBlock -> node.literal
        is IndentedCodeBlock -> node.literal
        else -> ""
    }

    val language = when (node) {
        is FencedCodeBlock -> node.info
        else -> null
    }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Force LTR for code blocks
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            // Language label if present
            if (!language.isNullOrBlank()) {
                Text(
                    text = language,
                    style = style.copy(
                        fontSize = 11.sp,
                        color = Color.Gray
                    ),
                    modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                )
            }

            // Code block with black background and click-to-copy
            // Make the entire Box scrollable and clickable with long press support
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()) // Scroll the entire box
                    .background(
                        color = Color.Black,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .combinedClickable(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(code))
                            Toast.makeText(
                                context,
                                "קטע הקוד הועתק ללוח",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onLongClick = onLongPress
                    )
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    style = style.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color(0xFFE0E0E0) // Light gray text on black
                    )
                    // Removed horizontalScroll from Text - it's now on the Box
                )
            }
        }
    }
}
