package com.example.ApI.server

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.RemoteSyncSettings
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.data.repository.LocalStorageManager
import com.example.ApI.data.sync.GoogleIdentity
import com.example.ApI.data.sync.SyncEngine
import com.example.ApI.server.auth.FakeSyncAuthClient
import com.example.ApI.server.auth.RealSyncAuthClient
import com.example.ApI.server.auth.RealSyncWhoamiClient
import com.example.ApI.server.runs.RunStatus
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.util.JsonConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cloud runs end to end with sync: a simulated phone (a real [DataRepository] + [SyncEngine] in
 * its own data dir), an in-process CAS sync server ([FakeCasSyncServer]) and the web backend as
 * the account's other sync device.  The phone authenticates with its sync token (bearer), the
 * server runs the request, saves the reply into its copy and syncs it; the phone gets it by sync.
 */
class CloudRunsSyncTest {

    private lateinit var fake: FakeCasSyncServer
    private val devices = mutableListOf<Phone>()

    @BeforeTest
    fun startFake() {
        fake = FakeCasSyncServer().start()
    }

    @AfterTest
    fun stopFake() {
        runBlocking { devices.forEach { it.repo.syncEngine.closeAndJoin() } }
        fake.stop()
    }

    /**
     * A signed-in phone.  Its debounced uploads run only when [flush]ed (like the shared sync
     * tests' SimDevice), so a test decides what the sync server has seen.
     */
    private inner class Phone(email: String) {
        val user = FakeSyncAuthClient.sanitizeEmail(email)
        private val root = File(System.getProperty("java.io.tmpdir"), "phone-${System.nanoTime()}").apply { mkdirs() }
        private val dir = File(root, "files/llm_data").apply { mkdirs() }
        val repo: DataRepository

        init {
            val json = JsonConfig.prettyPrint
            LocalStorageManager(dir, json).saveAppSettings(
                AppSettings(
                    current_user = "default", selected_provider = "openai", selected_model = "gpt-4o",
                    remoteSync = RemoteSyncSettings(serverBaseUrl = fake.baseUrl, syncApiKeys = true)
                )
            )
            // Registered first: the repository then shares this manually-flushed engine
            SyncEngine.forDir(dir, json, 3_600_000L, 0L) { LocalStorageManager(dir, json).loadAppSettings() }
            repo = DataRepository(ServerPlatformStorage(root))
            runBlocking { repo.signInToSync(GoogleIdentity("fake-id-token-$email", email)).getOrThrow() }
            assertEquals(user, repo.loadAppSettings().current_user)
            devices += this
        }

        val token: String get() = repo.loadAppSettings().remoteSync.authToken

        suspend fun flush() = repo.syncEngine.flushPendingUploads()
        suspend fun sync() {
            flush()
            repo.syncEngine.pull()
        }

        fun history(): UserChatHistory = repo.loadChatHistory(user)
        fun chat(chatId: String): Chat = history().chat_history.single { it.chat_id == chatId }

        /** Save a question the way the app does before sending. */
        fun ask(chatId: String, text: String): List<Message> {
            repo.addUserMessageAsNewNode(user, chatId, Message(role = "user", text = text))
            return chat(chatId).messages
        }
    }

    private fun live(storage: ServerPlatformStorage, engine: ChatEngine) = LiveServer(
        storage, engine,
        whoami = RealSyncWhoamiClient(fake.baseUrl),
        startRegistry = true,
        syncAuthClient = RealSyncAuthClient(fake.baseUrl),
        syncServerUrl = fake.baseUrl
        // Periodic pulls are off (LiveServer's default): the tests trigger every pull they rely on
    )

    private fun serverCopy(storage: ServerPlatformStorage, user: String): UserChatHistory? =
        File(storage.baseDir, "users/$user/files/llm_data/chat_history_$user.json").takeIf { it.exists() }
            ?.let { JsonConfig.prettyPrint.decodeFromString<UserChatHistory>(it.readText()) }

