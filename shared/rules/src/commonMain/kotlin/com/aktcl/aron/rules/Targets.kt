package com.aktcl.aron.rules

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

/** Colour of a target bar: red when far behind, amber part-way, green near or above target (T-05). */
enum class Band { RED, AMBER, GREEN }

/**
 * `cfg.kpi.bar_bands` (D-TSO-MAN-10): red below [lo], amber from [lo] below [hi], green from [hi], on the PRINTED integer
 * percent. Default [40, 80].
 */
data class Bands(val lo: Long, val hi: Long) {
    init {
        require(lo in 0L..hi) { "bands need 0 <= lo <= hi: [$lo, $hi]" }
    }

    companion object {
        val DEFAULT: Bands = Bands(40L, 80L)

        /** The bands of a `cfg.kpi.bar_bands` value; a missing or malformed value (not two numbers 0 <= lo <= hi) gives [DEFAULT]. */
        fun of(values: List<Long>?): Bands =
            if (values != null && values.size == 2 && values[0] in 0L..values[1]) Bands(values[0], values[1]) else DEFAULT
    }
}

/** `cfg.kpi.tilldate_basis.*` (D-TSO-MAN-05): the till-date target counts calendar days (default) or working days. */
enum class TillDateBasis(val wire: String) {
    CALENDAR_DAYS("calendar_days"), WORKING_DAYS("working_days");

    companion object {
        /** The basis of a cfg value; a missing or unknown value gives the default, [CALENDAR_DAYS]. */
        fun fromWire(s: String?): TillDateBasis = entries.firstOrNull { it.wire == s } ?: CALENDAR_DAYS
    }
}

/** The till-date fraction e / n: [elapsed] days of [period] days of the month (contract `elapsed_days`, `period_days`). */
data class TillDateDays(val elapsed: Int, val period: Int)

/**
 * Target figures (build contract s4.1 to s4.3; D-TSO-MAN-05, D-TSO-MAN-40; T-05, T-06, T-07, T-09). Long arithmetic, one
 * rounding per figure, no floating point. Monthly target T and achievement A are integer base units (sticks, pieces,
 * dozens). Card figures: a category or card till-date target is the SUM of its item (variant) till-date rows, each rounded
 * by [tillDate]; it is never [tillDate] of the summed monthly target (R-F-01: the Cigarette card at 21/26 is 33114, while
 * tillDate(40999, 21, 26) is 33115).
 */
object Targets {
    /**
     * Item till-date target: round_half_up(T x e / n) = (2 T e + n) div (2 n), with e clamped to 0..n. Null when n = 0
     * (a month without working days on the working basis). 1000 x 25 / 31 -> 806, 10000 -> 8065 (calendar basis, 25 Oct).
     */
    fun tillDate(t: Long, e: Long, n: Long): Long? {
        require(t >= 0L) { "target must be >= 0: $t" }
        require(n >= 0L) { "period must be >= 0: $n" }
        if (n == 0L) return null
        val ec = e.coerceIn(0L, n)
        return checkedAdd(checkedMul(checkedMul(2L, t), ec), n) / checkedMul(2L, n)
    }

    /** Remaining = max(T - A, 0): 81 - 85 -> 0 (T-09). */
    fun remaining(t: Long, a: Long): Long {
        require(t >= 0L && a >= 0L) { "target and achievement must be >= 0: $t, $a" }
        return if (a >= t) 0L else t - a
    }

    /** The bar colour of a printed integer percent [p]: p < lo red, p < hi amber, else green. */
    fun band(p: Long, bands: Bands = Bands.DEFAULT): Band = when {
        p < bands.lo -> Band.RED
        p < bands.hi -> Band.AMBER
        else -> Band.GREEN
    }

    /** Target card percent: min(100, [Percent.whole] (A, T')), T' monthly or till-date; null when T' = 0 (85 / 81 -> 100). */
    fun cardPct(a: Long, t: Long): Long? = Percent.whole(a, t)?.coerceAtMost(100L)

    /** Target details percent in basis points, uncapped (printed with 2 decimals): 85 / 81 -> 10494 ("104.94%"). */
    fun detailsBp(a: Long, t: Long): Long? = Percent.bp(a, t)

    /**
     * Dashboard sales ring: today's sales A against today's target T / W, as tenths of a percent, uncapped:
     * [Percent.tenths] (A x W, T). Null when W = 0 (R-F-13: no working day, no daily target; "Total Target"
     * `Qty.text(T, 0)` prints [Percent.DASH] too) and when T = 0 (no whole; "Total Target" then prints "0.0").
     * Cigarette 40 of 42,000 over 25 days -> 24 ("2.4%"); Bidi 2,991 of 26,000 -> 2876 ("287.6%"); Match 0 of 175 -> 0.
     */
    fun dailyRingTenths(a: Long, t: Long, w: Long): Long? {
        require(w >= 0L) { "working days must be >= 0: $w" }
        if (w == 0L) return null
        return Percent.tenths(checkedMul(a, w), t)
    }

    /**
     * The till-date fraction of [month] on business date [day] (D-TSO-MAN-05). A past month is complete (e = n); a future
     * month has not started (e = 0). In the month of [day]: calendar basis e = day of month, n = days in the month; working
     * basis e = [WorkingDays.elapsed] (day included when it is a working day), n = [WorkingDays.month]. [cal] is required
     * for the working basis and ignored for the calendar basis.
     */
    fun days(basis: TillDateBasis, month: YearMonth, day: LocalDate, cal: NodeCalendar? = null): TillDateDays {
        val period = when (basis) {
            TillDateBasis.CALENDAR_DAYS -> month.numberOfDays
            TillDateBasis.WORKING_DAYS -> WorkingDays.month(month, requireNotNull(cal) { "the working-days basis needs a calendar" })
        }
        val current = day.yearMonth
        val elapsed = when {
            month < current -> period
            month > current -> 0
            basis == TillDateBasis.CALENDAR_DAYS -> day.day
            else -> WorkingDays.elapsed(day, requireNotNull(cal))
        }
        return TillDateDays(elapsed, period)
    }

    /** [tillDate] with a [TillDateDays] fraction. */
    fun tillDate(t: Long, days: TillDateDays): Long? = tillDate(t, days.elapsed.toLong(), days.period.toLong())
}
