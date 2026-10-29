package com.aktcl.aron.feature.tso.targets

import com.aktcl.aron.rules.Band
import com.aktcl.aron.rules.Bands
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.Targets
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The two tabs of Target Status and Target Details (screenshots 39 to 42), in screen order. */
enum class TargetTab { MONTHLY, TILL_DATE }

/**
 * One product row of a target node (contract 1.10.0 `TargetAchievementRow`, the integer members only; D-TSO-MAN-39, 40): T the month's
 * target, A the achievement month to date, T' the till-date target (null when the node's `period_days` is 0, R-F-15, R-F-24). Base
 * units (sticks, pieces, dozens). The Decimal3 members and `achievement_pct` (the AMO app's) are not read; the `*_pct_bp` members are
 * never printed (every percent is computed once from the integers, D-TSO-MAN-10).
 */
data class TargetRow(
    val productLevel: String,
    val productId: Long,
    val name: String,
    val categoryCode: String,
    val target: Long,
    val achieved: Long,
    val tillDateTarget: Long?,
)

/** A node as the server named it: its level (`territory`, `house`, `zone`, ...), id and name. */
data class TargetNodeRef(val type: String, val id: Long, val name: String?)

/** One node of a target answer with its own till-date days (R-F-24) and its rows. */
data class TargetNode(val ref: TargetNodeRef, val elapsedDays: Int, val periodDays: Int, val rows: List<TargetRow>)

/**
 * A `GET /v1/dashboards/targets` answer: the month, the server's stamp, the business day the till-date figures are for, the basis
 * (`calendar_days` or `working_days`), the node and, with `children=zone`, one node per zone in the caller's reach.
 */
data class TargetAnswer(
    val month: String,
    val asOf: String,
    val day: String,
    val basis: String,
    val node: TargetNode,
    val children: List<TargetNode>,
) {
    /**
     * The cards of Target Status (screenshots 39 and 40): the answer's node (the TSO's territory or house), then each zone. A zone
     * node answers itself as its one child (R-F-18), which is one card, not two.
     */
    val cards: List<TargetNode>
        get() = listOf(node) + children.filterNot { it.ref.type == node.ref.type && it.ref.id == node.ref.id }
}

/**
 * Which node a Target Details screen shows, as the plain-string argument of [com.aktcl.aron.feature.tso.TsoNav.open]: [TOP] for the
 * answer's own node (read without `level`/`node_id`: the server takes the caller's top reach node, which also serves a house, whose
 * explicit `level=house` the dashboards refuse), else `<level>:<id>` of a child zone. Never a scope list (constraint 4).
 */
data class TargetNodeKey(val level: String?, val id: Long?) {
    val wire: String get() = if (level == null || id == null) TOP else "$level:$id"

    companion object {
        const val TOP = "top"
        val Top = TargetNodeKey(null, null)

        /** The key of [arg]; an unreadable argument is the top node (the screen never opens on a guess of another node). */
        fun parse(arg: String?): TargetNodeKey {
            if (arg == null || arg == TOP) return Top
            val level = arg.substringBefore(':', "")
            val id = arg.substringAfter(':', "").toLongOrNull()
            return if (level.isNotEmpty() && id != null && level.all { it in 'a'..'z' }) TargetNodeKey(level, id) else Top
        }

        /** The key that opens [node] of [answer]: the top node by [TOP], a child by its level and id. */
        fun of(answer: TargetAnswer, node: TargetNode): TargetNodeKey =
            if (node.ref == answer.node.ref) Top else TargetNodeKey(node.ref.type, node.ref.id)
    }
}

/**
 * Target Status and Target Details (build contract s4.3, D-TSO-MAN-40; screenshots 39 to 42): the parser and every printed figure,
 * each one rounding from the integers through the shared rules (`Targets.cardPct`, `Targets.band`, `Targets.detailsBp`,
 * `Targets.remaining`, `Percent.text`, `Formats.digits`). No floating point, no network, no timer.
 */
