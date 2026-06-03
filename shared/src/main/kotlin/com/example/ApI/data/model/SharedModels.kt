package com.example.ApI.data.model

enum class WebSearchSupport {
    UNSUPPORTED,
    OPTIONAL,
    REQUIRED
}

enum class TextDirectionMode {
    AUTO,
    RTL,
    LTR
}

data class SearchResult(
    val chat: Chat,
    val searchQuery: String,
    val matchType: SearchMatchType,
    val messageIndex: Int = -1,
    val highlightRanges: List<IntRange> = emptyList()
)

enum class SearchMatchType {
    TITLE,
    CONTENT,
    FILE_NAME
}

data class ExecutingToolInfo(
    val toolId: String,
    val toolName: String,
    val startTime: String
)

/**
 * Information about branch variants for a specific node.
 * Used by repository and UI layers to display branch navigation controls.
 */
data class BranchInfo(
    val nodeId: String,
    val currentVariantIndex: Int,
    val totalVariants: Int,
    val currentVariantId: String
) {
    val hasPrevious: Boolean get() = currentVariantIndex > 0
    val hasNext: Boolean get() = currentVariantIndex < totalVariants - 1
    val displayText: String get() = "${currentVariantIndex + 1}/$totalVariants"
}

