package com.aktcl.aron.backend.platform

import java.time.LocalDate

/** The Astha quarter containing [date]: calendar quarters shifted by `cfg.astha.quarter_start_month` (default 1). Label is `YYYY-Qn` of the year the quarter starts in. */
data class AsthaQuarter(val label: String, val months: List<LocalDate>) {
    companion object {
        fun of(date: LocalDate, startMonth: Int): AsthaQuarter {
            val sm = startMonth.coerceIn(1, 12)
            val monthsSinceStart = Math.floorMod(date.monthValue - sm, 12)
            val first = date.withDayOfMonth(1).minusMonths((monthsSinceStart % 3).toLong())
            val qIndex = (Math.floorMod(first.monthValue - sm, 12)) / 3 + 1
            return AsthaQuarter("${first.year}-Q$qIndex", (0L..2L).map { first.plusMonths(it) })
        }

        /** The quarter named by [label] (`YYYY-Qn`, the year of the quarter's first month): its first month is `startMonth + 3 x (n - 1)` of that year. Null for a malformed label. */
        fun parse(label: String, startMonth: Int): AsthaQuarter? {
            val m = Regex("^(\\d{4})-Q([1-4])$").matchEntire(label) ?: return null
            val monthNo = (startMonth.coerceIn(1, 12) - 1 + 3 * (m.groupValues[2].toInt() - 1)) % 12 + 1
            val first = LocalDate.of(m.groupValues[1].toInt(), monthNo, 1)
            return AsthaQuarter(label, (0L..2L).map { first.plusMonths(it) })
        }
    }
}
