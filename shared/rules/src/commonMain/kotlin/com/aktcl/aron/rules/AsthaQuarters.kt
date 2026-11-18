package com.aktcl.aron.rules

/**
 * THE Astha quarter naming, one function for the phone, the web and the server (`cfg.astha.quarter_start_month`, 1..12, default 1). Calendar quarters shifted by the start month; the label is
 * `YYYY-Qn` where YYYY is the year of the quarter's FIRST MONTH and n counts quarters from the start month (Q1 starts in the start month). With start month 7: 2026-11 is in `2026-Q2` (Oct to Dec 2026),
 * 2027-02 is in `2027-Q3` (Jan to Mar 2027), and the label `2026-Q4` names Apr to Jun 2026. Pure integer arithmetic in common Kotlin (Int.mod and Int.floorDiv, no java.lang.Math, so every target compiles), no locale, no clock. The server's `AsthaQuarter` must call this
 * (docs/requests/astha-quarter-shared-function.md); `AsthaQuartersParityTest` pins the vectors and compares every month of 2020 to 2035 for every start month with the server's algorithm.
 */
object AsthaQuarters {
    /** [key] `YYYY-Qn`; [year] and [index] its parts; [months] the three months `YYYY-MM`, ascending. */
    data class Quarter(val key: String, val year: Int, val index: Int, val months: List<String>)

    private fun clamp(startMonth: Int) = startMonth.coerceIn(1, 12)
    private fun two(n: Int) = if (n < 10) "0$n" else n.toString()
    private fun four(n: Int) = n.toString().padStart(4, '0')
    private fun monthKey(total: Int) = four(total.floorDiv(12)) + "-" + two(total.mod(12) + 1)

    /** The quarter that contains month [month] (1..12) of [year]. */
    fun of(year: Int, month: Int, startMonth: Int): Quarter {
        val sm = clamp(startMonth)
        val sinceStart = (month - sm).mod(12)
        val first = year * 12 + (month - 1) - sinceStart % 3 // months since year 0 of the quarter's first month
        val firstMonth = first.mod(12) + 1
        val index = (firstMonth - sm).mod(12) / 3 + 1
        val y = first.floorDiv(12)
        return Quarter(four(y) + "-Q" + index, y, index, (0..2).map { monthKey(first + it) })
    }

    /** The quarter containing a month `YYYY-MM` or a date `YYYY-MM-DD`; null for a malformed text. */
    fun of(text: String, startMonth: Int): Quarter? {
        val m = Regex("^(\\d{4})-(0[1-9]|1[0-2])(?:-\\d{2})?$").matchEntire(text) ?: return null
        return of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), startMonth)
    }

    /** The quarter named by [key] (`YYYY-Qn`, the year of its first month); null for a malformed key. */
    fun parse(key: String, startMonth: Int): Quarter? {
        val m = Regex("^(\\d{4})-Q([1-4])$").matchEntire(key) ?: return null
        val n = m.groupValues[2].toInt()
        val firstMonth = (clamp(startMonth) - 1 + 3 * (n - 1)) % 12 + 1
        val first = m.groupValues[1].toInt() * 12 + (firstMonth - 1)
        return Quarter(key, m.groupValues[1].toInt(), n, (0..2).map { monthKey(first + it) })
    }
}
