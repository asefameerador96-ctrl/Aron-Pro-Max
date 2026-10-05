package com.aktcl.aron.backend.platform

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Route kinds and the day plan (N-017). A route's visit days are the contract's `visit_days_mask` (bit0 Sat, bit1 Sun,
 * bit2 Mon, bit3 Tue, bit4 Wed, bit5 Thu, bit6 Fri): Daily = 127, a 3F route three bits (Sun/Tue/Thu = 42), a 2F route
 * two bits (Mon/Thu = 36). A route is planned on a business date when the date is a selling day for the route's zone
 * and the date's weekday is in the mask. Selling days come from the working calendar: the weekend (ISO weekdays,
 * default Friday), overridden by the most specific holiday, make-up day or emergency off-day on that date.
 */
object DayPlan {
    enum class VisitKind(val wire: String, val days: Int) { DAILY("daily", 7), THREE_F("3f", 3), TWO_F("2f", 2) }

    /** Bit of a weekday in `visit_days_mask`. */
    fun bit(day: DayOfWeek): Int = when (day) {
        DayOfWeek.SATURDAY -> 0; DayOfWeek.SUNDAY -> 1; DayOfWeek.MONDAY -> 2; DayOfWeek.TUESDAY -> 3
        DayOfWeek.WEDNESDAY -> 4; DayOfWeek.THURSDAY -> 5; DayOfWeek.FRIDAY -> 6
    }

    fun mask(vararg days: DayOfWeek): Int = days.fold(0) { m, d -> m or (1 shl bit(d)) }

    const val DAILY_MASK = 127

    fun visitsOn(mask: Int, date: LocalDate): Boolean = mask and (1 shl bit(date.dayOfWeek)) != 0

    /** The kind a mask describes (daily = all seven days; 3F and 2F by the number of days), null otherwise. */
    fun kindOf(mask: Int): VisitKind? = when {
        mask == DAILY_MASK -> VisitKind.DAILY
        Integer.bitCount(mask) == 3 -> VisitKind.THREE_F
        Integer.bitCount(mask) == 2 -> VisitKind.TWO_F
        else -> null
    }

    /** Checks that a stored visit_kind agrees with its mask (Daily = 127, 3F = 3 days, 2F = 2 days). */
    fun consistent(kind: VisitKind, mask: Int): Boolean = if (kind == VisitKind.DAILY) mask == DAILY_MASK else Integer.bitCount(mask) == kind.days

    /** Scope of a calendar entry; [specificity] orders global < wing < division < territory < zone. */
    enum class CalendarScope(val wire: String, val specificity: Int) { GLOBAL("global", 0), WING("wing", 1), DIVISION("division", 2), TERRITORY("territory", 3), ZONE("zone", 4) }

    data class CalendarEntry(val date: LocalDate, val scope: CalendarScope, val scopeId: Long, val kind: String, val sellingDay: Boolean)

    /** A zone and its ancestors, so a scoped calendar entry can be matched. */
    data class ZoneChain(val zoneId: Long, val territoryId: Long, val divisionId: Long, val wingId: Long) {
        fun matches(scope: CalendarScope, id: Long): Boolean = when (scope) {
            CalendarScope.GLOBAL -> true
            CalendarScope.WING -> id == wingId
            CalendarScope.DIVISION -> id == divisionId
            CalendarScope.TERRITORY -> id == territoryId
            CalendarScope.ZONE -> id == zoneId
        }
    }

    /** ISO weekday numbers (1 Monday .. 7 Sunday) that are not selling days; contract CalendarSection default [5]. */
    val DEFAULT_WEEKEND: Set<Int> = setOf(5)

    fun isSellingDay(date: LocalDate, zone: ZoneChain, calendar: List<CalendarEntry>, weekend: Set<Int> = DEFAULT_WEEKEND): Boolean {
        val applicable = calendar.filter { it.date == date && zone.matches(it.scope, it.scopeId) }
        if (applicable.isNotEmpty()) {
            val top = applicable.maxOf { it.scope.specificity }
            // At equal specificity an off-day wins over a make-up day (a contradiction in the calendar is not a selling day).
            return applicable.filter { it.scope.specificity == top }.all { it.sellingDay }
        }
        return date.dayOfWeek.value !in weekend
    }

    data class PlanRoute(val routeId: Long, val mask: Int, val zone: ZoneChain, val active: Boolean = true)

    fun isPlanned(route: PlanRoute, date: LocalDate, calendar: List<CalendarEntry>, weekend: Set<Int> = DEFAULT_WEEKEND): Boolean =
        route.active && visitsOn(route.mask, date) && isSellingDay(date, route.zone, calendar, weekend)

    fun plannedRoutes(routes: List<PlanRoute>, date: LocalDate, calendar: List<CalendarEntry>, weekend: Set<Int> = DEFAULT_WEEKEND): List<PlanRoute> =
        routes.filter { isPlanned(it, date, calendar, weekend) }

    /** Target outlets of a date: the active outlets of the planned routes (strike-rate denominator, docs/24 s12.4). */
    fun targetOutlets(planned: Collection<Long>, outletRouteIds: Map<Long, Long?>, activeOutletIds: Set<Long>): Int =
        outletRouteIds.count { (id, route) -> route != null && route in planned && id in activeOutletIds }
}

/** One route of a user on a business date, as the bundle's RouteSnapshot needs it (N-017). */
data class RouteDayPlan(
    val routeId: Long,
    val code: String,
    val name: String,
    val zoneId: Long,
    val visitKind: String?,
    val visitDaysMask: Int,
    val displayLabel: String?,
    val assignmentKind: String,
    val plannedToday: Boolean,
    /** Active outlets of the route (the strike-rate denominator when planned; frozen by the bundle at first fetch). */
    val targetOutlets: Int,
)

/** The routes a user holds on a date with their plan (implemented in backend:masterdata, read by backend:sync). */
fun interface RoutePlanner {
    fun routesFor(userId: Long, businessDate: java.time.LocalDate): List<RouteDayPlan>
}
