package com.example.ApI.data.model

import kotlinx.serialization.Serializable

/**
 * Represents the type of thinking budget control available for a model.
 */
sealed class ThinkingBudgetType {
    /**
     * Model supports discrete thinking effort levels (e.g., "low", "medium", "high")
     */
    data class Discrete(
        val options: List<String>,
        val default: String,
        val displayNames: Map<String, String> = emptyMap() // Optional Hebrew display names
    ) : ThinkingBudgetType()

    /**
     * Model supports continuous token budget (e.g., 1024-128000)
     * @param supportsOff If true, allows value 0 to disable thinking entirely (with jump from 0 to minTokens)
     */
    data class Continuous(
        val minTokens: Int,
        val maxTokens: Int,
        val default: Int,
        val step: Int = 1024, // Slider step size
        val supportsOff: Boolean = false // Whether 0 is allowed to disable thinking
    ) : ThinkingBudgetType()

    /**
     * Model does not support thinking at all
     */
    object NotSupported : ThinkingBudgetType()

    /**
     * Thinking budget support is in development for this model
     */
    object InDevelopment : ThinkingBudgetType()
}

/**
 * Current thinking budget setting for a session.
 */
@Serializable
sealed class ThinkingBudgetValue {
    /**
     * Discrete effort level (e.g., "low", "medium", "high")
     */
    @Serializable
    data class Effort(val level: String) : ThinkingBudgetValue()

    /**
     * Continuous token budget
     */
    @Serializable
    data class Tokens(val count: Int) : ThinkingBudgetValue()

    /**
     * No thinking budget set (use default or thinking disabled)
     */
    @Serializable
    object None : ThinkingBudgetValue()
}

/**
 * Configuration and utilities for thinking budget per provider/model combination.
 */
object ThinkingBudgetConfig {

    // Hebrew display names for discrete options
    private val hebrewDisplayNames = mapOf(
        "none" to "ללא",
        "minimal" to "מינימלי",
        "low" to "נמוך",
        "medium" to "בינוני",
        "high" to "גבוה",
        "xhigh" to "גבוה מאוד"
    )

    /**
     * Get the thinking budget type for a specific provider and model combination.
     * First checks if the model has a remote config, then falls back to hardcoded defaults.
     *
     * @param provider The provider name (e.g., "openai", "anthropic", "google", "openrouter")
     * @param model The model name
     * @param modelConfig Optional thinking config from the remote models.json
     */
    fun getThinkingBudgetType(
        provider: String,
        model: String,
        modelConfig: ThinkingBudgetType? = null
    ): ThinkingBudgetType {
        // If we have a remote config for this model, use it
        if (modelConfig != null) {
            return modelConfig
        }

        // Fall back to hardcoded defaults
        return when (provider.lowercase()) {
            "openai" -> getOpenAIThinkingBudget(model)
            "anthropic" -> getAnthropicThinkingBudget(model)
            "google" -> getGoogleThinkingBudget(model)
            "google" -> getGoogleThinkingBudget(model)
            "openrouter" -> getOpenRouterThinkingBudget(model)
            "poe" -> getPoeThinkingBudget(model)
            else -> ThinkingBudgetType.InDevelopment
        }
    }

    /**
     * Poe thinking budget configuration.
     * Experimental support for specific models.
     */
    private fun getPoeThinkingBudget(model: String): ThinkingBudgetType {
        val modelLower = model.lowercase()

        return when {
            // Gemini 3 Pro - Hardcoded temporary unlock
            modelLower.contains("gemini-3-pro") -> {
                ThinkingBudgetType.Discrete(
                    options = listOf("low", "high"),
                    default = "high",
                    displayNames = hebrewDisplayNames
                )
            }
            // Other Poe models
            else -> ThinkingBudgetType.InDevelopment
        }
    }

