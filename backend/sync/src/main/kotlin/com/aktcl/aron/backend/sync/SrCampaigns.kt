package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.rules.Targets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * Outlet campaign target and achievement of the SR bundle (lane T-KPI1, N-3; screen 21; design docs/status/sr-targets-server.md s5, D-TKPI-08). For the
 * outlets of the bundle's routes (never a client id) that have an `app.outlet_campaign_target` row for an ACTIVE campaign programme covering the bundle date:
 * STD and memo targets, the SKU targets, and the achievement from the accepted memos at the outlet ([SrMemoFigures.forOutletPairs]: `app.memo` / `app.memo_line`, sale lines, not voided, active memos; every route's memos at the outlet) through the last closed day;
 * today is never in a server figure (same rule as [SrTargets]). STD achieved = base quantity sold of the outlet's campaign SKUs (owner question Q-H);
 * memo achieved = distinct memos with a campaign-SKU line, never a sum of SKU memo counts; remaining = max(target - achieved, 0). The STD target is rounded
 * half up to a whole base unit on the wire. Only outlets with a target appear; at most 150 outlets and 20 SKUs each.
 */
object SrCampaigns {
    /** Caps of the section (docs/04 budget: 70 outlets x 20 SKUs at most 120 KB raw and 20 KB gzip); contract `RouteSnapshot.campaigns` maxItems and `RouteCampaign.skus` maxItems say the same. */
    const val MAX_SKUS = 20
    const val MAX_OUTLETS = 150

    /** The campaign outlets of one route in outlet-id order; [truncated] when more than the outlet cap or an outlet with more than the SKU cap existed (never silent). */
    class Result(val rows: List<JsonObject>, val truncated: Boolean)
    fun load(h: Handle, outletsByRoute: Map<Long, List<Long>>, date: LocalDate, through: LocalDate, maxOutlets: Int = MAX_OUTLETS, maxSkus: Int = MAX_SKUS): Map<Long, Result> {
        val all = outletsByRoute.values.flatten().distinct()
        if (all.isEmpty()) return emptyMap()
        val month = YearMonth.from(date); val first = month.atDay(1)
        class Camp(val programmeId: Long, val en: String, val bn: String?, val std: java.math.BigDecimal, val memo: Int)
        val camps0 = h.createQuery(
            """
            SELECT t.outlet_id, g.id, g.name_en, g.name_bn, t.std_target, t.memo_target FROM app.outlet_campaign_target t JOIN app.programme g ON g.id = t.programme_id
             WHERE t.outlet_id = ANY(:o) AND t.month = :m AND g.kind = 'campaign' AND g.status = 'active' AND g.active_from <= :d AND g.active_to >= :d ORDER BY t.outlet_id, g.id
            """,
        ).bindArray("o", Long::class.javaObjectType, all).bind("m", first).bind("d", date)
            .map { rs, _ -> rs.getLong(1) to Camp(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getInt(6)) }.list()
            .groupBy({ it.first }, { it.second })
        // One campaign per outlet is shown (the lowest programme id); an outlet with more active campaigns makes the route's `campaigns_truncated` true (never a silent drop).
        val moreThanOne = camps0.filterValues { it.size > 1 }.keys
        val camps = camps0.mapValues { it.value.first() }
        if (camps.isEmpty()) return emptyMap()
        // At most MAX_OUTLETS campaign outlets per route (lowest id first): the cut happens BEFORE any figure is read.
        val chosen = outletsByRoute.mapValues { (_, ids) -> ids.filter { it in camps }.sorted() }
        val outletIds = chosen.values.flatMap { it.take(maxOutlets) }.distinct()
        val skuTargets = h.createQuery("SELECT outlet_id, programme_id, sku_id, qty_base FROM app.outlet_campaign_sku_target WHERE outlet_id = ANY(:o) AND month = :m ORDER BY outlet_id, sku_id")
            .bindArray("o", Long::class.javaObjectType, outletIds).bind("m", first)
            .map { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }.list()
            .filter { camps[it[0]]?.programmeId == it[1] }.groupBy { it[0] }
        // The SKU TABLE is cut at the SKU cap (`shown`), but the FIGURES are computed over ALL the campaign's SKUs of the outlet: the (outlet, SKU) pairs of every SKU target of the chosen campaign go
        // into the query (matched by a join, so another campaign's SKU is never counted), exactly as the target (`std_target`, `memo_target`) is the whole campaign's. A cut table therefore never
        // under-reports the achieved STD or memo count (T1 follow-up of train 131); the per-outlet `truncated` flag tells the phone which outlets show a partial table.
        val shown = skuTargets.mapValues { it.value.take(maxSkus) }
        val pairs = skuTargets.flatMap { (o, rows) -> rows.map { o to it[2] } }
        val sold = HashMap<Pair<Long, Long>, Long>(); val memoSets = HashMap<Long, MutableSet<java.util.UUID>>()
        val (lines, outletAt) = SrMemoFigures.forOutletPairs(h, pairs, first, through)
        for (l in lines) { sold.merge(l.outlet to l.skuId, l.qty, Long::plus); memoSets.getOrPut(l.outlet) { HashSet() }.add(l.memo) }
        val memos = memoSets.mapValues { it.value.size.toLong() }
        fun figure(target: Long, achieved: Long) = buildJsonObject { put("target", target); put("achieved", achieved); put("remaining", Targets.remaining(target, achieved)) }
        return outletsByRoute.mapValues { (routeId, _) ->
            val ids = chosen[routeId].orEmpty()
            val asOfRoute = ids.take(maxOutlets).mapNotNull { outletAt[it] }.maxOrNull() ?: Instant.EPOCH   // informational: the newest received_at among the memos read for this route's campaign outlets
            val rows = ids.take(maxOutlets).map { oid ->
                val c = camps.getValue(oid)
                val skus = shown[oid].orEmpty()
                val achievedStd = skuTargets[oid].orEmpty().sumOf { sold[oid to it[2]] ?: 0L }   // ALL the campaign's SKUs, not only the ones listed below
                val cut = (skuTargets[oid]?.size ?: 0) > maxSkus || oid in moreThanOne
                buildJsonObject {
                    put("outlet_id", oid); put("programme_id", c.programmeId); put("label_en", c.en); put("label_bn", c.bn?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("month", month.toString()); put("through_date", through.toString()); put("as_of", asOfRoute.wire())
                    if (cut) put("truncated", true)   // only when true: this outlet's SKU table is cut, or another active campaign of the outlet is not shown
                    put("std", figure(c.std.setScale(0, java.math.RoundingMode.HALF_UP).toLong(), achievedStd))
                    put("memo", figure(c.memo.toLong(), memos[oid] ?: 0L))
                    put("skus", buildJsonArray { skus.forEach { k -> add(buildJsonObject { put("sku_id", k[2]); put("target_qty_base", k[3]); put("sold_qty_base", sold[oid to k[2]] ?: 0L) }) } })
                }
            }
            Result(rows, ids.size > maxOutlets || rows.any { it["truncated"] != null })
        }
    }
}
