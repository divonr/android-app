package com.example.ApI.server

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.RemoteSyncSettings
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.auth.FakeSyncAuthClient
import com.example.ApI.server.auth.RealSyncAuthClient
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.tools.ToolSpecification
import com.example.ApI.util.JsonConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
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
 * The web as a sync device, end to end against an in-process CAS sync server
 * ([FakeCasSyncServer]): the real login callback (with the real sync-auth client), the real
 * per-user SyncEngine and pull loop.  Scenario S1 (a brand-new account logging into the web),
 * a returning login, user isolation, live-refresh tick, re-authentication, startup rehydration
 * and the settings privacy rules.
 */
class WebSyncTest {

    private lateinit var fake: FakeCasSyncServer

    @BeforeTest
    fun startFake() {
        fake = FakeCasSyncServer().start()
    }

    @AfterTest
    fun stopFake() {
        fake.stop()
    }

    private fun username(email: String) = FakeSyncAuthClient.sanitizeEmail(email)

    private fun ApplicationTestBuilder.startWithSync(
        storage: ServerPlatformStorage,
        chatEngineFactory: ((DataRepository) -> ChatEngine)? = null
    ) = startWithFakeGoogleAuth(
        storage,
        startRegistry = true,
        syncAuthClient = RealSyncAuthClient(fake.baseUrl),
        syncServerUrl = fake.baseUrl,
        // Periodic pulls off: the tests trigger every pull they rely on
        pullIntervalSeconds = 3_600,
        chatEngineFactory = chatEngineFactory
    )

    private fun llmDataDir(storage: ServerPlatformStorage, user: String) = File(storage.baseDir, "users/$user/files/llm_data")

    private fun localHistory(storage: ServerPlatformStorage, user: String): UserChatHistory? =
        File(llmDataDir(storage, user), "chat_history_$user.json").takeIf { it.exists() }
            ?.let { JsonConfig.prettyPrint.decodeFromString<UserChatHistory>(it.readText()) }

    /** A chat history file of [user] with one chat [title] holding a question and an answer. */
    private fun historyJson(user: String, title: String, chatId: String = "chat-$title"): String {
        val dir = File(System.getProperty("java.io.tmpdir"), "web-sync-seed-${System.nanoTime()}")
        val repo = DataRepository(ServerPlatformStorage(baseDir = dir))
        repo.saveChatHistory(user, UserChatHistory(user, emptyList()))
        repo.updateChatHistory(user) { h ->
            h.copy(chat_history = h.chat_history + com.example.ApI.data.model.Chat(chat_id = chatId, preview_name = title, messages = emptyList()))
        }
        repo.addUserMessageAsNewNode(user, chatId, Message(role = "user", text = "Q of $title"))
        repo.addResponseToCurrentVariant(user, chatId, Message(role = "assistant", text = "A of $title"))
        return File(dir, "files/llm_data/chat_history_$user.json").readText()
    }

    private fun serverHistory(user: String): UserChatHistory? =
        fake.content(user, "chat_history_$user.json")?.let { JsonConfig.prettyPrint.decodeFromString<UserChatHistory>(it) }

    private suspend fun HttpClient.chatTitles(): List<String> {
        val r = get("/api/chats")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["chat_history"]!!.jsonArray
            .map { it.jsonObject["preview_name"]!!.jsonPrimitive.content }
    }

    private suspend fun HttpClient.syncStatus(): JsonObject =
        Json.parseToJsonElement(get("/api/sync/status?probe=false").bodyAsText()).jsonObject

