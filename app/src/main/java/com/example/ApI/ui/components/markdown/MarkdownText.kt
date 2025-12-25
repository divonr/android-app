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
import com.example.ApI.ui.screen.TextDirectionUtils
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
            is BlockQuote -> RenderBlockQuote(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
            is Code -> RenderInlineCode(child, style)
            is FencedCodeBlock -> RenderCodeBlock(child, style, layoutDirection, onLongPress)
            is IndentedCodeBlock -> RenderCodeBlock(child, style, layoutDirection, onLongPress)
            is BulletList -> RenderBulletList(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
            is OrderedList -> RenderOrderedList(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
            is ListItem -> RenderListItem(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
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
private fun RenderTextWithInlineLatex(
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

/**
 * Converts LaTeX to a text representation using the unified parsing engine
 */
private fun convertLatexToTextRepresentation(latex: String): String {
    val elements = LatexRenderer.parseLatexStructure(latex)
    return elements.joinToString("") { element ->
        convertElementToTextRepresentation(element)
    }
}

/**
 * Recursively converts a LaTeX element to text representation with proper Unicode symbols
 */
private fun convertElementToTextRepresentation(element: LatexRenderer.LatexElement): String {
    return when (element) {
        is LatexRenderer.LatexElement.Text -> {
            // Convert symbols like \alpha, \beta, etc. to Unicode
            LatexRenderer.convertLatexToUnicode(element.text)
        }
        is LatexRenderer.LatexElement.Superscript -> {
            val content = LatexRenderer.parseLatexStructure(element.text)
            val contentText = content.joinToString("") { convertElementToTextRepresentation(it) }
            // Convert common superscripts to Unicode
            convertSuperscriptToUnicode(contentText)
        }
        is LatexRenderer.LatexElement.Subscript -> {
            val content = LatexRenderer.parseLatexStructure(element.text)
            val contentText = content.joinToString("") { convertElementToTextRepresentation(it) }
            // Convert common subscripts to Unicode
            convertSubscriptToUnicode(contentText)
        }
        is LatexRenderer.LatexElement.Fraction -> {
            val numContent = LatexRenderer.parseLatexStructure(element.numerator)
            val denContent = LatexRenderer.parseLatexStructure(element.denominator)
            val numerator = numContent.joinToString("") { convertElementToTextRepresentation(it) }
            val denominator = denContent.joinToString("") { convertElementToTextRepresentation(it) }
            "($numerator)/($denominator)"
        }
        is LatexRenderer.LatexElement.SquareRoot -> {
            val content = LatexRenderer.parseLatexStructure(element.content)
            val contentText = content.joinToString("") { convertElementToTextRepresentation(it) }
            "√($contentText)"
        }
    }
}

/**
 * Append inline LaTeX as annotated spans so superscripts/subscripts for letters render correctly.
 */
private fun AnnotatedString.Builder.appendInlineLatexAnnotated(
    latex: String,
    baseStyle: TextStyle
) {
    val elements = LatexRenderer.parseLatexStructure(latex)

    elements.forEach { element ->
        when (element) {
            is LatexRenderer.LatexElement.Text -> {
                val text = LatexRenderer.convertLatexToUnicode(element.text)
                append(text)
            }
            is LatexRenderer.LatexElement.Superscript -> {
                val content = LatexRenderer.parseLatexStructure(element.text)
                val rendered = content.joinToString("") { sub -> convertElementToTextRepresentation(sub) }
                val start = length
                append(rendered)
                addStyle(
                    SpanStyle(
                        fontSize = baseStyle.fontSize * 0.7f,
                        baselineShift = BaselineShift(0.5f)
                    ),
                    start,
                    length
                )
            }
            is LatexRenderer.LatexElement.Subscript -> {
                val content = LatexRenderer.parseLatexStructure(element.text)
                val rendered = content.joinToString("") { sub -> convertElementToTextRepresentation(sub) }
                val start = length
                append(rendered)
                addStyle(
                    SpanStyle(
                        fontSize = baseStyle.fontSize * 0.7f,
                        baselineShift = BaselineShift(-0.3f)
                    ),
                    start,
                    length
                )
            }
            is LatexRenderer.LatexElement.Fraction -> {
                // Fallback textual inline rendering for fractions
                val num = convertLatexToTextRepresentation(element.numerator)
                val den = convertLatexToTextRepresentation(element.denominator)
                append("(")
                append(num)
                append(")/(")
                append(den)
                append(")")
            }
            is LatexRenderer.LatexElement.SquareRoot -> {
                val inner = convertLatexToTextRepresentation(element.content)
                append("√(")
                append(inner)
                append(")")
            }
        }
    }
}

/**
 * Convert common superscripts to Unicode
 */
private fun convertSuperscriptToUnicode(text: String): String {
    return text.map { char ->
        when (char) {
            '0' -> '⁰'
            '1' -> '¹'
            '2' -> '²'
            '3' -> '³'
            '4' -> '⁴'
            '5' -> '⁵'
            '6' -> '⁶'
            '7' -> '⁷'
            '8' -> '⁸'
            '9' -> '⁹'
            '+' -> '⁺'
            '-' -> '⁻'
            '=' -> '⁼'
            '(' -> '⁽'
            ')' -> '⁾'
            'n' -> 'ⁿ'
            'i' -> 'ⁱ'
            else -> char
        }
    }.joinToString("")
}

/**
 * Convert common subscripts to Unicode
 */
private fun convertSubscriptToUnicode(text: String): String {
    return text.map { char ->
        when (char) {
            '0' -> '₀'
            '1' -> '₁'
            '2' -> '₂'
            '3' -> '₃'
            '4' -> '₄'
            '5' -> '₅'
            '6' -> '₆'
            '7' -> '₇'
            '8' -> '₈'
            '9' -> '₉'
            '+' -> '₊'
            '-' -> '₋'
            '=' -> '₌'
            '(' -> '₍'
            ')' -> '₎'
            'a' -> 'ₐ'
            'e' -> 'ₑ'
            'h' -> 'ₕ'
            'i' -> 'ᵢ'
            'j' -> 'ⱼ'
            'k' -> 'ₖ'
            'l' -> 'ₗ'
            'm' -> 'ₘ'
            'n' -> 'ₙ'
            'o' -> 'ₒ'
            'p' -> 'ₚ'
            'r' -> 'ᵣ'
            's' -> 'ₛ'
            't' -> 'ₜ'
            'u' -> 'ᵤ'
            'v' -> 'ᵥ'
            'x' -> 'ₓ'
            else -> char
        }
    }.joinToString("")
}

@Composable
private fun RenderHeading(
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

@Composable
private fun RenderBlockQuote(
    node: BlockQuote,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {}
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
            RenderNode(node, style.copy(fontStyle = FontStyle.Italic), layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
        }
    }
}

@Composable
private fun RenderInlineCode(
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RenderCodeBlock(
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

@Composable
private fun RenderBulletList(
    node: BulletList,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {}
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
                        RenderNode(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
                    }
                }
            }
        }
        child = child.next
    }
}

@Composable
private fun RenderOrderedList(
    node: OrderedList,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {}
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
                        RenderNode(child, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
                    }
                }
            }
            index++
        }
        child = child.next
    }
}

