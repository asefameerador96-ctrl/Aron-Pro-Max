package com.aktcl.aron.rules

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WorkingDays (build contract s4.1, s4.2 "Working days"; D-TSO-MAN-08, CR-6, T-12): October 2026 has 31 days and five
 * Fridays (2, 9, 16, 23, 30). Path holidays (global down to zone) and make-up days; the most specific declaration on the
 * node's path decides a date.
 */
class WorkingDaysTest {
    private val oct = YearMonth(2026, 10)
    private val fri = WorkingDays.DEFAULT_WEEKEND
    private val zonePath = NodePath(wingId = 1, divisionId = 2, territoryId = 3, zoneId = 4)
    private val territoryPath = NodePath(wingId = 1, divisionId = 2, territoryId = 3)
    private fun d(day: Int) = LocalDate(2026, 10, day) // date-pin-ok: the contract's October 2026 fixtures
    private val holiday21 = CalendarMark(d(21), CalendarLevel.GLOBAL, 0, DayKind.OFF)
    private val zoneHoliday12 = CalendarMark(d(12), CalendarLevel.ZONE, 4, DayKind.OFF)
    private val makeup30 = CalendarMark(d(30), CalendarLevel.GLOBAL, 0, DayKind.MAKEUP)

    private fun month(path: NodePath, vararg marks: CalendarMark) = WorkingDays.month(oct, WorkingDays.resolve(path, fri, marks.toList()))

    @Test
    fun october2026Fixtures() {
        assertEquals(26, WorkingDays.month(oct, fri, emptySet(), emptySet())) // 31 - 5 Fridays
        assertEquals(25, month(NodePath.GLOBAL, holiday21)) // global holiday on Wednesday 21
        assertEquals(24, month(zonePath, holiday21, zoneHoliday12)) // plus the zone's own holiday on Monday 12
        assertEquals(25, month(territoryPath, holiday21, zoneHoliday12)) // a zone holiday is not on the territory's path
        assertEquals(26, month(NodePath.GLOBAL, holiday21, makeup30)) // Friday 30 declared selling
    }

    @Test
    fun setFormMatchesTheContractSignature() {
        val off = setOf(d(21)); val makeup = setOf(d(30))
        assertEquals(26, WorkingDays.month(oct, fri, off, makeup))
        assertEquals(20, WorkingDays.elapsed(d(25), fri, off, makeup))
        assertTrue(WorkingDays.isWorking(d(30), fri, off, makeup))
        assertFalse(WorkingDays.isWorking(d(21), fri, off, makeup))
        assertFalse(WorkingDays.isWorking(d(23), fri, off, makeup))
    }

    @Test
    fun elapsedCountsFromTheFirstToTheDayIncluded() {
        val cal = WorkingDays.resolve(NodePath.GLOBAL, fri, listOf(holiday21))
        assertEquals(20, WorkingDays.elapsed(d(25), cal)) // 25 days - 4 Fridays - the 21st
        assertEquals(1, WorkingDays.elapsed(d(1), cal)) // Thursday 1st, a working day, counts
        assertEquals(1, WorkingDays.elapsed(d(2), cal)) // Friday 2nd adds nothing
        assertEquals(17, WorkingDays.elapsed(d(21), cal)) // the holiday itself is not counted
        assertEquals(25, WorkingDays.elapsed(d(31), cal)) // the last day: the month's count
        assertEquals(WorkingDays.month(oct, cal), WorkingDays.elapsed(d(31), cal))
    }

    @Test
    fun eachDateCountsOnce() {
        // A holiday on a Friday is not subtracted twice; a make-up day on an ordinary weekday adds nothing.
        assertEquals(26, month(NodePath.GLOBAL, CalendarMark(d(23), CalendarLevel.GLOBAL, 0, DayKind.OFF)))
        assertEquals(26, month(NodePath.GLOBAL, CalendarMark(d(22), CalendarLevel.GLOBAL, 0, DayKind.MAKEUP)))
        // Overlapping sets passed directly: a make-up day works (the rule of dw.v_calendar.is_working_day).
        assertEquals(26, WorkingDays.month(oct, fri, setOf(d(21)), setOf(d(21))))
    }

