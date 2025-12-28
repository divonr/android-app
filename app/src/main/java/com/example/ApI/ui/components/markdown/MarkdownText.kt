package com.example.ApI.ui.components.markdown

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import com.example.ApI.data.model.TextDirectionMode
import com.example.ApI.ui.utils.TextDirectionUtils
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.Node
import org.commonmark.node.Paragraph as MdParagraph
import org.commonmark.node.Heading
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.BulletList
import org.commonmark.node.OrderedList
import org.commonmark.node.ListItem
import org.commonmark.node.HardLineBreak
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.ThematicBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Emphasis
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Link
import org.commonmark.node.Text as MdText
import org.commonmark.parser.Parser

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = style.color,
    textDirection: LayoutDirection? = null,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    onLongPress: () -> Unit = {}
) {
    val parser = remember {
        Parser.builder()
            .extensions(listOf(
                TablesExtension.create(),
                StrikethroughExtension.create()
            ))
            .build()
    }
    
    // Parse LaTeX segments to check if we need special rendering
    val segments = remember(markdown) {
        LatexRenderer.parseLatexSegments(markdown)
    }
    
    val layoutDirection = textDirection ?: LocalLayoutDirection.current
    
    Column(modifier = modifier) {
        // Check if we have display LaTeX (block-level)
        val hasDisplayLatex = segments.any { it is TextSegment.DisplayLatex }
        
        if (hasDisplayLatex) {
            // Render with LaTeX support at block level
            segments.forEach { segment ->
                when (segment) {
                    is TextSegment.Regular -> {
                        // Parse and render regular markdown (may contain inline LaTeX)
                        if (segment.text.isNotBlank()) {
                            val doc = parser.parse(segment.text)
                            RenderNode(
                                node = doc,
                                style = style.copy(color = color),
                                layoutDirection = layoutDirection,
                                textDirectionMode = textDirectionMode,
                                enableInlineLatex = true,
                                onLongPress = onLongPress
                            )
                        }
                    }
                    is TextSegment.InlineLatex -> {
                        // Standalone inline LaTeX (shouldn't normally happen with display mode)
                        InlineLatexText(
                            latex = segment.latex,
                            style = style.copy(color = color)
                        )
                    }
                    is TextSegment.DisplayLatex -> {
                        DisplayLatexText(
                            latex = segment.latex,
                            style = style.copy(color = color)
                        )
                    }
                }
            }
        } else {
            // No display LaTeX, use regular markdown rendering with inline LaTeX support
            val document = parser.parse(markdown)
        RenderNode(
            node = document,
            style = style.copy(color = color),
                layoutDirection = layoutDirection,
                textDirectionMode = textDirectionMode,
                enableInlineLatex = true,
                onLongPress = onLongPress
        )
        }
    }
}