@Composable
private fun RenderListItem(
    node: ListItem,
    style: TextStyle,
    layoutDirection: LayoutDirection,
    textDirectionMode: TextDirectionMode = TextDirectionMode.AUTO,
    enableInlineLatex: Boolean = false,
    onLongPress: () -> Unit = {}
) {
    RenderNode(node, style, layoutDirection, textDirectionMode, enableInlineLatex, onLongPress)
}

@Composable
private fun RenderTable(
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
private fun RenderTableRow(
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

private fun AnnotatedString.Builder.appendInlineContent(
    node: Node,
    style: TextStyle,
    enableInlineLatex: Boolean = false,
    latexContentMap: MutableMap<String, String> = mutableMapOf()
) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is MdText -> {
                val text = child.literal
                
                // Process inline LaTeX if enabled
                if (enableInlineLatex && text.contains("$")) {
                    processTextAndPopulateLatexMap(text, latexContentMap)
                } else {
                // Auto-link URLs in plain text
                val urlPattern = Regex("https?://[^\\s]+")
                val matches = urlPattern.findAll(text)
                
                if (matches.any()) {
                    var lastIndex = 0
                    matches.forEach { match ->
                        // Append text before URL
                        if (match.range.first > lastIndex) {
                            append(text.substring(lastIndex, match.range.first))
                        }
                        // Append URL with annotation
                        val url = match.value
                        val start = length
                        append(url)
                        addStyle(
                            SpanStyle(
                                color = Color(0xFF2196F3),
                                textDecoration = TextDecoration.Underline
                            ),
                            start,
                            length
                        )
                        addStringAnnotation(
                            tag = "URL",
                            annotation = url,
                            start = start,
                            end = length
                        )
                        lastIndex = match.range.last + 1
                    }
                    // Append remaining text
                    if (lastIndex < text.length) {
                        append(text.substring(lastIndex))
                    }
                } else {
                    append(text)
                    }
                }
            }
            is Emphasis -> {
                val start = length
                appendInlineContent(child, style, enableInlineLatex, latexContentMap)
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
            }
            is StrongEmphasis -> {
                val start = length
                appendInlineContent(child, style, enableInlineLatex, latexContentMap)
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
            }
            is Code -> {
                val start = length
                append(child.literal)
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = style.color.copy(alpha = 0.1f)
                    ),
                    start,
                    length
                )
            }
            is Link -> {
                val url = child.destination
                val start = length
                appendInlineContent(child, style, enableInlineLatex, latexContentMap)
                addStyle(
                    SpanStyle(
                        color = Color(0xFF2196F3),
                        textDecoration = TextDecoration.Underline
                    ),
                    start,
                    length
                )
                addStringAnnotation(
                    tag = "URL",
                    annotation = url,
                    start = start,
                    end = length
                )
            }
            is Strikethrough -> {
                val start = length
                appendInlineContent(child, style, enableInlineLatex, latexContentMap)
                addStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                    start,
                    length
                )
            }
            is SoftLineBreak -> append("\n")
            is HardLineBreak -> append("\n")
            else -> appendInlineContent(child, style, enableInlineLatex, latexContentMap)
        }
        child = child.next
    }
}

