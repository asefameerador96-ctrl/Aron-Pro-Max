package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.WorkingCalendarRepo
import com.aktcl.aron.backend.platform.WorkingCalendars
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
 * - `achievement_mtd`: the sold quantity (sale lines only, like Target Status) and memo count per SKU of the route's accepted memos, read straight from
 *   `app.memo` / `app.memo_line` by [SrMemoFigures] (never from the lagging `dw.agg_*`), business dates from the 1st up to and including `through_date` = the
 *   the last CLOSED day at build time ([SrMemoFigures.through]: the bundle date minus one day, or yesterday for a bundle built ahead). TODAY IS NEVER IN A SERVER FIGURE. The phone shows these figures as the
 *   server's (the figures rule of `RouteSnapshot`, `docs/requests/sr-programmes-phone-rule-b.md`) and never adds into or subtracts from them; there is no clock. `as_of` is informational: the newest `received_at` among the memos read (the epoch when none), never used in arithmetic.
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
        /** The last CLOSED day ([SrMemoFigures.through]); the working-day counts are anchored on `max(through + 1, the 1st of the bundle month)` (the day still open, never before the month), so counts and figures describe the same cut. */
        through: LocalDate = SrMemoFigures.through(date, now),
        figures: Map<Long, SrMemoFigures.Figures> = SrMemoFigures.forRoutes(h, routes.keys, YearMonth.from(date).atDay(1), through),
    ): Map<Long, Snapshot> {
        if (routes.isEmpty()) return emptyMap()
        val month = YearMonth.from(date)
        val first = month.atDay(1)
        // 'today' of the day counts: the day after the last closed day, but never before the 1st of the bundle month (a bundle for Monday 1 Feb built on Sunday 31 Jan has through = 30 Jan and anchor
        // 1 Feb, so before + today + after = the month's working days and `today_is_working` is read from the bundle month's own calendar, never asked of a January date).
        val anchor = maxOf(through.plusDays(1), first)
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

        val achieved = routes.keys.associateWith { r ->
            figures[r]?.bySku().orEmpty().filter { (_, v) -> v.first > 0 || v.second > 0 }.map { (sku, v) -> buildJsonObject {
                put("scope_type", "route"); put("scope_id", r); put("month", month.toString()); put("product_level", "sku")
                put("product_id", sku); put("achieved_qty_base", v.first); put("memo_count", v.second)
                put("as_of", (figures[r]?.asOf ?: Instant.EPOCH).wire()); put("through_date", through.toString())
            } }
        }

        val cals = WorkingCalendarRepo.load(h, month, now, config)
        val prevCals = WorkingCalendarRepo.load(h, month.minusMonths(1), now, config)
        val paths = WorkingCalendarRepo.zonePaths(h, routes.values)
        return routes.mapValues { (routeId, zoneId) ->
            val path = paths[zoneId] ?: com.aktcl.aron.rules.NodePath.GLOBAL
            val cal = cals.at(path)
            var before = 0; var after = 0
            var d = first
            while (!d.isAfter(month.atEndOfMonth())) {
                if (cal.isWorking(d.toKotlinLocalDate())) { if (d.isBefore(anchor)) before++ else if (d.isAfter(anchor)) after++ }
                d = d.plusDays(1)
            }
            Snapshot(
                targets[routeId].orEmpty().take(MAX_ROWS), achieved[routeId].orEmpty().take(MAX_ROWS),
                RouteCalendarDto(
                    month = month.toString(), working_days = cals.workingDays(path), elapsed_working_days_before_today = before,
                    remaining_working_days_after_today = after, today_is_working = cal.isWorking(anchor.toKotlinLocalDate()),
                    last_month_working_days = lastMonthWorkingDaysThrough(prevCals, path, month.minusMonths(1), through), through_date = through.toString(), as_of = (figures[routeId]?.asOf ?: Instant.EPOCH).wire(),
                ),
                truncated = targets[routeId].orEmpty().size > MAX_ROWS || achieved[routeId].orEmpty().size > MAX_ROWS,
            )
        }
    }

    /**
     * Working days of last month that have CLOSED at the cut: all of them once `through` is past the end of last month, else those up to and including `through` (a bundle built ahead on the last day of
     * a month: Star's last-month sold quantity is cut at the same day, so its divisor counts the same days; the bundle built on the 1st has the whole month).
     */
    private fun lastMonthWorkingDaysThrough(cals: WorkingCalendars, path: com.aktcl.aron.rules.NodePath, last: YearMonth, through: LocalDate): Int {
        if (!through.isBefore(last.atEndOfMonth())) return cals.workingDays(path)
        val cal = cals.at(path); var n = 0; var d = last.atDay(1)
        while (!d.isAfter(through) && !d.isAfter(last.atEndOfMonth())) { if (cal.isWorking(d.toKotlinLocalDate())) n++; d = d.plusDays(1) }
        return n
    }

    /** A base quantity as the contract's exact Decimal3 string: "500.000". */
    fun decimal3(q: Long): String = java.math.BigDecimal.valueOf(q, 0).setScale(3).toPlainString()
}
