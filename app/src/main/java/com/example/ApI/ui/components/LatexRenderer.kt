package com.example.ApI.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Data class representing a segment of text that can be either regular text or LaTeX
 */
sealed class TextSegment {
    data class Regular(val text: String) : TextSegment()
    data class InlineLatex(val latex: String) : TextSegment()
    data class DisplayLatex(val latex: String) : TextSegment()
}

/**
 * Utility object for parsing and rendering LaTeX expressions
 */
object LatexRenderer {
    
    /**
     * Parses text containing LaTeX expressions and splits it into segments
     * Supports:
     * - Display mode: $$...$$
     * - Inline mode: $...$
     */
    fun parseLatexSegments(text: String): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        var currentIndex = 0
        
        while (currentIndex < text.length) {
            // Look for display LaTeX ($$...$$)
            val displayStart = text.indexOf("$$", currentIndex)
            if (displayStart != -1 && displayStart >= currentIndex) {
                // Add regular text before the LaTeX
                if (displayStart > currentIndex) {
                    segments.add(TextSegment.Regular(text.substring(currentIndex, displayStart)))
                }
                
                // Find the closing $$
                val displayEnd = text.indexOf("$$", displayStart + 2)
                if (displayEnd != -1) {
                    val latexContent = text.substring(displayStart + 2, displayEnd).trim()
                    if (latexContent.isNotBlank()) {
                        segments.add(TextSegment.DisplayLatex(latexContent))
                    }
                    currentIndex = displayEnd + 2
                    continue
                } else {
                    // No closing $$, treat as regular text
                    segments.add(TextSegment.Regular(text.substring(displayStart)))
                    break
                }
            }
            
            // Look for inline LaTeX ($...$)
            val inlineStart = text.indexOf("$", currentIndex)
            if (inlineStart != -1 && inlineStart >= currentIndex) {
                // Add regular text before the LaTeX
                if (inlineStart > currentIndex) {
                    segments.add(TextSegment.Regular(text.substring(currentIndex, inlineStart)))
                }
                
                // Find the closing $
                val inlineEnd = text.indexOf("$", inlineStart + 1)
                if (inlineEnd != -1) {
                    val latexContent = text.substring(inlineStart + 1, inlineEnd)
                    // Make sure it's not empty and doesn't contain line breaks (likely not LaTeX)
                    if (latexContent.isNotBlank() && !latexContent.contains("\n")) {
                        segments.add(TextSegment.InlineLatex(latexContent))
                        currentIndex = inlineEnd + 1
                        continue
                    } else {
                        // Not valid LaTeX, treat as regular text
                        segments.add(TextSegment.Regular(text.substring(inlineStart, inlineEnd + 1)))
                        currentIndex = inlineEnd + 1
                        continue
                    }
                } else {
                    // No closing $, treat as regular text
                    segments.add(TextSegment.Regular(text.substring(inlineStart)))
                    break
                }
            }
            
            // No more LaTeX found, add the rest as regular text
            if (currentIndex < text.length) {
                segments.add(TextSegment.Regular(text.substring(currentIndex)))
            }
            break
        }
        
        // If no segments were found, add the entire text as regular
        if (segments.isEmpty() && text.isNotEmpty()) {
            segments.add(TextSegment.Regular(text))
        }
        
