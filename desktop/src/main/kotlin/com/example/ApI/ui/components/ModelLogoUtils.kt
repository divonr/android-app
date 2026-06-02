package com.example.ApI.ui.components

import java.util.Locale

object ModelLogoUtils {

    /**
     * Maps a model name to the corresponding logo asset path.
     * Returns null if no specific logo mapping is found.
     * 
     * Asset path format: file:///android_asset/models_logos/<filename>
     */
    fun getModelLogoPath(modelName: String?): String? {
        if (modelName == null) return null

        val lowerName = modelName.lowercase(Locale.ROOT)
        val basePath = "file:///android_asset/models_logos/"

        // 1. OpenAI: "gpt.png"
        // Note: The user specified logic. "gpt" or "o{digit}" -> gpt.png
        // This covers "gpt-4", "gpt-3.5", etc.
        if (lowerName.contains("gpt") || lowerName.matches(Regex(".*o\\d.*"))) {
            return "${basePath}gpt.png"
        }

        // 2. Anthropic: "claude.png"
        // Logic: "claude" OR "sonnet" OR "opus" OR "haiku"
        if (lowerName.contains("claude") || 
            lowerName.contains("sonnet") || 
            lowerName.contains("opus") || 
            lowerName.contains("haiku")) {
            return "${basePath}claude.png"
        }

        // 3. Google: "gemini.png"
        // Logic: "gemini"
        if (lowerName.contains("gemini")) {
            return "${basePath}gemini.png"
        }

        // 4. xAI: "grok.png"
        // Logic: "grok"
        if (lowerName.contains("grok")) {
            return "${basePath}grok.png"
        }

        // 5. Cohere: "command.png"
        // Logic: "command"
        if (lowerName.contains("command")) {
            return "${basePath}command.png"
        }

        // 6. DeepSeek: "deepseek.png"
        // Logic: "deep" AND "seek"
        if (lowerName.contains("deep") && lowerName.contains("seek")) {
            return "${basePath}deepseek.png"
        }

        // 7. GLM: "glm.png"
        // Logic: "glm"
        if (lowerName.contains("glm")) {
            return "${basePath}glm.png"
        }

        // 8. MiniMax: "minimax.png"
        // Logic: "minimax"
        if (lowerName.contains("minimax")) {
            return "${basePath}minimax.png"
        }

        // Fallback
        return null
    }
}
