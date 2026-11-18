package com.aktcl.aron.backend.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Contract 1.19.0 phone evidence (`GeoFix.signals`, `integrity_events`): a malformed value is refused at ingest, an unknown CODE is kept as an opaque bounded string. */
class PhoneEvidenceShapeTest {
    private val status = PayloadShapes.BY_TYPE.getValue("device_status")
    private val visit = PayloadShapes.BY_TYPE.getValue("visit")
    private fun ev(json: String) = PhoneEvidenceShape.error("device_status", status, Json.parseToJsonElement("""{"integrity_events":$json}""").jsonObject)
    private fun sig(json: String) = PhoneEvidenceShape.error("visit", visit, Json.parseToJsonElement("""{"fix":{"signals":$json}}""").jsonObject)
    private val good = """{"at":"2027-01-03T04:00:00Z","flags":["dev_options","adb"],"exempt":"none","app_version":"1.0.9+9"}"""

    @Test fun aWellFormedListPassesAndTheMemberIsOptional() {
        assertNull(ev("[$good]")); assertNull(ev("[]")); assertNull(ev("null"))
        assertNull(PhoneEvidenceShape.error("device_status", status, Json.parseToJsonElement("{}").jsonObject))
        assertNull(ev("""[{"at":"2027-01-03T04:00:00.123Z","flags":[],"exempt":"lab"}]"""))
    }

    @Test fun aMalformedElementIsRefused() {
        for (bad in listOf(
            """{"x":1}""", """"x"""", "7", "null", "[]",
            """{"at":"2027-01-03T04:00:00Z","flags":[{"x":1}],"exempt":"none"}""",   // the lead's example: an object where a code belongs
            """{"at":"2027-01-03T04:00:00Z","flags":[["a"]],"exempt":"none"}""",
            """{"at":"2027-01-03T04:00:00Z","flags":[1],"exempt":"none"}""",
            """{"at":"2027-01-03T04:00:00Z","flags":"adb","exempt":"none"}""",
            """{"at":"2027-01-03T04:00:00Z","exempt":"none"}""", """{"at":"2027-01-03T04:00:00Z","flags":[]}""", """{"flags":[],"exempt":"none"}""",
            """{"at":5,"flags":[],"exempt":"none"}""", """{"at":"yesterday","flags":[],"exempt":"none"}""", """{"at":"2027-01-03T04:00:00Z","flags":[],"exempt":{"a":1}}""",
            """{"at":"2027-01-03T04:00:00Z","flags":[],"exempt":""}""", """{"at":"2027-01-03T04:00:00Z","flags":[""],"exempt":"none"}""",
            """{"at":"2027-01-03T04:00:00Z","flags":["${"x".repeat(33)}"],"exempt":"none"}""", """{"at":"2027-01-03T04:00:00Z","flags":[],"exempt":"none","extra":1}""",
            """{"at":"2027-01-03T04:00:00Z","flags":[],"exempt":"none","app_version":{"a":1}}""", """{"at":"2027-01-03T04:00:00Z","flags":[],"exempt":"none","app_version":"${"9".repeat(41)}"}""",
        )) assertNotNull(ev("[$bad]"), "must be refused: $bad")
        assertNotNull(ev("""{"at":"2027-01-03T04:00:00Z"}""")); assertNotNull(ev(""""text"""")); assertNotNull(ev("7"))
    }

    @Test fun theListAndTheFlagsAreBounded() {
        assertNull(ev("[" + List(50) { good }.joinToString(",") + "]"))
        assertNotNull(ev("[" + List(51) { good }.joinToString(",") + "]"))
        assertNull(ev("""[{"at":"2027-01-03T04:00:00Z","flags":${List(8) { "\"c$it\"" }},"exempt":"none"}]"""))
        assertNotNull(ev("""[{"at":"2027-01-03T04:00:00Z","flags":${List(9) { "\"c$it\"" }},"exempt":"none"}]"""))
    }

    @Test fun anUnknownCodeFromANewerPhoneIsKeptAsAnOpaqueString() {
        assertNull(ev("""[{"at":"2027-01-03T04:00:00Z","flags":["usb_tethering"],"exempt":"future_exemption"}]"""))
        assertNull(sig("""["mock_flag","a_code_from_a_newer_phone"]"""))
    }

    @Test fun fixSignalsMustBeABoundedListOfCodes() {
        assertNull(sig("[]")); assertNull(sig("null")); assertNull(sig("""["jump","speed"]"""))
        assertNull(PhoneEvidenceShape.error("visit", visit, Json.parseToJsonElement("""{"fix":{}}""").jsonObject), "absent means unknown, and is fine")
        for (bad in listOf("{}", "\"jump\"", "7", "[1]", "[{\"a\":1}]", "[[\"jump\"]]", "[null]", "[\"\"]", "[\"${"x".repeat(33)}\"]", "[" + List(17) { "\"c$it\"" }.joinToString(",") + "]"))
            assertNotNull(sig(bad), "must be refused: $bad")
        assertNull(sig("[" + List(16) { "\"c$it\"" }.joinToString(",") + "]"))
    }

    @Test fun everyFixBearingRecordShapeIsCovered() {
        val withSignals = PayloadShapes.BY_TYPE.filterValues { s -> s.nested.values.any { "signals" in it.allowed } }
        assertNotNull(withSignals["visit"]); assertNotNull(withSignals["memo"]); assertNotNull(withSignals["attendance_event"]); assertNotNull(withSignals["geo_breadcrumb"])
        for ((type, shape) in withSignals) {
            val key = shape.nested.entries.first { "signals" in it.value.allowed }.key
            val o = Json.parseToJsonElement("""{"$key":{"signals":{"a":1}}}""").jsonObject
            assertNotNull(PhoneEvidenceShape.error(type, shape, o), "$type.$key.signals is checked")
        }
        assertEquals(12, withSignals.size, "the 12 fix-bearing shapes (gift_choice joined with the master-data round)")
    }
}
