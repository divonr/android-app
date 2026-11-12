package com.example.ApI.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Base interface for all tools that can be used by language models
 */
interface Tool {
    /**
     * Unique identifier for this tool
     */
    val id: String
    
    /**
     * Human-readable name for this tool
     */
    val name: String
    
    /**
     * Description of what this tool does
     */
    val description: String
    
    /**
     * Execute this tool with the given parameters
     * @param parameters The parameters provided by the model (can be empty)
     * @return The result of executing the tool
     */
    suspend fun execute(parameters: JsonObject): ToolExecutionResult
    
    /**
     * Get the tool specification for a specific provider
     * @param provider The provider name ("openai", "poe", "google")
     * @return The tool specification in the format expected by that provider
     */
    fun getSpecification(provider: String): ToolSpecification
}

/**
 * Result of executing a tool
 */
@Serializable
sealed class ToolExecutionResult {
    @Serializable
    data class Success(val result: String, val details: JsonObject? = null) : ToolExecutionResult()
    @Serializable
    data class Error(val error: String, val details: JsonObject? = null) : ToolExecutionResult()
}

/**
 * Specification of how a tool should be represented to a provider
 */
@Serializable
data class ToolSpecification(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null, // Parameters schema in provider format
    val providerSpecific: JsonObject? = null, // Any additional provider-specific data
    /**
     * [description] without per-request content, for providers whose prompt cache covers the
     * tool declarations. null when [description] is already stable.
     */
    val stableDescription: String? = null,
    /**
     * Set by a tool that is given the conversation's files itself (the code sandbox): where a
     * request finds out which of its attachments the tool has. Not part of what is declared.
     */
    @kotlinx.serialization.Transient
    val inputFiles: ToolInputFiles? = null
) {
    // Two specifications are the same when they declare the same tool; [inputFiles] is not declared
    override fun equals(other: Any?): Boolean = other is ToolSpecification && name == other.name &&
        description == other.description && parameters == other.parameters &&
        providerSpecific == other.providerSpecific && stableDescription == other.stableDescription

    override fun hashCode(): Int = listOf(name, description, parameters, providerSpecific, stableDescription).hashCode()
}

/** The conversation's files as a tool has them; asked once per request that needs to know. */
fun interface ToolInputFiles {
    suspend fun paths(): ToolInputPaths
}

/** The path the tool has [attachment]'s file under, or null when the tool does not have it. */
fun interface ToolInputPaths {
    fun pathOf(attachment: com.example.ApI.data.model.Attachment): String?
}

/**
 * Information about a tool call made by a model
 */
@Serializable
data class ToolCall(
    val id: String, // Unique ID for this call (provider-specific)
    val toolId: String, // The tool being called
    val parameters: JsonObject, // Parameters provided by the model
    val provider: String, // Which provider made this call
    val thoughtSignature: String? = null // Google's thought signature for function calls (required for Gemini 3+)
)

/**
 * Information about a completed tool call for UI display
 */
@Serializable
data class ToolCallInfo(
    val toolId: String,
    val toolName: String,
    val parameters: JsonObject,
    val result: ToolExecutionResult,
    val timestamp: String, // ISO 8601 format
    val thoughtSignature: String? = null // Google's thought signature for function calls (required for Gemini 3+)
)
