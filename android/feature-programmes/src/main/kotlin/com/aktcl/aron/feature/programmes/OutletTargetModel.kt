package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.EntryUnits
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.Qty
import com.aktcl.aron.rules.UiLocale

/**
 * One SKU of the outlet target page (screen 21, right). Quantities are in THOUSANDTHS of the SKU's base unit; [factor] converts them to the unit the field counts in ([unitKey]: packets of 20s or 10s,
 * dozens of matches, boxes of 25 lighters). The percentage is the single rounding of the raw sold over the raw target ([Percent.whole]); a target of 0 is a dash, never 0 %.
 */
data class OutletTargetSku(
    val skuId: Long, val name: String, val nameBn: String?, val categoryCode: String, val unitKey: String, val factor: Long, val targetMilli: Long, val soldMilli: Long, val pct: Long?,
)

/**
 * A category total under the SKU table (Cigarette, Lighter, Match): the exact sum of the SKU quantities in the category's own unit ([unitKey]), as one numerator over [den] (the common denominator of the
 * pack sizes), so nothing is rounded before the display and different units are never added (a category has one unit; a null total is a dash). [pct] is the whole percent of the raw base-unit sums.
 */
data class OutletTargetTotal(val categoryCode: String, val unitKey: String, val targetNum: Long?, val soldNum: Long?, val den: Long, val pct: Long?)

/** One campaign of the outlet: the server's STD and memo figures as sent (through [throughDate]), the SKU table, and the phone's own lines kept separate ([today], [unconfirmedEarlier], [forkLeftOut]). */
data class OutletCampaignView(
    val programmeId: Long, val labelEn: String, val labelBn: String?, val month: String, val throughDate: String, val asOf: String?, val fromFirst: Boolean,
    val std: CampaignFigure, val memo: CampaignFigure, val rows: List<OutletTargetSku>, val totals: List<OutletTargetTotal>, val skuMissing: Boolean,
    val today: LocalToday, val unconfirmedEarlier: Int, val forkLeftOut: Int,
)

/** The campaigns of one outlet; [truncated] when the bundle cut its campaign list (an outlet with none may still have one: the empty state says so). */
data class OutletTargetData(val campaigns: List<OutletCampaignView>, val truncated: Boolean)

object OutletTargetModel {
    /**
     * The campaigns of [outletId] from the parsed route figures ([figures]: one per route of the bundle) and the SKU table [skus]. [frameOf] gives the figure frame of this phone's memos for one campaign
     * (the outlet's memos, lines of the campaign's SKUs), so nothing local is ever added into a server figure. Null when the bundle holds no campaign for the outlet and did not cut its list.
     */
    fun build(figures: List<RouteFigures>, outletId: Long, skus: Map<Long, SkuInfo>, frameOf: (RouteCampaign) -> FigureFrame): OutletTargetData? {
        val truncated = figures.any { it.campaignsTruncated }
        // the same outlet on two routes carries the same figure twice: one view per programme
        val mine = figures.flatMap { it.campaigns }.filter { it.outletId == outletId }.distinctBy { it.programmeId to it.month }
        if (mine.isEmpty()) return if (truncated) OutletTargetData(emptyList(), true) else null
        val order = skus.keys.withIndex().associate { it.value to it.index }
        val views = mine.sortedBy { it.programmeId }.map { c ->
            val known = c.skus.filter { skus.containsKey(it.skuId) }
            val rows = known.map { s ->
                val k = skus.getValue(s.skuId)
                val spec = EntryUnits.resolve(k.baseUnit, k.basePerPack, k.entryUnitDefault)
                val t = s.targetQtyBase * 1000L; val a = s.soldQtyBase * 1000L
                OutletTargetSku(s.skuId, k.name, k.nameBn, k.categoryCode, EntryUnits.labelKey(k.categoryCode, spec.unit), spec.factor.toLong(), t, a, if (t > 0L) Percent.whole(a, t) else null)
            }.sortedWith(compareBy({ categoryOrder(it.categoryCode) }, { order[it.skuId] ?: Int.MAX_VALUE }))
            val frame = frameOf(c)
            OutletCampaignView(
                c.programmeId, c.labelEn, c.labelBn, c.month, c.throughDate, c.asOf, c.throughDate.take(7) < c.month,
                c.std, c.memo, rows, totals(rows), known.size != c.skus.size, frame.today, frame.unconfirmedEarlier, frame.forkLeftOut,
            )
        }
        return OutletTargetData(views, truncated)
    }

    private fun categoryOrder(c: String) = when (c) { "cigarette" -> 0; "bidi" -> 1; "lighter" -> 2; "match" -> 3; else -> 4 }

    /** One total per category (cigarette, lighter, match as the manual lists them); a pack-size sum that overflows is a dash, never a wrong number. */
    private fun totals(rows: List<OutletTargetSku>): List<OutletTargetTotal> =
        rows.groupBy { it.categoryCode }.map { (code, rs) ->
            val unit = rs.first().unitKey
            val den = runCatching { rs.fold(1L) { acc, r -> Math.multiplyExact(acc / gcd(acc, r.factor), r.factor) } }.getOrNull() ?: 1L
            fun sum(pick: (OutletTargetSku) -> Long): Long? = if (rs.any { it.unitKey != unit }) null else runCatching {
                rs.fold(0L) { s, r -> Math.addExact(s, Math.multiplyExact(pick(r), den / r.factor)) }
            }.getOrNull()
            val tBase = rs.sumOf { it.targetMilli }; val aBase = rs.sumOf { it.soldMilli }
            OutletTargetTotal(code, unit, sum { it.targetMilli }, sum { it.soldMilli }, den, if (tBase > 0L) Percent.whole(aBase, tBase) else null)
        }.sortedBy { categoryOrder(it.categoryCode) }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /** [milli] thousandths of base units in entered units ("25", "3.5"): one decimal half up, ".0" dropped, grouped, digits per [locale]. A null is a dash. */
    fun qtyText(milli: Long?, factor: Long, locale: UiLocale): String = SkuTargetModel.qtyText(milli, factor, locale)

    /** A category total (numerator over the common denominator, thousandths of base units) as entered units, one decimal half up; a dash when not computable. */
    fun totalText(num: Long?, den: Long, locale: UiLocale): String {
        if (num == null) return Percent.DASH
        val t = Qty.tenths(num.coerceAtLeast(0L), den * 1000L) ?: return Percent.DASH
        val frac = t % 10L
        return Formats.integer(t / 10L, locale) + if (frac != 0L) Formats.digits(".$frac", locale) else ""
    }

    /** A STD or memo count of the server (whole base units or memos), grouped, digits per [locale]. */
    fun countText(n: Long, locale: UiLocale): String = Formats.integer(n, locale)

    fun percentText(pct: Long?, locale: UiLocale): String = Percent.text(pct, 0, locale)
}
