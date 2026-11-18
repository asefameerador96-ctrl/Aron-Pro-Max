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

    @Test fun everyCodeIsAtMost32CharactersAndUnique() {
        val wires = SpoofCode.values().map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
        assertEquals("the server stores only codes of 1..32 characters", emptyList<String>(), wires.filter { it.length !in 1..32 })
    }
}
