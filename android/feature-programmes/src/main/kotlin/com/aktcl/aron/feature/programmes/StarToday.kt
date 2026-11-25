package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.UiLocale

/**
 * "Today on this phone" for one Lifting Star (screen 62): this phone's own live sale memos dated after the server's through_date (the figure never holds them), in the Star's programme unit
 * (`unit_divisor`), as a line SEPARATE from the server's STD, never added to it. [qtyBase] over [divisor] is kept exact and rounded once at display. [incomplete] is true when the server listed only the
 * first 500 SKUs of the programme (`sku_ids_truncated`): the phone cannot count the rest. [unconfirmedEarlier] is the COUNT of earlier-day memos the server has not confirmed.
 */
data class StarToday(val qtyBase: Long, val divisor: Long, val memoCount: Long, val unconfirmedEarlier: Int, val incomplete: Boolean)

object StarTodayModel {
    /**
     * The SKUs the Star's STD counts, as a test on a SKU id: the explicit `sku_ids`, else every SKU of `sku_category` ([categoryOf]); null when the row names neither (no line is shown: an older server,
     * a category outside the contract, or an empty list: nothing the phone can count).
     */
    fun skuFilter(star: RouteStar, categoryOf: (Long) -> String?): ((Long) -> Boolean)? {
        val ids = star.skuIds
        if (ids != null && ids.isNotEmpty()) { val set = ids.toSet(); return { it in set } }
        val cat = star.skuCategory ?: return null
        return { categoryOf(it) == cat }
    }

    /** The line for [star] from the phone's frame over the SKUs of [skuFilter]; null when the row has no SKU rule. */
    fun of(star: RouteStar, frame: FigureFrame): StarToday = StarToday(
        frame.today.qtyBase, star.unitDivisor ?: 1L, frame.today.memoCount, frame.unconfirmedEarlier, star.skuIdsTruncated,
    )

    /** [t]'s quantity in the programme unit as two decimals, rounded ONCE half up from the exact quotient ("2.50"); a dash when it cannot be computed (a divisor below 1, a negative quantity, an overflow). */
    fun qtyText(t: StarToday, locale: UiLocale): String {
        if (t.divisor < 1L || t.qtyBase < 0L) return Percent.DASH
        val h = runCatching { Math.addExact(Math.multiplyExact(t.qtyBase, 200L), t.divisor) / Math.multiplyExact(t.divisor, 2L) }.getOrNull() ?: return Percent.DASH // hundredths, half up
        return Formats.integer(h / 100L, locale) + Formats.digits("." + (h % 100L).toString().padStart(2, '0'), locale)
    }
}
