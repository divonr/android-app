package com.example.ApI.data.network.providers

import com.example.ApI.data.PlatformStorage
import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.ApiRequest
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.PROJECT_ID_PLACEHOLDER
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.ResponseFields
import com.example.ApI.data.model.StreamingCallback
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.VERTEX_PROVIDER
import com.example.ApI.data.model.withProjectIdFrom
import com.example.ApI.tools.DateTimeTool
import com.example.ApI.tools.PythonInterpreterTool
import com.example.ApI.tools.PythonToolFiles
import com.example.ApI.tools.SandboxConfig
import com.example.ApI.tools.ToolCall
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a request says of an attachment it does not send to the model: where the code sandbox
 * has it when the request offers the sandbox, and the provider's own words otherwise. Agent
 * Platform is driven through a fake endpoint, Anthropic through its message builder.
 */
class SandboxFileNotesTest {

    @TempDir
    lateinit var dir: File

    private lateinit var server: HttpServer
    private val bodies = CopyOnWriteArrayList<JsonObject>()
    private val reply = "data: " + """{"candidates":[{"content":{"parts":[{"text":"done"}]},"finishReason":"STOP"}]}""" + "\n\n"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            bodies += Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val storage get() = object : PlatformStorage {
        override val filesDir: File = File(dir, "files").apply { mkdirs() }
        override val downloadsDir: File? = null
    }

    private val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** A file on this machine, each in a folder of its own so that two can share a name. */
    private fun attached(name: String, mimeType: String, content: String): Attachment {
        val file = File(dir, "${java.util.UUID.randomUUID()}/$name").apply { parentFile.mkdirs(); writeText(content) }
        return Attachment(local_file_path = file.absolutePath, file_name = name, mime_type = mimeType)
    }

    private fun userTurn(text: String, vararg attachments: Attachment) =
        Message(role = "user", text = text, attachments = attachments.toList())

    /** The sandbox's declaration for a chat of [messages], as the platform layers make it. */
    private fun sandbox(
        messages: List<Message>,
        provider: String,
        group: ChatGroup? = null,
        files: PythonToolFiles? = null
    ): ToolSpecification = PythonInterpreterTool(
        storage, Chat(chat_id = "chat-1", preview_name = "chat", messages = messages), group,
        SandboxConfig("http://127.0.0.1:1", "KEY"), files
    ).getSpecification(provider)

    private fun vertexUserParts(
        messages: List<Message>,
        tools: List<ToolSpecification>,
        webSearch: Boolean = false
    ): List<List<JsonObject>> = runBlocking {
        bodies.clear()
        val provider = Provider(
            provider = VERTEX_PROVIDER,
            models = emptyList(),
            request = ApiRequest(
                request_type = "POST",
                base_url = "http://127.0.0.1:${server.address.port}/v1/projects/$PROJECT_ID_PLACEHOLDER/models/{model_name}:generateContent",
                headers = emptyMap()
            ),
            response_important_fields = ResponseFields()
        ).withProjectIdFrom(listOf(ApiKey(provider = VERTEX_PROVIDER, key = "FAKE-KEY", projectId = "fake-project")))
        GoogleProvider(GooglePlatform.AGENT_PLATFORM).sendMessage(
            provider = provider, modelName = "gemini-fake", messages = messages, systemPrompt = "",
            apiKey = "FAKE-KEY", webSearchEnabled = webSearch, enabledTools = tools,
            thinkingBudget = ThinkingBudgetValue.None, temperature = null,
            callback = object : StreamingCallback {
                override fun onPartialResponse(text: String) {}
                override fun onComplete(fullText: String) {}
                override fun onError(error: String) = throw AssertionError(error)
                override suspend fun onToolCall(toolCall: ToolCall, precedingText: String) = ToolExecutionResult.Success("")
            }
        )
        bodies.single().getValue("contents").jsonArray.map { it.jsonObject }
            .filter { it.getValue("role").jsonPrimitive.content == "user" }
            .map { content -> content.getValue("parts").jsonArray.map { it.jsonObject } }
    }

