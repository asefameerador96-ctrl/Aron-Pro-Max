package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.EntryUnits
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.QtyUnit
import com.aktcl.aron.rules.UiLocale

/** A figure in the unit the field counts in: [milli] thousandths of [unitKey] (packet, box, dozen) or, when the packs cannot be told, of the base unit (stick, piece). */
data class FieldQty(val milli: Long, val unitKey: String)

/** One KPI figure pair: target, achieved and the percentage the server sent (null: the owner has not defined it or the target is 0: a dash). */
data class KpiPair(val target: FieldQty, val achieved: FieldQty, val bp: Long?)

/** A brand row of the KPI: the volume and memo percentages, the volume target and the KPI of today (null until the owner defines it: a dash). */
data class KpiBrandRow(
    val brandId: Long, val name: BrandName?, val volumeTarget: FieldQty, val volumeBp: Long?, val memoTarget: Long, val memoBp: Long?, val kpiTodayMtk: Long?, val kpiAllocMtk: Long,
)

/** A BLM category row (match, lighter): target, achieved and percentage in the field unit (dozens of matches, boxes of 25 lighters). */
data class KpiCategoryRow(val categoryCode: String, val pair: KpiPair)

/**
 * Screen 60 and 61 (KPI Dashboard, Brand wise KPI) from the server's `RouteSnapshot.kpi` as sent, complete through [throughDate] (the figures rule; nothing local is ever added). Money the owner has not
 * defined ([achievedMtk], [achievementBp], [KpiBrandRow.kpiTodayMtk]) stays null: the screens show a dash in the manual's layout, never a number made up here. [daysElapsed] and [workingDays] come from the
 * calendar the server sent (the month card's "10 / 30"); null when the bundle has no calendar.
 */
data class KpiData(
    val month: String, val throughDate: String, val asOf: String?, val daysElapsed: Int?, val workingDays: Int?,
    val allocatedMtk: Long?, val achievedMtk: Long?, val achievementBp: Long?,
    val volume: KpiPair, val memo: KpiPair, val categories: List<KpiCategoryRow>,
    val cprTargetBp: Long?, val cprAchievedBp: Long?, val cprAllocMtk: Long?,
    val brands: List<KpiBrandRow>, val brandsTruncated: Boolean, val fromFirst: Boolean,
)

object KpiModel {
    /**
     * The KPI of the route(s) of the bundle. One route (the usual case) passes the server's percentages through; several routes are summed per brand and the percentages recomputed with the shared
     * [Percent.bp] (the CPR is shown only for a single route). Null when no route has a KPI section.
     */
    fun build(figures: List<RouteFigures>, brandNames: Map<Long, BrandName>): KpiData? {
        val routes = figures.filter { it.kpi != null }
        if (routes.isEmpty()) return null
        val ks = routes.map { it.kpi!! }
        val single = ks.size == 1
        val k = ks.first()
        fun bp(a: Long, t: Long, sent: Long?): Long? = if (single) sent else Percent.bp(a, t)
        val brandSums = LinkedHashMap<Long, MutableList<KpiBrand>>()
        ks.forEach { r -> r.brands.forEach { brandSums.getOrPut(it.brandId) { mutableListOf() } += it } }
        val packBrands = brandSums.values.map { merge(it) }
        val volT = ks.sumOf { it.volume.target }; val volA = ks.sumOf { it.volume.achieved }
        val memT = ks.sumOf { it.memo.target }; val memA = ks.sumOf { it.memo.achieved }
        val fromPacks = fieldTotals(packBrands, "packet", volT, volA)
        val cal = routes.firstNotNullOfOrNull { it.calendar }
        val cats = ks.flatMap { it.categories }.groupBy { it.categoryCode }.map { (code, rows) ->
            val t = rows.sumOf { it.volumeTarget }; val a = rows.sumOf { it.volumeAchieved }
            val packs = rows.flatMap { it.byPack }.groupBy { it.packFactor }.map { (f, l) -> PackFigure(f, l.sumOf { p -> p.target }, l.sumOf { p -> p.achieved }) }
            val unit = EntryUnits.labelKey(code, if (code == "match") QtyUnit.DOZEN else QtyUnit.PACK)
            KpiCategoryRow(code, pairFromPacks(packs, unit, t, a, bp(a, t, rows.first().volumeBp)))
        }.sortedBy { if (it.categoryCode == "match") 0 else 1 }
        val allocated = if (ks.all { it.allocatedMtk != null }) ks.sumOf { it.allocatedMtk!! } else null
        val achievedMtk = if (ks.all { it.achievedMtk != null }) ks.sumOf { it.achievedMtk!! } else null
        val achievementBp = if (single) k.achievementBp else if (achievedMtk != null && allocated != null) Percent.bp(achievedMtk, allocated) else null
        val through = ks.minOf { it.throughDate }
        return KpiData(
            k.month, through, ks.mapNotNull { it.asOf }.minOrNull(), cal?.dayCounts()?.elapsed, cal?.workingDays, allocated, achievedMtk, achievementBp,
            KpiPair(fromPacks.first, fromPacks.second, bp(volA, volT, k.volume.bp)), KpiPair(FieldQty(memT * 1000L, "memo"), FieldQty(memA * 1000L, "memo"), bp(memA, memT, k.memo.bp)), cats,
            if (single) k.cpr?.targetBp else null, if (single) k.cpr?.achievedBp else null, if (single) k.cpr?.allocMtk else null,
            packBrands.map { b ->
                val vt = brandQty(b)
                KpiBrandRow(b.brandId, brandNames[b.brandId], vt, bp(b.volumeAchieved, b.volumeTarget, b.volumeBp), b.memoTarget, bp(b.memoAchieved, b.memoTarget, b.memoBp), b.kpiTodayMtk, b.kpiAllocMtk)
            },
            ks.any { it.brandsTruncated }, through.take(7) < k.month,
        )
    }

