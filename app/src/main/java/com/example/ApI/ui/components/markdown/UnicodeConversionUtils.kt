package com.example.ApI.ui.components.markdown

/**
 * Convert common superscripts to Unicode
 */
internal fun convertSuperscriptToUnicode(text: String): String {
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
internal fun convertSubscriptToUnicode(text: String): String {
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
