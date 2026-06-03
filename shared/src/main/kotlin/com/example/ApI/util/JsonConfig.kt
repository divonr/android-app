package com.example.ApI.util

import kotlinx.serialization.json.Json

/**
 * Centralized JSON serialization configuration.
 * Use these instances throughout the app for consistent JSON parsing behavior.
 */
object JsonConfig {
    /**
     * Standard JSON configuration for API communication and data parsing.
     * - ignoreUnknownKeys: Allows parsing JSON with extra fields
     * - isLenient: Allows more flexible parsing (unquoted strings, etc.)
     * - coerceInputValues: Coerces null to default values for non-null types
     */
    val standard = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Pretty-print JSON configuration for storage and debugging.
     * Same as standard but with formatted output.
     */
    val prettyPrint = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}
