package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.AsthaQuarters
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone names quarters ONLY through the shared function (the server's algorithm): the vectors for start months 1, 4, 7 and 10 across year boundaries, through every phone entry point. */
class QuarterParityTest {
    private val vectors = listOf(
        // start month, business date, key, months
        Triple(1, "2026-11-18", "2026-Q4" to listOf("2026-10", "2026-11", "2026-12")),
        Triple(1, "2026-01-01", "2026-Q1" to listOf("2026-01", "2026-02", "2026-03")),
        Triple(4, "2026-11-18", "2026-Q3" to listOf("2026-10", "2026-11", "2026-12")),
        Triple(4, "2027-01-05", "2027-Q4" to listOf("2027-01", "2027-02", "2027-03")),
        Triple(7, "2026-11-18", "2026-Q2" to listOf("2026-10", "2026-11", "2026-12")),
        Triple(7, "2027-02-10", "2027-Q3" to listOf("2027-01", "2027-02", "2027-03")),
        Triple(10, "2026-12-31", "2026-Q1" to listOf("2026-10", "2026-11", "2026-12")),
        Triple(10, "2027-01-01", "2027-Q2" to listOf("2027-01", "2027-02", "2027-03")),
    )

    @Test fun everyPhoneEntryPointAgreesWithTheSharedFunction() {
        for ((sm, date, expected) in vectors) {
            val (key, months) = expected
            assertEquals("$date start $sm", key, GiftChoiceModel.quarterOf(date, sm))
            val year = key.take(4).toInt(); val n = key.last().digitToInt()
            assertEquals(year, AsthaModel.quarterYearOf(date.take(7), sm)); assertEquals(n, AsthaModel.quarterOf(date.take(7), sm))
            assertEquals(months, AsthaModel.monthsOfQuarter(year, n, sm))
            assertEquals(key, AsthaModel.quarterKey(year, n))
            assertEquals(AsthaQuarters.of(date, sm), AsthaQuarters.parse(key, sm)?.let { AsthaQuarters.of(it.months.first(), sm) }) // the key names the quarter the date is in
        }
    }

    @Test fun theKeyYearIsTheYearOfTheFirstMonthNotAFiscalYear() {
        assertEquals(listOf("2026-04", "2026-05", "2026-06"), AsthaModel.monthsOfQuarter(2026, 4, 7))
        assertEquals("2027-Q3", GiftChoiceModel.quarterOf("2027-02-10", 7)) // the old phone rule said 2026-Q3
    }
}
