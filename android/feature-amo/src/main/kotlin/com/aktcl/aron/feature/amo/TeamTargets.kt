package com.aktcl.aron.feature.amo

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

/** The two tabs of Team Performance (screenshots 34 to 36). */
enum class PerfTab { MONTHLY, TILL_DATE }

/** One item (brand) row of a target node, in base units; [tillDateTarget] is null when the node has no till-date figure. */
data class TeamTargetRow(val name: String, val categoryCode: String, val target: Long, val achieved: Long, val tillDateTarget: Long?)

/** One node of `GET /v1/dashboards/targets`: the zone or one route, with its brand rows. */
data class TeamTargetNode(val type: String, val id: Long, val name: String?, val periodDays: Int, val rows: List<TeamTargetRow>)

/**
 * Team Performance (D-AMO-MAN-06; screenshots 34 to 36) over the target slice the TSO already uses. Every figure comes from the shared
 * rules (`Targets`, `Percent`, `Formats`) with Long arithmetic, so the AMO and the TSO print the same number for the same data. A
 * category card is the SUM of its brand rows (R-F-01), never one rounded total. Base units, no unit text (as the TSO cards and the screenshot).
 */
object TeamTargets {
    val categoryOrder: List<String> = listOf("cigarette", "bidi", "lighter", "match")

    private fun obj(json: String?): JsonObject? = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull()
    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun l(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    /** A `TargetAchievement` answer for one node (brand rows); null when unreadable. A node with `period_days` 0 has no till-date figure. */
    fun parse(json: String?): TeamTargetNode? {
        val o = obj(json) ?: return null
        val ref = o["node"] as? JsonObject ?: return null
        val period = (o["period_days"] as? JsonPrimitive)?.intOrNull ?: 0
        val rows = (o["rows"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            val t = l(r, "target_qty_base") ?: return@mapNotNull null
            val a = l(r, "achieved_qty_base") ?: return@mapNotNull null
            if (t < 0L || a < 0L) return@mapNotNull null
            TeamTargetRow(s(r, "name").orEmpty(), s(r, "category_code").orEmpty(), t, a, if (period <= 0) null else l(r, "tilldate_target_qty_base")?.takeIf { it >= 0L })
        }
        return TeamTargetNode(s(ref, "type") ?: return null, l(ref, "id") ?: return null, s(ref, "name"), period, rows)
    }

    /** One bar per category: T, A, T' are sums of the brand rows. Cigarette, Bidi, Lighter, Match first, any other category after. */
    fun categoryCards(rows: List<TeamTargetRow>, periodDays: Int): List<TeamTargetRow> {
        val codes = categoryOrder + rows.map { it.categoryCode }.distinct().filter { it !in categoryOrder }
        return codes.mapNotNull { code ->
            val of = rows.filter { it.categoryCode == code }
            if (of.isEmpty()) return@mapNotNull null
            val till = if (periodDays <= 0 || of.any { it.tillDateTarget == null }) null else of.fold(0L) { a, r -> Math.addExact(a, r.tillDateTarget ?: 0L) }
            TeamTargetRow(code, code, of.fold(0L) { a, r -> Math.addExact(a, r.target) }, of.fold(0L) { a, r -> Math.addExact(a, r.achieved) }, till)
        }
    }

    /** The brand rows of the Details table: all, or one category when a bar is selected (screenshot 36), in the server's order. */
    fun items(rows: List<TeamTargetRow>, category: String?): List<TeamTargetRow> = if (category == null) rows else rows.filter { it.categoryCode == category }

    /** Tapping the selected category again clears the filter (screenshot 36 note); tapping another selects it. */
    fun toggle(current: String?, tapped: String): String? = if (current == tapped) null else tapped

    fun target(r: TeamTargetRow, tab: PerfTab): Long? = if (tab == PerfTab.MONTHLY) r.target else r.tillDateTarget

    /** Card percent: min(100, whole(A, T')); null without a target (a dash). */
    fun cardPct(r: TeamTargetRow, tab: PerfTab): Long? = target(r, tab)?.let { Targets.cardPct(r.achieved, it) }

    fun cardPctText(r: TeamTargetRow, tab: PerfTab, locale: UiLocale): String = Percent.text(cardPct(r, tab), 0, locale)

    /** red below 40, amber below 80, green from 80 on the PRINTED percent (`cfg.kpi.bar_bands`, default [40, 80]). */
    fun band(r: TeamTargetRow, tab: PerfTab, bands: Bands = Bands.DEFAULT): Band? = cardPct(r, tab)?.let { Targets.band(it, bands) }

    fun qty(n: Long?, locale: UiLocale): String = n?.let { Formats.integer(it, locale) } ?: Percent.DASH

    /** "achievement/target" with thousands grouping as screenshot 34 prints it ("1,598,680/2,270,000"). */
    fun fraction(r: TeamTargetRow, tab: PerfTab, locale: UiLocale): String = qty(r.achieved, locale) + "/" + qty(target(r, tab), locale)

    /** Details percent with two decimals, uncapped ("202.49%"); a dash without a target. */
    fun detailsText(r: TeamTargetRow, tab: PerfTab, locale: UiLocale): String = target(r, tab)?.let { Percent.text(Targets.detailsBp(r.achieved, it), 2, locale) } ?: Percent.DASH

    /** Remaining = max(T - A, 0); null without a target. */
    fun remaining(r: TeamTargetRow, tab: PerfTab): Long? = target(r, tab)?.let { Targets.remaining(it, r.achieved) }
}
