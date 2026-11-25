package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.Qty
import com.aktcl.aron.rules.UiLocale

/**
 * A figure in the unit the field counts in, kept as an EXACT quotient: [num] / [den] of [unitKey] (packet, box, dozen) or, when the packs cannot be told, of the base unit (stick, piece). The default
 * [den] of 1000 makes [num] thousandths. Nothing is rounded before the display ([KpiModel.qtyText] rounds once, to one decimal).
 */
data class FieldQty(val milli: Long, val unitKey: String, val den: Long = 1000L) {
    val isPositive: Boolean get() = milli > 0L
}

/** One KPI figure pair: target, achieved and the percentage the server sent (null: the owner has not defined it or the target is 0: a dash). */
data class KpiPair(val target: FieldQty, val achieved: FieldQty, val bp: Long?)

/**
 * A brand row of the KPI. [category] is the brand's category from the product tree the phone already has (`cigarette`, `match`, `lighter`...), or null when the brand mixes categories or the phone cannot
 * resolve it: then [volumeTarget] is null (a dash with a note, never a number in a unit that may be wrong). The memo figures are counts and need no unit. KPI today is null until the owner defines it.
 */
data class KpiBrandRow(
    val brandId: Long, val name: BrandName?, val category: String?, val volumeTarget: FieldQty?, val volumeBp: Long?, val memoTarget: Long, val memoBp: Long?, val kpiTodayMtk: Long?, val kpiAllocMtk: Long,
    /** The category the product tree shows for the brand's visible SKUs, whether or not the brand's volume could be accounted for ([category] is null then). */
    val visibleCategory: String? = null,
    /**
     * Set only when the server sent `category_code` on every by_pack entry of the brand (contract 1.25.0): the brand's volume per category, each in its own unit, in category order. A mixed brand then shows
     * every category instead of "unit not clear". Empty from an older server (the product-tree rule applies, unchanged).
     */
    val categoryVolumes: List<KpiCategoryVolume> = emptyList(),
)

/** One category of a brand's volume (a 1.25.0 server): [target] in the field unit, null where the unit is pending the owner (bidi) or the figure is not a valid quantity. */
data class KpiCategoryVolume(val categoryCode: String, val target: FieldQty?)

/** A BLM category row (match, lighter): target, achieved and percentage in the field unit (dozens of matches, boxes of 25 lighters). */
data class KpiCategoryRow(val categoryCode: String, val pair: KpiPair)

/**
 * Screen 60 and 61 (KPI Dashboard, Brand wise KPI) from the server's `RouteSnapshot.kpi` as sent, complete through [throughDate] (the figures rule; nothing local is ever added). Money the owner has not
 * defined ([achievedMtk], [achievementBp], [KpiBrandRow.kpiTodayMtk]) stays null: the screens show a dash in the manual's layout, never a number made up here. [daysElapsed] and [workingDays] come from the
 * calendar the server sent (the month card's "10 / 30"); null when the bundle has no calendar. The cigarette volume list ([volumeBrands]) holds the cigarette brands and the brands whose category the
 * phone cannot resolve (a dash and a note); match and lighter brands are not in it (their volume is the BLM categories).
 */
data class KpiData(
    val month: String, val throughDate: String, val asOf: String?, val daysElapsed: Int?, val workingDays: Int?,
    val allocatedMtk: Long?, val achievedMtk: Long?, val achievementBp: Long?,
    val volume: KpiPair, val memo: KpiPair, val categories: List<KpiCategoryRow>,
    val cprTargetBp: Long?, val cprAchievedBp: Long?, val cprAllocMtk: Long?,
    val brands: List<KpiBrandRow>, val brandsTruncated: Boolean, val fromFirst: Boolean,
    /** Several routes and at least one has no KPI section or a cut brand list: the summed percentages would not be complete, so they are dashes and the page says so (never a partial figure presented as the server's). */
    val incomplete: Boolean = false,
) {
    /**
     * The brands of the cigarette volume list: cigarette brands, and brands whose unit the phone cannot tell (a dash and a note) unless the tree says their visible SKUs are matches, lighters or bidi: such a
     * brand is not listed under cigarette sales.
     */
    /** True when the cigarette volume list leaves out a brand whose SKUs are matches, lighters or bidi and whose figures the phone cannot match to its tree: the screen says so in a short note. */
    val volumeLeftOut: Boolean get() = brands.any { it.categoryVolumes.isEmpty() && it.category == null && it.visibleCategory != null && it.visibleCategory != "cigarette" }
    val volumeBrands: List<KpiBrandRow> get() = brands.filter {
        // a brand with the server's per-category figures is listed under cigarette sales when it HAS cigarette volume (a match-only or lighter-only brand is not); an older server: the tree rule
        if (it.categoryVolumes.isNotEmpty()) it.categoryVolumes.any { c -> c.categoryCode == "cigarette" }
        else it.category == "cigarette" || (it.category == null && (it.visibleCategory == null || it.visibleCategory == "cigarette"))
    }
}

