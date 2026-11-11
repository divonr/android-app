package com.example.ApI.ui.components.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText

internal fun AnnotatedString.Builder.appendInlineContent(
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
internal fun AnnotatedString.Builder.processTextAndPopulateLatexMap(
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
