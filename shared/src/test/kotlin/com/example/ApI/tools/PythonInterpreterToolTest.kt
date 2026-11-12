package com.example.ApI.tools

import com.example.ApI.data.PlatformStorage
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.Message
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [PythonInterpreterTool] against a fake of the executor's v2 endpoint. */
class PythonInterpreterToolTest {

    @TempDir
    lateinit var dir: File

    private val executor = FakeSandboxExecutor().start()

    @AfterEach
    fun stop() = executor.stop()

    private val storage get() = object : PlatformStorage {
        override val filesDir: File = File(dir, "files").apply { mkdirs() }
        override val downloadsDir: File? = null
    }

    /** A file on "this device" and the attachment that points at it. */
    private fun attached(name: String, content: String, path: String = "${java.util.UUID.randomUUID()}/$name"): Attachment {
        val file = File(dir, path).apply { parentFile.mkdirs(); writeText(content) }
        return Attachment(local_file_path = file.absolutePath, file_name = name, mime_type = "text/plain")
    }

    private fun chat(vararg attachments: Attachment, id: String = "chat-1") = Chat(
        chat_id = id, preview_name = "chat",
        messages = attachments.map { Message(role = "user", text = "hi", attachments = listOf(it)) }
    )

    private fun tool(
        chat: Chat? = chat(),
        group: ChatGroup? = null,
        sandbox: SandboxConfig = executor.config,
        files: PythonToolFiles? = null
    ) = PythonInterpreterTool(storage, chat, group, sandbox, files)

    private fun run(tool: PythonInterpreterTool, code: String = "print(1)") =
        runBlocking { tool.execute(buildJsonObject { put("code", code) }) }

    private fun ToolExecutionResult.text() = when (this) {
        is ToolExecutionResult.Success -> result
        is ToolExecutionResult.Error -> error
    }

    private fun sha(text: String) = FakeSandboxExecutor.sha256(text.toByteArray())

    // ── The declaration ──────────────────────────────────────────────────────

