package com.example.ApI.data.sync.merge

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.RemoteSyncSettings
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.util.JsonConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncFileMergerTest {

    private val json = JsonConfig.prettyPrint

    private fun settings(
        user: String = "u",
        model: String = "m",
        multi: Boolean = false,
        tools: List<String> = emptyList(),
        sync: RemoteSyncSettings = RemoteSyncSettings()
    ) = AppSettings(
        current_user = user, selected_provider = "p", selected_model = model,
        multiMessageMode = multi, enabledTools = tools, remoteSync = sync
    )

    private fun enc(s: AppSettings) = json.encodeToString(s)
    private fun dec(text: String) = json.decodeFromString<AppSettings>(text)

    @Test
    fun `app settings device local keys are never adopted`() {
        val mine = RemoteSyncSettings(enabled = true, authToken = "secret", accountEmail = "a@b")
        val base = settings(user = "acct", sync = mine)
        val local = settings(user = "acct", sync = mine, tools = listOf("t1"))
        // the uploaded blob has remoteSync stripped and another device's current_user
        val remote = settings(user = "someone-else", model = "m2", tools = listOf("t2"))
        val m = dec(SyncFileMerger.mergeFile("app_settings.json", enc(base), enc(local), enc(remote), json))
        assertEquals("acct", m.current_user)
        assertEquals(mine, m.remoteSync)
        assertEquals("m2", m.selected_model)
        assertEquals(listOf("t1", "t2"), m.enabledTools)
    }

    @Test
    fun `app settings reset to default is a change`() {
        val base = settings(multi = true)
        val local = settings(multi = false)   // encoded without the key (encodeDefaults=false)
        val remote = settings(multi = true, model = "m2")
        val m = dec(SyncFileMerger.mergeFile("app_settings.json", enc(base), enc(local), enc(remote), json))
        assertFalse(m.multiMessageMode)
        assertEquals("m2", m.selected_model)
    }

    @Test
    fun `app settings without base take the account values except device keys`() {
        val local = settings(user = "acct", sync = RemoteSyncSettings(enabled = true, authToken = "t"))
        val remote = settings(user = "x", model = "account-model", multi = true)
        val m = dec(SyncFileMerger.mergeFile("app_settings.json", null, enc(local), enc(remote), json))
        assertEquals("account-model", m.selected_model)
        assertTrue(m.multiMessageMode)
        assertEquals("acct", m.current_user)
        assertEquals("t", m.remoteSync.authToken)
    }

    @Test
    fun `api keys union by id`() {
        val s = ListSerializer(ApiKey.serializer())
        val k1 = ApiKey(id = "1", provider = "openai", key = "a")
        val base = listOf(k1)
        val local = listOf(k1.copy(isActive = false), ApiKey(id = "L", provider = "google", key = "g"))
        val remote = listOf(k1, ApiKey(id = "R", provider = "poe", key = "p"))
        val m = json.decodeFromString(s, SyncFileMerger.mergeFile("api_keys_u.json",
            json.encodeToString(s, base), json.encodeToString(s, local), json.encodeToString(s, remote), json))
        assertEquals(listOf("1", "L", "R"), m.map { it.id })
        assertFalse(m.first().isActive)
        // no base → union too
        val m2 = json.decodeFromString(s, SyncFileMerger.mergeFile("api_keys_u.json",
            null, json.encodeToString(s, local), json.encodeToString(s, remote), json))
        assertEquals(setOf("1", "L", "R"), m2.map { it.id }.toSet())
    }

    @Test
    fun `api keys stored without ids get deterministic ids`() {
        val raw = """[{"provider":"openai","key":"a"}]"""
        val m1 = SyncFileMerger.mergeFile("api_keys_u.json", null, raw, raw, json)
        val m2 = SyncFileMerger.mergeFile("api_keys_u.json", null, raw, raw, json)
        assertEquals(m1, m2)
        assertEquals(1, json.decodeFromString(ListSerializer(ApiKey.serializer()), m1).size)
    }

    @Test
    fun `skills maps merge per key`() {
        val s = MapSerializer(String.serializer(), Boolean.serializer())
        val m = json.decodeFromString(s, SyncFileMerger.mergeFile("skills_enabled.json",
            """{"a":true,"b":true}""", """{"a":false,"b":true}""", """{"a":true,"b":true,"c":true}""", json))
        assertEquals(mapOf("a" to false, "b" to true, "c" to true), m)
    }

    @Test
    fun `auth files are atomic`() {
        val base = """{"auth":{"accessToken":"b","scope":"s","createdAt":1},"user":${user("b")},"connectedAt":1}"""
        val local = """{"auth":{"accessToken":"l","scope":"s","createdAt":2},"user":${user("b")},"connectedAt":2}"""
        val remote = """{"auth":{"accessToken":"r","scope":"s","createdAt":3},"user":${user("r")},"connectedAt":3}"""
        val m = SyncFileMerger.mergeFile("github_auth_u.json", base, local, remote, json)
        assertTrue("\"l\"" in m && "\"r\"" !in m)
    }

    @Test
    fun `a disconnected auth file merges 3-way like any value`() {
        val conn = """{"auth":{"accessToken":"b","scope":"s","createdAt":1},"user":${user("b")},"connectedAt":1}"""
        val other = """{"auth":{"accessToken":"n","scope":"s","createdAt":5},"user":${user("n")},"connectedAt":5}"""
        for (name in listOf("github_auth_u.json")) {
            assertTrue(SyncFileMerger.isValid(name, "null", json), "the disconnected marker is a valid copy")
            // Disconnected on one side, untouched on the other → disconnected (both directions)
            assertEquals("null", SyncFileMerger.mergeFile(name, conn, "null", conn, json).trim())
            assertEquals("null", SyncFileMerger.mergeFile(name, conn, conn, "null", json).trim())
            assertEquals("null", SyncFileMerger.tryMergeFile(name, conn, conn, "null", json)?.trim())
            // Reconnected on one side after the other disconnected → the new connection
            assertTrue("\"n\"" in SyncFileMerger.mergeFile(name, conn, other, "null", json))
            assertTrue("\"n\"" in SyncFileMerger.mergeFile(name, "null", "null", other, json))
            // Disconnect racing a reconnect (both changed) → the connection, in both directions
            // (like the settings' per-key connection entry: modification beats deletion)
            assertTrue("\"n\"" in SyncFileMerger.mergeFile(name, conn, "null", other, json))
            assertTrue("\"n\"" in SyncFileMerger.mergeFile(name, conn, other, "null", json))
            // No base: null is absence (union), so an old disconnect never wipes a connection
            assertTrue("\"b\"" in SyncFileMerger.mergeFile(name, null, conn, "null", json))
            assertTrue("\"b\"" in SyncFileMerger.mergeFile(name, null, "null", conn, json))
            assertTrue(SyncFileMerger.sameContent(name, "null", " null\n", json))
            assertFalse(SyncFileMerger.sameContent(name, "null", conn, json))
        }
        val ws = "google_workspace_auth_u.json"
        assertTrue(SyncFileMerger.isValid(ws, "null", json))
        assertEquals("null", SyncFileMerger.mergeFile(ws, null, "null", "null", json).trim())
    }

    private fun user(login: String) = """{"login":"$login","id":1,"node_id":"n","avatar_url":"a","gravatar_id":null,"url":"u",
        "html_url":"h","name":null,"company":null,"blog":null,"location":null,"email":null,"bio":null,
        "public_repos":0,"public_gists":0,"followers":0,"following":0,"created_at":"c","updated_at":"u"}"""

    @Test
    fun `unparseable sides never throw`() {
        val good = enc(settings())
        // app_settings: remote's device-local keys (stripped remoteSync, other device's
        // current_user) are never adopted, so an unreadable local file is left as is
        assertEquals("{broken", SyncFileMerger.mergeFile("app_settings.json", null, "{broken", good, json))
        val keys = """[{"id":"k","provider":"openai","key":"x"}]"""
        assertEquals(keys, SyncFileMerger.mergeFile("api_keys_u.json", null, "{broken", keys, json))
        assertEquals(good, SyncFileMerger.mergeFile("app_settings.json", null, good, "garbage", json))
        val merged = SyncFileMerger.mergeFile("app_settings.json", "nope", good, good, json)
        assertEquals(dec(good), dec(merged))
        val history = json.encodeToString(UserChatHistory("u", emptyList()))
        assertEquals(history, SyncFileMerger.mergeFile("chat_history_u.json", null, "", history, json))
        assertEquals(history, SyncFileMerger.mergeFile("chat_history_u.json", null, history, "[1,2", json))
        assertEquals("""{"x":1}""", SyncFileMerger.mergeFile("other.json", null, """{"x":1}""", "{{", json))
    }

    @Test
    fun `chat history legacy messages without ids merge deterministically`() {
        // Same legacy chat stored without message ids on both sides, one side longer
        val base = """{"user_name":"u","chat_history":[{"chat_id":"c","preview_name":"c","messages":[
            {"role":"user","text":"q"},{"role":"assistant","text":"a"}]}]}"""
        val remote = """{"user_name":"u","chat_history":[{"chat_id":"c","preview_name":"c","messages":[
            {"role":"user","text":"q"},{"role":"assistant","text":"a"},{"role":"user","text":"q2"}]}]}"""
        val out = SyncFileMerger.mergeFile("chat_history_u.json", base, base, remote, json)
        assertEquals(out, SyncFileMerger.mergeFile("chat_history_u.json", base, base, remote, json))
        val chat = json.decodeFromString<UserChatHistory>(out).chat_history.single()
        assertEquals(listOf("q", "a", "q2"), chat.messages.map { it.text })
        // divergent legacy sides: common prefix shares node ids (one root, one fork node)
        val local = """{"user_name":"u","chat_history":[{"chat_id":"c","preview_name":"c","messages":[
            {"role":"user","text":"q"},{"role":"assistant","text":"a"},{"role":"user","text":"other"}]}]}"""
        val forked = json.decodeFromString<UserChatHistory>(
            SyncFileMerger.mergeFile("chat_history_u.json", base, local, remote, json)
        ).chat_history.single()
        assertEquals(2, forked.messageNodes.size)
        MergeAssert.assertValidChat(forked)
    }

    @Test
    fun `chat history output is the app storage encoding`() {
        val h = UserChatHistory("u", listOf(Chat("c", "c", listOf(Message(id = "1", role = "user", text = "x")))))
        val text = json.encodeToString(h)
        assertEquals(text, SyncFileMerger.mergeFile("chat_history_u.json", text, text, text, json))
    }
}
