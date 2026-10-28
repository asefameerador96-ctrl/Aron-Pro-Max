package com.aktcl.aron.rules

/**
 * Quantities as the TSO dashboard and Target Status print them (D-TSO-MAN-10; build contract s4.1 and s4.2).
 *
 * A quantity is an integer count in the SKU's base unit (cigarette and bidi sticks, lighter pieces, match dozens), shown
 * as n / d (d = 1 for a total, d = working days for "today's target"). One decimal, half up: t = (20 n + d) div (2 d)
 * tenths. Below 1,000.0 it prints "t/10.t%10" ("40.0", "999.0"); from 1,000.0 it prints thousands with one decimal and
 * " K", rounded once from n (never from t): k = (2 n + 100 d) div (200 d) tenths of K, whole part grouped with ","
 * ("1.7 K", "1,234.6 K"). No M, no lakh or crore. Bengali digits in Bangla; "K" stays Latin.
 *
 * n >= 0 and d >= 0; a null n or d = 0 prints [Percent.DASH]. Long arithmetic, overflow throws, no floating point.
 */
object Qty {
    /** The thousands suffix, with its leading space; Latin in both locales. */
    const val K_SUFFIX: String = " K"

    /** Tenths of n / d, half up; null when d = 0. */
    fun tenths(n: Long, d: Long = 1L): Long? {
        require(n >= 0L) { "quantity must be >= 0: $n" }
        require(d >= 0L) { "divisor must be >= 0: $d" }
        if (d == 0L) return null
        return checkedAdd(checkedMul(20L, n), d) / checkedMul(2L, d)
    }

    /** A total quantity ("40.0", "3.0 K"); [Percent.DASH] when [n] is null. */
    fun text(n: Long?, locale: UiLocale): String = text(n, 1L, locale)

    /** n / d as text ("Total Target" = monthly target / working days); [Percent.DASH] when [n] is null or [d] is 0. */
    fun text(n: Long?, d: Long, locale: UiLocale): String {
        if (n == null) return Percent.DASH
        val t = tenths(n, d) ?: return Percent.DASH
        if (t < 10_000L) return Formats.digits("${t / 10L}.${t % 10L}", locale)
        val k = checkedAdd(checkedMul(2L, n), checkedMul(100L, d)) / checkedMul(200L, d)
        return Formats.integer(k / 10L, locale) + "." + Formats.digits((k % 10L).toString(), locale) + K_SUFFIX
    }
}
