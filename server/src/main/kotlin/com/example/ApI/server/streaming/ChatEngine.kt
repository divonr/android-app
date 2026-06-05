package com.example.ApI.server.streaming

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.tools.ToolSpecification

/**
 * Thin seam between the send-route and the actual LLM call.
 *
 * The real implementation delegates to [DataRepository.sendMessage];
 * the test implementation drives the callback with a scripted sequence
 * — no network, no real API keys needed.
 */
interface ChatEngine {
    suspend fun send(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        username: String,
        chatId: String,
        projectAttachments: List<Attachment>,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?,
        callback: StreamingCallback
    )
}
