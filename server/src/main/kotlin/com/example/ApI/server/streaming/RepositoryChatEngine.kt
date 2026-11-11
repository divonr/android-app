package com.example.ApI.server.streaming

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.tools.ToolSpecification

/**
 * Production [ChatEngine] that delegates to [DataRepository.sendMessage].
 */
class RepositoryChatEngine(private val repository: DataRepository) : ChatEngine {
    override suspend fun send(
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
    ) {
        // The user's custom providers may have changed (here or on another device) since the
        // last request: register them like Android's StreamingService does before every request
        repository.initializeCustomProviders(username)
        repository.initializeFullCustomProviders(username)
        repository.sendMessage(
            provider = provider,
            modelName = modelName,
            messages = messages,
            systemPrompt = systemPrompt,
            username = username,
            // No chat id: the repository would otherwise save the request's attachments back
            // into the chat, and on this server their paths are in-flight server paths. The run
            // writes the provider ids it obtained itself, by hash (RunRegistry.writeProviderIds)
            chatId = null,
            projectAttachments = projectAttachments,
            webSearchEnabled = webSearchEnabled,
            enabledTools = enabledTools,
            thinkingBudget = thinkingBudget,
            temperature = temperature,
            callback = callback
        )
    }
}
