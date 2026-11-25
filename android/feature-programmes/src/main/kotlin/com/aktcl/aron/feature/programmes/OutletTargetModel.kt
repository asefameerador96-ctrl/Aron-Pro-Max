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

/** What this phone sold of one category since the closed day, in the category's own unit (an exact numerator over [den], like [OutletTargetTotal]): never one number across categories. */
data class OutletTodayCategory(val categoryCode: String, val unitKey: String, val num: Long, val den: Long)

/**
 * One campaign of the outlet: the server's STD and memo figures as sent (through [throughDate]), the SKU table, and the phone's own lines kept separate ([today] for the memo count, [todayByCategory] for
 * the quantities, [unconfirmedEarlier], [forkLeftOut]). [incomplete] is true when the bundle cut the list or a SKU of the target is not on this phone (the category totals are then a dash).
 */
data class OutletCampaignView(
    val programmeId: Long, val labelEn: String, val labelBn: String?, val month: String, val throughDate: String, val asOf: String?, val fromFirst: Boolean,
    val std: CampaignFigure, val memo: CampaignFigure, val rows: List<OutletTargetSku>, val totals: List<OutletTargetTotal>, val skuMissing: Boolean,
    val today: LocalToday, val unconfirmedEarlier: Int, val forkLeftOut: Int,
    val todayByCategory: List<OutletTodayCategory> = emptyList(), val incomplete: Boolean = skuMissing,
)

/** The campaigns of one outlet; [truncated] when the bundle cut its campaign list (an outlet with none may still have one: the empty state says so). */
data class OutletTargetData(val campaigns: List<OutletCampaignView>, val truncated: Boolean)

object OutletTargetModel {
    /**
     * The campaigns of [outletId] from the parsed route figures ([figures]: one per route of the bundle) and the SKU table [skus]. [frameOf] gives the figure frame of this phone's memos for one campaign
     * (the outlet's memos, lines of the SKUs the filter keeps), so nothing local is ever added into a server figure. Null when the bundle holds no campaign for the outlet and did not cut its list.
     */
    fun build(figures: List<RouteFigures>, outletId: Long, skus: Map<Long, SkuInfo>, frameOf: (RouteCampaign, (Long) -> Boolean) -> FigureFrame): OutletTargetData? {
        val truncated = figures.any { it.campaignsTruncated }
        // the same outlet on two routes carries the same figure twice: one view per programme
        val mine = figures.flatMap { it.campaigns }.filter { it.outletId == outletId }.distinctBy { it.programmeId to it.month }
        if (mine.isEmpty()) return if (truncated) OutletTargetData(emptyList(), true) else null
        val order = skus.keys.withIndex().associate { it.value to it.index }
        val views = mine.sortedBy { it.programmeId }.map { c ->
            // a SKU the phone does not know, a negative quantity or a unit rule that fails drops THAT row only (never the whole read); the list is then incomplete and its totals are a dash
            val rows = c.skus.mapNotNull { s -> row(s, skus) }.sortedWith(compareBy({ categoryOrder(it.categoryCode) }, { order[it.skuId] ?: Int.MAX_VALUE }))
            val skuMissing = rows.size != c.skus.size
            val incomplete = skuMissing || truncated
            val frame = frameOf(c) { true }
            val todayCats = rows.groupBy { it.categoryCode }.mapNotNull { (code, rs) ->
                val den = lcm(rs) ?: return@mapNotNull null
                val num = runCatching {
                    rs.fold(0L) { acc, r -> Math.addExact(acc, Math.multiplyExact(frameOf(c) { id -> id == r.skuId }.today.qtyBase * 1000L, den / r.factor)) }
                }.getOrNull() ?: return@mapNotNull null
                if (num == 0L) null else OutletTodayCategory(code, rs.first().unitKey, num, den)
            }.sortedBy { categoryOrder(it.categoryCode) }
            OutletCampaignView(
                c.programmeId, c.labelEn, c.labelBn, c.month, c.throughDate, c.asOf, c.throughDate.take(7) < c.month,
                c.std, c.memo, rows, totals(rows, incomplete), skuMissing, frame.today, frame.unconfirmedEarlier, frame.forkLeftOut, todayCats, incomplete,
            )
        }
        return OutletTargetData(views, truncated)
    }

