package com.example.ApI.data.sync.merge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals

/** Seeded random 3-way JSON merges: identity properties, device-local keys, never throws. */
class JsonMergeFuzzReviewTest {

    private fun prim(rnd: Random): JsonElement = when (rnd.nextInt(4)) {
        0 -> JsonPrimitive(rnd.nextInt(3))
        1 -> JsonPrimitive(rnd.nextBoolean())
        2 -> JsonPrimitive("s${rnd.nextInt(3)}")
        else -> JsonPrimitive("t${rnd.nextInt(3)}")
    }

    private fun gen(rnd: Random, depth: Int): JsonElement = when {
        depth <= 0 -> prim(rnd)
        else -> when (rnd.nextInt(4)) {
            0 -> prim(rnd)
            1 -> JsonObject((0 until rnd.nextInt(4)).associate { "k$it" to gen(rnd, depth - 1) })
            2 -> JsonArray((0 until rnd.nextInt(4)).map { i ->
                JsonObject(mapOf("id" to JsonPrimitive("i$i"), "v" to gen(rnd, depth - 1)))
            })
            else -> JsonArray((0 until rnd.nextInt(4)).map { prim(rnd) })
        }
    }

    private fun mutate(rnd: Random, e: JsonElement, depth: Int): JsonElement = when (e) {
        is JsonObject -> {
            val m = LinkedHashMap(e)
            when (rnd.nextInt(4)) {
                0 -> m["k${rnd.nextInt(5)}"] = gen(rnd, depth)
                1 -> m.keys.randomOrNull(rnd)?.let { m.remove(it) }
                else -> m.keys.randomOrNull(rnd)?.let { m[it] = mutate(rnd, m.getValue(it), depth - 1) }
            }
            JsonObject(m)
        }
        is JsonArray -> {
            val l = e.toMutableList()
            when (rnd.nextInt(4)) {
                0 -> if (l.isNotEmpty() && l[0] is JsonObject) l.add(JsonObject(mapOf("id" to JsonPrimitive("n${rnd.nextInt(1000)}"), "v" to prim(rnd)))) else l.add(prim(rnd))
                1 -> if (l.isNotEmpty()) l.removeAt(rnd.nextInt(l.size))
                2 -> if (l.size > 1) l.shuffle(rnd)
                else -> if (l.isNotEmpty()) { val i = rnd.nextInt(l.size); l[i] = mutate(rnd, l[i], depth - 1) }
            }
            JsonArray(l)
        }
        else -> if (rnd.nextBoolean()) prim(rnd) else gen(rnd, depth)
    }

    @Test
    fun `random json merges satisfy identity properties`() {
        val policies = listOf(
            JsonMergePolicy(),
            JsonMergePolicy(deviceLocalKeys = setOf("k0"), unkeyedArraysAsSets = true),
            SyncFileMerger.ATOMIC_POLICY
        )
        repeat(3000) { iter ->
            val rnd = Random(50_000 + iter)
            val base = JsonObject((0 until 4).associate { "k$it" to gen(rnd, 3) })
            val l = (0 until rnd.nextInt(1, 4)).fold(base as JsonElement) { acc, _ -> mutate(rnd, acc, 3) }
            val r = (0 until rnd.nextInt(1, 4)).fold(base as JsonElement) { acc, _ -> mutate(rnd, acc, 3) }
            for (p in policies) {
                val ctx = "seed=${50_000 + iter} policy=$p"
                val sets = p.unkeyedArraysAsSets
                if (!sets) {
                    assertEquals(l, JsonMerger.merge(base, l, base, p), "$ctx merge(B,L,B)")
                    if (p.deviceLocalKeys.isEmpty()) assertEquals(r, JsonMerger.merge(base, base, r, p), "$ctx merge(B,B,R)")
                }
                assertEquals(l, JsonMerger.merge(base, l, l, p).let { if (p.deviceLocalKeys.isEmpty() && !sets) it else l }, ctx)
                val m = JsonMerger.merge(base, l, r, p)
                assertEquals(m, JsonMerger.merge(base, l, r, p), "$ctx deterministic")
                if (p.deviceLocalKeys.isNotEmpty()) {
                    assertEquals((l as JsonObject)["k0"], (m as JsonObject)["k0"], "$ctx device-local key")
                }
                JsonMerger.merge(null, l, r, p)
            }
        }
    }
}
