package com.example.ApI.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.ApI.MainActivity
import com.example.ApI.data.model.*
import com.example.ApI.data.network.LLMApiService
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that manages streaming API requests.
 *
 * This service:
 * - Survives activity destruction
 * - Holds HTTP connections for SSE streaming
 * - Shows notification while processing requests
 * - Saves responses directly to repository
 * - Broadcasts events to ViewModel via SharedFlow
 * - Supports multiple concurrent requests to different chats
 */
class StreamingService : Service() {

    companion object {
        private const val TAG = "StreamingService"
        const val NOTIFICATION_CHANNEL_ID = "streaming_channel"
        const val NOTIFICATION_ID = 1001

        // Actions
        const val ACTION_START_REQUEST = "com.example.ApI.START_REQUEST"
        const val ACTION_CANCEL_REQUEST = "com.example.ApI.CANCEL_REQUEST"
        const val ACTION_PROVIDE_TOOL_RESULT = "com.example.ApI.PROVIDE_TOOL_RESULT"

        // Intent extras
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PROVIDER_JSON = "provider_json"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_SYSTEM_PROMPT = "system_prompt"
        const val EXTRA_WEB_SEARCH_ENABLED = "web_search_enabled"
        const val EXTRA_MESSAGES_JSON = "messages_json"
        const val EXTRA_PROJECT_ATTACHMENTS_JSON = "project_attachments_json"
        const val EXTRA_ENABLED_TOOLS_JSON = "enabled_tools_json"
        const val EXTRA_TOOL_RESULT_JSON = "tool_result_json"
        const val EXTRA_THINKING_BUDGET_JSON = "thinking_budget_json"
        const val EXTRA_TEMPERATURE = "temperature"
    }

    // Binder for local binding
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    // Dependencies
    private lateinit var repository: DataRepository
    private lateinit var apiService: LLMApiService

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // Service-level coroutine scope (survives activity death)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Active requests mapped by requestId
    private val activeRequests = ConcurrentHashMap<String, StreamingRequest>()

    // Active jobs mapped by requestId (for cancellation)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // Pending tool results (for tool execution flow)
    private val pendingToolResults = ConcurrentHashMap<String, CompletableDeferred<ToolExecutionResult>>()

    // SharedFlow for broadcasting events to observers (ViewModel)
    private val _streamingEvents = MutableSharedFlow<StreamingEvent>(
        extraBufferCapacity = 100,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val streamingEvents: SharedFlow<StreamingEvent> = _streamingEvents.asSharedFlow()

    // Flow to expose active request count for UI
    private val _activeRequestCount = MutableSharedFlow<Int>(replay = 1)
    val activeRequestCount: SharedFlow<Int> = _activeRequestCount.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "StreamingService created")
        repository = DataRepository(applicationContext)
        apiService = LLMApiService(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START_REQUEST -> handleStartRequest(intent)
            ACTION_CANCEL_REQUEST -> handleCancelRequest(intent)
            ACTION_PROVIDE_TOOL_RESULT -> handleProvideToolResult(intent)
        }

