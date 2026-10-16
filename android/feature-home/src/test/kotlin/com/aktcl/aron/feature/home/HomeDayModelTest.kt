package com.aktcl.aron.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The next-step dock follows the day-state table of screens-sr s2, in the table's precedence. */
class HomeDayModelTest {
    private fun state(checkedIn: Boolean = true, checkedOut: Boolean = false, stock: Boolean = true, routeDone: Boolean = false, after17: Boolean = false, submitted: Boolean = false) =
        HomeDayModel.dayState(checkedIn, checkedOut, stock, routeDone, after17, submitted)

    @Test fun notCheckedInComesFirstWhateverElseHappened() {
        assertEquals(HomeDayState.NOT_CHECKED_IN, state(checkedIn = false, stock = true, routeDone = true, after17 = true))
        assertEquals(HomeDock.CHECK_IN, HomeDayModel.dock(HomeDayState.NOT_CHECKED_IN))
        assertEquals(HomeTile.ATTENDANCE, HomeDock.CHECK_IN.target)
    }

    @Test fun checkedInWithoutStockTakesStock() {
        assertEquals(HomeDayState.NO_STOCK, state(stock = false))
        assertEquals(HomeDock.TAKE_STOCK, HomeDayModel.dock(HomeDayState.NO_STOCK))
    }

    @Test fun inTheFieldStartsSelling() {
        assertEquals(HomeDayState.IN_FIELD, state())
        assertEquals(HomeDock.START_SELLING, HomeDayModel.dock(HomeDayState.IN_FIELD))
        assertEquals(HomeTile.SALE, HomeDock.START_SELLING.target)
    }

    @Test fun routeDoneOrAfterCheckoutTimeSubmits() {
        assertEquals(HomeDayState.READY_TO_SUBMIT, state(routeDone = true))
        assertEquals(HomeDayState.READY_TO_SUBMIT, state(after17 = true))
        assertEquals(HomeDayState.READY_TO_SUBMIT, state(after17 = true, stock = false)) // the time wins over the missing stock
        assertEquals(HomeDock.SUBMIT, HomeDayModel.dock(HomeDayState.READY_TO_SUBMIT))
    }

    @Test fun submittedAfterCheckoutTimeChecksOut() {
        assertEquals(HomeDayState.READY_TO_CHECK_OUT, state(submitted = true, after17 = true))
        assertEquals(HomeDock.CHECK_OUT, HomeDayModel.dock(HomeDayState.READY_TO_CHECK_OUT))
        assertEquals(HomeDayState.SUBMITTED, state(submitted = true, after17 = false))
        assertNull(HomeDayModel.dock(HomeDayState.SUBMITTED))
    }

    @Test fun completeDayHasNoDock() {
        assertEquals(HomeDayState.COMPLETE, state(submitted = true, checkedOut = true))
        assertNull(HomeDayModel.dock(HomeDayState.COMPLETE))
    }
}
