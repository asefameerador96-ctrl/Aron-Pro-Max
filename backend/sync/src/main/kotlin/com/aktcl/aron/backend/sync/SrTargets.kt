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
 *   `as_of` is the newest rewrite time of the route's own aggregate rows ([asOfByRoute]), never the request time or the worker's clock; no row means the epoch.
 * - `calendar`: the working days at the route's zone path (division and wing weekend and holidays honoured).
 */
object SrTargets {
    class Snapshot(val targets: List<JsonObject>, val achievement: List<JsonObject>, val calendar: RouteCalendarDto, val kpi: JsonObject? = null, val truncated: Boolean = false) {
        /** True when the route has target or achievement rows; a route with none (and no KPI, star or campaign) carries no `calendar` either (B1: nothing to show, nothing to resend). */
        val hasFigures: Boolean get() = targets.isNotEmpty() || achievement.isNotEmpty()
    }

    const val MAX_ROWS = 500

    fun load(
        h: Handle, routes: Map<Long, Long>, date: LocalDate, now: Instant, config: ServerConfig?,
        asOf: Map<Long, Instant> = asOfByRoute(h, routes.keys, date),
    ): Map<Long, Snapshot> {
        if (routes.isEmpty()) return emptyMap()
        val month = YearMonth.from(date)
        val first = month.atDay(1)
        val through = date.minusDays(1)
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
                put("as_of", (asOf[it.first] ?: Instant.EPOCH).wire()); put("through_date", through.toString())
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
                    last_month_working_days = prevCals.workingDays(path), through_date = through.toString(), as_of = (asOf[routeId] ?: Instant.EPOCH).wire(),
                ),
                truncated = targets[routeId].orEmpty().size > MAX_ROWS || achieved[routeId].orEmpty().size > MAX_ROWS,
            )
        }
    }

    /**
     * The `as_of` of a route's figures (B1 of the lead review, D-TKPI-10). Two parts:
     *  1. the newest `updated_at` among the route's own aggregate rows the figures are built from (previous month to `through_date`), the epoch when there are none;
     *     the aggregation writes that stamp with clock_timestamp() and keeps it when a rebuild changes no figure, so it moves only when a figure moved, never with
     *     the worker's clock (`dw.summary_freshness` is rewritten about every 5 seconds by an idle worker);
     *  2. clamped to just before (one second) the oldest `first_dirtied_at` of the route's `route_day_agg` dirty keys in that window, pending, claimed, backed-off and
     *     dead ones included: a route-day is rebuilt in its own transaction, in any order, so the rewrite of one day says nothing about another day of the same route
     *     that still waits (this is what `touchFreshness` does for the global stamp). Without pending keys there is no clamp, so an idle server changes nothing.
     * The phone's rule (b) (RouteSnapshot, openapi) needs exactly this guarantee: a memo the figures do not hold yet was accepted AFTER `as_of`. Campaign figures read
     * `dw.fact_memo_line` by outlet; a memo's lines are written in the same route-day rebuild as that route's aggregates, and the phone holds only its own SR's
     * memos, so the same clamp is the right one for them (another route's memos at the outlet can change the figure without moving this `as_of`; they are not on this
     * phone, so nothing is added twice).
     */
    fun asOfByRoute(h: Handle, routeIds: Collection<Long>, date: LocalDate): Map<Long, Instant> {
        if (routeIds.isEmpty()) return emptyMap()
        val through = date.minusDays(1)
        val from = YearMonth.from(date).minusMonths(1).atDay(1)
        val ids = routeIds.toList()
        val newest = h.createQuery("SELECT route_id, max(updated_at) FROM dw.agg_daily_route_sku WHERE route_id = ANY(:r) AND business_date BETWEEN :f AND :t GROUP BY route_id")
            .bindArray("r", Long::class.javaObjectType, ids).bind("f", from).bind("t", through)
            .map { rs, _ -> rs.getLong(1) to rs.getTimestamp(2).toInstant() }.list().toMap()
        val pending = h.createQuery("SELECT subject_id, min(first_dirtied_at) FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = ANY(:r) AND business_date BETWEEN :f AND :t GROUP BY subject_id")
            .bindArray("r", Long::class.javaObjectType, ids).bind("f", from).bind("t", through)
            .map { rs, _ -> rs.getLong(1) to rs.getTimestamp(2).toInstant() }.list().toMap()
        return newest.mapValues { (route, at) -> pending[route]?.minusSeconds(1)?.let { cap -> if (cap.isBefore(at)) cap else at } ?: at }
    }

    /** A base quantity as the contract's exact Decimal3 string: "500.000". */
    fun decimal3(q: Long): String = java.math.BigDecimal.valueOf(q, 0).setScale(3).toPlainString()
}
