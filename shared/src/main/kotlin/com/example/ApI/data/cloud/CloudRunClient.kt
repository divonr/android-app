package com.example.ApI.data.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Where the cloud is and who is asking: llm-web's base URL and the device's sync token. */
data class CloudRunConfig(val baseUrl: String, val token: String)

/** Outcome of `POST /api/runs`. */
sealed class CloudStartResult {
    /** The cloud runs the request (202, or 200 for a run id it already knew). */
    data class Accepted(val status: String) : CloudStartResult()

    /**
     * The cloud does not run the request: the device runs it itself.
     * [mayHaveStarted]: the request may have reached the server (timeout, connection lost,
     * 5xx…), so the run must be cancelled, best effort, before the device answers it too.
     */
    data class Fallback(val reason: String, val mayHaveStarted: Boolean) : CloudStartResult()
}

/** Outcome of `POST /api/runs/{runId}/cancel`. */
enum class CloudCancelResult {
    /** Accepted (the `cancelled` event confirms), or the run had already finished. */
    OK,
    /** The server does not know the run (forgotten, or a restart): there is nothing to stop. */
    NOT_FOUND,
    /** The server could not be asked. */
    FAILED
}

/**
 * HTTP client of the cloud runs (CLOUD_RUNS_PLAN.md): start a run, cancel it, and the
 * user-level event stream.  Every request carries `Authorization: Bearer <sync token>`.
 * [config] is read for every request (the settings may change); null = not configured.
 */
