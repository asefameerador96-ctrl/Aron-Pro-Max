package com.aktcl.aron.backend.sync

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** F-SYS-090 (D-584): the backdate window in working days, with its calendar ceiling. Friday is the weekend here. */
class WorkingDaysTest {
    private fun working(off: Set<LocalDate> = emptySet()): (LocalDate) -> Boolean = { d -> d.dayOfWeek != DayOfWeek.FRIDAY && d !in off }

    @Test
    fun theCalendarUnitIsTodayMinusTheDays() =
        assertEquals(LocalDate.parse("2027-01-03"), WorkingDays.floor(LocalDate.parse("2027-01-10"), 7, WorkingDays.CALENDAR, 15, working()))

    @Test
    fun aWeekendDayIsNotCounted() {
        // Sunday 2027-01-10 back 7 working days: Sat 9, Thu 7, Wed 6, Tue 5, Mon 4, Sun 3, Sat 2 (Fri 8 skipped).
        assertEquals(LocalDate.parse("2027-01-02"), WorkingDays.floor(LocalDate.parse("2027-01-10"), 7, WorkingDays.WORKING_DAYS, 15, working()))
    }

    @Test
    fun afterANineDayBreakTheLastWorkingEveningIsStillInside() {
        val today = LocalDate.parse("2027-01-20")
        val breakDays = (1L..9L).map { today.minusDays(it) }.toSet() // 11..19 off
        val floor = WorkingDays.floor(today, 7, WorkingDays.WORKING_DAYS, 15, working(breakDays))
        // Seven working days back from Wed 20 would reach Sun 3 (Fri 8 and the break skipped); the 15-day ceiling stops at
        // Tue 5. The last working day before the break (Sun 10, 10 calendar days old) is inside; the calendar rule refuses it.
        assertEquals(LocalDate.parse("2027-01-05"), floor)
    }

    @Test
    fun theCeilingBoundsAVeryLongBreak() {
        val today = LocalDate.parse("2027-03-01")
        val off = (1L..60L).map { today.minusDays(it) }.toSet()
        assertEquals(today.minusDays(15), WorkingDays.floor(today, 7, WorkingDays.WORKING_DAYS, 15, working(off)))
    }

    @Test
    fun neverNarrowerThanTheCalendarRule() {
        // Every day working: 7 working days = 7 calendar days.
        val today = LocalDate.parse("2027-01-10")
        assertEquals(today.minusDays(7), WorkingDays.floor(today, 7, WorkingDays.WORKING_DAYS, 15) { true })
    }

    @Test
    fun theCeilingFollowsTheRegistryRetention() {
        assertEquals(15, WorkingDays.ceiling(45, 7))
        assertEquals(7, WorkingDays.ceiling(30, 7))
    }
}