    private fun syncServerCopy(user: String): UserChatHistory? =
        fake.content(user, "chat_history_$user.json")?.let { JsonConfig.prettyPrint.decodeFromString<UserChatHistory>(it) }

    private fun LiveClient.runStatus(runId: String): String? = sse("/api/runs/stream").use { stream ->
        stream.expect("snapshot")["runs"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it["runId"]!!.jsonPrimitive.content == runId }
            ?.get("status")?.jsonPrimitive?.content
    }

    private fun LiveClient.awaitRun(runId: String, status: String = RunStatus.COMPLETE) =
        awaitTrue(what = "run $runId to be $status") { runStatus(runId) == status }

    /** The texts of every message in the chat's tree, to catch duplicates off the current path. */
    private fun Chat.treeTexts(): List<String> =
        messageNodes.flatMap { node -> node.variants.flatMap { v -> listOf(v.userMessage.text) + v.responses.map { it.text } } }

    @Test
    fun `a phone's bearer token bootstraps the server copy of a user who never logged in on the web`() {
        val email = "bearer.only@example.com"
        val phone = Phone(email)
        val chatId = phone.repo.createNewChat(phone.user, "From the phone").chat_id
        phone.ask(chatId, "Hello")
        phone.repo.addApiKey(phone.user, ApiKey(id = "k1", provider = "openai", key = "sk-phone-key", isActive = true))
        runBlocking { phone.sync() }

        val storage = tempStorage()
        live(storage, ReplyEngine()).use { server ->
            assertFalse(File(storage.baseDir, "users/${phone.user}").exists())
            val client = server.client(phone.token)

            // The first bearer call waits for the first pull, like a login: the data is there at once
            val chats = client.get("/api/chats")
            assertEquals(200, chats.status, chats.body)
            assertTrue(chats.body.contains("From the phone"))
            assertEquals(listOf("From the phone"), serverCopy(storage, phone.user)?.chat_history?.map { it.preview_name })

            val settings = client.get("/api/settings").json()
            assertEquals(phone.user, settings["current_user"]!!.jsonPrimitive.content)
            val remote = settings["remoteSync"]!!.jsonObject
            assertEquals(true, remote["enabled"]!!.jsonPrimitive.boolean)
            assertEquals(fake.baseUrl, remote["serverBaseUrl"]!!.jsonPrimitive.content)
            assertEquals("", remote["authToken"]!!.jsonPrimitive.content, "the token never leaves the server")
            assertEquals(email, remote["accountEmail"]!!.jsonPrimitive.content)
            assertEquals(true, remote["syncApiKeys"]!!.jsonPrimitive.boolean, "a cloud-run device's server copy syncs the keys")

            // The account's keys reached the server copy (masked on the wire)
            val keys = client.get("/api/keys").body
            assertTrue(keys.contains("openai") && keys.contains("...-key"), keys)

            // One bootstrap: the next calls don't pull again, and whoami is cached
            val manifests = fake.requestCount("GET /sync/manifest")
            client.get("/api/chats")
            client.get("/api/me")
            assertEquals(manifests, fake.requestCount("GET /sync/manifest"))
            assertEquals(1, fake.requestCount("GET /auth/whoami"))

            // A token the sync server does not know never works
            assertEquals(401, server.client("tok-unknown").get("/api/me").status)
        }
    }