        return START_STICKY // Restart if killed
    }

    private fun handleStartRequest(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: UUID.randomUUID().toString()
        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: return
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: return
        val providerJson = intent.getStringExtra(EXTRA_PROVIDER_JSON) ?: return
        val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: return
        val systemPrompt = intent.getStringExtra(EXTRA_SYSTEM_PROMPT) ?: ""
        val webSearchEnabled = intent.getBooleanExtra(EXTRA_WEB_SEARCH_ENABLED, false)
        val messagesJson = intent.getStringExtra(EXTRA_MESSAGES_JSON) ?: return
        val projectAttachmentsJson = intent.getStringExtra(EXTRA_PROJECT_ATTACHMENTS_JSON) ?: "[]"
        val enabledToolsJson = intent.getStringExtra(EXTRA_ENABLED_TOOLS_JSON) ?: "[]"
        val thinkingBudgetJson = intent.getStringExtra(EXTRA_THINKING_BUDGET_JSON)
        val temperature = if (intent.hasExtra(EXTRA_TEMPERATURE)) {
            intent.getFloatExtra(EXTRA_TEMPERATURE, 1.0f)
        } else {
            null
        }

        Log.d(TAG, "Starting request: requestId=$requestId, chatId=$chatId, temperature=$temperature")

        // Parse data from JSON
        val provider: Provider
        val messages: List<Message>
        val projectAttachments: List<Attachment>
        val enabledTools: List<ToolSpecification>
        val thinkingBudget: ThinkingBudgetValue

        try {
            provider = json.decodeFromString<Provider>(providerJson)
            messages = json.decodeFromString<List<Message>>(messagesJson)
            projectAttachments = json.decodeFromString<List<Attachment>>(projectAttachmentsJson)
            enabledTools = json.decodeFromString<List<ToolSpecification>>(enabledToolsJson)
            thinkingBudget = if (thinkingBudgetJson != null) {
                json.decodeFromString<ThinkingBudgetValue>(thinkingBudgetJson)
            } else {
                ThinkingBudgetValue.None
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse request data", e)
            serviceScope.launch {
                _streamingEvents.emit(StreamingEvent.Error(requestId, chatId, "Failed to parse request: ${e.message}"))
            }
            return
        }

        // Create request tracking object
        val request = StreamingRequest(
            requestId = requestId,
            chatId = chatId,
            username = username,
            providerName = provider.provider,
            modelName = modelName,
            systemPrompt = systemPrompt,
            webSearchEnabled = webSearchEnabled,
            status = RequestStatus.PENDING,
            createdAt = Instant.now().toString()
        )
        activeRequests[requestId] = request

        // Start foreground with notification
        startForeground(NOTIFICATION_ID, buildNotification())
        updateNotification()

        // Emit status change
        serviceScope.launch {
            _streamingEvents.emit(StreamingEvent.StatusChange(requestId, chatId, RequestStatus.STREAMING))
            _activeRequestCount.emit(activeRequests.size)
        }

        // Launch streaming in serviceScope (survives activity death)
        val job = serviceScope.launch {
            executeStreamingRequest(
                requestId = requestId,
                chatId = chatId,
                username = username,
                provider = provider,
                modelName = modelName,
                messages = messages,
                systemPrompt = systemPrompt,
                webSearchEnabled = webSearchEnabled,
                projectAttachments = projectAttachments,
                enabledTools = enabledTools,
                thinkingBudget = thinkingBudget,
                temperature = temperature
            )
        }
        activeJobs[requestId] = job
    }

    private fun handleCancelRequest(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        Log.d(TAG, "Cancelling request: requestId=$requestId")

        activeJobs[requestId]?.cancel()
        activeJobs.remove(requestId)

        val request = activeRequests.remove(requestId)
        if (request != null) {
            serviceScope.launch {
                _streamingEvents.emit(StreamingEvent.StatusChange(requestId, request.chatId, RequestStatus.CANCELLED))
                _activeRequestCount.emit(activeRequests.size)
            }
        }

        checkAndStopSelf()
    }

    private fun handleProvideToolResult(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val toolResultJson = intent.getStringExtra(EXTRA_TOOL_RESULT_JSON) ?: return

        try {
            val result = json.decodeFromString<ToolExecutionResult>(toolResultJson)
            pendingToolResults[requestId]?.complete(result)
            pendingToolResults.remove(requestId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tool result", e)
            pendingToolResults[requestId]?.complete(ToolExecutionResult.Error("Failed to parse tool result"))
            pendingToolResults.remove(requestId)
        }
    }

    /**
     * Provide tool result directly (for bound service usage)
     */
    fun provideToolResult(requestId: String, result: ToolExecutionResult) {
        pendingToolResults[requestId]?.complete(result)
        pendingToolResults.remove(requestId)
    }

    /**
     * Cancel a request directly (for bound service usage)
     */
    fun cancelRequest(requestId: String) {
        activeJobs[requestId]?.cancel()
        activeJobs.remove(requestId)

        val request = activeRequests.remove(requestId)
        if (request != null) {
            serviceScope.launch {
                _streamingEvents.emit(StreamingEvent.StatusChange(requestId, request.chatId, RequestStatus.CANCELLED))
                _activeRequestCount.emit(activeRequests.size)
            }
        }

        checkAndStopSelf()
    }

    /**
     * Stop a request and mark it as completed (user stopped it gracefully).
     * The ViewModel is responsible for saving the accumulated text.
     * This just cancels the HTTP connection and cleans up without emitting any events
     * (since the ViewModel handles the "completion" locally).
     */
    fun stopAndComplete(requestId: String) {
        Log.d(TAG, "Stopping request and marking complete: $requestId")

        // Cancel the job (which will close HTTP connection)
        activeJobs[requestId]?.cancel()
        activeJobs.remove(requestId)

        // Remove from active requests without emitting events
        // (ViewModel handles the UI state change)
        activeRequests.remove(requestId)

        // Update notification and possibly stop service
        serviceScope.launch {
            _activeRequestCount.emit(activeRequests.size)
            updateNotification()
            checkAndStopSelf()
        }
    }

    /**
     * Get all active requests (for UI display)
     */
    fun getActiveRequests(): Map<String, StreamingRequest> = activeRequests.toMap()

    private suspend fun executeStreamingRequest(
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
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
        temperature: Float? = null
    ) {
        Log.d(TAG, "Executing streaming request: requestId=$requestId")

        // Update status to streaming
        activeRequests[requestId] = activeRequests[requestId]?.copy(status = RequestStatus.STREAMING)
            ?: return

        // Create service-level callback
        val callback = object : StreamingCallback {
            private val accumulatedText = StringBuilder()
            private var thoughtsData: Triple<String?, Float?, ThoughtsStatus>? = null

            override fun onPartialResponse(text: String) {
                accumulatedText.append(text)

                // Update accumulated text in request
                activeRequests[requestId] = activeRequests[requestId]?.copy(
                    accumulatedText = accumulatedText.toString()
                ) ?: return

                // Broadcast to ViewModel for UI update
                serviceScope.launch {
                    _streamingEvents.emit(StreamingEvent.PartialResponse(requestId, chatId, text))
                }
            }

            override fun onComplete(fullText: String) {
                Log.d(TAG, "Request completed: requestId=$requestId")

                serviceScope.launch {
                    try {
                        // Save response to repository directly (survives ViewModel death)
                        val assistantMessage = Message(
                            role = "assistant",
                            text = fullText,
                            attachments = emptyList(),
                            model = modelName,
                            datetime = Instant.now().toString(),
                            thoughts = thoughtsData?.first,
                            thinkingDurationSeconds = thoughtsData?.second,
                            thoughtsStatus = thoughtsData?.third ?: ThoughtsStatus.NONE
                        )
                        repository.addResponseToCurrentVariant(username, chatId, assistantMessage)

                        // Broadcast completion
                        _streamingEvents.emit(StreamingEvent.Complete(requestId, chatId, fullText, modelName))
                        _streamingEvents.emit(StreamingEvent.StatusChange(requestId, chatId, RequestStatus.COMPLETED))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save response", e)
                        _streamingEvents.emit(StreamingEvent.Error(requestId, chatId, "Failed to save response: ${e.message}"))
                    } finally {
                        // Cleanup
                        activeRequests.remove(requestId)
                        activeJobs.remove(requestId)
                        _activeRequestCount.emit(activeRequests.size)
                        updateNotification()
                        checkAndStopSelf()
                    }
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "Request error: requestId=$requestId, error=$error")

                serviceScope.launch {
                    _streamingEvents.emit(StreamingEvent.Error(requestId, chatId, error))
                    _streamingEvents.emit(StreamingEvent.StatusChange(requestId, chatId, RequestStatus.FAILED))

                    // Cleanup
                    activeRequests.remove(requestId)
                    activeJobs.remove(requestId)
                    _activeRequestCount.emit(activeRequests.size)
                    updateNotification()
                    checkAndStopSelf()
                }
            }

            override suspend fun onToolCall(toolCall: ToolCall, precedingText: String): ToolExecutionResult {
                Log.d(TAG, "Tool call detected: requestId=$requestId, tool=${toolCall.toolId}")

                // Broadcast event and wait for response via CompletableDeferred
                val resultDeferred = CompletableDeferred<ToolExecutionResult>()
                pendingToolResults[requestId] = resultDeferred

                _streamingEvents.emit(StreamingEvent.ToolCallRequest(requestId, chatId, toolCall, precedingText))

                // Wait for ViewModel to provide result
                return try {
                    withTimeout(300_000) { // 5 minute timeout for tool execution
                        resultDeferred.await()
                    }
                } catch (e: TimeoutCancellationException) {
                    pendingToolResults.remove(requestId)
                    ToolExecutionResult.Error("Tool execution timed out")
                }
            }

            override suspend fun onSaveToolMessages(toolCallMessage: Message, toolResponseMessage: Message, precedingText: String) {
                // Save tool messages to chat history
                try {
                    // First, save preceding text as assistant message if it exists
                    if (precedingText.isNotBlank()) {
                        val precedingMessage = Message(
                            role = "assistant",
                            text = precedingText,
                            attachments = emptyList(),
                            model = modelName,
                            datetime = Instant.now().toString()
                        )
                        repository.addResponseToCurrentVariant(username, chatId, precedingMessage)
                    }
                    repository.addResponseToCurrentVariant(username, chatId, toolCallMessage)
                    repository.addResponseToCurrentVariant(username, chatId, toolResponseMessage)

                    // Notify ViewModel to reload chat history and clear streaming text
                    // This ensures saved messages appear separately from new streaming content
                    _streamingEvents.emit(StreamingEvent.MessagesAdded(requestId, chatId))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save tool messages", e)
                }
            }

            override fun onThinkingStarted() {
                Log.d(TAG, "Thinking started: requestId=$requestId")
                serviceScope.launch {
                    _streamingEvents.emit(StreamingEvent.ThinkingStarted(requestId, chatId))
                }
            }

            override fun onThinkingPartial(text: String) {
                serviceScope.launch {
                    _streamingEvents.emit(StreamingEvent.ThinkingPartial(requestId, chatId, text))
                }
            }

            override fun onThinkingComplete(thoughts: String?, durationSeconds: Float, status: ThoughtsStatus) {
                Log.d(TAG, "Thinking complete: requestId=$requestId, duration=${durationSeconds}s, status=$status")
                // Store thoughts data for saving with the final message
                thoughtsData = Triple(thoughts, durationSeconds, status)
                serviceScope.launch {
                    _streamingEvents.emit(StreamingEvent.ThinkingComplete(requestId, chatId, thoughts, durationSeconds, status))
                }
            }
        }

        try {
            // Execute via Repository (which handles file uploads and delegates to ApiService)
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
        } catch (e: CancellationException) {
            // CancellationException is expected when user stops streaming - don't treat as error
            Log.d(TAG, "Request was cancelled: requestId=$requestId")
            // Just clean up without emitting error
            activeRequests.remove(requestId)
            activeJobs.remove(requestId)
            serviceScope.launch {
                _activeRequestCount.emit(activeRequests.size)
                updateNotification()
                checkAndStopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during streaming request", e)
            callback.onError("Exception: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "API Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when AI responses are being received"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val count = activeRequests.size
        val contentText = if (count == 1) {
            "Processing 1 request"
        } else {
            "Processing $count requests"
        }

        // Intent to open the app when notification is tapped
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Receiving AI response")
            .setContentText(contentText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        if (activeRequests.isNotEmpty()) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun checkAndStopSelf() {
        if (activeRequests.isEmpty()) {
            Log.d(TAG, "No more active requests, stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "StreamingService destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }
}
