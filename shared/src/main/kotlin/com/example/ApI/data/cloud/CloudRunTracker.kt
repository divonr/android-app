package com.example.ApI.data.cloud

import com.example.ApI.data.model.StreamingEvent

/** What the UI has to do after an event of the cloud-run stream (see [CloudRunTracker]). */
sealed class CloudRunAction {
    /**
     * Show [chatId] as streaming with exactly this state.  [isNew]: the run was not displayed
     * until now (otherwise: its state re-read from a snapshot after a reconnect).
     */
    data class Show(
        val runId: String,
        val chatId: String,
        val text: String,
        val thinkingText: String,
        val thinkingActive: Boolean,
        val isNew: Boolean
    ) : CloudRunAction()

    /** A run this device did not start appeared: its chat / question may not be here yet (pull). */
    data class Discovered(val runId: String, val chatId: String) : CloudRunAction()

    /** An event of the displayed run, for the ordinary streaming-event handling. */
    data class Stream(val event: StreamingEvent) : CloudRunAction()

    data class ToolStarted(val runId: String, val chatId: String, val toolId: String, val toolName: String) : CloudRunAction()
    data class ToolFinished(val runId: String, val chatId: String) : CloudRunAction()

    /**
     * The server saved what streamed so far (text before a tool call + the tool messages):
     * pull, then drop that text from the overlay; the run goes on.
     */
    data class MessagesSaved(val runId: String, val chatId: String, val displayed: Boolean) : CloudRunAction()

    /**
     * The run is over without an error (completed, cancelled, or gone from the server): pull,
     * then clear the overlay.  [messageId]: the saved reply to wait for, when known.
     */
    data class Finished(val runId: String, val chatId: String, val displayed: Boolean, val messageId: String?) : CloudRunAction()

    /** The run failed with [error] (nothing was saved by that). */
    data class Failed(val runId: String, val chatId: String, val displayed: Boolean, val error: String) : CloudRunAction()

    /** The server changed [chatId] after a run ended (its automatic title): pull and refresh the chat list. */
    data class ChatUpdated(val chatId: String) : CloudRunAction()
}

/**
 * Client-side state of the account's cloud runs, fed with the events of the user-level stream
 * ([CloudRunClient.events]) and with the runs this device starts; it answers with the
 * [CloudRunAction]s that keep the streaming UI in step.  No I/O and no UI: unit-testable.
 *
 * Rules:
 *  - A chat displays at most one run.  A run takes its chat's display when it appears, unless
 *    the chat is displaying another run or is busy with an on-device request
 *    ([isChatBusyLocally], e.g. a fallback run); such a run is followed silently and only its
 *    saves are reported (as actions with `displayed = false`), until the display is free.
 *  - A run started here ([registerLocal]) is displayed from the start (the send path already
 *    shows the chat as loading) and is *pending* until the server acknowledged it
 *    ([confirmLocal], or any event of it).
 *  - A snapshot is the truth after a reconnect: a known, acknowledged run that is missing from
 *    it, or finished in it, is finished; a displayed run takes its text from it; an unknown
 *    running run is adopted.
 *  - An event of an unknown run adopts the run (a snapshot can be older than a start request).
 *  - A run given up for an on-device fallback ([abandon]) is ignored for good.
 *
 * Not thread-safe by itself: every method is synchronized on the instance.
 */
class CloudRunTracker(private val isChatBusyLocally: (chatId: String) -> Boolean = { false }) {

    private class Run(val runId: String, val chatId: String, val startedHere: Boolean) {
        var confirmed = false
        var displayed = false
        var stopping = false
        var thinkingActive = false
        val text = StringBuilder()
        val thinkingText = StringBuilder()
    }

    private val runs = LinkedHashMap<String, Run>()
    private val abandoned = LinkedHashSet<String>()

    // ── Runs of this device ───────────────────────────────────────────────────

    /** A start request for [runId] is about to be sent; the chat shows it from now on. */
    @Synchronized
    fun registerLocal(runId: String, chatId: String) {
        runs.values.filter { it.chatId == chatId }.forEach { it.displayed = false }
        runs[runId] = Run(runId, chatId, startedHere = true).also { it.displayed = true }
    }

    /** The server accepted [runId]. */
    @Synchronized
    fun confirmLocal(runId: String) {
        runs[runId]?.confirmed = true
    }

    /** The start of [runId] failed and the device runs the request itself: forget the run for good. */
    @Synchronized
    fun abandon(runId: String) {
        runs.remove(runId)
        abandoned.add(runId)
        while (abandoned.size > MAX_ABANDONED) abandoned.remove(abandoned.first())
    }

