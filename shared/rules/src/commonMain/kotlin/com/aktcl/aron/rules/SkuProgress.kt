package com.aktcl.aron.rules

/**
 * SR Target and Achievement by SKU (manual screen 10, D-58 amended 2026-10-04, doc 16 K-13, R-23; lane T-KPI1, N-2).
 *
 * Integers in, integers out, rounded ONCE half up, no floating point (same style as [Percent] and [Targets]). The server
 * calls [row] on whole base units. The Android SKU target screen (lane android-sr-programmes, D-SRPROG-11) uses only [dayCounts]
 * and the [Calendar]: it keeps the Decimal3 target and the achieved quantity unrounded, divides the raw figure by the day count and
 * the unit factor as one exact quotient and rounds ONCE at display in the unit shown (a packet, a box, a dozen), so a figure on
 * the phone can differ in the last decimal from the whole-base-unit figures of [row] (target 500 over 14 days: 36 here, 35.7 on the phone).
 *
 *   ADS  = achieved / elapsed working days        PADS = ADS (default, owner question Q-B)
 *   TADS = target / elapsed working days          RADS = remaining / remaining working days
 *   Achievement % = bp(achieved, target), not capped (a target of 0 gives null)
 *
 * K-13 fixture (April 2026 as of 2026-04-18: 14 elapsed, 11 remaining): target 500 gives TADS 36 and RADS 45; 900 gives 64 and
 * 82. A division by 0 days gives null (day 1 has no elapsed day, the last day has no remaining day), never 0.
 */
object SkuProgress {
    /**
     * Which days count as elapsed (owner question Q-A, lead ruling: both conventions live behind [dayCounts] so a change is
     * one line, [DEFAULT_CONVENTION]).
     * [BEFORE_TODAY] is K-13: the working days of the month before the as-of date (14 on 2026-04-18).
     * [THROUGH_TODAY] is screen 62 (EWD 10 on 2026-11-11): the as-of date counts when it is a working day.
     */
    enum class DayConvention { BEFORE_TODAY, THROUGH_TODAY }

    val DEFAULT_CONVENTION: DayConvention = DayConvention.BEFORE_TODAY

    /** The bundle's calendar numbers (contract `RouteCalendar`): working days before and after the as-of date, and whether it works. */
    data class Calendar(val elapsedBeforeToday: Int, val remainingAfterToday: Int, val todayIsWorking: Boolean) {
        init {
            require(elapsedBeforeToday >= 0 && remainingAfterToday >= 0) { "day counts must be >= 0" }
        }
    }

    /** Elapsed and remaining working days under a convention. */
    data class DayCounts(val elapsed: Int, val remaining: Int)

    /** THE one function that decides what "elapsed" and "remaining" mean. */
    fun dayCounts(cal: Calendar, convention: DayConvention = DEFAULT_CONVENTION): DayCounts = when (convention) {
        DayConvention.BEFORE_TODAY -> DayCounts(cal.elapsedBeforeToday, cal.remainingAfterToday)
        DayConvention.THROUGH_TODAY -> DayCounts(cal.elapsedBeforeToday + (if (cal.todayIsWorking) 1 else 0), cal.remainingAfterToday)
    }

    /** round_half_up(n / d) for n >= 0, d >= 0; null when d = 0. */
    fun ratio(n: Long, d: Long): Long? {
        require(n >= 0L && d >= 0L) { "n and d must be >= 0: $n, $d" }
        if (d == 0L) return null
        return checkedAdd(checkedMul(2L, n), d) / checkedMul(2L, d)
    }

    fun ads(achieved: Long, elapsed: Int): Long? = ratio(achieved, elapsed.toLong())
    fun pads(achieved: Long, elapsed: Int): Long? = ads(achieved, elapsed)
    fun tads(target: Long, elapsed: Int): Long? = ratio(target, elapsed.toLong())
    fun rads(target: Long, achieved: Long, remaining: Int): Long? = ratio(Targets.remaining(target, achieved), remaining.toLong())

    /** One SKU row of screen 10. */
    data class Row(val target: Long, val achieved: Long, val remaining: Long, val achievementBp: Long?, val ads: Long?, val tads: Long?, val pads: Long?, val rads: Long?)

    fun row(target: Long, achieved: Long, cal: Calendar, convention: DayConvention = DEFAULT_CONVENTION): Row {
        val d = dayCounts(cal, convention)
        return Row(target, achieved, Targets.remaining(target, achieved), Percent.bp(achieved, target), ads(achieved, d.elapsed), tads(target, d.elapsed), pads(achieved, d.elapsed), rads(target, achieved, d.remaining))
    }
}
