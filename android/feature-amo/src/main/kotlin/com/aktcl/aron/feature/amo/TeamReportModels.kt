package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.Targets
import com.aktcl.aron.rules.UiLocale
import java.time.LocalDate

/** One route of the STD Memo Report and its sold quantity per category column (base units); null when the category mixes units. */
data class StdRow(val route: String, val cells: Map<String, Long?>, val field: Map<String, java.math.BigDecimal?> = emptyMap())

/** The route x category matrix of screenshot 71 with the totals row. [zone] is the first header cell. */
data class StdMatrix(
    val zone: String, val categories: List<String>, val rows: List<StdRow>, val totals: Map<String, Long?>,
    /** Field-unit columns (units rule 5): a category whose every row carries its pack factor is in [fieldTotals] / [StdRow.field] (sums of the CONVERTED rows) in [units]; the others stay base. */
    val fieldTotals: Map<String, java.math.BigDecimal?> = emptyMap(), val units: Map<String, String?> = emptyMap(),
) {
    fun isField(category: String) = category in fieldTotals
    fun cellText(row: StdRow, category: String, l: UiLocale): String =
        if (isField(category)) row.field[category]?.let { FieldQty.text(it, l) } ?: Percent.DASH else row.cells[category]?.let { Formats.integer(it, l) } ?: Percent.DASH
    fun totalText(category: String, l: UiLocale): String =
        if (isField(category)) fieldTotals[category]?.let { FieldQty.text(it, l) } ?: Percent.DASH else totals[category]?.let { Formats.integer(it, l) } ?: Percent.DASH
}

/** The card of one node (zone or route) of the sales-till-now summary (screenshot 72). Cells are strings already formatted by [SalesTillNow]. */
data class TillRow(val category: String, val target: String, val sales: String, val pct: String, val memo: String, val unit: String? = null)
data class TillCard(val heading: String, val isZone: Boolean, val routeId: Long?, val rows: List<TillRow>)

/** A brand line of the route detail: target, sales, percent, memos (a dash where the server sends nothing). */
data class BrandLine(val brand: String, val target: String, val sales: String, val pct: String, val memo: String, val unit: String? = null)

object TeamReports {
    /** The first of the month to today (screenshot 71 shows "October 1, 2026 - October 25, 2026"). */
    fun monthToToday(today: String): Pair<String, String> = LocalDate.parse(today).let { it.withDayOfMonth(1).toString() to it.toString() }

    /** "October 1, 2026 - October 25, 2026". */
    fun rangeText(from: String, to: String): String = formDateText(from) + " - " + formDateText(to)

    /** A range is valid when it is not inverted and not in the future ([today] is the Dhaka business date). */
    fun rangeValid(from: String, to: String, today: String): Boolean = runCatching { !LocalDate.parse(from).isAfter(LocalDate.parse(to)) && !LocalDate.parse(to).isAfter(LocalDate.parse(today)) }.getOrDefault(false)

    /**
     * The STD matrix from the `route-std` rows (route x category, `sold_qty_base`). Routes in name order; a category column is shown when
     * any route has it, the four known categories first. A cell whose category mixes base units is a dash and the total of that column too.
     */
    fun stdMatrix(zone: String, rows: List<RouteCategory>): StdMatrix {
        val cats = (TeamTargets.categoryOrder + rows.map { it.category }.distinct()).distinct().filter { c -> rows.any { it.category == c } }
        // one row per route CODE (two routes may share a name), labelled by the name
        val byRoute = rows.groupBy { it.code }.toList().sortedWith(compareBy({ it.second.first().route.lowercase() }, { it.second.first().route }, { it.first }))
        val body = byRoute.map { (_, rs) -> StdRow(rs.first().route, cats.associateWith { c -> rs.filter { it.category == c }.let { m -> if (m.isEmpty()) 0L else if (m.any { it.qty == null }) null else m.sumOf { it.qty ?: 0L } } }) }
        // a column adds only quantities of ONE base unit: two routes counting the same category in different units give a dash
        val mixed = cats.filter { c -> rows.filter { it.category == c }.mapNotNull { it.unit }.toSet().size > 1 }.toSet()
        val totals = cats.associateWith { c -> if (c in mixed || body.any { it.cells[c] == null }) null else body.sumOf { it.cells[c] ?: 0L } }
        // a column is in field units only when EVERY row of that category carries its pack factor and they share one unit; the total is the sum of the converted rows
        val field = cats.filter { c -> rows.filter { it.category == c }.let { rs -> rs.all { it.packQty != null && it.packUnit != null } && rs.mapNotNull { it.packUnit }.toSet().size == 1 } }.toSet()
        val withField = byRoute.mapIndexed { i, (_, rs) -> body[i].copy(field = field.associateWith { c -> rs.filter { it.category == c }.fold(java.math.BigDecimal.ZERO) { a, x -> a + x.packQty!! } }) }
        val fieldTotals = field.associateWith { c -> withField.fold(java.math.BigDecimal.ZERO) { a, r -> a + (r.field[c] ?: java.math.BigDecimal.ZERO) } }
        val units = cats.associateWith { c ->
            val rs = rows.filter { it.category == c }
            if (c in field) rs.first().packUnit else rs.mapNotNull { it.unit }.toSet().singleOrNull()?.let { UnitWords.key(it, c) } ?: FieldQty.baseUnitKey(c)
        }
        return StdMatrix(zone, cats, withField, totals, fieldTotals, units)
    }

