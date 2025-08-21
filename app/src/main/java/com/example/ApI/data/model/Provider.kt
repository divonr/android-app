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

@Serializable
sealed class Model {
    abstract val name: String?
    abstract val min_points: Int?
    
    @Serializable
    data class SimpleModel(
        override val name: String,
        override val min_points: Int? = null
    ) : Model()
    
    @Serializable
    data class ComplexModel(
        override val name: String? = null,
        override val min_points: Int? = null,
        val other_fields: Map<String, kotlinx.serialization.json.JsonElement>? = null
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