    /** The user stopped [runId]: what still streams is no longer shown. */
    @Synchronized
    fun markStopping(runId: String) {
        runs[runId]?.stopping = true
    }

    /**
     * Treat [runId] as over (the server does not know it, or cannot be asked to stop it).  If
     * it is in fact still running, its next event or the next snapshot brings it back.
     */
    @Synchronized
    fun forceFinish(runId: String): List<CloudRunAction> {
        val run = runs[runId] ?: return emptyList()
        return ArrayList<CloudRunAction>().also { finish(run, null, it) }
    }

    /** Forget everything (the feature was turned off): every run is reported as finished. */
    @Synchronized
    fun reset(): List<CloudRunAction> {
        val actions = runs.values.map { CloudRunAction.Finished(it.runId, it.chatId, it.displayed, null) }
        runs.clear()
        abandoned.clear()
        return actions
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** The run [chatId] displays, if any. */
    @Synchronized
    fun displayedRun(chatId: String): String? = runs.values.firstOrNull { it.chatId == chatId && it.displayed }?.runId

    /** Whether [runId] was started here and the server has not acknowledged it yet. */
    @Synchronized
    fun isPending(runId: String): Boolean = runs[runId]?.let { !it.confirmed } ?: false

    @Synchronized
    fun isTracked(runId: String): Boolean = runId in runs

    // ── Stream events ─────────────────────────────────────────────────────────

    @Synchronized
    fun onEvent(event: CloudRunEvent): List<CloudRunAction> {
        val out = ArrayList<CloudRunAction>()
        when (event) {
            is CloudRunEvent.Snapshot -> onSnapshot(event.runs, out)
            CloudRunEvent.Disconnected -> {}
            is CloudRunEvent.RunStarted -> {
                val state = event.run
                if (state.runId !in abandoned && state.isRunning) {
                    val known = runs[state.runId]
                    if (known != null) known.confirmed = true else adopt(state.runId, state.chatId, state, out)
                }
            }
            is CloudRunEvent.Partial -> running(event.runId, event.chatId, out)?.let { run ->
                if (!run.stopping) {
                    run.text.append(event.text)
                    if (run.displayed) out += CloudRunAction.Stream(StreamingEvent.PartialResponse(run.runId, run.chatId, event.text))
                }
            }
            is CloudRunEvent.ThinkingStarted -> running(event.runId, event.chatId, out)?.let { run ->
                if (!run.stopping) {
                    run.thinkingActive = true
                    run.thinkingText.setLength(0)
                    if (run.displayed) out += CloudRunAction.Stream(StreamingEvent.ThinkingStarted(run.runId, run.chatId))
                }
            }
            is CloudRunEvent.ThinkingPartial -> running(event.runId, event.chatId, out)?.let { run ->
                if (!run.stopping) {
                    run.thinkingText.append(event.text)
                    if (run.displayed) out += CloudRunAction.Stream(StreamingEvent.ThinkingPartial(run.runId, run.chatId, event.text))
                }
            }
            is CloudRunEvent.ThinkingComplete -> running(event.runId, event.chatId, out)?.let { run ->
                run.thinkingActive = false
                if (run.displayed) {
                    out += CloudRunAction.Stream(
                        StreamingEvent.ThinkingComplete(run.runId, run.chatId, event.thoughts, event.durationSeconds, event.status)
                    )
                }
            }
            is CloudRunEvent.ToolCall -> running(event.runId, event.chatId, out)?.let { run ->
                if (run.displayed && !run.stopping) out += CloudRunAction.ToolStarted(run.runId, run.chatId, event.toolId, event.toolName)
            }
            is CloudRunEvent.ToolResult -> running(event.runId, event.chatId, out)?.let { run ->
                if (run.displayed) out += CloudRunAction.ToolFinished(run.runId, run.chatId)
            }
            is CloudRunEvent.MessagesAdded -> running(event.runId, event.chatId, out)?.let { run ->
                run.text.setLength(0)
                run.thinkingText.setLength(0)
                run.thinkingActive = false
                out += CloudRunAction.MessagesSaved(run.runId, run.chatId, run.displayed)
            }
            is CloudRunEvent.Complete -> terminal(event.runId, event.chatId, out) { run ->
                finish(run, event.messageId.takeIf { it.isNotBlank() }, out)
            }
            is CloudRunEvent.Cancelled -> terminal(event.runId, event.chatId, out) { run -> finish(run, null, out) }
            is CloudRunEvent.Error -> terminal(event.runId, event.chatId, out) { run -> fail(run, event.error, out) }
            // About the chat, not the run (which is over): never adopts or changes a run. Also
            // reported for a run given up for an on-device fallback: the title is news all the same
            is CloudRunEvent.ChatUpdated -> out += CloudRunAction.ChatUpdated(event.chatId)
        }
        return out
    }

    private fun onSnapshot(snapshot: List<CloudRunState>, out: MutableList<CloudRunAction>) {
        val byId = snapshot.associateBy { it.runId }

        // Known runs first: what ended frees its chat's display for the runs adopted below
        for (run in runs.values.toList()) {
            val state = byId[run.runId]
            when {
                // Not on the server (finished long ago, or a restart). A pending start may simply not have arrived yet.
                state == null -> if (run.confirmed) finish(run, null, out)
                state.status == CloudRunState.STATUS_ERROR -> fail(run, state.error ?: "Unknown error", out)
                !state.isRunning -> finish(run, null, out)
                else -> {
                    run.confirmed = true
                    run.text.setLength(0)
                    run.thinkingText.setLength(0)
                    if (!run.stopping) {
                        run.text.append(state.text)
                        run.thinkingText.append(state.thinkingText)
                    }
                    run.thinkingActive = state.thinkingActive && !run.stopping
                    if (run.displayed) out += show(run, isNew = false)
                }
            }
        }

        for (state in snapshot) {
            if (state.isRunning && state.runId !in runs && state.runId !in abandoned) {
                adopt(state.runId, state.chatId, state, out)
            }
        }

        // A run that was followed silently takes a display that is free by now
        for (run in runs.values) {
            if (!run.displayed) display(run, out)
        }
    }

    /** The tracked run of a non-terminal event; an unknown run is adopted (null: to be ignored). */
    private fun running(runId: String, chatId: String, out: MutableList<CloudRunAction>): Run? {
        if (runId in abandoned) return null
        val run = runs[runId] ?: return adopt(runId, chatId, null, out)
        run.confirmed = true
        return run
    }

    private inline fun terminal(runId: String, chatId: String, out: MutableList<CloudRunAction>, end: (Run) -> Unit) {
        val run = runs[runId]
        if (run != null) {
            end(run)
        } else {
            // Not followed (abandoned, or it began and ended while disconnected): its saves are still news
            abandoned.remove(runId)
            out += CloudRunAction.Finished(runId, chatId, displayed = false, messageId = null)
        }
    }

    /** Start following a run this device did not start (or no longer knows). */
    private fun adopt(runId: String, chatId: String, state: CloudRunState?, out: MutableList<CloudRunAction>): Run {
        val run = Run(runId, chatId, startedHere = false)
        run.confirmed = true
        if (state != null) {
            run.text.append(state.text)
            run.thinkingText.append(state.thinkingText)
            run.thinkingActive = state.thinkingActive
        }
        runs[runId] = run
        out += CloudRunAction.Discovered(runId, chatId)
        display(run, out)
        return run
    }

    /** Give [run] its chat's display if nothing else shows there. */
    private fun display(run: Run, out: MutableList<CloudRunAction>) {
        if (run.displayed || run.stopping) return
        if (runs.values.any { it.chatId == run.chatId && it.displayed }) return
        if (isChatBusyLocally(run.chatId)) return
        run.displayed = true
        out += show(run, isNew = true)
    }

    private fun show(run: Run, isNew: Boolean) = CloudRunAction.Show(
        run.runId, run.chatId, run.text.toString(), run.thinkingText.toString(), run.thinkingActive, isNew
    )

    private fun finish(run: Run, messageId: String?, out: MutableList<CloudRunAction>) {
        runs.remove(run.runId)
        out += CloudRunAction.Finished(run.runId, run.chatId, run.displayed, messageId)
        if (run.displayed) handOver(run.chatId, out)
    }

    private fun fail(run: Run, error: String, out: MutableList<CloudRunAction>) {
        runs.remove(run.runId)
        out += CloudRunAction.Failed(run.runId, run.chatId, run.displayed, error)
        if (run.displayed) handOver(run.chatId, out)
    }

    /** The display of [chatId] is free: another run of the chat, if any, takes it. */
    private fun handOver(chatId: String, out: MutableList<CloudRunAction>) {
        runs.values.firstOrNull { it.chatId == chatId && !it.displayed && !it.stopping }?.let { display(it, out) }
    }

    private companion object {
        const val MAX_ABANDONED = 64
    }
}
