package com.example.ApI.data.sync.merge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-file policy for [JsonMerger].
 *
 * @param deviceLocalKeys top-level object keys that belong to this device: always taken from
 *   local (absent if local lacks them), never adopted from remote, never compared.
 * @param identityKeys candidate identity keys for arrays of objects, tried in order. A key is
 *   used only if every element of every side is an object holding a non-null primitive under
 *   it and the values are unique per side; such arrays are merged as keyed sets.
 * @param unkeyedArraysAsSets arrays without an identity key are merged as sets of whole
 *   values (3-way add/remove) instead of as one scalar.
 * @param atomicPaths dotted paths ("" = the whole document) whose values are merged as one
 *   scalar, e.g. credentials whose fields must stay consistent with each other.
 */
data class JsonMergePolicy(
    val deviceLocalKeys: Set<String> = emptySet(),
    val identityKeys: List<String> = listOf("id"),
    val unkeyedArraysAsSets: Boolean = false,
    val atomicPaths: Set<String> = emptySet()
)

/**
 * Generic 3-way JSON merge (SYNC_MERGE_PLAN.md §3).
 * - Objects: per key, recursively. A key present on one side only was added (absent from
 *   base → kept) or deleted on the other side (dropped unless this side changed it).
 * - Keyed arrays of objects: the same, per identity value.
 * - Other arrays and scalars: the side that changed wins; both changed → local.
 * - Without a base: objects/keyed arrays are unioned and scalar conflicts go to **remote**
 *   (the account's existing value beats a freshly signed-in device's defaults).
 */
object JsonMerger {

    fun merge(
        base: JsonElement?,
        local: JsonElement,
        remote: JsonElement,
        policy: JsonMergePolicy = JsonMergePolicy()
    ): JsonElement = Context(policy, noBase = base == null).value(base, local, remote, "")

    private class Context(val policy: JsonMergePolicy, val noBase: Boolean) {

        fun value(base: JsonElement?, local: JsonElement, remote: JsonElement, path: String): JsonElement {
            if (local == remote) return local
            if (path in policy.atomicPaths) return scalar(base, local, remote)
            if (local is JsonObject && remote is JsonObject) {
                return obj(base as? JsonObject, local, remote, path)
            }
            if (local is JsonArray && remote is JsonArray) {
                return array(base as? JsonArray, local, remote, path)
            }
            return scalar(base, local, remote)
        }

        fun scalar(base: JsonElement?, local: JsonElement, remote: JsonElement): JsonElement = when {
            local == remote -> local
            base != null && local == base -> remote
            base != null && remote == base -> local
            base == null && noBase -> remote
            else -> local
        }

        /** 3-way presence decision for an entry present on one side only. */
        fun keepOneSided(base: JsonElement?, value: JsonElement): Boolean = base == null || base != value

        fun obj(base: JsonObject?, local: JsonObject, remote: JsonObject, path: String): JsonObject {
            val out = LinkedHashMap<String, JsonElement>()
            for (key in local.keys + remote.keys) {
                if (path.isEmpty() && key in policy.deviceLocalKeys) {
                    local[key]?.let { out[key] = it }
                    continue
                }
                val l = local[key]
                val r = remote[key]
                val b = base?.get(key)
                val childPath = if (path.isEmpty()) key else "$path.$key"
                when {
                    l != null && r != null -> out[key] = value(b, l, r, childPath)
                    l != null -> if (keepOneSided(b, l)) out[key] = l
                    r != null -> if (keepOneSided(b, r)) out[key] = r
                }
            }
            return JsonObject(out)
        }

        fun array(base: JsonArray?, local: JsonArray, remote: JsonArray, path: String): JsonElement {
            val idKey = identityKey(listOfNotNull(base, local, remote))
            if (idKey != null) {
                fun ids(a: JsonArray) = a.map { (it as JsonObject).getValue(idKey) }
                return keyed(base, local, remote, path, idKey, ::ids)
            }
            if (policy.unkeyedArraysAsSets) return asSet(base, local, remote)
            return scalar(base, local, remote)
        }

        fun identityKey(arrays: List<JsonArray>): String? = policy.identityKeys.firstOrNull { key ->
            arrays.all { array ->
                val values = array.map { e -> ((e as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull } }
                values.all { it != null } && values.toSet().size == values.size
            }
        }

        fun keyed(
            base: JsonArray?,
            local: JsonArray,
            remote: JsonArray,
            path: String,
            idKey: String,
            ids: (JsonArray) -> List<JsonElement>
        ): JsonArray {
            fun index(a: JsonArray) = a.associateBy { (it as JsonObject).getValue(idKey) }
            val b = base?.let(::index)
            val l = index(local)
            val r = index(remote)
            val merged = LinkedHashMap<JsonElement, JsonElement>()
            for (id in l.keys + r.keys) {
                val lv = l[id]
                val rv = r[id]
                val bv = b?.get(id)
                when {
                    lv != null && rv != null -> merged[id] = value(bv, lv, rv, "$path[]")
                    lv != null -> if (keepOneSided(bv, lv)) merged[id] = lv
                    rv != null -> if (keepOneSided(bv, rv)) merged[id] = rv
                }
            }
            val order = MergeSupport.mergeOrder(base?.let(ids), ids(local), ids(remote), merged.keys)
            return JsonArray(order.map { merged.getValue(it) })
        }

        fun asSet(base: JsonArray?, local: JsonArray, remote: JsonArray): JsonArray {
            val b = base?.toSet()
            val l = local.toSet()
            val r = remote.toSet()
            val keep = HashSet<JsonElement>()
            for (e in l + r) {
                val both = e in l && e in r
                // One-sided element: added (not in base) → keep; in base → deleted on the other side
                if (both || b == null || e !in b) keep += e
            }
            return JsonArray(
                MergeSupport.mergeOrder(base?.distinct(), local.distinct(), remote.distinct(), keep)
            )
        }
    }
}
