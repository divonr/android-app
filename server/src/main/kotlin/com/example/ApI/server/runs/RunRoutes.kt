package com.example.ApI.server.runs

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.repository.ReplyAnchor
import com.example.ApI.server.UserContext
import com.example.ApI.server.appModule
import com.example.ApI.server.streaming.parseBudget
import com.example.ApI.server.userContext
import com.example.ApI.tools.ToolRegistry
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

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
    val projectAttachments: List<Attachment> = emptyList()
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

// ── Attachments rule ──────────────────────────────────────────────────────────

/**
 * Whether sending [attachment] to [provider] reads the attachment's file.  Mirrors what
 * `FileUploadManager.ensureFilesUploadedForProvider` and the provider classes do:
 *  - openai: images and PDFs are sent by `file_OPENAI_id` (uploaded first when missing); every
 *    other type is read and inlined as text;
 *  - poe: sent by `file_POE_url` (uploaded first when missing);
 *  - google (AI Studio): sent by `file_GOOGLE_uri` (uploaded first when missing);
 *  - every other provider (anthropic, vertex, cohere, openrouter, custom…) inlines the bytes.
 */
internal fun attachmentNeedsFile(provider: String, attachment: Attachment): Boolean = when (provider) {
    "openai" -> {
        val sentById = attachment.mime_type.startsWith("image/") || attachment.mime_type == "application/pdf"
        !(sentById && !attachment.file_OPENAI_id.isNullOrBlank())
    }
    "poe" -> attachment.file_POE_url.isNullOrBlank()
    "google" -> attachment.file_GOOGLE_uri.isNullOrBlank() || attachment.file_GOOGLE_uri == "{file_URI}"
    else -> true
}

/** Whether [attachment]'s file is one of this server's files of the user (under [userDir]). */
internal fun isServerFile(attachment: Attachment, userDir: File): Boolean {
    val path = attachment.local_file_path?.takeIf { it.isNotBlank() } ?: return false
    return try {
        val file = File(path).canonicalFile
        file.isFile && file.toPath().startsWith(userDir.canonicalFile.toPath())
    } catch (_: Exception) {
        false
    }
}

/**
 * The attachments rule of a cloud run (CLOUD_RUNS_PLAN.md, "Attachments"): the name of an
 * attachment the server cannot serve, or null when the request can run here.
 *
 * Only user messages and the project attachments are sent to a provider.  Each of their
 * attachments must either need no file ([attachmentNeedsFile]) or be a file of this user on this
 * server (uploaded through the web).  Besides, no attachment anywhere in the request may make
 * the server upload a file that is not the user's own server file (a device path that happens
 * to exist here): its uploaded id would be written into the synced history.
 */
internal fun unservableAttachment(
    provider: String,
    messages: List<Message>,
    projectAttachments: List<Attachment>,
    userDir: File
): String? {
    val sent = messages.filter { it.role == "user" }.flatMap { it.attachments } + projectAttachments
    sent.firstOrNull { attachmentNeedsFile(provider, it) && !isServerFile(it, userDir) }?.let { return it.file_name }

    val uploads = provider == "openai" || provider == "poe" || provider == "google"
    if (uploads) {
        val missingId: (Attachment) -> Boolean = when (provider) {
            "openai" -> { a -> a.file_OPENAI_id == null }
            "poe" -> { a -> a.file_POE_url == null }
            else -> { a -> a.file_GOOGLE_uri == null }
        }
        messages.flatMap { it.attachments }.firstOrNull { a ->
            val path = a.local_file_path
            missingId(a) && path != null && !isServerFile(a, userDir) && runCatching { File(path).exists() }.getOrDefault(true)
        }?.let { return it.file_name }
    }
    return null
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

        val tools = ToolRegistry.getInstance()
        val unknownTools = body.enabledToolIds.filter { tools.getTool(it) == null }
        if (unknownTools.isNotEmpty()) {
            return@post reject(RunRejectionCode.UNSUPPORTED_TOOLS, "Tools not available on the server: ${unknownTools.joinToString()}")
        }

        unservableAttachment(body.provider, body.messages, body.projectAttachments, ctx.storage.baseDir)?.let { name ->
            return@post reject(RunRejectionCode.ATTACHMENTS_UNSUPPORTED, "Attachment \"$name\" is not available on the server")
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
                enabledTools = tools.getEnabledToolsSpecifications(body.enabledToolIds, body.provider),
                thinkingBudget = parseBudget(body.thinkingBudget),
                temperature = body.temperature,
                projectAttachments = body.projectAttachments,
                anchor = ReplyAnchor.forRequest(body.messages)
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
