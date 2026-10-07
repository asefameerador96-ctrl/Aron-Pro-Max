package com.aktcl.aron.feature.dayclose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesSubmitTest {
    private val clean = OutboxStates(0, 0, 40, 0, 0)
    private val ok = listOf(CountRow("memo", 6, 6), CountRow("visit", 6, 6))
    private val noDues = DuesAtSubmit(0, 0)

    @Test fun enabledWhenEverythingIsAckedAndCountsMatch() {
        val g = SalesSubmitRules.gate(clean, ok, noDues, false)
        assertTrue(g.enabled); assertNull(g.duesWarning)
    }

    @Test fun duesWarnButNeverBlock() {
        val g = SalesSubmitRules.gate(clean, ok, DuesAtSubmit(2, 125_000), false)
        assertTrue(g.enabled); assertEquals(2, g.duesWarning!!.retailersWithDues)
    }

    @Test fun pendingInFlightRejectedAndQuarantinedBlock() {
        assertFalse(SalesSubmitRules.gate(clean.copy(pending = 1), ok, noDues, false).enabled)
        assertFalse(SalesSubmitRules.gate(clean.copy(inFlight = 2), ok, noDues, false).enabled)
        assertTrue(SalesSubmitRules.gate(clean.copy(rejected = 1), ok, noDues, false).blocks.single() is SubmitBlock.Rejected)
        assertTrue(SalesSubmitRules.gate(clean.copy(quarantined = 1), ok, noDues, false).blocks.single() is SubmitBlock.Quarantined)
    }

    @Test fun aBlankServerColumnBeforeTheFirstSyncBlocksAndAMismatchNamesTheType() {
        val g = SalesSubmitRules.gate(clean, listOf(CountRow("memo", 6, null), CountRow("visit", 6, 5)), noDues, false)
        assertFalse(g.enabled)
        assertEquals(listOf("memo", "visit"), g.blocks.map { (it as SubmitBlock.CountMismatch).recordType })
        assertFalse(CountRow("memo", 6, null).matches)
    }

    @Test fun aSubmittedDayCannotBeSubmittedTwice() {
        assertEquals(listOf(SubmitBlock.AlreadySubmitted), SalesSubmitRules.gate(clean, ok, noDues, true).blocks)
    }

    @Test fun syncButtonRetriesOnlyWhenOnlineAndIdle() {
        assertTrue(SalesSubmitRules.syncButtonEnabled(true, false)); assertFalse(SalesSubmitRules.syncButtonEnabled(false, false)); assertFalse(SalesSubmitRules.syncButtonEnabled(true, true))
    }
}