@Composable
private fun RenderNode(
    node: Node,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {}
) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is MdParagraph -> RenderParagraph(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
            is Heading -> RenderHeading(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
            is BlockQuote -> RenderBlockQuote(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress) { node, s, ld, tdm, eil, olp ->
                RenderNode(node, s, ld, tdm, eil, olp)
            }
            is Code -> RenderInlineCode(child, style)
            is FencedCodeBlock -> RenderCodeBlock(child, style, layoutDirection, onLongPress)
            is IndentedCodeBlock -> RenderCodeBlock(child, style, layoutDirection, onLongPress)
            is BulletList -> RenderBulletList(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress) { node, s, ld, tdm, eil, olp ->
                RenderNode(node, s, ld, tdm, eil, olp)
            }
            is OrderedList -> RenderOrderedList(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress) { node, s, ld, tdm, eil, olp ->
                RenderNode(node, s, ld, tdm, eil, olp)
            }
            is ListItem -> RenderListItem(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress) { node, s, ld, tdm, eil, olp ->
                RenderNode(node, s, ld, tdm, eil, olp)
            }
            is TableBlock -> RenderTable(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
            is HardLineBreak -> Spacer(modifier = Modifier.height(4.dp))
            is ThematicBreak -> {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    color = style.color.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            is HtmlBlock, is HtmlInline -> {
                // Skip HTML for now
            }
            else -> RenderNode(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
        }
        child = child.next
    }
}

@Composable
private fun RenderParagraph(
    node: MdParagraph,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {}
) {
    val uriHandler = LocalUriHandler.current
    val inlineLatexContent = remember { mutableMapOf<String, String>() }
    val annotatedString = buildAnnotatedString {
        appendInlineContent(node, style, enableInlineLatex, inlineLatexContent)
    }
    
    if (annotatedString.text.isNotEmpty()) {
        val rawText = annotatedString.text.trimStart()
        val isQuote = rawText.startsWith(">")
        
        // Determine paragraph-specific direction based on mode
        val paragraphDirection = when (textDirectionMode) {
            TextDirectionMode.AUTO -> TextDirectionUtils.inferTextDirection(rawText)
            TextDirectionMode.RTL -> LayoutDirection.Rtl
            TextDirectionMode.LTR -> LayoutDirection.Ltr
        }
        
        if (isQuote) {
            // Remove the ">" prefix and any following space
            val textWithoutPrefix = rawText.removePrefix(">").trimStart()
            
            // Build a new annotated string without the ">" prefix
            val displayAnnotated = buildAnnotatedString {
                append(textWithoutPrefix)
                
                // Calculate the offset (how many chars we removed)
                val offset = rawText.length - textWithoutPrefix.length
                
                // Re-apply URL annotations with adjusted positions
                annotatedString.getStringAnnotations(0, annotatedString.length).forEach { ann ->
                    val newStart = ann.start - offset
                    val newEnd = ann.end - offset
                    if (newEnd > 0 && newStart < textWithoutPrefix.length) {
                        addStringAnnotation(
                            tag = ann.tag,
                            annotation = ann.item,
                            start = maxOf(0, newStart),
                            end = minOf(textWithoutPrefix.length, newEnd)
                        )
                    }
                }
                
                // Re-apply span styles (for link colors, etc.)
                annotatedString.spanStyles.forEach { spanStyle ->
                    val newStart = spanStyle.start - offset
                    val newEnd = spanStyle.end - offset
                    if (newEnd > 0 && newStart < textWithoutPrefix.length) {
                        addStyle(
                            spanStyle.item,
                            maxOf(0, newStart),
                            minOf(textWithoutPrefix.length, newEnd)
                        )
                    }
                }
            }
            
            // Render as quote with vertical line
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                // Vertical gray line
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(Color.Gray)
                )
                Spacer(modifier = Modifier.width(12.dp))
                CompositionLocalProvider(LocalLayoutDirection provides paragraphDirection) {
                    BasicText(
                        text = displayAnnotated,
                        style = style.copy(fontStyle = FontStyle.Italic),
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = 8.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        // Find which character was tapped
                                        // Note: This is a simplified approach - proper implementation would need LayoutResult
                                        displayAnnotated.getStringAnnotations(tag = "URL", start = 0, end = displayAnnotated.length)
                                            .firstOrNull()?.let { annotation ->
                                                try {
                                                    uriHandler.openUri(annotation.item)
                                                } catch (e: Exception) {
                                                    // Handle invalid URL
                                                }
                                            }
                                    },
                                    onLongPress = { onLongPress() }
                                )
                            }
                    )
                }
            }
        } else {
            // Regular paragraph
            CompositionLocalProvider(LocalLayoutDirection provides paragraphDirection) {
                RenderTextWithInlineLatex(
                    text = annotatedString,
                    style = style,
                    layoutDirection = paragraphDirection,
                    inlineLatexContent = inlineLatexContent,
                    modifier = Modifier.padding(bottom = 8.dp),
                    uriHandler = uriHandler,
                    onLongPress = onLongPress
                )
            }
        }
    }
}

@Composable
internal fun RenderTextWithInlineLatex(
    text: AnnotatedString,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    inlineLatexContent: Map<String, String>,
    modifier: Modifier = Modifier,
    uriHandler: UriHandler,
    onLongPress: () -> Unit = {}
) {
    // Convert LaTeX placeholders to actual LaTeX content using the unified parsing engine
    val processedText = buildAnnotatedString {
        var currentIndex = 0
        val textString = text.text
        val regex = """\[LATEX:(latex_\d+)\]""".toRegex()
        
        regex.findAll(textString).forEach { match ->
            // Append text before the match
            if (match.range.first > currentIndex) {
                append(textString.substring(currentIndex, match.range.first))
            }
            
            // Extract the ID and get the corresponding LaTeX content
            val id = match.groupValues[1]
            val latexContent = inlineLatexContent[id] ?: ""
            
            if (latexContent.isNotEmpty()) {
                // Append inline LaTeX using annotated spans (handles letters in super/sub scripts)
                val startBefore = length
                appendInlineLatexAnnotated(latexContent, style)
                // Keep LaTeX styled with italic serif overall
                addStyle(
                    SpanStyle(
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Serif
                    ),
                    startBefore,
                    length
                )
            }
            
            currentIndex = match.range.last + 1
        }
        
        // Append any remaining text after the last match
        if (currentIndex < textString.length) {
            append(textString.substring(currentIndex))
        }
        
        // Copy URL annotations from the original text
        text.getStringAnnotations(0, text.length).forEach { annotation ->
            if (annotation.tag == "URL") {
                addStringAnnotation(
                    tag = annotation.tag,
                    annotation = annotation.item,
                    start = annotation.start,
                    end = annotation.end
                )
            }
        }

        // Copy span styles from the original text (for link colors, underlines, etc.)
        text.spanStyles.forEach { spanStyle ->
            addStyle(
                spanStyle.item,
                spanStyle.start,
                spanStyle.end
            )
        }
    }
    
    BasicText(
        text = processedText,
        style = style,
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { offset ->
                    // Simplified: open first URL found (proper implementation would need LayoutResult to find exact position)
                    processedText.getStringAnnotations(tag = "URL", start = 0, end = processedText.length)
                        .firstOrNull()?.let { annotation ->
                            try {
                                uriHandler.openUri(annotation.item)
                            } catch (e: Exception) {
                                // Handle invalid URL
                            }
                        }
                },
                onLongPress = { onLongPress() }
            )
        }
    )
}

