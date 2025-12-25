package com.example.ApI.ui.components.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.BaselineShift

/**
 * Converts LaTeX to a text representation using the unified parsing engine
 */
internal fun convertLatexToTextRepresentation(latex: String): String {
    val elements = LatexRenderer.parseLatexStructure(latex)
    return elements.joinToString("") { element ->
        convertElementToTextRepresentation(element)
    }
}

/**
 * Recursively converts a LaTeX element to text representation with proper Unicode symbols
 */
internal fun convertElementToTextRepresentation(element: LatexElement): String {
    return when (element) {
        is LatexElement.Text -> {
            // Convert symbols like \alpha, \beta, etc. to Unicode
            LatexRenderer.convertLatexToUnicode(element.text)
        }
        is LatexElement.Superscript -> {
            val content = LatexRenderer.parseLatexStructure(element.text)
            val contentText = content.joinToString("") { convertElementToTextRepresentation(it) }
            // Convert common superscripts to Unicode
            convertSuperscriptToUnicode(contentText)
        }
        is LatexElement.Subscript -> {
            val content = LatexRenderer.parseLatexStructure(element.text)
            val contentText = content.joinToString("") { convertElementToTextRepresentation(it) }
            // Convert common subscripts to Unicode
            convertSubscriptToUnicode(contentText)
        }
        is LatexElement.Fraction -> {
            val numContent = LatexRenderer.parseLatexStructure(element.numerator)
            val denContent = LatexRenderer.parseLatexStructure(element.denominator)
            val numerator = numContent.joinToString("") { convertElementToTextRepresentation(it) }
            val denominator = denContent.joinToString("") { convertElementToTextRepresentation(it) }
            "($numerator)/($denominator)"
        }
        is LatexElement.SquareRoot -> {
            val content = LatexRenderer.parseLatexStructure(element.content)
            val contentText = content.joinToString("") { convertElementToTextRepresentation(it) }
            "√($contentText)"
        }
    }
}

/**
 * Append inline LaTeX as annotated spans so superscripts/subscripts for letters render correctly.
 */
internal fun AnnotatedString.Builder.appendInlineLatexAnnotated(
    latex: String,
    baseStyle: TextStyle
) {
    val elements = LatexRenderer.parseLatexStructure(latex)

    elements.forEach { element ->
        when (element) {
            is LatexElement.Text -> {
                val text = LatexRenderer.convertLatexToUnicode(element.text)
                append(text)
            }
            is LatexElement.Superscript -> {
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
            is LatexElement.Subscript -> {
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
            is LatexElement.Fraction -> {
                // Fallback textual inline rendering for fractions
                val num = convertLatexToTextRepresentation(element.numerator)
                val den = convertLatexToTextRepresentation(element.denominator)
                append("(")
                append(num)
                append(")/(")
                append(den)
                append(")")
            }
            is LatexElement.SquareRoot -> {
                val inner = convertLatexToTextRepresentation(element.content)
                append("√(")
                append(inner)
                append(")")
            }
        }
    }
}
