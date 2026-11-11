package com.example.ApI.data.sync.merge

import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.MessageNode
import com.example.ApI.data.model.MessageVariant
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.util.JsonConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Attachment.sha256` / `size` (the attachment store identity) through serialization and the
 * sync merge — in particular against a client that predates the fields and rewrites the history
 * without them.
 */
class AttachmentIdentityMergeTest {
    private val json = JsonConfig.prettyPrint
    private val file = "chat_history_u.json"

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

    private fun attachment(name: String = "photo.jpg", sha: String? = null, size: Long? = null, openAi: String? = null) = Attachment(
        local_file_path = "/data/attachments/uuid_$name", file_name = name, mime_type = "image/jpeg",
        file_OPENAI_id = openAi, sha256 = sha, size = size
    )

    private fun user(id: String, text: String, vararg attachments: Attachment) =
        Message(id = id, role = "user", text = text, attachments = attachments.toList(), datetime = "2026-01-01T00:00:00Z")

    private fun reply(id: String, text: String, vararg attachments: Attachment) =
        Message(id = id, role = "assistant", text = text, attachments = attachments.toList(), datetime = "2026-01-01T00:00:01Z")

    /** A branching chat with one node per (question, replies) pair, as the app stores it. */
    private fun chat(id: String, vararg turns: Pair<Message, List<Message>>): Chat {
        val nodes = turns.mapIndexed { i, (question, replies) ->
            MessageNode(
                nodeId = "$id-n$i", parentNodeId = if (i == 0) null else "$id-n${i - 1}",
                variants = listOf(
                    MessageVariant(
                        variantId = "$id-v$i",
                        userMessage = question.copy(nodeId = "$id-n$i", variantId = "$id-v$i"),
                        responses = replies.map { it.copy(nodeId = "$id-n$i", variantId = "$id-v$i") },
                        childNodeId = if (i == turns.lastIndex) null else "$id-n${i + 1}"
                    )
                )
            )
        }
        return ChatTreeRepair.finalizeChat(
            Chat(chat_id = id, preview_name = id, messages = emptyList(), messageNodes = nodes, currentVariantPath = nodes.map { it.variants[0].variantId }),
            nodes.map { it.variants[0].variantId }
        )
    }

    private fun history(vararg chats: Chat, groups: List<ChatGroup> = emptyList()) =
        UserChatHistory(user_name = "u", chat_history = chats.toList(), groups = groups)

    private fun enc(h: UserChatHistory): String = json.encodeToString(h)
    private fun dec(text: String): UserChatHistory = json.decodeFromString(text)

    /** What a client that does not know the fields writes back: the same JSON without them. */
    private fun asOldClientWrites(text: String): String {
        fun strip(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.filterKeys { it != "sha256" && it != "size" }.mapValues { strip(it.value) })
            is JsonArray -> JsonArray(e.map(::strip))
            else -> e
        }
        return json.encodeToString(JsonElement.serializer(), strip(json.parseToJsonElement(text)))
    }

    private fun merge(base: UserChatHistory?, local: String, remote: String): UserChatHistory =
        dec(SyncFileMerger.mergeFile(file, base?.let(::enc), local, remote, json))

    private fun allAttachments(h: UserChatHistory): List<Attachment> =
        h.chat_history.flatMap { c ->
            c.messageNodes.flatMap { n -> n.variants.flatMap { v -> v.userMessage.attachments + v.responses.flatMap { it.attachments } } }
        } + h.groups.flatMap { it.group_attachments }

    // ── serialization ────────────────────────────────────────────────────────

    @Test
    fun `history written before the fields existed still loads, and loads the same`() {
        val old = """
            {"user_name":"u","chat_history":[{"chat_id":"c","preview_name":"c","messages":[
              {"id":"m1","role":"user","text":"hi","attachments":[
                {"local_file_path":"/p/x.png","file_name":"x.png","mime_type":"image/png","file_GOOGLE_uri":"g"}]}]}],
             "groups":[{"group_id":"g","group_name":"G","group_attachments":[{"file_name":"n.txt","mime_type":"text/plain"}]}]}
        """.trimIndent()
        val h = dec(old)
        val a = h.chat_history[0].messages[0].attachments[0]
        assertNull(a.sha256)
        assertNull(a.size)
        assertEquals("g", a.file_GOOGLE_uri)
        assertNull(h.groups[0].group_attachments[0].sha256)
        // An attachment without a hash is written exactly as before: no new keys appear
        val rewritten = enc(h)
        assertFalse(rewritten.contains("sha256"))
        assertFalse(rewritten.contains("\"size\""))
    }

    @Test
    fun `the fields round trip and an old client ignores them`() {
        val h = history(chat("c", user("m1", "look", attachment(sha = shaA, size = 1234)) to emptyList()))
        val text = enc(h)
        assertTrue(text.contains("\"sha256\": \"$shaA\""))
        assertTrue(text.contains("\"size\": 1234"))
        assertEquals(h, dec(text))
        // The old client's model has no such keys; the app's Json ignores unknown keys
        val old = dec(asOldClientWrites(text))
        assertEquals(listOf<String?>(null), allAttachments(old).map { it.sha256 })
        assertEquals(
            allAttachments(h).map { it.copy(sha256 = null, size = null) },
            allAttachments(old)
        )
    }

    // ── merge: both fields survive ordinary merges ───────────────────────────

    @Test
    fun `a hash recorded on one device reaches the other`() {
        val before = history(chat("c", user("m1", "look", attachment()) to listOf(reply("r1", "nice"))))
        val after = history(chat("c", user("m1", "look", attachment(sha = shaA, size = 10)) to listOf(reply("r1", "nice"))))
        // The backfilling device uploaded; the other device merges
        assertEquals(listOf(shaA), allAttachments(merge(before, enc(before), enc(after))).map { it.sha256 })
        // The backfilling device merges an untouched remote
        assertEquals(listOf(shaA), allAttachments(merge(before, enc(after), enc(before))).map { it.sha256 })
        // First sync of a device (no base)
        assertEquals(listOf(shaA), allAttachments(merge(null, enc(before), enc(after))).map { it.sha256 })
        assertEquals(listOf(shaA), allAttachments(merge(null, enc(after), enc(before))).map { it.sha256 })
    }

    @Test
    fun `a new message with a hashed attachment merges with the other device's new chat`() {
        val base = history(chat("c", user("m1", "one") to listOf(reply("r1", "1"))))
        val local = history(
            chat("c", user("m1", "one") to listOf(reply("r1", "1")), user("m2", "file", attachment(sha = shaA, size = 5)) to emptyList())
        )
        val remote = history(
            chat("c", user("m1", "one") to listOf(reply("r1", "1"))),
            chat("d", user("x1", "other", attachment("doc.pdf", sha = shaB, size = 7)) to emptyList())
        )
        val merged = merge(base, enc(local), enc(remote))
        assertEquals(setOf(shaA to 5L, shaB to 7L), allAttachments(merged).map { it.sha256!! to it.size!! }.toSet())
    }

    // ── merge: a client that predates the fields ─────────────────────────────

    @Test
    fun `an old client's rewrite does not drop the hashes`() {
        val synced = history(
            chat("c", user("m1", "look", attachment(sha = shaA, size = 10)) to listOf(reply("r1", "made this", attachment("out.png", sha = shaB, size = 20)))),
            groups = listOf(ChatGroup("g", "G", group_attachments = listOf(attachment("project.pdf", sha = shaA, size = 10)), is_project = true))
        )
        // The old client sends a message in another chat: it rewrites the whole file
        val oldRewrite = dec(asOldClientWrites(enc(synced))).let { h ->
            h.copy(chat_history = h.chat_history + chat("new", user("n1", "from the old phone") to emptyList()))
        }
        val remote = asOldClientWrites(enc(oldRewrite))
        assertFalse(remote.contains("sha256"))

        val mergedText = SyncFileMerger.mergeFile(file, enc(synced), enc(synced), remote, json)
        val merged = dec(mergedText)
        assertEquals(listOf(shaA, shaB, shaA), allAttachments(merged).map { it.sha256 })
        assertEquals(listOf<Long?>(10, 20, 10), allAttachments(merged).map { it.size })
        assertTrue(merged.chat_history.any { it.chat_id == "new" }, "the old client's message is kept")
        // The merge differs from the remote copy, so the sync engine uploads it (repairing the server)
        assertFalse(SyncFileMerger.sameContent(file, mergedText, remote, json))
    }

    @Test
    fun `an old client that adds to a chat keeps that chat's hashes`() {
        val synced = history(chat("c", user("m1", "look", attachment(sha = shaA, size = 10)) to listOf(reply("r1", "ok"))))
        val oldRewrite = history(
            chat("c", user("m1", "look", attachment()) to listOf(reply("r1", "ok")), user("m2", "more") to listOf(reply("r2", "sure")))
        )
        val merged = merge(synced, enc(synced), asOldClientWrites(enc(oldRewrite)))
        assertEquals(listOf("look", "ok", "more", "sure"), merged.chat_history[0].messages.map { it.text })
        assertEquals(listOf(shaA), allAttachments(merged).map { it.sha256 })
        // `messages` (the displayed path) carries it too
        assertEquals(shaA, merged.chat_history[0].messages[0].attachments[0].sha256)
    }

    @Test
    fun `an old client's rewrite does not resurrect a chat deleted on this device`() {
        val synced = history(
            chat("keep", user("k1", "stay") to emptyList()),
            chat("gone", user("m1", "look", attachment(sha = shaA, size = 10)) to listOf(reply("r1", "ok")))
        )
        val local = history(chat("keep", user("k1", "stay") to emptyList()))
        // Without the restore the stripped chat would count as modified, and modification beats deletion
        val merged = merge(synced, enc(local), asOldClientWrites(enc(synced)))
        assertEquals(listOf("keep"), merged.chat_history.map { it.chat_id })
    }

    @Test
    fun `an old client that uploads the file to a provider keeps the hash and its provider id`() {
        val synced = history(chat("c", user("m1", "look", attachment(sha = shaA, size = 10)) to emptyList()))
        val oldRewrite = history(chat("c", user("m1", "look", attachment(openAi = "file-123")) to emptyList()))
        val merged = merge(synced, enc(synced), asOldClientWrites(enc(oldRewrite)))
        val a = allAttachments(merged).single()
        assertEquals(shaA, a.sha256)
        assertEquals(10L, a.size)
        assertEquals("file-123", a.file_OPENAI_id)
    }

    // ── merge: both devices changed the same message ─────────────────────────

    @Test
    fun `hash recorded on one side and provider id written on the other - the hash survives either way`() {
        val base = history(chat("c", user("m1", "look", attachment()) to emptyList()))
        val withHash = history(chat("c", user("m1", "look", attachment(sha = shaA, size = 10)) to emptyList()))
        val withId = history(chat("c", user("m1", "look", attachment(openAi = "file-123")) to emptyList()))

        // The device wrote the provider id (e.g. the server after uploading the file for a run)
        val a = allAttachments(merge(base, enc(withId), enc(withHash))).single()
        assertEquals(shaA, a.sha256)
        assertEquals(10L, a.size)
        assertEquals("file-123", a.file_OPENAI_id)

        // The device recorded the hash; local wins the message conflict (the provider id of the
        // other side is lost, as before this change — it is simply uploaded again when needed)
        val b = allAttachments(merge(base, enc(withHash), enc(withId))).single()
        assertEquals(shaA, b.sha256)
        assertEquals(10L, b.size)
    }

    @Test
    fun `project attachments keep their hash against a concurrent edit of the group`() {
        fun group(name: String, vararg attachments: Attachment) =
            ChatGroup("g", name, group_attachments = attachments.toList(), is_project = true)
        val base = history(groups = listOf(group("G", attachment("spec.pdf"))))
        val hashed = history(groups = listOf(group("G", attachment("spec.pdf", sha = shaA, size = 3))))
        val renamedWithId = history(groups = listOf(group("Renamed", attachment("spec.pdf", openAi = "file-9"))))

        val merged = merge(base, enc(renamedWithId), enc(hashed))
        assertEquals("Renamed", merged.groups[0].group_name)
        assertEquals(shaA, merged.groups[0].group_attachments.single().sha256)
        assertEquals("file-9", merged.groups[0].group_attachments.single().file_OPENAI_id)
    }

    // ── merge: the restore does not invent hashes ────────────────────────────

    @Test
    fun `a hash is restored only onto the very same attachment`() {
        val synced = history(
            chat("c", user("m1", "one", attachment("a.jpg", sha = shaA, size = 10)) to emptyList()),
            chat("d", user("m1", "same message id, another chat", attachment("a.jpg")) to emptyList())
        )
        val oldRewrite = history(
            chat(
                "c",
                // another file of the same message, and the same name at another path
                user("m1", "one", attachment("a.jpg"), attachment("b.jpg"), attachment("a.jpg").copy(local_file_path = "/elsewhere/a.jpg")) to emptyList(),
                // the same file name in another message
                user("m2", "two", attachment("a.jpg")) to emptyList()
            ),
            chat("d", user("m1", "same message id, another chat", attachment("a.jpg")) to emptyList())
        )
        val merged = merge(synced, enc(synced), asOldClientWrites(enc(oldRewrite)))
        assertEquals(listOf(shaA, null, null, null, null), allAttachments(merged).map { it.sha256 })
    }

    @Test
    fun `a replaced file keeps its own new hash`() {
        val base = history(chat("c", user("m1", "look", attachment(sha = shaA, size = 10)) to emptyList()))
        val changed = history(chat("c", user("m1", "look", attachment(sha = shaB, size = 11)) to emptyList()))
        assertEquals(shaB, allAttachments(merge(base, enc(base), enc(changed))).single().sha256)
        assertEquals(shaB, allAttachments(merge(base, enc(changed), enc(base))).single().sha256)
    }

    @Test
    fun `histories without hashes merge exactly as before`() {
        val base = history(chat("c", user("m1", "look", attachment()) to listOf(reply("r1", "ok"))))
        val local = history(chat("c", user("m1", "look", attachment(openAi = "f")) to listOf(reply("r1", "ok"))))
        val merged = SyncFileMerger.mergeFile(file, enc(base), enc(local), enc(base), json)
        assertEquals(dec(enc(local)), dec(merged))
        assertNotEquals(dec(enc(base)), dec(merged))
    }
}
