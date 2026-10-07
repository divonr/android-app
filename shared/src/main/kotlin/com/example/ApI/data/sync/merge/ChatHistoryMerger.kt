package com.example.ApI.data.sync.merge

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.MessageNode
import com.example.ApI.data.model.MessageVariant
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.util.AppLogger

/**
 * Pure 3-way merge of two chat histories against their last common (synced) base.
 * See SYNC_MERGE_PLAN.md §3.
 *
 * - Chats are keyed by `chat_id`, groups by `group_id`, nodes by `nodeId`, variants by
 *   `variantId`. `Message.id` is never used as a cross-variant key (sibling variants share
 *   user message ids by design); response lists are compared as id sequences with a
 *   content-equality fallback.
 * - Additions on either side are never lost; a deletion wins only when the other side left
 *   the deleted item unchanged (modification beats deletion).
 * - `currentVariantPath` is device view state: the local path is kept (repaired and extended
 *   to the leaf) and `messages` is recomputed from it.
 * - Without a base (first link of a device, lost state) everything is a 2-way union.
 */
object ChatHistoryMerger {
    private const val TAG = "ChatHistoryMerger"

    fun merge(base: UserChatHistory?, local: UserChatHistory, remote: UserChatHistory): UserChatHistory {
        val baseChats = base?.let { dedupeChats(it.chat_history) }
        val localChats = dedupeChats(local.chat_history)
        val remoteChats = dedupeChats(remote.chat_history)

        val (groups, deletedGroupIds) = mergeGroups(base?.groups, local.groups, remote.groups)

        val baseById = baseChats?.associateBy { it.chat_id }
        val localById = localChats.associateBy { it.chat_id }
        val remoteById = remoteChats.associateBy { it.chat_id }

        val merged = LinkedHashMap<String, Chat>()
        for (id in (localById.keys + remoteById.keys)) {
            val l = localById[id]
            val r = remoteById[id]
            val b = baseById?.get(id)
            val result = try {
                when {
                    l != null && r != null -> mergeChat(b, l, r)
                    l != null -> keepOneSided(b, baseById != null, l)
                    else -> keepOneSided(b, baseById != null, r!!)
                }
            } catch (e: Exception) {
                AppLogger.e("[$TAG] Failed to merge chat $id; keeping one side", e)
                l ?: r
            }
            if (result != null) merged[id] = result
        }

        val order = MergeSupport.mergeOrder(
            baseChats?.map { it.chat_id },
            localChats.map { it.chat_id },
            remoteChats.map { it.chat_id },
            merged.keys
        )
        val chats = order.map { id ->
            val chat = merged.getValue(id)
            if (chat.group != null && chat.group in deletedGroupIds) chat.copy(group = null) else chat
        }
        return UserChatHistory(user_name = local.user_name, chat_history = chats, groups = groups)
    }

    // ---------------------------------------------------------------------------------
    // Chats
    // ---------------------------------------------------------------------------------

    /** Unite chats sharing a `chat_id` inside one file (2-way), keeping the first position. */
    private fun dedupeChats(chats: List<Chat>): List<Chat> {
        if (chats.map { it.chat_id }.toSet().size == chats.size) return chats
        val byId = LinkedHashMap<String, Chat>()
        for (chat in chats) {
            val existing = byId[chat.chat_id]
            byId[chat.chat_id] = if (existing == null) chat else mergeChat(null, existing, chat)
        }
        return byId.values.toList()
    }

    /**
     * A chat present on one side only: added (absent from base) → kept; present in base →
     * deleted on the other side, kept only if this side modified it.
     */
    private fun keepOneSided(base: Chat?, hasBase: Boolean, chat: Chat): Chat? {
        if (hasBase && base != null && !isModified(base, chat)) return null
        return ChatTreeRepair.finalizeChat(chat, chat.currentVariantPath)
    }

