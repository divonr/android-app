package com.example.ApI.data.model

import kotlinx.serialization.Serializable

/**
 * Represents the temperature configuration for a model.
 */
@Serializable
data class TemperatureConfig(
    val min: Float,
    val max: Float,
    val default: Float?, // null means use API default (don't send parameter)
    val step: Float = 0.1f
) {
    companion object {
        /**
         * Default configurations for known providers.
         * These are used as fallbacks when no remote config is available.
         */
        val OPENAI_DEFAULT = TemperatureConfig(
            min = 0f,
            max = 2f,
            default = 1f,
            step = 0.1f
        )

        val GOOGLE_DEFAULT = TemperatureConfig(
            min = 0f,
            max = 2f,
            default = 1f,
            step = 0.1f
        )

        val ANTHROPIC_DEFAULT = TemperatureConfig(
            min = 0f,
            max = 1f,
            default = 1f,
            step = 0.1f
        )

        val COHERE_DEFAULT = TemperatureConfig(
            min = 0f,
            max = 1f,
            default = 0.3f,
            step = 0.1f
        )

        val OPENROUTER_DEFAULT = TemperatureConfig(
            min = 0f,
            max = 2f,
            default = 1f,
            step = 0.1f
        )

        // Poe doesn't support temperature control
        val NOT_SUPPORTED: TemperatureConfig? = null
    }
}

/**
 * Configuration and utilities for temperature per provider/model combination.
 */
object TemperatureConfigUtils {

    /**
     * Get the temperature config for a specific provider and model combination.
     * First checks if the model has a remote config, then falls back to provider defaults.
     *
     * @param provider The provider name (e.g., "openai", "anthropic", "google")
     * @param model The model name
     * @param modelConfig Optional temperature config from the remote models.json
     * @return TemperatureConfig or null if temperature is not supported
     */
    fun getTemperatureConfig(
        provider: String,
        model: String,
        modelConfig: TemperatureConfig? = null
    ): TemperatureConfig? {
        // If we have a remote config for this model, use it
        if (modelConfig != null) {
            return modelConfig
        }

        // Fall back to provider defaults
        return when (provider.lowercase()) {
            "openai" -> TemperatureConfig.OPENAI_DEFAULT
            "google" -> TemperatureConfig.GOOGLE_DEFAULT
            "anthropic" -> TemperatureConfig.ANTHROPIC_DEFAULT
            "cohere" -> TemperatureConfig.COHERE_DEFAULT
            "openrouter" -> TemperatureConfig.OPENROUTER_DEFAULT
            "poe" -> TemperatureConfig.NOT_SUPPORTED // Poe doesn't expose temperature
            else -> null
        }
    }

    /**
     * Check if a model supports temperature control.
     */
    fun supportsTemperature(
        provider: String,
        model: String,
        modelConfig: TemperatureConfig? = null
    ): Boolean {
        return getTemperatureConfig(provider, model, modelConfig) != null
    }

    /**
     * Format temperature value for display.
     */
    fun formatValueForDisplay(value: Float?): String {
        return when {
            value == null -> "ברירת מחדל"
            value == value.toInt().toFloat() -> value.toInt().toString()
            else -> String.format("%.1f", value)
        }
    }
}
