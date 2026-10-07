package com.example.ApI.data.sync.merge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class JsonMergerTest {

    private fun j(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun merge(base: String?, local: String, remote: String, policy: JsonMergePolicy = JsonMergePolicy()) =
        JsonMerger.merge(base?.let(::j), j(local), j(remote), policy)

    @Test
    fun `objects merge per key three way`() {
        val m = merge(
            """{"a":1,"b":1,"c":1,"d":1}""",
            """{"a":2,"b":1,"c":3,"d":1,"l":1}""",
            """{"a":1,"b":2,"c":4,"r":1}"""
        )
        // a: local changed, b: remote changed, c: both → local, d: deleted remotely, l/r added
        assertEquals(j("""{"a":2,"b":2,"c":3,"l":1,"r":1}"""), m)
    }

    @Test
    fun `modification beats deletion`() {
        assertEquals(j("""{"x":{"v":2}}"""), merge("""{"x":{"v":1}}""", """{"x":{"v":2}}""", """{}"""))
    }

    @Test
    fun `no base unions and resolves scalar conflicts to remote`() {
        assertEquals(j("""{"a":2,"l":1,"r":1}"""), merge(null, """{"a":1,"l":1}""", """{"a":2,"r":1}"""))
    }

    @Test
    fun `keyed arrays merge by id`() {
        val m = merge(
            """[{"id":"1","v":1},{"id":"2","v":1},{"id":"3","v":1}]""",
            """[{"id":"1","v":2},{"id":"2","v":1},{"id":"3","v":1},{"id":"L"}]""",
            """[{"id":"1","v":1},{"id":"3","v":3},{"id":"R"}]"""
        )
        assertEquals(j("""[{"id":"1","v":2},{"id":"3","v":3},{"id":"L"},{"id":"R"}]"""), m)
    }

    @Test
    fun `remote reorder survives when local did not reorder`() {
        val m = merge("""[{"id":"1"},{"id":"2"}]""", """[{"id":"1"},{"id":"2"}]""", """[{"id":"2"},{"id":"1"}]""")
        assertEquals(j("""[{"id":"2"},{"id":"1"}]"""), m)
    }

    @Test
    fun `arrays without identity are scalars unless sets are enabled`() {
        assertEquals(j("""["a","l"]"""), merge("""["a"]""", """["a","l"]""", """["a","r"]"""))
        val sets = JsonMergePolicy(unkeyedArraysAsSets = true)
        assertEquals(j("""["l","r"]"""), merge("""["a"]""", """["a","l"]""", """["r"]""", sets))
    }

    @Test
    fun `device local keys are never adopted`() {
        val policy = JsonMergePolicy(deviceLocalKeys = setOf("remoteSync", "current_user"))
        val m = merge(
            """{"current_user":"x","remoteSync":{"t":"base"},"v":1}""",
            """{"current_user":"me","remoteSync":{"t":"mine"},"v":1}""",
            """{"current_user":"other","remoteSync":{"t":"theirs"},"v":2,"new":true}""",
            policy
        )
        assertEquals(j("""{"current_user":"me","remoteSync":{"t":"mine"},"v":2,"new":true}"""), m)
        // absent locally → stays absent
        assertEquals(j("""{"v":1}"""), merge(null, """{"v":1}""", """{"v":1,"remoteSync":{}}""", policy))
    }

    @Test
    fun `atomic paths are merged as one value`() {
        val policy = JsonMergePolicy(atomicPaths = setOf(""))
        val m = merge("""{"token":"b","user":"b"}""", """{"token":"l","user":"b"}""", """{"token":"r","user":"r"}""", policy)
        assertEquals(j("""{"token":"l","user":"b"}"""), m)
    }

    @Test
    fun `identity properties`() {
        val b = """{"a":[{"id":"1","v":1}],"s":"x"}"""
        val l = """{"a":[{"id":"1","v":2},{"id":"2"}],"s":"x"}"""
        val r = """{"a":[],"s":"y"}"""
        assertEquals(j(l), merge(b, l, b))
        assertEquals(j(r), merge(b, b, r))
        assertEquals(j(l), merge(b, l, l))
    }
}