object TargetStatus {
    private fun obj(json: String?): JsonObject? = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull()
    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun l(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    private fun i(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
    private fun arr(o: JsonObject, k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** The category order of the cards (screenshots 39 to 42, as the Dashboard's Sales card); a later category follows them. */
    val categoryOrder: List<String> = listOf("cigarette", "bidi", "lighter", "match")

    private fun row(r: JsonObject): TargetRow? {
        val target = l(r, "target_qty_base") ?: return null
        val achieved = l(r, "achieved_qty_base") ?: return null
        if (target < 0L || achieved < 0L) return null
        return TargetRow(
            s(r, "product_level") ?: return null, l(r, "product_id") ?: return null, s(r, "name").orEmpty(),
            s(r, "category_code").orEmpty(), target, achieved, l(r, "tilldate_target_qty_base")?.takeIf { it >= 0L },
        )
    }

    private fun node(ref: JsonObject?, o: JsonObject, rows: List<JsonObject>): TargetNode? {
        val r = ref ?: return null
        val period = i(o, "period_days") ?: 0
        val parsed = rows.mapNotNull(::row)
        // R-F-15, R-F-24: a node whose period is 0 has no till-date figure, whatever a row says
        val clean = if (period <= 0) parsed.map { it.copy(tillDateTarget = null) } else parsed
        return TargetNode(TargetNodeRef(s(r, "type") ?: return null, l(r, "id") ?: return null, s(r, "name")), i(o, "elapsed_days") ?: 0, period, clean)
    }

    /** A `TargetAchievement` answer; null when it is unreadable (the screen then says why it has no figures). */
    fun parse(json: String?): TargetAnswer? {
        val o = obj(json) ?: return null
        val top = node(o["node"] as? JsonObject, o, arr(o, "rows")) ?: return null
        val children = arr(o, "children").mapNotNull { c -> node(c["node"] as? JsonObject, c, arr(c, "rows")) }
        return TargetAnswer(s(o, "month") ?: return null, s(o, "as_of") ?: return null, s(o, "day").orEmpty(), s(o, "tilldate_basis") ?: "calendar_days", top, children)
    }

    /** [rows] in card order: Cigarette, Bidi, Lighter, Match, then any other category in the server's order. */
    fun ordered(rows: List<TargetRow>): List<TargetRow> =
        categoryOrder.flatMap { c -> rows.filter { it.categoryCode == c } } + rows.filter { it.categoryCode !in categoryOrder }

    /**
     * The category bars of a Details card from its item rows: per category T, A and T' are the SUMS of the item rows (D-TSO-MAN-05,
     * R-F-01: the Cigarette card of the eight items at 21/26 is 33114, never tillDate(40999) = 33115). The four categories of the
     * cards are always shown (a category with no item row is 0 of 0, "—"), then any other category in the order of the items. T' is
     * null when the node has no till-date figure ([periodDays] 0) or an item has none.
     */
    fun categoryCards(items: List<TargetRow>, periodDays: Int): List<TargetRow> {
        val codes = categoryOrder + items.map { it.categoryCode }.distinct().filter { it !in categoryOrder }
        return codes.map { code ->
            val of = items.filter { it.categoryCode == code }
            val tillDate = if (periodDays <= 0 || of.any { it.tillDateTarget == null }) null else of.fold(0L) { a, r -> Math.addExact(a, r.tillDateTarget ?: 0L) }
            TargetRow("category", 0L, code, code, of.fold(0L) { a, r -> Math.addExact(a, r.target) }, of.fold(0L) { a, r -> Math.addExact(a, r.achieved) }, tillDate)
        }
    }

    /** The item rows of the Details table, filtered to [category] when a bar is selected (D-TSO-MAN-40), in the server's order. */
    fun items(rows: List<TargetRow>, category: String?): List<TargetRow> = if (category == null) rows else rows.filter { it.categoryCode == category }

    // ---- the printed figures (s4.3) ---------------------------------------------------------------------------------------------

    /** The target the [tab] compares with: T (Monthly) or T' (Till Date; null when the node has none). */
    fun target(r: TargetRow, tab: TargetTab): Long? = if (tab == TargetTab.MONTHLY) r.target else r.tillDateTarget

    /** The card percent: min(100, whole(A, T')) (1030 of 40999 is 3; 85 of 81 is 100); null without a target ("—"). */
    fun cardPct(r: TargetRow, tab: TargetTab): Long? = target(r, tab)?.let { Targets.cardPct(r.achieved, it) }

    fun cardPctText(r: TargetRow, tab: TargetTab, locale: UiLocale): String = Percent.text(cardPct(r, tab), 0, locale)

    /** The bar colour of the PRINTED card percent on `cfg.kpi.bar_bands` (red < 40 <= amber < 80 <= green); null without a percent. */
    fun band(r: TargetRow, tab: TargetTab, bands: Bands): Band? = cardPct(r, tab)?.let { Targets.band(it, bands) }

    /** A quantity as the screenshots print it: the integer in base units, no grouping ("40999"), Bengali digits in Bangla; null "—". */
    fun qty(n: Long?, locale: UiLocale): String = n?.let { Formats.digits(it.toString(), locale) } ?: Percent.DASH

    /** "achieved/target" of a card bar ("1030/40999", "85/81"). */
    fun fraction(r: TargetRow, tab: TargetTab, locale: UiLocale): String = qty(r.achieved, locale) + "/" + qty(target(r, tab), locale)

    /** The Details percent in basis points, uncapped (85 of 81 is 10494); null without a target. */
    fun detailsBp(r: TargetRow, tab: TargetTab): Long? = target(r, tab)?.let { Targets.detailsBp(r.achieved, it) }

    /** The Details percent with 2 decimals ("35.00%", "104.94%"). */
    fun detailsText(r: TargetRow, tab: TargetTab, locale: UiLocale): String = Percent.text(detailsBp(r, tab), 2, locale)

    /** Remaining = max(T - A, 0) against the tab's target (81 - 85 is 0); null without a target. */
    fun remaining(r: TargetRow, tab: TargetTab): Long? = target(r, tab)?.let { Targets.remaining(it, r.achieved) }

    /** `cfg.kpi.bar_bands` from its bundle JSON text ("[40,80]"); anything else gives the default [40, 80]. */
    fun bandsOf(json: String?): Bands {
        val a = runCatching { Json.parseToJsonElement(json ?: return Bands.DEFAULT) as? JsonArray }.getOrNull() ?: return Bands.DEFAULT
        return Bands.of(a.map { (it as? JsonPrimitive)?.longOrNull ?: return Bands.DEFAULT })
    }
}
