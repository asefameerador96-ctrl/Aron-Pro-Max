package com.aktcl.aron.rules

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SkuProgressTest {
    private val friday = setOf(DayOfWeek.FRIDAY)
    private val none = emptySet<LocalDate>()

    /** Calendar numbers of [day] as the server derives them: before (1st..day-1), after (day+1..end), today. */
    private fun cal(day: LocalDate): SkuProgress.Calendar {
        val first = LocalDate(day.year, day.month, 1)
        val last = YearMonth(day.year, day.month).lastDay
        fun w(d: LocalDate) = WorkingDays.isWorking(d, friday, none, none)
        var before = 0; var after = 0
        var d = first
        while (d <= last) { if (w(d)) { if (d < day) before++ else if (d > day) after++ }; d = LocalDate.fromEpochDays(d.toEpochDays() + 1) }
        return SkuProgress.Calendar(before, after, w(day))
    }

    @Test fun calendarOf2026April18IsK13() {
        val c = cal(LocalDate(2026, 4, 18))
        assertEquals(14, c.elapsedBeforeToday); assertEquals(11, c.remainingAfterToday); assertEquals(true, c.todayIsWorking)
    }

    @Test fun k13FixtureFiveHundred() {
        val r = SkuProgress.row(500, 0, cal(LocalDate(2026, 4, 18)))
        assertEquals(36L, r.tads); assertEquals(45L, r.rads); assertEquals(0L, r.ads); assertEquals(0L, r.pads)
        assertEquals(500L, r.remaining); assertEquals(0L, r.achievementBp)
    }

    @Test fun k13FixtureNineHundred() {
        val r = SkuProgress.row(900, 0, cal(LocalDate(2026, 4, 18)))
        assertEquals(64L, r.tads); assertEquals(82L, r.rads); assertEquals(900L, r.remaining)
    }

    @Test fun screen62ConventionCountsToday() {
        // Screen 62: 2026-11-11, EWD 10, WD 26 (Friday off).
        val c = cal(LocalDate(2026, 11, 11))
        assertEquals(9, c.elapsedBeforeToday)
        assertEquals(10, SkuProgress.dayCounts(c, SkuProgress.DayConvention.THROUGH_TODAY).elapsed)
        assertEquals(9, SkuProgress.dayCounts(c, SkuProgress.DayConvention.BEFORE_TODAY).elapsed)
        assertEquals(26, WorkingDays.month(YearMonth(2026, 11), friday, none, none))
    }

    @Test fun nonWorkingTodayDoesNotAddADay() {
        val c = cal(LocalDate(2026, 11, 13)) // a Friday
        assertEquals(false, c.todayIsWorking)
        assertEquals(c.elapsedBeforeToday, SkuProgress.dayCounts(c, SkuProgress.DayConvention.THROUGH_TODAY).elapsed)
    }

    @Test fun achievementIsNotCappedAndRemainingStopsAtZero() {
        val r = SkuProgress.row(100, 250, SkuProgress.Calendar(10, 10, true))
        assertEquals(25_000L, r.achievementBp); assertEquals(0L, r.remaining); assertEquals(0L, r.rads)
    }

    @Test fun zeroDaysAndZeroTargetAreNullNeverZero() {
        val r = SkuProgress.row(0, 5, SkuProgress.Calendar(0, 0, true))
        assertNull(r.achievementBp); assertNull(r.ads); assertNull(r.tads); assertNull(r.rads); assertNull(r.pads)
    }

    @Test fun halfRoundsUp() {
        assertEquals(1L, SkuProgress.ratio(1, 2)); assertEquals(0L, SkuProgress.ratio(1, 3)); assertEquals(2L, SkuProgress.ratio(3, 2))
        assertEquals(36L, SkuProgress.ratio(500, 14)); assertEquals(45L, SkuProgress.ratio(500, 11))
    }

    @Test fun negativeInputIsACallerBug() {
        assertFailsWith<IllegalArgumentException> { SkuProgress.ratio(-1, 2) }
    }
}
