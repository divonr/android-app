package com.example.ApI.tools

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
        registerTool(DateTimeTool())
    }

    fun registerTool(tool: Tool) {
        tools[tool.id] = tool
    }

    fun getTool(toolId: String): Tool? = tools[toolId]

    fun getAllTools(): List<Tool> = tools.values.toList()

    fun getToolDisplayName(toolId: String): String = tools[toolId]?.name ?: toolId

    fun getToolSpecifications(provider: String, excludedToolIds: List<String> = emptyList()): List<ToolSpecification> {
        return tools.values
            .filterNot { it.id in excludedToolIds }
            .map { it.getSpecification(provider) }
    }
}
