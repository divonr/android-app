package com.example.ApI.desktop

import com.example.ApI.data.model.*
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Desktop replacement for Android's StreamingService foreground service.
 * Handles streaming requests directly in coroutines and emits StreamingEvents.
 */
class DesktopStreamingCoordinator(
    private val repository: DesktopRepository
) {
    // Tool result channels: requestId -> channel
    private val toolResultChannels = mutableMapOf<String, Channel<ToolExecutionResult>>()

    /**
     * Start a streaming request and emit events to the provided handler.
     * This is called by ChatViewModel when a message needs to be sent.
     */
    suspend fun startStreamingRequest(
        requestId: String,
        chatId: String,
        username: String,
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        webSearchEnabled: Boolean,
        projectAttachments: List<Attachment>,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?,
        onEvent: (StreamingEvent) -> Unit
    ) {
        onEvent(StreamingEvent.StatusChange(requestId, chatId, RequestStatus.STREAMING))

        val accumulatedText = StringBuilder()
        val accumulatedThoughts = StringBuilder()
        var thinkingStarted = false
        var thinkingStartTime = 0L
        val currentModel = modelName

        val callback = object : StreamingCallback {
            override fun onPartialResponse(text: String) {
                accumulatedText.append(text)
                onEvent(StreamingEvent.PartialResponse(requestId, chatId, text))
            }

            override fun onComplete(fullText: String) {
                // Save assistant message to chat
                kotlinx.coroutines.runBlocking {
                    val assistantMessage = Message(
                        role = "assistant",
                        text = fullText,
                        attachments = emptyList(),
                        model = currentModel,
                        datetime = java.time.Instant.now().toString()
                    )
                    repository.addResponseToCurrentVariant(username, chatId, assistantMessage)
                }
                onEvent(StreamingEvent.Complete(requestId, chatId, fullText, currentModel))
            }

            override fun onError(error: String) {
                onEvent(StreamingEvent.Error(requestId, chatId, error))
            }

            override fun onThinkingStarted() {
                if (!thinkingStarted) {
                    thinkingStarted = true
                    thinkingStartTime = System.currentTimeMillis()
                    onEvent(StreamingEvent.ThinkingStarted(requestId, chatId))
                }
            }

            override fun onThinkingPartial(text: String) {
                accumulatedThoughts.append(text)
                onEvent(StreamingEvent.ThinkingPartial(requestId, chatId, text))
            }

            override fun onThinkingComplete(thoughts: String?, durationSeconds: Float, status: ThoughtsStatus) {
                onEvent(StreamingEvent.ThinkingComplete(requestId, chatId, thoughts, durationSeconds, status))
            }

            override suspend fun onToolCall(toolCall: ToolCall, precedingText: String): ToolExecutionResult {
                // Create a channel to receive the tool result
                val resultChannel = Channel<ToolExecutionResult>(Channel.RENDEZVOUS)
                toolResultChannels[requestId] = resultChannel

                // Emit the tool call request event
                onEvent(StreamingEvent.ToolCallRequest(requestId, chatId, toolCall, precedingText))

                // Wait for the result (provided by the ViewModel via provideToolResult)
                val result = resultChannel.receive()
                toolResultChannels.remove(requestId)
                return result
            }

            override suspend fun onSaveToolMessages(toolCallMessage: Message, toolResponseMessage: Message, precedingText: String) {
                if (precedingText.isNotBlank()) {
                    val precedingMessage = Message(
                        role = "assistant",
                        text = precedingText,
                        model = currentModel,
                        datetime = java.time.Instant.now().toString()
                    )
                    repository.addResponseToCurrentVariant(username, chatId, precedingMessage)
                }
                repository.addResponseToCurrentVariant(username, chatId, toolCallMessage)
                repository.addResponseToCurrentVariant(username, chatId, toolResponseMessage)
                onEvent(StreamingEvent.MessagesAdded(requestId, chatId))
            }
        }

        try {
            repository.sendMessage(
                provider = provider,
                modelName = modelName,
                messages = messages,
                systemPrompt = systemPrompt,
                username = username,
                chatId = chatId,
                projectAttachments = projectAttachments,
                webSearchEnabled = webSearchEnabled,
                enabledTools = enabledTools,
                thinkingBudget = thinkingBudget,
                temperature = temperature,
                callback = callback
            )
        } catch (e: Exception) {
            onEvent(StreamingEvent.Error(requestId, chatId, e.message ?: "Unknown error"))
        }
    }

    /** Called by the ViewModel when a tool result is ready. */
    fun provideToolResult(requestId: String, result: ToolExecutionResult) {
        toolResultChannels[requestId]?.trySend(result)
    }

    /** Cancel an active streaming request. */
    fun cancelRequest(requestId: String) {
        toolResultChannels[requestId]?.close()
        toolResultChannels.remove(requestId)
    }
}