object KpiModel {
    /**
     * The KPI of the route(s) of the bundle. One route (the usual case) passes the server's percentages through; several routes are summed per brand and the percentages recomputed with the shared
     * [Percent.bp] of the raw sums (the CPR is shown only for a single route). [brandCategory] is brand id to the one category of its SKUs ([AsthaModel.brandCategories]; a mixed or unknown brand is absent).
     * Null when no route has a KPI section.
     */
    fun build(figures: List<RouteFigures>, brandNames: Map<Long, BrandName>, brandCategory: Map<Long, String> = emptyMap(), skuBrand: Map<Long, Long> = emptyMap()): KpiData? {
        val routes = figures.filter { it.kpi != null }
        if (routes.isEmpty()) return null
        val ks = routes.map { it.kpi!! }
        val single = figures.size == 1 // one route in the bundle: the server's figures as sent; any other count recomputes from the raw sums
        val k = ks.first()
        // several routes: the percentage is Percent.bp of the raw summed figures, once; it is shown only when every route of the bundle has its KPI and no brand list was cut
        val incomplete = !single && (figures.size != routes.size || ks.any { it.brandsTruncated })
        // a negative figure is not a valid one (a server question): no percentage, the same for one route and for several
        fun bp(a: Long, t: Long, sent: Long?): Long? = if (a < 0L || t < 0L) null else if (single) sent else if (incomplete) null else Percent.bp(a, t)
        val brandSums = LinkedHashMap<Long, MutableList<KpiBrand>>()
        ks.forEach { r -> r.brands.forEach { brandSums.getOrPut(it.brandId) { mutableListOf() } += it } }
        val merged = brandSums.values.map { merge(it) }
        // a brand's category counts only when the phone's product tree accounts for ALL of the brand's volume: the SKUs of the route that the tree maps to the brand must add up to the brand's sent target and
        // achieved (a SKU the tree cannot map, such as an inactive variant, may be of another category): else the brand is unresolved (no unit, no percentage)
        // a 1.25.0 server tags every by_pack entry with its SKU category: then the brand's categories come from the entries, not the tree (an inactive variant needs no tree entry); no tag: the tree rule below
        val coded = merged.associate { b -> b.brandId to codedCategories(b) }
        val resolved = merged.associate { b ->
            val c = coded[b.brandId]
            b.brandId to if (c != null) c.keys.singleOrNull() else resolvedCategory(b, brandCategory[b.brandId], figures, skuBrand)
        }
        val volT = ks.sumOf { it.volume.target }; val volA = ks.sumOf { it.volume.achieved }
        val memT = ks.sumOf { it.memo.target }; val memA = ks.sumOf { it.memo.achieved }
        // the server's volume total is the CIGARETTE brands': the packet check uses the cigarette brands' packs only (never the packs of a match or lighter brand)
        // (a coded brand adds its cigarette entries only, so a mixed brand's matches never reach the packet check)
        val cigarettePacks = merged.flatMap { b -> coded[b.brandId]?.get("cigarette") ?: if (coded[b.brandId] == null && resolved[b.brandId] == "cigarette") b.byPack else emptyList() }
            .groupBy { it.packFactor }.map { (f, l) -> PackFigure(f, l.sumOf { p -> p.target }, l.sumOf { p -> p.achieved }) }
        val volume = pairFromPacks(cigarettePacks, "packet", volT, volA, bp(volA, volT, k.volume.bp))
        val cal = routes.firstNotNullOfOrNull { it.calendar }
        val cats = ks.flatMap { it.categories }.groupBy { it.categoryCode }.map { (code, rows) ->
            val t = rows.sumOf { it.volumeTarget }; val a = rows.sumOf { it.volumeAchieved }
            val packs = rows.flatMap { it.byPack }.groupBy { it.packFactor }.map { (f, l) -> PackFigure(f, l.sumOf { p -> p.target }, l.sumOf { p -> p.achieved }) }
            val unit = if (code == "match") "dozen" else "box"
            KpiCategoryRow(code, pairFromPacks(packs, unit, t, a, bp(a, t, rows.first().volumeBp)))
        }.sortedBy { if (it.categoryCode == "match") 0 else 1 }
        val allocated = if (ks.all { it.allocatedMtk != null }) ks.sumOf { it.allocatedMtk!! } else null
        val achievedMtk = if (ks.all { it.achievedMtk != null }) ks.sumOf { it.achievedMtk!! } else null
        val achievementBp = if (single) k.achievementBp else if (!incomplete && achievedMtk != null && allocated != null) Percent.bp(achievedMtk, allocated) else null
        val through = ks.minOf { it.throughDate }
        return KpiData(
            k.month, through, ks.mapNotNull { it.asOf }.minOrNull(), cal?.dayCounts()?.elapsed, cal?.workingDays, allocated, achievedMtk, achievementBp,
            volume, KpiPair(FieldQty(memT * 1000L, "memo"), FieldQty(memA * 1000L, "memo"), bp(memA, memT, k.memo.bp)), cats,
            if (single) k.cpr?.targetBp else null, if (single) k.cpr?.achievedBp else null, if (single) k.cpr?.allocMtk else null,
            merged.map { b ->
                val cat = resolved[b.brandId]
                // a brand with no single known category has no unit and so no volume percentage either (the figure may mix sticks and dozens)
                val byCat = coded[b.brandId]
                // coded brand: one category = its unit and the server's percentage; several = a figure per category (the one percentage would add sticks to dozens, so it is a dash)
                val volume = if (byCat != null) byCat[cat]?.let { brandVolume(b.copy(byPack = it), cat) } else brandVolume(b, cat)
                KpiBrandRow(b.brandId, brandNames[b.brandId], cat, volume, if (cat == null) null else bp(b.volumeAchieved, b.volumeTarget, b.volumeBp), b.memoTarget, bp(b.memoAchieved, b.memoTarget, b.memoBp), b.kpiTodayMtk, b.kpiAllocMtk, brandCategory[b.brandId],
                    byCat?.map { (code, packs) -> KpiCategoryVolume(code, categoryVolume(code, packs)) }.orEmpty())
            },
            ks.any { it.brandsTruncated }, through.take(7) < k.month, incomplete,
        )
    }