        return segments
    }
    
    /**
     * Converts LaTeX notation to Unicode mathematical symbols where possible
     */
    fun convertLatexToUnicode(latex: String): String {
        var result = latex
        
        // Greek letters (lowercase)
        val greekMap = mapOf(
            "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
            "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", 
            "\\theta" to "θ", "\\vartheta" to "ϑ",
            "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
            "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\varpi" to "ϖ",
            "\\rho" to "ρ", "\\varrho" to "ϱ",
            "\\sigma" to "σ", "\\varsigma" to "ς", "\\tau" to "τ", "\\upsilon" to "υ",
            "\\phi" to "φ", "\\varphi" to "φ",
            "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω"
        )
        
        // Greek letters (uppercase)
        val greekUpperMap = mapOf(
            "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
            "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Phi" to "Φ",
            "\\Psi" to "Ψ", "\\Omega" to "Ω"
        )
        
        // Mathematical operators and symbols
        val symbolMap = mapOf(
            "\\infty" to "∞", "\\partial" to "∂", "\\nabla" to "∇",
            "\\pm" to "±", "\\mp" to "∓",
            "\\times" to "×", "\\div" to "÷",
            "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
            "\\neq" to "≠", "\\ne" to "≠",
            "\\approx" to "≈", "\\equiv" to "≡", "\\sim" to "∼", "\\simeq" to "≃",
            "\\propto" to "∝",
            "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂", "\\supset" to "⊃",
            "\\subseteq" to "⊆", "\\supseteq" to "⊇",
            "\\cap" to "∩", "\\cup" to "∪",
            "\\int" to "∫", "\\iint" to "∬", "\\iiint" to "∭", "\\oint" to "∮",
            "\\sum" to "∑", "\\prod" to "∏",
            "\\sqrt" to "√",
            "\\cdot" to "·", "\\bullet" to "•",
            "\\ldots" to "…", "\\cdots" to "⋯", "\\vdots" to "⋮", "\\ddots" to "⋱",
            "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←",
            "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐",
            "\\leftrightarrow" to "↔", "\\Leftrightarrow" to "⇔",
            "\\uparrow" to "↑", "\\downarrow" to "↓",
            "\\forall" to "∀", "\\exists" to "∃", "\\nexists" to "∄",
            "\\emptyset" to "∅", "\\varnothing" to "∅",
            "\\angle" to "∠", "\\perp" to "⊥", "\\parallel" to "∥",
            "\\oplus" to "⊕", "\\otimes" to "⊗",
            "\\lim" to "lim", "\\sin" to "sin", "\\cos" to "cos", "\\tan" to "tan",
            "\\ln" to "ln", "\\log" to "log", "\\exp" to "exp",
            "\\min" to "min", "\\max" to "max", "\\sup" to "sup", "\\inf" to "inf",
            "\\det" to "det", "\\dim" to "dim",
            "\\deg" to "deg", "\\arg" to "arg"
        )
        
        // Replace symbols (longer patterns first to avoid partial matches)
        val allSymbols = (greekMap + greekUpperMap + symbolMap).entries.sortedByDescending { it.key.length }
        allSymbols.forEach { (latex, unicode) ->
            result = result.replace(latex, unicode)
        }
        
        return result
    }
    
    /**
     * Parses superscripts (^) and subscripts (_) with proper handling
     */
    data class ParsedLatex(
        val baseText: String,
        val superscripts: List<Pair<Int, String>>, // position to superscript
        val subscripts: List<Pair<Int, String>>    // position to subscript
    )
    
    /**
     * Represents a parsed LaTeX element for rendering
     */
    sealed class LatexElement {
        data class Text(val text: String) : LatexElement()
        data class Superscript(val text: String) : LatexElement()
        data class Subscript(val text: String) : LatexElement()
        data class Fraction(val numerator: String, val denominator: String) : LatexElement()
        data class SquareRoot(val content: String) : LatexElement()
    }
    
    /**
     * Extracts content between braces, handling nested braces
     * Returns the content and the index after the closing brace, or null if invalid
     */
    private fun extractBracedContent(text: String, startIndex: Int): Pair<String, Int>? {
        if (startIndex >= text.length || text[startIndex] != '{') return null
        
        var braceCount = 0
        var index = startIndex
        val content = StringBuilder()
        
        while (index < text.length) {
            when (text[index]) {
                '{' -> {
                    braceCount++
                    if (braceCount > 1) content.append('{')
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        return content.toString() to index + 1
                    } else {
                        content.append('}')
                    }
                }
                else -> {
                    if (braceCount > 0) content.append(text[index])
                }
            }
            index++
        }
        return null // Unmatched braces
    }
    
    /**
     * Parses LaTeX into structured elements for rendering
     */
    fun parseLatexStructure(latex: String): List<LatexElement> {
        val elements = mutableListOf<LatexElement>()
        var remaining = convertLatexToUnicode(latex)
        var currentIndex = 0
        
        while (currentIndex < remaining.length) {
            // Check for fraction
            if (remaining.startsWith("\\frac", currentIndex)) {
                val afterFrac = currentIndex + 5
                if (afterFrac < remaining.length && remaining[afterFrac] == '{') {
                    val numeratorResult = extractBracedContent(remaining, afterFrac)
                    if (numeratorResult != null) {
                        val (numerator, afterNumerator) = numeratorResult
                        if (afterNumerator < remaining.length && remaining[afterNumerator] == '{') {
                            val denominatorResult = extractBracedContent(remaining, afterNumerator)
                            if (denominatorResult != null) {
                                val (denominator, afterDenominator) = denominatorResult
                                elements.add(LatexElement.Fraction(numerator, denominator))
                                currentIndex = afterDenominator
                                continue
                            }
                        }
                    }
                }
            }
            
            // Check for square root
            if (remaining.startsWith("\\sqrt", currentIndex)) {
                val afterSqrt = currentIndex + 5
                if (afterSqrt < remaining.length && remaining[afterSqrt] == '{') {
                    val contentResult = extractBracedContent(remaining, afterSqrt)
                    if (contentResult != null) {
                        val (content, afterContent) = contentResult
                        elements.add(LatexElement.SquareRoot(content))
                        currentIndex = afterContent
                        continue
                    }
                }
            }
            
            // Check for superscript with braces
            if (remaining[currentIndex] == '^') {
                if (currentIndex + 1 < remaining.length) {
                    if (remaining[currentIndex + 1] == '{') {
                        val contentResult = extractBracedContent(remaining, currentIndex + 1)
                        if (contentResult != null) {
                            val (content, afterContent) = contentResult
                            elements.add(LatexElement.Superscript(content))
                            currentIndex = afterContent
                            continue
                        }
                    } else {
                        // Single character superscript
                        elements.add(LatexElement.Superscript(remaining[currentIndex + 1].toString()))
                        currentIndex += 2
                        continue
                    }
                }
            }
            
            // Check for subscript with braces
            if (remaining[currentIndex] == '_') {
                if (currentIndex + 1 < remaining.length) {
                    if (remaining[currentIndex + 1] == '{') {
                        val contentResult = extractBracedContent(remaining, currentIndex + 1)
                        if (contentResult != null) {
                            val (content, afterContent) = contentResult
                            elements.add(LatexElement.Subscript(content))
                            currentIndex = afterContent
                            continue
                        }
                    } else {
                        // Single character subscript
                        elements.add(LatexElement.Subscript(remaining[currentIndex + 1].toString()))
                        currentIndex += 2
                        continue
                    }
                }
            }
            
            // Regular text - collect until next special character
            val nextSpecialIndex = remaining.indexOfAny(charArrayOf('^', '_', '\\'), currentIndex + 1)
            val endIndex = if (nextSpecialIndex == -1) remaining.length else nextSpecialIndex
            
            if (endIndex > currentIndex) {
                elements.add(LatexElement.Text(remaining.substring(currentIndex, endIndex)))
                currentIndex = endIndex
            } else {
                // Single character
                elements.add(LatexElement.Text(remaining[currentIndex].toString()))
                currentIndex++
            }
        }
        
        return elements
    }
}

