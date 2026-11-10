package com.example.ApI.server.runs

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.ThoughtsStatus
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.data.repository.ReplyAnchor
import com.example.ApI.server.UserContext
import com.example.ApI.server.streaming.maybeGenerateTitle
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant

// ── Wire model ────────────────────────────────────────────────────────────────

/** Status values of [RunState.status]. */
object RunStatus {
    const val RUNNING = "running"
    const val COMPLETE = "complete"
    const val ERROR = "error"
    const val CANCELLED = "cancelled"
}

/**
 * What a client needs to show a run it did not watch from the start (see CLOUD_RUNS_PLAN.md).
 * [text] / [thinkingText] are what streamed since the last `messages_added` (what is not in the
 * saved chat history yet).
 */
@Serializable
data class RunState(
    val runId: String,
    val chatId: String,
    val provider: String,
    val modelName: String,
    val status: String,
    val text: String = "",
    val thinkingActive: Boolean = false,
    val thinkingText: String = "",
    val error: String? = null,
    val startedAt: Long
)

/** One event of a run: [payload] is the event's own data (without `runId` / `chatId`). */
class RunEvent(val type: String, val runId: String, val chatId: String, val payload: JsonObject) {
    val isTerminal: Boolean get() = type == "complete" || type == "error" || type == "cancelled"

    /** The data object of the user-level stream: the payload plus `runId` and `chatId`. */
    fun dataWithIds(): JsonObject = JsonObject(
        payload + mapOf("runId" to JsonPrimitive(runId), "chatId" to JsonPrimitive(chatId))
    )
}

/** Everything a run needs; built (and validated) by the route that starts it. */
class RunSpec(
    val runId: String,
    val chatId: String,
    val provider: Provider,
    val modelName: String,
    val messages: List<Message>,
    val systemPrompt: String,
    val webSearchEnabled: Boolean,
    val enabledToolIds: List<String>,
    val enabledTools: List<ToolSpecification>,
    val thinkingBudget: ThinkingBudgetValue,
    val temperature: Float?,
    val projectAttachments: List<Attachment>,
    /** Where the run's responses are saved (see [ReplyAnchor]). */
    val anchor: ReplyAnchor
)

/**
 * A subscription to the runs of one user: [snapshot] is the state of every run at the moment of
 * subscribing and [events] carries every event emitted after it (none lost, none duplicated).
 * Must be [close]d.
 */
class RunSubscription internal constructor(
    val snapshot: List<RunState>,
    val events: ReceiveChannel<RunEvent>,
    private val onClose: () -> Unit
) {
    fun close() = onClose()
}

// ── Registry ──────────────────────────────────────────────────────────────────

/**
 * Owns every LLM run of the server.  A run executes in the registry's own coroutine scope —
 * never in the scope of an HTTP call — so a client that disconnects does not stop it; only
 * [cancel] (or [shutdown]) does.  The run saves its tool messages and its reply into the user's
 * chat history itself, and everything it does is published as [RunEvent]s to the user's
 * subscribers and accumulated in its [RunState] for clients that connect later.
 *
 * `messages_added`, `complete` and `cancelled` are emitted only after the save and a bounded
 * flush of the pending sync uploads, so a client told about them can pull right away.
 *
 * Finished runs stay visible for [retentionMs]; a start with a run id seen before never starts a
 * second run.
 */
