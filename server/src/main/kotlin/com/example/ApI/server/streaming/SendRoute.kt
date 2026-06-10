package com.example.ApI.server.streaming

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.TitleGenerationSettings
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.appModule
import com.example.ApI.server.currentUsername
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

// ── Request DTOs ──────────────────────────────────────────────────────────────

/**
 * JSON body for POST /api/chat/send.
 *
 * The client sends the full message history (including the new user message) so the
 * server doesn't need a separate "add message" round-trip before streaming.  The
 * server persists the new user message and streams the assistant response.
 *
 * [messages] — full conversation history INCLUDING the new user message at the end.
 * [projectAttachments] — optional group/project context attachments.
 * [thinkingBudget] — "none" | "low" | "medium" | "high" | "max" | integer string
 *                    (maps to [ThinkingBudgetValue]; see [parseBudget]).
 */
@Serializable
data class SendRequest(
    val chatId: String,
    val provider: String,
    val modelName: String,
    val messages: List<Message>,
    val systemPrompt: String = "",
    val webSearchEnabled: Boolean = false,
    val enabledToolIds: List<String> = emptyList(),
    val thinkingBudget: String = "none",
    val temperature: Float? = null,
    val projectAttachments: List<Attachment> = emptyList(),
    /**
     * When false, the server does NOT persist the trailing user message before streaming.
     * Used by multi-message mode: the client already persisted the buffered user messages
     * via POST /api/chats/{id}/messages and only wants the assistant reply now.
     */
    val persistUserMessage: Boolean = true
)

/**
 * JSON body for POST /api/chats/{chatId}/messages/{messageId}/resend.
 *
 * The server deletes the specified message and all subsequent messages, then
 * re-adds the user message and streams a new assistant response.
 */
@Serializable
data class ResendRequest(
    val provider: String,
    val modelName: String,
    val systemPrompt: String = "",
    val webSearchEnabled: Boolean = false,
    val enabledToolIds: List<String> = emptyList(),
    val thinkingBudget: String = "none",
    val temperature: Float? = null
)

// ── Thinking-budget parser ────────────────────────────────────────────────────

internal fun parseBudget(raw: String): ThinkingBudgetValue = when (raw.lowercase()) {
    "none"   -> ThinkingBudgetValue.None
    "low"    -> ThinkingBudgetValue.Effort("low")
    "medium" -> ThinkingBudgetValue.Effort("medium")
    "high"   -> ThinkingBudgetValue.Effort("high")
    "max"    -> ThinkingBudgetValue.Effort("high")
    else     -> raw.toIntOrNull()?.let { ThinkingBudgetValue.Tokens(it) } ?: ThinkingBudgetValue.None
}

// ── Title-generation helper ────────────────────────────────────────────────────

/**
 * Mirrors [TitleGenerationManager.handleTitleGeneration]:
 * Generates a title when the chat has exactly 1 or 3 assistant messages
 * (depending on [TitleGenerationSettings.updateOnExtension]).
 */
internal suspend fun maybeGenerateTitle(
    repo: DataRepository,
    username: String,
    chatId: String,
    settings: TitleGenerationSettings
) {
    if (!settings.enabled) return

    val chat = repo.loadChatHistory(username).chat_history.find { it.chat_id == chatId } ?: return
    val assistantCount = chat.messages.count { it.role == "assistant" }

    val shouldGenerate = when {
        assistantCount == 1 -> true
        assistantCount == 3 && settings.updateOnExtension -> true
        else -> false
    }
    if (!shouldGenerate) return

    try {
        val providerToUse = if (settings.provider == "auto") null else settings.provider
        val title = repo.generateConversationTitle(username, chatId, providerToUse)
        if (title.isNotBlank() && title != "New chat") {
            val history = repo.loadChatHistory(username)
            val updated = history.copy(
                chat_history = history.chat_history.map { c ->
                    if (c.chat_id == chatId) c.copy(preview_name = title) else c
                }
            )
            repo.saveChatHistory(updated)
        }
    } catch (e: Exception) {
        // Title generation failure is non-fatal — log and continue.
        println("[SendRoute] Title generation failed: ${e.message}")
    }
}

// ── Shared SSE plumbing ───────────────────────────────────────────────────────

private val routeJson = Json { encodeDefaults = true; isLenient = true; ignoreUnknownKeys = true }

/** SSE frame format: "event: <type>\ndata: <json>\n\n" */
private fun sseFrame(eventType: String, data: String): String =
    "event: $eventType\ndata: $data\n\n"

private const val COMPLETE_SENTINEL = "__COMPLETE__"

/**
 * Shared SSE streaming helper used by both POST /api/chat/send and
 * POST /api/chats/{chatId}/messages/{messageId}/resend.
 *
 * Sets SSE headers, builds a [StreamingCallback] that fans events into a
 * channel, launches the [ChatEngine.send] call, drains the channel into
 * the HTTP response, persists the assistant message on completion, and
 * triggers automatic title generation.
 */