/**
 * Composable for rendering inline LaTeX
 */
@Composable
fun InlineLatexText(
    latex: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val elements = LatexRenderer.parseLatexStructure(latex)
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        elements.forEach { element ->
            RenderLatexElement(element, style, inline = true)
        }
    }
}

/**
 * Helper to render a single LaTeX element recursively
 */
@Composable
private fun RenderLatexElement(
    element: LatexRenderer.LatexElement,
    style: TextStyle,
    inline: Boolean
) {
    when (element) {
        is LatexRenderer.LatexElement.Text -> {
            Text(
                text = element.text,
                style = style.copy(
                    fontStyle = FontStyle.Italic,
                    fontFamily = FontFamily.Serif
                )
            )
        }
        is LatexRenderer.LatexElement.Superscript -> {
            // Parse the superscript content recursively
            val superElements = LatexRenderer.parseLatexStructure(element.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                superElements.forEach { superElement ->
                    RenderLatexElement(
                        superElement,
                        style.copy(
                            fontSize = style.fontSize * 0.7f,
                            baselineShift = BaselineShift(0.5f)
                        ),
                        inline = true
                    )
                }
            }
        }
        is LatexRenderer.LatexElement.Subscript -> {
            // Parse the subscript content recursively
            val subElements = LatexRenderer.parseLatexStructure(element.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                subElements.forEach { subElement ->
                    RenderLatexElement(
                        subElement,
                        style.copy(
                            fontSize = style.fontSize * 0.7f,
                            baselineShift = BaselineShift(-0.3f)
                        ),
                        inline = true
                    )
                }
            }
        }
        is LatexRenderer.LatexElement.Fraction -> {
            FractionDisplayNested(
                numerator = element.numerator,
                denominator = element.denominator,
                style = style,
                inline = inline
            )
        }
        is LatexRenderer.LatexElement.SquareRoot -> {
            SquareRootDisplayNested(
                content = element.content,
                style = style,
                inline = inline
            )
        }
    }
}

