package com.example.ApI.data.sync.merge

import java.util.UUID

/** Small helpers shared by the merge engines. */
internal object MergeSupport {

    /** Deterministic UUID (v3) derived from [seed]. */
    fun nameUuid(seed: String): String =
        UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()

    /**
     * Deterministic id derived from [seed] that is not in [taken]; re-hashes on collision.
     * The returned id is added to [taken].
     */
    fun uniqueId(seed: String, taken: MutableSet<String>): String {
        var candidate = nameUuid(seed)
        while (candidate in taken) candidate = nameUuid("$candidate:next")
        taken += candidate
        return candidate
    }

    /**
     * Order of a merged keyed list.
     * - If local did not reorder/add/remove relative to base, remote's order is adopted
     *   (so a remote-only reorder or move-to-end survives).
     * - Otherwise local order, followed by remote-only keys in remote order.
     * Only keys in [keep] are returned; local-only/remote-only survivors not covered by the
     * chosen order are appended.
     */
    fun <K> mergeOrder(base: List<K>?, local: List<K>, remote: List<K>, keep: Set<K>): List<K> {
        val primary = if (base != null && local == base) remote else local
        val secondary = if (primary === remote) local else remote
        val out = LinkedHashSet<K>()
        for (k in primary) if (k in keep) out += k
        for (k in secondary) if (k in keep) out += k
        return out.toList()
    }

    /**
     * Generic 3-way pick for one scalar value.
     * - Equal sides → that value.
     * - With a base: the side that changed wins; both changed → local.
     * - Without a base: local, unless local is default/empty and remote is not.
     */
    fun <T> pick(base: T?, hasBase: Boolean, local: T, remote: T, isDefault: (T) -> Boolean): T = when {
        local == remote -> local
        hasBase && local == base -> remote
        hasBase -> local
        isDefault(local) && !isDefault(remote) -> remote
        else -> local
    }
}