    private fun anthropicUserContent(messages: List<Message>, tools: List<ToolSpecification>): List<List<JsonObject>> = runBlocking {
        AnthropicProvider().buildMessages(messages, tools)
            .filter { it.getValue("role").jsonPrimitive.content == "user" }
            .map { message -> message.getValue("content").jsonArray.map { it.jsonObject } }
    }

    private fun List<JsonObject>.texts() = mapNotNull { it["text"]?.jsonPrimitive?.content }

    private fun available(name: String, path: String = name) =
        """[User attached file "$name" — available inside workspace_sandbox at /app/input/$path]"""

    @Test
    fun `Agent Platform says where the sandbox has a file it is not sent, under the sandbox's own name for it`() {
        val first = attached("report.xlsx", xlsx, "one")
        val second = attached("report.xlsx", xlsx, "two")
        val odd = attached("a\"b.zip", "application/zip", "zip")
        val messages = listOf(
            userTurn("Compare these", first, second),
            Message(role = "assistant", text = "Done"),
            userTurn("And this", odd, first)
        )

        val turns = vertexUserParts(messages, listOf(sandbox(messages, "google")))

        assertEquals(
            listOf(available("report.xlsx"), available("report.xlsx", "report (2).xlsx"), "Compare these"),
            turns[0].texts()
        )
        // The name is the manifest's (no quote in it); a file attached again is the same file
        assertEquals(listOf(available("a\"b.zip", "a'b.zip"), available("report.xlsx"), "And this"), turns[1].texts())
    }

    @Test
    fun `without the sandbox among the request's tools the file is said not to be sent`() {
        val sheet = attached("report.xlsx", xlsx, "one")
        val messages = listOf(userTurn("Read it", sheet))
        val notSent = """[Attachment "report.xlsx" was not sent: $xlsx is not a supported file type]"""

        assertEquals(listOf(notSent, "Read it"), vertexUserParts(messages, emptyList()).single().texts())
        assertEquals(
            listOf(notSent, "Read it"),
            vertexUserParts(messages, listOf(DateTimeTool().getSpecification("google"))).single().texts()
        )
        // The search toggle keeps the function tools out of the request: the sandbox is not offered
        assertEquals(
            listOf(notSent, "Read it"),
            vertexUserParts(messages, listOf(sandbox(messages, "google")), webSearch = true).single().texts()
        )
        assertFalse("functionDeclarations" in bodies.single().toString())
    }

    @Test
    fun `a file the sandbox cannot have is not said to be there`() {
        val gone = Attachment(local_file_path = File(dir, "gone.xlsx").absolutePath, file_name = "gone.xlsx", mime_type = xlsx)
        val pathless = Attachment(file_name = "elsewhere.docx", mime_type = "application/msword")
        val refused = attached("refused.zip", "application/zip", "zip")
        val messages = listOf(userTurn("Read them", gone, pathless, refused))
        val files = object : PythonToolFiles {
            override fun isAvailable(attachment: Attachment) = attachment.file_name != "refused.zip"
            override suspend fun inputFile(attachment: Attachment) = attachment.local_file_path?.let { File(it) }
            override suspend fun saveOutput(fileName: String, mimeType: String, bytes: ByteArray): Attachment? = null
        }

        val texts = vertexUserParts(messages, listOf(sandbox(messages, "google", files = files))).single().texts()

        assertEquals(
            listOf(
                """[Attachment "gone.xlsx" was not sent: $xlsx is not a supported file type]""",
                """[Attachment "elsewhere.docx" was not sent: application/msword is not a supported file type]""",
                """[Attachment "refused.zip" was not sent: application/zip is not a supported file type]""",
                "Read them"
            ),
            texts
        )
        assertEquals(
            listOf("Read them"),
            anthropicUserContent(messages, listOf(sandbox(messages, "anthropic", files = files))).single().texts()
        )
    }

