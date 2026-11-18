package com.aktcl.aron.core.database

import com.aktcl.aron.contract.GeoFix
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.record.RecordMapping
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract 1.21.0: the codes stored with a fix at capture go out as `GeoFix.signals`; an older row (NULL) leaves the member out, so its payload bytes are unchanged. */
class FixSignalsMappingTest {
    private fun entity(signals: String?) = GeoFixEntity(
        "f1", "o1", "visit_open", "ok", 23.79, 90.40, 8.0, provider = "fused", isMock = false, reused = false,
        deviceOwner = false, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false, signalsJson = signals,
    )
    private fun wire(f: GeoFix) = Json.parseToJsonElement(RecordMapping.json.encodeToString(GeoFix.serializer(), f)).jsonObject

    @Test fun storedCodesAreSentInOrderAndUnknownOnesAreLeftOut() {
        val f = RecordMapping.fix(entity("""["mock_app_selected","jump","brand_new","jump"]"""))
        assertEquals(listOf("mock_app_selected", "jump"), f.signals)
        assertEquals(listOf("mock_app_selected", "jump"), wire(f)["signals"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun anOlderRowSendsNoSignalsMemberAtAll() {
        val f = RecordMapping.fix(entity(null))
        assertNull(f.signals); assertFalse("signals" in wire(f))
    }

    @Test fun aBrokenStoredValueNeverFailsTheRecord() {
        assertNull(RecordMapping.fix(entity("not json")).signals)
        assertTrue(RecordMapping.fix(entity("[]")).signals!!.isEmpty())
    }

    @Test fun nothingElseOnTheFixChanged() {
        val without = wire(RecordMapping.fix(entity(null))).toMutableMap()
        val with = wire(RecordMapping.fix(entity("""["jump"]"""))).toMutableMap(); with.remove("signals")
        assertEquals(without, with)
    }
}