internal suspend fun ApplicationCall.streamSseResponse(
    chatId: String,
    username: String,
    messages: List<Message>,
    provider: Provider,
    modelName: String,
    systemPrompt: String,
    webSearchEnabled: Boolean,
    enabledToolIds: List<String>,
    projectAttachments: List<Attachment>,
    enabledTools: List<ToolSpecification>,
    thinkingBudget: ThinkingBudgetValue,
    temperature: Float?,
    repo: DataRepository,
    engine: ChatEngine
) {
    response.headers.append(HttpHeaders.CacheControl, "no-cache")
    response.headers.append(HttpHeaders.Connection, "keep-alive")
    response.headers.append("X-Accel-Buffering", "no")

    val sseChannel = Channel<String>(Channel.UNLIMITED)
    var fullResponseText = ""

    respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
        val callback = object : com.example.ApI.data.model.StreamingCallback {

            override fun onPartialResponse(text: String) {
                val payload = buildJsonObject { put("text", text) }
                sseChannel.trySend(sseFrame("partial", routeJson.encodeToString(JsonObject.serializer(), payload)))
            }

            override fun onThinkingStarted() {
                sseChannel.trySend(sseFrame("thinking_started", "{}"))
            }

            override fun onThinkingPartial(text: String) {
                val payload = buildJsonObject { put("text", text) }
                sseChannel.trySend(sseFrame("thinking_partial", routeJson.encodeToString(JsonObject.serializer(), payload)))
            }

            override fun onThinkingComplete(
                thoughts: String?,
                durationSeconds: Float,
                status: com.example.ApI.data.model.ThoughtsStatus
            ) {
                val payload = buildJsonObject {
                    if (thoughts != null) put("thoughts", thoughts) else put("thoughts", null as String?)
                    put("durationSeconds", durationSeconds.toDouble())
                    put("status", status.name)
                }
                sseChannel.trySend(sseFrame("thinking_complete", routeJson.encodeToString(JsonObject.serializer(), payload)))
            }

            override suspend fun onToolCall(
                toolCall: com.example.ApI.tools.ToolCall,
                precedingText: String
            ): com.example.ApI.tools.ToolExecutionResult {
                val callPayload = buildJsonObject {
                    put("toolId", toolCall.toolId)
                    put("toolName", ToolRegistry.getInstance().getToolDisplayName(toolCall.toolId))
                    put("parameters", toolCall.parameters)
                }
                sseChannel.trySend(sseFrame("tool_call", routeJson.encodeToString(JsonObject.serializer(), callPayload)))

                val result = ToolRegistry.getInstance().executeTool(toolCall, enabledToolIds)

                val resultPayload = buildJsonObject {
                    put("toolId", toolCall.toolId)
                    when (result) {
                        is com.example.ApI.tools.ToolExecutionResult.Success -> {
                            put("success", true)
                            put("output", result.result)
                        }
                        is com.example.ApI.tools.ToolExecutionResult.Error -> {
                            put("success", false)
                            put("output", result.error)
                        }
                    }
                }
                sseChannel.trySend(sseFrame("tool_result", routeJson.encodeToString(JsonObject.serializer(), resultPayload)))

                return result
            }

            override suspend fun onSaveToolMessages(
                toolCallMessage: Message,
                toolResponseMessage: Message,
                precedingText: String
            ) {
                if (precedingText.isNotBlank()) {
                    val preceding = Message(
                        role = "assistant",
                        text = precedingText,
                        model = modelName,
                        datetime = Instant.now().toString()
                    )
                    repo.addResponseToCurrentVariant(username, chatId, preceding)
                }
                repo.addResponseToCurrentVariant(username, chatId, toolCallMessage)
                repo.addResponseToCurrentVariant(username, chatId, toolResponseMessage)
                // Notify the client that new messages were persisted mid-stream so it
                // can reload the chat history and clear the streaming overlay.
                sseChannel.trySend(sseFrame("messages_added", "{}"))
            }

            override fun onComplete(fullText: String) {
                fullResponseText = fullText
                sseChannel.trySend(COMPLETE_SENTINEL)
                sseChannel.close()
            }

            override fun onError(error: String) {
                val payload = buildJsonObject { put("error", error) }
                sseChannel.trySend(sseFrame("error", routeJson.encodeToString(JsonObject.serializer(), payload)))
                sseChannel.close()
            }
        }

        coroutineScope {
            val engineJob = launch(Dispatchers.IO) {
                try {
                    engine.send(
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
                    val payload = buildJsonObject { put("error", e.message ?: "Unknown error") }
                    sseChannel.trySend(sseFrame("error", routeJson.encodeToString(JsonObject.serializer(), payload)))
                    sseChannel.close()
                }
            }

            for (frame in sseChannel) {
                if (frame == COMPLETE_SENTINEL) {
                    val assistantMessage = Message(
                        role = "assistant",
                        text = fullResponseText,
                        model = modelName,
                        datetime = Instant.now().toString()
                    )
                    val savedChat = repo.addResponseToCurrentVariant(username, chatId, assistantMessage)
                    val savedMessageId = savedChat?.messages
                        ?.lastOrNull { it.role == "assistant" }?.id ?: ""

                    val titleSettings = repo.loadAppSettings().titleGenerationSettings
                    maybeGenerateTitle(repo, username, chatId, titleSettings)

                    val completePayload = buildJsonObject {
                        put("text", fullResponseText)
                        put("messageId", savedMessageId)
                    }
                    write(sseFrame("complete", routeJson.encodeToString(JsonObject.serializer(), completePayload)))
                    flush()
                    break
                } else {
                    write(frame)
                    flush()
                }
            }

            engineJob.join()
        }
    }
}