    @Test
    fun `a file of a supported type that is not on this machine is said to be in the sandbox only when it is`() {
        val sha = "ab".repeat(32)
        val stored = Attachment(file_name = "scan.pdf", mime_type = "application/pdf", sha256 = sha, size = 10)
        val unknown = Attachment(file_name = "lost.pdf", mime_type = "application/pdf")
        val messages = listOf(userTurn("Read them", stored, unknown))
        val files = object : PythonToolFiles {
            override fun isAvailable(attachment: Attachment) = attachment.sha256 != null
            override suspend fun inputFile(attachment: Attachment): File? = null
            override suspend fun saveOutput(fileName: String, mimeType: String, bytes: ByteArray): Attachment? = null
        }

        assertEquals(
            listOf(
                available("scan.pdf"),
                """[Attachment "lost.pdf" was not sent: the file is not available on this device]""",
                "Read them"
            ),
            vertexUserParts(messages, listOf(sandbox(messages, "google", files = files))).single().texts()
        )
    }

    @Test
    fun `files that are sent to the model are sent as before`() {
        val image = attached("chart.png", "image/png", "png")
        val notes = attached("notes.txt", "text/plain", "body")
        val messages = listOf(userTurn("Look", image, notes))

        val withSandbox = vertexUserParts(messages, listOf(sandbox(messages, "google"))).single()
        val without = vertexUserParts(messages, emptyList()).single()

        assertEquals(without, withSandbox)
        assertEquals(listOf("""[Attached file: "chart.png"]""", """[Attached file: "notes.txt"]""", "Look"), withSandbox.texts())
        assertEquals(2, withSandbox.count { "inlineData" in it })

        val anthropicWith = anthropicUserContent(messages.take(1).map { it.copy(attachments = listOf(image)) }, listOf(sandbox(messages, "anthropic")))
        val anthropicWithout = anthropicUserContent(messages.take(1).map { it.copy(attachments = listOf(image)) }, emptyList())
        assertEquals(anthropicWithout, anthropicWith)
        assertEquals(listOf("text", "image"), anthropicWith.single().map { it.getValue("type").jsonPrimitive.content })
    }

