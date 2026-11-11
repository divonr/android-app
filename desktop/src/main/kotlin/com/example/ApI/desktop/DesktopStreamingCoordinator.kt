package com.example.ApI.desktop

import com.example.ApI.data.model.*
import com.example.ApI.data.repository.ReplyAnchor
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop replacement for Android's StreamingService foreground service.
 * Handles streaming requests directly in coroutines and emits StreamingEvents.
 */
class DesktopStreamingCoordinator(
    private val repository: DesktopRepository
) {
    // Tool result channels: requestId -> channel
    private val toolResultChannels = mutableMapOf<String, Channel<ToolExecutionResult>>()

    /** An active request: its chat and where its responses are saved (pinned variant + tail). */
    private class ActiveRequest(val chatId: String, val anchor: ReplyAnchor) {
        @Volatile var stopped = false
    }

    private val activeRequests = ConcurrentHashMap<String, ActiveRequest>()

    /** Where the active request of [chatId] saves its next response (null: none active). */
    fun replyAnchorForChat(chatId: String): ReplyAnchor? =
        activeRequests.values.firstOrNull { it.chatId == chatId && !it.stopped }?.anchor

    /**
     * The user stopped [chatId]'s stream and saves the partial text itself: the request's later
     * output is not saved any more (it would duplicate the partial reply).
     */
    fun stopForChat(chatId: String) {
        activeRequests.values.filter { it.chatId == chatId }.forEach { it.stopped = true }
    }

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
        // Pin the replies to the variant of the last message sent, after that message
        val active = ActiveRequest(chatId, ReplyAnchor.forRequest(messages))
        activeRequests[requestId] = active
        fun save(message: Message) {
            if (!active.stopped) repository.addAnchoredResponse(username, chatId, message, active.anchor)
        }

        val accumulatedText = StringBuilder()
        val accumulatedThoughts = StringBuilder()
        var thinkingStarted = false
        var thinkingStartTime = 0L
        val currentModel = modelName

        val callback = object : StreamingCallback {
            override fun onPartialResponse(text: String) {
                if (active.stopped) return
                accumulatedText.append(text)
                onEvent(StreamingEvent.PartialResponse(requestId, chatId, text))
            }

            override fun onComplete(fullText: String) {
                if (active.stopped) return  // the ViewModel saved the partial reply
                // Save assistant message to chat
                val assistantMessage = Message(
                    role = "assistant",
                    text = fullText,
                    attachments = emptyList(),
                    model = currentModel,
                    datetime = java.time.Instant.now().toString()
                )
                save(assistantMessage)
                onEvent(StreamingEvent.Complete(requestId, chatId, fullText, currentModel))
            }

            override fun onError(error: String) {
                if (active.stopped) return
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
                if (active.stopped) return
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
                    save(precedingMessage)
                }
                save(toolCallMessage)
                save(toolResponseMessage)
                if (!active.stopped) onEvent(StreamingEvent.MessagesAdded(requestId, chatId))
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
        } finally {
            activeRequests.remove(requestId)
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
