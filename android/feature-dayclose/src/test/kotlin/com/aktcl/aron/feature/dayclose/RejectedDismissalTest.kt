package com.aktcl.aron.feature.dayclose

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** D-SRFIX-04: old refused non-money records leave the "Not accepted as sent" list; a sale never does. */
class RejectedDismissalTest {
    private val today = "2026-11-04"
    private fun row(type: String, date: String, outcome: RejectedOutcome = RejectedOutcome.DROPPED, mine: Boolean = true) =
        RejectedRow("u-$type-$date", type, date, "schema_invalid", outcome, fromThisPhone = mine)

    @Test fun anOldDroppedMediaRecordIsDismissed() {
        assertTrue(RejectedDismissal.isDismissed(row("media_meta", "2026-10-21"), today))
        assertTrue(RejectedDismissal.isDismissed(row("media_meta", "2026-10-27"), today)) // 8 days
    }

    @Test fun aRecentOneStaysListedForSevenDays() {
        assertFalse(RejectedDismissal.isDismissed(row("media_meta", "2026-10-28"), today)) // exactly 7 days
        assertFalse(RejectedDismissal.isDismissed(row("media_meta", "2026-11-04"), today))
    }

    @Test fun aSaleBearingOrMoneyRecordIsNeverDismissedHoweverOld() {
        listOf("memo", "memo_line", "memo_discount", "memo_void", "due_collection", "qc_line", "stock_movement", "day_submit", "visit", "visit_close")
            .forEach { assertFalse(it, RejectedDismissal.isDismissed(row(it, "2026-01-01"), today)) }
    }

    @Test fun onlyDroppedRowsFromThisPhoneAreDismissed() {
        assertFalse(RejectedDismissal.isDismissed(row("media_meta", "2026-10-01", RejectedOutcome.RETRY), today))
        assertFalse(RejectedDismissal.isDismissed(row("media_meta", "2026-10-01", RejectedOutcome.STORED_FLAGGED), today))
        assertFalse(RejectedDismissal.isDismissed(row("media_meta", "2026-10-01", mine = false), today))
    }

    @Test fun splitKeepsOrderAndAMalformedDateStaysListed() {
        val a = row("media_meta", "2026-10-21"); val b = row("memo", "2026-10-21"); val c = row("media_meta", "not-a-date")
        val (listed, dismissed) = RejectedDismissal.split(listOf(a, b, c), today)
        assertEquals(listOf(b, c), listed); assertEquals(listOf(a), dismissed)
    }
}
