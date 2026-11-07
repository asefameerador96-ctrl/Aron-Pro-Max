package com.aktcl.aron.feature.attendance

import org.junit.Assert.assertEquals
import org.junit.Test

/** The hour of the configured check-out time on the 12-hour clock for the dashed orange box (screenshot 15: "চেক আউট ৫টার পরে সক্রিয় হবে"). */
class CheckoutAfterLabelTest {
    @Test fun banglaGetsTheBareHourAndEnglishAMeridiem() {
        assertEquals("5", checkoutAfterLabel("17:00", bangla = true))
        assertEquals("5 PM", checkoutAfterLabel("17:00", bangla = false))
        assertEquals("5:30", checkoutAfterLabel("17:30", bangla = true))
        assertEquals("5:30 PM", checkoutAfterLabel("17:30", bangla = false))
    }

    @Test fun noonMidnightAndMorningAreRight() {
        assertEquals("12 PM", checkoutAfterLabel("12:00", bangla = false))
        assertEquals("12 AM", checkoutAfterLabel("00:00", bangla = false))
        assertEquals("9 AM", checkoutAfterLabel("09:00", bangla = false))
        assertEquals("12", checkoutAfterLabel("12:00", bangla = true))
    }

    @Test fun badInputComesBackUnchanged() {
        assertEquals("soon", checkoutAfterLabel("soon", bangla = true))
    }
}