/**
 * Helper function to process text containing inline LaTeX expressions
 * and populate the LaTeX content map for inline rendering
 */
private fun AnnotatedString.Builder.processTextAndPopulateLatexMap(
    text: String,
    latexContentMap: MutableMap<String, String>
) {
    var currentIndex = 0
    
    while (currentIndex < text.length) {
        // Look for inline LaTeX ($...$)
        val dollarIndex = text.indexOf("$", currentIndex)
        
        if (dollarIndex == -1) {
            // No more LaTeX, append remaining text
            append(text.substring(currentIndex))
            break
        }
        
        // Check if it's escaped or part of $$
        if (dollarIndex > 0 && text[dollarIndex - 1] == '\\') {
            // Escaped dollar sign, append text including it
            append(text.substring(currentIndex, dollarIndex))
            currentIndex = dollarIndex + 1
            continue
        }
        
        // Check for $$ display delimiters (skip them in inline processing)
        if (dollarIndex + 1 < text.length && text[dollarIndex + 1] == '$') {
            append(text.substring(currentIndex, dollarIndex + 2))
            currentIndex = dollarIndex + 2
            continue
        }
        
        // Find closing $
        val closingIndex = text.indexOf("$", dollarIndex + 1)
        
        if (closingIndex == -1) {
            // No closing $, treat as regular text
            append(text.substring(currentIndex))
            break
        }
        
        // Extract LaTeX content
        val latexContent = text.substring(dollarIndex + 1, closingIndex)
        
        // Check if it's valid inline LaTeX (not empty, no newlines)
        if (latexContent.isBlank() || latexContent.contains("\n")) {
            // Not valid LaTeX, append the dollar sign and continue
            append(text.substring(currentIndex, dollarIndex + 1))
            currentIndex = dollarIndex + 1
            continue
        }
        
        // Append text before LaTeX
        if (dollarIndex > currentIndex) {
            append(text.substring(currentIndex, dollarIndex))
        }
        
        // Create a unique ID for this LaTeX expression
        val id = "latex_${latexContentMap.size}"
        latexContentMap[id] = latexContent
        
        // Append a text marker that we can replace later
        append("[LATEX:$id]")
        
        currentIndex = closingIndex + 1
    }
}
