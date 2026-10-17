package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExceptionsTest {
    private fun sig(id: Long, code: String, sev: Int, date: String = "2026-10-17", status: String = "open", score: Int = 10) =
        """{"signal_id":$id,"code":"$code","severity":$sev,"business_date":"$date","subject_type":"visit","subject_id":"v$id","user_id":5,"score":$score,"evidence":{"distance_m":820,"speeds":[1,2]},"status":"$status","config_version":1,"created_at":"2026-10-17T05:00:00Z"}"""

    private fun sup(vararg s: String) = """{"risk_signals":[${s.joinToString(",")}]}"""

    @Test fun listsTodaysOpenSignalsWorstFirst() {
        val j = sup(sig(1, "GEO_MOCK", 2), sig(2, "GEO_TELEPORT", 4), sig(3, "GEO_MOCK", 2, score = 50), sig(4, "GEO_MOCK", 3, date = "2026-10-16"), sig(5, "CLOCK_SKEW", 3, status = "reviewed"))
        assertEquals(listOf(2L, 3L, 1L), Exceptions.open(j, "2026-10-17").map { it.id })
    }

    @Test fun aReviewedSignalLeavesAtOnceOffline() {
        val j = sup(sig(1, "GEO_MOCK", 2), sig(2, "GEO_TELEPORT", 4))
        assertEquals(listOf(2L), Exceptions.open(j, "2026-10-17", setOf(1L)).map { it.id })
    }

    @Test fun unknownCodeIsKeptAndEvidenceIsFlattened() {
        val r = Exceptions.open(sup(sig(9, "BRAND_NEW_CODE", 1)), "2026-10-17").single()
        assertEquals("BRAND_NEW_CODE", r.code); assertEquals(ExceptionGroup.OTHER, Exceptions.group(r.code))
        assertEquals(listOf("distance_m" to "820", "speeds" to "1, 2"), r.evidence)
        assertEquals(ExceptionGroup.GEO, Exceptions.group("GEO_MOCK")); assertEquals(ExceptionGroup.DEVICE, Exceptions.group("DEVICE_NOT_OWNER"))
    }

    @Test fun malformedOrMissingIsEmpty() {
        assertTrue(Exceptions.open(null, "d").isEmpty()); assertTrue(Exceptions.open("[", "d").isEmpty())
        assertTrue(Exceptions.open("""{"risk_signals":[{"code":"x"}]}""", "d").isEmpty())
    }
}