    /** One SKU row, or null when the phone cannot show it honestly (unknown SKU, negative quantity, a unit rule that throws). */
    private fun row(s: CampaignSku, skus: Map<Long, SkuInfo>): OutletTargetSku? {
        val k = skus[s.skuId] ?: return null
        if (s.targetQtyBase < 0L || s.soldQtyBase < 0L) return null
        val spec = runCatching { EntryUnits.resolve(k.baseUnit, k.basePerPack, k.entryUnitDefault) }.getOrNull() ?: return null
        if (spec.factor <= 0) return null
        val t = runCatching { Math.multiplyExact(s.targetQtyBase, 1000L) }.getOrNull() ?: return null
        val a = runCatching { Math.multiplyExact(s.soldQtyBase, 1000L) }.getOrNull() ?: return null
        return OutletTargetSku(s.skuId, k.name, k.nameBn, k.categoryCode, EntryUnits.labelKey(k.categoryCode, spec.unit), spec.factor.toLong(), t, a, if (t > 0L) Percent.whole(a, t) else null)
    }

    private fun lcm(rs: List<OutletTargetSku>): Long? = runCatching { rs.fold(1L) { acc, r -> Math.multiplyExact(acc / gcd(acc, r.factor), r.factor) } }.getOrNull()

    private fun categoryOrder(c: String) = when (c) { "cigarette" -> 0; "bidi" -> 1; "lighter" -> 2; "match" -> 3; else -> 4 }

    /** One total per category (cigarette, lighter, match as the manual lists them); a pack-size sum that overflows is a dash, never a wrong number. */
    private fun totals(rows: List<OutletTargetSku>, incomplete: Boolean): List<OutletTargetTotal> =
        rows.groupBy { it.categoryCode }.map { (code, rs) ->
            val unit = rs.first().unitKey
            val den = lcm(rs) ?: 1L
            fun sum(pick: (OutletTargetSku) -> Long): Long? = if (incomplete || lcm(rs) == null || rs.any { it.unitKey != unit }) null else runCatching {
                rs.fold(0L) { s, r -> Math.addExact(s, Math.multiplyExact(pick(r), den / r.factor)) }
            }.getOrNull()
            val tBase = rs.sumOf { it.targetMilli }; val aBase = rs.sumOf { it.soldMilli }
            OutletTargetTotal(code, unit, sum { it.targetMilli }, sum { it.soldMilli }, den, if (!incomplete && tBase > 0L) Percent.whole(aBase, tBase) else null)
        }.sortedBy { categoryOrder(it.categoryCode) }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /** [milli] thousandths of base units in entered units ("25", "3.5"): one decimal half up, ".0" dropped, grouped, digits per [locale]. A null is a dash. */
    fun qtyText(milli: Long?, factor: Long, locale: UiLocale): String = SkuTargetModel.qtyText(milli, factor, locale)

    /** A category total (numerator over the common denominator, thousandths of base units) as entered units, one decimal half up; a dash when not computable (or when the arithmetic would overflow). */
    fun totalText(num: Long?, den: Long, locale: UiLocale): String {
        if (num == null) return Percent.DASH
        val d = runCatching { Math.multiplyExact(den, 1000L) }.getOrNull() ?: return Percent.DASH
        val t = Qty.tenths(num.coerceAtLeast(0L), d) ?: return Percent.DASH
        val frac = t % 10L
        return Formats.integer(t / 10L, locale) + if (frac != 0L) Formats.digits(".$frac", locale) else ""
    }

    /** A STD or memo count of the server (whole base units or memos), grouped, digits per [locale]. */
    fun countText(n: Long, locale: UiLocale): String = Formats.integer(n, locale)

    fun percentText(pct: Long?, locale: UiLocale): String = Percent.text(pct, 0, locale)
}
