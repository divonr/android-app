package com.example.ApI.server

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.EnabledGoogleServices
import com.example.ApI.data.model.GitHubAuth
import com.example.ApI.data.model.GitHubConnection
import com.example.ApI.data.model.GitHubUser
import com.example.ApI.data.model.GoogleWorkspaceAuth
import com.example.ApI.data.model.GoogleWorkspaceConnection
import com.example.ApI.data.model.GoogleWorkspaceUser
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.auth.FakeSyncWhoamiClient
import com.example.ApI.server.auth.SyncIdentity
import com.example.ApI.server.oauth.GoogleRefreshRejectedException
import com.example.ApI.server.oauth.GoogleTokenRefresher
import com.example.ApI.server.oauth.RefreshedGoogleToken
import com.example.ApI.server.runs.IntegrationTools
import com.example.ApI.server.runs.RunRegistry
import com.example.ApI.server.runs.RunRequest
import com.example.ApI.server.runs.RunStatus
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.tools.ToolSpecification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Fakes of the integrations (shared by every server test: nothing reaches GitHub / Google) ──

/**
 * [IntegrationTools] whose tools call nothing: each remembers the credential it was created
 * with, and every execution is recorded with it — so a test sees whose token a run used.
 */
internal class FakeIntegrationTools : IntegrationTools {
    data class Execution(val toolId: String, val credential: String)

    val executions = CopyOnWriteArrayList<Execution>()

    private inner class FakeTool(override val id: String, val credential: String) : Tool {
        override val name = "Fake $id"
        override val description = "$id as $credential"
        override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
            executions += Execution(id, credential)
            return ToolExecutionResult.Success("$id ran as $credential")
        }

        override fun getSpecification(provider: String) = ToolSpecification(name = id, description = description)
    }

    override fun registerGitHub(registry: ToolRegistry, accessToken: String, githubLogin: String) {
        registry.getGitHubToolIds().forEach { registry.registerTool(FakeTool(it, "github:$githubLogin:$accessToken")) }
    }

    override fun registerGoogleWorkspace(registry: ToolRegistry, connection: GoogleWorkspaceConnection) {
        val services = connection.enabledServices
        val ids = (if (services.gmail) registry.getGmailToolIds() else emptyList()) +
            (if (services.calendar) registry.getCalendarToolIds() else emptyList()) +
            (if (services.drive) registry.getDriveToolIds() else emptyList())
        ids.forEach { registry.registerTool(FakeTool(it, "google:${connection.user.email}:${connection.auth.accessToken}")) }
    }
}

/** [GoogleTokenRefresher] that answers with [answer] and counts its calls (default: cannot refresh). */
internal class FakeGoogleTokenRefresher(
    var answer: (refreshToken: String) -> Result<RefreshedGoogleToken> = { Result.failure(IllegalStateException("no refresh in tests")) }
) : GoogleTokenRefresher {
    val calls = CopyOnWriteArrayList<String>()
    override suspend fun refresh(refreshToken: String): Result<RefreshedGoogleToken> {
        calls += refreshToken
        return answer(refreshToken)
    }
}

/**
 * Tries the tool calls of [attempts] (per username; default: none), then replies "Done".
 * Records what each user's run was offered ([offered]) and what its calls returned ([results]).
 */
internal class ToolCallingEngine(private val attempts: Map<String, List<String>> = emptyMap()) : ChatEngine {
    val offered = ConcurrentHashMap<String, List<String>>()
    val descriptions = ConcurrentHashMap<String, String>()
    val results = ConcurrentHashMap<String, List<ToolExecutionResult>>()

    /** When set, a run waits here after its tool calls (to keep it running). */
    @Volatile
    var gate: CompletableDeferred<Unit>? = null
    val reachedGate = CompletableDeferred<Unit>()

    override suspend fun send(
        provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
        username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
        enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
        callback: StreamingCallback
    ) {
        offered[username] = enabledTools.map { it.name }
        enabledTools.forEach { descriptions[it.name] = it.description }
        results[username] = attempts[username].orEmpty().map { toolId ->
            callback.onToolCall(ToolCall(id = "call-$toolId", toolId = toolId, parameters = buildJsonObject {}, provider = "openai"), "")
        }
        gate?.let {
            reachedGate.complete(Unit)
            it.await()
        }
        callback.onPartialResponse("Done")
        callback.onComplete("Done")
    }
}

/**
 * Stage S5 of CLOUD_RUNS_PLAN.md: the per-user, per-run tool set (GitHub, Google Workspace, the
 * group-conversations tool; isolation between users; token refresh) and the order of the
 * terminal event and the title generation (`complete` first, then `chat_updated`).
 */
