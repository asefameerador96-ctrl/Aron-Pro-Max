package com.aktcl.aron.feature.attendance

import org.junit.Assert.assertEquals
import org.junit.Test

/** The hour of the configured check-out time on the 12-hour clock for the dashed orange box (screenshot 15: "চেক আউট ৫টার পরে সক্রিয় হবে"). */
class CheckoutAfterLabelTest {
    private val words = { p: PeriodOfDay -> when (p) { PeriodOfDay.MORNING -> "M"; PeriodOfDay.NOON -> "N"; PeriodOfDay.EVENING -> "E"; PeriodOfDay.NIGHT -> "X" } }

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
        assertEquals("N 12", checkoutAfterLabel("12:00", bangla = true, words))
        assertEquals("X 12", checkoutAfterLabel("00:00", bangla = true, words))
        assertEquals("M 9", checkoutAfterLabel("09:00", bangla = true, words))
        assertEquals("X 10:30", checkoutAfterLabel("22:30", bangla = true, words))
        assertEquals("1", checkoutAfterLabel("13:00", bangla = true, words))
        // 17:00 stays the bare "5" of screenshot 12; from 18:00 the bare hour could read as morning, so evening and night get a word
        assertEquals("5", checkoutAfterLabel("17:00", bangla = true, words))
        assertEquals("5:59", checkoutAfterLabel("17:59", bangla = true, words))
        assertEquals("E 6", checkoutAfterLabel("18:00", bangla = true, words))
        assertEquals("E 7:30", checkoutAfterLabel("19:30", bangla = true, words))
        assertEquals("X 8", checkoutAfterLabel("20:00", bangla = true, words))
        assertEquals("X 9:59", checkoutAfterLabel("21:59", bangla = true, words))
        assertEquals("noon and midnight differ in Bangla", true, checkoutAfterLabel("12:00", bangla = true, words) != checkoutAfterLabel("00:00", bangla = true, words))
    }

    @Test fun badInputComesBackUnchanged() {
        assertEquals("soon", checkoutAfterLabel("soon", bangla = true))
        // out of range is bad input too, not "1 PM" for 25:00
        assertEquals("25:00", checkoutAfterLabel("25:00", bangla = false))
        assertEquals("17:75", checkoutAfterLabel("17:75", bangla = true))
    }
}
