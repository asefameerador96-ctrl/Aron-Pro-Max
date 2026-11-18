package com.aktcl.aron.core.sync

import com.aktcl.aron.core.database.record.RecordMapping
import com.aktcl.aron.core.geo.spoof.SpoofCode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The upload filter (`RecordMapping.SPOOF_CODES`, a hand-kept list) must be exactly the codes the phone can produce: a new `SpoofCode` is never silently dropped from uploads. */
class SpoofCodesTieTest {
    @Test fun theUploadListIsExactlyTheSpoofCodeWires() {
        assertEquals(SpoofCode.values().map { it.wire }.toSet(), RecordMapping.SPOOF_CODES)
    }

    /** The server refuses a fix with more than 16 codes (schema_invalid hits the whole visit or memo): the enum must never outgrow that. */
    @Test fun theCodeListCanNeverOutgrowTheIngestCapOf16() {
        org.junit.Assert.assertTrue("SpoofCode has " + SpoofCode.values().size + " codes; the server cap is 16 (GeoFix.signals maxItems, PhoneEvidenceShape.MAX_SIGNALS)", SpoofCode.values().size <= 16)
    }

    @Test fun everyCodeIsAtMost32CharactersAndUnique() {
        val wires = SpoofCode.values().map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
        assertEquals("the server stores only codes of 1..32 characters", emptyList<String>(), wires.filter { it.length !in 1..32 })
    }
}
