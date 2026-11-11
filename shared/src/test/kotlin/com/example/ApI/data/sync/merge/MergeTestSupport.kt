package com.example.ApI.data.sync.merge

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.MessageNode
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.data.repository.ChatHistoryManager
import com.example.ApI.data.repository.DeleteMessageResult
import com.example.ApI.data.repository.MessageBranchingManager
import com.example.ApI.util.JsonConfig
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Monotonic fake clock so "newest" decisions are deterministic. */
class FakeClock(private var millis: Long = 1_700_000_000_000L) {
    fun now(): String {
        millis += 1000
        return Instant.ofEpochMilli(millis).toString()
    }
}

/**
 * One replica: a temp data dir driven through the real ChatHistoryManager and
 * MessageBranchingManager, so trees have exactly the shapes the app produces.
 */
class Device(dir: File, private val clock: FakeClock, val user: String = "u") {
    val chm = ChatHistoryManager(dir.apply { mkdirs() }, JsonConfig.prettyPrint)
    val mbm = MessageBranchingManager(chm)

    fun load(): UserChatHistory = chm.loadChatHistory(user)
    fun save(history: UserChatHistory) = chm.saveChatHistory(history.copy(user_name = user))
    fun chat(chatId: String): Chat = load().chat_history.first { it.chat_id == chatId }

    private fun update(transform: (UserChatHistory) -> UserChatHistory) = save(transform(load()))

    fun newChat(chatId: String, name: String = chatId, group: String? = null) = update {
        it.copy(chat_history = it.chat_history + Chat(chat_id = chatId, preview_name = name, messages = emptyList(), group = group))
    }

    /** A pre-branching chat (only `messages`). */
    fun newLegacyChat(chatId: String, vararg texts: String) = update {
        val messages = texts.mapIndexed { i, t ->
            Message(role = if (i % 2 == 0) "user" else "assistant", text = t, datetime = clock.now())
        }
        it.copy(chat_history = it.chat_history + Chat(chat_id = chatId, preview_name = chatId, messages = messages))
    }

    /** Old-style append that keeps a legacy chat linear. */
    fun legacyAppend(chatId: String, role: String, text: String) = update { h ->
        h.copy(chat_history = h.chat_history.map {
            if (it.chat_id == chatId) it.copy(messages = it.messages + Message(role = role, text = text, datetime = clock.now())) else it
        })
    }

    /** Deterministic migration (what T3 makes MessageBranchingManager do). */
    fun ensureBranching(chatId: String) = update { h ->
        h.copy(chat_history = h.chat_history.map { if (it.chat_id == chatId) LegacyChatConverter.toBranching(it) else it })
    }

    fun send(chatId: String, text: String) {
        ensureBranching(chatId)
        mbm.addUserMessageAsNewNode(user, chatId, Message(role = "user", text = text, datetime = clock.now()))
    }

    fun reply(chatId: String, text: String, role: String = "assistant") {
        ensureBranching(chatId)
        mbm.addResponseToCurrentVariant(user, chatId, Message(role = role, text = text, datetime = clock.now(), model = "m"))
    }

    /** Edit the user message of the [pathIndex]-th node on the current path (new sibling variant). */
    fun edit(chatId: String, pathIndex: Int, text: String): Boolean {
        ensureBranching(chatId)
        val chat = chat(chatId)
        val variantId = chat.currentVariantPath.getOrNull(pathIndex) ?: return false
        val node = chat.messageNodes.firstOrNull { n -> n.variants.any { it.variantId == variantId } } ?: return false
        val original = node.variants.first { it.variantId == variantId }.userMessage
        return mbm.createBranch(user, chatId, node.nodeId, original.copy(text = text, datetime = clock.now())) != null
    }

    fun switchVariant(chatId: String, pathIndex: Int, variantIndex: Int): Boolean {
        ensureBranching(chatId)
        val chat = chat(chatId)
        val variantId = chat.currentVariantPath.getOrNull(pathIndex) ?: return false
        val node = chat.messageNodes.firstOrNull { n -> n.variants.any { it.variantId == variantId } } ?: return false
        return mbm.switchVariant(user, chatId, node.nodeId, variantIndex) != null
    }

    /** Delete the last message shown (a leaf). */
    fun deleteLast(chatId: String): Boolean {
        val chat = chat(chatId)
        val last = chat.messages.lastOrNull() ?: return false
        return mbm.deleteMessageFromBranch(user, chatId, last.id) is DeleteMessageResult.Success
    }

    fun deleteChat(chatId: String) = update { h -> h.copy(chat_history = h.chat_history.filter { it.chat_id != chatId }) }

    fun rename(chatId: String, name: String) = update { h ->
        h.copy(chat_history = h.chat_history.map { if (it.chat_id == chatId) it.copy(preview_name = name) else it })
    }

    fun setChatGroup(chatId: String, groupId: String?) = update { h ->
        h.copy(chat_history = h.chat_history.map { if (it.chat_id == chatId) it.copy(group = groupId) else it })
    }

