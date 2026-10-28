package com.aktcl.aron.rules

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.yearMonth

/** Level of a calendar declaration (`app.calendar_holiday.scope_type`); a later entry is more specific. */
enum class CalendarLevel(val wire: String) {
    GLOBAL("global"), WING("wing"), DIVISION("division"), TERRITORY("territory"), ZONE("zone");

    companion object {
        /** The level for a `scope_type` value; null for an unknown one. */
        fun fromWire(s: String): CalendarLevel? = entries.firstOrNull { it.wire == s }
    }
}

/** What a declaration does to its date: an off day (holiday or emergency off) or a make-up (declared selling) day. */
enum class DayKind {
    OFF, MAKEUP;

    companion object {
        /** `app.calendar_holiday.kind`: holiday and emergency_off are off days, makeup_day is a selling day; null otherwise. */
        fun fromWire(kind: String): DayKind? = when (kind) {
            "holiday", "emergency_off" -> OFF
            "makeup_day" -> MAKEUP
            else -> null
        }
    }
}

/** One live calendar declaration: [scopeId] is the id of the wing, division, territory or zone (0 for global). */
data class CalendarMark(val date: LocalDate, val level: CalendarLevel, val scopeId: Long, val kind: DayKind)

/**
 * The path of a dashboard node (D-TSO-MAN-08): the ids of its own level and of every level above it, null below it. A
 * zone node has all four ids; a territory node has wing, division and territory; the national (global) node has none.
 */
data class NodePath(val wingId: Long? = null, val divisionId: Long? = null, val territoryId: Long? = null, val zoneId: Long? = null) {
    /** True when a declaration at [level] for [scopeId] lies on this path (global always does). */
    fun covers(level: CalendarLevel, scopeId: Long): Boolean = when (level) {
        CalendarLevel.GLOBAL -> true
        CalendarLevel.WING -> wingId == scopeId
        CalendarLevel.DIVISION -> divisionId == scopeId
        CalendarLevel.TERRITORY -> territoryId == scopeId
        CalendarLevel.ZONE -> zoneId == scopeId
    }

    companion object {
        /** The national node: only global declarations apply. */
        val GLOBAL: NodePath = NodePath()
    }
}

/** The working calendar resolved at one node: its weekend, and its off and make-up dates after path resolution. */
data class NodeCalendar(val weekend: Set<DayOfWeek>, val off: Set<LocalDate>, val makeup: Set<LocalDate>) {
    /** A make-up day works; otherwise a day works when it is neither a weekend day nor an off day. */
    fun isWorking(date: LocalDate): Boolean = WorkingDays.isWorking(date, weekend, off, makeup)
}

/**
 * The one working-day count (D-TSO-MAN-08, CR-6): used for "today's target" (monthly target / W), the working-days
 * till-date basis (D-TSO-MAN-05), the admin calendar and daily tracking. Callers load the declarations on the node's path
 * and the weekend resolved at the node (`cfg.calendar.weekend_days`); this object does the counting, without I/O.
 *
 * A date is a working day when it is a make-up day, or when it is neither a weekend day nor an off day. So the month count
 * is "days of month - weekend days - off days + make-up days" with each date counted once: a holiday on a Friday is not
 * subtracted twice, a make-up day on an ordinary weekday adds nothing. With only global declarations and a Friday weekend
 * this is the rule of dw.v_calendar.is_working_day (V0097), which B2 tests equal for global scope.
 */
object WorkingDays {
    /** The default weekend: Friday (contract CalendarSection default [5]). */
    val DEFAULT_WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY)

    /**
     * The weekend from `cfg.calendar.weekend_days` (ISO 1 Monday .. 7 Sunday): a missing value gives Friday; numbers outside
     * 1..7 are ignored, so an empty list (or one with only bad numbers) means no weekend day, as the national dashboard reads it.
     */
    fun weekendOf(isoDays: Collection<Int>?): Set<DayOfWeek> =
        isoDays?.filter { it in 1..7 }?.map { DayOfWeek(it) }?.toSet() ?: DEFAULT_WEEKEND

    /** The ISO numbers of a weekend, sorted (the form `cfg.calendar.weekend_days` stores). */
    fun isoOf(weekend: Set<DayOfWeek>): List<Int> = weekend.map { it.isoDayNumber }.sorted()

    /** A make-up day works; otherwise a day works when it is neither in [weekend] nor in [off]. */
    fun isWorking(date: LocalDate, weekend: Set<DayOfWeek>, off: Set<LocalDate>, makeup: Set<LocalDate>): Boolean =
        date in makeup || (date.dayOfWeek !in weekend && date !in off)

    /** Working days of [ym]: days of the month - weekend days - off days + make-up days, each date once. */
    fun month(ym: YearMonth, weekend: Set<DayOfWeek>, off: Set<LocalDate>, makeup: Set<LocalDate>): Int =
        ym.days.count { isWorking(it, weekend, off, makeup) }

    /** Working days in [1st of the month .. [day]], [day] included when it is a working day. */
    fun elapsed(day: LocalDate, weekend: Set<DayOfWeek>, off: Set<LocalDate>, makeup: Set<LocalDate>): Int =
        (day.yearMonth.firstDay..day).count { isWorking(it, weekend, off, makeup) }

    /** [month] with a resolved node calendar. */
    fun month(ym: YearMonth, cal: NodeCalendar): Int = month(ym, cal.weekend, cal.off, cal.makeup)

    /** [elapsed] with a resolved node calendar. */
    fun elapsed(day: LocalDate, cal: NodeCalendar): Int = elapsed(day, cal.weekend, cal.off, cal.makeup)

    /**
     * Resolves the declarations on [path] (global, then wing, division, territory and zone, as far down as the node goes)
     * into the node's off and make-up dates. Per date the most specific declaration on the path decides, as the route-day
     * plan does (backend DayPlan.isSellingDay): a zone make-up day overrides a global holiday, a zone holiday overrides a
     * global make-up day. The database keeps one live declaration per date and scope, so two at the same level cannot both
     * be on one path; if they were, the off day wins (a contradiction is not a selling day). Declarations of other
     * branches (another zone, a territory's own zones for a territory node) are ignored.
     */
    fun resolve(path: NodePath, weekend: Set<DayOfWeek>, marks: Iterable<CalendarMark>): NodeCalendar {
        val off = HashSet<LocalDate>()
        val makeup = HashSet<LocalDate>()
        marks.filter { path.covers(it.level, it.scopeId) }.groupBy { it.date }.forEach { (date, onDate) ->
            val top = onDate.maxOf { it.level.ordinal }
            if (onDate.any { it.level.ordinal == top && it.kind == DayKind.OFF }) off += date else makeup += date
        }
        return NodeCalendar(weekend, off, makeup)
    }
}
