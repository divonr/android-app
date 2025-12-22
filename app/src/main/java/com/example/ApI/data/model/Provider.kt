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
 * Represents Poe pricing information for a model.
 * Can be either fixed (exact points per message) or token-based (points per 1k tokens).
 */
@Serializable
data class PoePricing(
    // Fixed pricing - exact points per message (mutually exclusive with token-based)
    val points: Int? = null,
    // Legacy minimum points field (deprecated)
    val min_points: Int? = null,
    // Token-based pricing - points per 1000 input tokens
    val input_points_per_1k: Double? = null,
    // Token-based pricing - points per 1000 output tokens
    val output_points_per_1k: Double? = null
) {
    /**
     * Returns true if this is fixed pricing (exact points per message)
     */
    val isFixedPricing: Boolean
        get() = points != null

    /**
     * Returns true if this is token-based pricing
     */
    val isTokenBasedPricing: Boolean
        get() = input_points_per_1k != null || output_points_per_1k != null

    /**
     * Returns true if this uses the legacy min_points field
     */
    val isLegacyPricing: Boolean
        get() = min_points != null && points == null && !isTokenBasedPricing

    /**
     * Returns true if any pricing information is available
     */
    val hasPricing: Boolean
        get() = points != null || min_points != null || isTokenBasedPricing
}

@Serializable
sealed class Model {
    abstract val name: String?
    abstract val min_points: Int?
    abstract val pricing: PoePricing?
    abstract val thinkingConfig: ThinkingBudgetType?
    abstract val temperatureConfig: TemperatureConfig?

    @Serializable
    data class SimpleModel(
        override val name: String,
        override val min_points: Int? = null,
        override val pricing: PoePricing? = null,
        @kotlinx.serialization.Transient
        override val thinkingConfig: ThinkingBudgetType? = null,
        @kotlinx.serialization.Transient
        override val temperatureConfig: TemperatureConfig? = null
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
        override val temperatureConfig: TemperatureConfig? = null
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