// ── Routes ─────────────────────────────────────────────────────────────────────

/**
 * Registers POST /api/chat/send inside the authenticated /api route block.
 *
 * Persists the incoming user message, then delegates all SSE streaming to
 * [streamSseResponse].
 */
fun Route.sendRoute() {
    post("/chat/send") {
        val body = try {
            call.receive<SendRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            return@post
        }

        val appModule = call.application.appModule
        val repo = appModule.repository
        val engine = appModule.chatEngine
        val username = call.currentUsername()

        val provider = repo.loadProviders().find { it.provider == body.provider }
        if (provider == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown provider: ${body.provider}"))
            return@post
        }

        val enabledTools = ToolRegistry.getInstance()
            .getEnabledToolsSpecifications(body.enabledToolIds, body.provider)

        val userMessage = body.messages.lastOrNull { it.role == "user" }
        if (userMessage != null && body.persistUserMessage) {
            repo.addUserMessageAsNewNode(username, body.chatId, userMessage)
        }

        call.streamSseResponse(
            chatId = body.chatId,
            username = username,
            messages = body.messages,
            provider = provider,
            modelName = body.modelName,
            systemPrompt = body.systemPrompt,
            webSearchEnabled = body.webSearchEnabled,
            enabledToolIds = body.enabledToolIds,
            projectAttachments = body.projectAttachments,
            enabledTools = enabledTools,
            thinkingBudget = parseBudget(body.thinkingBudget),
            temperature = body.temperature,
            repo = repo,
            engine = engine
        )
    }
}

/**
 * Registers POST /api/chats/{chatId}/messages/{messageId}/resend inside the
 * authenticated /api route block.
 *
 * Deletes the specified message and all subsequent messages, re-adds the user
 * message as a new node, then delegates SSE streaming to [streamSseResponse].
 */
fun Route.resendRoute() {
    post("/chats/{chatId}/messages/{messageId}/resend") {
        val chatId = call.parameters["chatId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val messageId = call.parameters["messageId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing messageId")
        )
        val body = try {
            call.receive<ResendRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            return@post
        }

        val appModule = call.application.appModule
        val repo = appModule.repository
        val engine = appModule.chatEngine
        val username = call.currentUsername()

        // Verify the chat exists
        val chat = repo.loadChatHistory(username).chat_history.find { it.chat_id == chatId }
        if (chat == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
            return@post
        }

        // Find the message to resend
        val message = chat.messages.find { it.id == messageId }
        if (message == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Message not found"))
            return@post
        }

        // Resolve provider
        val provider = repo.loadProviders().find { it.provider == body.provider }
        if (provider == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown provider: ${body.provider}"))
            return@post
        }

        val enabledTools = ToolRegistry.getInstance()
            .getEnabledToolsSpecifications(body.enabledToolIds, body.provider)

        // Delete from that message onwards (inclusive), then re-add as a new node.
        repo.deleteMessagesFromPoint(username, chatId, message)
        repo.addUserMessageAsNewNode(username, chatId, message)

        // Load the updated message list (includes the re-added user message).
        val updatedMessages = repo.loadChatHistory(username)
            .chat_history.find { it.chat_id == chatId }?.messages ?: listOf(message)

        call.streamSseResponse(
            chatId = chatId,
            username = username,
            messages = updatedMessages,
            provider = provider,
            modelName = body.modelName,
            systemPrompt = body.systemPrompt,
            webSearchEnabled = body.webSearchEnabled,
            enabledToolIds = body.enabledToolIds,
            projectAttachments = emptyList(),
            enabledTools = enabledTools,
            thinkingBudget = parseBudget(body.thinkingBudget),
            temperature = body.temperature,
            repo = repo,
            engine = engine
        )
    }
}
