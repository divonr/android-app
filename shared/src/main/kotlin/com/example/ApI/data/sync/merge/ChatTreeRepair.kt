package com.example.ApI.data.sync.merge

import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.MessageNode
import com.example.ApI.data.model.MessageVariant

/**
 * Validation/repair of a chat's message tree, plus the derived view state
 * (`currentVariantPath`, `messages`). Never drops message content: broken links are
 * re-attached or folded instead of discarded.
 */
internal object ChatTreeRepair {

    private class MutNode(val nodeId: String, var parentNodeId: String?, val variants: MutableList<MessageVariant>)

    fun stripMessage(m: Message): Message =
        if (m.nodeId == null && m.variantId == null) m else m.copy(nodeId = null, variantId = null)

    fun stripVariant(v: MessageVariant): MessageVariant =
        v.copy(userMessage = stripMessage(v.userMessage), responses = v.responses.map(::stripMessage))

    fun stripNodes(nodes: List<MessageNode>): List<MessageNode> =
        nodes.map { n -> n.copy(variants = n.variants.map(::stripVariant)) }

    /**
     * Returns a valid tree:
     * - node ids and variant ids unique (duplicate nodes are united, conflicting duplicate
     *   variants get a deterministic new id, identical duplicates are dropped);
     * - nodes without variants removed;
     * - exactly one root; every `childNodeId` resolves, is owned by a single variant and the
     *   child's `parentNodeId` is the owning node; no cycles;
     * - orphaned nodes are linked to a free variant of their recorded parent, or folded into
     *   a reachable node (the parent's child, else the root);
     * - every message is stamped with its node/variant id.
     */
    fun repair(chatId: String, nodes: List<MessageNode>): List<MessageNode> {
        if (nodes.isEmpty()) return nodes

        val takenVariantIds = HashSet<String>()
        nodes.forEach { n -> n.variants.forEach { takenVariantIds += it.variantId } }

        // 1. Unite duplicate node ids; make variant ids unique
        val map = LinkedHashMap<String, MutNode>()
        val seenVariants = HashMap<String, MessageVariant>()
        for (node in nodes) {
            val target = map.getOrPut(node.nodeId) { MutNode(node.nodeId, node.parentNodeId, mutableListOf()) }
            for (v in node.variants) {
                val previous = seenVariants[v.variantId]
                when {
                    previous == null -> {
                        seenVariants[v.variantId] = v
                        target.variants += v
                    }
                    stripVariant(previous) == stripVariant(v) -> Unit
                    else -> {
                        val newId = MergeSupport.uniqueId("$chatId:${v.variantId}:dup:${node.nodeId}", takenVariantIds)
                        val renamed = v.copy(variantId = newId)
                        seenVariants[newId] = renamed
                        target.variants += renamed
                    }
                }
            }
        }
        map.values.removeAll { it.variants.isEmpty() }
        if (map.isEmpty()) return emptyList()

        // 2. Null dangling and self references
        for (node in map.values) {
            for (i in node.variants.indices) {
                val child = node.variants[i].childNodeId ?: continue
                if (child == node.nodeId || child !in map) {
                    node.variants[i] = node.variants[i].copy(childNodeId = null)
                }
            }
        }

        // 3. Walk from the root; first reference to a node wins
        val referenced = map.values.flatMap { n -> n.variants.mapNotNull { it.childNodeId } }.toSet()
        val root = map.values.firstOrNull { it.parentNodeId == null && it.nodeId !in referenced }
            ?: map.values.firstOrNull { it.nodeId !in referenced }
            ?: map.values.first()
        val reached = HashSet<String>()
        val parentOf = HashMap<String, String?>()
        reached += root.nodeId
        parentOf[root.nodeId] = null

        fun walk(startNode: MutNode, fromVariant: Int) {
            val stack = ArrayDeque<Pair<MutNode, Int>>()
            stack.addLast(startNode to fromVariant)
            while (stack.isNotEmpty()) {
                val (node, from) = stack.removeLast()
                for (i in from until node.variants.size) {
                    val child = node.variants[i].childNodeId ?: continue
                    if (child in reached) {
                        node.variants[i] = node.variants[i].copy(childNodeId = null)
                    } else {
                        reached += child
                        parentOf[child] = node.nodeId
                        stack.addLast(map.getValue(child) to 0)
                    }
                }
            }
        }
        walk(root, 0)

        // 4. Re-attach everything not reachable from the root
        while (reached.size < map.size) {
            val unreached = map.values.filter { it.nodeId !in reached }
            val referencedByUnreached = unreached.flatMap { n -> n.variants.mapNotNull { it.childNodeId } }.toSet()
            val orphan = unreached.firstOrNull { it.nodeId !in referencedByUnreached } ?: unreached.first()
            // Break cycles through the orphan
            for (u in unreached) {
                for (i in u.variants.indices) {
                    if (u.variants[i].childNodeId == orphan.nodeId) u.variants[i] = u.variants[i].copy(childNodeId = null)
                }
            }
            val parent = orphan.parentNodeId?.let { map[it] }?.takeIf { it.nodeId in reached }
            val freeIndex = parent?.variants?.indexOfFirst { it.childNodeId == null } ?: -1
            if (parent != null && freeIndex >= 0) {
                parent.variants[freeIndex] = parent.variants[freeIndex].copy(childNodeId = orphan.nodeId)
                reached += orphan.nodeId
                parentOf[orphan.nodeId] = parent.nodeId
                walk(orphan, 0)
            } else {
                val target = parent?.variants?.firstNotNullOfOrNull { it.childNodeId }?.let { map[it] } ?: root
                val from = target.variants.size
                target.variants.addAll(orphan.variants)
                map.remove(orphan.nodeId)
                walk(target, from)
            }
        }

        // 5. Emit with parents and stamps
        return map.values.map { node ->
            MessageNode(
                nodeId = node.nodeId,
                parentNodeId = parentOf[node.nodeId],
                variants = node.variants.map { stampVariant(node.nodeId, it) }
            )
        }
    }

