package com.aktcl.aron.backend.platform

import com.aktcl.aron.rules.AsthaQuarters
import java.time.LocalDate

/**
 * The Astha quarter containing [date]: calendar quarters shifted by `cfg.astha.quarter_start_month` (default 1). Label is `YYYY-Qn` of the year the quarter starts in.
 * The naming is NOT computed here any more: both functions call the ONE shared function `com.aktcl.aron.rules.AsthaQuarters` (phone, web and server cannot drift;
 * docs/requests/astha-quarter-shared-function.md), this class only gives the months as dates.
 */
data class AsthaQuarter(val label: String, val months: List<LocalDate>) {
    companion object {
        fun of(date: LocalDate, startMonth: Int): AsthaQuarter = from(AsthaQuarters.of(date.year, date.monthValue, startMonth), startMonth)

        /** The quarter named by [label] (`YYYY-Qn`, the year of the quarter's first month). Null for a malformed label. */
        fun parse(label: String, startMonth: Int): AsthaQuarter? = AsthaQuarters.parse(label, startMonth)?.let { from(it, startMonth) }

        // The months are built from the quarter's year and index (no text round trip), so any date works. LIMIT: the LABEL is the shared function's key, written with four digits: dates before year 0 get that function's label.
        private fun from(q: AsthaQuarters.Quarter, startMonth: Int): AsthaQuarter {
            val first = LocalDate.of(q.year, (startMonth.coerceIn(1, 12) - 1 + 3 * (q.index - 1)) % 12 + 1, 1)
            return AsthaQuarter(q.key, (0L..2L).map { first.plusMonths(it) })
        }
    }
}