    private suspend fun awaitTrue(timeoutMs: Long = 10_000, what: String, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            delay(25)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private val replyEngine = object : ChatEngine {
        override suspend fun send(
            provider: Provider, modelName: String, messages: List<Message>, systemPrompt: String,
            username: String, chatId: String, projectAttachments: List<Attachment>, webSearchEnabled: Boolean,
            enabledTools: List<ToolSpecification>, thinkingBudget: ThinkingBudgetValue, temperature: Float?,
            callback: StreamingCallback
        ) {
            callback.onPartialResponse("Web reply")
            callback.onComplete("Web reply")
        }
    }

    // ── S1: a brand-new account logs into the web first ───────────────────────

    @Test
    fun `S1 a brand-new account works right after its first login and its chats reach the sync server`() = testApplication {
        val storage = tempStorage()
        startWithSync(storage) { replyEngine }
        val email = "new.user@example.com"
        val u = username(email)
        assertTrue(fake.filenames(u).isEmpty())

        val c = googleLogin(email)

        // A working account immediately: authenticated, empty chat list
        assertEquals(HttpStatusCode.OK, c.get("/api/session").status)
        assertEquals(emptyList(), c.chatTitles())
        // Exactly one pull at login (no double pull)
        assertEquals(1, fake.requestCount("GET /sync/manifest"), "login runs a single pull: ${fake.requests}")
        // The device's settings reached the account (device-local keys stripped of the token)
        val settings = fake.awaitContent(u, "app_settings.json") { true }
        assertNotNull(settings, "app_settings uploaded")
        assertFalse(settings.contains("tok-"), "the sync token is never uploaded")

        // Creating a chat (and talking in it) uploads chat_history
        val created = c.post("/api/chats") {
            contentType(ContentType.Application.Json); setBody("""{"previewName":"My first chat"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val chatId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["chat_id"]!!.jsonPrimitive.content
        val send = c.post("/api/chat/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","provider":"openai","modelName":"gpt-4o","messages":[{"role":"user","text":"Hello sync"}]}""")
        }
        assertEquals(HttpStatusCode.OK, send.status)

        val uploaded = fake.awaitContent(u, "chat_history_$u.json") { it.contains("Web reply") }
        assertNotNull(uploaded, "the chat and its reply reach the sync server; server has ${fake.filenames(u)}")
        val chat = serverHistory(u)!!.chat_history.single()
        assertEquals("My first chat", chat.preview_name)
        assertEquals(listOf("Hello sync", "Web reply"), chat.messages.map { it.text })
        assertEquals(u, serverHistory(u)!!.user_name)
    }

    @Test
    fun `a returning account sees its chats and settings as soon as the login callback returns`() = testApplication {
        val email = "returning@example.com"
        val u = username(email)
        fake.seed(u, "chat_history_$u.json", historyJson(u, "From the phone"))
        // The account's settings, written by a phone whose local user name differs
        val phoneSettings = AppSettings(
            current_user = "phone_local_name", selected_provider = "anthropic", selected_model = "claude-x",
            remoteSync = RemoteSyncSettings(enabled = true, authToken = "phone-secret")
        )
        fake.seed(u, "app_settings.json", JsonConfig.prettyPrint.encodeToString(AppSettings.serializer(), phoneSettings))

        val storage = tempStorage()
        startWithSync(storage)
        val c = googleLogin(email)

        // No waiting: the callback waited for the first pull
        assertEquals(listOf("From the phone"), localHistory(storage, u)?.chat_history?.map { it.preview_name })
        assertEquals(listOf("From the phone"), c.chatTitles())
        assertEquals(1, fake.requestCount("GET /sync/manifest"), "login runs a single pull")

        val s = Json.parseToJsonElement(c.get("/api/settings").bodyAsText()).jsonObject
        assertEquals("claude-x", s["selected_model"]!!.jsonPrimitive.content, "the account's settings win on a new device")
        assertEquals(u, s["current_user"]!!.jsonPrimitive.content, "current_user is pinned to the session user, never adopted")
        val rs = s["remoteSync"]!!.jsonObject
        assertEquals("", rs["authToken"]!!.jsonPrimitive.content, "the token never reaches the browser")
        assertEquals(true, rs["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(fake.baseUrl, rs["serverBaseUrl"]!!.jsonPrimitive.content, "the phone's sync config is never adopted")
    }

    @Test
    fun `two web users are isolated locally and on the sync server`() = testApplication {
        val storage = tempStorage()
        startWithSync(storage)
        val a = googleLogin("alice@example.com")
        val b = googleLogin("bob@example.com")
        val ua = username("alice@example.com")
        val ub = username("bob@example.com")

        for ((client, title) in listOf(a to "Alice chat", b to "Bob chat")) {
            val r = client.post("/api/chats") {
                contentType(ContentType.Application.Json); setBody("""{"previewName":"$title"}""")
            }
            assertEquals(HttpStatusCode.Created, r.status)
        }

        assertNotNull(fake.awaitContent(ua, "chat_history_$ua.json") { it.contains("Alice chat") })
        assertNotNull(fake.awaitContent(ub, "chat_history_$ub.json") { it.contains("Bob chat") })
        assertFalse(fake.content(ua, "chat_history_$ua.json")!!.contains("Bob chat"))
        assertFalse(fake.content(ub, "chat_history_$ub.json")!!.contains("Alice chat"))
        assertTrue(fake.filenames(ua).none { it.contains(ub) } && fake.filenames(ub).none { it.contains(ua) })
        assertEquals(listOf("Alice chat"), a.chatTitles())
        assertEquals(listOf("Bob chat"), b.chatTitles())
    }

    // ── Live refresh / re-auth ────────────────────────────────────────────────

    @Test
    fun `a pull that changes local files changes lastChangeTick`() = testApplication {
        val email = "ticker@example.com"
        val u = username(email)
        val storage = tempStorage()
        startWithSync(storage)
        val c = googleLogin(email)
        val before = c.syncStatus()["lastChangeTick"]!!.jsonPrimitive.long

        // Another device adds a chat
        fake.seed(u, "chat_history_$u.json", historyJson(u, "Added elsewhere"))
        assertEquals(HttpStatusCode.OK, c.post("/api/sync/pull").status)
        awaitTrue(what = "lastChangeTick to change") { c.syncStatus()["lastChangeTick"]!!.jsonPrimitive.long != before }
        assertEquals(listOf("Added elsewhere"), c.chatTitles())

        // A pull that changes nothing keeps the tick
        val after = c.syncStatus()["lastChangeTick"]!!.jsonPrimitive.long
        val manifests = fake.requestCount("GET /sync/manifest")
        c.post("/api/sync/pull")
        awaitTrue(what = "the second pull") { fake.requestCount("GET /sync/manifest") > manifests }
        delay(300)
        assertEquals(after, c.syncStatus()["lastChangeTick"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a revoked token surfaces needsReauth and logging in again clears it`() = testApplication {
        val email = "reauth@example.com"
        val u = username(email)
        val storage = tempStorage()
        startWithSync(storage)
        val c = googleLogin(email)
        val status = c.syncStatus()
        assertEquals(false, status["needsReauth"]!!.jsonPrimitive.boolean)
        assertEquals(false, status["serverLacksCas"]!!.jsonPrimitive.boolean)
        assertEquals(email, status["accountEmail"]!!.jsonPrimitive.content)

        fake.revokeAll(u)
        c.post("/api/sync/pull")
        awaitTrue(what = "needsReauth") { c.syncStatus()["needsReauth"]!!.jsonPrimitive.boolean }

        // Re-login mints a new token, clears the flag and syncs again right away
        fake.seed(u, "chat_history_$u.json", historyJson(u, "After reauth"))
        val c2 = googleLogin(email)
        assertEquals(false, c2.syncStatus()["needsReauth"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("After reauth"), c2.chatTitles())
    }

    // ── Startup rehydration ───────────────────────────────────────────────────

    @Test
    fun `server startup resumes every signed-in user's sync before any request`() = testApplication {
        val storage = tempStorage()
        val users = listOf("rehydrated_one", "rehydrated_two")
        for (u in users) {
            fake.seed(u, "chat_history_$u.json", historyJson(u, "Server chat of $u"))
            val repo = DataRepository(ServerPlatformStorage(File(storage.baseDir, "users/$u")))
            // current_user left at "default": the registry pins it to the dir's user
            repo.saveAppSettings(
                AppSettings(
                    current_user = "default", selected_provider = "openai", selected_model = "gpt-4o",
                    remoteSync = RemoteSyncSettings(enabled = true, serverBaseUrl = fake.baseUrl, authToken = fake.tokenFor(u))
                )
            )
        }
        // A user without sync is left alone
        DataRepository(ServerPlatformStorage(File(storage.baseDir, "users/offline_user")))
            .saveAppSettings(AppSettings(current_user = "offline_user", selected_provider = "openai", selected_model = "gpt-4o"))

        startWithSync(storage)
        startApplication()

        for (u in users) {
            awaitTrue(what = "rehydrated pull of $u") {
                localHistory(storage, u)?.chat_history?.map { it.preview_name } == listOf("Server chat of $u")
            }
            val settings = JsonConfig.prettyPrint.decodeFromString<AppSettings>(File(llmDataDir(storage, u), "app_settings.json").readText())
            assertEquals(u, settings.current_user)
        }
        assertTrue(fake.filenames("offline_user").isEmpty())
    }

    @Test
    fun `rehydrateAll starts only users with sync credentials and stopAll stops them`() {
        val baseDir = tempStorage().baseDir
        val synced = "synced_user"
        DataRepository(ServerPlatformStorage(File(baseDir, "users/$synced"))).saveAppSettings(
            AppSettings(
                current_user = synced, selected_provider = "openai", selected_model = "gpt-4o",
                remoteSync = RemoteSyncSettings(enabled = true, serverBaseUrl = fake.baseUrl, authToken = fake.tokenFor(synced))
            )
        )
        DataRepository(ServerPlatformStorage(File(baseDir, "users/no_token"))).saveAppSettings(
            AppSettings(current_user = "no_token", selected_provider = "openai", selected_model = "gpt-4o",
                remoteSync = RemoteSyncSettings(enabled = true, serverBaseUrl = fake.baseUrl, authToken = ""))
        )
        File(baseDir, "users/no_settings").mkdirs()

        val registry = UserRegistry(baseDir, syncServerUrl = fake.baseUrl, pullIntervalSeconds = 3_600)
        assertEquals(listOf(synced), runBlocking { registry.rehydrateAll() })
        assertEquals(setOf(synced), registry.syncingUsers())
        registry.stopAll()
        assertTrue(registry.syncingUsers().isEmpty())

        val disabled = UserRegistry(baseDir, syncServerUrl = fake.baseUrl, startEngine = false)
        assertEquals(emptyList(), runBlocking { disabled.rehydrateAll() })
    }

    // ── Settings privacy ──────────────────────────────────────────────────────

    @Test
    fun `PATCH settings changes only syncApiKeys of remoteSync and never returns the token`() = testApplication {
        val email = "settings@example.com"
        val u = username(email)
        val storage = tempStorage()
        startWithSync(storage)
        val c = googleLogin(email)
        val tokenBefore = JsonConfig.prettyPrint.decodeFromString<AppSettings>(
            File(llmDataDir(storage, u), "app_settings.json").readText()
        ).remoteSync.authToken
        assertTrue(tokenBefore.isNotBlank())

        val r = c.patch("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"selected_model":"gpt-x","remoteSync":{"enabled":false,"serverBaseUrl":"http://evil.example","authToken":"stolen","accountEmail":"x@y","syncApiKeys":true}}"""
            )
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val returned = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("", returned["remoteSync"]!!.jsonObject["authToken"]!!.jsonPrimitive.content)

        val saved = JsonConfig.prettyPrint.decodeFromString<AppSettings>(File(llmDataDir(storage, u), "app_settings.json").readText())
        assertEquals("gpt-x", saved.selected_model)
        assertEquals(true, saved.remoteSync.enabled, "the browser can't turn the server's sync off")
        assertEquals(fake.baseUrl, saved.remoteSync.serverBaseUrl, "the browser can't redirect the server's sync")
        assertEquals(tokenBefore, saved.remoteSync.authToken)
        assertEquals(email, saved.remoteSync.accountEmail)
        assertEquals(true, saved.remoteSync.syncApiKeys, "the per-device API-key opt-in is the one browser-editable field")
    }
}