    /**
     * Content modification relative to base. View state (path, derived messages of branching
     * chats) and the `group` field (cleared as a side effect of deleting a group) are ignored.
     */
    private fun isModified(base: Chat, chat: Chat): Boolean {
        if (base.preview_name != chat.preview_name || base.systemPrompt != chat.systemPrompt ||
            base.shareLink != chat.shareLink || base.shareId != chat.shareId
        ) return true
        if (!base.hasBranchingStructure && !chat.hasBranchingStructure) {
            return base.messages.map(ChatTreeRepair::stripMessage) != chat.messages.map(ChatTreeRepair::stripMessage)
        }
        return !sameTree(treeOf(base), treeOf(chat))
    }

    private fun treeOf(chat: Chat): List<MessageNode> =
        ChatTreeRepair.repair(chat.chat_id, LegacyChatConverter.toBranching(chat).messageNodes)

    private fun sameTree(a: List<MessageNode>, b: List<MessageNode>): Boolean =
        ChatTreeRepair.stripNodes(a) == ChatTreeRepair.stripNodes(b)

    private fun mergeChat(base: Chat?, local: Chat, remote: Chat): Chat {
        if (local == remote) return ChatTreeRepair.finalizeChat(local, local.currentVariantPath)
        val hasBase = base != null
        val scalars = local.copy(
            preview_name = MergeSupport.pick(base?.preview_name, hasBase, local.preview_name, remote.preview_name) { it.isBlank() },
            systemPrompt = MergeSupport.pick(base?.systemPrompt, hasBase, local.systemPrompt, remote.systemPrompt) { it.isEmpty() },
            group = MergeSupport.pick(base?.group, hasBase, local.group, remote.group) { it == null },
            shareLink = MergeSupport.pick(base?.shareLink, hasBase, local.shareLink, remote.shareLink) { it.isEmpty() },
            shareId = MergeSupport.pick(base?.shareId, hasBase, local.shareId, remote.shareId) { it.isEmpty() }
        )

        val bothLegacy = !local.hasBranchingStructure && !remote.hasBranchingStructure
        if (bothLegacy) {
            val baseMessages = base?.messages
            val linear = mergeLinear(baseMessages, local.messages, remote.messages)
            if (linear != null) {
                return scalars.copy(messages = linear, messageNodes = emptyList(), currentVariantPath = emptyList())
            }
        }

        val nodes = mergeTrees(
            local.chat_id,
            base?.let { treeOf(it) },
            treeOf(local),
            treeOf(remote)
        )
        val preferredPath = if (local.hasBranchingStructure) local.currentVariantPath
            else LegacyChatConverter.toBranching(local).currentVariantPath
        if (nodes.isEmpty()) {
            // Everything deleted: an empty chat (a legacy local keeps its unconvertible messages)
            val messages = if (local.hasBranchingStructure) emptyList() else local.messages
            return scalars.copy(messages = messages, messageNodes = emptyList(), currentVariantPath = emptyList())
        }
        return ChatTreeRepair.finalizeChat(scalars.copy(messageNodes = nodes), preferredPath)
    }

    /**
     * Linear (legacy-vs-legacy) merge: equal / unchanged side / prefix → the longer list.
     * Returns null when the lists diverge (the caller then merges them as trees).
     */
    private fun mergeLinear(base: List<Message>?, local: List<Message>, remote: List<Message>): List<Message>? {
        val l = local.map(ChatTreeRepair::stripMessage)
        val r = remote.map(ChatTreeRepair::stripMessage)
        if (l == r) return local
        if (base != null) {
            val b = base.map(ChatTreeRepair::stripMessage)
            if (l == b) return remote
            if (r == b) return local
        }
        if (isPrefix(local, remote)) return remote
        if (isPrefix(remote, local)) return local
        return null
    }

    /** Same message for sequence comparison: same role and text, and same id or same time. */
    private fun sameMessage(a: Message, b: Message): Boolean =
        a.role == b.role && a.text == b.text && (a.id == b.id || a.datetime == b.datetime)

