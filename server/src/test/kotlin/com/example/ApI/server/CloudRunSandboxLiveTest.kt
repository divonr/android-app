package com.example.ApI.server

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.auth.FakeSyncWhoamiClient
import com.example.ApI.server.auth.SyncIdentity
import com.example.ApI.server.runs.RunStatus
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolCallInfo
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A cloud run's code sandbox call against a real executor: the run's own tool path (the user's
 * attachment store for the input and the generated file) with only the model and the store
 * faked. Skipped unless asked for:
 *
 *     SANDBOX_LIVE_URL=http://127.0.0.1:8000/v2/execute SANDBOX_LIVE_KEY=... \
 *         ./gradlew :server:test --tests '*CloudRunSandboxLiveTest*'
 *
 * The session is the chat created here; its id is printed, its container is the executor's to
 * remove (two idle hours).
 */
class CloudRunSandboxLiveTest {

    @Test
    fun `a cloud run executes code in the real sandbox with a file from the store and keeps what it made`() {
        val url = System.getenv("SANDBOX_LIVE_URL").orEmpty()
        val key = System.getenv("SANDBOX_LIVE_KEY").orEmpty()
        assumeTrue("SANDBOX_LIVE_URL / SANDBOX_LIVE_KEY not set", url.isNotBlank() && key.isNotBlank())

        val store = FakeFileStore().start().also { it.accounts[TEST_BEARER] = TEST_USERNAME }
        try {
            val csv = "a,b\n1,2\n3,4\n".toByteArray()
            val csvSha = store.put(TEST_USERNAME, csv)
            val input = Attachment(
                local_file_path = "/data/user/0/app/data.csv", file_name = "data.csv", mime_type = "text/csv",
                sha256 = csvSha, size = csv.size.toLong(), file_POE_url = "https://poe/f"
            )

            val baseDir = File(System.getProperty("java.io.tmpdir"), "sandbox-live-${System.nanoTime()}").apply { mkdirs() }
            val repo = DataRepository(ServerPlatformStorage(File(baseDir, "users/$TEST_USERNAME")))
            repo.saveAppSettings(AppSettings(current_user = TEST_USERNAME, selected_provider = "poe", selected_model = "gpt-4o"))
            repo.addApiKey(TEST_USERNAME, ApiKey(id = "k2", provider = "poe", key = "poe-key", isActive = true))
            val chatId = repo.createNewChat(TEST_USERNAME, "Sandbox live").chat_id
            repo.addUserMessageAsNewNode(TEST_USERNAME, chatId, Message(role = "user", text = "Sum it", attachments = listOf(input)))
            println("[CloudRunSandboxLiveTest] $url chat=$chatId")

            // Longer than one keep-alive interval of the executor (12 s)
            val code = """
                import os, time, pandas as pd
                df = pd.read_csv('/app/input/data.csv')
                print('rows', len(df), 'sum', int(df.values.sum()), 'same', os.path.samefile('./data/data.csv', '/app/input/data.csv'))
                time.sleep(15)
                df.describe().to_csv('/app/output/summary.csv')
                print('done')
            """.trimIndent()
            val results = CopyOnWriteArrayList<ToolExecutionResult>()
            val engine = object : ChatEngine {
                override suspend fun send(
                    provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
                    username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
                    enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
                    callback: StreamingCallback
                ) {
                    enabledTools.single { it.name == ToolRegistry.WORKSPACE_SANDBOX_NAME }
                    val parameters = buildJsonObject { put("code", code) }
                    val call = ToolCall(id = "call-1", toolId = ToolRegistry.PYTHON_INTERPRETER, parameters = parameters, provider = "poe")
                    val result = callback.onToolCall(call, "")
                    results += result
                    callback.onSaveToolMessages(
                        Message(
                            role = "tool_call", text = "python",
                            toolCall = ToolCallInfo(
                                toolId = ToolRegistry.PYTHON_INTERPRETER, toolName = "Python", parameters = parameters,
                                result = result, timestamp = java.time.Instant.now().toString()
                            )
                        ),
                        Message(role = "tool_response", text = "ran", toolResponseCallId = "call-1", toolResponseOutput = "ran"),
                        ""
                    )
                    callback.onPartialResponse("Summed")
                    callback.onComplete("Summed")
                }
            }
            val whoami = FakeSyncWhoamiClient().apply { identities[TEST_BEARER] = SyncIdentity(TEST_USERNAME, TEST_USER_EMAIL) }
            LiveServer(
                ServerPlatformStorage(baseDir), engine, whoami, syncServerUrl = store.baseUrl,
                pythonExecutorUrl = url, sandboxKey = key
            ).use { server ->
                val client = server.client()
                val path = repo.loadChatHistory(TEST_USERNAME).chat_history.first { it.chat_id == chatId }.messages
                val started = client.post(
                    "/api/runs",
                    runBody("run-live", chatId, path, provider = "poe", enabledToolIds = listOf(ToolRegistry.PYTHON_INTERPRETER))
                )
                assertEquals(202, started.status, started.body)
                awaitTrue(timeoutMs = 120_000, what = "the run to complete") {
                    client.sse("/api/runs/stream").use { stream ->
                        stream.expect("snapshot")["runs"]!!.jsonArray.map { it.jsonObject }.any {
                            it["runId"]!!.jsonPrimitive.content == "run-live" && it["status"]!!.jsonPrimitive.content == RunStatus.COMPLETE
                        }
                    }
                }

                val result = results.single()
                assertIs<ToolExecutionResult.Success>(result, result.toString().take(800))
                println("[CloudRunSandboxLiveTest] result: ${result.result.replace("\n", " | ")}")
                assertTrue("rows 2 sum 10 same True\ndone" in result.result, result.result)
                assertTrue(result.result.endsWith("**Generated Files:**\n- summary.csv"), result.result)

                // What the code made is in the user's store and attached to the reply by its hash
                val messages = repo.loadChatHistory(TEST_USERNAME).chat_history.first { it.chat_id == chatId }.messages
                assertEquals(listOf("user", "tool_call", "tool_response", "assistant"), messages.map { it.role })
                val made = messages.last().attachments.single()
                assertEquals("summary.csv", made.file_name)
                val stored = store.files(TEST_USERNAME)[made.sha256]
                assertNotNull(stored, "the generated file is in the store")
                assertTrue(stored.committed && String(stored.bytes).startsWith(",a,b\ncount,2.0,2.0"), String(stored.bytes))
            }
        } finally {
            store.stop()
        }
    }
}
