package com.aktcl.aron.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-SYS-072: the phone's canonicaliser must hash exactly what the server's does (backend/sync JcsTest has the same
 * RFC 8785 vectors; a change on either side must change both).
 */
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
        cases.forEach { (lit, want) -> assertEquals(lit, want, Jcs.number(lit)) }
    }

    @Test
    fun membersSortByUtf16AndStringsUseMinimalEscapes() {
        assertEquals("""{"a":1,"b":[true,null,"x"]}""", c("""{ "b" : [true, null, "x"], "a" : 1 }"""))
        assertEquals("\"\\u0001\\b\\t\\n\\f\\r\\\"\\\\/ঢাকা\"", c("\"\\u0001\\b\\t\\n\\f\\r\\\"\\\\\\/ঢাকা\""))
        assertEquals("""{"\r":0,"1":0,"""+"\"\u0080\":0,\"ö\":0,\"€\":0,\"😀\":0,\"\ufb33\":0}",
            c("""{"\ufb33":0,"😀":0,"€":0,"ö":0,"\u0080":0,"1":0,"\r":0}"""))
    }

    @Test
    fun aBanglaDefaultLocaleChangesNothing() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("bn-BD"))
            assertEquals("\"\\u001f\"", c("\"\\u001f\""))
            assertEquals("23.792512", Jcs.number("23.792512"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun theRecordSignatureStringIgnoresLayoutAndTheSigMember() {
        val a = Json.parseToJsonElement("""{"type":"visit","client_uuid":"u1","payload":{"lat":23.79,"lng":90.41},"sig":null}""").jsonObject
        val b = Json.parseToJsonElement("""{ "payload" : { "lng" : 90.410, "lat" : 23.79 }, "client_uuid" : "u1", "type" : "visit" }""").jsonObject
        val s = ProofStrings.record("visit", "u1", a)
        assertEquals(s, ProofStrings.record("visit", "u1", b))
        val lines = s.split('\n')
        assertEquals(listOf("aron-sig-v1", "visit", "u1"), lines.take(3))
        assertEquals(ProofStrings.sha256Hex("""{"client_uuid":"u1","payload":{"lat":23.79,"lng":90.41},"type":"visit"}""".toByteArray()), lines[3])
        assertTrue(lines.size == 4)
    }
}