    private fun isPrefix(prefix: List<Message>, of: List<Message>): Boolean =
        prefix.size <= of.size && prefix.indices.all { sameMessage(prefix[it], of[it]) }

    // ---------------------------------------------------------------------------------
    // Trees
    // ---------------------------------------------------------------------------------

    private class Side(val nodes: List<MessageNode>) {
        val nodeById = nodes.associateBy { it.nodeId }
        val variantById = HashMap<String, MessageVariant>()
        val nodeOfVariant = HashMap<String, String>()
        /** child nodeId → owning variant id */
        val ownerOfNode = HashMap<String, String>()
        val root: String? = nodes.firstOrNull { it.parentNodeId == null }?.nodeId

        init {
            for (n in nodes) for (v in n.variants) {
                variantById[v.variantId] = v
                nodeOfVariant[v.variantId] = n.nodeId
                v.childNodeId?.let { ownerOfNode[it] = v.variantId }
            }
        }

        fun has(variantId: String) = variantId in variantById
    }

    private class UnionFind(private val rank: (String) -> String) {
        private val parent = HashMap<String, String>()
        fun add(x: String) { parent.putIfAbsent(x, x) }
        fun find(x: String): String {
            var r = x
            while (parent.getValue(r) != r) r = parent.getValue(r)
            var c = x
            while (parent.getValue(c) != r) { val next = parent.getValue(c); parent[c] = r; c = next }
            return r
        }
        fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            // The better-ranked id survives as the class representative
            if (rank(ra) <= rank(rb)) parent[rb] = ra else parent[ra] = rb
        }
    }

    private fun mergeTrees(
        chatId: String,
        baseNodes: List<MessageNode>?,
        localNodes: List<MessageNode>,
        remoteNodes: List<MessageNode>
    ): List<MessageNode> {
        val l = Side(localNodes)
        val r = Side(remoteNodes)
        val b = baseNodes?.let { Side(it) }

        // Whole-tree shortcuts (keeps ids/order exactly when only one side changed)
        if (sameTree(localNodes, remoteNodes)) return localNodes
        if (b != null && sameTree(localNodes, b.nodes)) return remoteNodes
        if (b != null && sameTree(remoteNodes, b.nodes)) return localNodes

        // 1. Per-variant decisions and contents
        val allIds = LinkedHashSet<String>()
        localNodes.forEach { n -> n.variants.forEach { allIds += it.variantId } }
        remoteNodes.forEach { n -> n.variants.forEach { allIds += it.variantId } }
        val takenIds = HashSet(allIds)
        b?.variantById?.keys?.let { takenIds += it }

        val content = HashMap<String, MessageVariant>()
        val forkOf = HashMap<String, MessageVariant>()
        val kept = HashSet<String>()
        for (id in allIds) {
            val lv = l.variantById[id]
            val rv = r.variantById[id]
            val bv = b?.variantById?.get(id)
            if (lv != null && rv != null) {
                kept += id
                val (main, fork) = mergeVariant(bv, lv, rv, takenIds)
                content[id] = main
                if (fork != null) forkOf[id] = fork
            } else {
                val v = lv ?: rv!!
                content[id] = v
                if (b == null || bv == null || !sameVariantContent(bv, v)) kept += id
            }
        }

        // 2. A variant is needed if kept or if anything below it is kept
        val needed = HashSet(kept)
        val queue = ArrayDeque(kept)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            for (side in listOf(l, r)) {
                val node = side.nodeOfVariant[id] ?: continue
                val owner = side.ownerOfNode[node] ?: continue
                if (needed.add(owner)) queue.addLast(owner)
            }
        }

        // 3. Live nodes per side
        fun alive(side: Side, nodeId: String?): Boolean =
            nodeId != null && side.nodeById[nodeId]?.variants?.any { it.variantId in needed } == true
        val baseNodeIds = b?.nodeById?.keys ?: emptySet()

        // 4. Unite nodes that must become one
        // Representative: a node present in base, else the smallest id (symmetric, deterministic)
        val uf = UnionFind { id -> if (id in baseNodeIds) "0:$id" else "1:$id" }
        localNodes.filter { alive(l, it.nodeId) }.forEach { uf.add(it.nodeId) }
        remoteNodes.filter { alive(r, it.nodeId) }.forEach { uf.add(it.nodeId) }
        for (id in needed) {
            val nl = l.nodeOfVariant[id]
            val nr = r.nodeOfVariant[id]
            // Same variant inside different nodes (a fold happened on one side)
            if (nl != null && nr != null && nl != nr) uf.union(nl, nr)
            // Both continued after the same variant with different next nodes → fold
            if (nl != null && nr != null && id !in forkOf) {
                val cl = l.variantById.getValue(id).childNodeId?.takeIf { alive(l, it) }
                val cr = r.variantById.getValue(id).childNodeId?.takeIf { alive(r, it) }
                if (cl != null && cr != null && cl != cr) uf.union(cl, cr)
            }
        }
        // Two roots → fold
        if (alive(l, l.root) && alive(r, r.root) && l.root != r.root) uf.union(l.root!!, r.root!!)

        // 5. Assemble nodes: variants ordered base, local, remote (append only), forks last
        val classOrder = LinkedHashMap<String, MutableList<MessageVariant>>()
        localNodes.forEach { if (alive(l, it.nodeId)) classOrder.getOrPut(uf.find(it.nodeId)) { mutableListOf() } }
        remoteNodes.forEach { if (alive(r, it.nodeId)) classOrder.getOrPut(uf.find(it.nodeId)) { mutableListOf() } }

        fun classOf(variantId: String): String? = (l.nodeOfVariant[variantId] ?: r.nodeOfVariant[variantId])?.let(uf::find)
        fun childFor(side: Side, variantId: String): String? =
            side.variantById[variantId]?.childNodeId?.takeIf { alive(side, it) }?.let(uf::find)

        val sequence = LinkedHashSet<String>()
        b?.nodes?.forEach { n -> n.variants.forEach { sequence += it.variantId } }
        sequence += allIds
        val placed = HashSet<String>()
        for (id in sequence) {
            if (id !in needed || id !in content || !placed.add(id)) continue
            val cls = classOf(id) ?: continue
            val forked = id in forkOf
            val childL = childFor(l, id)
            val childR = childFor(r, id)
            val child = if (forked) (if (l.has(id)) childL else childR) else (childL ?: childR)
            classOrder.getValue(cls) += content.getValue(id).copy(childNodeId = child)
        }
        for (id in sequence) {
            val fork = forkOf[id] ?: continue
            val cls = classOf(id) ?: continue
            val childL = childFor(l, id)
            val childR = childFor(r, id)
            classOrder.getValue(cls) += fork.copy(childNodeId = childR?.takeIf { it != childL })
        }

        val rootClass = (l.root?.takeIf { alive(l, it) } ?: r.root?.takeIf { alive(r, it) })?.let(uf::find)
        val parentOfClass = HashMap<String, String>()
        for (side in listOf(l, r)) for (n in side.nodes) {
            if (!alive(side, n.nodeId)) continue
            val cls = uf.find(n.nodeId)
            val p = n.parentNodeId?.takeIf { alive(side, it) }?.let(uf::find)
            if (p != null && p != cls) parentOfClass.putIfAbsent(cls, p)
        }
        // Unknown parent → self: repair re-attaches such a node
        fun parentClass(cls: String): String? = if (cls == rootClass) null else parentOfClass[cls] ?: cls
        // parentNodeId is re-derived by repair; it only guides re-attachment of orphans
        val nodes = classOrder.map { (cls, variants) ->
            MessageNode(nodeId = cls, parentNodeId = parentClass(cls), variants = variants)
        }
        return ChatTreeRepair.repair(chatId, nodes)
    }

    private fun sameVariantContent(a: MessageVariant, b: MessageVariant): Boolean =
        ChatTreeRepair.stripMessage(a.userMessage) == ChatTreeRepair.stripMessage(b.userMessage) &&
            a.responses.map(ChatTreeRepair::stripMessage) == b.responses.map(ChatTreeRepair::stripMessage)

    /**
     * Merges one variant present on both sides. Returns the merged variant (child decided by
     * the caller) and, when the response lists diverged, a fork variant holding the remote
     * continuation (deterministic id derived from the remote variant id).
     */
    private fun mergeVariant(
        base: MessageVariant?,
        local: MessageVariant,
        remote: MessageVariant,
        takenIds: MutableSet<String>
    ): Pair<MessageVariant, MessageVariant?> {
        val strip = ChatTreeRepair::stripMessage
        val userMessage = MessageMerge.pick(base?.userMessage, local.userMessage, remote.userMessage)

        val lr = local.responses.map(strip)
        val rr = remote.responses.map(strip)
        val br = base?.responses?.map(strip)
        val responses: List<Message>? = when {
            lr == rr -> local.responses
            br != null && lr == br -> remote.responses
            br != null && rr == br -> local.responses
            isPrefix(local.responses, remote.responses) -> remote.responses
            isPrefix(remote.responses, local.responses) -> local.responses
            else -> null
        }
        if (responses != null) {
            return local.copy(userMessage = userMessage, responses = responses) to null
        }
        val forkId = MergeSupport.uniqueId("${remote.variantId}:fork", takenIds)
        val fork = remote.copy(variantId = forkId, childNodeId = null)
        return local to fork
    }

    private object MessageMerge {
        fun pick(base: Message?, local: Message, remote: Message): Message {
            val l = ChatTreeRepair.stripMessage(local)
            val r = ChatTreeRepair.stripMessage(remote)
            return when {
                l == r -> local
                base != null && ChatTreeRepair.stripMessage(base) == l -> remote
                else -> local
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Groups
    // ---------------------------------------------------------------------------------

    /** Returns the merged groups and the ids of groups whose deletion won. */
    private fun mergeGroups(
        base: List<ChatGroup>?,
        local: List<ChatGroup>,
        remote: List<ChatGroup>
    ): Pair<List<ChatGroup>, Set<String>> {
        val baseById = base?.associateBy { it.group_id }
        val localById = local.associateBy { it.group_id }
        val remoteById = remote.associateBy { it.group_id }
        val merged = LinkedHashMap<String, ChatGroup>()
        for (id in localById.keys + remoteById.keys) {
            val l = localById[id]
            val r = remoteById[id]
            val b = baseById?.get(id)
            val result = when {
                l != null && r != null -> mergeGroup(b, l, r)
                else -> {
                    val g = l ?: r!!
                    if (baseById != null && b != null && b == g) null else g
                }
            }
            if (result != null) merged[id] = result
        }
        val order = MergeSupport.mergeOrder(
            base?.map { it.group_id }, local.map { it.group_id }, remote.map { it.group_id }, merged.keys
        )
        val deleted = (baseById?.keys ?: emptySet()) - merged.keys
        return order.map { merged.getValue(it) } to deleted
    }

    private fun mergeGroup(base: ChatGroup?, local: ChatGroup, remote: ChatGroup): ChatGroup {
        val hasBase = base != null
        return ChatGroup(
            group_id = local.group_id,
            group_name = MergeSupport.pick(base?.group_name, hasBase, local.group_name, remote.group_name) { it.isBlank() },
            system_prompt = MergeSupport.pick(base?.system_prompt, hasBase, local.system_prompt, remote.system_prompt) { it.isNullOrEmpty() },
            group_attachments = MergeSupport.pick(base?.group_attachments, hasBase, local.group_attachments, remote.group_attachments) { it.isEmpty() },
            is_project = MergeSupport.pick(base?.is_project, hasBase, local.is_project, remote.is_project) { !it }
        )
    }
}
