package com.example.ApI.data.network.providers

import com.example.ApI.data.model.*
import com.example.ApI.data.network.streaming.DataOnlyStreamParser
import com.example.ApI.data.network.streaming.StreamAction
import com.example.ApI.data.network.streaming.StreamEventHandler
import com.example.ApI.data.network.streaming.StreamResult
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** Which Google backend a [GoogleProvider] instance talks to. */
enum class GooglePlatform {
    /** generativelanguage.googleapis.com, keyed by `key=` and with the Files API. */
    AI_STUDIO,

    /** Gemini Enterprise Agent Platform (formerly Vertex AI), project-scoped, `x-goog-api-key`. */
    AGENT_PLATFORM
}

/**
 * Google Gemini API provider implementation.
 * Handles streaming responses, tool calling, and thinking support.
 *
 * The same wire protocol serves both [GooglePlatform]s. Agent Platform differs in auth, in
 * attachments (no Files API: inline base64 only), in being stricter about a few request
 * shapes, and in keeping the start of the request identical from one request to the next (its
 * implicit prompt cache is a prefix match over system instruction, tools, then contents);
 * those differences are gated on [isAgentPlatform] and leave AI Studio untouched.
 */
class GoogleProvider(
    private val platform: GooglePlatform = GooglePlatform.AI_STUDIO
) : BaseProvider() {

    private val isAgentPlatform: Boolean get() = platform == GooglePlatform.AGENT_PLATFORM
    private val toolCallProviderName: String get() = if (isAgentPlatform) VERTEX_PROVIDER else "google"
    private val platformLabel: String get() = if (isAgentPlatform) "Google Agent Platform" else "Google"

    // The content role function responses are replayed with on each platform
    private val toolResponseRole: String get() = if (isAgentPlatform) "user" else "function"

    override suspend fun sendMessage(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?,
        callback: StreamingCallback
    ) {
        try {
            val streamingResponse = makeStreamingRequest(
                provider, modelName, messages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, callback, temperature = temperature
            )

            when (streamingResponse) {
                is ProviderStreamingResult.TextComplete -> {
                    AppLogger.d("[TOOL_CALL_DEBUG] Google Streaming: Text response complete")
                    callback.onComplete(streamingResponse.fullText)
                }
                is ProviderStreamingResult.ToolCallDetected -> {
                    handleToolCall(
                        provider, modelName, messages, systemPrompt, apiKey,
                        webSearchEnabled, enabledTools, thinkingBudget, temperature,
                        streamingResponse, callback
                    )
                }
                is ProviderStreamingResult.Error -> {
                    // Already called callback.onError
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to send Google streaming message: ${e.message}")
        }
    }

    private suspend fun handleToolCall(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?,
        initialResponse: ProviderStreamingResult.ToolCallDetected,
        callback: StreamingCallback
    ) {
        AppLogger.d("[TOOL_CALL_DEBUG] Google Streaming: Tool call detected - ${initialResponse.toolCall.toolId}")

        var followUpFailed = false
        val finalText = handleToolCallChain(
            initialToolCall = initialResponse.toolCall,
            initialPrecedingText = initialResponse.precedingText,
            initialAdditionalToolCalls = initialResponse.additionalToolCalls,
            messages = messages,
            modelName = modelName,
            callback = callback
        ) { updatedMessages ->
            makeStreamingRequest(
                provider, modelName, updatedMessages, systemPrompt, apiKey,
                webSearchEnabled, enabledTools, thinkingBudget, callback,
                temperature = temperature
            ).also { if (it is ProviderStreamingResult.Error) followUpFailed = true }
        }

        if (finalText != null) {
            callback.onComplete(finalText)
        } else if (!followUpFailed) {
            // Max depth exceeded; a failed follow-up was already reported, and this would replace it
            callback.onError("Maximum tool call depth ($MAX_TOOL_DEPTH) exceeded")
        }
    }

    private suspend fun makeStreamingRequest(
        provider: Provider,
        modelName: String,
        messages: List<Message>,
        systemPrompt: String,
        apiKey: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue = ThinkingBudgetValue.None,
        callback: StreamingCallback,
        maxToolDepth: Int = MAX_TOOL_DEPTH,
        currentDepth: Int = 0,
        temperature: Float? = null
    ): ProviderStreamingResult = withContext(Dispatchers.IO) {
        try {
            // Build request URL - replace placeholder and change to streaming endpoint
            val baseUrl = provider.request.base_url.replace("{model_name}", modelName)
                .replace(":generateContent", ":streamGenerateContent")
            if (isAgentPlatform && baseUrl.contains(PROJECT_ID_PLACEHOLDER)) {
                val message = "Google Agent Platform needs a Cloud project ID. In API Keys, delete this key and add it again with the Project ID field filled in."
                callback.onError(message)
                return@withContext ProviderStreamingResult.Error(message)
            }

            // Agent Platform authenticates by header so the key stays out of URLs and logs.
            val url = URL(if (isAgentPlatform) "$baseUrl?alt=sse" else "$baseUrl?alt=sse&key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = provider.request.request_type
            connection.setRequestProperty("Content-Type", "application/json")
            if (isAgentPlatform) {
                connection.setRequestProperty("x-goog-api-key", apiKey)
            }
            connection.doOutput = true

            if (isAgentPlatform) {
                val contents = buildContents(messages, systemPrompt = "")
                val requestBody = buildAgentPlatformRequestBody(
                    modelName, contents, systemPrompt, webSearchEnabled, enabledTools, thinkingBudget, temperature
                )
                writeAgentPlatformBody(connection, baseUrl, contents, requestBody)
            } else {
                val contents = buildContents(messages, systemPrompt)
                val requestBody = buildRequestBody(modelName, contents, webSearchEnabled, enabledTools, thinkingBudget, temperature)
                val requestBodyJson = json.encodeToString(requestBody)

                // Log request details (API key is in URL, so we mask the full URL)
                logApiRequestDetails(
                    baseUrl = baseUrl.substringBefore("?"),
                    headers = mapOf("Content-Type" to "application/json"),
                    body = requestBodyJson
                )

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(requestBodyJson)
                writer.flush()
                writer.close()
            }

            val responseCode = connection.responseCode
            logApiResponse(responseCode)

            if (responseCode >= 400) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                logApiError(errorBody)
                callback.onError("HTTP $responseCode: $errorBody")
                return@withContext ProviderStreamingResult.Error("HTTP $responseCode: $errorBody")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            parseStreamingResponse(reader, connection, modelName, callback)
        } catch (e: Exception) {
            callback.onError("Failed to make Google streaming request: ${e.message}")
            ProviderStreamingResult.Error("Failed to make Google streaming request: ${e.message}")
        }
    }

    /**
     * Inner class to handle Google SSE events.
     * Implements StreamEventHandler for use with DataOnlyStreamParser.
     */
    private inner class GoogleEventHandler(
        private val callback: StreamingCallback
    ) : StreamEventHandler {

        // Response accumulation
        val fullResponse = StringBuilder()

        // Tool call tracking
        var detectedToolCall: ToolCall? = null

        // Every functionCall part in order; on Gemini 3+ the first is the signed one
        val detectedToolCalls = mutableListOf<ToolCall>()

        // Last finish reason seen, reported when the response comes back empty
        var lastFinishReason: String? = null

        // Thinking state tracking
        var isInThinkingPhase = false
        var thinkingStartTime: Long = 0
        val thoughtsBuilder = StringBuilder()

        // Error tracking
        var errorMessage: String? = null

        // Last usageMetadata with token counts: the final chunk's, earlier ones only name the traffic type
        var lastUsage: JsonObject? = null

        init {
            AppLogger.d("[TOOL_CALL_DEBUG] Starting to read Google stream...")
        }

        override fun onEvent(eventType: String?, data: JsonObject): StreamAction {
            // Read first: the chunk that ends a response in an error carries the counts too
            (data["usageMetadata"] as? JsonObject)?.takeIf { "promptTokenCount" in it }?.let { lastUsage = it }

            // Check for error
            val error = data["error"]?.jsonObject
            if (error != null) {
                errorMessage = error["message"]?.jsonPrimitive?.content ?: "Unknown Google API streaming error"
                callback.onError("Google API streaming error: $errorMessage")
                return StreamAction.Error("Google API streaming error: $errorMessage")
            }

            val blockReason = (data["promptFeedback"] as? JsonObject)
                ?.get("blockReason")?.jsonPrimitive?.contentOrNull
            if (blockReason != null) {
                val detail = (data["promptFeedback"] as? JsonObject)
                    ?.get("blockReasonMessage")?.jsonPrimitive?.contentOrNull
                errorMessage = "$platformLabel blocked the prompt: $blockReason" +
                    (detail?.let { " ($it)" } ?: "")
                callback.onError(errorMessage!!)
                return StreamAction.Error(errorMessage!!)
            }

            val candidates = data["candidates"]?.jsonArray

            if (candidates != null && candidates.isNotEmpty()) {
                val firstCandidate = candidates[0].jsonObject

                // Check for safety blocks
                val finishReason = firstCandidate["finishReason"]?.jsonPrimitive?.contentOrNull
                if (finishReason != null) lastFinishReason = finishReason
                if (!isAgentPlatform && finishReason != null && finishReason != "STOP") {
                    errorMessage = "Google API streaming blocked due to: $finishReason"
                    callback.onError(errorMessage!!)
                    return StreamAction.Error(errorMessage!!)
                }

                val content = firstCandidate["content"]?.jsonObject
                val parts = content?.get("parts")?.jsonArray

                if (parts != null && parts.isNotEmpty()) {
                    for (partElement in parts) {
                        val part = partElement.jsonObject

                        val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                        val text = part["text"]?.jsonPrimitive?.contentOrNull
                        val functionCall = part["functionCall"]?.jsonObject

                        when {
                            isThought && text != null -> {
                                if (!isInThinkingPhase) {
                                    isInThinkingPhase = true
                                    thinkingStartTime = System.currentTimeMillis()
                                    callback.onThinkingStarted()
                                }
                                thoughtsBuilder.append(text)
                                callback.onThinkingPartial(text)
                            }

                            functionCall != null -> {
                                AppLogger.d("[TOOL_CALL_DEBUG] Google Streaming: Found functionCall part: $functionCall")
                                val name = functionCall["name"]?.jsonPrimitive?.content
                                // A call to a parameterless function may omit args altogether
                                val args = (functionCall["args"] as? JsonObject) ?: buildJsonObject {}
                                // Capture thoughtSignature - required for Gemini 3+ function calling
                                val thoughtSignature = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull

                                if (name != null) {
                                    val toolCall = ToolCall(
                                        // Parallel calls can share a millisecond; replay matches by this id
                                        id = "google_${System.currentTimeMillis()}_${detectedToolCalls.size}",
                                        toolId = com.example.ApI.tools.ToolRegistry.toolIdForModelName(name),
                                        parameters = args,
                                        provider = toolCallProviderName,
                                        thoughtSignature = thoughtSignature
                                    )
                                    // Only the first parallel call is signed, so it must stay first
                                    detectedToolCalls.add(toolCall)
                                    detectedToolCall = detectedToolCalls.first()
                                    AppLogger.d("[TOOL_CALL_DEBUG] Google Streaming: Detected tool call in chunk, thoughtSignature=${thoughtSignature != null}")
                                }
                            }

                            text != null -> {
                                // Transition from thinking to response
                                if (isInThinkingPhase) {
                                    completeThinkingPhase(callback, thinkingStartTime, thoughtsBuilder)
                                    isInThinkingPhase = false
                                }

                                AppLogger.d("[TOOL_CALL_DEBUG] Google Streaming: Found text part: '$text'")
                                fullResponse.append(text)
                                callback.onPartialResponse(text)
                            }
                        }
                    }
                }

                // Agent Platform: read this chunk's parts first, its final text must not be lost
                if (isAgentPlatform && finishReason != null) {
                    if (finishReason != "STOP" && finishReason != "FINISH_REASON_UNSPECIFIED") {
                        if (finishReason == "MAX_TOKENS" && (fullResponse.isNotEmpty() || detectedToolCall != null)) {
                            AppLogger.w("[GoogleProvider] Response truncated at the output token limit")
                            return StreamAction.Stop
                        }
                        val detail = firstCandidate["finishMessage"]?.jsonPrimitive?.contentOrNull
                        errorMessage = "Google Agent Platform stopped the response: $finishReason" +
                            (detail?.let { " ($it)" } ?: "")
                        callback.onError(errorMessage!!)
                        return StreamAction.Error(errorMessage!!)
                    }
                }
            }

            return StreamAction.Continue
        }

        override fun onStreamEnd() {
            // Stream ended - complete thinking phase if still in progress
            if (isInThinkingPhase) {
                completeThinkingPhase(callback, thinkingStartTime, thoughtsBuilder)
                isInThinkingPhase = false
            }
        }

        override fun onParseError(line: String, error: Exception) {
            println("[DEBUG] Error parsing Google streaming chunk: ${error.message}")
            println("[DEBUG] Problematic chunk: $line")
        }

        /**
         * Build the final result based on accumulated state.
         */
        fun getResult(): ProviderStreamingResult {
            // Check for error first
            errorMessage?.let {
                return ProviderStreamingResult.Error(it)
            }

            val thoughtsContent = thoughtsBuilder.toString().takeIf { it.isNotEmpty() }
            val thinkingDuration = if (thoughtsBuilder.isNotEmpty()) {
                thinkingStartTime.takeIf { it > 0 }?.let { (System.currentTimeMillis() - it) / 1000f }
            } else null
            val thoughtsStatus = when {
                thoughtsContent != null -> ThoughtsStatus.PRESENT
                else -> ThoughtsStatus.NONE
            }

            return when {
                detectedToolCall != null -> ProviderStreamingResult.ToolCallDetected(
                    toolCall = detectedToolCall!!,
                    precedingText = fullResponse.toString(),
                    thoughts = thoughtsContent,
                    thinkingDurationSeconds = thinkingDuration,
                    thoughtsStatus = thoughtsStatus,
                    additionalToolCalls = detectedToolCalls.drop(1)
                )
                fullResponse.isNotEmpty() -> ProviderStreamingResult.TextComplete(
                    fullText = fullResponse.toString(),
                    thoughts = thoughtsContent,
                    thinkingDurationSeconds = thinkingDuration,
                    thoughtsStatus = thoughtsStatus
                )
                else -> {
                    // Must reach the UI: nothing else ends the request, and it would spin forever
                    val message = "$platformLabel returned no content" +
                        (lastFinishReason?.let { " (finish reason: $it)" } ?: "")
                    callback.onError(message)
                    ProviderStreamingResult.Error(message)
                }
            }
        }
    }

    private fun parseStreamingResponse(
        reader: BufferedReader,
        connection: HttpURLConnection,
        modelName: String,
        callback: StreamingCallback
    ): ProviderStreamingResult {
        val parser = DataOnlyStreamParser(
            json = json,
            eventTypeField = null,  // Structure-based parsing (no event type field)
            doneMarker = "[DONE]",  // Not typically used by Google
            skipKeepalives = false,
            logTag = "GoogleProvider"
        )
        val handler = GoogleEventHandler(callback)

        val result = try {
            parser.parse(reader, handler)
        } finally {
            // Also when the connection drops after the counts arrived
            reportTokenUsage(modelName, handler.lastUsage, callback)
        }

        reader.close()
        connection.disconnect()

        // If parser returned an error (from StreamAction.Error), use that
        if (result is StreamResult.Error) {
            return ProviderStreamingResult.Error(result.message)
        }

        return handler.getResult()
    }

    /**
     * Agent Platform caches prompts implicitly, with no request field: the cached count in the
     * reply is the only sign that the request prefix was reused, so it is logged and handed on.
     */
    private fun reportTokenUsage(modelName: String, usage: JsonObject?, callback: StreamingCallback) {
        if (!isAgentPlatform || usage == null) return
        try {
            fun count(field: String) = (usage[field] as? JsonPrimitive)?.intOrNull ?: 0
            val counts = TokenUsage(
                promptTokens = count("promptTokenCount"),
                // Absent, not 0, when nothing was served from the cache
                cachedTokens = count("cachedContentTokenCount"),
                outputTokens = count("candidatesTokenCount"),
                thoughtsTokens = count("thoughtsTokenCount")
            )
            AppLogger.i("Vertex token usage: model=$modelName, ${counts.describe()}")
            callback.onUsage(counts)
        } catch (e: Exception) {
            // Bookkeeping only: it must not fail a request that otherwise worked
            AppLogger.w("[GoogleProvider] Could not report token usage: ${e.message}")
        }
    }

    private fun buildContents(messages: List<Message>, systemPrompt: String): List<JsonObject> {
        val contents = mutableListOf<JsonObject>()
        val inlineAttachments = if (isAgentPlatform) selectInlineAttachments(messages) else emptySet()

        // Add system instruction if provided
        if (systemPrompt.isNotBlank()) {
            contents.add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("text", systemPrompt)
                    })
                })
            })
        }

        var i = 0
        while (i < messages.size) {
            val message = messages[i]

            when (message.role) {
                "user" -> {
                    val parts = mutableListOf<JsonElement>()

                    // Agent Platform: the files go first, so that editing the end of the prompt
                    // leaves them inside the prefix its prompt cache matches on
                    if (!isAgentPlatform && message.text.isNotBlank()) {
                        parts.add(buildJsonObject {
                            put("text", message.text)
                        })
                    }

                    message.attachments.forEach { attachment ->
                        if (isAgentPlatform) {
                            parts.addAll(buildAgentPlatformAttachmentParts(attachment, inlineAttachments))
                        } else if (!attachment.file_GOOGLE_uri.isNullOrBlank() && attachment.file_GOOGLE_uri != "{file_URI}") {
                            parts.add(buildJsonObject {
                                put("file_data", buildJsonObject {
                                    put("mime_type", attachment.mime_type)
                                    put("file_uri", attachment.file_GOOGLE_uri)
                                })
                            })
                        }
                    }

                    if (isAgentPlatform && message.text.isNotBlank()) {
                        parts.add(buildJsonObject {
                            put("text", message.text)
                        })
                    }

                    if (parts.isNotEmpty()) {
                        contents.add(buildJsonObject {
                            put("role", "user")
                            put("parts", JsonArray(parts))
                        })
                    }
                    i++
                }
                "assistant" -> {
                    // Check if next message is tool_call - combine them into one model turn
                    val nextMessage = messages.getOrNull(i + 1)
                    if (nextMessage?.role == "tool_call") {
                        i = appendToolCallGroup(contents, messages, i + 1, message.text)
                    } else {
                        // Regular assistant message
                        if (message.text.isNotBlank()) {
                            contents.add(buildJsonObject {
                                put("role", "model")
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", message.text)
                                    })
                                })
                            })
                        }
                        i++
                    }
                }
                "tool_call" -> {
                    // Standalone tool_call (old format or no preceding assistant)
                    i = appendToolCallGroup(contents, messages, i, precedingText = "")
                }
                "tool_response" -> {
                    contents.add(buildJsonObject {
                        put("role", toolResponseRole)
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    put("name", message.toolResponseCallId?.let { findToolNameByCallId(messages, it) } ?: "unknown")
                                    put("response", buildJsonObject {
                                        put("result", toolResponseResult(message))
                                    })
                                })
                            })
                        })
                    })
                    i++
                }
                else -> i++
            }
        }

        return contents
    }

    /**
     * The output a function response is replayed with. Agent Platform: a list sent by the web
     * keeps a tool result only as the message's text, and the files a run generated are named
     * there (the code sandbox's result lists them).
     */
    private fun toolResponseResult(message: Message): String =
        message.toolResponseOutput ?: message.text.takeIf { isAgentPlatform } ?: ""

    private fun findToolNameByCallId(messages: List<Message>, callId: String): String? {
        return messages.find { it.role == "tool_call" && it.toolCallId == callId }
            ?.toolCall?.toolId?.let { com.example.ApI.tools.ToolRegistry.modelNameForToolId(it) }
    }

    /**
     * Appends one model step and its function responses, starting at the tool_call at [callIndex].
     *
     * A parallel step must be replayed as all calls followed by all responses: interleaving them
     * is a 400 on Gemini 3+. Calls are stored as (tool_call, tool_response) pairs, and only the
     * first call of a parallel step is signed, so an unsigned call after a signed step is a
     * sibling of that step. Unsigned calls after an unsigned one (Gemini 2.5, or a chat started
     * on another provider) are left as separate steps.
     *
     * @return the index of the first message after this group
     */
    private fun appendToolCallGroup(
        contents: MutableList<JsonObject>,
        messages: List<Message>,
        callIndex: Int,
        precedingText: String
    ): Int {
        val calls = mutableListOf(messages[callIndex])
        val responses = mutableListOf<Message>()
        var next = callIndex + 1
        if (messages.getOrNull(next)?.role == "tool_response") {
            responses.add(messages[next])
            next++
        }

        val firstCallIsSigned = !calls.first().toolCall?.thoughtSignature.isNullOrBlank()
        while (firstCallIsSigned &&
            messages.getOrNull(next)?.role == "tool_call" &&
            messages[next].toolCall?.thoughtSignature.isNullOrBlank() &&
            messages.getOrNull(next + 1)?.role == "tool_response"
        ) {
            calls.add(messages[next])
            responses.add(messages[next + 1])
            next += 2
        }

        contents.add(buildJsonObject {
            put("role", "model")
            put("parts", buildJsonArray {
                if (precedingText.isNotBlank()) {
                    add(buildJsonObject { put("text", precedingText) })
                }
                calls.forEach { call ->
                    add(buildJsonObject {
                        put("functionCall", buildJsonObject {
                            put("name", call.toolCall?.toolId?.let { com.example.ApI.tools.ToolRegistry.modelNameForToolId(it) } ?: "unknown")
                            put("args", call.toolCall?.parameters ?: buildJsonObject {})
                        })
                        call.toolCall?.thoughtSignature?.let { signature ->
                            put("thoughtSignature", signature)
                        }
                    })
                }
            })
        })

        if (responses.isNotEmpty()) {
            contents.add(buildJsonObject {
                put("role", toolResponseRole)
                put("parts", buildJsonArray {
                    responses.forEach { response ->
                        add(buildJsonObject {
                            put("functionResponse", buildJsonObject {
                                put("name", response.toolResponseCallId?.let { findToolNameByCallId(messages, it) } ?: "unknown")
                                put("response", buildJsonObject {
                                    put("result", toolResponseResult(response))
                                })
                            })
                        })
                    }
                })
            })
        }

        return next
    }

    /**
     * Chooses which attachments are sent inline, newest first, so one oversized history can't
     * push out what the user just attached. Agent Platform has no Files API.
     */
    private fun selectInlineAttachments(messages: List<Message>): Set<Attachment> {
        val selected = HashSet<Attachment>()
        var total = 0L
        for (message in messages.asReversed()) {
            if (message.role != "user") continue
            for (attachment in message.attachments) {
                val size = attachment.local_file_path?.let { File(it) }
                    ?.takeIf { it.exists() }?.length() ?: continue
                if (size > MAX_INLINE_ATTACHMENT_BYTES || total + size > MAX_INLINE_TOTAL_BYTES) continue
                selected.add(attachment)
                total += size
            }
        }
        return selected
    }

    /**
     * The file's name and its inlineData part, or a text note telling the model why this file
     * isn't there. The name is what the code sandbox has the file under in its input directory; saying it here, not in the
     * tool's description, keeps it the same on every later request.
     */
    private fun buildAgentPlatformAttachmentParts(attachment: Attachment, inlineAttachments: Set<Attachment>): List<JsonObject> {
        fun note(reason: String) = listOf(buildJsonObject {
            put("text", "[Attachment \"${attachment.file_name}\" was not sent: $reason]")
        })

        val mimeType = agentPlatformMimeType(attachment.mime_type)
            ?: return note("${attachment.mime_type} is not a supported file type")
        val file = attachment.local_file_path?.let { File(it) }?.takeIf { it.exists() }
            ?: return note("the file is not available on this device")
        if (attachment !in inlineAttachments) {
            return note("it is too large to send inline")
        }
        val base64 = try {
            readFileAsBase64(file.path)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return note("the file could not be read")

        return listOf(
            buildJsonObject { put("text", "[Attached file: \"${attachment.file_name}\"]") },
            buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", mimeType)
                    put("data", base64)
                })
            }
        )
    }

    /** The MIME type to send for an attachment, or null when Agent Platform can't accept it. */
    private fun agentPlatformMimeType(mimeType: String): String? {
        val lower = mimeType.lowercase()
        return when {
            lower == "image/jpg" -> "image/jpeg"
            lower in AGENT_PLATFORM_IMAGE_TYPES -> lower
            lower == "application/pdf" -> lower
            lower.startsWith("audio/") || lower.startsWith("video/") -> lower
            // text/plain is the only text type accepted; send other text formats as that
            lower.startsWith("text/") || lower in AGENT_PLATFORM_TEXT_LIKE_TYPES -> "text/plain"
            else -> null
        }
    }

    /**
     * Sends the request. Bodies carrying inline files are streamed (HttpURLConnection otherwise
     * buffers the whole upload in memory) and never logged, since the base64 could be huge.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun writeAgentPlatformBody(
        connection: HttpURLConnection,
        baseUrl: String,
        contents: List<JsonObject>,
        requestBody: JsonObject
    ) {
        val headers = mapOf("Content-Type" to "application/json")
        val hasInlineFiles = contents.any { content ->
            content["parts"]?.jsonArray.orEmpty().any { "inlineData" in it.jsonObject }
        }

        if (hasInlineFiles) {
            logApiRequestDetails(baseUrl, headers, body = "<${contents.size} turns with inline attachments, body not logged>")
            connection.setChunkedStreamingMode(0)
            connection.outputStream.use { json.encodeToStream(JsonObject.serializer(), requestBody, it) }
        } else {
            val requestBodyJson = json.encodeToString(requestBody)
            logApiRequestDetails(baseUrl, headers, body = requestBodyJson)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(requestBodyJson) }
        }
    }

    private fun buildAgentPlatformRequestBody(
        modelName: String,
        contents: List<JsonObject>,
        systemPrompt: String,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?
    ): JsonObject = buildJsonObject {
        if (systemPrompt.isNotBlank()) {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
        }

        // Agent Platform rejects search tools alongside function tools in one request, so the
        // explicit per-chat search toggle wins and function tools sit out while it is on.
        when {
            webSearchEnabled -> put("tools", buildJsonArray {
                add(buildJsonObject { put("googleSearch", buildJsonObject {}) })
            })
            enabledTools.isNotEmpty() -> put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        // The prompt cache matches the declarations as a prefix: sorted, since the
                        // enabled-tools order differs between devices and sessions, and without
                        // the per-request content some descriptions carry
                        enabledTools.sortedBy { it.name }.forEach { toolSpec ->
                            add(buildJsonObject {
                                put("name", toolSpec.name)
                                put("description", toolSpec.stableDescription ?: toolSpec.description)
                                toolSpec.parameters?.let { params ->
                                    sanitizeToolParameters(params)?.let { put("parameters", it) }
                                }
                            })
                        }
                    })
                })
            })
        }

        val thinkingConfig = buildAgentPlatformThinkingConfig(modelName, thinkingBudget)
        if (temperature != null || thinkingConfig != null) {
            put("generationConfig", buildJsonObject {
                temperature?.let { put("temperature", it.toDouble()) }
                thinkingConfig?.let { put("thinkingConfig", it) }
            })
        }

        put("contents", JsonArray(contents))
    }

    /**
     * Keeps the level inside what the model accepts: a stale token budget or a level left over
     * from another model would be a 400 on Gemini 3+. Always explicit so a model whose server
     * default is MINIMAL can't reach MINIMAL, which needs replayed text signatures.
     */
    private fun buildAgentPlatformThinkingConfig(modelName: String, thinkingBudget: ThinkingBudgetValue): JsonObject? {
        return when (val type = ThinkingBudgetConfig.getThinkingBudgetType(VERTEX_PROVIDER, modelName)) {
            is ThinkingBudgetType.Discrete -> buildJsonObject {
                put("includeThoughts", true)
                val requested = (thinkingBudget as? ThinkingBudgetValue.Effort)?.level?.lowercase()
                val level = requested?.takeIf { it in type.options } ?: type.default
                put("thinkingLevel", level.uppercase())
            }
            is ThinkingBudgetType.Continuous -> buildJsonObject {
                put("includeThoughts", true)
                if (thinkingBudget is ThinkingBudgetValue.Tokens && thinkingBudget.count > 0) {
                    put("thinkingBudget", thinkingBudget.count)
                }
            }
            else -> null
        }
    }

    /**
     * Reduces a tool schema to what Agent Platform documents. Returns null for a function with
     * no parameters, which must omit the field instead of sending an empty OBJECT.
     */
    private fun sanitizeToolParameters(parameters: JsonObject): JsonObject? {
        val properties = parameters["properties"] as? JsonObject
        if (properties == null || properties.isEmpty()) return null
        return sanitizeSchema(parameters)
    }

    private fun sanitizeSchema(schema: JsonObject): JsonObject = buildJsonObject {
        schema.forEach { (key, value) ->
            when {
                key !in AGENT_PLATFORM_SCHEMA_KEYS -> Unit
                key == "properties" && value is JsonObject -> put(key, buildJsonObject {
                    value.forEach { (name, property) ->
                        put(name, if (property is JsonObject) sanitizeSchema(property) else property)
                    }
                })
                key == "items" && value is JsonObject -> put(key, sanitizeSchema(value))
                key == "anyOf" && value is JsonArray -> put(key, JsonArray(value.map { option ->
                    if (option is JsonObject) sanitizeSchema(option) else option
                }))
                key == "required" && value is JsonArray && value.isEmpty() -> Unit
                else -> put(key, value)
            }
        }
    }

    private fun buildRequestBody(
        modelName: String,
        contents: List<JsonObject>,
        webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>,
        thinkingBudget: ThinkingBudgetValue,
        temperature: Float?
    ): JsonObject {
        return buildJsonObject {
            // Add tools section
            val toolsArray = buildJsonArray {
                if (webSearchEnabled) {
                    add(buildJsonObject {
                        put("google_search", buildJsonObject {})
                    })
                }

                if (enabledTools.isNotEmpty()) {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            enabledTools.forEach { toolSpec ->
                                add(buildJsonObject {
                                    put("name", toolSpec.name)
                                    put("description", toolSpec.description)
                                    if (toolSpec.parameters != null) {
                                        put("parameters", toolSpec.parameters)
                                    } else {
                                        put("parameters", buildJsonObject {})
                                    }
                                })
                            }
                        })
                    })
                }
            }

            if (toolsArray.isNotEmpty()) {
                put("tools", toolsArray)
            }

            // Add generationConfig with thinkingConfig and temperature
            val modelLower = modelName.lowercase()
            val needsThinkingConfig = !modelLower.contains("flash-lite") && !modelLower.contains("2.0-flash-lite")
            val needsGenerationConfig = needsThinkingConfig || temperature != null

            if (needsGenerationConfig) {
                put("generationConfig", buildJsonObject {
                    if (temperature != null) {
                        put("temperature", temperature.toDouble())
                    }

                    if (needsThinkingConfig) {
                        put("thinkingConfig", buildJsonObject {
                            put("includeThoughts", true)

                            when (thinkingBudget) {
                                is ThinkingBudgetValue.Effort -> {
                                    put("thinkingLevel", thinkingBudget.level.uppercase())
                                }
                                is ThinkingBudgetValue.Tokens -> {
                                    if (thinkingBudget.count > 0) {
                                        put("thinkingBudget", thinkingBudget.count)
                                    }
                                }
                                ThinkingBudgetValue.None -> {
                                    // Use default behavior
                                }
                            }
                        })
                    }
                })
            }

            put("contents", JsonArray(contents))
        }
    }

    private companion object {
        // Google allows far more inline (256 MB a part on Gemini 3+); these keep the phone's
        // heap safe, since the whole base64 body lives in memory while it is built.
        const val MAX_INLINE_ATTACHMENT_BYTES = 50L * 1024 * 1024
        const val MAX_INLINE_TOTAL_BYTES = 64L * 1024 * 1024

        val AGENT_PLATFORM_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif")
        val AGENT_PLATFORM_TEXT_LIKE_TYPES = setOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-yaml", "application/yaml", "application/csv"
        )

        /** The subset of OpenAPI schema fields Agent Platform accepts in function parameters. */
        val AGENT_PLATFORM_SCHEMA_KEYS = setOf(
            "type", "nullable", "required", "format", "description",
            "properties", "items", "enum", "anyOf", "\$ref", "\$defs"
        )
    }
}