    @Test
    fun `Anthropic names the sandbox's file in place of a file it drops, and drops it silently without the sandbox`() {
        val first = attached("data.csv", "text/csv", "one")
        val second = attached("data.csv", "text/csv", "two")
        val image = attached("chart.png", "image/png", "png")
        val messages = listOf(userTurn("Compare", first, image, second))

        val content = anthropicUserContent(messages, listOf(sandbox(messages, "anthropic"))).single()

        assertEquals(listOf("text", "text", "image", "text"), content.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(listOf("Compare", available("data.csv"), available("data.csv", "data (2).csv")), content.texts())

        val plain = anthropicUserContent(messages, emptyList()).single()
        assertEquals(listOf("text", "image"), plain.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(plain, anthropicUserContent(messages, listOf(DateTimeTool().getSpecification("anthropic"))).single())
    }

    @Test
    fun `the request's copies of the chat's attachments are found in the manifest`() {
        val sha = "cd".repeat(32)
        val inChat = Attachment(file_name = "book.xlsx", mime_type = xlsx, sha256 = sha, size = 3)
        val files = object : PythonToolFiles {
            override fun isAvailable(attachment: Attachment) = true
            override suspend fun inputFile(attachment: Attachment) = attachment.local_file_path?.let { File(it) }
            override suspend fun saveOutput(fileName: String, mimeType: String, bytes: ByteArray): Attachment? = null
        }
        val projectFile = attached("rules.docx", "application/msword", "rules")
        val group = ChatGroup(group_id = "g", group_name = "g", group_attachments = listOf(projectFile))
        val tools = listOf(sandbox(listOf(userTurn("Read it", inChat)), "google", group, files))

        // As sent: the project's files in a turn of their own, the chat's file fetched to this machine
        val fetched = attached("book.xlsx", xlsx, "abc").copy(sha256 = sha, size = 3)
        val request = listOf(
            userTurn("Project files:", projectFile.copy(file_GOOGLE_uri = "https://files.example/1")),
            userTurn("Read it", fetched)
        )

        val turns = vertexUserParts(request, tools)
        assertEquals(listOf(available("rules.docx"), "Project files:"), turns[0].texts())
        assertEquals(listOf(available("book.xlsx"), "Read it"), turns[1].texts())
    }

    @Test
    fun `a turn's notes are the same once later turns and files are added`() {
        val first = attached("report.xlsx", xlsx, "one")
        val second = attached("report.xlsx", xlsx, "two")
        val early = listOf(userTurn("Compare these", first, second))
        val later = early + listOf(
            Message(role = "assistant", text = "Done"),
            userTurn("One more", attached("report.xlsx", xlsx, "three"), attached("other.zip", "application/zip", "zip")),
            Message(role = "assistant", text = "Done again"),
            userTurn("Thanks")
        )

        val before = vertexUserParts(early, listOf(sandbox(early, "google")))
        val after = vertexUserParts(later, listOf(sandbox(later, "google")))
        assertEquals(before[0], after[0])
        assertEquals(after, vertexUserParts(later, listOf(sandbox(later, "google"))))
        assertEquals(
            listOf(available("report.xlsx", "report (3).xlsx"), available("other.zip"), "One more"),
            after[1].texts()
        )

        val anthropicBefore = anthropicUserContent(early, listOf(sandbox(early, "anthropic")))
        val anthropicAfter = anthropicUserContent(later, listOf(sandbox(later, "anthropic")))
        assertEquals(anthropicBefore[0], anthropicAfter[0])
    }

    // ── The providers that otherwise drop such a file without a word ─────────

    private val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** A user turn with an image, two sheets of one name and a document; and the sandbox's declaration for it. */
    private fun mixedTurn(): List<Message> = listOf(
        userTurn(
            "Compare", attached("report.xlsx", xlsx, "one"), attached("chart.png", "image/png", "png"),
            attached("report.xlsx", xlsx, "two"), attached("brief.docx", docx, "doc")
        )
    )

    private val mixedNotes = listOf(
        available("report.xlsx"), available("report.xlsx", "report (2).xlsx"), available("brief.docx")
    )

    private fun JsonObject.role() = getValue("role").jsonPrimitive.content

    @Test
    fun `the OpenAI-compatible providers name the sandbox's files in the turn's text and keep a turn without images a string`() {
        val messages = mixedTurn()
        val filesOnly = listOf(userTurn("Read it", attached("brief.docx", docx, "doc")))
        for (provider in listOf(OpenRouterProvider(), LLMStatsProvider())) runBlocking<Unit> {
            val notes = provider.sandboxNotes(messages, listOf(sandbox(messages, "openai")))
            val content = provider.buildMessages(messages, "", notes).single().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
            assertEquals(listOf("text", "image_url"), content.map { it.getValue("type").jsonPrimitive.content })
            assertEquals((listOf("Compare") + mixedNotes).joinToString("\n\n"), content.first().getValue("text").jsonPrimitive.content)

            val plainNotes = provider.sandboxNotes(filesOnly, listOf(sandbox(filesOnly, "openai")))
            assertEquals(
                "Read it\n\n" + available("brief.docx"),
                provider.buildMessages(filesOnly, "", plainNotes).single().jsonObject.getValue("content").jsonPrimitive.content
            )

            // Without the sandbox among the tools: as before
            val other = listOf(DateTimeTool().getSpecification("openai"))
            assertEquals(emptyMap(), provider.sandboxNotes(messages, other))
            assertEquals(provider.buildMessages(messages, ""), provider.buildMessages(messages, "", provider.sandboxNotes(messages, other)))
            assertEquals("Read it", provider.buildMessages(filesOnly, "").single().jsonObject.getValue("content").jsonPrimitive.content)
        }
    }

    @Test
    fun `Cohere names the sandbox's files in place of the files it drops`() = runBlocking<Unit> {
        val messages = mixedTurn()
        val content = CohereProvider().buildMessages(messages, "", listOf(sandbox(messages, "cohere")))
            .single().getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "text", "image_url", "text", "text"), content.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(listOf("Compare") + mixedNotes, content.texts())

        val plain = CohereProvider().buildMessages(messages, "")
        assertEquals(plain, CohereProvider().buildMessages(messages, "", listOf(DateTimeTool().getSpecification("cohere"))))
        assertEquals(
            listOf("text", "image_url"),
            plain.single().getValue("content").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content }
        )
    }