class CloudRunClient(
    private val config: () -> CloudRunConfig?,
    baseHttpClient: OkHttpClient = OkHttpClient(),
    startTimeoutMs: Long = START_TIMEOUT_MS,
    cancelTimeoutMs: Long = CANCEL_TIMEOUT_MS,
    streamReadTimeoutMs: Long = STREAM_READ_TIMEOUT_MS,
    private val backoffMs: (attempt: Int) -> Long = ::defaultBackoffMs
) {
    companion object {
        /** The whole start request; the server's first request of an account can take ~8 s. */
        const val START_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val CANCEL_TIMEOUT_MS = 8_000L
        /** The server pings every 15 s: silence this long means a dead connection. */
        const val STREAM_READ_TIMEOUT_MS = 45_000L

        /** 1 s, 2 s, 4 s … capped at 30 s. */
        fun defaultBackoffMs(attempt: Int): Long = (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * What a failed start means (see [CloudStartResult.Fallback]).  Only a failure that
         * proves the request never reached the server is unambiguous.
         */
        fun classifyFailure(error: Throwable): CloudStartResult.Fallback {
            val neverSent = error is ConnectException || error is UnknownHostException || error is NoRouteToHostException
            return CloudStartResult.Fallback(
                reason = "${error.javaClass.simpleName}: ${error.message}",
                mayHaveStarted = !neverSent
            )
        }

        /** What an HTTP answer to the start request means; [body] is the response body. */
        fun classifyResponse(code: Int, body: String): CloudStartResult = when {
            code in 200..299 -> {
                val status = try {
                    CloudRunJson.decodeFromString(CloudRunStartResponse.serializer(), body).status
                } catch (_: Exception) {
                    ""
                }
                CloudStartResult.Accepted(status.ifBlank { CloudRunState.STATUS_RUNNING })
            }
            code == 409 -> {
                val rejection = try {
                    CloudRunJson.decodeFromString(CloudRunRejection.serializer(), body)
                } catch (_: Exception) {
                    CloudRunRejection()
                }
                CloudStartResult.Fallback("409 ${rejection.code.ifBlank { "rejected" }}: ${rejection.error}", mayHaveStarted = false)
            }
            // The server answered and did not take the request
            code in 400..499 -> CloudStartResult.Fallback("HTTP $code", mayHaveStarted = false)
            // A proxy or a failing server: the run may exist behind it
            else -> CloudStartResult.Fallback("HTTP $code", mayHaveStarted = true)
        }
    }

    private val startClient = baseHttpClient.newBuilder()
        .connectTimeout(minOf(CONNECT_TIMEOUT_MS, startTimeoutMs), TimeUnit.MILLISECONDS)
        .readTimeout(startTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(startTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(startTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val cancelClient = baseHttpClient.newBuilder()
        .connectTimeout(minOf(CONNECT_TIMEOUT_MS, cancelTimeoutMs), TimeUnit.MILLISECONDS)
        .callTimeout(cancelTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val streamClient = baseHttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(streamReadTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun url(cfg: CloudRunConfig, path: String) = cfg.baseUrl.trimEnd('/') + path

    private fun Request.Builder.auth(cfg: CloudRunConfig) = header("Authorization", "Bearer ${cfg.token}")

    /** The JSON body of a start request (what [startRun] posts). */
    fun encodeRequest(request: CloudRunRequest): String =
        CloudRunJson.encodeToString(CloudRunRequest.serializer(), request)

    /**
     * Ask the cloud to run [request].  Never throws (except for cancellation): everything that
     * is not an acceptance is a [CloudStartResult.Fallback].
     */
    suspend fun startRun(request: CloudRunRequest): CloudStartResult {
        val cfg = config() ?: return CloudStartResult.Fallback("cloud execution is not configured", mayHaveStarted = false)
        return try {
            // A long chat is megabytes of JSON: not on the caller's (UI) thread
            val json = withContext(Dispatchers.Default) { encodeRequest(request) }
            val call = startClient.newCall(
                Request.Builder().url(url(cfg, "/api/runs")).auth(cfg)
                    .post(json.toRequestBody(JSON_MEDIA))
                    .build()
            )
            val (code, body) = call.await()
            classifyResponse(code, body)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IllegalArgumentException) {
            // An unusable base URL: nothing was sent
            CloudStartResult.Fallback("Bad cloud URL: ${e.message}", mayHaveStarted = false)
        } catch (e: Exception) {
            classifyFailure(e)
        }
    }

    /** Stop [runId]; [keepPartial]: the server saves what streamed so far as the reply. Never throws. */
    suspend fun cancelRun(runId: String, keepPartial: Boolean): CloudCancelResult {
        val cfg = config() ?: return CloudCancelResult.FAILED
        return try {
            val body = CloudRunJson.encodeToString(CloudCancelRequest.serializer(), CloudCancelRequest(keepPartial))
            val call = cancelClient.newCall(
                Request.Builder()
                    .url(
                        cfg.baseUrl.toHttpUrl().newBuilder()
                            .addPathSegments("api/runs").addPathSegment(runId).addPathSegment("cancel").build()
                    )
                    .auth(cfg)
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
            )
            val (code, _) = call.await()
            when (code) {
                in 200..299 -> CloudCancelResult.OK
                404 -> CloudCancelResult.NOT_FOUND
                else -> CloudCancelResult.FAILED
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            CloudCancelResult.FAILED
        }
    }

    /**
     * The user-level event stream, kept open for as long as the flow is collected: every
     * connection starts with a [CloudRunEvent.Snapshot]; when it ends (error, silence, server
     * restart, not configured) a [CloudRunEvent.Disconnected] is emitted and the stream is
     * reopened after a backoff that grows until a connection delivers its snapshot.
     * Never completes and never throws (except for cancellation).
     */
    fun events(): Flow<CloudRunEvent> = flow {
        var attempt = 0
        while (true) {
            try {
                connection().collect { event ->
                    if (event is CloudRunEvent.Snapshot) attempt = 0
                    emit(event)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Reconnect below
            }
            emit(CloudRunEvent.Disconnected)
            delay(backoffMs(attempt))
            attempt++
        }
    }

    /** One connection of the stream: its events until it ends (completes) or fails (throws). */
    private fun connection(): Flow<CloudRunEvent> = callbackFlow {
        val cfg = config()
        if (cfg == null) {
            close()
            return@callbackFlow
        }
        val call = streamClient.newCall(
            Request.Builder().url(url(cfg, "/api/runs/stream")).auth(cfg)
                .header("Accept", "text/event-stream")
                .get().build()
        )
        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val source = response.body?.source() ?: throw IOException("Empty body")
                    val pending = ArrayList<CloudRunEvent>()
                    val parser = SseParser { type, data -> CloudRunEvent.parse(type, data)?.let { pending.add(it) } }
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        parser.feedLine(line)
                        if (pending.isNotEmpty()) {
                            for (event in pending) send(event)
                            pending.clear()
                        }
                    }
                }
                close()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { call.cancel() }
    }
}

/**
 * Execute the call without blocking the caller's thread and return (status code, body); the body
 * is read on OkHttp's thread (the caller may be the UI thread).  Cancelling the coroutine cancels
 * the call.
 */
private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { Result.success(it.code to (it.body?.string() ?: "")) }
            } catch (e: IOException) {
                Result.failure(e)
            }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    })
}
