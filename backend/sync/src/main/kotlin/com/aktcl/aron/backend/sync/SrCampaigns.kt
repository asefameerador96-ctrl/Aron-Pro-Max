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
import java.time.LocalDate
import java.time.YearMonth

/**
 * Outlet campaign target and achievement of the SR bundle (lane T-KPI1, N-3; screen 21; design docs/status/sr-targets-server.md s5, D-TKPI-08). For the
 * outlets of the bundle's routes (never a client id) that have an `app.outlet_campaign_target` row for an ACTIVE campaign programme covering the bundle date:
 * STD and memo targets, the SKU targets, and the achievement from `dw.fact_memo_line` (sale lines, not voided, active memos) through the last closed day;
 * today is never in a server figure (same rule as [SrTargets]). STD achieved = base quantity sold of the outlet's campaign SKUs (owner question Q-H);
 * memo achieved = distinct memos with a campaign-SKU line, never a sum of SKU memo counts; remaining = max(target - achieved, 0). The STD target is rounded
 * half up to a whole base unit on the wire. Only outlets with a target appear; at most 2,000 outlets and 100 SKUs each.
 */
object SrCampaigns {
    fun load(h: Handle, outletsByRoute: Map<Long, List<Long>>, date: LocalDate): Map<Long, List<JsonObject>> {
        val all = outletsByRoute.values.flatten().distinct()
        if (all.isEmpty()) return emptyMap()
        val month = YearMonth.from(date); val first = month.atDay(1); val through = date.minusDays(1)
        val asOf = SrTargets.asOf(h)
        class Camp(val programmeId: Long, val en: String, val bn: String?, val std: java.math.BigDecimal, val memo: Int)
        val camps = h.createQuery(
            """
            SELECT t.outlet_id, g.id, g.name_en, g.name_bn, t.std_target, t.memo_target FROM app.outlet_campaign_target t JOIN app.programme g ON g.id = t.programme_id
             WHERE t.outlet_id = ANY(:o) AND t.month = :m AND g.kind = 'campaign' AND g.status = 'active' AND g.active_from <= :d AND g.active_to >= :d ORDER BY t.outlet_id, g.id
            """,
        ).bindArray("o", Long::class.javaObjectType, all).bind("m", first).bind("d", date)
            .map { rs, _ -> rs.getLong(1) to Camp(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getInt(6)) }.list()
            .groupBy({ it.first }, { it.second }).mapValues { it.value.first() } // one campaign per outlet: the lowest programme id
        if (camps.isEmpty()) return emptyMap()
        val outletIds = camps.keys.toList()
        val skuTargets = h.createQuery("SELECT outlet_id, programme_id, sku_id, qty_base FROM app.outlet_campaign_sku_target WHERE outlet_id = ANY(:o) AND month = :m ORDER BY outlet_id, sku_id")
            .bindArray("o", Long::class.javaObjectType, outletIds).bind("m", first)
            .map { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }.list()
            .filter { camps[it[0]]?.programmeId == it[1] }.groupBy { it[0] }
        val sold = HashMap<Pair<Long, Long>, Long>(); val memos = HashMap<Long, Long>()
        if (!through.isBefore(first)) {
            h.createQuery(
                """
                SELECT l.outlet_id, l.sku_id, sum(l.qty_base) FROM dw.fact_memo_line l
                 WHERE l.outlet_id = ANY(:o) AND l.business_date BETWEEN :f AND :t AND l.line_kind = 'sale' AND NOT l.voided AND l.memo_status = 'active'
                   AND EXISTS (SELECT 1 FROM app.outlet_campaign_sku_target k WHERE k.outlet_id = l.outlet_id AND k.sku_id = l.sku_id AND k.month = :m)
                 GROUP BY l.outlet_id, l.sku_id
                """,
            ).bindArray("o", Long::class.javaObjectType, outletIds).bind("f", first).bind("t", through).bind("m", first)
                .map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }.list().forEach { sold[it.first to it.second] = it.third }
            h.createQuery(
                """
                SELECT l.outlet_id, count(DISTINCT l.memo_client_uuid) FROM dw.fact_memo_line l
                 WHERE l.outlet_id = ANY(:o) AND l.business_date BETWEEN :f AND :t AND l.line_kind = 'sale' AND NOT l.voided AND l.memo_status = 'active'
                   AND EXISTS (SELECT 1 FROM app.outlet_campaign_sku_target k WHERE k.outlet_id = l.outlet_id AND k.sku_id = l.sku_id AND k.month = :m)
                 GROUP BY l.outlet_id
                """,
            ).bindArray("o", Long::class.javaObjectType, outletIds).bind("f", first).bind("t", through).bind("m", first)
                .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list().forEach { memos[it.first] = it.second }
        }
        fun figure(target: Long, achieved: Long) = buildJsonObject { put("target", target); put("achieved", achieved); put("remaining", Targets.remaining(target, achieved)) }
        return outletsByRoute.mapValues { (_, ids) ->
            ids.filter { it in camps }.take(2000).map { oid ->
                val c = camps.getValue(oid)
                val skus = skuTargets[oid].orEmpty().take(100)
                val achievedStd = skus.sumOf { sold[oid to it[2]] ?: 0L }
                buildJsonObject {
                    put("outlet_id", oid); put("programme_id", c.programmeId); put("label_en", c.en); put("label_bn", c.bn?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("month", month.toString()); put("through_date", through.toString()); put("as_of", asOf.wire())
                    put("std", figure(c.std.setScale(0, java.math.RoundingMode.HALF_UP).toLong(), achievedStd))
                    put("memo", figure(c.memo.toLong(), memos[oid] ?: 0L))
                    put("skus", buildJsonArray { skus.forEach { k -> add(buildJsonObject { put("sku_id", k[2]); put("target_qty_base", k[3]); put("sold_qty_base", sold[oid to k[2]] ?: 0L) }) } })
                }
            }
        }
    }
}
