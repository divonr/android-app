package com.example.ApI.data.cloud

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.ThoughtsStatus
import com.example.ApI.data.model.TokenUsage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** JSON of the cloud-run wire protocol (CLOUD_RUNS_PLAN.md). */
internal val CloudRunJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * Body of `POST /api/runs`.  [messages] must be the chat's path messages exactly as stored (same
 * ids, user messages with their `nodeId` / `variantId`): the server pins the reply with them.
 * [thinkingBudget] is a [ThinkingBudgetWire] string.
 */
@Serializable
data class CloudRunRequest(
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
     * The group of the chat on this device, when it has one: what the group-conversations tool
     * runs for.  The server's copy of a chat that was just created may not have it yet.
     */
    val groupId: String? = null
)

/** A run as the server reports it (snapshot / `run_started`). */
@Serializable
data class CloudRunState(
    val runId: String,
    val chatId: String,
    val provider: String = "",
    val modelName: String = "",
    val status: String = STATUS_RUNNING,
    /** Text streamed since the last `messages_added`. */
    val text: String = "",
    val thinkingActive: Boolean = false,
    val thinkingText: String = "",
    val error: String? = null,
    val startedAt: Long = 0
) {
    val isRunning: Boolean get() = status == STATUS_RUNNING

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_ERROR = "error"
        const val STATUS_CANCELLED = "cancelled"
    }
}

@Serializable
internal data class CloudRunsSnapshot(val runs: List<CloudRunState> = emptyList())

@Serializable
internal data class CloudRunStartResponse(val runId: String = "", val status: String = "")

@Serializable
internal data class CloudRunRejection(val error: String = "", val code: String = "")

@Serializable
internal data class CloudCancelRequest(val keepPartial: Boolean)

/** One event of the user-level stream (`GET /api/runs/stream`). */
sealed class CloudRunEvent {
    /** First event of every connection: every running run + the runs finished in the last minutes. */
    data class Snapshot(val runs: List<CloudRunState>) : CloudRunEvent()

    /** The connection was lost (a new [Snapshot] follows once it is back). */
    object Disconnected : CloudRunEvent()

    data class RunStarted(val run: CloudRunState) : CloudRunEvent()
    data class Partial(val runId: String, val chatId: String, val text: String) : CloudRunEvent()
    data class ThinkingStarted(val runId: String, val chatId: String) : CloudRunEvent()
    data class ThinkingPartial(val runId: String, val chatId: String, val text: String) : CloudRunEvent()
    data class ThinkingComplete(
        val runId: String,
        val chatId: String,
        val thoughts: String?,
        val durationSeconds: Float,
        val status: ThoughtsStatus
    ) : CloudRunEvent()

    data class ToolCall(val runId: String, val chatId: String, val toolId: String, val toolName: String) : CloudRunEvent()
    data class ToolResult(val runId: String, val chatId: String, val toolId: String, val success: Boolean) : CloudRunEvent()

    /** The text before a tool call and the tool messages are saved (and uploaded). */
    data class MessagesAdded(val runId: String, val chatId: String) : CloudRunEvent()

    /** Terminal. [messageId] is blank when the reply could not be attached (chat deleted on the server). */
    data class Complete(val runId: String, val chatId: String, val text: String, val messageId: String) : CloudRunEvent()

    /** Terminal. */
    data class Error(val runId: String, val chatId: String, val error: String) : CloudRunEvent()

    /** Terminal. */
    data class Cancelled(val runId: String, val chatId: String) : CloudRunEvent()

    /**
     * Not an event of the run's state: after the run [runId] ended, the server changed its
     * chat (the automatic title) and uploaded it.
     */
    data class ChatUpdated(val runId: String, val chatId: String) : CloudRunEvent()

    /**
     * Not an event of the run's state: the token counts of one provider request of the run
     * (each tool follow-up is a request), from providers that report them — what tells whether
     * the provider's prompt cache was used.
     */
    data class Usage(val runId: String, val chatId: String, val usage: TokenUsage) : CloudRunEvent()