    /**
     * [category] when the SKUs of the route(s) that the product tree maps to the brand add up to the brand's sent volume (target and achieved, exactly), else null.
     * With no tree ([skuBrand] empty) the category is taken as given.
     */
    private fun resolvedCategory(b: KpiBrand, category: String?, figures: List<RouteFigures>, skuBrand: Map<Long, Long>): String? {
        category ?: return null
        if (skuBrand.isEmpty()) return category
        val skus = skuBrand.filterValues { it == b.brandId }.keys
        val targetMilli = figures.sumOf { f -> f.targets.filter { it.skuId in skus }.sumOf { it.targetMilli } }
        val achieved = figures.sumOf { f -> f.achievement.filter { it.skuId in skus }.sumOf { it.qtyBase } }
        // the server's figures are exact integers of base units (std_target is the Decimal3 of qty_base and the brand target is the sum of the same quantities), so ANY gap is an unmapped SKU: tolerance 0, no half-unit allowance.
        return if (targetMilli == b.volumeTarget * 1000L && achieved == b.volumeAchieved) category else null
    }

    /**
     * The brand's by_pack entries grouped by their category, in category order, when EVERY entry carries `category_code` and the entries add up to the brand's sent volume (target and achieved); else null (an
     * older server, or figures that do not add up: the tree rule decides, never a guess).
     */
    private fun codedCategories(b: KpiBrand): Map<String, List<PackFigure>>? {
        if (b.byPack.isEmpty() || b.byPack.any { it.categoryCode == null }) return null
        if (b.byPack.sumOf { it.target } != b.volumeTarget || b.byPack.sumOf { it.achieved } != b.volumeAchieved) return null
        return b.byPack.groupBy { it.categoryCode!! }.entries.sortedBy { categoryRank(it.key) }.associate { it.key to it.value } // a LinkedHashMap in category order
    }

    private fun categoryRank(code: String) = when (code) { "cigarette" -> 0; "match" -> 1; "lighter" -> 2; else -> 3 }

    /** A category's target in its own unit (cigarette packets, match dozens, lighter boxes); null for bidi (unit pending the owner) or any other code. */
    private fun categoryVolume(code: String, packs: List<PackFigure>): FieldQty? {
        val t = packs.sumOf { it.target }; val a = packs.sumOf { it.achieved }
        return when (code) {
            "cigarette" -> pairFromPacks(packs, "packet", t, a, null).target
            "match" -> FieldQty(t * 1000L, "dozen")
            // a box is 25 pieces: another pack size is shown in pieces with their word, never as boxes
            "lighter" -> if (packs.all { it.packFactor == 25 }) pairFromPacks(packs, "box", t, a, null).target else FieldQty(t * 1000L, "piece")
            else -> null // bidi (unit pending the owner) or an unknown category: the screen prints the category with a dash, never leaves it out
        }
    }

