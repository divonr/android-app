package com.example.ApI.server.streaming

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.TitleGenerationSettings
import com.example.ApI.server.appModule
import com.example.ApI.server.currentUsername
import com.example.ApI.tools.ToolRegistry
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

// ── Request DTO ───────────────────────────────────────────────────────────────

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
    val projectAttachments: List<Attachment> = emptyList()
)

// ── Thinking-budget parser ────────────────────────────────────────────────────

private fun parseBudget(raw: String): ThinkingBudgetValue = when (raw.lowercase()) {
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
private suspend fun maybeGenerateTitle(
    repo: com.example.ApI.data.repository.DataRepository,
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

// ── Route ─────────────────────────────────────────────────────────────────────

private val routeJson = Json { encodeDefaults = true; isLenient = true; ignoreUnknownKeys = true }

/**
 * SSE frame format: "event: <type>\ndata: <json>\n\n"
 */
private fun sseFrame(eventType: String, data: String): String =
    "event: $eventType\ndata: $data\n\n"

/**
 * Sentinel value used as a channel message to signal that [onComplete] fired.
 * The real full text is passed via a separate variable captured in the closure.
 */
private const val COMPLETE_SENTINEL = "__COMPLETE__"

/**
 * Registers POST /api/chat/send inside the authenticated /api route block.
 *
 * Flow:
 * 1. Parse [SendRequest].
 * 2. Resolve [Provider] from repository.
 * 3. Resolve enabled [ToolSpecification]s from [ToolRegistry].
 * 4. Persist the incoming user message via [addUserMessageAsNewNode].
 * 5. Open SSE response via [respondTextWriter].
 * 6. Inside the writer block: launch the [ChatEngine.send] call on Dispatchers.IO
 *    (using coroutineScope so the writer block waits for it); the callback sends
 *    pre-formatted SSE frames to an UNLIMITED [Channel].
 * 7. A drain loop in the writer block consumes the channel and writes/flushes to the
 *    HTTP response.
 * 8. On completion: persist assistant message, trigger title generation, emit `complete`.
 * 9. On error: emit `error` event and close stream.
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

        // ── Resolve Provider ─────────────────────────────────────────────────
        val provider = repo.loadProviders().find { it.provider == body.provider }
        if (provider == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown provider: ${body.provider}"))
            return@post
        }

        // ── Resolve enabled tools ─────────────────────────────────────────────
        val enabledTools = ToolRegistry.getInstance()
            .getEnabledToolsSpecifications(body.enabledToolIds, body.provider)

        // ── Persist the new user message ──────────────────────────────────────
        // The last user message in the request body is the new user message.
        val userMessage = body.messages.lastOrNull { it.role == "user" }
        if (userMessage != null) {
            repo.addUserMessageAsNewNode(username, body.chatId, userMessage)
        }

        // ── Open SSE stream ───────────────────────────────────────────────────
        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
        call.response.headers.append("X-Accel-Buffering", "no")

        // Channel used as a producer-consumer bridge between the callback (producer,
        // may run on any coroutine) and the respondTextWriter block (consumer).
        // UNLIMITED capacity so callback.trySend() never drops a frame.
        val sseChannel = Channel<String>(Channel.UNLIMITED)

        // Mutable state captured by the callback closures below.
        var fullResponseText = ""

        call.respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
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
                    // Emit tool_call
                    val callPayload = buildJsonObject {
                        put("toolId", toolCall.toolId)
                        put("toolName", ToolRegistry.getInstance().getToolDisplayName(toolCall.toolId))
                        put("parameters", toolCall.parameters)
                    }
                    sseChannel.trySend(sseFrame("tool_call", routeJson.encodeToString(JsonObject.serializer(), callPayload)))

                    // Execute
                    val result = ToolRegistry.getInstance().executeTool(toolCall, body.enabledToolIds)

                    // Emit tool_result
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
                            model = body.modelName,
                            datetime = Instant.now().toString()
                        )
                        repo.addResponseToCurrentVariant(username, body.chatId, preceding)
                    }
                    repo.addResponseToCurrentVariant(username, body.chatId, toolCallMessage)
                    repo.addResponseToCurrentVariant(username, body.chatId, toolResponseMessage)
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

            // Launch the engine call concurrently with channel draining.
            coroutineScope {
                val engineJob = launch(Dispatchers.IO) {
                    try {
                        engine.send(
                            provider = provider,
                            modelName = body.modelName,
                            messages = body.messages,
                            systemPrompt = body.systemPrompt,
                            username = username,
                            chatId = body.chatId,
                            projectAttachments = body.projectAttachments,
                            webSearchEnabled = body.webSearchEnabled,
                            enabledTools = enabledTools,
                            thinkingBudget = parseBudget(body.thinkingBudget),
                            temperature = body.temperature,
                            callback = callback
                        )
                    } catch (e: Exception) {
                        val payload = buildJsonObject { put("error", e.message ?: "Unknown error") }
                        sseChannel.trySend(sseFrame("error", routeJson.encodeToString(JsonObject.serializer(), payload)))
                        sseChannel.close()
                    }
                }

                // Drain the channel, writing each frame to the HTTP response.
                for (frame in sseChannel) {
                    if (frame == COMPLETE_SENTINEL) {
                        // onComplete fired — persist the assistant message.
                        val assistantMessage = Message(
                            role = "assistant",
                            text = fullResponseText,
                            model = body.modelName,
                            datetime = Instant.now().toString()
                        )
                        val savedChat = repo.addResponseToCurrentVariant(username, body.chatId, assistantMessage)
                        val savedMessageId = savedChat?.messages
                            ?.lastOrNull { it.role == "assistant" }?.id ?: ""

                        // Title generation (mirrors desktop TitleGenerationManager)
                        val titleSettings = repo.loadAppSettings().titleGenerationSettings
                        maybeGenerateTitle(repo, username, body.chatId, titleSettings)

                        // Emit the public `complete` event
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
}