    @Test
    fun `OpenAI names the sandbox's file for a binary or oversized file and still inlines a text file`() = runBlocking<Unit> {
        val binary = File(dir, "bin/book.xlsx").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(80, 75, 3, 4, 0, 0, -1, -2)) }
        val sheet = Attachment(local_file_path = binary.absolutePath, file_name = "book.xlsx", mime_type = xlsx)
        val latin = File(dir, "bin/legacy.dat").apply { writeBytes(byteArrayOf(72, 105, -23, -1)) }
        val notUtf8 = Attachment(local_file_path = latin.absolutePath, file_name = "legacy.dat", mime_type = "application/octet-stream")
        val csv = attached("data.csv", "text/csv", "a,b\n1,2\n")
        val json = attached("config.json", "application/json", "{\"a\": 1}")
        val big = attached("big.log", "text/plain", "x".repeat(100 * 1024 + 1))
        val image = attached("chart.png", "image/png", "png").copy(file_OPENAI_id = "file-1")
        val messages = listOf(userTurn("Look", sheet, csv, notUtf8, json, big, image))
        val openai = OpenAIProvider()

        val notes = openai.sandboxNotes(messages, listOf(sandbox(messages, "openai")))
        val content = openai.buildInput(messages, "", notes).single().getValue("content").jsonArray.map { it.jsonObject }
        val texts = content.texts()
        assertEquals("Look", texts[0])
        assertEquals(available("book.xlsx"), texts[1])
        assertEquals("[ATTACHED FILE: data.csv]\n[MIME TYPE: text/csv]\n[CONTENT START]\na,b\n1,2\n\n[CONTENT END]", texts[2])
        assertEquals(available("legacy.dat"), texts[3])
        assertTrue(texts[4].startsWith("[ATTACHED FILE: config.json]") && "{\"a\": 1}" in texts[4], texts[4])
        assertEquals(available("big.log"), texts[5])
        assertEquals("input_image", content.last().getValue("type").jsonPrimitive.content)
        assertEquals(setOf(sheet, notUtf8, big), notes.keys)

        // Without the sandbox: every such file is put in the prompt as it was
        val before = openai.buildInput(messages, "")
        assertEquals(before, openai.buildInput(messages, "", openai.sandboxNotes(messages, emptyList())))
        val beforeTexts = before.single().getValue("content").jsonArray.map { it.jsonObject }.texts()
        assertTrue(beforeTexts[1].startsWith("[ATTACHED FILE: book.xlsx]"), beforeTexts[1])
        assertTrue("[File too large to include inline." in beforeTexts[5], beforeTexts[5].take(200))
    }

    @Test
    fun `Poe names the sandbox's file only for a file it has no URL for`() = runBlocking<Unit> {
        val uploaded = attached("report.xlsx", xlsx, "one").copy(file_POE_url = "https://poe/f")
        val failed = attached("brief.docx", docx, "doc")
        val messages = listOf(userTurn("Read them", uploaded, failed))
        val poe = PoeProvider()

        val notes = poe.sandboxNotes(messages, listOf(sandbox(messages, "poe")))
        val turn = poe.buildMessages(messages, "", false, ThinkingBudgetValue.None, sandboxNotes = notes).single()
        assertEquals("Read them\n\n" + available("brief.docx"), turn.getValue("content").jsonPrimitive.content)
        assertEquals(
            listOf("https://poe/f"),
            turn.getValue("attachments").jsonArray.map { it.jsonObject.getValue("url").jsonPrimitive.content }
        )

        assertEquals(emptyMap(), poe.sandboxNotes(messages, emptyList()))
        val before = poe.buildMessages(messages, "", false, ThinkingBudgetValue.None).single()
        assertEquals("Read them", before.getValue("content").jsonPrimitive.content)
        assertEquals(2, before.getValue("attachments").jsonArray.size)
    }

    @Test
    fun `a template provider names the sandbox's files only when its template declares the tools`() = runBlocking<Unit> {
        val messages = mixedTurn() + Message(role = "assistant", text = "Done")
        val field = com.example.ApI.data.model.MessageFieldConfig("messages", """{"role":"user","content":"{prompt}"}""")
        fun provider(toolField: com.example.ApI.data.model.MessageFieldConfig?) = FullDynamicCustomProvider(
            com.example.ApI.data.model.FullCustomProviderConfig(
                name = "Mine", providerKey = "fullcustom_mine", baseUrl = "http://127.0.0.1:1", defaultModel = "m",
                bodyTemplate = "{}",
                messageFields = com.example.ApI.data.model.MessageFieldsConfig(userField = field, toolDefinitionField = toolField)
            )
        )
        val tools = listOf(sandbox(messages, "openai"))

        val noted = provider(field).withSandboxNotes(messages, tools)
        assertEquals(
            listOf("Compare", mixedNotes[0], available("chart.png"), mixedNotes[1], mixedNotes[2]),
            noted.first().text.split("\n\n")
        )
        assertEquals(messages.drop(1), noted.drop(1))

        assertEquals(messages, provider(field).withSandboxNotes(messages, emptyList()))
        assertEquals(messages, provider(null).withSandboxNotes(messages, tools))
    }

    @Test
    fun `AI Studio names the sandbox's file for a file without a Files API URI`() {
        val uploaded = attached("scan.pdf", "application/pdf", "pdf").copy(file_GOOGLE_uri = "https://files.example/1")
        val messages = listOf(userTurn("Read them", uploaded, attached("report.xlsx", xlsx, "one"), attached("report.xlsx", xlsx, "two")))

        fun userParts(tools: List<ToolSpecification>): List<JsonObject> = runBlocking {
            bodies.clear()
            val provider = Provider(
                provider = "google", models = emptyList(),
                request = ApiRequest(
                    request_type = "POST",
                    base_url = "http://127.0.0.1:${server.address.port}/v1/models/{model_name}:generateContent",
                    headers = emptyMap()
                ),
                response_important_fields = ResponseFields()
            )
            GoogleProvider(GooglePlatform.AI_STUDIO).sendMessage(
                provider = provider, modelName = "gemini-fake", messages = messages, systemPrompt = "",
                apiKey = "FAKE-KEY", webSearchEnabled = false, enabledTools = tools,
                thinkingBudget = ThinkingBudgetValue.None, temperature = null,
                callback = object : StreamingCallback {
                    override fun onPartialResponse(text: String) {}
                    override fun onComplete(fullText: String) {}
                    override fun onError(error: String) = throw AssertionError(error)
                }
            )
            bodies.single().getValue("contents").jsonArray.map { it.jsonObject }.single { it.role() == "user" }
                .getValue("parts").jsonArray.map { it.jsonObject }
        }

        val parts = userParts(listOf(sandbox(messages, "google")))
        assertEquals(listOf("Read them", available("report.xlsx"), available("report.xlsx", "report (2).xlsx")), parts.texts())
        assertEquals(1, parts.count { "file_data" in it })

        val before = userParts(emptyList())
        assertEquals(listOf("Read them"), before.texts())
        assertEquals(before, userParts(listOf(DateTimeTool().getSpecification("google"))))
    }
}
