package com.example.ApI.tools

import com.example.ApI.data.PlatformStorage
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The two descriptions of [PythonInterpreterTool]: the per-request one and the stable one. */
class PythonInterpreterToolTest {

    @TempDir
    lateinit var dir: File

    private fun tool(vararg attachments: Attachment) = PythonInterpreterTool(
        platformStorage = object : PlatformStorage {
            override val filesDir: File = dir
            override val downloadsDir: File? = null
        },
        currentChat = Chat(
            chat_id = "chat", preview_name = "chat",
            messages = listOf(Message(role = "user", text = "hi", attachments = attachments.toList()))
        ),
        currentGroup = null,
        sandbox = SandboxConfig("http://127.0.0.1:9/execute", "test-key")
    )

    private val csv = Attachment(local_file_path = "/device/a.csv", file_name = "a.csv", mime_type = "text/csv")
    private val png = Attachment(local_file_path = "/device/b.png", file_name = "b.png", mime_type = "image/png")

    @Test
    fun `the stable description is the description with only the file list replaced`() {
        val empty = tool()
        val withFiles = tool(csv, png)

        // The description follows the chat's files, as every other provider still gets it
        assertTrue("No files currently available in conversation." in empty.description.lines())
        assertTrue("Available files: a.csv, b.png" in withFiles.description.lines())

        // The stable one does not
        assertEquals(empty.stableDescription, withFiles.stableDescription)
        assertFalse("a.csv" in withFiles.stableDescription)

        val descriptionLines = withFiles.description.lines()
        val stableLines = withFiles.stableDescription.lines()
        assertEquals(descriptionLines.size, stableLines.size)
        assertEquals(
            listOf("Available files: a.csv, b.png" to PythonInterpreterTool.STABLE_FILES_LINE),
            descriptionLines.zip(stableLines).filter { (described, stable) -> described != stable }
        )
    }

    @Test
    fun `the specification carries both descriptions for every provider`() {
        val withFiles = tool(csv)
        for (provider in listOf("google", "openai", "anthropic")) {
            val specification = withFiles.getSpecification(provider)
            assertEquals(withFiles.description, specification.description)
            assertEquals(withFiles.stableDescription, specification.stableDescription)
        }
    }
}
