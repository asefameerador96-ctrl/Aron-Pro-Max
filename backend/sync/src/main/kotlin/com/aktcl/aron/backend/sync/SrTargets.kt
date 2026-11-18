package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.WorkingCalendarRepo
import com.aktcl.aron.backend.platform.wire
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * The SR programme screens' target data in the bundle (lane T-KPI1, N-2; owner directive D-OWNER-SR-PROGRAMMES-01; design in
 * docs/status/sr-targets-server.md s2). Read-only, per assigned route, never from a client id.
 *
 * - `targets`: the `app.route_monthly_target` rows of the route for the Dhaka month of the bundle date, one `TargetRow` per SKU
 *   (`std_target` = qty_base as Decimal3, `memo_target` 0: the memo target is the brand table of N-5).
 * - `achievement_mtd`: `dw.agg_daily_route_sku.sold_qty_base` (sale lines only, like Target Status) of the business dates from the
 *   1st up to and including `through_date` = the bundle date minus one day. TODAY IS NEVER IN A SERVER FIGURE: the phone adds its
 *   own memos dated after `through_date`, and older unacknowledged ones, de-duplicated by memo UUID, so nothing is counted twice.
 *   `as_of` is the projector time of the aggregate (`dw.summary_freshness`), never the request time; no row means the epoch.
 * - `calendar`: the working days at the route's zone path (division and wing weekend and holidays honoured).
 */
object SrTargets {
    class Snapshot(val targets: List<JsonObject>, val achievement: List<JsonObject>, val calendar: RouteCalendarDto)

    private const val MAX_ROWS = 500

    fun load(h: Handle, routes: Map<Long, Long>, date: LocalDate, now: Instant, config: ServerConfig?): Map<Long, Snapshot> {
        if (routes.isEmpty()) return emptyMap()
        val month = YearMonth.from(date)
        val first = month.atDay(1)
        val through = date.minusDays(1)
        val asOf = h.createQuery("SELECT last_updated_at FROM dw.summary_freshness WHERE table_name = 'agg_daily_route_sku'")
            .map { rs, _ -> rs.getTimestamp(1).toInstant() }.findOne().orElse(Instant.EPOCH)
        val routeIds = routes.keys.toList()

        val targets = h.createQuery(
            """
            SELECT t.route_id, t.sku_id, t.qty_base, s.base_unit FROM app.route_monthly_target t JOIN app.sku s ON s.id = t.sku_id
             WHERE t.route_id = ANY(:r) AND t.month = :m ORDER BY t.route_id, t.sku_id
            """,
        ).bindArray("r", Long::class.javaObjectType, routeIds).bind("m", first)
            .map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3) to rs.getString(4)) }.list()
            .groupBy({ it.first }, { buildJsonObject {
                put("scope_type", "route"); put("scope_id", it.first); put("month", month.toString()); put("product_level", "sku")
                put("product_id", it.second); put("std_target", decimal3(it.third.first)); put("std_unit", it.third.second); put("memo_target", 0)
            } })

        val achieved = if (through < first) emptyMap() else h.createQuery(
            """
            SELECT route_id, sku_id, sum(sold_qty_base), sum(memo_count) FROM dw.agg_daily_route_sku
             WHERE route_id = ANY(:r) AND business_date BETWEEN :f AND :t GROUP BY route_id, sku_id HAVING sum(sold_qty_base) > 0 OR sum(memo_count) > 0
             ORDER BY route_id, sku_id
            """,
        ).bindArray("r", Long::class.javaObjectType, routeIds).bind("f", first).bind("t", through)
            .map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3) to rs.getInt(4)) }.list()
            .groupBy({ it.first }, { buildJsonObject {
                put("scope_type", "route"); put("scope_id", it.first); put("month", month.toString()); put("product_level", "sku")
                put("product_id", it.second); put("achieved_qty_base", it.third.first); put("memo_count", it.third.second)
                put("as_of", asOf.wire()); put("through_date", through.toString())
            } })

        val cals = WorkingCalendarRepo.load(h, month, now, config)
        val prevCals = WorkingCalendarRepo.load(h, month.minusMonths(1), now, config)
        val paths = WorkingCalendarRepo.zonePaths(h, routes.values)
        return routes.mapValues { (routeId, zoneId) ->
            val path = paths[zoneId] ?: com.aktcl.aron.rules.NodePath.GLOBAL
            val cal = cals.at(path)
            var before = 0; var after = 0
            var d = first
            while (!d.isAfter(month.atEndOfMonth())) {
                if (cal.isWorking(d.toKotlinLocalDate())) { if (d.isBefore(date)) before++ else if (d.isAfter(date)) after++ }
                d = d.plusDays(1)
            }
            Snapshot(
                targets[routeId].orEmpty().take(MAX_ROWS), achieved[routeId].orEmpty().take(MAX_ROWS),
                RouteCalendarDto(
                    month = month.toString(), working_days = cals.workingDays(path), elapsed_working_days_before_today = before,
                    remaining_working_days_after_today = after, today_is_working = cal.isWorking(date.toKotlinLocalDate()),
                    last_month_working_days = prevCals.workingDays(path), through_date = through.toString(), as_of = asOf.wire(),
                ),
            )
        }
    }

    /** A base quantity as the contract's exact Decimal3 string: "500.000". */
    fun decimal3(q: Long): String = java.math.BigDecimal.valueOf(q, 0).setScale(3).toPlainString()
}