class CloudRunToolsTest {

    private companion object {
        const val ALICE = TEST_USERNAME
        const val BOB = "bob_example_com"
        const val BOB_TOKEN = "bob-token"
        const val GITHUB_TOOL = "github_list_repositories"
        const val GROUP_TOOL = "get_current_group_conversations"
    }

    private class User(val name: String, val repo: DataRepository, val chatId: String) {
        fun path(): List<Message> = chat().messages
        fun chat() = repo.loadChatHistory(name).chat_history.first { it.chat_id == chatId }
        fun texts(): List<String> = chat().messages.map { it.text }
    }

    private class World(val storage: ServerPlatformStorage) {
        fun user(name: String): User {
            val repo = DataRepository(ServerPlatformStorage(File(storage.baseDir, "users/$name")))
            repo.saveAppSettings(AppSettings(current_user = name, selected_provider = "openai", selected_model = "gpt-4o"))
            repo.addApiKey(name, ApiKey(id = "k-$name", provider = "openai", key = "sk-$name", isActive = true))
            val chat = repo.createNewChat(name, "Chat of $name")
            repo.addUserMessageAsNewNode(name, chat.chat_id, Message(role = "user", text = "Q1"))
            return User(name, repo, chat.chat_id)
        }
    }

    private fun world() = World(
        ServerPlatformStorage(File(System.getProperty("java.io.tmpdir"), "run-tools-test-${System.nanoTime()}").apply { mkdirs() })
    )

    private fun whoami() = FakeSyncWhoamiClient().apply {
        identities[TEST_BEARER] = SyncIdentity(ALICE, TEST_USER_EMAIL)
        identities[BOB_TOKEN] = SyncIdentity(BOB, "bob@example.com")
    }

    private fun github(token: String, login: String, expiresIn: Long? = null, createdAt: Long = System.currentTimeMillis()) =
        GitHubConnection(
            auth = GitHubAuth(accessToken = token, scope = "repo", expiresIn = expiresIn, createdAt = createdAt),
            user = GitHubUser(
                login = login, id = 1, nodeId = "n", avatarUrl = "", gravatarId = null, url = "", htmlUrl = "",
                name = null, company = null, blog = null, location = null, email = null, bio = null,
                publicRepos = 0, publicGists = 0, followers = 0, following = 0, createdAt = "", updatedAt = ""
            )
        )

    private fun google(
        token: String,
        email: String,
        expiresInMs: Long = 3_600_000,
        refreshToken: String? = null,
        services: EnabledGoogleServices = EnabledGoogleServices()
    ) = GoogleWorkspaceConnection(
        auth = GoogleWorkspaceAuth(
            accessToken = token, refreshToken = refreshToken,
            expiresAt = System.currentTimeMillis() + expiresInMs, scopes = listOf("scope")
        ),
        user = GoogleWorkspaceUser(id = "g", email = email, displayName = null, photoUrl = null),
        enabledServices = services
    )

    private fun body(runId: String, user: User, tools: List<String>, groupId: String? = null, chatId: String = user.chatId): String =
        Json.encodeToString(
            RunRequest.serializer(),
            RunRequest(
                runId = runId, chatId = chatId, provider = "openai", modelName = "gpt-4o", messages = user.path(),
                enabledToolIds = tools, groupId = groupId
            )
        )

    private fun LiveClient.start(body: String): LiveResponse = post("/api/runs", body)

    private fun LiveResponse.assertUnsupported(vararg toolIds: String) {
        assertEquals(409, status, body)
        assertEquals("unsupported_tools", json()["code"]!!.jsonPrimitive.content)
        toolIds.forEach { assertTrue(body.contains(it), "$it should be named in: $body") }
    }

    private fun LiveClient.awaitComplete(runId: String) = awaitTrue(what = "run $runId to complete") {
        sse("/api/runs/stream").use { stream ->
            stream.expect("snapshot")["runs"]!!.jsonArray.map { it.jsonObject }.any {
                it["runId"]!!.jsonPrimitive.content == runId && it["status"]!!.jsonPrimitive.content == RunStatus.COMPLETE
            }
        }
    }

    private fun ToolExecutionResult.text(): String = when (this) {
        is ToolExecutionResult.Success -> result
        is ToolExecutionResult.Error -> "ERROR: $error"
    }

    // ── Isolation between users ──────────────────────────────────────────────

