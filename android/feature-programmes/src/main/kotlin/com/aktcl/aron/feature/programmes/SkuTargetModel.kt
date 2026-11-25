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
 * before the display; [factor] converts it to the unit the field counts in (rule 5: packets of 20s or 10s, boxes of 25 lighters, dozens of matches; [unitKey] names it). NOTHING here is rounded
 * before the display: the percentage is the whole percent of the raw achieved over the raw target ([Percent.whole], once), and the daily figures (ADS, TADS, PADS, RADS) are the exact quotient of the raw
 * figure by the day count the SERVER sent ([elapsedDays], [remainingDays], read through [SkuProgress.dayCounts]) and the unit factor, rounded ONCE at display in the unit shown ([perDayText]).
 * A figure whose divisor is 0 or whose calendar is missing is a dash, never 0. A negative achieved quantity (not a valid figure: a server question) is a dash for achieved, left, % and the daily
 * figures, never a crash and never a made-up number; a negative target gives no row.
 */
data class SkuTargetRow(
    val skuId: Long, val name: String, val nameBn: String?, val categoryCode: String, val unitKey: String, val factor: Long,
    val targetMilli: Long, val achievedMilli: Long?, val remainingMilli: Long?, val achievementPct: Long?,
    val elapsedDays: Int?, val remainingDays: Int?,
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
            f.targets.filter { it.targetMilli >= 0L }.forEach { t -> targets.merge(t.skuId, t.targetMilli, Long::plus) }
            f.achievement.forEach { a -> achieved.merge(a.skuId, a.qtyBase * 1000L, Long::plus) }
        }
        val calendar = figures.firstNotNullOfOrNull { it.calendar }
        val through = figures.mapNotNull { it.throughDate }.minOrNull()
        val month = figures.firstNotNullOfOrNull { it.month }
        val counts = calendar?.dayCounts() // the day counts exactly as the server sent them, through the one shared convention
        val order = skus.keys.withIndex().associate { it.value to it.index } // the SKU table's own order (sort, code), as the reference lists them
        val rows = targets.entries.mapNotNull { (id, t) ->
            val k = skus[id] ?: return@mapNotNull null
            val spec = EntryUnits.resolve(k.baseUnit, k.basePerPack, k.entryUnitDefault)
            val a = (achieved[id] ?: 0L).takeIf { it >= 0L } // a negative achieved quantity is not a figure: null (a dash)
            SkuTargetRow(
                id, k.name, k.nameBn, k.categoryCode, EntryUnits.labelKey(k.categoryCode, spec.unit), spec.factor.toLong(), t, a, a?.let { (t - it).coerceAtLeast(0L) },
                if (t > 0L && a != null) Percent.whole(a, t) else null, counts?.elapsed, counts?.remaining,
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
    fun homeStd(data: SkuTargetData?): Pair<Long?, Long>? {
        if (data == null || data.truncated) return null // a cut list gives a partial total: a dash, not a wrong percentage
        val cig = data?.rows?.filter { it.categoryCode == "cigarette" }.orEmpty()
        if (cig.isEmpty()) return null
        // an invalid (negative) achieved figure makes the achieved total a dash (null) but the valid target stays: never a partial total
        return (if (cig.any { it.achievedMilli == null }) null else cig.sumOf { it.achievedMilli!! }) to cig.sumOf { it.targetMilli }
    }

    /** [milli] thousandths of base units as entered units ("25", "3.5"), one decimal half up, trailing ".0" dropped, grouped, digits per [locale]. */
    fun qtyText(milli: Long?, factor: Long, locale: UiLocale): String {
        if (milli == null) return Percent.DASH
        val t = Qty.tenths(milli.coerceAtLeast(0L), factor * 1000L) ?: return Percent.DASH
        val frac = t % 10L
        return Formats.integer(t / 10L, locale) + if (frac != 0L) Formats.digits(".$frac", locale) else ""
    }

    /** The achievement as a whole percent, not capped ("104%"); a dash when the target is 0. [pct] is already the single rounding of the raw figures. */
    fun percentText(pct: Long?, locale: UiLocale): String = Percent.text(pct, 0, locale)

    /**
     * A daily figure: [numMilli] (thousandths of base units) over [days] working days and the unit [factor], as one exact quotient rounded once to one decimal in the unit shown ("1.7"); a dash when
     * [days] is null or 0.
     */
    fun perDayText(numMilli: Long?, days: Int?, factor: Long, locale: UiLocale): String {
        if (numMilli == null || days == null || days <= 0) return Percent.DASH
        val t = Qty.tenths(numMilli.coerceAtLeast(0L), days.toLong() * factor * 1000L) ?: return Percent.DASH
        val frac = t % 10L
        return Formats.integer(t / 10L, locale) + if (frac != 0L) Formats.digits(".$frac", locale) else ""
    }
}