/**
 * Composable for rendering display (block) LaTeX
 */
@Composable
fun DisplayLatexText(
    latex: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val elements = LatexRenderer.parseLatexStructure(latex)
    
    // Force LTR for LaTeX equations
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                elements.forEach { element ->
                    RenderLatexElement(
                        element,
                        style.copy(fontSize = style.fontSize * 1.3f),
                        inline = false
                    )
                }
            }
        }
    }
}

/**
 * Composable for rendering a fraction with recursive parsing of numerator and denominator
 */
@Composable
fun FractionDisplayNested(
    numerator: String,
    denominator: String,
    style: TextStyle,
    inline: Boolean,
    modifier: Modifier = Modifier
) {
    val fontSize = if (inline) style.fontSize * 0.85f else style.fontSize * 1.1f
    val nestedStyle = style.copy(fontSize = fontSize)
    val numeratorElements = LatexRenderer.parseLatexStructure(numerator)
    val denominatorElements = LatexRenderer.parseLatexStructure(denominator)

    // Measure numerator and denominator, then draw a line that exactly matches the widest of the two
    androidx.compose.ui.layout.SubcomposeLayout(modifier = modifier.padding(horizontal = 2.dp)) { constraints ->
        // Subcompose numerator
        val numPlaceables = subcompose("numerator") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                numeratorElements.forEach { element ->
                    RenderLatexElement(element, nestedStyle, inline = true)
                }
            }
        }.map { it.measure(constraints.copy(minWidth = 0)) }

        // Subcompose denominator
        val denPlaceables = subcompose("denominator") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                denominatorElements.forEach { element ->
                    RenderLatexElement(element, nestedStyle, inline = true)
                }
            }
        }.map { it.measure(constraints.copy(minWidth = 0)) }

        val numWidth = numPlaceables.maxOfOrNull { it.width } ?: 0
        val numHeight = numPlaceables.maxOfOrNull { it.height } ?: 0
        val denWidth = denPlaceables.maxOfOrNull { it.width } ?: 0
        val denHeight = denPlaceables.maxOfOrNull { it.height } ?: 0
        val lineHeightPx =  (1.dp.toPx()).toInt().coerceAtLeast(1)

        val contentWidth = maxOf(numWidth, denWidth)
        val layoutWidth = contentWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = numHeight + lineHeightPx + denHeight

        val numX = (layoutWidth - numWidth) / 2
        val denX = (layoutWidth - denWidth) / 2

        layout(layoutWidth, layoutHeight) {
            // Place numerator centered
            var y = 0
            numPlaceables.forEach { it.placeRelative(numX, y) }
            y += numHeight

            // Draw the fraction line by subcomposing a Box with exact width
            val linePlaceables = subcompose("line") {
                Box(
                    modifier = Modifier
                        .width(with(this@SubcomposeLayout) { contentWidth.toDp() })
                        .height(with(this@SubcomposeLayout) { lineHeightPx.toDp() })
                        .background(style.color)
                )
            }.map { it.measure(androidx.compose.ui.unit.Constraints.fixed(contentWidth, lineHeightPx)) }

            linePlaceables.forEach { it.placeRelative(0, y) }
            y += lineHeightPx

            // Place denominator centered
            denPlaceables.forEach { it.placeRelative(denX, y) }
        }
    }
}

/**
 * Composable for rendering a square root with recursive parsing of content
 */
@Composable
fun SquareRootDisplayNested(
    content: String,
    style: TextStyle,
    inline: Boolean,
    modifier: Modifier = Modifier
) {
    val elements = LatexRenderer.parseLatexStructure(content)
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "√(",
            style = style.copy(
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Serif
            )
        )
        elements.forEach { element ->
            RenderLatexElement(element, style, inline)
        }
        Text(
            text = ")",
            style = style.copy(
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Serif
            )
        )
    }
}

