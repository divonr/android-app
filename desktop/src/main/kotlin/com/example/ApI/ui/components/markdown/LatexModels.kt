package com.example.ApI.ui.components.markdown

/**
 * Data class representing a segment of text that can be either regular text or LaTeX
 */
sealed class TextSegment {
    data class Regular(val text: String) : TextSegment()
    data class InlineLatex(val latex: String) : TextSegment()
    data class DisplayLatex(val latex: String) : TextSegment()
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