    @Test
    fun `a bearer bootstrap keeps the api-key sync choice of a user who logged in on the web`() {
        val email = "web.user@example.com"
        val phone = Phone(email)
        phone.repo.addApiKey(phone.user, ApiKey(id = "k1", provider = "openai", key = "sk-phone-key", isActive = true))
        val chatId = phone.repo.createNewChat(phone.user, "Chat").chat_id
        val path = phone.ask(chatId, "Hello")
        runBlocking { phone.sync() }

        val storage = tempStorage()
        live(storage, ReplyEngine()).use { server ->
            // The user is signed in on the web, with API-key sync off there (the default)
            val browser = server.login(email)
            assertEquals(false, browser.get("/api/settings").json()["remoteSync"]!!.jsonObject["syncApiKeys"]!!.jsonPrimitive.boolean)

            val device = server.client(phone.token)
            assertEquals(200, device.get("/api/me").status)
            assertEquals(
                false, browser.get("/api/settings").json()["remoteSync"]!!.jsonObject["syncApiKeys"]!!.jsonPrimitive.boolean,
                "a device's bearer token does not change the web's choice"
            )
            // …so the server copy has no keys and the device is told to run the request itself
            val rejected = device.post("/api/runs", runBody("run-1", chatId, path))
            assertEquals(409, rejected.status, rejected.body)
            assertEquals("no_api_key", rejected.json()["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a run whose chat is not in the server copy yet saves its reply and merges cleanly with the phone's upload`() {
        val email = "racer@example.com"
        val phone = Phone(email)
        phone.repo.addApiKey(phone.user, ApiKey(id = "k1", provider = "openai", key = "sk-phone-key", isActive = true))
        runBlocking { phone.sync() }  // the keys are on the sync server; no chat yet

        val storage = tempStorage()
        val engine = ReplyEngine("Cloud reply")
        live(storage, engine).use { server ->
            val client = server.client(phone.token)

            // The phone creates a chat and asks; its upload has not happened yet (debounce / offline)
            val chatId = phone.repo.createNewChat(phone.user, "Phone chat", systemPrompt = "Be brief").chat_id
            val path = phone.ask(chatId, "What is up?")
            assertTrue(syncServerCopy(phone.user)?.chat_history.isNullOrEmpty(), "the sync server has not seen the chat")

            val started = client.post("/api/runs", runBody("run-1", chatId, path))
            assertEquals(202, started.status, started.body)
            client.awaitRun("run-1")

            // The server saved the reply under the question it answers, restored from the request
            val onServer = serverCopy(storage, phone.user)!!.chat_history.single { it.chat_id == chatId }
            assertEquals(listOf("What is up?", "Cloud reply"), onServer.messages.map { it.text })
            assertEquals(path.single().id, onServer.messages.first().id)
            // `complete` came after the upload flush: the sync server already has the reply
            assertTrue(fake.content(phone.user, "chat_history_${phone.user}.json")!!.contains("Cloud reply"))

            // Now the phone's own upload of the same chat arrives and merges with the server's copy
            runBlocking { phone.sync() }
            val onPhone = phone.chat(chatId)
            assertEquals(listOf("What is up?", "Cloud reply"), onPhone.messages.map { it.text })
            assertEquals(listOf("What is up?", "Cloud reply"), onPhone.treeTexts(), "one question, one reply, no duplicate branch")
            assertEquals(1, onPhone.messageNodes.size)
            assertEquals(1, onPhone.messageNodes.single().variants.size)
            assertEquals(path.single().variantId, onPhone.messageNodes.single().variants.single().variantId)
            assertEquals("Phone chat", onPhone.preview_name, "the phone's chat (name, system prompt) is the chat")
            assertEquals("Be brief", onPhone.systemPrompt)
            assertEquals(1, phone.history().chat_history.count { it.chat_id == chatId })

            // …and the server copy converges to the same chat
            client.post("/api/sync/pull")
            awaitTrue(what = "the server copy to converge") {
                val c = serverCopy(storage, phone.user)?.chat_history?.singleOrNull { it.chat_id == chatId }
                c != null && c.preview_name == "Phone chat" && c.messages.map { it.text } == listOf("What is up?", "Cloud reply")
            }
            val converged = serverCopy(storage, phone.user)!!.chat_history.single { it.chat_id == chatId }
            assertEquals(onPhone.messageNodes, converged.messageNodes)
            assertEquals(onPhone.currentVariantPath, converged.currentVariantPath)
            assertEquals("Be brief", converged.systemPrompt)
            assertEquals(onPhone.messageNodes, syncServerCopy(phone.user)!!.chat_history.single().messageNodes)
            assertEquals(1, engine.calls.get())

            // The conversation goes on from the phone, in the cloud
            val path2 = phone.ask(chatId, "And then?")
            val second = client.post("/api/runs", runBody("run-2", chatId, path2))
            assertEquals(202, second.status, second.body)
            client.awaitRun("run-2")
            runBlocking { phone.sync() }
            val texts = listOf("What is up?", "Cloud reply", "And then?", "Cloud reply")
            assertEquals(texts, phone.chat(chatId).messages.map { it.text })
            assertEquals(texts, phone.chat(chatId).treeTexts())
        }
    }

    @Test
    fun `a run whose question has not reached the server copy yet lands under that question after the merge`() {
        val email = "followup@example.com"
        val phone = Phone(email)
        phone.repo.addApiKey(phone.user, ApiKey(id = "k1", provider = "openai", key = "sk-phone-key", isActive = true))
        val chatId = phone.repo.createNewChat(phone.user, "Long chat").chat_id
        phone.ask(chatId, "First question")
        phone.repo.addAnchoredResponse(
            phone.user, chatId, Message(role = "assistant", text = "First answer", model = "m"),
            com.example.ApI.data.repository.ReplyAnchor.forRequest(phone.chat(chatId).messages)
        )
        runBlocking { phone.sync() }

        val storage = tempStorage()
        live(storage, ReplyEngine("Second answer")).use { server ->
            val client = server.client(phone.token)
            // The server copy has the chat (pulled at the bearer bootstrap)…
            assertEquals(200, client.get("/api/me").status)
            assertEquals(
                listOf("First question", "First answer"),
                serverCopy(storage, phone.user)!!.chat_history.single().messages.map { it.text }
            )

            // …but not the follow-up question the phone just saved
            val path = phone.ask(chatId, "Second question")
            val started = client.post("/api/runs", runBody("run-1", chatId, path))
            assertEquals(202, started.status, started.body)
            client.awaitRun("run-1")

            runBlocking { phone.sync() }
            val texts = listOf("First question", "First answer", "Second question", "Second answer")
            val onPhone = phone.chat(chatId)
            assertEquals(texts, onPhone.messages.map { it.text })
            assertEquals(texts, onPhone.treeTexts(), "no duplicated question or branch")
            assertEquals(2, onPhone.messageNodes.size)
            assertTrue(onPhone.messageNodes.all { it.variants.size == 1 })

            client.post("/api/sync/pull")
            awaitTrue(what = "the server copy to converge") {
                serverCopy(storage, phone.user)?.chat_history?.singleOrNull()?.messageNodes == onPhone.messageNodes
            }
            assertNotNull(syncServerCopy(phone.user))
            assertEquals(onPhone.messageNodes, syncServerCopy(phone.user)!!.chat_history.single().messageNodes)
        }
    }

    @Test
    fun `the reply of a run started by the web's send route reaches a phone by sync`() {
        val email = "web.first@example.com"
        val phone = Phone(email)
        val chatId = phone.repo.createNewChat(phone.user, "Shared chat").chat_id
        runBlocking { phone.sync() }

        val storage = tempStorage()
        live(storage, ReplyEngine("Web reply")).use { server ->
            val browser = server.login(email)
            val send = browser.post(
                "/api/chat/send",
                """{"chatId":"$chatId","provider":"openai","modelName":"gpt-4o","messages":[{"role":"user","text":"From the web"}]}"""
            )
            assertEquals(200, send.status, send.body)
            // The response ended with `complete`, which is emitted after the upload flush
            assertTrue(send.body.contains("event: complete"), send.body)
            assertTrue(fake.content(phone.user, "chat_history_${phone.user}.json")!!.contains("Web reply"))

            runBlocking { phone.sync() }
            assertEquals(listOf("From the web", "Web reply"), phone.chat(chatId).messages.map { it.text })
        }
    }
}
