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

    @Test fun pendingAndInFlightBlockRejectedAndQuarantinedOnlyNote() {
        assertFalse(SalesSubmitRules.gate(clean.copy(pending = 1), ok, noDues, false).enabled)
        assertFalse(SalesSubmitRules.gate(clean.copy(inFlight = 2), ok, noDues, false).enabled)
        assertTrue(SalesSubmitRules.gate(clean.copy(rejected = 1), ok, noDues, false).let { it.enabled && it.notes.single() is SubmitBlock.Rejected })
        assertTrue(SalesSubmitRules.gate(clean.copy(quarantined = 1), ok, noDues, false).let { it.enabled && it.notes.single() is SubmitBlock.Quarantined })
    }

    @Test fun aBlankServerColumnBeforeTheFirstSyncBlocksAndAMismatchNamesTheType() {
        val g = SalesSubmitRules.gate(clean, listOf(CountRow("memo", 6, null), CountRow("visit", 6, 5)), noDues, false)
        assertFalse(g.enabled)
        assertEquals(listOf("memo", "visit"), g.blocks.map { (it as SubmitBlock.CountMismatch).recordType })
        assertFalse(CountRow("memo", 6, null).matches)
    }

    /** D-NIGHT-DAYCLOSE-01 (a), the owner's 10:46 screenshot: server above the phone is information, never "Not matching". */
    @Test fun theServerHoldingMoreThanThePhoneIsInSync() {
        val owner = listOf(CountRow("visit", 3, 9), CountRow("memo", 4, 10), CountRow("memo_line", 23, 29), CountRow("stock_movement", 4, 4))
        val g = SalesSubmitRules.gate(clean, owner, noDues, false)
        assertTrue(g.enabled)
        assertEquals(listOf(6, 6, 6, 0), owner.map { it.serverExtra })
        assertFalse(CountRow("memo", 5, 4).matches) // the phone above the server is the mismatch
        assertEquals(0, CountRow("memo", 5, 4).serverExtra)
        assertEquals(0, CountRow("memo", 5, null).serverExtra)
    }

    /** The adapter for contract 1.9 row 4: once the server reports this phone's own count, the rule compares with it. */
    @Test fun thePerDeviceCountWinsOverTheLoginTotalOnceItArrives() {
        assertFalse(CountRow("memo", 4, 10, serverThisDevice = 3).matches) // one of this phone's memos is missing on the server
        assertTrue(CountRow("memo", 4, 10, serverThisDevice = 4).matches)
        assertFalse(CountRow("memo", 4, 10, serverThisDevice = 0).matches) // a type the digest does not list is zero, not the login total
    }

    @Test fun aSubmittedDayCannotBeSubmittedTwice() {
        assertEquals(listOf(SubmitBlock.AlreadySubmitted), SalesSubmitRules.gate(clean, ok, noDues, true).blocks)
    }

    @Test fun syncButtonRetriesOnlyWhenOnlineAndIdle() {
        assertTrue(SalesSubmitRules.syncButtonEnabled(true, false)); assertFalse(SalesSubmitRules.syncButtonEnabled(false, false)); assertFalse(SalesSubmitRules.syncButtonEnabled(true, true))
    }
}
