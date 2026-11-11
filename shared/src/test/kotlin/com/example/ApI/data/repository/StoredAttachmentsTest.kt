package com.example.ApI.data.repository

import com.example.ApI.data.PlatformStorage
import com.example.ApI.data.attachments.FileDigest
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.tools.PythonInterpreterTool
import com.example.ApI.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Changes to attachments already stored in the chat history (CLOUD_RUNS_PLAN.md, "Attachment
 * store"): the backfilled identity and the provider ids, found by chat / group, message id and
 * attachment, written under the file lock — and the Python tool's output files carrying a hash.
 */
class StoredAttachmentsTest {

    @TempDir
    lateinit var dir: File

    private val user = "alice"
    private val repos = mutableListOf<DataRepository>()

    @AfterEach
    fun teardown() {
        runBlocking { repos.map { it.syncEngine }.distinct().forEach { it.closeAndJoin() } }
    }

    private fun repo(): DataRepository = DataRepository(object : PlatformStorage {
        override val filesDir: File = dir
        override val downloadsDir: File? = null
    }).also { repos += it }

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)
    private val photo = Attachment(local_file_path = "/device/photo.png", file_name = "photo.png", mime_type = "image/png")
    private val notes = Attachment(local_file_path = "/device/notes.txt", file_name = "notes.txt", mime_type = "text/plain")

    private fun DataRepository.chat(chatId: String): Chat = loadChatHistory(user).chat_history.first { it.chat_id == chatId }

    /** Every stored copy of message [messageId]: in the path and in the tree. */
    private fun DataRepository.copies(chatId: String, messageId: String): List<Message> = chat(chatId).let { chat ->
        chat.messages.filter { it.id == messageId } +
            chat.messageNodes.flatMap { n -> n.variants.flatMap { v -> listOf(v.userMessage) + v.responses } }.filter { it.id == messageId }
    }

    private fun seed(repo: DataRepository, vararg attachments: Attachment): Pair<String, String> {
        val chat = repo.createNewChat(user, "Chat")
        val message = Message(role = "user", text = "Q", attachments = attachments.toList())
        repo.addUserMessageAsNewNode(user, chat.chat_id, message)
        return chat.chat_id to message.id
    }

    @Test
    fun `a backfilled identity is recorded on the attachment of the message, in the path and in the tree`() {
        val repo = repo()
        val (chatId, messageId) = seed(repo, photo, notes)
        val other = Message(role = "user", text = "Q2", attachments = listOf(photo))
        repo.addUserMessageAsNewNode(user, chatId, other)
        val owner = AttachmentOwner.ChatMessage(chatId, messageId)

        assertTrue(repo.recordAttachmentIdentity(user, owner, photo, FileDigest(shaA, 123)))

        val copies = repo.copies(chatId, messageId)
        assertEquals(2, copies.size, "the message is in the path and in the tree")
        copies.forEach { assertEquals(listOf(photo.copy(sha256 = shaA, size = 123), notes), it.attachments) }
        // The same file on another message is another attachment: untouched
        repo.copies(chatId, other.id).forEach { assertEquals(listOf(photo), it.attachments) }

        // Already recorded / an attachment that has a hash is never changed / unknown targets
        assertFalse(repo.recordAttachmentIdentity(user, owner, photo, FileDigest(shaA, 123)))
        assertFalse(repo.recordAttachmentIdentity(user, owner, photo, FileDigest(shaB, 5)))
        assertFalse(repo.recordAttachmentIdentity(user, owner, photo.copy(file_name = "other.png"), FileDigest(shaB, 5)))
        assertFalse(repo.recordAttachmentIdentity(user, owner, notes, FileDigest("not-a-hash", 5)))
        assertFalse(repo.recordAttachmentIdentity(user, AttachmentOwner.ChatMessage(chatId, "no-such-message"), notes, FileDigest(shaB, 5)))
        assertFalse(repo.recordAttachmentIdentity(user, AttachmentOwner.ChatMessage("no-such-chat", messageId), notes, FileDigest(shaB, 5)))
        repo.copies(chatId, messageId).forEach { assertEquals(listOf(photo.copy(sha256 = shaA, size = 123), notes), it.attachments) }
    }

    @Test
    fun `a backfilled identity is recorded on a group attachment`() {
        val repo = repo()
        val group = repo.createNewGroup(user, "Project")
        val otherGroup = repo.createNewGroup(user, "Other")
        repo.addAttachmentToGroup(user, group.group_id, photo)
        repo.addAttachmentToGroup(user, group.group_id, notes)
        repo.addAttachmentToGroup(user, otherGroup.group_id, notes)

        assertTrue(repo.recordAttachmentIdentity(user, AttachmentOwner.Group(group.group_id), notes, FileDigest(shaB, 7)))
        assertFalse(repo.recordAttachmentIdentity(user, AttachmentOwner.Group("no-such-group"), notes, FileDigest(shaB, 7)))

        val groups = repo.loadChatHistory(user).groups.associateBy { it.group_id }
        assertEquals(listOf(photo, notes.copy(sha256 = shaB, size = 7)), groups.getValue(group.group_id).group_attachments)
        assertEquals(listOf(notes), groups.getValue(otherGroup.group_id).group_attachments)
    }

    @Test
    fun `provider ids are recorded by hash and never bring a path with them`() {
        val repo = repo()
        val stored = photo.copy(sha256 = shaA, size = 10)
        val (chatId, messageId) = seed(repo, stored, notes.copy(sha256 = shaB, size = 3))
        val owner = AttachmentOwner.ChatMessage(chatId, messageId)

        // What another machine saw: its own path for the same file, and the upload's answer
        val asRequested = stored.copy(local_file_path = "/srv/users/alice/files/attachments_cache/$shaA")
        val uploaded = Attachment(
            local_file_path = "/srv/users/alice/files/attachments_cache/$shaA", file_name = "renamed.png",
            mime_type = "application/octet-stream", file_OPENAI_id = "file-1"
        )
        assertTrue(repo.recordAttachmentProviderIds(user, owner, asRequested, uploaded))

        repo.copies(chatId, messageId).forEach {
            assertEquals(listOf(stored.copy(file_OPENAI_id = "file-1"), notes.copy(sha256 = shaB, size = 3)), it.attachments)
        }
        val json = File(dir, "llm_data/chat_history_$user.json").readText()
        assertFalse(json.contains("/srv/"), "the other machine's path must not be stored")

        // Other ids are added next to it; an id that is not set never clears one
        assertTrue(repo.recordAttachmentProviderIds(user, owner, stored, Attachment(file_name = "x", mime_type = "y", file_GOOGLE_uri = "https://g/1", file_POE_url = "")))
        assertFalse(repo.recordAttachmentProviderIds(user, owner, stored, uploaded), "nothing new")
        repo.copies(chatId, messageId).forEach {
            assertEquals(stored.copy(file_OPENAI_id = "file-1", file_GOOGLE_uri = "https://g/1"), it.attachments.first())
        }
        // No hash on the request's side: the same path, name and type identify the attachment
        assertFalse(repo.recordAttachmentProviderIds(user, owner, photo.copy(local_file_path = "/elsewhere"), uploaded.copy(file_OPENAI_id = "file-2")))
        assertTrue(repo.recordAttachmentProviderIds(user, owner, photo, uploaded.copy(file_OPENAI_id = "file-2")))
        assertEquals("file-2", repo.copies(chatId, messageId).first().attachments.first().file_OPENAI_id)

        // A group attachment
        val group = repo.createNewGroup(user, "Project")
        repo.addAttachmentToGroup(user, group.group_id, stored)
        assertTrue(repo.recordAttachmentProviderIds(user, AttachmentOwner.Group(group.group_id), asRequested, uploaded))
        assertEquals(
            listOf(stored.copy(file_OPENAI_id = "file-1")),
            repo.loadChatHistory(user).groups.single().group_attachments
        )
    }

    @Test
    fun `stored attachment updates do not lose concurrent changes`() {
        val repo = repo()
        val attachments = (0 until 8).map { Attachment(local_file_path = "/device/f$it", file_name = "f$it", mime_type = "text/plain") }
        val (chatId, messageId) = seed(repo, *attachments.toTypedArray())
        val owner = AttachmentOwner.ChatMessage(chatId, messageId)

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        attachments.forEachIndexed { i, attachment ->
            pool.execute {
                start.await()
                repo.recordAttachmentIdentity(user, owner, attachment, FileDigest("%064x".format(i + 1), i.toLong()))
                done.countDown()
            }
            pool.execute {
                start.await()
                repo.addUserMessageAsNewNode(user, chatId, Message(role = "user", text = "later $i"))
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdown()

        val stored = repo.copies(chatId, messageId).first().attachments
        assertEquals(attachments.indices.map { "%064x".format(it + 1) }, stored.map { it.sha256 })
        assertEquals(9, repo.chat(chatId).messages.size, "no message was lost either")
    }

    @Test
    fun `the python tool's output files carry the hash when there is one and the device path as before`() {
        fun result(vararg files: kotlinx.serialization.json.JsonObject) = ToolExecutionResult.Success(
            "ok", buildJsonObject { put("output_files", buildJsonArray { files.forEach { add(it) } }) }
        )
        // The device's own output (what Android produces): a local path, no hash
        val device = buildJsonObject { put("file_name", "chart.png"); put("mime_type", "image/png"); put("local_file_path", "/device/chart.png") }
        // A stored file: hash + size, with or without a path
        val stored = buildJsonObject { put("file_name", "out.csv"); put("mime_type", "text/csv"); put("sha256", shaA); put("size", 42) }
        val both = buildJsonObject { put("file_name", "b.txt"); put("local_file_path", "/device/b.txt"); put("sha256", shaB); put("size", 1) }
        val unusable = buildJsonObject { put("file_name", "nowhere.txt"); put("sha256", "not-a-hash") }
        val nameless = buildJsonObject { put("local_file_path", "/device/x") }

        assertEquals(
            listOf(
                Attachment(local_file_path = "/device/chart.png", file_name = "chart.png", mime_type = "image/png"),
                Attachment(file_name = "out.csv", mime_type = "text/csv", sha256 = shaA, size = 42),
                Attachment(local_file_path = "/device/b.txt", file_name = "b.txt", mime_type = "application/octet-stream", sha256 = shaB, size = 1)
            ),
            PythonInterpreterTool.outputAttachments(result(device, stored, both, unusable, nameless))
        )
        assertEquals(
            listOf(Attachment(local_file_path = "/device/chart.png", file_name = "chart.png", mime_type = "image/png")),
            PythonInterpreterTool.outputAttachments(ToolExecutionResult.Error("failed", buildJsonObject { put("output_files", buildJsonArray { add(device) }) }))
        )
        assertTrue(PythonInterpreterTool.outputAttachments(null).isEmpty())
        assertTrue(PythonInterpreterTool.outputAttachments(ToolExecutionResult.Success("no details")).isEmpty())
        assertTrue(PythonInterpreterTool.outputAttachments(ToolExecutionResult.Success("x", buildJsonObject { put("output_files", JsonPrimitive("?")) })).isEmpty())
        assertNull(PythonInterpreterTool.outputAttachments(result(stored)).single().local_file_path)
    }
}