    /** One brand summed over the routes that carry it (a single route returns it as sent). */
    private fun merge(rows: List<KpiBrand>): KpiBrand {
        if (rows.size == 1) return rows.first()
        val f = rows.first()
        val packs = rows.flatMap { it.byPack }.groupBy { it.categoryCode to it.packFactor }.map { (key, l) -> PackFigure(key.second, l.sumOf { it.target }, l.sumOf { it.achieved }, key.first) }
        return f.copy(
            volumeTarget = rows.sumOf { it.volumeTarget }, volumeAchieved = rows.sumOf { it.volumeAchieved }, memoTarget = rows.sumOf { it.memoTarget }, memoAchieved = rows.sumOf { it.memoAchieved },
            kpiAllocMtk = rows.sumOf { it.kpiAllocMtk }, kpiTodayMtk = if (rows.all { it.kpiTodayMtk != null }) rows.sumOf { it.kpiTodayMtk!! } else null, byPack = packs,
        )
    }

    /** The exact sum of the per-pack figures (each pack size over its own factor), as one quotient over the common denominator: nothing is rounded here. */
    private fun packTotal(packs: List<PackFigure>, pick: (PackFigure) -> Long, unit: String): FieldQty {
        val den = packs.map { it.packFactor.toLong() }.fold(1L) { acc, f -> Math.multiplyExact(acc / gcd(acc, f), f) }
        return FieldQty(packs.fold(0L) { sum, p -> Math.addExact(sum, Math.multiplyExact(pick(p), den / p.packFactor)) }, unit, den)
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /** The per-pack figures in [unit] when they add up to the sent figure; base units (sticks, pieces) when they do not. Never guessed. */
    private fun pairFromPacks(packs: List<PackFigure>, unit: String, target: Long, achieved: Long, bp: Long?): KpiPair {
        val exact = packs.isNotEmpty() && packs.sumOf { it.target } == target && packs.sumOf { it.achieved } == achieved
        if (!exact) return KpiPair(FieldQty(target * 1000L, baseUnitOf(unit)), FieldQty(achieved * 1000L, baseUnitOf(unit)), bp)
        // checked arithmetic: an overflow (absurd pack sizes or quantities) is not a number: base units, never a wrapped figure
        return try { KpiPair(packTotal(packs, { it.target }, unit), packTotal(packs, { it.achieved }, unit), bp) }
        catch (e: ArithmeticException) { KpiPair(FieldQty(target * 1000L, baseUnitOf(unit)), FieldQty(achieved * 1000L, baseUnitOf(unit)), bp) }
    }

    private fun baseUnitOf(unit: String) = when (unit) { "packet" -> "stick"; "box" -> "piece"; else -> unit }

    /**
     * A brand's volume target in its own unit, decided by the brand's category from the product tree: cigarette brands in packets (their per-pack figures divided by the pack size when they add up to the
     * sent target, else sticks), matches in dozens, lighters in boxes (the same rule, else pieces). A brand whose category is unknown or mixed has no unit: null (a dash with a note).
     */
    private fun brandVolume(b: KpiBrand, category: String?): FieldQty? = when (category) {
        "cigarette" -> pairFromPacks(b.byPack, "packet", b.volumeTarget, b.volumeAchieved, null).target // bidi volume is not shown: its unit is pending the owner (docs/21), a dash with a note
        "match" -> FieldQty(b.volumeTarget * 1000L, "dozen")
        "lighter" -> pairFromPacks(b.byPack, "box", b.volumeTarget, b.volumeAchieved, null).target
        else -> null
    }

    /** [q] as text ("73,900", "1.5"): one decimal, rounded once from the exact quotient, trailing ".0" dropped, digits per [locale]. */
    fun qtyText(q: FieldQty, locale: UiLocale): String {
        if (q.milli < 0L) return Percent.DASH // a negative quantity is not a valid figure: a dash, like the SKU target
        val t = Qty.tenths(q.milli, q.den) ?: return Percent.DASH
        val frac = t % 10L
        return Formats.integer(t / 10L, locale) + if (frac != 0L) Formats.digits(".$frac", locale) else ""
    }

    /** Whole taka from milli-taka with the sign in front ("৳5,700"); a dash when null (the owner has not defined it). */
    fun takaText(mtk: Long?, locale: UiLocale): String = if (mtk == null) Percent.DASH else "৳" + Formats.digits(com.aktcl.aron.rules.Money.formatTaka(mtk, 0), locale)

    /** A percentage in basis points as "29.63%"; a dash when null. */
    fun bpText(bp: Long?, locale: UiLocale): String = Percent.text(bp, 2, locale)

    /** The bar fill (0..1000 per mille), capped at the full bar; null when there is no percentage. */
    fun fillPerMille(bp: Long?): Int? = bp?.let { (it / 10L).coerceIn(0L, 1000L).toInt() }
}
