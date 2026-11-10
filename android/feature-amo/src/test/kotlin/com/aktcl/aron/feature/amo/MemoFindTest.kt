package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoFindTest {
    private fun m(id: String, date: String, at: String, due: Long, sup: String? = null) = LocalDue(id, date, at, due, sup)

    @Test fun localDuesAfterTheServerFigureAreAddedAndOlderOnesAreNotCountedTwice() {
        val local = listOf(m("a", "2026-11-05", "2026-11-05T04:00:00Z", 5_000_000), m("b", "2026-11-06", "2026-11-06T05:00:00Z", 11_642_000))
        assertEquals(11_642_000L + 20_000_000L, MemoFind.outstandingMtk(20_000_000, "2026-11-05T10:00:00Z", local)) // a is already in the server figure
        assertEquals(20_000_000L + 5_000_000L + 11_642_000L, MemoFind.outstandingMtk(20_000_000, null, local)) // no as-of: nothing assumed in
        assertEquals(20_000_000L, MemoFind.outstandingMtk(20_000_000, "2026-11-06", local)) // a date-only as-of is the end of that day
    }

    @Test fun asOfTodayStillAddsAMemoMadeAfterTheFigureWasTaken() {
        val local = listOf(m("a", "2026-11-06", "2026-11-06T03:00:00Z", 4_000_000), m("b", "2026-11-06", "2026-11-06T09:00:00Z", 1_000_000))
        assertEquals(7_000_000L + 1_000_000L, MemoFind.outstandingMtk(7_000_000, "2026-11-06T04:00:00Z", local))
    }

    @Test fun anEditCountsOnceWhateverSideOfTheFigureItsOriginalIsOn() {
        val orig = m("o", "2026-11-06", "2026-11-06T03:00:00Z", 4_000_000)
        val edit = m("e", "2026-11-06", "2026-11-06T09:00:00Z", 3_000_000, "o")
        // the figure (04:00) holds the original: the edit adds only the difference (here a reduction)
        assertEquals(10_000_000L - 1_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-06T04:00:00Z", listOf(orig, edit)))
        // the figure is older than both: only the edit counts, the original is superseded
        assertEquals(10_000_000L + 3_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-06T01:00:00Z", listOf(orig, edit)))
        // no as-of: nothing netted, the edit's due is added (the unknown figure is taken as not holding it)
        assertEquals(10_000_000L + 3_000_000L, MemoFind.outstandingMtk(10_000_000, null, listOf(orig, edit)))
        // the figure is newer than the edit: both in, nothing added
        assertEquals(10_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-06T12:00:00Z", listOf(orig, edit)))
        // a chain of two edits: the latest version the figure holds is the one netted
        val edit2 = m("f", "2026-11-06", "2026-11-06T11:00:00Z", 2_000_000, "e")
        assertEquals(10_000_000L - 1_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-06T10:00:00Z", listOf(orig, edit, edit2))) // edit (09:00) is held: 2M - 3M
    }

    // lead gate M2: earlier-day phone memos, an edit of an earlier-day memo, and Mark Paid each count once
    @Test fun anEarlierDayMemoNotYetInTheFigureIsCounted() {
        val earlier = m("e1", "2026-11-04", "2026-11-04T05:00:00Z", 2_000_000)
        assertEquals(10_000_000L + 2_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-03T10:00:00Z", listOf(earlier)))
        assertEquals(10_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-05T10:00:00Z", listOf(earlier))) // already in the figure
    }

    @Test fun anEditOfAnEarlierDayMemoIsNettedAgainstTheOriginalTheFigureHolds() {
        val orig = m("o", "2026-11-04", "2026-11-04T05:00:00Z", 4_000_000)
        val edit = m("e", "2026-11-06", "2026-11-06T05:00:00Z", 3_000_000, "o")
        // the figure (11-05) holds the original: the edit adds only the difference, never its full due on top
        assertEquals(10_000_000L - 1_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-05T10:00:00Z", listOf(orig, edit)))
    }

    @Test fun markPaidReducesTheFigureOnceWhetherTheFigureHoldsTheMemoOrNot() {
        // memo held by the figure, collected after it: the due falls by the collection
        val held = m("h", "2026-11-05", "2026-11-05T05:00:00Z", 5_000_000).copy(collections = listOf("2026-11-06T05:00:00Z" to 2_000_000L))
        assertEquals(10_000_000L - 2_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-05T10:00:00Z", listOf(held)))
        // memo and collection both after the figure: net memo due
        val fresh = m("f", "2026-11-06", "2026-11-06T05:00:00Z", 5_000_000).copy(collections = listOf("2026-11-06T06:00:00Z" to 2_000_000L))
        assertEquals(10_000_000L + 3_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-05T10:00:00Z", listOf(fresh)))
        // memo and its collection both already in the figure: nothing to add or take off
        val both = m("b", "2026-11-05", "2026-11-05T05:00:00Z", 5_000_000).copy(collections = listOf("2026-11-05T06:00:00Z" to 2_000_000L))
        assertEquals(10_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-05T10:00:00Z", listOf(both)))
    }

    @Test fun aCreditIsNeverSubtractedAndTheTotalNeverGoesNegative() {
        assertEquals(10_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-01", listOf(m("c", "2026-11-06", "2026-11-06T05:00:00Z", -3_000_000))))
        assertEquals(0L, MemoFind.outstandingMtk(-5, null, emptyList()))
    }
}
