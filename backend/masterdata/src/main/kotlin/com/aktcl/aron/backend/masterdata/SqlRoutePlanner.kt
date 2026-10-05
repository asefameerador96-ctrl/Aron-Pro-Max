package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DayPlan
import com.aktcl.aron.backend.platform.RouteDayPlan
import com.aktcl.aron.backend.platform.RoutePlanner
import com.aktcl.aron.backend.platform.ServerConfig
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * Today's routes of a user (N-017): the routes assigned on the business date (primary or cover), each marked
 * planned when the working calendar makes the date a selling day for the route's zone and the date's weekday is in
 * the route's visit days valid on that date (`route_planned`, else `route.visit_days_mask`), with the active-outlet
 * count of the route. The weekend is `cfg.calendar.weekend_days` (default Friday).
 */
class SqlRoutePlanner(private val db: Database, private val geo: GeoRepository, private val config: ServerConfig) : RoutePlanner {
    override fun routesFor(userId: Long, businessDate: LocalDate): List<RouteDayPlan> {
        val g = geo.geo()
        val weekend = runCatching { config.value("cfg.calendar.weekend_days").jsonArray.mapNotNull { it.jsonPrimitive.intOrNull }.toSet() }
            .getOrDefault(DayPlan.DEFAULT_WEEKEND)
        return db.jdbi.withHandle<List<RouteDayPlan>, Exception> { h ->
            data class Row(val id: Long, val code: String, val name: String, val zone: Long, val kind: String?, val mask: Int, val label: String?, val asg: String, val active: Boolean)
            val rows = h.createQuery(
                """
                SELECT r.id, r.code, r.name, r.zone_id, r.display_label, r.status,
                       COALESCE(p.visit_kind, r.visit_kind) AS visit_kind, COALESCE(p.visit_days_mask, r.visit_days_mask) AS mask,
                       min(a.kind) AS asg   -- 'cover' < 'primary': a user holding both kinds of one route is shown as cover
                FROM app.route_assignment a
                JOIN app.route r ON r.id = a.route_id
                LEFT JOIN app.route_planned p ON p.route_id = r.id AND p.valid_from <= :d AND (p.valid_to IS NULL OR p.valid_to > :d)
                WHERE a.user_id = :u AND a.valid_from <= :d AND (a.valid_to IS NULL OR a.valid_to > :d)
                GROUP BY r.id, r.code, r.name, r.zone_id, r.display_label, r.status, p.visit_kind, r.visit_kind, p.visit_days_mask, r.visit_days_mask
                ORDER BY r.id
                """.trimIndent(),
            ).bind("u", userId).bind("d", businessDate).map { rs, _ ->
                Row(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getLong("zone_id"), rs.getString("visit_kind"),
                    rs.getInt("mask"), rs.getString("display_label"), if (rs.getString("asg") == "cover") "cover" else "primary", rs.getString("status") == "active")
            }.list()
            if (rows.isEmpty()) return@withHandle emptyList()
            val calendar = h.createQuery("SELECT date, scope_type, scope_id, kind, selling_day FROM app.calendar_holiday WHERE date = :d AND revoked_at IS NULL")
                .bind("d", businessDate).map { rs, _ ->
                    DayPlan.CalendarEntry(rs.getObject(1, LocalDate::class.java), DayPlan.CalendarScope.valueOf(rs.getString(2).uppercase()), rs.getLong(3), rs.getString(4), rs.getBoolean(5))
                }.list()
            val counts = h.createQuery("SELECT route_id, count(*) FROM app.outlet WHERE route_id = ANY(:r) AND status = 'active' GROUP BY route_id")
                .bindArray("r", Long::class.javaObjectType, rows.map { it.id }).map { rs, _ -> rs.getLong(1) to rs.getInt(2) }.list().toMap()
            rows.map { r ->
                val t = g.zoneTerritory.getValue(r.zone); val dv = g.territoryDivision.getValue(t); val w = g.divisionWing.getValue(dv)
                val planned = DayPlan.isPlanned(DayPlan.PlanRoute(r.id, r.mask, DayPlan.ZoneChain(r.zone, t, dv, w), r.active), businessDate, calendar, weekend)
                RouteDayPlan(r.id, r.code, r.name, r.zone, r.kind, r.mask, r.label, r.asg, planned, counts[r.id] ?: 0)
            }
        }
    }

    /** Target outlets of the user's date: the active outlets of the planned routes (docs/24 s12.4). */
    fun targetOutlets(userId: Long, businessDate: LocalDate): Int = routesFor(userId, businessDate).filter { it.plannedToday }.sumOf { it.targetOutlets }
}