    /** The sales-summary card of a route: by route id; by route code only when the card carries no id (an older server). Never by name (two routes may share one). */
    fun cardFor(cards: List<RouteCard>, routeId: Long, routeCode: String?): RouteCard? =
        cards.firstOrNull { it.routeId == routeId } ?: routeCode?.takeIf { it.isNotBlank() }?.let { c -> cards.firstOrNull { it.routeId == null && it.code == c } }

    /** The sales-summary period (lead ruling 2026-11-07, screenshot 72): the first of the month to TODAY, so CPR and memos match the target slice; F-AMO-033's "up to yesterday" is superseded for the AMO summary. */
    fun summaryPeriod(today: String): Pair<String, String> = monthToToday(today)

    /**
     * Memos per category for a till-now card (contract 1.11.2): the server's own counts, never derived here. A route's are the `memos_<category>`
     * members of its sales-summary rows ([RouteCard.categoryMemos]); a category the server did not send is absent and the card prints a dash.
     * `CategoryVolume.memos` (the Live Data panel) is the one-day window of `login-submit`; these cover the report window, month to today.
     */
    fun categoryMemos(cats: List<CategorySales>): Map<String, Long> = cats.mapNotNull { c -> c.memos?.let { c.code to it } }.toMap()

    /**
     * The zone card's memos per category: the sum of every route card's server count (a memo belongs to one route, so the sum is exact), only for a
     * category EVERY route reported; with no cards, or one route without the member, that category is absent (a dash), never a partial sum.
     */
    fun zoneMemos(cards: List<RouteCard>): Map<String, Long> =
        if (cards.isEmpty()) emptyMap() else Reports.MEMO_CATEGORIES.filter { c -> cards.all { c in it.categoryMemos } }.associateWith { c -> cards.sumOf { it.categoryMemos.getValue(c) } }

    private fun fmt(n: Long?, l: UiLocale) = TeamTargets.qty(n, l)

    /** A till-now card from a target node: target = the till-date target, sales = the achievement, percent with two decimals, memos when known. */
    fun tillCard(heading: String, node: TeamTargetNode, isZone: Boolean, memos: Map<String, Long>, l: UiLocale): TillCard =
        TillCard(heading, isZone, node.id.takeIf { !isZone }, TeamTargets.categoryCards(node.rows, node.periodDays).map { r ->
            TillRow(r.categoryCode, TeamTargets.targetText(r, PerfTab.TILL_DATE, l), TeamTargets.achievedText(r, l), r.tillDateTarget?.let { Percent.text(Targets.detailsBp(r.achieved, it), 2, l) } ?: Percent.DASH, memos[r.categoryCode]?.let { fmt(it, l) } ?: Percent.DASH, TeamTargets.unitKey(r))
        })

    /** CPR of a route: successful calls over target outlets, two decimals without a percent sign (screenshot 72 prints 79.67). */
    fun cprText(successfulCalls: Long, targetOutlets: Long, l: UiLocale): String = Percent.text(Percent.bp(successfulCalls, targetOutlets), 2, l).removeSuffix("%")

    /** A brand line from the till-date brand row of the route's target node (sales in base units) and its memo count. */
    fun brandLine(r: TeamTargetRow, memos: Long?, l: UiLocale): BrandLine = BrandLine(
        r.name, TeamTargets.targetText(r, PerfTab.TILL_DATE, l), TeamTargets.achievedText(r, l), r.tillDateTarget?.let { Percent.text(Targets.detailsBp(r.achieved, it), 2, l) } ?: Percent.DASH, memos?.let { fmt(it, l) } ?: Percent.DASH, TeamTargets.unitKey(r),
    )
}
