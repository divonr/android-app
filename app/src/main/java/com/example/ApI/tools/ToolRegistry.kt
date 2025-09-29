package com.example.ApI.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * Registry that manages all available tools in the application
 */
class ToolRegistry {
    companion object {
        @Volatile
        private var INSTANCE: ToolRegistry? = null
        
        fun getInstance(): ToolRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ToolRegistry().also { INSTANCE = it }
            }
        }
    }
    
    private val tools = mutableMapOf<String, Tool>()
    
    init {
        // Register all available tools
        registerTool(DateTimeTool())
        // Future tools can be registered here:
        // registerTool(WeatherTool())
        // registerTool(CalculatorTool())
    }
    
    fun registerPendingToolCall(provider: String, callId: String, toolName: String, rawArguments: String) {
        // TODO: Decode and handle provider-specific tool call payloads
    }

    /**
     * Register a tool with the registry
     */
    fun registerTool(tool: Tool) {
        tools[tool.id] = tool
    }
    
    /**
     * Get a tool by its ID
     */
    fun getTool(id: String): Tool? = tools[id]
    
    /**
     * Get all registered tools
     */
    fun getAllTools(): List<Tool> = tools.values.toList()
    
    /**
     * Get tools that are enabled for the given provider
     * @param enabledToolIds List of tool IDs that are enabled by user
     * @param provider The provider name ("openai", "poe", "google")
     */
    fun getEnabledToolsSpecifications(enabledToolIds: List<String>, provider: String): List<ToolSpecification> {
        return enabledToolIds.mapNotNull { toolId ->
            tools[toolId]?.getSpecification(provider)
        }
    }
    
    /**
     * Execute a tool call
     * @param toolCall Information about the tool call from the model
     * @param enabledToolIds List of tool IDs that are currently enabled
     */
    suspend fun executeTool(toolCall: ToolCall, enabledToolIds: List<String>): ToolExecutionResult {
        // Check if tool is enabled
        if (toolCall.toolId !in enabledToolIds) {
            return ToolExecutionResult.Error("Tool '${toolCall.toolId}' is not enabled")
        }
        
        // Get the tool
        val tool = tools[toolCall.toolId] 
            ?: return ToolExecutionResult.Error("Tool '${toolCall.toolId}' not found")
        
        // Execute the tool
        return try {
            tool.execute(toolCall.parameters)
        } catch (e: Exception) {
            ToolExecutionResult.Error("Failed to execute tool '${toolCall.toolId}': ${e.message}")
        }
    }
    
    /**
     * Parse a tool call from model response for the given provider
     * @param provider The provider name
     * @param responseData The response data containing tool call information
     */
    fun parseToolCall(provider: String, responseData: JsonElement): ToolCall? {
        return when (provider) {
            "openai" -> parseOpenAIToolCall(responseData)
            "poe" -> parsePoeToolCall(responseData)
            "google" -> parseGoogleToolCall(responseData)
            else -> null
        }
    }
    
    /**
     * Parse tool call from OpenAI response format
     */
    private fun parseOpenAIToolCall(responseData: JsonElement): ToolCall? {
        // Based on providers.json OpenAI response format
        // Looking for tool_calls in the response
        try {
            if (responseData is JsonObject) {
                val toolCalls = responseData["tool_calls"]
                // TODO: Implement OpenAI tool call parsing based on response format
                // This will be implemented when we update the ApiService
            }
        } catch (e: Exception) {
            // Handle parsing error
        }
        return null
    }
    
    /**
     * Parse tool call from Poe response format (SSE events)
     */
    private fun parsePoeToolCall(responseData: JsonElement): ToolCall? {
        // Based on the Poe SSE example provided
        // Looking for tool_calls in delta choices
        try {
            if (responseData is JsonObject) {
                val choices = responseData["choices"]
                // TODO: Implement Poe tool call parsing based on SSE format
                // This will be implemented when we update the ApiService
            }
        } catch (e: Exception) {
            // Handle parsing error
        }
        return null
    }
    
    /**
     * Parse tool call from Google response format
     */
    private fun parseGoogleToolCall(responseData: JsonElement): ToolCall? {
        // Based on providers.json Google response format
        // TODO: Implement Google tool call parsing based on response format
        // This will be implemented when we update the ApiService
        return null
    }
}

