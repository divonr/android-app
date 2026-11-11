package com.example.ApI.server.runs

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.repository.AttachmentOwner
import com.example.ApI.data.repository.ReplyAnchor
import com.example.ApI.server.UserContext
import com.example.ApI.server.appModule
import com.example.ApI.server.streaming.parseBudget
import com.example.ApI.server.userContext
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ── DTOs ──────────────────────────────────────────────────────────────────────

/**
 * JSON body of POST /api/runs (see CLOUD_RUNS_PLAN.md): a client-chosen [runId] plus what
 * `POST /api/chat/send` takes.  [messages] is the chat's path, the last one being the message
 * to answer; the client has already saved it (it reaches the server copy through sync).
 */
@Serializable
data class RunRequest(
    val runId: String,
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
     * The chat's group as the device knows it (optional): what the group-conversations tool
     * runs for when this server's copy of the chat has not arrived yet.
     */
    val groupId: String? = null
)

@Serializable
data class RunStartResponse(val runId: String, val status: String)

/** 409 body: the cloud cannot run the request; [code] tells the client why (it then runs it itself). */
@Serializable
data class RunRejection(val error: String, val code: String)

@Serializable
data class CancelRunRequest(val keepPartial: Boolean = false)

@Serializable
private data class RunsSnapshot(val runs: List<RunState>)

/** The `code` values of a 409 from POST /api/runs. */
object RunRejectionCode {
    const val UNKNOWN_PROVIDER = "unknown_provider"
    const val NO_API_KEY = "no_api_key"
    const val UNSUPPORTED_TOOLS = "unsupported_tools"
    const val ATTACHMENTS_UNSUPPORTED = "attachments_unsupported"
}

// ── Attachments ───────────────────────────────────────────────────────────────

/** How long POST /api/runs waits for the request's files (the device gives the whole call 15 s). */
internal const val RUN_ATTACHMENTS_TIMEOUT_MS = 10_000L

/** How long the web's own send / resend wait for the request's files. */
internal const val WEB_ATTACHMENTS_TIMEOUT_MS = 60_000L

/**
 * The files of a request's attachments for a run of this user (see [resolveRequestAttachments]),
 * or null when they could not all be fetched within [timeoutMs].  Attachments found in the
 * user's own files without a hash (saved by the web before the store existed) are hashed,
 * stored and given their identity in the background.
 */
internal suspend fun UserContext.attachmentsForRun(
    provider: String,
    chatId: String,
    groupId: String?,
    messages: List<Message>,
    projectAttachments: List<Attachment>,
    timeoutMs: Long
): ResolvedRequestAttachments? {
    if (messages.all { it.attachments.isEmpty() } && projectAttachments.isEmpty()) {
        return ResolvedRequestAttachments(AttachmentFiles.NONE, emptyList(), emptyList())
    }
    val resolved = withTimeoutOrNull(timeoutMs) {
        attachments.resolveRequestAttachments(provider, messages, projectAttachments)
    } ?: return null
    for (unhashed in resolved.unhashed) {
        val owner = unhashed.messageId?.let { AttachmentOwner.ChatMessage(chatId, it) }
            ?: groupId?.takeIf { it.isNotBlank() }?.let { AttachmentOwner.Group(it) }
            ?: continue
        attachments.backfillInBackground(owner, unhashed.attachment, unhashed.file)
    }
    return resolved
}

// ── SSE plumbing ──────────────────────────────────────────────────────────────

/** SSE frame format: "event: <type>\ndata: <json>\n\n" */
internal fun sseFrame(eventType: String, data: JsonObject): String =
    "event: $eventType\ndata: ${RunJson.encodeToString(JsonObject.serializer(), data)}\n\n"

private const val SSE_PING = ": ping\n\n"

/**
 * Answer with an SSE stream written by [block] (through its `send`).  A client that went away
 * ends the stream quietly: whatever feeds it lives on elsewhere.
 */
