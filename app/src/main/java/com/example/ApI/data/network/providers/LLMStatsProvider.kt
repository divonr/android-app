package com.example.ApI.data.network.providers

import android.content.Context
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * LLM Stats (ZeroEval) API provider implementation.
 * Uses OpenAI-compatible format for multi-model access via ZeroEval gateway.
 * Supports:
 * - Streaming responses
 * - Reasoning/thinking with effort levels (xhigh, high, medium, low, minimal)
 * - Tool/function calling with OpenAI-compatible format
 */
class LLMStatsProvider(context: Context) : OpenAICompatibleProvider(context) {

    override val providerName: String = "llmstats"
    override val logTag: String = "LLMStatsProvider"

    override fun formatToolParameters(toolSpec: ToolSpecification): JsonElement {
        return toolSpec.parameters ?: buildJsonObject {}
    }

    override fun getEmptyResponseErrorMessage(enabledTools: List<ToolSpecification>): String {
        if (enabledTools.isNotEmpty()) {
            return "LLM_STATS_EMPTY_RESPONSE_WITH_TOOLS"
        }
        return super.getEmptyResponseErrorMessage(enabledTools)
    }
}
