package com.aktcl.aron.backend.platform

import com.aktcl.aron.backend.platform.DayPlan.CalendarEntry
import com.aktcl.aron.backend.platform.DayPlan.CalendarScope
import com.aktcl.aron.backend.platform.DayPlan.PlanRoute
import com.aktcl.aron.backend.platform.DayPlan.VisitKind
import com.aktcl.aron.backend.platform.DayPlan.ZoneChain
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DayPlanTest {
    private val zone = ZoneChain(zoneId = 5012, territoryId = 334, divisionId = 33, wingId = 3)
    private val daily = PlanRoute(1, DayPlan.DAILY_MASK, zone)
    private val threeF = PlanRoute(2, DayPlan.mask(SUNDAY, TUESDAY, THURSDAY), zone)
    private val twoF = PlanRoute(3, DayPlan.mask(MONDAY, THURSDAY), zone)
    private val routes = listOf(daily, threeF, twoF)
    private val sunday = LocalDate.parse("2026-10-04")
    private val friday = LocalDate.parse("2026-10-09")

    @Test
    fun masksMatchTheContractExamples() {
        assertEquals(42, threeF.mask)
        assertEquals(36, twoF.mask)
        assertEquals(VisitKind.DAILY, DayPlan.kindOf(127))
        assertEquals(VisitKind.THREE_F, DayPlan.kindOf(42))
        assertEquals(VisitKind.TWO_F, DayPlan.kindOf(36))
        assertTrue(DayPlan.consistent(VisitKind.THREE_F, 42))
        assertFalse(DayPlan.consistent(VisitKind.TWO_F, 42))
    }

    @Test
    fun onASeededSundayDailyAnd3fArePlannedAnd2fIsNot() {
        assertEquals(SUNDAY, sunday.dayOfWeek)
        assertEquals(listOf(1L, 2L), DayPlan.plannedRoutes(routes, sunday, emptyList()).map { it.routeId })
    }

    @Test
    fun onFridayTheWeekendNothingIsPlanned() {
        assertEquals(java.time.DayOfWeek.FRIDAY, friday.dayOfWeek)
        assertEquals(emptyList(), DayPlan.plannedRoutes(routes, friday, emptyList()))
    }

    @Test
    fun aMondayAndAThursdayPlanTheRightKinds() {
        assertEquals(listOf(1L, 3L), DayPlan.plannedRoutes(routes, LocalDate.parse("2026-10-05"), emptyList()).map { it.routeId })
        assertEquals(listOf(1L, 2L, 3L), DayPlan.plannedRoutes(routes, LocalDate.parse("2026-10-08"), emptyList()).map { it.routeId })
    }

    @Test
    fun targetOutletsEqualThePlannedRoutesOutlets() {
        // 60 outlets: 20 per route; outlet 60 is closed; outlet 61 has no route.
        val outletRoute = (1L..60L).associateWith { ((it - 1) / 20) + 1 } + (61L to null)
        val active = (1L..59L).toSet() + 61L
        val planned = DayPlan.plannedRoutes(routes, sunday, emptyList()).map { it.routeId }
        assertEquals(40, DayPlan.targetOutlets(planned, outletRoute, active))
        val thursday = DayPlan.plannedRoutes(routes, LocalDate.parse("2026-10-08"), emptyList()).map { it.routeId }
        assertEquals(59, DayPlan.targetOutlets(thursday, outletRoute, active))
    }

    @Test
    fun theCalendarOverridesTheWeekdayByMostSpecificScope() {
        val holiday = CalendarEntry(sunday, CalendarScope.GLOBAL, 0, "holiday", false)
        assertEquals(emptyList(), DayPlan.plannedRoutes(routes, sunday, listOf(holiday)))
        // A zone make-up day beats a global holiday; a holiday in another zone does not apply.
        val makeup = CalendarEntry(sunday, CalendarScope.ZONE, 5012, "makeup_day", true)
        assertEquals(listOf(1L, 2L), DayPlan.plannedRoutes(routes, sunday, listOf(holiday, makeup)).map { it.routeId })
        val elsewhere = CalendarEntry(sunday, CalendarScope.ZONE, 9999, "emergency_off", false)
        assertEquals(listOf(1L, 2L), DayPlan.plannedRoutes(routes, sunday, listOf(elsewhere)).map { it.routeId })
        // A Friday make-up day plans the routes whose mask contains Friday (Daily).
        val fridayMakeup = CalendarEntry(friday, CalendarScope.TERRITORY, 334, "makeup_day", true)
        assertEquals(listOf(1L), DayPlan.plannedRoutes(routes, friday, listOf(fridayMakeup)).map { it.routeId })
        // Same scope, contradictory entries: not a selling day.
        val both = listOf(CalendarEntry(sunday, CalendarScope.ZONE, 5012, "makeup_day", true), CalendarEntry(sunday, CalendarScope.ZONE, 5012, "emergency_off", false))
        assertEquals(emptyList(), DayPlan.plannedRoutes(routes, sunday, both))
        // An inactive route is never planned.
        assertFalse(DayPlan.isPlanned(daily.copy(active = false), sunday, emptyList()))
    }
}