class RunRegistry(
    private val retentionMs: Long = RETENTION_MS,
    private val flushTimeoutMs: Long = FLUSH_TIMEOUT_MS,
    private val titleTimeoutMs: Long = TITLE_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val RETENTION_MS = 10 * 60_000L
        const val FLUSH_TIMEOUT_MS = 3_000L
        const val TITLE_TIMEOUT_MS = 20_000L
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
        private val EMPTY = JsonObject(emptyMap())
    }

    private class Run(val ctx: UserContext, val spec: RunSpec, var state: RunState) {
        var job: Job? = null

        /** The coroutine ending the run after a cancel (saves the partial reply). */
        var ending: Job? = null

        /** The run's outcome is being saved: a cancel no longer changes it. */
        var finalizing = false

        /** Set by a cancel request (the run's coroutine then leaves the ending to the cancel). */
        @Volatile
        var cancelled = false
        var finishedAt: Long? = null

        /** Serializes the run's saves (tool messages / partial reply on cancel). */
        val saveMutex = Mutex()
    }

    private class UserRuns {
        val runs = LinkedHashMap<String, Run>()
        val subscribers = LinkedHashSet<Channel<RunEvent>>()
    }

    private val log = LoggerFactory.getLogger(RunRegistry::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards [users], every [Run]'s mutable fields and the delivery order of events. */
    private val lock = Any()
    private val users = HashMap<String, UserRuns>()
    private var stopped = false

    // ── Public surface ────────────────────────────────────────────────────────

    /** The state of [runId] of [username], if it is running or finished recently. */
    fun find(username: String, runId: String): RunState? = synchronized(lock) {
        prune(username)
        users[username]?.runs?.get(runId)?.state
    }

    /**
     * Start the run of [spec] for [ctx]'s user, unless its run id is already known.  Returns the
     * run's state and whether this call started it.
     */
    fun start(ctx: UserContext, spec: RunSpec): Pair<RunState, Boolean> {
        val run = synchronized(lock) {
            check(!stopped) { "The server is shutting down" }
            prune(ctx.username)
            val userRuns = users.getOrPut(ctx.username) { UserRuns() }
            userRuns.runs[spec.runId]?.let { return it.state to false }
            val run = Run(
                ctx, spec,
                RunState(
                    runId = spec.runId, chatId = spec.chatId, provider = spec.provider.provider,
                    modelName = spec.modelName, status = RunStatus.RUNNING, startedAt = now()
                )
            )
            userRuns.runs[spec.runId] = run
            publish(run, "run_started", buildJsonObject { put("run", RunJson.encodeToJsonElement(RunState.serializer(), run.state)) })
            // Started under the lock, so a cancel always finds the job
            run.job = scope.launch { execute(run) }
            run
        }
        return run.state to true
    }

    /** Snapshot of [username]'s runs + every later event, atomically. */
    fun subscribe(username: String): RunSubscription = synchronized(lock) {
        prune(username)
        val userRuns = users.getOrPut(username) { UserRuns() }
        val channel = Channel<RunEvent>(Channel.UNLIMITED)
        if (stopped) channel.close() else userRuns.subscribers += channel
        RunSubscription(userRuns.runs.values.map { it.state }, channel) {
            synchronized(lock) { userRuns.subscribers -= channel }
            channel.close()
        }
    }

    /**
     * Stop [runId].  [keepPartial]: save the text streamed so far as the reply (Android's "stop
     * and keep"); otherwise drop it.  Tool messages saved earlier stay either way.  Returns the
     * run's state (a finished run is left as it is), or null when the run is unknown.
     */
    fun cancel(username: String, runId: String, keepPartial: Boolean): RunState? {
        val run = synchronized(lock) {
            val run = users[username]?.runs?.get(runId) ?: return null
            if (run.finishedAt != null || run.finalizing || run.cancelled) return run.state
            run.cancelled = true
            run
        }
        run.job?.cancel(CancellationException("Run cancelled"))
        // Not left to the run's own coroutine: a provider blocked on the network notices the
        // cancellation only with its next chunk
        val ending = scope.launch { finishCancelled(run, keepPartial) }
        return synchronized(lock) {
            run.ending = ending
            run.state
        }
    }

    /**
     * Stop every run, keeping what streamed so far (bounded wait for the saves), and end every
     * subscription.  Called when the application stops.
     */
    fun shutdown(timeoutMs: Long = SHUTDOWN_TIMEOUT_MS) {
        val running = synchronized(lock) {
            stopped = true
            users.values.flatMap { it.runs.values }.filter { it.finishedAt == null }
        }
        val endings = running.mapNotNull { run ->
            val mine = synchronized(lock) {
                if (run.finishedAt != null || run.finalizing || run.cancelled) false
                else { run.cancelled = true; true }
            }
            if (mine) {
                run.job?.cancel(CancellationException("Server shutting down"))
                scope.launch { finishCancelled(run, keepPartial = true) }
            } else {
                // Already saving its outcome or being cancelled: wait for that instead
                synchronized(lock) { run.ending ?: run.job.takeIf { run.finalizing } }
            }
        }
        runBlocking { withTimeoutOrNull(timeoutMs) { endings.joinAll() } }
        synchronized(lock) {
            users.values.forEach { u -> u.subscribers.forEach { it.close() }; u.subscribers.clear() }
        }
        scope.cancel()
        if (running.isNotEmpty()) log.info("Stopped ${running.size} run(s) on shutdown")
    }

    // ── State + events (always under the lock) ────────────────────────────────

    /** Drop [username]'s runs that finished more than [retentionMs] ago.  Under [lock]. */
    private fun prune(username: String) {
        val limit = now() - retentionMs
        users[username]?.runs?.values?.removeIf { run -> run.finishedAt?.let { it < limit } == true }
    }

    /** Deliver an event of [run] to its user's subscribers.  Under [lock]. */
    private fun publish(run: Run, type: String, payload: JsonObject) {
        val event = RunEvent(type, run.spec.runId, run.spec.chatId, payload)
        users[run.ctx.username]?.subscribers?.forEach { it.trySend(event) }
    }

    /** Apply [change] to the state of a run that is not finished and publish the event. */
    private fun emit(run: Run, type: String, payload: JsonObject = EMPTY, change: (RunState) -> RunState = { it }) {
        synchronized(lock) {
            if (run.finishedAt != null) return
            run.state = change(run.state)
            if (run.state.status != RunStatus.RUNNING) run.finishedAt = now()
            publish(run, type, payload)
        }
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    private sealed class Outcome {
        class Complete(val text: String) : Outcome()
        class Failed(val error: String) : Outcome()
    }

    private class Thoughts(val text: String?, val durationSeconds: Float, val status: ThoughtsStatus)

    private suspend fun execute(run: Run) {
        val spec = run.spec
        val repo = run.ctx.repository
        val username = run.ctx.username
        var outcome: Outcome? = null
        // The thoughts of the message being streamed; saved with it (like Android's service)
        var pendingThoughts: Thoughts? = null

        /** A cancelled run takes nothing more from its provider. */
        fun stopIfCancelled() {
            if (run.cancelled) throw CancellationException("Run cancelled")
        }

        val callback = object : StreamingCallback {
            override fun onPartialResponse(text: String) {
                stopIfCancelled()
                emit(run, "partial", buildJsonObject { put("text", text) }) { it.copy(text = it.text + text) }
            }

            override fun onThinkingStarted() {
                stopIfCancelled()
                emit(run, "thinking_started") { it.copy(thinkingActive = true, thinkingText = "") }
            }

            override fun onThinkingPartial(text: String) {
                stopIfCancelled()
                emit(run, "thinking_partial", buildJsonObject { put("text", text) }) {
                    it.copy(thinkingText = it.thinkingText + text)
                }
            }

            override fun onThinkingComplete(thoughts: String?, durationSeconds: Float, status: ThoughtsStatus) {
                stopIfCancelled()
                pendingThoughts = Thoughts(thoughts, durationSeconds, status)
                val payload = buildJsonObject {
                    if (thoughts != null) put("thoughts", thoughts) else put("thoughts", JsonNull)
                    put("durationSeconds", durationSeconds.toDouble())
                    put("status", status.name)
                }
                emit(run, "thinking_complete", payload) { it.copy(thinkingActive = false) }
            }

            override suspend fun onToolCall(toolCall: ToolCall, precedingText: String): ToolExecutionResult {
                currentCoroutineContext().ensureActive()
                stopIfCancelled()
                emit(run, "tool_call", buildJsonObject {
                    put("toolId", toolCall.toolId)
                    put("toolName", ToolRegistry.getInstance().getToolDisplayName(toolCall.toolId))
                    put("parameters", toolCall.parameters)
                })
                val result = ToolRegistry.getInstance().executeTool(toolCall, spec.enabledToolIds)
                emit(run, "tool_result", buildJsonObject {
                    put("toolId", toolCall.toolId)
                    when (result) {
                        is ToolExecutionResult.Success -> {
                            put("success", true)
                            put("output", result.result)
                        }
                        is ToolExecutionResult.Error -> {
                            put("success", false)
                            put("output", result.error)
                        }
                    }
                })
                return result
            }

            override suspend fun onSaveToolMessages(
                toolCallMessage: Message,
                toolResponseMessage: Message,
                precedingText: String
            ) {
                currentCoroutineContext().ensureActive()
                // All or nothing: a cancel waits for these saves (and then finds no text left)
                withContext(NonCancellable) {
                    run.saveMutex.withLock {
                        if (run.cancelled) return@withLock
                        val t = pendingThoughts
                        if (precedingText.isNotBlank() || t != null) {
                            repo.addAnchoredResponse(
                                username, spec.chatId,
                                assistantMessage(spec, precedingText, t), spec.anchor
                            )
                            // They belong to that message, not to the final reply
                            pendingThoughts = null
                        }
                        repo.addAnchoredResponse(username, spec.chatId, toolCallMessage, spec.anchor)
                        repo.addAnchoredResponse(username, spec.chatId, toolResponseMessage, spec.anchor)
                        flushUploads(repo)
                        // What streamed so far is in the saved history now
                        emit(run, "messages_added") { it.copy(text = "", thinkingText = "", thinkingActive = false) }
                    }
                }
                stopIfCancelled()
            }

            override fun onComplete(fullText: String) {
                if (outcome == null) outcome = Outcome.Complete(fullText)
            }

            override fun onError(error: String) {
                if (outcome == null) outcome = Outcome.Failed(error)
            }
        }

        try {
            run.ctx.chatEngine.send(
                provider = spec.provider,
                modelName = spec.modelName,
                messages = spec.messages,
                systemPrompt = spec.systemPrompt,
                username = username,
                chatId = spec.chatId,
                projectAttachments = spec.projectAttachments,
                webSearchEnabled = spec.webSearchEnabled,
                enabledTools = spec.enabledTools,
                thinkingBudget = spec.thinkingBudget,
                temperature = spec.temperature,
                callback = callback
            )
        } catch (ce: CancellationException) {
            if (run.cancelled) return
            if (outcome == null) outcome = Outcome.Failed("Request was interrupted")
        } catch (e: Exception) {
            if (outcome == null) outcome = Outcome.Failed(e.message ?: "Unknown error")
        }

        // From here the outcome is saved whatever happens; a cancel that came first owns the ending
        synchronized(lock) {
            if (run.cancelled) return
            run.finalizing = true
        }
        withContext(NonCancellable) {
            when (val result = outcome) {
                is Outcome.Complete -> finishComplete(run, result.text, pendingThoughts)
                is Outcome.Failed -> finishFailed(run, result.error)
                null -> finishFailed(run, "The request ended without a response")
            }
        }
    }

    private fun assistantMessage(spec: RunSpec, text: String, thoughts: Thoughts?) = Message(
        role = "assistant",
        text = text,
        model = spec.modelName,
        datetime = Instant.now().toString(),
        thoughts = thoughts?.text,
        thinkingDurationSeconds = thoughts?.durationSeconds,
        thoughtsStatus = thoughts?.status ?: ThoughtsStatus.NONE
    )

    private fun finishFailed(run: Run, error: String) {
        emit(run, "error", buildJsonObject { put("error", error) }) {
            it.copy(status = RunStatus.ERROR, error = error, thinkingActive = false)
        }
    }

    private suspend fun finishComplete(run: Run, fullText: String, thoughts: Thoughts?) {
        val spec = run.spec
        val repo = run.ctx.repository
        val message = assistantMessage(spec, fullText, thoughts)
        val saved = try {
            run.saveMutex.withLock { repo.addAnchoredResponse(run.ctx.username, spec.chatId, message, spec.anchor) }
        } catch (e: Exception) {
            log.warn("Saving the reply of run ${spec.runId} failed: ${e.message}")
            return finishFailed(run, "Failed to save response: ${e.message}")
        }
        // null: the chat was deleted on this server meanwhile — the reply is dropped, like on a device
        if (saved != null) {
            generateTitle(run)
            flushUploads(repo)
        }
        val payload = buildJsonObject {
            put("text", fullText)
            put("messageId", if (saved != null) message.id else "")
        }
        emit(run, "complete", payload) { it.copy(status = RunStatus.COMPLETE, thinkingActive = false) }
    }

    private suspend fun finishCancelled(run: Run, keepPartial: Boolean) = withContext(NonCancellable) {
        val spec = run.spec
        val repo = run.ctx.repository
        try {
            run.saveMutex.withLock {
                // Read after any tool-message save in flight: only what is not saved yet
                val text = synchronized(lock) { run.state.text }
                if (keepPartial && text.isNotBlank()) {
                    val saved = repo.addAnchoredResponse(
                        run.ctx.username, spec.chatId, assistantMessage(spec, text, null), spec.anchor
                    )
                    if (saved != null) {
                        generateTitle(run)
                        flushUploads(repo)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Saving the partial reply of run ${spec.runId} failed: ${e.message}")
        }
        emit(run, "cancelled") { it.copy(status = RunStatus.CANCELLED, thinkingActive = false) }
    }

    /** Automatic title generation after a saved reply (bounded: the run's ending waits for it). */
    private suspend fun generateTitle(run: Run) {
        val repo = run.ctx.repository
        try {
            val settings = repo.loadAppSettings().titleGenerationSettings
            val work = scope.launch { maybeGenerateTitle(repo, run.ctx.username, run.spec.chatId, settings) }
            if (withTimeoutOrNull(titleTimeoutMs) { work.join() } == null) {
                log.warn("Title generation for run ${run.spec.runId} still running after ${titleTimeoutMs}ms; not waiting")
            }
        } catch (e: Exception) {
            log.warn("Title generation for run ${run.spec.runId} failed: ${e.message}")
        }
    }

    /**
     * Upload what the run just saved now instead of after the sync debounce, waiting at most
     * [flushTimeoutMs] (the upload itself goes on in the background).
     */
    private suspend fun flushUploads(repo: DataRepository) {
        val work = scope.launch {
            try {
                repo.syncEngine.flushPendingUploads()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log.warn("Flushing sync uploads failed: ${e.message}")
            }
        }
        withTimeoutOrNull(flushTimeoutMs) { work.join() }
    }
}

/** JSON of the run wire protocol. */
val RunJson = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true; isLenient = true }
