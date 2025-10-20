package com.example.ApI.ui.screen

import androidx.compose.ui.unit.LayoutDirection

/**
 * Unicode direction control characters for forcing text direction
 */
object TextDirectionUtils {
    // Pop Directional Formatting - closes any LRO/RLO/LRE/RLE
    private const val PDF = '\u202C'
    // Left-to-Right Override - STRONGEST force LTR
    private const val LRO = '\u202D'
    // Right-to-Left Override - STRONGEST force RTL
    private const val RLO = '\u202E'
    
    /**
     * Infer text direction based on the first identifying character.
     * Checks characters sequentially until finding one that identifies the direction.
     * @param text The text to analyze
     * @return LayoutDirection.Rtl for Hebrew/Arabic, LayoutDirection.Ltr otherwise
     */
    fun inferTextDirection(text: String): LayoutDirection {
        if (text.isBlank()) return LayoutDirection.Ltr
        
        for (char in text) {
            // Skip non-identifying characters (numbers, punctuation, whitespace, etc.)
            if (!char.isLetter()) continue
            
            // Check if it's a Hebrew character (U+0590 to U+05FF)
            if (char in '\u0590'..'\u05FF') {
                return LayoutDirection.Rtl
            }
            
            // Check if it's an Arabic character (U+0600 to U+06FF, U+0750 to U+077F, U+08A0 to U+08FF)
            if (char in '\u0600'..'\u06FF' || char in '\u0750'..'\u077F' || char in '\u08A0'..'\u08FF') {
                return LayoutDirection.Rtl
            }
            
            // If we found a letter that's not RTL, it's LTR
            return LayoutDirection.Ltr
        }
        
        // Default to LTR if no identifying characters found
        return LayoutDirection.Ltr
    }
    
    /**
     * Force text to render in LTR direction using OVERRIDE characters (strongest)
     */
    fun forceTextLTR(text: String): String {
        if (text.isBlank()) return text
        return "$LRO$text$PDF"
    }
    
    /**
     * Force text to render in RTL direction using OVERRIDE characters (strongest)
     */
    fun forceTextRTL(text: String): String {
        if (text.isBlank()) return text
        return "$RLO$text$PDF"
    }
    
    /**
     * Force text direction based on boolean flag
     * @param text The text to wrap
     * @param isRTL true for RTL, false for LTR
     */
    fun forceTextDirection(text: String, isRTL: Boolean): String {
        return if (isRTL) {
            forceTextRTL(text)
        } else {
            forceTextLTR(text)
        }
    }
}