    @Test
    fun `the declaration takes only the code and is the same whatever the chat's files`() {
        val empty = tool()
        val withFiles = tool(chat(attached("a.csv", "1"), attached("b.png", "2")))
        for (provider in listOf("google", "openai", "anthropic")) {
            val specification = withFiles.getSpecification(provider)
            assertEquals(empty.getSpecification(provider), specification)
            assertFalse("a.csv" in specification.description)
            assertNull(specification.stableDescription, "the description itself is stable")
            val parameters = specification.parameters!!
            assertEquals(setOf("code"), parameters["properties"]!!.jsonObject.keys)
            assertEquals(listOf("code"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertFalse("files_to_include" in specification.toString())
        }
    }

    // ── The manifest ─────────────────────────────────────────────────────────

    @Test
    fun `the manifest names every file of the chat and its group with size and hash`() = runBlocking {
        val group = ChatGroup(group_id = "g", group_name = "G", group_attachments = listOf(attached("brief.txt", "the brief")))
        val manifest = tool(chat(attached("a.csv", "x,y"), attached("דוח.pdf", "pdf")), group).inputManifest()

        assertEquals(listOf("a.csv", "דוח.pdf", "brief.txt"), manifest.files.map { it.name })
        assertEquals(listOf(3L, 3L, 9L), manifest.files.map { it.size })
        assertEquals(listOf(sha("x,y"), sha("pdf"), sha("the brief")), manifest.files.map { it.sha256 })
        assertTrue(manifest.unavailable.isEmpty())
        assertEquals(
            """[{"name":"a.csv","size":3,"sha256":"${sha("x,y")}"},{"name":"דוח.pdf","size":3,"sha256":"${sha("pdf")}"},""" +
                """{"name":"brief.txt","size":9,"sha256":"${sha("the brief")}"}]""",
            manifest.toJson().toString()
        )
    }

    @Test
    fun `equal names get numbered, equal files are one, and names stay as the chat grows`() = runBlocking {
        val first = attached("data.csv", "one")
        val other = attached("data.csv", "two")
        val again = attached("data.csv", "one")
        val third = attached("data.csv", "three")
        val noExtension = listOf(attached("README", "a"), attached("README", "b"))
        val group = ChatGroup(group_id = "g", group_name = "G", group_attachments = listOf(attached("data.csv", "two"), attached("data.csv", "group's own")))

        val early = tool(chat(first, other)).inputManifest()
        assertEquals(listOf("data.csv" to sha("one"), "data (2).csv" to sha("two")), early.files.map { it.name to it.sha256 })

        val later = tool(chat(first, other, again, third, *noExtension.toTypedArray()), group).inputManifest()
        assertEquals(
            listOf(
                "data.csv" to sha("one"), "data (2).csv" to sha("two"), "data (3).csv" to sha("three"),
                "README" to sha("a"), "README (2)" to sha("b"), "data (4).csv" to sha("group's own")
            ),
            later.files.map { it.name to it.sha256 }
        )
        assertEquals(later.files.map { it.name }.toSet().size, later.files.size, "names are unique")
    }

    @Test
    fun `a name is made a plain file name`() {
        assertEquals("a_b_c.txt", PythonInterpreterTool.inputFileName("a/b\\c.txt"))
        assertEquals("say 'hi'.txt", PythonInterpreterTool.inputFileName("say \"hi\".txt"))
        assertEquals("two_lines", PythonInterpreterTool.inputFileName("two\nlines"))
        assertEquals("file", PythonInterpreterTool.inputFileName("  "))
        assertEquals("file", PythonInterpreterTool.inputFileName(".."))
    }

    @Test
    fun `a recorded hash is used as it is and a file is hashed once`() = runBlocking {
        // Recorded identity: the file is not even looked for
        val recorded = Attachment(local_file_path = "/not/on/this/device.bin", file_name = "big.bin", mime_type = "application/octet-stream", sha256 = "ab".repeat(32), size = 5_000_000_000)
        val plain = attached("plain.txt", "to be hashed")
        val tool = tool(chat(recorded, plain))

        val before = PythonInterpreterTool.hashedFiles.get()
        val manifest = tool.inputManifest()
        assertEquals(listOf("ab".repeat(32) to 5_000_000_000, sha("to be hashed") to 12L), manifest.files.map { it.sha256 to it.size })
        assertEquals(before + 1, PythonInterpreterTool.hashedFiles.get())

        // Again, also from another instance of the tool: nothing is hashed
        tool.inputManifest()
        tool(chat(recorded, plain)).inputManifest()
        assertEquals(before + 1, PythonInterpreterTool.hashedFiles.get())

        // A changed file is hashed again
        File(plain.local_file_path!!).writeText("changed, and longer")
        assertEquals(sha("changed, and longer"), tool.inputManifest().files.last().sha256)
        assertEquals(before + 2, PythonInterpreterTool.hashedFiles.get())
    }

    // ── The handshake ────────────────────────────────────────────────────────

    @Test
    fun `bodies are sent only when the executor asks, once per file`() {
        val a = attached("a.csv", "aaa")
        val b = attached("קובץ.txt", "bbb")
        executor.stdout = "done\n"

        val first = run(tool(chat(a, b)), "print('done')")
        assertIs<ToolExecutionResult.Success>(first)
        // Round 1 without bodies, round 2 with exactly the missing ones
        assertEquals(2, executor.calls.size)
        assertTrue(executor.calls[0].files.isEmpty())
        assertEquals(mapOf("a.csv" to "aaa", "קובץ.txt" to "bbb"), executor.calls[1].files.mapValues { String(it.value) })
        for (call in executor.calls) {
            assertEquals(setOf("code", "chat_id", "input_manifest"), call.fields.keys, "no session id, no kill_session")
            assertEquals("print('done')", call.code)
            assertEquals("chat-1", call.chatId)
            assertEquals(FakeSandboxExecutor.KEY, call.key)
            assertEquals(listOf("a.csv", "קובץ.txt"), call.manifest.map { it.name })
        }
        assertEquals(1, executor.runs.size)

        // The next call of the chat: the executor holds the files, nothing is uploaded
        run(tool(chat(a, b)))
        assertEquals(3, executor.calls.size)
        assertTrue(executor.calls[2].files.isEmpty())
        assertEquals(2, executor.calls[2].manifest.size)

        // A new file: only it is uploaded
        val c = attached("c.txt", "ccc")
        run(tool(chat(a, b, c)))
        assertEquals(5, executor.calls.size)
        assertEquals(setOf("c.txt"), executor.calls[4].files.keys)
        assertEquals(3, executor.runs.size)

        // Another chat is another session: its files are asked for again
        run(tool(chat(a, id = "chat-2")))
        assertEquals("chat-2", executor.calls.last().chatId)
        assertEquals(setOf("a.csv"), executor.calls.last().files.keys)
    }

    @Test
    fun `files still missing after the upload are an error, with one retry only`() {
        executor.alwaysMissing = listOf("a.csv")
        val result = run(tool(chat(attached("a.csv", "aaa"), attached("b.csv", "bbb"))))
        assertIs<ToolExecutionResult.Error>(result)
        assertTrue("a.csv" in result.error && "did not accept" in result.error, result.error)
        assertFalse("b.csv" in result.error)
        assertEquals(2, executor.calls.size)
        assertTrue(executor.runs.isEmpty())
    }

    @Test
    fun `an executor asking for a file the chat does not have is an error`() {
        executor.alwaysMissing = listOf("a.csv")
        executor.rawAnswer = 200 to """{"status":"missing_files","missing":["other.bin"]}"""
        val result = run(tool(chat(attached("a.csv", "aaa"))))
        assertIs<ToolExecutionResult.Error>(result)
        assertTrue("other.bin" in result.error, result.error)
        assertEquals(1, executor.calls.size)
    }

    @Test
    fun `a file that cannot be fetched fails the call by name and one that is nowhere is reported`() {
        val gone = Attachment(file_name = "lost.xlsx", mime_type = "application/octet-stream", sha256 = "cd".repeat(32), size = 10)
        val never = Attachment(file_name = "never-uploaded.pdf", mime_type = "application/pdf")
        val here = attached("here.txt", "here")
        val files = object : PythonToolFiles {
            override fun isAvailable(attachment: Attachment) = attachment.sha256 != null || attachment.local_file_path != null
            override suspend fun inputFile(attachment: Attachment): File? = attachment.local_file_path?.let { File(it) }
            override suspend fun saveOutput(fileName: String, mimeType: String, bytes: ByteArray): Attachment? = null
        }

        // The store should have it (it has a hash) but it cannot be had: nothing runs
        val failed = run(tool(chat(here, gone), files = files))
        assertIs<ToolExecutionResult.Error>(failed)
        assertTrue("\"lost.xlsx\"" in failed.error && "Nothing was run" in failed.error, failed.error)
        assertTrue(executor.runs.isEmpty())

        // No hash and no file on this machine: left out, and the model is told
        executor.stdout = "ok"
        val told = run(tool(chat(here, never), files = files))
        assertIs<ToolExecutionResult.Success>(told)
        assertEquals(listOf("here.txt"), executor.runs.single().manifest.map { it.name })
        assertTrue("never-uploaded.pdf" in told.result && "NOT in /app/input" in told.result, told.result)

        // The same for a device path whose file is gone
        val deleted = attached("deleted.txt", "x").also { File(it.local_file_path!!).delete() }
        val alsoTold = run(tool(chat(here, deleted)))
        assertTrue("deleted.txt" in alsoTold.text(), alsoTold.text())
    }

    @Test
    fun `without a chat the session is this install's`() {
        run(tool(chat = null))
        run(tool(chat = null))
        val ids = executor.calls.map { it.chatId }
        assertEquals(1, ids.toSet().size)
        assertTrue(ids.first().startsWith("nochat-") && ids.first().length > 20)
        assertEquals("[]", executor.calls.first().fields["input_manifest"])
    }

    // ── The result ───────────────────────────────────────────────────────────

    @Test
    fun `stdout and stderr both reach the model, labelled, and empty sections are left out`() {
        executor.stdout = "value: 42\n"
        executor.stderr = "DeprecationWarning: old\n"
        val both = run(tool())
        assertIs<ToolExecutionResult.Success>(both)
        assertEquals("**stdout:**\n```\nvalue: 42\n```\n\n**stderr:**\n```\nDeprecationWarning: old\n```", both.result)

        executor.stderr = ""
        assertEquals("**stdout:**\n```\nvalue: 42\n```", run(tool()).text())

        executor.stdout = ""
        assertEquals("Code executed successfully (no output)", run(tool()).text())
    }

    @Test
    fun `a failing script is an error with its traceback and exit code`() {
        executor.stdout = "started\n"
        executor.stderr = "Traceback (most recent call last):\nZeroDivisionError: division by zero\n"
        executor.exitCode = 1
        val result = run(tool())
        assertIs<ToolExecutionResult.Error>(result)
        assertEquals(
            "**stdout:**\n```\nstarted\n```\n\n**stderr:**\n```\nTraceback (most recent call last):\nZeroDivisionError: division by zero\n```\n\n**Exit code:** 1",
            result.error
        )
        assertEquals(1, result.details!!["exit_code"]!!.jsonPrimitive.intOrNull)

        // Silent failure: still said
        executor.stdout = ""; executor.stderr = ""; executor.exitCode = 3
        assertEquals("**Exit code:** 3", run(tool()).text())
    }

    @Test
    fun `a timeout returns what was printed until then with the executor's message`() {
        executor.status = "timeout"
        executor.exitCode = null
        executor.stdout = "step 1\nstep 2\n"
        executor.stderr = "warming up\n"
        executor.message = "The process was killed after 300 seconds."
        executor.artifacts = mapOf("partial.csv" to "p".toByteArray())

        val result = run(tool())
        assertIs<ToolExecutionResult.Error>(result)
        assertTrue(result.error.startsWith("**stdout:**\n```\nstep 1\nstep 2\n```\n\n**stderr:**\n```\nwarming up\n```\n\n**Timed out:** The process was killed after 300 seconds."), result.error)
        assertFalse("Exit code" in result.error)
        assertEquals(true, result.details!!["timed_out"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(JsonNull, result.details!!["exit_code"])
        // Its files are still attached
        assertEquals(listOf("partial.csv"), PythonInterpreterTool.outputAttachments(result).map { it.file_name })
        assertTrue("- partial.csv" in result.error)
    }

    @Test
    fun `long output arrives whole and a truncation by the executor is said`() {
        val long = (1..200_000).joinToString("\n") { "line $it" }
        executor.stdout = long
        val whole = run(tool())
        assertEquals("**stdout:**\n```\n$long\n```", whole.text())
        assertFalse("truncated" in whole.text())

        executor.truncated = true
        val cut = run(tool()).text()
        assertTrue(cut.startsWith("**stdout:**\n```\n$long\n```\n\n**Note:** the sandbox truncated this output"), cut.takeLast(300))
    }

    @Test
    fun `a container that replaced an expired or lost one is announced first, a new one is not`() {
        executor.stdout = "hello"
        val new = run(tool()).text()
        assertEquals("**stdout:**\n```\nhello\n```", new, "fresh_reason new: no note")

        for (reason in listOf("expired", "lost")) {
            executor.freshReason = reason
            val text = run(tool()).text()
            assertTrue(text.startsWith("[Sandbox notice: this call ran in a new container"), text)
            assertTrue("packages installed" in text && "are gone" in text, text)
            assertTrue(text.endsWith("**stdout:**\n```\nhello\n```"), text)
        }

        // Also ahead of an error
        executor.exitCode = 2
        assertTrue(run(tool()).text().startsWith("[Sandbox notice:"))
    }

    @Test
    fun `artifacts are kept as files of the device and listed in the result`() {
        val png = byteArrayOf(1, 2, 3, 0, -1)
        executor.stdout = "plotted"
        executor.artifacts = linkedMapOf("chart.png" to png, "דוח.docx" to "doc".toByteArray())

        val result = run(tool())
        assertIs<ToolExecutionResult.Success>(result)
        assertTrue(result.result.endsWith("**Generated Files:**\n- chart.png\n- דוח.docx"), result.result)

        val attachments = PythonInterpreterTool.outputAttachments(result)
        assertEquals(listOf("chart.png" to "image/png", "דוח.docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), attachments.map { it.file_name to it.mime_type })
        val saved = File(attachments.first().local_file_path!!)
        assertEquals(File(storage.filesDir, "attachments"), saved.parentFile)
        assertTrue(png.contentEquals(saved.readBytes()))
        assertEquals("doc", File(attachments.last().local_file_path!!).readText())
        // The output is the result's text; the details do not hold it a second time
        assertEquals(setOf("exit_code", "timed_out", "output_files"), result.details!!.keys)
    }

    @Test
    fun `artifacts go through the platform's files when it has them, and one that cannot be kept is said`() {
        val kept = mutableListOf<String>()
        val files = object : PythonToolFiles {
            override fun isAvailable(attachment: Attachment) = true
            override suspend fun inputFile(attachment: Attachment): File? = null
            override suspend fun saveOutput(fileName: String, mimeType: String, bytes: ByteArray): Attachment? {
                if (fileName == "broken.bin") return null
                kept += "$fileName:$mimeType:${String(bytes)}"
                return Attachment(file_name = fileName, mime_type = mimeType, sha256 = FakeSandboxExecutor.sha256(bytes), size = bytes.size.toLong())
            }
        }
        executor.artifacts = linkedMapOf("out.csv" to "a,b".toByteArray(), "broken.bin" to "?".toByteArray())

        val result = run(tool(files = files))
        assertEquals(listOf("out.csv:text/csv:a,b"), kept)
        val attachment = PythonInterpreterTool.outputAttachments(result).single()
        assertEquals(Attachment(file_name = "out.csv", mime_type = "text/csv", sha256 = sha("a,b"), size = 3), attachment)
        assertTrue("could not be attached to the chat: broken.bin" in result.text(), result.text())
    }

    // ── Configuration and failures ───────────────────────────────────────────

    @Test
    fun `a build without the key says so and calls nothing`() {
        val result = run(tool(sandbox = SandboxConfig(executor.url, "")))
        assertIs<ToolExecutionResult.Error>(result)
        assertTrue("not configured" in result.error && "API key" in result.error, result.error)
        assertTrue(executor.calls.isEmpty())

        assertTrue("not configured" in run(tool(sandbox = SandboxConfig(" ", "key"))).text())
        assertNotNull(SandboxConfig("", "").configurationError)
        assertNull(SandboxConfig("http://x", "k").configurationError)
        assertFalse("secret-value" in SandboxConfig("http://x", "secret-value").toString())
    }

    @Test
    fun `a refused key, an executor error and an unreadable answer are clear errors`() {
        val refused = run(tool(sandbox = SandboxConfig(executor.url, "wrong")))
        assertIs<ToolExecutionResult.Error>(refused)
        assertTrue("refused this app's API key" in refused.error, refused.error)
        assertFalse("wrong" in refused.error)

        executor.rawAnswer = 200 to """{"status":"error","message":"docker is down"}"""
        assertEquals("Sandbox error: docker is down", run(tool()).text())

        executor.rawAnswer = 422 to """{"detail":"chat_id missing"}"""
        assertEquals("""Sandbox error: HTTP 422: {"detail":"chat_id missing"}""", run(tool()).text())

        executor.rawAnswer = 200 to "<html>gateway</html>"
        assertTrue("not JSON" in run(tool()).text())

        val missingCode = runBlocking { tool().execute(buildJsonObject { }) }
        assertEquals("Parameter 'code' is required", missingCode.text())
    }
}
