package com.example.ApI.data.cloud

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.RemoteSyncSettings
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.util.JsonConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudRunWireTest {

    // ── Thinking budget ───────────────────────────────────────────────────────

    @Test
    fun `every thinking budget a device can have survives the wire`() {
        val values = listOf(
            ThinkingBudgetValue.None,
            ThinkingBudgetValue.Tokens(0),
            ThinkingBudgetValue.Tokens(1024),
            ThinkingBudgetValue.Tokens(128_000)
        ) + listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "HIGH", "").map { ThinkingBudgetValue.Effort(it) }
        for (value in values) {
            assertEquals(value, ThinkingBudgetWire.decode(ThinkingBudgetWire.encode(value)), "round trip of $value")
        }
    }

    @Test
    fun `the common values keep their legacy form`() {
        assertEquals("none", ThinkingBudgetWire.encode(ThinkingBudgetValue.None))
        assertEquals("low", ThinkingBudgetWire.encode(ThinkingBudgetValue.Effort("low")))
        assertEquals("medium", ThinkingBudgetWire.encode(ThinkingBudgetValue.Effort("medium")))
        assertEquals("high", ThinkingBudgetWire.encode(ThinkingBudgetValue.Effort("high")))
        assertEquals("2048", ThinkingBudgetWire.encode(ThinkingBudgetValue.Tokens(2048)))
        // Not expressible in a legacy form
        assertEquals("effort:none", ThinkingBudgetWire.encode(ThinkingBudgetValue.Effort("none")))
        assertEquals("effort:xhigh", ThinkingBudgetWire.encode(ThinkingBudgetValue.Effort("xhigh")))
        assertEquals("effort:minimal", ThinkingBudgetWire.encode(ThinkingBudgetValue.Effort("minimal")))
    }

    @Test
    fun `the legacy forms of the web client decode as before`() {
        assertEquals(ThinkingBudgetValue.None, ThinkingBudgetWire.decode("none"))
        assertEquals(ThinkingBudgetValue.Effort("low"), ThinkingBudgetWire.decode("low"))
        assertEquals(ThinkingBudgetValue.Effort("medium"), ThinkingBudgetWire.decode("MEDIUM"))
        assertEquals(ThinkingBudgetValue.Effort("high"), ThinkingBudgetWire.decode("high"))
        assertEquals(ThinkingBudgetValue.Effort("high"), ThinkingBudgetWire.decode("max"))
        assertEquals(ThinkingBudgetValue.Tokens(8000), ThinkingBudgetWire.decode("8000"))
        assertEquals(ThinkingBudgetValue.None, ThinkingBudgetWire.decode("whatever"))
        assertEquals(ThinkingBudgetValue.None, ThinkingBudgetWire.decode(""))
        assertEquals(ThinkingBudgetValue.Tokens(512), ThinkingBudgetWire.decode("tokens:512"))
        assertEquals(ThinkingBudgetValue.None, ThinkingBudgetWire.decode("tokens:many"))
    }

    // ── SSE framing ───────────────────────────────────────────────────────────

    private fun frames(body: String): List<Pair<String, String>> {
        val frames = ArrayList<Pair<String, String>>()
        val parser = SseParser { type, data -> frames += type to data }
        body.split("\n").forEach { parser.feedLine(it) }
        return frames
    }

    @Test
    fun `frames end at a blank line and comments are skipped`() {
        assertEquals(
            listOf("snapshot" to """{"runs":[]}""", "partial" to """{"text":"a"}"""),
            frames(
                "event: snapshot\ndata: {\"runs\":[]}\n\n: ping\n\n: ping\n\nevent: partial\ndata: {\"text\":\"a\"}\n\n"
            )
        )
    }

    @Test
    fun `an unfinished frame is not delivered and multi-line data is joined`() {
        assertEquals(emptyList(), frames("event: partial\ndata: {\"text\":\"a\"}"))
        assertEquals(listOf("x" to "one\ntwo"), frames("event: x\ndata: one\ndata: two\n\n"))
        // No event field: the default type; no space after the colon; unknown fields ignored
        assertEquals(listOf("message" to "d"), frames("id: 7\nretry: 5\ndata:d\n\n"))
        // A frame without data is nothing
        assertEquals(emptyList(), frames("event: lonely\n\n"))
    }

    // ── Event parsing ─────────────────────────────────────────────────────────

    @Test
    fun `unknown or unusable events are ignored`() {
        assertNull(CloudRunEvent.parse("something_new", """{"runId":"r","chatId":"c"}"""))
        assertNull(CloudRunEvent.parse("partial", "not json"))
        assertNull(CloudRunEvent.parse("partial", """{"text":"no ids"}"""))
        assertNull(CloudRunEvent.parse("run_started", """{"runId":"r","chatId":"c"}"""))
        assertNull(CloudRunEvent.parse("snapshot", """{"runs":"nope"}"""))
    }

    @Test
    fun `chat_updated names the chat whose title the server changed`() {
        assertEquals(
            CloudRunEvent.ChatUpdated("r", "c"),
            CloudRunEvent.parse("chat_updated", """{"runId":"r","chatId":"c"}""")
        )
        assertNull(CloudRunEvent.parse("chat_updated", """{"runId":"r"}"""))
    }

    @Test
    fun `the request carries the chat's group only when it has one`() {
        val base = CloudRunRequest(runId = "r", chatId = "c", provider = "openai", modelName = "m", messages = emptyList())
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        fun groupOf(request: CloudRunRequest) =
            json.parseToJsonElement(json.encodeToString(CloudRunRequest.serializer(), request))
                .let { it as kotlinx.serialization.json.JsonObject }["groupId"]
        assertEquals(kotlinx.serialization.json.JsonNull, groupOf(base))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("g1"), groupOf(base.copy(groupId = "g1")))
        // A request of a client that predates the field
        val old = """{"runId":"r","chatId":"c","provider":"openai","modelName":"m","messages":[]}"""
        assertNull(json.decodeFromString(CloudRunRequest.serializer(), old).groupId)
    }

    @Test
    fun `a snapshot tolerates fields this client does not know and missing optional ones`() {
        val event = CloudRunEvent.parse(
            "snapshot",
            """{"runs":[{"runId":"r","chatId":"c","status":"running","somethingNew":1}],"alsoNew":true}"""
        ) as CloudRunEvent.Snapshot
        val run = event.runs.single()
        assertEquals("r", run.runId)
        assertTrue(run.isRunning)
        assertEquals("", run.text)
        assertFalse(run.thinkingActive)
    }

    @Test
    fun `thinking_complete tolerates a null thoughts text and an unknown status`() {
        val event = CloudRunEvent.parse(
            "thinking_complete", """{"thoughts":null,"durationSeconds":3,"status":"SOMETHING","runId":"r","chatId":"c"}"""
        ) as CloudRunEvent.ThinkingComplete
        assertNull(event.thoughts)
        assertEquals(3f, event.durationSeconds)
        assertEquals(com.example.ApI.data.model.ThoughtsStatus.NONE, event.status)
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    @Test
    fun `settings saved before the feature existed load with it off`() {
        val old = """{"current_user":"u","selected_provider":"openai","selected_model":"m",
            "remoteSync":{"enabled":true,"serverBaseUrl":"https://s","authToken":"t","accountEmail":"a@b","syncApiKeys":true}}"""
        val settings = JsonConfig.standard.decodeFromString(AppSettings.serializer(), old)
        assertFalse(settings.remoteSync.cloudExecution)
        assertEquals("https://app.api-divonr.xyz", settings.remoteSync.cloudBaseUrl)
        assertFalse(settings.remoteSync.cloudExecutionEffective)

        val on = settings.remoteSync.copy(cloudExecution = true)
        assertTrue(on.cloudExecutionEffective)
        val reloaded = JsonConfig.standard.decodeFromString(
            AppSettings.serializer(),
            JsonConfig.prettyPrint.encodeToString(AppSettings.serializer(), settings.copy(remoteSync = on))
        )
        assertEquals(on, reloaded.remoteSync)
    }

    @Test
    fun `cloud execution is effective only with sync on, signed in and the keys synced`() {
        val on = RemoteSyncSettings(enabled = true, authToken = "t", accountEmail = "a@b", syncApiKeys = true, cloudExecution = true)
        assertTrue(on.cloudExecutionEffective)
        assertFalse(on.copy(cloudExecution = false).cloudExecutionEffective)
        assertFalse(on.copy(enabled = false).cloudExecutionEffective)
        assertFalse(on.copy(syncApiKeys = false).cloudExecutionEffective)
        assertFalse(on.copy(authToken = "").cloudExecutionEffective)
        assertFalse(on.copy(cloudBaseUrl = " ").cloudExecutionEffective)
    }
}