    @Test
    fun theMostSpecificDeclarationOnThePathDecides() {
        // A zone make-up day overrides a global holiday on the same date, for the zone only.
        val zoneMakeup21 = CalendarMark(d(21), CalendarLevel.ZONE, 4, DayKind.MAKEUP)
        assertEquals(26, month(zonePath, holiday21, zoneMakeup21))
        assertEquals(25, month(territoryPath, holiday21, zoneMakeup21))
        // A zone emergency off overrides a global make-up day (Saturday 24 made a selling day globally, off in zone 4).
        val makeup24 = CalendarMark(d(24), CalendarLevel.GLOBAL, 0, DayKind.MAKEUP)
        val zoneOff24 = CalendarMark(d(24), CalendarLevel.ZONE, 4, DayKind.OFF)
        val friSat = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
        // 31 - 5 Fridays - 5 Saturdays = 21, + the global make-up Saturday = 22; the zone's own off day takes it back.
        assertEquals(22, WorkingDays.month(oct, WorkingDays.resolve(NodePath.GLOBAL, friSat, listOf(makeup24, zoneOff24))))
        assertEquals(21, WorkingDays.month(oct, WorkingDays.resolve(zonePath, friSat, listOf(makeup24, zoneOff24))))
        // Wing, division and territory declarations apply to every zone below them, and only there.
        for (level in listOf(CalendarLevel.WING to 1L, CalendarLevel.DIVISION to 2L, CalendarLevel.TERRITORY to 3L)) {
            val mark = CalendarMark(d(13), level.first, level.second, DayKind.OFF)
            assertEquals(25, month(zonePath, mark), "${level.first} on the zone's path")
            assertEquals(25, month(territoryPath, mark), "${level.first} on the territory's path")
            assertEquals(26, month(NodePath.GLOBAL, mark), "${level.first} is not on the national path")
            assertEquals(26, month(NodePath(1, 2, 99, 98), CalendarMark(d(13), CalendarLevel.TERRITORY, 3, DayKind.OFF)))
        }
        // Another branch's declaration never applies.
        assertEquals(26, month(zonePath, CalendarMark(d(13), CalendarLevel.ZONE, 5, DayKind.OFF)))
    }

    @Test
    fun equalLevelContradictionIsAnOffDay() {
        // The database keeps one live declaration per date and scope, so this cannot come from one path; if it did, off wins.
        val cal = WorkingDays.resolve(NodePath.GLOBAL, fri, listOf(
            CalendarMark(d(21), CalendarLevel.GLOBAL, 0, DayKind.MAKEUP), CalendarMark(d(21), CalendarLevel.GLOBAL, 0, DayKind.OFF)))
        assertFalse(cal.isWorking(d(21)))
        assertEquals(setOf(d(21)), cal.off)
        assertTrue(cal.makeup.isEmpty())
    }

    @Test
    fun weekendFromConfig() {
        assertEquals(setOf(DayOfWeek.FRIDAY), WorkingDays.weekendOf(null))
        assertEquals(emptySet(), WorkingDays.weekendOf(emptyList()))
        assertEquals(setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), WorkingDays.weekendOf(listOf(5, 6)))
        assertEquals(setOf(DayOfWeek.MONDAY), WorkingDays.weekendOf(listOf(0, 1, 8)))
        assertEquals(listOf(5, 6), WorkingDays.isoOf(setOf(DayOfWeek.SATURDAY, DayOfWeek.FRIDAY)))
        // No weekend: every day of October works; Friday and Saturday off: 31 - 5 - 5.
        assertEquals(31, WorkingDays.month(oct, emptySet(), emptySet(), emptySet()))
        assertEquals(21, WorkingDays.month(oct, setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), emptySet(), emptySet()))
    }

    @Test
    fun wireCodesOfTheCalendarTable() {
        assertEquals(DayKind.OFF, DayKind.fromWire("holiday"))
        assertEquals(DayKind.OFF, DayKind.fromWire("emergency_off"))
        assertEquals(DayKind.MAKEUP, DayKind.fromWire("makeup_day"))
        assertNull(DayKind.fromWire("picnic"))
        assertEquals(CalendarLevel.entries.toList(), listOf("global", "wing", "division", "territory", "zone").map { CalendarLevel.fromWire(it) })
        assertNull(CalendarLevel.fromWire("house"))
    }

    @Test
    fun monthLengthsAndLeapYears() {
        assertEquals(29, WorkingDays.month(YearMonth(2028, 2), emptySet(), emptySet(), emptySet()))
        assertEquals(28, WorkingDays.month(YearMonth(2027, 2), emptySet(), emptySet(), emptySet()))
        assertEquals(30, WorkingDays.month(YearMonth(2026, 11), emptySet(), emptySet(), emptySet()))
        // Off and make-up dates of another month do not leak into this one.
        assertEquals(26, WorkingDays.month(oct, fri, setOf(LocalDate(2026, 11, 3)), setOf(LocalDate(2026, 9, 4))))
    }
}