    /** One brand summed over the routes that carry it (a single route returns it as sent). */
    private fun merge(rows: List<KpiBrand>): KpiBrand {
        if (rows.size == 1) return rows.first()
        val f = rows.first()
        val packs = rows.flatMap { it.byPack }.groupBy { it.packFactor }.map { (fac, l) -> PackFigure(fac, l.sumOf { it.target }, l.sumOf { it.achieved }) }
        return f.copy(
            volumeTarget = rows.sumOf { it.volumeTarget }, volumeAchieved = rows.sumOf { it.volumeAchieved }, memoTarget = rows.sumOf { it.memoTarget }, memoAchieved = rows.sumOf { it.memoAchieved },
            kpiAllocMtk = rows.sumOf { it.kpiAllocMtk }, kpiTodayMtk = if (rows.all { it.kpiTodayMtk != null }) rows.sumOf { it.kpiTodayMtk!! } else null, byPack = packs,
        )
    }

    /** Thousandths of [unit] from the per-pack figures: each pack size divided by its own factor, then added (never a stick sum of mixed pack sizes); base units when the packs do not add up to the figure. */
    private fun pairFromPacks(packs: List<PackFigure>, unit: String, target: Long, achieved: Long, bp: Long?): KpiPair {
        val exact = packs.isNotEmpty() && packs.sumOf { it.target } == target && packs.sumOf { it.achieved } == achieved
        if (!exact) return KpiPair(FieldQty(target * 1000L, baseUnitOf(unit)), FieldQty(achieved * 1000L, baseUnitOf(unit)), bp)
        return KpiPair(FieldQty(packs.sumOf { it.target * 1000L / it.packFactor }, unit), FieldQty(packs.sumOf { it.achieved * 1000L / it.packFactor }, unit), bp)
    }

    private fun baseUnitOf(unit: String) = when (unit) { "packet" -> "stick"; "box" -> "piece"; else -> unit }

    private fun fieldTotals(brands: List<KpiBrand>, unit: String, t: Long, a: Long): Pair<FieldQty, FieldQty> {
        val packs = brands.flatMap { it.byPack }.groupBy { it.packFactor }.map { (f, l) -> PackFigure(f, l.sumOf { p -> p.target }, l.sumOf { p -> p.achieved }) }
        val p = pairFromPacks(packs, unit, t, a, null)
        return p.target to p.achieved
    }

    private fun brandQty(b: KpiBrand): FieldQty = pairFromPacks(b.byPack, "packet", b.volumeTarget, b.volumeAchieved, null).target

    /** [q] as text ("73,900", "1.5"), one decimal half up, trailing ".0" dropped, digits per [locale]. */
    fun qtyText(q: FieldQty, locale: UiLocale): String = SkuTargetModel.qtyText(q.milli, 1L, locale)

    /** Whole taka from milli-taka with the sign in front ("৳5,700"); a dash when null (the owner has not defined it). */
    fun takaText(mtk: Long?, locale: UiLocale): String = if (mtk == null) Percent.DASH else "৳" + Formats.digits(com.aktcl.aron.rules.Money.formatTaka(mtk, 0), locale)

    /** A percentage in basis points as "29.63%"; a dash when null. */
    fun bpText(bp: Long?, locale: UiLocale): String = Percent.text(bp, 2, locale)

    /** The bar fill (0..1000 per mille), capped at the full bar; null when there is no percentage. */
    fun fillPerMille(bp: Long?): Int? = bp?.let { (it / 10L).coerceIn(0L, 1000L).toInt() }
}