    /**
     * OpenAI thinking budget configuration.
     * - o series: ["low", "medium", "high"] (default: "medium")
     * - gpt-5: ["minimal", "low", "medium", "high"] (default: "medium")
     * - gpt-5.1: ["none", "low", "medium", "high"] (default: "none")
     * - gpt-5.2+: ["none", "low", "medium", "high", "xhigh"] (default: "medium")
     */
    private fun getOpenAIThinkingBudget(model: String): ThinkingBudgetType {
        val modelLower = model.lowercase()

        return when {
            // o series models (o1, o3, o4-mini, etc.)
            modelLower.startsWith("o") && modelLower.getOrNull(1)?.isDigit() == true -> {
                ThinkingBudgetType.Discrete(
                    options = listOf("low", "medium", "high"),
                    default = "medium",
                    displayNames = hebrewDisplayNames
                )
            }
            // gpt-5.2 and higher (e.g., gpt-5.2, gpt-5.3, etc.)
            modelLower.matches(Regex("gpt-5\\.[2-9].*")) ||
            modelLower.matches(Regex("gpt-5\\.\\d{2,}.*")) -> {
                ThinkingBudgetType.Discrete(
                    options = listOf("none", "low", "medium", "high", "xhigh"),
                    default = "medium",
                    displayNames = hebrewDisplayNames
                )
            }
            // gpt-5.1
            modelLower.matches(Regex("gpt-5\\.1.*")) -> {
                ThinkingBudgetType.Discrete(
                    options = listOf("none", "low", "medium", "high"),
                    default = "none",
                    displayNames = hebrewDisplayNames
                )
            }
            // gpt-5 (base, without version suffix)
            modelLower == "gpt-5" || modelLower.matches(Regex("gpt-5[^.].*")) -> {
                ThinkingBudgetType.Discrete(
                    options = listOf("minimal", "low", "medium", "high"),
                    default = "medium",
                    displayNames = hebrewDisplayNames
                )
            }
            // Other OpenAI models don't support thinking
            else -> ThinkingBudgetType.NotSupported
        }
    }

    /**
     * Anthropic thinking budget configuration.
     * - All models: continuous budget_tokens (1024 to max_tokens-1, max 128000)
     * - Default: 10000 tokens (reasonable middle ground)
     * - Supports 0 to disable thinking entirely
     */
    private fun getAnthropicThinkingBudget(model: String): ThinkingBudgetType {
        // All Anthropic models support continuous thinking budget
        return ThinkingBudgetType.Continuous(
            minTokens = 1024,
            maxTokens = 120000,
            default = 10000,
            step = 1024,
            supportsOff = true // Anthropic allows disabling thinking by not including the parameter
        )
    }

    /**
     * Google thinking budget configuration.
     * - gemini-2.0-flash-lite: Not supported
     * - gemini-3 series: Discrete ["low", "high"] (default: "high")
     * - gemini-2.5-pro: Continuous 128-32768
     * - Other: Continuous 0-24576
     */
    private fun getGoogleThinkingBudget(model: String): ThinkingBudgetType {
        val modelLower = model.lowercase()

        return when {
            // Flash lite doesn't support thinking
            modelLower.contains("flash-lite") || modelLower.contains("2.0-flash-lite") -> {
                ThinkingBudgetType.NotSupported
            }
            // Gemini 3 series (discrete)
            modelLower.startsWith("gemini-3") || modelLower.contains("gemini-3-") -> {
                ThinkingBudgetType.Discrete(
                    options = listOf("low", "high"),
                    default = "high",
                    displayNames = hebrewDisplayNames
                )
            }
            // Gemini 2.5 Pro (continuous with specific range)
            modelLower.contains("gemini-2.5-pro") || modelLower == "gemini-2.5-pro" -> {
                ThinkingBudgetType.Continuous(
                    minTokens = 128,
                    maxTokens = 32768,
                    default = 8192,
                    step = 128
                )
            }
            // Other Gemini models (continuous with different range)
            modelLower.contains("gemini") -> {
                ThinkingBudgetType.Continuous(
                    minTokens = 0,
                    maxTokens = 24576,
                    default = 8192,
                    step = 256
                )
            }
            // Unknown Google model
            else -> ThinkingBudgetType.InDevelopment
        }
    }

