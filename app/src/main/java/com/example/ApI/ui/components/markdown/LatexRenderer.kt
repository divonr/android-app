package com.example.ApI.ui.components.markdown

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
