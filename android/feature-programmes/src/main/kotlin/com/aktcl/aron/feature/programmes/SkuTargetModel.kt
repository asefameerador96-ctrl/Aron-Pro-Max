package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.EntryUnits
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.Qty
import com.aktcl.aron.rules.SkuProgress
import com.aktcl.aron.rules.UiLocale

/** The SKU facts the target rows need (from the bundle's SKU table): name, category, base unit and the pack that rule 5 counts in. */
data class SkuInfo(val skuId: Long, val name: String, val nameBn: String?, val categoryCode: String, val baseUnit: String, val basePerPack: Int, val entryUnitDefault: String)

/**
 * One SKU of screen 10 (Target and achievement by SKU). Every quantity is in THOUSANDTHS of the SKU's base unit (the server's Decimal3 target, the achieved base units times 1000), so nothing is rounded
 * before the display; [factor] converts it to the unit the field counts in (rule 5: packets of 20s or 10s, boxes of 25 lighters, dozens of matches; [unitKey] names it). The ratios are the shared
 * [SkuProgress] maths over the day counts the SERVER sent; they are null when the bundle has no calendar or a divisor is zero (a dash, never 0).
 */
data class SkuTargetRow(
    val skuId: Long, val name: String, val nameBn: String?, val categoryCode: String, val unitKey: String, val factor: Long,
    val targetMilli: Long, val achievedMilli: Long, val remainingMilli: Long, val achievementBp: Long?,
    val adsMilli: Long?, val tadsMilli: Long?, val padsMilli: Long?, val radsMilli: Long?,
)

/** The SKU target page of the route(s) of the bundle: the server figures as sent, with the closed day they cover. */
data class SkuTargetData(
    val rows: List<SkuTargetRow>, val month: String?, val throughDate: String?, val asOf: String?, val fromFirst: Boolean, val hasCalendar: Boolean, val truncated: Boolean,
)

object SkuTargetModel {
    /**
     * The rows from the parsed route figures ([figures]: one per route of the bundle; a SKU's target and achievement are summed over the routes) and the SKU table [skus]. A SKU with a target and no
     * sale has achieved 0 (the bundle omits it); a SKU the table does not know is skipped (never a nameless row). Null when no route has a target for any SKU.
     */
    fun build(figures: List<RouteFigures>, skus: Map<Long, SkuInfo>): SkuTargetData? {
        val targets = HashMap<Long, Long>(); val achieved = HashMap<Long, Long>()
        figures.forEach { f ->
            f.targets.forEach { t -> targets.merge(t.skuId, t.targetMilli, Long::plus) }
            f.achievement.forEach { a -> achieved.merge(a.skuId, a.qtyBase * 1000L, Long::plus) }
        }
        val calendar = figures.firstNotNullOfOrNull { it.calendar }
        val through = figures.mapNotNull { it.throughDate }.minOrNull()
        val month = figures.firstNotNullOfOrNull { it.month }
        val cal = calendar?.skuCalendar()
        val order = skus.keys.withIndex().associate { it.value to it.index } // the SKU table's own order (sort, code), as the reference lists them
        val rows = targets.entries.mapNotNull { (id, t) ->
            val k = skus[id] ?: return@mapNotNull null
            val spec = EntryUnits.resolve(k.baseUnit, k.basePerPack, k.entryUnitDefault)
            val a = achieved[id] ?: 0L
            // the ratios are the shared maths in WHOLE base units, exactly as the server and the fixtures compute them (target 500: TADS 36, RADS 45); only the display converts to packets, boxes and dozens
            val tBase = (t + 500L) / 1000L; val aBase = a / 1000L
            val r = if (cal != null) SkuProgress.row(tBase, aBase, cal) else null
            fun milli(v: Long?): Long? = v?.times(1000L)
            SkuTargetRow(
                id, k.name, k.nameBn, k.categoryCode, EntryUnits.labelKey(k.categoryCode, spec.unit), spec.factor.toLong(), t, a, (t - a).coerceAtLeast(0L),
                r?.achievementBp ?: Percent.bp(aBase, tBase), milli(r?.ads), milli(r?.tads), milli(r?.pads), milli(r?.rads),
            )
        }.sortedWith(compareBy({ categoryOrder(it.categoryCode) }, { order[it.skuId] ?: Int.MAX_VALUE }))
        if (rows.isEmpty()) return null
        return SkuTargetData(
            rows, month, through, figures.mapNotNull { f -> f.achievement.firstNotNullOfOrNull { it.asOf } ?: f.calendar?.asOf }.minOrNull(),
            fromFirst = month != null && through != null && through.take(7) < month, hasCalendar = calendar != null, truncated = figures.any { it.targetsTruncated },
        )
    }

    private fun categoryOrder(c: String) = when (c) { "cigarette" -> 0; "bidi" -> 1; "match" -> 2; "lighter" -> 3; else -> 4 }

    /**
     * The STD of the Home card: the achieved and target of the CIGARETTE SKUs in thousandths of base sticks (STD is the standard stick list; matches in dozens and lighters in pieces are not summed into
     * it), or null when there is no cigarette target (the card shows a dash, never a made-up figure).
     */
    fun homeStd(data: SkuTargetData?): Pair<Long, Long>? {
        val cig = data?.rows?.filter { it.categoryCode == "cigarette" }.orEmpty()
        if (cig.isEmpty()) return null
        return cig.sumOf { it.achievedMilli } to cig.sumOf { it.targetMilli }
    }

    /** [milli] thousandths of base units as entered units ("25", "3.5"), one decimal half up, trailing ".0" dropped, grouped, digits per [locale]. */
    fun qtyText(milli: Long?, factor: Long, locale: UiLocale): String {
        if (milli == null) return Percent.DASH
        val t = Qty.tenths(milli.coerceAtLeast(0L), factor * 1000L) ?: return Percent.DASH
        val frac = t % 10L
        return Formats.integer(t / 10L, locale) + if (frac != 0L) Formats.digits(".$frac", locale) else ""
    }

    /** The achievement as a whole percent, not capped ("104%"); a dash when the target is 0. */
    fun percentText(bp: Long?, locale: UiLocale): String = if (bp == null) Percent.DASH else Percent.text((bp + 50L) / 100L, 0, locale)
}
