package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Test

/** The picker's range area (rows 20-22, D-AMO-CALLS-01/02): no stale verdict, no dead end, a restored call shows its tiles. */
class CallRangeRuleTest {
    private fun r(outlet: Long? = 5, open: Boolean = false, checking: Boolean = false, needs: Pair<Boolean, Boolean>? = null, sales: Boolean = false, checked: Long? = null) =
        CallRangeRule.of(outlet, open, checking, needs, sales, checked)

    @Test fun noRetailerIsIdle() = assertEquals(CallRange.Idle, r(outlet = null, open = true, sales = true))
    @Test fun anOpenCallShowsTheTilesAlsoAfterARelaunch() = assertEquals(CallRange.In, r(open = true, checking = true, needs = true to true))
    @Test fun readingTheFixIsChecking() = assertEquals(CallRange.Checking, r(checking = true))
    @Test fun outOfRangeCarriesOverrideAndRefresh() {
        assertEquals(CallRange.Out(true, false), r(needs = true to false))
        assertEquals(CallRange.Out(false, true), r(needs = false to true))
    }
    @Test fun theSalesTileHasAskedNothingUntilTappedSoItStaysIn() {
        assertEquals(CallRange.In, r(sales = true, checked = null))
        assertEquals(CallRange.In, r(sales = true, checked = 9)) // another retailer was answered: the stale verdict is not this one's
        assertEquals(CallRange.Idle, r(sales = true, checked = 5)) // answered, nothing pending, no open call (a non-standard state has its own card)
    }
    @Test fun controlCallWithNothingPendingIsIdle() = assertEquals(CallRange.Idle, r(sales = false, checked = null))
}
