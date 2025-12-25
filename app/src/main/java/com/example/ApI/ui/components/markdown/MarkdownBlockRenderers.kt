package com.example.ApI.ui.components.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.data.model.TextDirectionMode
import com.example.ApI.ui.screen.TextDirectionUtils
import org.commonmark.node.BlockQuote
import org.commonmark.node.Heading
import org.commonmark.node.Node

/**
 * Renders a markdown heading (h1-h6) with appropriate text sizing and weight.
 */
@Composable
internal fun RenderHeading(
    node: Heading,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {}
) {
    val headingStyle = when (node.level) {
        1 -> style.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold)
        2 -> style.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)
        3 -> style.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)
        4 -> style.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)
        5 -> style.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
        else -> style.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }

    val uriHandler = LocalUriHandler.current
    val inlineLatexContent = remember { mutableMapOf<String, String>() }
    val annotatedString = buildAnnotatedString {
        appendInlineContent(node, headingStyle, enableInlineLatex, inlineLatexContent)
    }

    // Determine heading-specific direction based on mode
    val headingDirection = when (textDirectionMode) {
        TextDirectionMode.AUTO -> TextDirectionUtils.inferTextDirection(annotatedString.text)
        TextDirectionMode.RTL -> LayoutDirection.Rtl
        TextDirectionMode.LTR -> LayoutDirection.Ltr
    }

    CompositionLocalProvider(LocalLayoutDirection provides headingDirection) {
        RenderTextWithInlineLatex(
            text = annotatedString,
            style = headingStyle,
            layoutDirection = headingDirection,
            inlineLatexContent = inlineLatexContent,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            uriHandler = uriHandler,
            onLongPress = onLongPress
        )
    }
}

/**
 * Renders a markdown block quote with a vertical line and italic text.
 *
 * @param renderNode Lambda to recursively render child nodes. Required to avoid circular dependencies.
 */
@Composable
internal fun RenderBlockQuote(
    node: BlockQuote,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {},
    renderNode: @Composable (Node, TextStyle, LayoutDirection, TextDirectionMode, Boolean, () -> Unit) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Vertical line on the side
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(style.color.copy(alpha = 0.5f))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            renderNode(node, style.copy(fontStyle = FontStyle.Italic), layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
        }
    }
}
