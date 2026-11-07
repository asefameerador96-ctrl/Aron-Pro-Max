package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.Targets
import com.aktcl.aron.rules.UiLocale
import java.time.LocalDate

/** One route of the STD Memo Report and its sold quantity per category column (base units); null when the category mixes units. */
data class StdRow(val route: String, val cells: Map<String, Long?>)

/** The route x category matrix of screenshot 71 with the totals row. [zone] is the first header cell. */
data class StdMatrix(val zone: String, val categories: List<String>, val rows: List<StdRow>, val totals: Map<String, Long?>)

/** The card of one node (zone or route) of the sales-till-now summary (screenshot 72). Cells are strings already formatted by [SalesTillNow]. */
data class TillRow(val category: String, val target: String, val sales: String, val pct: String, val memo: String)
data class TillCard(val heading: String, val isZone: Boolean, val routeId: Long?, val rows: List<TillRow>)

/** A brand line of the route detail: target, sales, percent, memos (a dash where the server sends nothing). */
data class BrandLine(val brand: String, val target: String, val sales: String, val pct: String, val memo: String)

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
        return StdMatrix(zone, cats, body, totals)
    }

    /** The sales-summary period (lead ruling 2026-11-07, screenshot 72): the first of the month to TODAY, so CPR and memos match the target slice; F-AMO-033's "up to yesterday" is superseded for the AMO summary. */
    fun summaryPeriod(today: String): Pair<String, String> = monthToToday(today)

    /**
     * Memos per category for a till-now card, from the `sales-summary` route cards: the sum of the memo counts of the brands of that category
     * (the node's brand rows say which brand belongs to which category). A zone adds every route's brands; a route uses its own card (matched by
     * name or code). A memo with two brands of one category is counted once per brand, so this is an upper bound of the category's memos.
     * A category with no matching brand row is absent (the card prints a dash), never zero.
     */
    fun categoryMemos(node: TeamTargetNode, cards: List<RouteCard>, isZone: Boolean): Map<String, Long> {
        val mine = if (isZone) cards else cards.filter { it.name.equals(node.name, true) || it.code.equals(node.name, true) }
        val brandCategory = node.rows.associate { it.name.lowercase() to it.categoryCode }
        val out = linkedMapOf<String, Long>()
        for (b in mine.flatMap { it.brands }) { val c = brandCategory[b.name.lowercase()] ?: continue; out[c] = (out[c] ?: 0L) + b.memos }
        return out
    }

    private fun fmt(n: Long?, l: UiLocale) = TeamTargets.qty(n, l)

    /** A till-now card from a target node: target = the till-date target, sales = the achievement, percent with two decimals, memos when known. */
    fun tillCard(heading: String, node: TeamTargetNode, isZone: Boolean, memos: Map<String, Long>, l: UiLocale): TillCard =
        TillCard(heading, isZone, node.id.takeIf { !isZone }, TeamTargets.categoryCards(node.rows, node.periodDays).map { r ->
            TillRow(r.categoryCode, fmt(r.tillDateTarget, l), fmt(r.achieved, l), r.tillDateTarget?.let { Percent.text(Targets.detailsBp(r.achieved, it), 2, l) } ?: Percent.DASH, memos[r.categoryCode]?.let { fmt(it, l) } ?: Percent.DASH)
        })

    /** CPR of a route: successful calls over target outlets, two decimals without a percent sign (screenshot 72 prints 79.67). */
    fun cprText(successfulCalls: Long, targetOutlets: Long, l: UiLocale): String = Percent.text(Percent.bp(successfulCalls, targetOutlets), 2, l).removeSuffix("%")

    /** A brand line from the till-date brand row of the route's target node (sales in base units) and its memo count. */
    fun brandLine(r: TeamTargetRow, memos: Long?, l: UiLocale): BrandLine = BrandLine(
        r.name, fmt(r.tillDateTarget, l), fmt(r.achieved, l), r.tillDateTarget?.let { Percent.text(Targets.detailsBp(r.achieved, it), 2, l) } ?: Percent.DASH, memos?.let { fmt(it, l) } ?: Percent.DASH,
    )
}