    @Test
    fun `each user's run executes its tools with that user's own connection`() {
        val world = world()
        val alice = world.user(ALICE)
        val bob = world.user(BOB)
        alice.repo.saveGitHubConnection(ALICE, github("gh-alice", "alice-gh"))
        bob.repo.saveGitHubConnection(BOB, github("gh-bob", "bob-gh"))
        val tools = FakeIntegrationTools()
        val engine = ToolCallingEngine(mapOf(ALICE to listOf(GITHUB_TOOL), BOB to listOf(GITHUB_TOOL)))

        LiveServer(world.storage, engine, whoami(), integrationTools = tools).use { server ->
            val aliceClient = server.client()
            val bobClient = server.client(BOB_TOKEN)
            val aliceStream = aliceClient.sse("/api/runs/stream")
            aliceStream.expect("snapshot")

            assertEquals(202, aliceClient.start(body("run-a", alice, listOf(GITHUB_TOOL))).status)
            // The display name and the result on the stream come from the run's own tools
            val call = aliceStream.nextOf("tool_call")
            assertEquals(GITHUB_TOOL, call["toolId"]!!.jsonPrimitive.content)
            assertEquals("Fake $GITHUB_TOOL", call["toolName"]!!.jsonPrimitive.content)
            val result = aliceStream.expect("tool_result")
            assertEquals(true, result["success"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(result["output"]!!.jsonPrimitive.content.contains("gh-alice"))
            aliceClient.awaitComplete("run-a")

            assertEquals(202, bobClient.start(body("run-b", bob, listOf(GITHUB_TOOL))).status)
            bobClient.awaitComplete("run-b")
        }

        assertEquals(
            listOf(
                FakeIntegrationTools.Execution(GITHUB_TOOL, "github:alice-gh:gh-alice"),
                FakeIntegrationTools.Execution(GITHUB_TOOL, "github:bob-gh:gh-bob")
            ),
            tools.executions.toList()
        )
        assertEquals(listOf(GITHUB_TOOL), engine.offered[ALICE])
        assertEquals(listOf(GITHUB_TOOL), engine.offered[BOB])
        // Nothing of a user ever lands in the process-wide registry (the single-user clients' one)
        assertNull(ToolRegistry.getInstance().getTool(GITHUB_TOOL))
    }

    @Test
    fun `a user without a connection can never run with another user's tools`() {
        val world = world()
        val alice = world.user(ALICE)
        val bob = world.user(BOB)
        alice.repo.saveGitHubConnection(ALICE, github("gh-alice", "alice-gh"))
        val tools = FakeIntegrationTools()
        // Bob's "model" asks for the GitHub tool although his run does not have it
        val engine = ToolCallingEngine(mapOf(ALICE to listOf(GITHUB_TOOL), BOB to listOf(GITHUB_TOOL)))
        val gate = CompletableDeferred<Unit>()
        engine.gate = gate

        LiveServer(world.storage, engine, whoami(), integrationTools = tools).use { server ->
            val aliceClient = server.client()
            val bobClient = server.client(BOB_TOKEN)

            // Alice's run is live (holding her tools) while Bob tries
            assertEquals(202, aliceClient.start(body("run-a", alice, listOf(GITHUB_TOOL))).status)
            runBlocking { withTimeout(10_000) { engine.reachedGate.await() } }

            // Asking for it is refused: the cloud cannot run this request for Bob
            bobClient.start(body("run-b1", bob, listOf("get_date_time", GITHUB_TOOL))).assertUnsupported(GITHUB_TOOL)

            // A run of Bob without the tool: the model's call finds no tool to execute
            assertEquals(202, bobClient.start(body("run-b2", bob, listOf("get_date_time"))).status)
            awaitTrue(what = "Bob's tool call to be answered") { engine.results[BOB] != null }
            val refused = engine.results[BOB]!!.single()
            assertTrue(refused is ToolExecutionResult.Error, refused.text())
            assertEquals(listOf("get_date_time"), engine.offered[BOB])

            // The web's send route does not answer 409: the tool is just not there for Bob
            val bobBrowser = server.login("bob@example.com")
            val send = """{"chatId":"${bob.chatId}","provider":"openai","modelName":"gpt-4o",""" +
                """"messages":[{"role":"user","text":"Q2","id":"q2"}],"enabledToolIds":["get_date_time","$GITHUB_TOOL"]}"""
            engine.results.remove(BOB)
            val stream = bobBrowser.sse("/api/chat/send", method = "POST", body = send)
            assertEquals(200, stream.status)
            awaitTrue(what = "Bob's web tool call to be answered") { engine.results[BOB] != null }
            assertTrue(engine.results[BOB]!!.single() is ToolExecutionResult.Error)
            assertEquals(listOf("get_date_time"), engine.offered[BOB])

            gate.complete(Unit)
            aliceClient.awaitComplete("run-a")
        }

        // The only execution ever: Alice's own
        assertEquals(listOf(FakeIntegrationTools.Execution(GITHUB_TOOL, "github:alice-gh:gh-alice")), tools.executions.toList())
    }

    // ── Tool families: accepted with their prerequisite, 409 without ─────────

    @Test
    fun `github tools need the user's github connection`() {
        val world = world()
        val alice = world.user(ALICE)
        val engine = ToolCallingEngine()
        LiveServer(world.storage, engine, whoami()).use { server ->
            val client = server.client()
            val all = ToolRegistry().getGitHubToolIds()

            client.start(body("r1", alice, all)).assertUnsupported(*all.toTypedArray())

            // The connection arrives (a sync from the device): picked up by the next run
            alice.repo.saveGitHubConnection(ALICE, github("gh-alice", "alice-gh"))
            assertEquals(202, client.start(body("r2", alice, all)).status)
            client.awaitComplete("r2")
            assertEquals(all, engine.offered[ALICE])

            // An expired token
            alice.repo.saveGitHubConnection(ALICE, github("gh-old", "alice-gh", expiresIn = 60, createdAt = System.currentTimeMillis() - 3_600_000))
            client.start(body("r3", alice, listOf(GITHUB_TOOL))).assertUnsupported(GITHUB_TOOL)

            // Disconnected (on any device): gone at once
            alice.repo.saveGitHubConnection(ALICE, github("gh-alice", "alice-gh"))
            alice.repo.removeGitHubConnection(ALICE)
            client.start(body("r4", alice, listOf(GITHUB_TOOL))).assertUnsupported(GITHUB_TOOL)
        }
    }

    @Test
    fun `google workspace tools need the connection, its enabled service and a valid token`() {
        val world = world()
        val alice = world.user(ALICE)
        val tools = FakeIntegrationTools()
        val engine = ToolCallingEngine(mapOf(ALICE to listOf("drive_list_files")))
        LiveServer(world.storage, engine, whoami(), integrationTools = tools).use { server ->
            val client = server.client()
            val registry = ToolRegistry()
            val all = registry.getGoogleWorkspaceToolIds()

            client.start(body("r1", alice, listOf("gmail_send_email"))).assertUnsupported("gmail_send_email")

            alice.repo.saveGoogleWorkspaceConnection(ALICE, google("ya29-alice", "alice@gmail.com"))
            assertEquals(202, client.start(body("r2", alice, all)).status)
            client.awaitComplete("r2")
            assertEquals(all, engine.offered[ALICE])
            assertEquals(
                listOf(FakeIntegrationTools.Execution("drive_list_files", "google:alice@gmail.com:ya29-alice")),
                tools.executions.toList()
            )

            // A service turned off in the connection: only its tools are missing
            alice.repo.updateGoogleWorkspaceEnabledServices(ALICE, EnabledGoogleServices(gmail = false, calendar = true, drive = true))
            client.start(body("r3", alice, all)).assertUnsupported(*registry.getGmailToolIds().toTypedArray())
            assertEquals(202, client.start(body("r4", alice, registry.getCalendarToolIds() + registry.getDriveToolIds())).status)
            client.awaitComplete("r4")

            // An expired token that cannot be refreshed (what an Android-made connection is after an hour)
            alice.repo.saveGoogleWorkspaceConnection(ALICE, google("ya29-old", "alice@gmail.com", expiresInMs = -1_000))
            client.start(body("r5", alice, listOf("drive_list_files"))).assertUnsupported("drive_list_files")

            alice.repo.removeGoogleWorkspaceConnection(ALICE)
            client.start(body("r6", alice, listOf("drive_list_files"))).assertUnsupported("drive_list_files")
        }
    }

    @Test
    fun `an expiring google token is refreshed, saved to the connection and used by the run`() {
        val world = world()
        val alice = world.user(ALICE)
        val tools = FakeIntegrationTools()
        val refresher = FakeGoogleTokenRefresher { token ->
            assertEquals("refresh-1", token)
            Result.success(RefreshedGoogleToken("ya29-new", System.currentTimeMillis() + 3_600_000))
        }
        // Expires within the 5 minutes margin
        alice.repo.saveGoogleWorkspaceConnection(
            ALICE,
            google(
                "ya29-old", "alice@gmail.com", expiresInMs = 60_000, refreshToken = "refresh-1",
                services = EnabledGoogleServices(gmail = false, calendar = false, drive = true)
            )
        )
        val engine = ToolCallingEngine(mapOf(ALICE to listOf("drive_list_files")))
        LiveServer(world.storage, engine, whoami(), integrationTools = tools, googleTokenRefresher = refresher).use { server ->
            val client = server.client()
            assertEquals(202, client.start(body("r1", alice, listOf("drive_list_files"))).status)
            client.awaitComplete("r1")
            // The token is valid now: no second refresh
            assertEquals(202, client.start(body("r2", alice, listOf("drive_list_files"))).status)
            client.awaitComplete("r2")
            // A run without a Google tool never asks Google for anything
            assertEquals(202, client.start(body("r3", alice, listOf("get_date_time"))).status)
            client.awaitComplete("r3")
        }
        assertEquals(listOf("refresh-1"), refresher.calls.toList())
        assertEquals(
            List(2) { FakeIntegrationTools.Execution("drive_list_files", "google:alice@gmail.com:ya29-new") },
            tools.executions.toList()
        )
        // Saved through the synced connection file, the rest of the connection untouched
        val saved = alice.repo.loadGoogleWorkspaceConnection(ALICE)!!
        assertEquals("ya29-new", saved.auth.accessToken)
        assertEquals("refresh-1", saved.auth.refreshToken)
        assertFalse(saved.auth.isExpiringSoon())
        assertEquals(EnabledGoogleServices(gmail = false, calendar = false, drive = true), saved.enabledServices)
    }

    @Test
    fun `a refused refresh token is not tried again while a passing failure is`() {
        val world = world()
        val alice = world.user(ALICE)
        val refresher = FakeGoogleTokenRefresher { Result.failure(GoogleRefreshRejectedException("invalid_grant")) }
        alice.repo.saveGoogleWorkspaceConnection(ALICE, google("ya29-old", "alice@gmail.com", expiresInMs = -1_000, refreshToken = "auth-code"))
        LiveServer(world.storage, ToolCallingEngine(), whoami(), googleTokenRefresher = refresher).use { server ->
            val client = server.client()
            client.start(body("r1", alice, listOf("drive_list_files"))).assertUnsupported("drive_list_files")
            client.start(body("r2", alice, listOf("drive_list_files"))).assertUnsupported("drive_list_files")
            assertEquals(listOf("auth-code"), refresher.calls.toList(), "a refused token is asked about once")

            // A reconnect (here or on a device) brings another refresh token; the network is down, then back
            alice.repo.saveGoogleWorkspaceConnection(ALICE, google("ya29-old2", "alice@gmail.com", expiresInMs = -1_000, refreshToken = "refresh-2"))
            refresher.answer = { Result.failure(java.io.IOException("network down")) }
            client.start(body("r3", alice, listOf("drive_list_files"))).assertUnsupported("drive_list_files")
            refresher.answer = { Result.success(RefreshedGoogleToken("ya29-new", System.currentTimeMillis() + 3_600_000)) }
            assertEquals(202, client.start(body("r4", alice, listOf("drive_list_files"))).status)
            assertEquals(listOf("auth-code", "refresh-2", "refresh-2"), refresher.calls.toList())
            client.awaitComplete("r4")
        }
        // An unusable token is never written: the expired one stayed until the refresh worked
        assertEquals("ya29-new", alice.repo.loadGoogleWorkspaceConnection(ALICE)!!.auth.accessToken)
    }

    @Test
    fun `the group tool runs for the run's chat and its group`() {
        val world = world()
        val alice = world.user(ALICE)
        val repo = alice.repo
        val group = repo.createNewGroup(ALICE, "Trip planning")
        val other = repo.createNewGroup(ALICE, "Work")
        val inGroup = repo.createNewChatInGroup(ALICE, "Flights", group.group_id)
        repo.addUserMessageAsNewNode(ALICE, inGroup.chat_id, Message(role = "user", text = "Which flight is cheapest?"))
        val sibling = repo.createNewChatInGroup(ALICE, "Hotels", group.group_id)
        repo.addUserMessageAsNewNode(ALICE, sibling.chat_id, Message(role = "user", text = "A hotel near the beach"))
        val elsewhere = repo.createNewChatInGroup(ALICE, "Budget meeting", other.group_id)
        repo.addUserMessageAsNewNode(ALICE, elsewhere.chat_id, Message(role = "user", text = "Quarterly numbers"))
        fun pathOf(chatId: String) = repo.loadChatHistory(ALICE).chat_history.first { it.chat_id == chatId }.messages
        fun bodyFor(runId: String, chatId: String, messages: List<Message>, groupId: String? = null) = Json.encodeToString(
            RunRequest.serializer(),
            RunRequest(
                runId = runId, chatId = chatId, provider = "openai", modelName = "gpt-4o", messages = messages,
                enabledToolIds = listOf(GROUP_TOOL), groupId = groupId
            )
        )

        val engine = ToolCallingEngine(mapOf(ALICE to listOf(GROUP_TOOL)))
        LiveServer(world.storage, engine, whoami()).use { server ->
            val client = server.client()

            // A chat outside every group has no such tool (a device never asks for it there)
            client.start(body("r0", alice, listOf(GROUP_TOOL))).assertUnsupported(GROUP_TOOL)

            // The run's chat decides the group: the tool is described and executed for it
            assertEquals(202, client.start(bodyFor("r1", inGroup.chat_id, pathOf(inGroup.chat_id))).status)
            client.awaitComplete("r1")
            assertEquals(listOf(GROUP_TOOL), engine.offered[ALICE])
            assertTrue(engine.descriptions[GROUP_TOOL]!!.contains("Trip planning"))
            val output = engine.results[ALICE]!!.single().text()
            assertTrue(output.contains("A hotel near the beach"), output)
            assertFalse(output.contains("Which flight is cheapest?"), "the run's own chat is not part of the answer")
            assertFalse(output.contains("Quarterly numbers"), "chats of another group are not part of the answer")

            // A chat the device created a moment ago is not in the server's copy yet: the device names its group
            val question = Message(role = "user", text = "New question", nodeId = "node-1", variantId = "variant-1")
            assertEquals(202, client.start(bodyFor("r2", "chat-not-synced-yet", listOf(question), groupId = other.group_id)).status)
            client.awaitComplete("r2")
            assertTrue(engine.descriptions[GROUP_TOOL]!!.contains("Work"))
            val second = engine.results[ALICE]!!.single().text()
            assertTrue(second.contains("Quarterly numbers"), second)
            assertFalse(second.contains("A hotel near the beach"))

            // …but not a group the user does not have
            client.start(bodyFor("r3", "chat-not-synced-yet-2", listOf(question), groupId = "no-such-group"))
                .assertUnsupported(GROUP_TOOL)
            // …and without it, an unknown chat has no group
            client.start(bodyFor("r4", "chat-not-synced-yet-3", listOf(question))).assertUnsupported(GROUP_TOOL)
        }
    }

    @Test
    fun `one user's group tool never reads another user's chats`() {
        val world = world()
        val alice = world.user(ALICE)
        val bob = world.user(BOB)
        val aliceGroup = alice.repo.createNewGroup(ALICE, "Alice's group")
        val secret = alice.repo.createNewChatInGroup(ALICE, "Secret", aliceGroup.group_id)
        alice.repo.addUserMessageAsNewNode(ALICE, secret.chat_id, Message(role = "user", text = "Alice's secret"))

        LiveServer(world.storage, ToolCallingEngine(), whoami()).use { server ->
            // Bob names Alice's group (and even her chat): his copy has no such group
            val asBob = Json.encodeToString(
                RunRequest.serializer(),
                RunRequest(
                    runId = "r1", chatId = secret.chat_id, provider = "openai", modelName = "gpt-4o", messages = bob.path(),
                    enabledToolIds = listOf(GROUP_TOOL), groupId = aliceGroup.group_id
                )
            )
            server.client(BOB_TOKEN).start(asBob).assertUnsupported(GROUP_TOOL)
        }
    }

    @Test
    fun `skill tools stay on the device`() {
        val world = world()
        val alice = world.user(ALICE)
        // Even with a skill on the server: skill folders are not synced, the device's are others
        assertNotNull(alice.repo.createSkill("web-skill", "A skill made on the web", "Body"))
        val engine = ToolCallingEngine()
        LiveServer(world.storage, engine, whoami()).use { server ->
            val client = server.client()
            val skills = ToolRegistry().getSkillToolIds()
            client.start(body("r1", alice, skills)).assertUnsupported(*skills.toTypedArray())
        }
        assertTrue(engine.offered.isEmpty(), "nothing ran")
    }

    @Test
    fun `the python interpreter needs the user's attachment store`() = runBlocking {
        val world = world()
        val alice = world.user(ALICE)
        val storage = ServerPlatformStorage(File(world.storage.baseDir, "users/$ALICE"))
        val attachments = com.example.ApI.server.attachments.UserAttachments(ALICE, storage, alice.repo)
        val tools = com.example.ApI.server.runs.UserTools(ALICE, alice.repo, FakeIntegrationTools(), storage = storage, attachments = attachments)
        val python = listOf(ToolRegistry.PYTHON_INTERPRETER)

        // Sync is off for this user: a generated file could not reach the user's devices
        assertEquals(python, tools.forRun(alice.chatId, python).missing(python))

        alice.repo.updateAppSettings {
            it.copy(remoteSync = it.remoteSync.copy(enabled = true, serverBaseUrl = UNREACHABLE_SYNC_URL, authToken = "token"))
        }
        assertEquals(emptyList(), tools.forRun(alice.chatId, python).missing(python))
        attachments.close()
    }

    @Test
    fun `the web's send route gets the user's tools and still drops the ones the server lacks`() {
        val world = world()
        val alice = world.user(ALICE)
        alice.repo.saveGitHubConnection(ALICE, github("gh-alice", "alice-gh"))
        val group = alice.repo.createNewGroup(ALICE, "Web group")
        alice.repo.addChatToGroup(ALICE, alice.chatId, group.group_id)
        val tools = FakeIntegrationTools()
        val engine = ToolCallingEngine(mapOf(ALICE to listOf(GITHUB_TOOL)))
        LiveServer(world.storage, engine, whoami(), integrationTools = tools).use { server ->
            val browser = server.login(TEST_USER_EMAIL)
            val send = """{"chatId":"${alice.chatId}","provider":"openai","modelName":"gpt-4o",""" +
                """"messages":[{"role":"user","text":"Q2","id":"q2"}],""" +
                """"enabledToolIds":["get_date_time","$GITHUB_TOOL","$GROUP_TOOL","python_interpreter","read_skill","gmail_send_email"]}"""
            val stream = browser.sse("/api/chat/send", method = "POST", body = send)
            assertEquals(200, stream.status)
            stream.expect("run")
            assertEquals("Fake $GITHUB_TOOL", stream.expect("tool_call")["toolName"]!!.jsonPrimitive.content)
            stream.expect("tool_result")
            stream.nextOf("complete")
            assertNull(stream.nextOrNull(), "the per-request stream ends with the terminal event")
        }
        // Skills and Gmail (no connection) are dropped; the Python tool runs here now (the user has a
        // store): enabled by its id, python_interpreter, and offered to the model as workspace_sandbox
        assertEquals(listOf("get_date_time", GITHUB_TOOL, GROUP_TOOL, "workspace_sandbox"), engine.offered[ALICE])
        assertEquals(listOf(FakeIntegrationTools.Execution(GITHUB_TOOL, "github:alice-gh:gh-alice")), tools.executions.toList())
    }

    // ── Terminal event first, then the title ─────────────────────────────────

    /** A title generator that waits for [gate]; [started] completes when it was asked. */
    private class GatedTitle(private val title: String = "Generated title") : TitleGenerator {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        override suspend fun generate(username: String, chatId: String, provider: String?): String {
            calls.incrementAndGet()
            started.complete(Unit)
            gate.await()
            return title
        }

        fun awaitStarted() = runBlocking { withTimeout(10_000) { started.await() } }
    }

    @Test
    fun `complete is emitted before the title is generated, then chat_updated`() {
        val world = world()
        val alice = world.user(ALICE)
        val title = GatedTitle()
        LiveServer(world.storage, ReplyEngine(), whoami(), titleGenerator = title).use { server ->
            val client = server.client()
            val stream = client.sse("/api/runs/stream")
            stream.expect("snapshot")
            assertEquals(202, client.start(body("run-1", alice, emptyList())).status)

            // The run ends (reply saved) while the title is still being generated
            val complete = stream.nextOf("complete")
            assertEquals("run-1", complete["runId"]!!.jsonPrimitive.content)
            title.awaitStarted()
            assertEquals(listOf("Q1", "Cloud reply"), alice.texts())
            assertEquals("Chat of $ALICE", alice.chat().preview_name)
            assertTrue(runCatching { stream.next(300) }.isFailure, "nothing follows the terminal event until the title is there")
            // The run is over for every client meanwhile
            assertEquals(200, client.post("/api/runs/run-1/cancel", """{"keepPartial":true}""").status)

            title.gate.complete(Unit)
            val updated = stream.expect("chat_updated")
            assertEquals(setOf("runId", "chatId"), updated.keys)
            assertEquals("run-1", updated["runId"]!!.jsonPrimitive.content)
            assertEquals(alice.chatId, updated["chatId"]!!.jsonPrimitive.content)
            // Emitted after the rename is saved
            assertEquals("Generated title", alice.chat().preview_name)
            // The run's retained state is what it was
            val snapshot = client.sse("/api/runs/stream").use { it.expect("snapshot")["runs"]!!.jsonArray.single().jsonObject }
            assertEquals(RunStatus.COMPLETE, snapshot["status"]!!.jsonPrimitive.content)
        }
        assertEquals(1, title.calls.get())
    }

    @Test
    fun `a kept partial reply ends with cancelled first, then chat_updated`() {
        val world = world()
        val alice = world.user(ALICE)
        val title = GatedTitle("Partial title")
        val engine = GatedEngine()
        LiveServer(world.storage, engine, whoami(), titleGenerator = title).use { server ->
            val client = server.client()
            val stream = client.sse("/api/runs/stream")
            stream.expect("snapshot")
            client.start(body("run-1", alice, emptyList()))
            stream.nextOf("partial")

            assertEquals(200, client.post("/api/runs/run-1/cancel", """{"keepPartial":true}""").status)
            stream.expect("cancelled")
            title.awaitStarted()
            assertEquals(listOf("Q1", "Hel"), alice.texts())
            assertEquals("Chat of $ALICE", alice.chat().preview_name)

            title.gate.complete(Unit)
            assertEquals(alice.chatId, stream.expect("chat_updated")["chatId"]!!.jsonPrimitive.content)
            assertEquals("Partial title", alice.chat().preview_name)
        }
    }

    @Test
    fun `the web's per-request stream ends at the terminal event and the title arrives on the account stream`() {
        val world = world()
        val alice = world.user(ALICE)
        val title = GatedTitle()
        LiveServer(world.storage, ReplyEngine(), whoami(), titleGenerator = title).use { server ->
            val browser = server.login(TEST_USER_EMAIL)
            val account = browser.sse("/api/runs/stream")
            account.expect("snapshot")
            // A new chat's first reply (the seeded question is answered here)
            val send = """{"chatId":"${alice.chatId}","provider":"openai","modelName":"gpt-4o",""" +
                """"messages":[{"role":"user","text":"Q1"}],"persistUserMessage":false}"""
            val stream = browser.sse("/api/chat/send", method = "POST", body = send)
            val runId = stream.expect("run")["runId"]!!.jsonPrimitive.content
            stream.nextOf("complete")
            assertNull(stream.nextOrNull(), "the response ends while the title is still being generated")
            title.awaitStarted()

            title.gate.complete(Unit)
            val updated = account.nextOf("chat_updated")
            assertEquals(runId, updated["runId"]!!.jsonPrimitive.content)
            assertEquals(alice.chatId, updated["chatId"]!!.jsonPrimitive.content)
            assertEquals("Generated title", alice.chat().preview_name)
        }
    }

    @Test
    fun `no chat_updated when the title did not change, failed or took too long`() {
        val world = world()
        val alice = world.user(ALICE)
        val answer = java.util.concurrent.atomic.AtomicReference<suspend () -> String> { "" }
        val title = TitleGenerator { _, _, _ -> answer.get()() }
        val registry = RunRegistry(titleTimeoutMs = 300)
        LiveServer(world.storage, ReplyEngine(), whoami(), titleGenerator = title, runRegistry = registry).use { server ->
            val client = server.client()
            val stream = client.sse("/api/runs/stream")
            stream.expect("snapshot")

            // A blank title (generation failed): the name stays
            client.start(body("run-1", alice, emptyList()))
            stream.nextOf("complete")
            assertTrue(runCatching { stream.next(500) }.isFailure)
            assertEquals(listOf("Q1", "Cloud reply"), alice.texts())

            // The second reply of a chat generates no title at all
            alice.repo.addUserMessageAsNewNode(ALICE, alice.chatId, Message(role = "user", text = "Q2"))
            answer.set { "Never used" }
            client.start(body("run-2", alice, emptyList()))
            stream.nextOf("complete")
            assertTrue(runCatching { stream.next(500) }.isFailure)

            // The third does (title update on extension): one that never finishes is given up
            alice.repo.addUserMessageAsNewNode(ALICE, alice.chatId, Message(role = "user", text = "Q3"))
            answer.set { awaitCancellation() }
            client.start(body("run-3", alice, emptyList()))
            stream.nextOf("complete")
            assertTrue(runCatching { stream.next(900) }.isFailure)
            assertEquals("Chat of $ALICE", alice.chat().preview_name)
        }
    }
}