internal suspend fun ApplicationCall.respondSse(block: suspend (send: suspend (String) -> Unit) -> Unit) {
    response.headers.append(HttpHeaders.CacheControl, "no-cache")
    response.headers.append(HttpHeaders.Connection, "keep-alive")
    response.headers.append("X-Accel-Buffering", "no")
    respondBytesWriter(contentType = ContentType.Text.EventStream) {
        try {
            block { frame ->
                writeStringUtf8(frame)
                flush()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // The client disconnected
        }
    }
}

/**
 * Relay [subscription]'s events through [onEvent] until it returns false or the subscription
 * ends, with a ping comment whenever nothing was sent for [pingMs].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun relayEvents(
    subscription: RunSubscription,
    pingMs: Long,
    send: suspend (String) -> Unit,
    onEvent: suspend (RunEvent) -> Boolean
) {
    while (true) {
        // select: a timeout never drops an event that was already taken from the channel
        val received = select {
            subscription.events.onReceiveCatching { it }
            onTimeout(pingMs) { null }
        }
        if (received == null) {
            send(SSE_PING)
            continue
        }
        val event = received.getOrNull() ?: return
        if (!onEvent(event)) return
    }
}

/**
 * Start the run of [spec] and answer with its per-request SSE stream (the wire format of
 * `POST /api/chat/send`): a first `run` event with the run id, then the run's events until it
 * ends.  The run belongs to the registry: closing this response does not stop it.
 */
internal suspend fun ApplicationCall.startAndStreamRun(ctx: UserContext, spec: RunSpec) {
    val appModule = application.appModule
    // Subscribed before the start: no event of the run is missed
    val subscription = appModule.runRegistry.subscribe(ctx.username)
    try {
        appModule.runRegistry.start(ctx, spec)
        respondSse { send ->
            send(sseFrame("run", buildJsonObject { put("runId", spec.runId) }))
            relayEvents(subscription, appModule.runPingIntervalMs, send) { event ->
                if (event.runId != spec.runId || event.type == "run_started") return@relayEvents true
                send(sseFrame(event.type, event.payload))
                !event.isTerminal
            }
        }
    } finally {
        subscription.close()
    }
}

// ── Routes ────────────────────────────────────────────────────────────────────

/** POST /api/runs, GET /api/runs/stream, POST /api/runs/{runId}/cancel (inside the authenticated /api block). */
fun Route.runRoutes() {

    post("/runs") {
        val body = try {
            call.receive<RunRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            return@post
        }
        if (body.runId.isBlank() || body.chatId.isBlank() || body.messages.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId, chatId and messages are required"))
            return@post
        }

        val ctx = call.userContext()
        val repo = ctx.repository
        val registry = call.application.appModule.runRegistry

        // A retry of a request that already got through
        registry.find(ctx.username, body.runId)?.let { known ->
            call.respond(HttpStatusCode.OK, RunStartResponse(known.runId, known.status))
            return@post
        }

        suspend fun reject(code: String, error: String) =
            call.respond(HttpStatusCode.Conflict, RunRejection(error = error, code = code))

        val provider = repo.loadProviders().find { it.provider == body.provider }
            ?: return@post reject(RunRejectionCode.UNKNOWN_PROVIDER, "Unknown provider: ${body.provider}")

        val hasKey = repo.loadApiKeys(ctx.username).any { it.isActive && it.provider == body.provider && it.key.isNotBlank() }
        if (!hasKey) {
            return@post reject(RunRejectionCode.NO_API_KEY, "No active API key for ${body.provider} on the server")
        }

        // The user's own tools for this run, from the user's connections as they are right now
        val tools = ctx.tools.forRun(body.chatId, body.enabledToolIds, body.groupId, body.messages, body.projectAttachments)
        val unknownTools = tools.missing(body.enabledToolIds)
        if (unknownTools.isNotEmpty()) {
            return@post reject(RunRejectionCode.UNSUPPORTED_TOOLS, "Tools not available on the server: ${unknownTools.joinToString()}")
        }

        // The files the provider will read: the user's own files here, the cache, the store.
        // Only a file the server cannot have at all sends the request back to the device
        val groupId = body.groupId?.takeIf { it.isNotBlank() }
            ?: body.projectAttachments.takeIf { it.isNotEmpty() }
                ?.let { repo.loadChatHistory(ctx.username).chat_history.find { c -> c.chat_id == body.chatId }?.group }
        val resolved = ctx.attachmentsForRun(
            body.provider, body.chatId, groupId, body.messages, body.projectAttachments, RUN_ATTACHMENTS_TIMEOUT_MS
        ) ?: return@post reject(
            RunRejectionCode.ATTACHMENTS_UNSUPPORTED, "The attachments could not be fetched from the attachment store in time"
        )
        resolved.unavailable.firstOrNull()?.let { missing ->
            return@post reject(
                RunRejectionCode.ATTACHMENTS_UNSUPPORTED,
                "Attachment \"${missing.attachment.file_name}\" is not available on the server: ${missing.reason}"
            )
        }

        // The client saved its message on its side: bring the server copy up to date meanwhile
        repo.pullNow()

        val (state, started) = registry.start(
            ctx,
            RunSpec(
                runId = body.runId,
                chatId = body.chatId,
                provider = provider,
                modelName = body.modelName,
                messages = body.messages,
                systemPrompt = body.systemPrompt,
                webSearchEnabled = body.webSearchEnabled,
                enabledToolIds = body.enabledToolIds,
                tools = tools,
                thinkingBudget = parseBudget(body.thinkingBudget),
                temperature = body.temperature,
                projectAttachments = body.projectAttachments,
                anchor = ReplyAnchor.forRequest(body.messages),
                attachmentFiles = resolved.files,
                groupId = groupId
            )
        )
        call.respond(
            if (started) HttpStatusCode.Accepted else HttpStatusCode.OK,
            RunStartResponse(state.runId, state.status)
        )
    }

    get("/runs/stream") {
        val ctx = call.userContext()
        val appModule = call.application.appModule
        val subscription = appModule.runRegistry.subscribe(ctx.username)
        try {
            call.respondSse { send ->
                val snapshot = RunJson.encodeToJsonElement(RunsSnapshot.serializer(), RunsSnapshot(subscription.snapshot))
                send(sseFrame("snapshot", snapshot as JsonObject))
                relayEvents(subscription, appModule.runPingIntervalMs, send) { event ->
                    send(sseFrame(event.type, event.dataWithIds()))
                    true
                }
            }
        } finally {
            subscription.close()
        }
    }

    post("/runs/{runId}/cancel") {
        val runId = call.parameters["runId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing runId")
        )
        // An empty body means keepPartial = false
        val keepPartial = try {
            val text = call.receiveText()
            if (text.isBlank()) false else RunJson.decodeFromString(CancelRunRequest.serializer(), text).keepPartial
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            return@post
        }
        val ctx = call.userContext()
        val state = call.application.appModule.runRegistry.cancel(ctx.username, runId, keepPartial)
        if (state == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Run not found"))
        } else {
            call.respond(HttpStatusCode.OK, RunStartResponse(state.runId, state.status))
        }
    }
}