    private fun stampVariant(nodeId: String, v: MessageVariant): MessageVariant {
        fun stamp(m: Message) = if (m.nodeId == nodeId && m.variantId == v.variantId) m
            else m.copy(nodeId = nodeId, variantId = v.variantId)
        return v.copy(userMessage = stamp(v.userMessage), responses = v.responses.map(::stamp))
    }

    private fun epochMillis(datetime: String?): Long {
        if (datetime == null) return Long.MIN_VALUE
        return try {
            java.time.Instant.parse(datetime).toEpochMilli()
        } catch (e: Exception) {
            Long.MIN_VALUE
        }
    }

    /**
     * Root→leaf variant path over a valid tree: at each node the variant from [preferred]
     * (device view state) if present, else the variant whose subtree holds the newest message
     * (ties → the most recently added variant). Always extended down to a leaf.
     */
    fun repairPath(nodes: List<MessageNode>, preferred: List<String>): List<String> {
        if (nodes.isEmpty()) return emptyList()
        val byId = nodes.associateBy { it.nodeId }
        val root = nodes.firstOrNull { it.parentNodeId == null } ?: nodes.first()

        // Newest message time per node subtree, computed children-first
        val order = mutableListOf<MessageNode>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<MessageNode>()
        queue.addLast(root)
        visited += root.nodeId
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            order += n
            for (v in n.variants) {
                val c = v.childNodeId?.let { byId[it] } ?: continue
                if (visited.add(c.nodeId)) queue.addLast(c)
            }
        }
        val nodeNewest = HashMap<String, Long>()
        val variantNewest = HashMap<String, Long>()
        for (n in order.asReversed()) {
            var best = Long.MIN_VALUE
            for (v in n.variants) {
                var t = epochMillis(v.userMessage.datetime)
                for (r in v.responses) t = maxOf(t, epochMillis(r.datetime))
                v.childNodeId?.let { c -> nodeNewest[c]?.let { t = maxOf(t, it) } }
                variantNewest[v.variantId] = t
                best = maxOf(best, t)
            }
            nodeNewest[n.nodeId] = best
        }

        val preferredIndex = HashMap<String, Int>()
        preferred.forEachIndexed { i, id -> preferredIndex.putIfAbsent(id, i) }

        val path = mutableListOf<String>()
        val seen = HashSet<String>()
        var node: MessageNode? = root
        while (node != null && seen.add(node.nodeId) && node.variants.isNotEmpty()) {
            val chosen = node.variants.filter { it.variantId in preferredIndex }.minByOrNull { preferredIndex.getValue(it.variantId) }
                ?: node.variants.reduce { acc, v ->
                    if ((variantNewest[v.variantId] ?: Long.MIN_VALUE) >= (variantNewest[acc.variantId] ?: Long.MIN_VALUE)) v else acc
                }
            path += chosen.variantId
            node = chosen.childNodeId?.let { byId[it] }
        }
        return path
    }

    /** Messages along [path] (user message + responses of each variant). */
    fun messagesForPath(nodes: List<MessageNode>, path: List<String>): List<Message> {
        val variants = HashMap<String, MessageVariant>()
        nodes.forEach { n -> n.variants.forEach { variants[it.variantId] = it } }
        return path.flatMap { id -> variants[id]?.allMessages ?: emptyList() }
    }

    /**
     * Repairs a branching chat and recomputes its view state from [preferredPath].
     * Legacy chats (no nodes) are returned unchanged.
     */
    fun finalizeChat(chat: Chat, preferredPath: List<String>): Chat {
        if (chat.messageNodes.isEmpty()) return chat
        val nodes = repair(chat.chat_id, chat.messageNodes)
        if (nodes.isEmpty()) return chat.copy(messageNodes = emptyList(), currentVariantPath = emptyList(), messages = emptyList())
        val path = repairPath(nodes, preferredPath)
        return chat.copy(messageNodes = nodes, currentVariantPath = path, messages = messagesForPath(nodes, path))
    }
}
