package com.example.ApI.tools

import com.example.ApI.data.PlatformStorage
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.network.providers.AnthropicProvider
import com.example.ApI.data.network.providers.CohereProvider
import com.example.ApI.data.network.providers.OpenAIProvider
import com.example.ApI.data.network.providers.OpenRouterProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The code sandbox is `workspace_sandbox` to the models while its tool ID — in the enabled-tools
 * settings and in every stored tool call — stays `python_interpreter`.
 */
class WorkspaceSandboxNameTest {

    @TempDir
    lateinit var dir: File

    private fun sandbox() = PythonInterpreterTool(
        platformStorage = object : PlatformStorage {
            override val filesDir: File = dir
            override val downloadsDir: File? = null
        },
        currentChat = Chat(chat_id = "chat", preview_name = "chat", messages = emptyList()),
        currentGroup = null,
        sandbox = SandboxConfig("http://127.0.0.1:9/v2/execute", "")
    )

    @Test
    fun `the tool keeps its id and is declared as workspace_sandbox with the stable description`() {
        val tool = sandbox()
        assertEquals("python_interpreter", tool.id)
        assertEquals(ToolRegistry.PYTHON_INTERPRETER, tool.id)
        for (provider in listOf("google", "openai", "anthropic", "poe")) {
            val specification = tool.getSpecification(provider)
            assertEquals("workspace_sandbox", specification.name)
            assertEquals(PythonInterpreterTool.DESCRIPTION, specification.description)
        }
        val description = PythonInterpreterTool.DESCRIPTION
        assertTrue(description.startsWith("Execute Python 3.11 code inside a persistent, full-featured Debian Linux container (root access)"))
        assertTrue(description.endsWith("Use `print()` to return text output to yourself."))
        // The indentation of the text is part of it
        assertTrue("\n1. Container vs. Process Lifecycle:\n   - FILESYSTEM IS PERSISTENT" in description)
        assertTrue("ONLY files saved directly inside `/app/output/`" in description)
        assertTrue("`/app/input/{file_name}` (also symlinked at `./data/{file_name}`)" in description)
        // Checked against the image: the package and the files that are really there
        assertTrue("(`culmus`, `fonts-noto-core`, `fonts-dejavu`)" in description)
        assertTrue("`/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf`" in description)
        assertTrue("`/usr/share/fonts/truetype/culmus/FrankRuehlCLM-Medium.ttf`" in description)
        assertTrue("pandoc, wkhtmltopdf, chromium (headless; Plotly static image export" in description)
        for (gone in listOf("python_interpreter", "files_to_include", "working directory (e.g.", "Available files", "fonts-culmus")) {
            assertFalse(gone in description, gone)
        }
    }

    @Test
    fun `names and ids map onto each other and leave every other tool alone`() {
        assertEquals("python_interpreter", ToolRegistry.toolIdForModelName("workspace_sandbox"))
        assertEquals("python_interpreter", ToolRegistry.toolIdForModelName("python_interpreter"))
        assertEquals("workspace_sandbox", ToolRegistry.modelNameForToolId("python_interpreter"))
        assertEquals("workspace_sandbox", ToolRegistry.modelNameForToolId("workspace_sandbox"))
        for (other in listOf("get_date_time", "github_read_file", "unknown")) {
            assertEquals(other, ToolRegistry.toolIdForModelName(other))
            assertEquals(other, ToolRegistry.modelNameForToolId(other))
        }
    }

    @Test
    fun `a setting that enables python_interpreter enables the renamed tool, called by either name`() = runBlocking {
        val registry = ToolRegistry()
        registry.registerTool(sandbox())
        // What every device has stored: the old id
        val enabled = listOf("get_date_time", "python_interpreter")

        assertEquals(listOf("get_date_time", "workspace_sandbox"), registry.getEnabledToolsSpecifications(enabled, "openai").map { it.name })
        assertEquals("Workspace Sandbox", registry.getToolDisplayName("python_interpreter"))
        assertEquals("Workspace Sandbox", registry.getToolDisplayName("workspace_sandbox"))

        // The tool is reached (it answers that this build has no key) by the new name, by the
        // old one, and when the enabled list holds the declared names
        val code = buildJsonObject { put("code", "print(1)") }
        for ((called, enabledAs) in listOf(
            "workspace_sandbox" to enabled, "python_interpreter" to enabled, "workspace_sandbox" to listOf("workspace_sandbox")
        )) {
            val result = registry.executeTool(ToolCall(id = "c", toolId = called, parameters = code, provider = "openai"), enabledAs)
            assertIs<ToolExecutionResult.Error>(result)
            assertTrue("not configured" in result.error, result.error)
        }
        // Not enabled stays not enabled
        val refused = registry.executeTool(ToolCall(id = "c", toolId = "workspace_sandbox", parameters = code, provider = "openai"), listOf("get_date_time"))
        assertTrue("not enabled" in (refused as ToolExecutionResult.Error).error)
    }

    // ── Replay of stored calls ───────────────────────────────────────────────

    /** A stored tool step under [toolId], as the chats hold it. */
    private fun history(toolId: String): List<Message> = listOf(
        Message(role = "user", text = "Count the lines"),
        Message(role = "assistant", text = "Let me run it."),
        Message(
            role = "tool_call", text = "", toolCallId = "call-1",
            toolCall = ToolCallInfo(
                toolId = toolId, toolName = toolId, parameters = buildJsonObject { put("code", "print(1)") },
                result = ToolExecutionResult.Success("1"), timestamp = "2026-11-11T10:00:00Z"
            )
        ),
        Message(role = "tool_response", text = "1", toolResponseCallId = "call-1", toolResponseOutput = "1"),
        Message(role = "assistant", text = "One line."),
        Message(role = "user", text = "And now?")
    )

    private fun assertReplayedAsSandbox(provider: String, build: (List<Message>) -> Any) {
        val old = build(history("python_interpreter")).toString()
        assertTrue("\"name\":\"workspace_sandbox\"" in old, "$provider: $old")
        assertFalse("python_interpreter" in old, "$provider: $old")
        // A call saved before the rename and one saved after it replay as the same bytes
        assertEquals(old, build(history("workspace_sandbox")).toString(), provider)
        // Other tools keep their names
        assertTrue("\"name\":\"get_date_time\"" in build(history("get_date_time")).toString(), provider)
    }

    @Test
    fun `stored python_interpreter calls are replayed under the declared name by every serialiser`() {
        assertReplayedAsSandbox("openai") { OpenAIProvider().buildInput(it, "") }
        assertReplayedAsSandbox("anthropic") { runBlocking { AnthropicProvider().buildMessages(it) } }
        assertReplayedAsSandbox("cohere") { runBlocking { CohereProvider().buildMessages(it, "") } }
        assertReplayedAsSandbox("openai-compatible") { OpenRouterProvider().buildMessages(it, "") }
    }
}
