package com.aktcl.aron.backend.sync

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RFC 8785 vectors (appendix B numbers, s3.2.2 strings and ordering) and the payload-shape drift guard. */
class JcsTest {
    private fun c(s: String) = Jcs.canonicalize(Json.parseToJsonElement(s))

    @Test
    fun numbersFollowEcmaScript() {
        val cases = mapOf(
            "0" to "0", "-0" to "0", "-0.0" to "0", "1" to "1", "1.0" to "1", "4.50" to "4.5", "2e-3" to "0.002", "1E30" to "1e+30",
            "1e21" to "1e+21", "1e20" to "100000000000000000000", "0.000001" to "0.000001", "1e-7" to "1e-7",
            "333333333.33333329" to "333333333.3333333", "0.000000000000000000000000001" to "1e-27", "-1e-7" to "-1e-7",
            "9007199254740991" to "9007199254740991", "9007199254740993" to "9007199254740992", "295147905179352830000" to "295147905179352830000",
            "1.7976931348623157e308" to "1.7976931348623157e+308", "5e-324" to "5e-324", "123456789.0" to "123456789", "23.792512" to "23.792512",
        )
        cases.forEach { (lit, want) -> assertEquals(want, Jcs.number(lit), lit) }
    }

    @Test
    fun membersSortByUtf16AndStringsUseMinimalEscapes() {
        assertEquals("""{"a":1,"b":[true,null,"x"]}""", c("""{ "b" : [true, null, "x"], "a" : 1 }"""))
        // RFC 8785 s3.2.3 ordering example: "\r" < "1" < "\u0080" < "ö" < "€" < "😀" < "\ufb33".
        assertEquals("[\"\\r\",\"1\",\"\u0080\",\"ö\",\"€\",\"😀\",\"\ufb33\"]",
            c("""{"\ufb33":0,"😀":0,"€":0,"ö":0,"\u0080":0,"1":0,"\r":0}""").let { s -> "[" + Regex("\"((?:[^\"\\\\]|\\\\.)*)\":0").findAll(s).joinToString(",") { "\"" + it.groupValues[1] + "\"" } + "]" })
        assertEquals("\"\\u0001\\b\\t\\n\\f\\r\\\"\\\\/ঢাকা\"", c("\"\\u0001\\b\\t\\n\\f\\r\\\"\\\\\\/ঢাকা\""))
    }

    @Test
    fun equalContentHashesEquallyWhateverTheLayout() {
        val a = Jcs.sha256(Json.parseToJsonElement("""{"x":1.50,"y":{"b":2,"a":[1,2]}}"""))
        val b = Jcs.sha256(Json.parseToJsonElement("""{ "y" : { "a" : [1, 2], "b" : 2.0 }, "x" : 1.5 }"""))
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun payloadShapesMatchTheContractSlices() {
        val root = File(System.getProperty("aron.repoRoot"))
        val p = ProcessBuilder("python3", "backend/sync/tools/gen_payload_shapes.py", "--check").directory(root).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), "PayloadShapes.kt is stale: run python3 backend/sync/tools/gen_payload_shapes.py ($out)")
        assertEquals(42, PayloadShapes.BY_TYPE.size)
        assertEquals(PayloadShapes.BY_TYPE.keys, TypeRules.BY_TYPE.keys, "every contract record type has an ingest rule")
    }
}
