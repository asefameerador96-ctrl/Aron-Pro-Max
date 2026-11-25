package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.UiLocale

/**
 * "Today on this phone" for one Lifting Star (screen 62): this phone's own live sale memos dated after the server's through_date (the figure never holds them), in the Star's programme unit
 * (`unit_divisor`), as a line SEPARATE from the server's STD, never added to it. [qtyBase] over [divisor] is kept exact and rounded once at display. [incomplete] is true when the server listed only the
 * first 500 SKUs of the programme (`sku_ids_truncated`): the phone cannot count the rest. [unconfirmedEarlier] is the COUNT of earlier-day memos the server has not confirmed.
 */
data class StarToday(
    val qtyBase: Long, val divisor: Long, val memoCount: Long, val unconfirmedEarlier: Int, val incomplete: Boolean,
    /** True when the phone could not read its memos: the line is still shown, with dashes (never a vanished line, never a made-up 0). */
    val failed: Boolean = false,
)

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

    /** True when the row names the SKUs its STD counts (a line is shown); false for an older server or an empty list. */
    fun hasRule(star: RouteStar): Boolean = skuFilter(star) { null } != null

    /** The line for [star] from the phone's frame over the SKUs of [skuFilter]; [incomplete] adds a reason of the phone's own (a sale of a SKU whose category it cannot resolve). */
    fun of(star: RouteStar, frame: FigureFrame, incomplete: Boolean = false): StarToday = StarToday(
        frame.today.qtyBase, star.unitDivisor ?: 1L, frame.today.memoCount, frame.unconfirmedEarlier, star.skuIdsTruncated || incomplete,
    )

    /** The line of a row whose memos could not be read: dashes. */
    fun failedOf(star: RouteStar): StarToday = StarToday(0, star.unitDivisor ?: 1L, 0, 0, false, failed = true)

    /**
     * One line per Star row of [figures] (those with a `star`, in order; null where the row names no SKUs). A memo counts for a Star only when it belongs to the Star's route (`routeId` equal: the server's
     * STD selects `route_id = ANY(route)`, so a memo with no route is in no route's figure and on no route's line). [categories] is the category of EVERY SKU the phone holds (active or not); when the rule is a
     * category and a sale of this route after through_date has a SKU whose category the phone cannot resolve at all, it may belong to the programme: the line says "may be incomplete".
     */
    fun forRoutes(figures: List<RouteFigures>, categories: Map<Long, String>, memosOfMonth: (String) -> List<LocalMemo>, acked: Set<String>?): List<StarToday?> =
        figures.filter { it.star != null }.map { f ->
            val s = f.star!!
            val keep = skuFilter(s) { categories[it] } ?: return@map null
            try {
                val memos = memosOfMonth(s.month)
                val mine: (LocalMemo) -> Boolean = { f.routeId != null && it.routeId == f.routeId }
                val frame = FigureFrame.of(memos, s.month, s.throughDate, s.asOf, acked, memoIn = mine, lineIn = { keep(it.skuId) })
                val unresolved = s.skuIds.isNullOrEmpty() && memos.any { m ->
                    mine(m) && m.state != "rejected" && m.memoKind == "sale" && m.businessDate.take(7) == s.month && m.businessDate > s.throughDate && m.lines.any { it.lineKind == "sale" && it.skuId !in categories }
                }
                of(s, frame, unresolved)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { failedOf(s) }
        }

    /** The memo phrase of the line ("1 memo", "2 memos"): the count, or a dash when the memos could not be read. */
    fun memoCountText(t: StarToday, locale: UiLocale): String = if (t.failed) Percent.DASH else Formats.integer(t.memoCount, locale)

    /** [t]'s quantity in the programme unit as two decimals, rounded ONCE half up from the exact quotient ("2.50"); a dash when it cannot be computed (a divisor below 1, a negative quantity, an overflow). */
    fun qtyText(t: StarToday, locale: UiLocale): String {
        if (t.failed || t.divisor < 1L || t.qtyBase < 0L) return Percent.DASH
        val h = runCatching { Math.addExact(Math.multiplyExact(t.qtyBase, 200L), t.divisor) / Math.multiplyExact(t.divisor, 2L) }.getOrNull() ?: return Percent.DASH // hundredths, half up
        return Formats.integer(h / 100L, locale) + Formats.digits("." + (h % 100L).toString().padStart(2, '0'), locale)
    }
}
