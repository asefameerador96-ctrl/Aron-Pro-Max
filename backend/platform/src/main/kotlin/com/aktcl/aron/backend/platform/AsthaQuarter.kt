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
        fun of(date: LocalDate, startMonth: Int): AsthaQuarter = AsthaQuarters.of(date.year, date.monthValue, startMonth).let(::from)

        /** The quarter named by [label] (`YYYY-Qn`, the year of the quarter's first month). Null for a malformed label. */
        fun parse(label: String, startMonth: Int): AsthaQuarter? = AsthaQuarters.parse(label, startMonth)?.let(::from)

        private fun from(q: AsthaQuarters.Quarter) = AsthaQuarter(q.key, q.months.map { LocalDate.of(it.substringBeforeLast('-').toInt(), it.substringAfterLast('-').toInt(), 1) })   // built from the parts, so any year keeps working (no ISO text parse)
    }
}