    fun addGroup(groupId: String, name: String) = update { h ->
        h.copy(groups = h.groups + ChatGroup(group_id = groupId, group_name = name))
    }

    fun renameGroup(groupId: String, name: String) = update { h ->
        h.copy(groups = h.groups.map { if (it.group_id == groupId) it.copy(group_name = name) else it })
    }

    /** Same as GroupProjectManager.deleteGroup. */
    fun deleteGroup(groupId: String) = update { h ->
        h.copy(
            chat_history = h.chat_history.map { if (it.group == groupId) it.copy(group = null) else it },
            groups = h.groups.filter { it.group_id != groupId }
        )
    }
}

object MergeAssert {

    /** Every structural invariant of a merged history. */
    fun assertValid(history: UserChatHistory) {
        val chatIds = history.chat_history.map { it.chat_id }
        assertEquals(chatIds.size, chatIds.toSet().size, "duplicate chat ids")
        for (chat in history.chat_history) assertValidChat(chat)
    }

    fun assertValidChat(chat: Chat) {
        val ctx = "chat ${chat.chat_id}"
        if (chat.messageNodes.isEmpty()) {
            assertTrue(chat.currentVariantPath.isEmpty(), "$ctx: legacy chat with a path")
            return
        }
        val nodes = chat.messageNodes
        val byId = nodes.associateBy { it.nodeId }
        assertEquals(nodes.size, byId.size, "$ctx: duplicate node ids")
        val roots = nodes.filter { it.parentNodeId == null }
        assertEquals(1, roots.size, "$ctx: expected exactly one root")
        val variantIds = nodes.flatMap { n -> n.variants.map { it.variantId } }
        assertEquals(variantIds.size, variantIds.toSet().size, "$ctx: variant id in two places")
        val owners = HashMap<String, String>()
        for (n in nodes) {
            assertTrue(n.variants.isNotEmpty(), "$ctx: empty node ${n.nodeId}")
            for (v in n.variants) {
                for (m in v.allMessages) {
                    assertEquals(n.nodeId, m.nodeId, "$ctx: bad nodeId stamp")
                    assertEquals(v.variantId, m.variantId, "$ctx: bad variantId stamp")
                }
                val child = v.childNodeId ?: continue
                val childNode = byId[child] ?: fail("$ctx: dangling childNodeId $child")
                assertEquals(n.nodeId, childNode.parentNodeId, "$ctx: child's parentNodeId mismatch")
                assertTrue(owners.put(child, v.variantId) == null, "$ctx: node $child owned twice")
            }
        }
        // Reachability (no cycles / orphans)
        val reached = HashSet<String>()
        val stack = ArrayDeque(listOf(roots.single()))
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            assertTrue(reached.add(n.nodeId), "$ctx: cycle")
            n.variants.mapNotNull { it.childNodeId }.forEach { stack.addLast(byId.getValue(it)) }
        }
        assertEquals(nodes.size, reached.size, "$ctx: unreachable nodes")
        // Path: root → leaf chain
        val path = chat.currentVariantPath
        assertTrue(path.isNotEmpty(), "$ctx: empty path")
        var node: MessageNode? = roots.single()
        val walked = mutableListOf<Message>()
        for ((i, id) in path.withIndex()) {
            val n = node ?: fail("$ctx: path longer than tree")
            val v = n.variants.firstOrNull { it.variantId == id } ?: fail("$ctx: path[$i] not in expected node")
            walked += v.allMessages
            node = v.childNodeId?.let { byId[it] }
        }
        assertTrue(node == null, "$ctx: path does not reach a leaf")
        assertEquals(walked, chat.messages, "$ctx: messages != path walk")
    }

    /** All message texts in a history (tree nodes or legacy `messages`). */
    fun texts(history: UserChatHistory): Set<String> = history.chat_history.flatMapTo(HashSet()) { textsOf(it) }

    fun textsOf(chat: Chat): Set<String> =
        if (chat.messageNodes.isEmpty()) chat.messages.mapTo(HashSet()) { it.text }
        else chat.messageNodes.flatMapTo(HashSet()) { n -> n.variants.flatMap { v -> v.allMessages.map { it.text } } }

    /** History content without device view state, in a form comparable across merges. */
    fun contentView(history: UserChatHistory): Any = Triple(
        history.user_name,
        history.chat_history.map { chat ->
            if (chat.messageNodes.isEmpty()) chat.copy(messages = chat.messages.map(ChatTreeRepair::stripMessage), currentVariantPath = emptyList())
            else chat.copy(
                messages = emptyList(),
                currentVariantPath = emptyList(),
                messageNodes = ChatTreeRepair.stripNodes(ChatTreeRepair.repair(chat.chat_id, chat.messageNodes))
            )
        },
        history.groups
    )

    fun assertSameContent(expected: UserChatHistory, actual: UserChatHistory, message: String) {
        assertEquals(contentView(expected), contentView(actual), message)
    }
}
