package com.aktcl.aron.rules

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Parity of [AsthaQuarters] with the server's `AsthaQuarter` (backend/platform AsthaQuarter.kt, copied below as the reference): same key, same months, for every month and every start month. */
class AsthaQuartersParityTest {
    private data class Ref(val label: String, val months: List<LocalDate>)

    // the server's algorithm, verbatim
    private fun refOf(date: LocalDate, startMonth: Int): Ref {
        val sm = startMonth.coerceIn(1, 12)
        val monthsSinceStart = Math.floorMod(date.monthValue - sm, 12)
        val first = date.withDayOfMonth(1).minusMonths((monthsSinceStart % 3).toLong())
        val qIndex = (Math.floorMod(first.monthValue - sm, 12)) / 3 + 1
        return Ref("${first.year}-Q$qIndex", (0L..2L).map { first.plusMonths(it) })
    }

    private fun refParse(label: String, startMonth: Int): Ref? {
        val m = Regex("^(\\d{4})-Q([1-4])$").matchEntire(label) ?: return null
        val monthNo = (startMonth.coerceIn(1, 12) - 1 + 3 * (m.groupValues[2].toInt() - 1)) % 12 + 1
        val first = LocalDate.of(m.groupValues[1].toInt(), monthNo, 1)
        return Ref(label, (0L..2L).map { first.plusMonths(it) })
    }

    private fun mk(d: LocalDate) = "%04d-%02d".format(java.util.Locale.ROOT, d.year, d.monthValue)

    @Test fun everyMonthOf2020To2035ForEveryStartMonthMatchesTheServer() {
        for (sm in 1..12) for (y in 2020..2035) for (m in 1..12) {
            val ref = refOf(LocalDate.of(y, m, 1), sm)
            val q = AsthaQuarters.of(y, m, sm)
            assertEquals(ref.label, q.key, "key of $y-$m start $sm")
            assertEquals(ref.months.map(::mk), q.months, "months of $y-$m start $sm")
            val parsed = AsthaQuarters.parse(ref.label, sm)!!
            val refP = refParse(ref.label, sm)!!
            assertEquals(refP.months.map(::mk), parsed.months, "parse ${ref.label} start $sm")
            assertEquals(q.months, parsed.months) // parse(of(x).key) names the same quarter
        }
    }

    @Test fun vectorsForStartMonths1_4_7_10AndYearBoundaries() {
        fun key(t: String, sm: Int) = AsthaQuarters.of(t, sm)!!.key
        assertEquals("2026-Q4", key("2026-11-18", 1)); assertEquals("2026-Q1", key("2026-01", 1)); assertEquals("2026-Q4", key("2026-12-31", 1))
        assertEquals("2026-Q3", key("2026-11-18", 4)); assertEquals("2027-Q4", key("2027-01-05", 4))
        assertEquals("2026-Q2", key("2026-11-18", 7)); assertEquals("2027-Q3", key("2027-02-10", 7)); assertEquals("2026-Q1", key("2026-07-01", 7)); assertEquals("2027-Q4", key("2027-05-31", 7))
        assertEquals("2026-Q1", key("2026-10-01", 10)); assertEquals("2026-Q1", key("2026-12-31", 10)); assertEquals("2027-Q2", key("2027-01-01", 10)); assertEquals("2026-Q4", key("2026-09-30", 10))
        assertEquals(listOf("2026-10", "2026-11", "2026-12"), AsthaQuarters.of("2026-11", 7)!!.months)
        assertEquals(listOf("2026-04", "2026-05", "2026-06"), AsthaQuarters.parse("2026-Q4", 7)!!.months) // the key year is the year of the FIRST month
        assertEquals(listOf("2026-12", "2027-01", "2027-02"), AsthaQuarters.parse("2026-Q4", 3)!!.months) // a quarter that runs over the new year (start month 3: Q4 is Dec to Feb)
        assertEquals(listOf("2026-11", "2026-12", "2027-01"), AsthaQuarters.of("2027-01", 11)!!.months)
        assertEquals("2026-Q1", key("2027-01", 11))
    }

    @Test fun malformedTextIsNullAndTheStartMonthIsClamped() {
        assertNull(AsthaQuarters.of("2026-13", 1)); assertNull(AsthaQuarters.of("26-11", 1)); assertNull(AsthaQuarters.parse("2026-Q5", 1)); assertNull(AsthaQuarters.parse("2026Q1", 1))
        assertEquals(AsthaQuarters.of(2026, 11, 12), AsthaQuarters.of(2026, 11, 99))
        assertEquals(AsthaQuarters.of(2026, 11, 1), AsthaQuarters.of(2026, 11, 0))
    }
}