    /**
     * OpenRouter thinking budget configuration.
     * OpenRouter uses discrete effort levels: xhigh, high, medium, low, minimal
     * Supported by: OpenAI reasoning models (o1, o3, gpt-5) and Grok models
     *
     * Note: OpenRouter routes to underlying providers, so we check model names
     * to determine which ones support reasoning.
     */
    private fun getOpenRouterThinkingBudget(model: String): ThinkingBudgetType {
        val modelLower = model.lowercase()

        return when {
            // OpenAI o-series models via OpenRouter (o1, o1-mini, o1-pro, o3, o3-mini, o4-mini)
            modelLower.contains("openai/o1") ||
            modelLower.contains("openai/o3") ||
            modelLower.contains("openai/o4") -> {
                ThinkingBudgetType.Discrete(
                    options = listOf("low", "medium", "high"),
                    default = "medium",
                    displayNames = hebrewDisplayNames
                )
            }
            // OpenAI GPT-5 series via OpenRouter
            modelLower.contains("openai/gpt-5") -> {
                ThinkingBudgetType.Discrete(
                    options = listOf("minimal", "low", "medium", "high", "xhigh"),
                    default = "medium",
                    displayNames = hebrewDisplayNames
                )
            }
            // Grok models via OpenRouter
            modelLower.contains("x-ai/grok") || modelLower.contains("xai/grok") -> {
                ThinkingBudgetType.Discrete(
                    options = listOf("low", "medium", "high"),
                    default = "medium",
                    displayNames = hebrewDisplayNames
                )
            }
            // DeepSeek R1 (reasoning model)
            modelLower.contains("deepseek/deepseek-r1") ||
            modelLower.contains("deepseek-r1") -> {
                ThinkingBudgetType.Discrete(
                    options = listOf("low", "medium", "high"),
                    default = "medium",
                    displayNames = hebrewDisplayNames
                )
            }
            // Qwen QWQ (reasoning model)
            modelLower.contains("qwen/qwq") || modelLower.contains("qwq") -> {
                ThinkingBudgetType.Discrete(
                    options = listOf("low", "medium", "high"),
                    default = "medium",
                    displayNames = hebrewDisplayNames
                )
            }
            // Other OpenRouter models - reasoning not supported or unknown
            else -> ThinkingBudgetType.NotSupported
        }
    }

    /**
     * Check if a model supports thinking at all.
     */
    fun supportsThinking(
        provider: String,
        model: String,
        modelConfig: ThinkingBudgetType? = null
    ): Boolean {
        return when (getThinkingBudgetType(provider, model, modelConfig)) {
            is ThinkingBudgetType.Discrete -> true
            is ThinkingBudgetType.Continuous -> true
            ThinkingBudgetType.NotSupported -> false
            ThinkingBudgetType.InDevelopment -> false
        }
    }

    /**
     * Get the default thinking budget value for a provider/model.
     */
    fun getDefaultValue(
        provider: String,
        model: String,
        modelConfig: ThinkingBudgetType? = null
    ): ThinkingBudgetValue {
        return when (val type = getThinkingBudgetType(provider, model, modelConfig)) {
            is ThinkingBudgetType.Discrete -> ThinkingBudgetValue.Effort(type.default)
            is ThinkingBudgetType.Continuous -> ThinkingBudgetValue.Tokens(type.default)
            ThinkingBudgetType.NotSupported -> ThinkingBudgetValue.None
            ThinkingBudgetType.InDevelopment -> ThinkingBudgetValue.None
        }
    }

    /**
     * Format a thinking budget value for display.
     */
    fun formatValueForDisplay(value: ThinkingBudgetValue, type: ThinkingBudgetType): String {
        return when (value) {
            is ThinkingBudgetValue.Effort -> {
                val displayNames = (type as? ThinkingBudgetType.Discrete)?.displayNames ?: emptyMap()
                displayNames[value.level] ?: value.level
            }
            is ThinkingBudgetValue.Tokens -> {
                when {
                    value.count == 0 -> "כבוי"
                    value.count >= 1000 -> "${value.count / 1000}K"
                    else -> value.count.toString()
                }
            }
            ThinkingBudgetValue.None -> ""
        }
    }

    /**
     * Check if thinking is actually enabled based on the current value.
     * Returns false when:
     * - Effort level is "none" (OpenAI)
     * - Token count is 0 (Google/Anthropic when supported)
     * - Value is None
     */
    fun isThinkingEnabled(value: ThinkingBudgetValue): Boolean {
        return when (value) {
            is ThinkingBudgetValue.Effort -> value.level.lowercase() != "none"
            is ThinkingBudgetValue.Tokens -> value.count > 0
            ThinkingBudgetValue.None -> false
        }
    }
}
