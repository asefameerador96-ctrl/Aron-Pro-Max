package com.aktcl.aron.rules

/**
 * Percentages from integers, rounded ONCE (D-TSO-MAN-10; build contract docs/requests/tso-manual-build-2026-10.md s4.1).
 *
 * Every value is round_half_up(scale x n / d) computed as (2 x scale x n + d) div (2 x d) on Long, so no figure is derived
 * from an already rounded one (29 of 1,186 is 2.4%, never the 2.5% that rounding 245 bp to tenths would give). The part
 * n is >= 0 and the whole d is >= 0; d = 0 gives null, which [text] prints as [DASH]. A negative input is a caller bug
 * and throws [IllegalArgumentException]; an overflow throws [ArithmeticException] instead of wrapping. The domain bound of
 * the contract (n up to 2000 x A x W with A <= 10^10, W <= 31) is far inside Long. No floating point anywhere.
 *
 * The phone, the server and the web print the same text for the same integers: contract/fixtures/kpi-format-corpus.json
 * (tools/ci/kpi-format-corpus.py) pins it, KpiFormatCorpusTest checks this file against it.
 */
object Percent {
    /** What a missing figure prints as (d = 0, no target, no data): U+2014 EM DASH. */
    const val DASH: String = "—"

    /** Whole percent: (200 n + d) div (2 d). Login and Bikroy Joma bars, Brand CPR, the Target card (then capped), tickets. */
    fun whole(n: Long, d: Long): Long? = scaled(n, d, 100L)

    /** Tenths of a percent: (2000 n + d) div (2 d). Dashboard rings and cards, CPR, Geo Fencing. */
    fun tenths(n: Long, d: Long): Long? = scaled(n, d, 1_000L)

    /** Basis points (hundredths of a percent): (20000 n + d) div (2 d). Target details (2 dp), channel pie (2 dp), server `*_bp`. */
    fun bp(n: Long, d: Long): Long? = scaled(n, d, 10_000L)

    /** round_half_up(scale x n / d) on Long; null when d = 0. */
    private fun scaled(n: Long, d: Long, scale: Long): Long? {
        require(n >= 0L) { "part must be >= 0: $n" }
        require(d >= 0L) { "whole must be >= 0: $d" }
        if (d == 0L) return null
        return checkedAdd(checkedMul(2L * scale, n), d) / checkedMul(2L, d)
    }

    /**
     * A scaled integer percentage as text: [v] / 10^[decimals] with exactly [decimals] fraction digits and "%", the whole
     * part grouped with "," like [Formats.integer] (Western grouping in both locales, F-SYS-051). Bengali digits in Bangla;
     * "," "." and "%" stay Latin. Null prints [DASH]. 24 tenths -> "2.4%", 10494 bp -> "104.94%", 40 whole -> "৪০%" (bn).
     */
    fun text(v: Long?, decimals: Int, locale: UiLocale): String {
        require(decimals in 0..4) { "decimals must be 0..4: $decimals" }
        if (v == null) return DASH
        require(v != Long.MIN_VALUE) { "percentage out of range" }
        var pow = 1L
        repeat(decimals) { pow *= 10L }
        val a = if (v < 0L) -v else v
        val whole = Formats.integer(a / pow, locale)
        val frac = if (decimals == 0) "" else "." + Formats.digits((a % pow).toString().padStart(decimals, '0'), locale)
        return (if (v < 0L) "-" else "") + whole + frac + "%"
    }

    /** [whole] as text ("40%"); [DASH] when d = 0. */
    fun wholeText(n: Long, d: Long, locale: UiLocale): String = text(whole(n, d), 0, locale)

    /** [tenths] as text ("2.4%"); [DASH] when d = 0. */
    fun tenthsText(n: Long, d: Long, locale: UiLocale): String = text(tenths(n, d), 1, locale)

    /** [bp] as text with two decimals ("104.94%"); [DASH] when d = 0. */
    fun bpText(n: Long, d: Long, locale: UiLocale): String = text(bp(n, d), 2, locale)
}
