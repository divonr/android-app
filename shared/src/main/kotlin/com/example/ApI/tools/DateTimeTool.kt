package com.example.ApI.tools

import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Tool that provides current date and time information
 */
class DateTimeTool : Tool {
    override val id: String = "get_date_time"
    override val name: String = "Get Date and Time"
    override val description: String = "Get the current date and time on the device"
    
    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            val now = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val formattedDateTime = now.format(formatter)
            
            // Get timezone info
            val zoneId = ZoneId.systemDefault()
            val offset = now.atZone(zoneId).offset
            
            val result = buildString {
                append("Current date and time: $formattedDateTime")
                append(" (${zoneId.id}")
                if (offset != ZoneOffset.UTC) {
                    append(", UTC${offset}")
                }
                append(")")
            }
            
            val details = buildJsonObject {
                put("date", now.toLocalDate().toString())
                put("time", now.toLocalTime().toString()) 
                put("timezone", zoneId.id)
                put("utc_offset", offset.toString())
                put("iso_format", now.atZone(zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            }
            
            ToolExecutionResult.Success(result, details)
        } catch (e: Exception) {
            ToolExecutionResult.Error("Failed to get current date and time: ${e.message}")
        }
    }
    
    override fun getSpecification(provider: String): ToolSpecification {
        return when (provider) {
            "openai" -> getOpenAISpecification()
            "poe" -> getPoeSpecification()
            "google" -> getGoogleSpecification()
            else -> getDefaultSpecification()
        }
    }
    
    private fun getOpenAISpecification(): ToolSpecification {
        // Based on providers.json OpenAI format
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    // This tool doesn't require parameters
                })
                put("required", JsonArray(emptyList()))
            }
        )
    }
    
    private fun getPoeSpecification(): ToolSpecification {
        // Poe uses function calling format similar to OpenAI
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    // This tool doesn't require parameters
                })
                put("required", JsonArray(emptyList()))
            }
        )
    }
    
    private fun getGoogleSpecification(): ToolSpecification {
        // Based on providers.json Google format
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    // This tool doesn't require parameters
                })
                put("required", JsonArray(emptyList()))
            }
        )
    }
    
    private fun getDefaultSpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    // This tool doesn't require parameters
                })
                put("required", JsonArray(emptyList()))
            }
        )
    }
}
