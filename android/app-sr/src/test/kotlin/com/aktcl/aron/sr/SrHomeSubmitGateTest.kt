package com.aktcl.aron.sr

import com.aktcl.aron.feature.attendance.AttendanceState
import com.aktcl.aron.feature.home.HomeDayState
import com.aktcl.aron.feature.memo.domain.KpiStrip
import org.junit.Assert.assertEquals
import org.junit.Test

/** D-NIGHT-CHECKOUT-01 checker: Home keeps its 17:00 Submit prompt although check-out now waits for the Sales Submit. */
class SrHomeSubmitGateTest {
    private val strip = KpiStrip(plannedOutlets = 10, visitedOutlets = 4, strikeRateHundredths = null, nonVisitOutlets = 6, noSaleOutlets = 0, categories = emptyList())
    private val after17 = AttendanceState(checkedInAt = "2026-10-07T03:00:00.000Z", checkOutEnabled = false, needsSalesSubmit = true, checkOutTimeReached = true)

    @Test fun after17TheHomeAsksForTheSubmitThenForTheCheckOut() {
        assertEquals(HomeDayState.READY_TO_SUBMIT, SrHomeReads.dayState(strip, after17, stockIssued = true, submitted = false))
        assertEquals(HomeDayState.READY_TO_CHECK_OUT, SrHomeReads.dayState(strip, after17, stockIssued = true, submitted = true))
    }
}