    companion object {
        /**
         * The event of an SSE frame, or null for an unknown event type or unusable data (ignored:
         * a newer server may send events this client does not know).
         */
        fun parse(type: String, data: String): CloudRunEvent? {
            val obj = try {
                CloudRunJson.parseToJsonElement(data).jsonObject
            } catch (_: Exception) {
                return null
            }
            return try {
                parse(type, obj)
            } catch (_: Exception) {
                null
            }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

        private fun parse(type: String, obj: JsonObject): CloudRunEvent? {
            if (type == "snapshot") {
                return Snapshot(CloudRunJson.decodeFromJsonElement(CloudRunsSnapshot.serializer(), obj).runs)
            }
            if (type == "run_started") {
                val run = obj["run"] as? JsonObject ?: return null
                return RunStarted(CloudRunJson.decodeFromJsonElement(CloudRunState.serializer(), run))
            }
            val runId = obj.string("runId")?.takeIf { it.isNotBlank() } ?: return null
            val chatId = obj.string("chatId")?.takeIf { it.isNotBlank() } ?: return null
            return when (type) {
                "partial" -> Partial(runId, chatId, obj.string("text") ?: "")
                "thinking_started" -> ThinkingStarted(runId, chatId)
                "thinking_partial" -> ThinkingPartial(runId, chatId, obj.string("text") ?: "")
                "thinking_complete" -> ThinkingComplete(
                    runId, chatId,
                    thoughts = obj.string("thoughts"),
                    durationSeconds = (obj["durationSeconds"] as? JsonPrimitive)?.floatOrNull ?: 0f,
                    status = obj.string("status")
                        ?.let { name -> ThoughtsStatus.entries.firstOrNull { it.name == name } }
                        ?: ThoughtsStatus.NONE
                )
                "tool_call" -> {
                    val toolId = obj.string("toolId") ?: ""
                    ToolCall(runId, chatId, toolId, obj.string("toolName")?.takeIf { it.isNotBlank() } ?: toolId)
                }
                "tool_result" -> ToolResult(
                    runId, chatId, obj.string("toolId") ?: "",
                    success = (obj["success"] as? JsonPrimitive)?.booleanOrNull ?: true
                )
                "messages_added" -> MessagesAdded(runId, chatId)
                "complete" -> Complete(runId, chatId, obj.string("text") ?: "", obj.string("messageId") ?: "")
                "error" -> Error(runId, chatId, obj.string("error") ?: "Unknown error")
                "cancelled" -> Cancelled(runId, chatId)
                "chat_updated" -> ChatUpdated(runId, chatId)
                "usage" -> Usage(
                    runId, chatId,
                    TokenUsage(obj.int("promptTokens"), obj.int("cachedTokens"), obj.int("outputTokens"), obj.int("thoughtsTokens"))
                )
                else -> null
            }
        }
    }
}

/**
 * Incremental parser of a `text/event-stream` body: feed it the lines of the body; it calls
 * [onFrame] with (event type, data) at the blank line that ends each frame.  Comment lines
 * (`: ping`) are skipped; several `data:` lines of one frame are joined with a newline.
 */
class SseParser(private val onFrame: (type: String, data: String) -> Unit) {
    private var type: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feedLine(line: String) {
        when {
            line.isEmpty() -> {
                if (hasData) onFrame(type ?: "message", data.toString())
                type = null
                data.setLength(0)
                hasData = false
            }
            line.startsWith(":") -> {}  // comment (keep-alive ping)
            else -> {
                val colon = line.indexOf(':')
                val field = if (colon < 0) line else line.substring(0, colon)
                val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
                when (field) {
                    "event" -> type = value
                    "data" -> {
                        if (hasData) data.append('\n')
                        data.append(value)
                        hasData = true
                    }
                }
            }
        }
    }
}
