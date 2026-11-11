package com.example.ApI.ui.components.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

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
        RenderLatexElements(elements, style, inline = true)
    }
}

/**
 * Helper to render a single LaTeX element recursively
 */
@Composable
internal fun RenderLatexElement(
    element: LatexElement,
    style: TextStyle,
    inline: Boolean
) {
    when (element) {
        is LatexElement.Text -> {
            Text(
                text = element.text,
                style = style.copy(
                    fontStyle = FontStyle.Italic,
                    fontFamily = FontFamily.Serif
                )
            )
        }
        is LatexElement.Superscript -> {
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
        is LatexElement.Subscript -> {
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
        is LatexElement.Fraction -> {
            FractionDisplayNested(
                numerator = element.numerator,
                denominator = element.denominator,
                style = style,
                inline = inline
            )
        }
        is LatexElement.SquareRoot -> {
            SquareRootDisplayNested(
                content = element.content,
                style = style,
                inline = inline
            )
        }
    }
}

/**
 * Render a list of LaTeX elements with combining logic for base+super/sub scripts
 */
@Composable
internal fun RenderLatexElements(
    elements: List<LatexElement>,
    style: TextStyle,
    inline: Boolean
) {
    // Preprocess elements to combine base character with following super/subscripts (in any order)
    var index = 0
    while (index < elements.size) {
        val current = elements[index]
        when (current) {
            is LatexElement.Text -> {
                val text = current.text
                // If next token(s) are super/sub, attach them to the last char of this text
                if (text.isNotEmpty() && index + 1 < elements.size) {
                    val next1 = elements.getOrNull(index + 1)
                    if (next1 is LatexElement.Superscript || next1 is LatexElement.Subscript) {
                        val baseChar = text.last().toString()
                        val prefix = text.dropLast(1)
                        if (prefix.isNotEmpty()) {
                            // render prefix first
                            RenderLatexElement(LatexElement.Text(prefix), style, inline)
                        }

                        var superText: String? = null
                        var subText: String? = null
                        var consume = 1
                        // Collect up to two following tokens in any order
                        val n2 = elements.getOrNull(index + 1)
                        if (n2 is LatexElement.Superscript) superText = n2.text
                        if (n2 is LatexElement.Subscript) subText = n2.text
                        val n3 = elements.getOrNull(index + 2)
                        if (n3 is LatexElement.Superscript && superText == null) {
                            superText = n3.text; consume = 2
                        }
                        if (n3 is LatexElement.Subscript && subText == null) {
                            subText = n3.text; consume = 2
                        }

                        BaseWithScripts(
                            base = baseChar,
                            superText = superText,
                            subText = subText,
                            style = style
                        )
                        index += 1 + consume
                        continue
                    }
                }
                // default: render as-is
                RenderLatexElement(current, style, inline)
                index++
            }
            else -> {
                // Not text: just render (fractions/sqrt recurse internally)
                RenderLatexElement(current, style, inline)
                index++
            }
        }
    }
}

@Composable
internal fun BaseWithScripts(
    base: String,
    superText: String?,
    subText: String?,
    style: TextStyle
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = base,
            style = style.copy(
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Serif
            )
        )
        if (superText != null || subText != null) {
            Column(modifier = Modifier.padding(start = 1.dp)) {
                if (superText != null) {
                    val superElements = LatexRenderer.parseLatexStructure(superText)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        superElements.forEach { e ->
                            RenderLatexElement(
                                e,
                                style.copy(
                                    fontSize = style.fontSize * 0.7f,
                                    baselineShift = BaselineShift(0.5f)
                                ),
                                inline = true
                            )
                        }
                    }
                } else {
                    // keep spacing consistent
                    Spacer(Modifier.height(0.dp))
                }
                if (subText != null) {
                    val subElements = LatexRenderer.parseLatexStructure(subText)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        subElements.forEach { e ->
                            RenderLatexElement(
                                e,
                                style.copy(
                                    fontSize = style.fontSize * 0.7f,
                                    baselineShift = BaselineShift(-0.3f)
                                ),
                                inline = true
                            )
                        }
                    }
                }
            }
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
                RenderLatexElements(
                    elements,
                    style.copy(fontSize = style.fontSize * 1.3f),
                    inline = false
                )
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
                RenderLatexElements(numeratorElements, nestedStyle, inline = true)
            }
        }.map { it.measure(constraints.copy(minWidth = 0)) }

        // Subcompose denominator
        val denPlaceables = subcompose("denominator") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RenderLatexElements(denominatorElements, nestedStyle, inline = true)
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
