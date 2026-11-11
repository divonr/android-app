package com.example.ApI.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Provider(
    val provider: String,
    val models: List<@Serializable(with = ModelSerializer::class) Model>,
    val request: ApiRequest,
    val response_important_fields: ResponseFields,
    val upload_files_request: UploadRequest? = null,
    val upload_files_response_important_fields: UploadResponseFields? = null
)

/**
 * Represents pricing information for a model.
 * Supports both Poe points-based pricing and USD pricing.
 */
@Serializable
data class ModelPricing(
    // Fixed pricing - exact points per message (mutually exclusive with token-based)
    val points: Int? = null,
    // Legacy minimum points field (deprecated)
    val min_points: Int? = null,
    // Token-based pricing - points per 1000 input tokens (Poe)
    val input_points_per_1k: Double? = null,
    // Token-based pricing - points per 1000 output tokens (Poe)
    val output_points_per_1k: Double? = null,
    // USD pricing - price per 1000 input tokens
    val input_price_per_1k: Double? = null,
    // USD pricing - price per 1000 output tokens
    val output_price_per_1k: Double? = null
) {
    /**
     * Returns true if this is fixed pricing (exact points per message)
     */
    val isFixedPricing: Boolean
        get() = points != null

    /**
     * Returns true if this is token-based Poe points pricing
     */
    val isTokenBasedPricing: Boolean
        get() = input_points_per_1k != null || output_points_per_1k != null

    /**
     * Returns true if this has USD pricing
     */
    val hasUsdPricing: Boolean
        get() = input_price_per_1k != null || output_price_per_1k != null

    /**
     * Returns true if this uses the legacy min_points field
     */
    val isLegacyPricing: Boolean
        get() = min_points != null && points == null && !isTokenBasedPricing

    /**
     * Returns true if any pricing information is available
     */
    val hasPricing: Boolean
        get() = points != null || min_points != null || isTokenBasedPricing || hasUsdPricing
}

// Type alias for backwards compatibility
typealias PoePricing = ModelPricing

@Serializable
sealed class Model {
    abstract val name: String?
    abstract val min_points: Int?
    abstract val pricing: PoePricing?
    abstract val thinkingConfig: ThinkingBudgetType?
    abstract val temperatureConfig: TemperatureConfig?
    abstract val releaseOrder: Int?
    abstract val webSearch: String? // "optional", "required", "unsupported", or null

    @Serializable
    data class SimpleModel(
        override val name: String,
        override val min_points: Int? = null,
        override val pricing: PoePricing? = null,
        @kotlinx.serialization.Transient
        override val thinkingConfig: ThinkingBudgetType? = null,
        @kotlinx.serialization.Transient
        override val temperatureConfig: TemperatureConfig? = null,
        @kotlinx.serialization.Transient
        override val releaseOrder: Int? = null,
        @kotlinx.serialization.Transient
        override val webSearch: String? = null
    ) : Model()

    @Serializable
    data class ComplexModel(
        override val name: String? = null,
        override val min_points: Int? = null,
        override val pricing: PoePricing? = null,
        val other_fields: Map<String, kotlinx.serialization.json.JsonElement>? = null,
        @kotlinx.serialization.Transient
        override val thinkingConfig: ThinkingBudgetType? = null,
        @kotlinx.serialization.Transient
        override val temperatureConfig: TemperatureConfig? = null,
        @kotlinx.serialization.Transient
        override val releaseOrder: Int? = null,
        @kotlinx.serialization.Transient
        override val webSearch: String? = null
    ) : Model()

    override fun toString(): String = name ?: "Unknown Model"
}

@Serializable
data class ApiRequest(
    val request_type: String,
    val base_url: String,
    val headers: Map<String, String>,
    val body: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val params: Map<String, String>? = null
)

@Serializable
data class ResponseFields(
    val id: String? = null,
    val model: String? = null,
    val output: List<kotlinx.serialization.json.JsonElement>? = null,
    val usage: kotlinx.serialization.json.JsonElement? = null,
    val response_format: String? = null,
    val events: List<kotlinx.serialization.json.JsonElement>? = null,
    val candidates: List<kotlinx.serialization.json.JsonElement>? = null,
    val usageMetadata: kotlinx.serialization.json.JsonElement? = null,
    val modelVersion: String? = null,
    val responseId: String? = null
)

@Serializable
data class UploadRequest(
    val request_type: String,
    val base_url: String,
    val headers: Map<String, String>,
    val data: Map<String, String>? = null,
    val files: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val params: Map<String, String>? = null
)

@Serializable
data class UploadResponseFields(
    val id: String? = null,
    val file_id: String? = null,
    val attachment_url: String? = null,
    val mime_type: String? = null,
    val file: kotlinx.serialization.json.JsonElement? = null
)
