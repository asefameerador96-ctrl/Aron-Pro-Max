package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.DayPlan
import java.time.LocalDate

/**
 * F-SYS-090 (D-584, docs/16 DQ-09): the ingest backdate window counted in working days. With
 * `cfg.calendar.window_unit = working_days` a row is inside the window when its business date is not before the
 * [days]-th working day before today (non-working days in between are inside too), so the rows of the last working
 * evening before a long break are not quarantined on the first morning after it. The window never reaches further back
 * than [ceilingDays] calendar days: the ingest registry keeps a uuid for `cfg.retention.ingest_registry_days`, which the
 * registry requires to exceed the backdate window by 30 days, so a re-send inside the window is always recognised.
 */
object WorkingDays {
    const val CALENDAR = "calendar"
    const val WORKING_DAYS = "working_days"

    /** The oldest business date still inside the window ending at [today]. */
    fun floor(today: LocalDate, days: Long, unit: String, ceilingDays: Long, isWorking: (LocalDate) -> Boolean): LocalDate {
        val calendarFloor = today.minusDays(days)
        if (unit != WORKING_DAYS) return calendarFloor
        val limit = today.minusDays(maxOf(ceilingDays, days))
        var d = today
        var counted = 0L
        while (counted < days && d.isAfter(limit)) {
            d = d.minusDays(1)
            if (isWorking(d)) counted++
        }
        // Never narrower than the calendar rule.
        return minOf(d, calendarFloor)
    }

    /** The calendar ceiling from the registry retention (default 45): retention - 30, at least [days]. */
    fun ceiling(registryDays: Long, days: Long): Long = maxOf(days, registryDays - 30)

    fun isWorking(date: LocalDate, zone: DayPlan.ZoneChain, calendar: List<DayPlan.CalendarEntry>, weekend: Set<Int>): Boolean =
        DayPlan.isSellingDay(date, zone, calendar, weekend)
}
