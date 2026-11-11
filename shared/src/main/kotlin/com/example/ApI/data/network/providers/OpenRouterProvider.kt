package com.example.ApI.data.network.providers

import com.example.ApI.tools.ToolSpecification
import java.net.HttpURLConnection

/**
 * OpenRouter API provider implementation.
 * Uses OpenAI-compatible format for multi-model access.
 * Supports:
 * - Streaming responses
 * - Reasoning/thinking with effort levels (xhigh, high, medium, low, minimal)
 * - Tool/function calling with OpenAI-compatible format
 */
class OpenRouterProvider() : OpenAICompatibleProvider() {

    override val providerName: String = "openrouter"
    override val logTag: String = "OpenRouterProvider"

    override fun addCustomHeaders(connection: HttpURLConnection) {
        connection.setRequestProperty("HTTP-Referer", "https://github.com/your-app")
        connection.setRequestProperty("X-Title", "LLM Chat App")
    }
}
