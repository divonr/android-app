package com.example.ApI.ui.managers.streaming

import android.widget.Toast
import com.example.ApI.R
import com.example.ApI.data.cloud.CloudCancelResult
import com.example.ApI.data.cloud.CloudRunAction
import com.example.ApI.data.cloud.CloudRunClient
import com.example.ApI.data.cloud.CloudRunConfig
import com.example.ApI.data.cloud.CloudRunRequest
import com.example.ApI.data.cloud.CloudRunTracker
import com.example.ApI.data.cloud.CloudStartResult
import com.example.ApI.data.cloud.ThinkingBudgetWire
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ExecutingToolInfo
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingEvent
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.ui.managers.ManagerDependencies
import com.example.ApI.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cloud runs on the device (CLOUD_RUNS_PLAN.md, "Android client"): when "manage API requests in
 * the cloud" is effective, a request is handed to the web backend instead of to
 * [com.example.ApI.service.StreamingService], and the account's event stream drives the same
 * per-chat streaming state the service's events drive — also for runs another device started.
 *
 * The cloud is the only writer of a cloud run's reply: nothing is saved here and no title is
 * generated here; saved messages arrive through sync (a pull after `messages_added` / the end of
 * a run), and only then is the streamed text taken off the screen.
 *
 * Everything runs on the main thread (the ViewModel's scope), like the other UI managers.
 */
class CloudRunManager(
    private val deps: ManagerDependencies,
    private val getCurrentDateTimeISO: () -> String,
    /** The ordinary handling of the service's streaming events. */
    private val handleStreamingEvent: (StreamingEvent) -> Unit,
    /** Whether the on-device service is running a request of this chat. */
    private val isServiceBusyWith: (chatId: String) -> Boolean
) {
    /** How a request handed to [tryStart] goes on. */
    enum class StartOutcome {
        /** The cloud runs it: no service; the event stream drives the UI. */
        CLOUD,
        /** The cloud did not take it: the device runs it through the service, as always. */
        DEVICE,
        /** The user stopped it while it was being handed over: nothing left to do. */
        STOPPED
    }

    private val client = CloudRunClient(config = { config() })
    private val tracker = CloudRunTracker { chatId -> isChatBusyLocally(chatId) }

    private var foreground = false
    private var streamJob: Job? = null

    /** Chats a fallback request was just handed to the service for (it registers it a moment later). */
    private val recentDeviceStarts = HashMap<String, Long>()

    /** Stop pressed while the start request was in flight: runId → keepPartial. */
    private val pendingStops = HashMap<String, Boolean>()

    /** Chats whose displayed run ended and whose saved messages are being pulled: chatId → runId. */
    private val finishing = HashMap<String, String>()

    /** The run that set the tool indicator, to clear only its own. */
    private var toolRunId: String? = null

    /**
     * Where the overlay text of a chat stands in its run's stream: how much of what streamed
     * since the overlay was (re)set has been taken off because it was saved.  [epoch] changes
     * whenever the overlay is replaced, which voids trims that were computed before.
     */
    private class Overlay(var epoch: Int = 0, var trimmedText: Int = 0, var trimmedThoughts: Int = 0)
    private val overlays = HashMap<String, Overlay>()

    init {
        // The stream follows the setting (toggle, sign-out, key sync off, another account's token)
        deps.scope.launch {
            deps.appSettings.map { config() }.distinctUntilChanged().collect { onConfigChanged() }
        }
    }

    private fun config(): CloudRunConfig? {
        val sync = deps.appSettings.value.remoteSync
        return if (sync.cloudExecutionEffective) CloudRunConfig(sync.cloudBaseUrl.trim(), sync.authToken) else null
    }

    /** Whether requests go to the cloud right now. */
    val isEffective: Boolean get() = config() != null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** The app is resumed: follow the account's runs. */
    fun onForeground() {
        foreground = true
        updateStream()
    }

    /** The app is paused: stop following (the snapshot of the next connection catches up). */
    fun onBackground() {
        foreground = false
        updateStream()
    }

    private fun updateStream() {
        val wanted = foreground && isEffective
        if (wanted && streamJob == null) {
            streamJob = deps.scope.launch {
                client.events().collect { event -> apply(tracker.onEvent(event)) }
            }
        } else if (!wanted) {
            streamJob?.cancel()
            streamJob = null
        }
    }

    /**
     * Turned on or off, or the account changed: what was followed belongs to the old
     * configuration and nothing will report it any more.  Runs that are still going (same
     * account) come back with the snapshot of the new connection.
     */
    private fun onConfigChanged() {
        streamJob?.cancel()
        streamJob = null
        pendingStops.clear()
        apply(tracker.reset())
        updateStream()
    }

    // ── Start (routing) ───────────────────────────────────────────────────────

    /**
     * Hand the request to the cloud.  The caller (the single place requests are handed to the
     * service) already shows the chat as loading; on [StartOutcome.DEVICE] it starts the service
     * exactly as it always did.
     */
    suspend fun tryStart(
        runId: String,
        chatId: String,
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        webSearchEnabled: Boolean,
        projectAttachments: List<Attachment>,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?
    ): StartOutcome {
        // The server pins the reply with the stored path messages (ids, node and variant of every question)
        val storedPath = messages.isNotEmpty() &&
            messages.none { it.role == "user" && (it.nodeId == null || it.variantId == null) }
        if (!storedPath) {
            AppLogger.w("Cloud run $runId not attempted: the request's messages carry no node/variant ids.")
            noteDeviceStart(chatId)
            return StartOutcome.DEVICE
        }

        val request = CloudRunRequest(
            runId = runId,
            chatId = chatId,
            provider = provider.provider,
            modelName = modelName,
            messages = messages.toList(),
            systemPrompt = systemPrompt,
            webSearchEnabled = webSearchEnabled,
            // A tool's specification is named by its id in the tool registry
            enabledToolIds = enabledTools.map { it.name },
            thinkingBudget = ThinkingBudgetWire.encode(thinkingBudget),
            temperature = temperature,
            projectAttachments = projectAttachments.toList()
        )

        tracker.registerLocal(runId, chatId)
        resetOverlay(chatId)
        val result = try {
            client.startRun(request)
        } catch (e: Exception) {
            // Cancellation (the ViewModel is gone): nothing follows
            tracker.abandon(runId)
            pendingStops.remove(runId)
            throw e
        }
        val stopRequested = pendingStops.remove(runId)

        return when (result) {
            is CloudStartResult.Accepted -> {
                tracker.confirmLocal(runId)
                AppLogger.i("API request handed to the cloud (run $runId, model: $modelName, provider: ${provider.provider}).")
                if (stopRequested != null) requestCancel(runId, stopRequested)
                StartOutcome.CLOUD
            }
            is CloudStartResult.Fallback -> {
                AppLogger.w("Cloud did not take run $runId (${result.reason}); sending from the device.")
                // It may be running there after all: stop it so the chat does not get two replies
                if (result.mayHaveStarted) cancelAbandoned(runId)
                if (stopRequested != null) {
                    apply(tracker.forceFinish(runId))
                    tracker.abandon(runId)
                    StartOutcome.STOPPED
                } else {
                    tracker.abandon(runId)
                    noteDeviceStart(chatId)
                    StartOutcome.DEVICE
                }
            }
        }
    }

    /** Best-effort cancel of a run the device answers itself (a few attempts, nothing kept). */
    private fun cancelAbandoned(runId: String) {
        deps.scope.launch {
            repeat(ABANDON_CANCEL_ATTEMPTS) { attempt ->
                if (client.cancelRun(runId, keepPartial = false) != CloudCancelResult.FAILED) return@launch
                delay(ABANDON_CANCEL_RETRY_MS * (attempt + 1))
            }
            AppLogger.w("Could not confirm that cloud run $runId is not running; the chat may get two replies.")
        }
    }

    private fun noteDeviceStart(chatId: String) {
        recentDeviceStarts[chatId] = System.currentTimeMillis()
    }

    private fun isChatBusyLocally(chatId: String): Boolean {
        if (isServiceBusyWith(chatId)) return true
        val startedAt = recentDeviceStarts[chatId] ?: return false
        if (System.currentTimeMillis() - startedAt < DEVICE_START_GRACE_MS) return true
        recentDeviceStarts.remove(chatId)
        return false
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    /**
     * The stop button of [chatId].  True: the chat shows a cloud run and the stop was handled
     * here ([keepPartial]: the cloud saves what streamed so far as the reply, like the device's
     * stop does); false: not a cloud run — the caller stops the on-device request as always.
     */
    fun stop(chatId: String, keepPartial: Boolean): Boolean {
        // Its run is over and the saved reply is on its way: nothing to stop, nothing to save here
        if (finishing.containsKey(chatId)) return true
        val runId = tracker.displayedRun(chatId) ?: return false
        tracker.markStopping(runId)
        if (tracker.isPending(runId)) {
            // The start request is still in flight: decided when it returns
            pendingStops[runId] = keepPartial
        } else {
            requestCancel(runId, keepPartial)
        }
        return true
    }

    private fun requestCancel(runId: String, keepPartial: Boolean) {
        deps.scope.launch {
            when (client.cancelRun(runId, keepPartial)) {
                CloudCancelResult.OK -> {}  // the `cancelled` event (after the save) ends the run
                CloudCancelResult.NOT_FOUND -> apply(tracker.forceFinish(runId))
                CloudCancelResult.FAILED -> {
                    Toast.makeText(deps.context, R.string.cloud_execution_stop_failed, Toast.LENGTH_SHORT).show()
                    // Give the chat back; if the run is still going its next event shows it again
                    apply(tracker.forceFinish(runId))
                }
            }
        }
    }

    // ── Stream actions → UI state ─────────────────────────────────────────────

    private fun apply(actions: List<CloudRunAction>) {
        for (action in actions) {
            try {
                apply(action)
            } catch (e: Exception) {
                AppLogger.e("Cloud run event handling failed: ${e.message}")
            }
        }
    }

    private fun apply(action: CloudRunAction) {
        when (action) {
            is CloudRunAction.Show -> show(action)
            is CloudRunAction.Discovered -> pullForDiscovered()
            is CloudRunAction.Stream -> handleStreamingEvent(action.event)
            is CloudRunAction.ToolStarted -> {
                toolRunId = action.runId
                deps.updateUiState(
                    deps.uiState.value.copy(
                        executingToolCall = ExecutingToolInfo(
                            toolId = action.toolId,
                            toolName = action.toolName,
                            startTime = getCurrentDateTimeISO()
                        )
                    )
                )
            }
            is CloudRunAction.ToolFinished -> clearTool(action.runId)
            is CloudRunAction.MessagesSaved -> {
                clearTool(action.runId)
                if (action.displayed) onMessagesSaved(action.runId, action.chatId) else deps.repository.pullNow()
            }
            is CloudRunAction.Finished -> {
                clearTool(action.runId)
                if (action.displayed) onFinished(action.runId, action.chatId, action.messageId) else deps.repository.pullNow()
            }
            is CloudRunAction.Failed -> {
                clearTool(action.runId)
                if (action.displayed) {
                    overlays.remove(action.chatId)
                    // Shown, and the chat's streaming state cleared, like an on-device error
                    handleStreamingEvent(StreamingEvent.Error(action.runId, action.chatId, action.error))
                }
                // Tool messages saved before the error
                deps.repository.pullNow()
            }
        }
    }

    private fun clearTool(runId: String) {
        if (toolRunId != runId) return
        toolRunId = null
        deps.updateUiState(deps.uiState.value.copy(executingToolCall = null))
    }

    private fun resetOverlay(chatId: String): Overlay {
        val overlay = overlays.getOrPut(chatId) { Overlay() }
        overlay.epoch++
        overlay.trimmedText = 0
        overlay.trimmedThoughts = 0
        return overlay
    }

    /** Show the chat as streaming with exactly the run's state. */
    private fun show(action: CloudRunAction.Show) {
        val chatId = action.chatId
        resetOverlay(chatId)
        val state = deps.uiState.value
        deps.updateUiState(
            state.copy(
                loadingChatIds = state.loadingChatIds + chatId,
                streamingChatIds = state.streamingChatIds + chatId,
                streamingTextByChat = state.streamingTextByChat + (chatId to action.text),
                streamingThoughtsTextByChat = if (action.thinkingText.isNotEmpty()) {
                    state.streamingThoughtsTextByChat + (chatId to action.thinkingText)
                } else {
                    state.streamingThoughtsTextByChat - chatId
                },
                thinkingChatIds = if (action.thinkingActive) state.thinkingChatIds + chatId else state.thinkingChatIds - chatId,
                thinkingStartTimeByChat = when {
                    !action.thinkingActive -> state.thinkingStartTimeByChat - chatId
                    chatId in state.thinkingStartTimeByChat -> state.thinkingStartTimeByChat
                    else -> state.thinkingStartTimeByChat + (chatId to System.currentTimeMillis())
                },
                completedThinkingDurationByChat = if (action.isNew || action.thinkingActive) {
                    state.completedThinkingDurationByChat - chatId
                } else {
                    state.completedThinkingDurationByChat
                }
            )
        )
    }

    /** A run of another device: its chat or its question may not be here yet. */
    private fun pullForDiscovered() {
        deps.repository.pullNow()
        deps.scope.launch {
            // The other device uploads its question after a short debounce
            delay(DISCOVERED_SECOND_PULL_MS)
            deps.repository.pullNow()
        }
    }

    /**
     * The cloud saved what streamed so far (the text before a tool call + the tool messages):
     * once they are here, take that text off the overlay; what streamed since stays.
     */
    private fun onMessagesSaved(runId: String, chatId: String) {
        val overlay = overlays.getOrPut(chatId) { Overlay() }
        val epoch = overlay.epoch
        val state = deps.uiState.value
        // Stream positions (absolute since the overlay was set) up to which everything is saved
        val savedText = overlay.trimmedText + (state.streamingTextByChat[chatId]?.length ?: 0)
        val savedThoughts = overlay.trimmedThoughts + (state.streamingThoughtsTextByChat[chatId]?.length ?: 0)
        // The UI's copy (no disk read here): if it is behind the disk the wait below is just shorter
        val before = messageIdsOf(state.chatHistory.find { it.chat_id == chatId })

        deps.scope.launch {
            awaitPulled(chatId) { chat -> chat != null && messageIdsOf(chat) != before }
            val history = withContext(Dispatchers.IO) { loadHistory() }
            val refreshedChat = history.find { it.chat_id == chatId }
            var updated = deps.uiState.value.copy(chatHistory = history)
            if (refreshedChat != null && updated.currentChat?.chat_id == chatId) {
                updated = updated.copy(currentChat = refreshedChat)
            }
            // Still the same overlay of the same run (not replaced by a snapshot, not ended)
            if (tracker.displayedRun(chatId) == runId && overlay.epoch == epoch && overlays[chatId] === overlay) {
                val dropText = (savedText - overlay.trimmedText).coerceAtLeast(0)
                val dropThoughts = (savedThoughts - overlay.trimmedThoughts).coerceAtLeast(0)
                overlay.trimmedText += dropText
                overlay.trimmedThoughts += dropThoughts
                val text = (updated.streamingTextByChat[chatId] ?: "").drop(dropText)
                val thoughts = (updated.streamingThoughtsTextByChat[chatId] ?: "").drop(dropThoughts)
                val thinking = chatId in updated.thinkingChatIds
                updated = updated.copy(
                    streamingTextByChat = updated.streamingTextByChat + (chatId to text),
                    streamingThoughtsTextByChat = if (thoughts.isNotEmpty()) {
                        updated.streamingThoughtsTextByChat + (chatId to thoughts)
                    } else {
                        updated.streamingThoughtsTextByChat - chatId
                    },
                    // The saved message carries its thoughts now
                    completedThinkingDurationByChat = if (thoughts.isEmpty() && !thinking) {
                        updated.completedThinkingDurationByChat - chatId
                    } else {
                        updated.completedThinkingDurationByChat
                    }
                )
            }
            deps.updateUiState(updated)
        }
    }

    /**
     * The displayed run is over: bring its saved messages, then clear the chat's streaming
     * state in the same update that shows them (so the reply never flickers away).
     */
    private fun onFinished(runId: String, chatId: String, messageId: String?) {
        finishing[chatId] = runId
        deps.scope.launch {
            try {
                awaitPulled(chatId) { chat -> messageId == null || (chat != null && containsMessage(chat, messageId)) }
            } finally {
                if (finishing[chatId] == runId) finishing.remove(chatId)
            }
            val history = withContext(Dispatchers.IO) { loadHistory() }
            val refreshedChat = history.find { it.chat_id == chatId }
            var updated = deps.uiState.value.copy(chatHistory = history)
            if (refreshedChat != null && updated.currentChat?.chat_id == chatId) {
                updated = updated.copy(currentChat = refreshedChat)
            }
            // Unless the chat shows something newer by now (another run, an on-device request)
            if (tracker.displayedRun(chatId) == null && !finishing.containsKey(chatId) && !isChatBusyLocally(chatId)) {
                overlays.remove(chatId)
                updated = updated.copy(
                    loadingChatIds = updated.loadingChatIds - chatId,
                    streamingChatIds = updated.streamingChatIds - chatId,
                    streamingTextByChat = updated.streamingTextByChat - chatId,
                    streamingThoughtsTextByChat = updated.streamingThoughtsTextByChat - chatId,
                    completedThinkingDurationByChat = updated.completedThinkingDurationByChat - chatId,
                    thinkingChatIds = updated.thinkingChatIds - chatId,
                    thinkingStartTimeByChat = updated.thinkingStartTimeByChat - chatId
                )
            }
            deps.updateUiState(updated)
        }
    }

    // ── Sync helpers ──────────────────────────────────────────────────────────

    private fun loadHistory(): List<Chat> =
        deps.repository.loadChatHistory(deps.appSettings.value.current_user).chat_history

    private fun findChat(chatId: String): Chat? = try {
        loadHistory().find { it.chat_id == chatId }
    } catch (_: Exception) {
        null
    }

    private fun messageIdsOf(chat: Chat?): List<String> = chat?.messages?.map { it.id } ?: emptyList()

    /** On the current path or anywhere in the chat's tree (the path may have moved meanwhile). */
    private fun containsMessage(chat: Chat, messageId: String): Boolean =
        chat.messages.any { it.id == messageId } ||
            chat.messageNodes.any { node -> node.variants.any { v -> v.responses.any { it.id == messageId } } }

    /**
     * Pull until [arrived] holds for the local copy of [chatId]; bounded: a few pulls, each with
     * a timeout (the cloud uploads before it reports, so the first one normally does it).
     */
    private suspend fun awaitPulled(chatId: String, arrived: (Chat?) -> Boolean) {
        repeat(PULL_ATTEMPTS) { attempt ->
            // Its own job: the wait is bounded even while the pull is blocked on the network
            val pull = deps.scope.launch(Dispatchers.IO) {
                try {
                    deps.repository.syncEngine.pull()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("Pull after a cloud run event failed: ${e.message}")
                }
            }
            withTimeoutOrNull(PULL_TIMEOUT_MS) { pull.join() }
            val chat = withContext(Dispatchers.IO) { findChat(chatId) }
            if (arrived(chat)) return
            if (attempt < PULL_ATTEMPTS - 1) delay(PULL_RETRY_MS * (attempt + 1))
        }
    }

    private companion object {
        const val PULL_ATTEMPTS = 3
        const val PULL_TIMEOUT_MS = 6_000L
        const val PULL_RETRY_MS = 800L
        const val DISCOVERED_SECOND_PULL_MS = 3_000L
        const val DEVICE_START_GRACE_MS = 5_000L
        const val ABANDON_CANCEL_ATTEMPTS = 3
        const val ABANDON_CANCEL_RETRY_MS = 2_000L
    }
}
