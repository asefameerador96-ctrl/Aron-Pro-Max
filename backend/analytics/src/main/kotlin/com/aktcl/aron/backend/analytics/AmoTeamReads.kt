package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DayPlan
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import com.aktcl.aron.rules.Geo
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import org.jdbi.v3.core.Handle
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

// ---------------------------------------------------------------------------------------------- DTOs (contract 1.11.0)

@Serializable data class JourneyUser(val user_id: Long, val full_name: String)
@Serializable data class JourneyRoute(val route_id: Long, val name: String)
@Serializable data class JourneyOutlet(val id: Long, val name: String, val code: String, val phone_masked: String?, val cluster: String?)

/** One call of the SR's day. `status`: new_sale | edited_sale (the visit's memo was superseded by an edit) | repeat_sale (a sold call at an outlet already sold earlier that day; emitted only while cfg.sale.journey_repeat_sale_enabled) | no_sale (no memo). */
@Serializable
data class JourneyCall(
    val visit_uuid: String, val outlet: JourneyOutlet, val status: String, val opened_at: String, val closed_at: String?, val duration_s: Long?,
    val distance_from_prev_m: Double?, val distance_so_far_m: Double, val gap_to_next_s: Long?,
)

@Serializable
data class TeamJourney(val user: JourneyUser, val route: JourneyRoute, val business_date: String, val calls: List<JourneyCall>, val distance_m: Double)

/** One row per SR and route; a route with no SR on the date is `vacant` (user_id and full_name null). `phone` in clear only for an AMO reading their own zone. */
@Serializable
data class TeamMemberRow(
    val user_id: Long?, val full_name: String?, val route_id: Long, val route_code: String, val route_name: String, val route_days: List<Int>, val phone: String?, val phone_masked: String?,
    val total_outlets: Int, val vacant: Boolean,
)

@Serializable data class TeamMemberList(val as_of: String, val business_date: String, val items: List<TeamMemberRow>)

@Serializable data class SalesSku(val sku_id: Long, val sku_code: String, val short_name: String?)
@Serializable data class SalesByDateLine(val sku: SalesSku, val qty_report: String, val report_unit: String, val qty_base: Long, val value_mtk: Long)
@Serializable data class SalesByDate(val outlet_id: Long, val business_date: String, val lines: List<SalesByDateLine>, val total_qty_base: Long, val total_value_mtk: Long)

/** The AMO manual build's team reads (F2): Sales Journey, the SR list with vacant routes and the previous sale data of an outlet. */
class AmoTeamReads(private val db: Database, private val clock: AronClock = AronClock.SYSTEM, private val repeatSaleEnabled: () -> Boolean = { false }) {

    private fun mask(p: String?) = p?.let { "${it.take(3)}*****${it.takeLast(3)}" }

    /** Distances are metres to two decimals (the screenshot prints 277.32). */
    private fun r2(v: Double) = Math.round(v * 100.0) / 100.0

    fun journey(reach: Reach, userId: Long, routeId: Long, date: LocalDate): TeamJourney = db.readJdbi.withHandle<TeamJourney, Exception> { h ->
        val route = h.createQuery("SELECT name, zone_id FROM app.route WHERE id = :r").bind("r", routeId).map { rs, _ -> rs.getString(1) to rs.getLong(2) }.findOne().orElse(null)
        if (route == null || !reach.coversRoute(routeId, route.second)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "route is outside your reach")
        class U(val name: String, val homeZone: Long?)
        val u = h.createQuery("SELECT full_name, home_zone_id FROM app.app_user WHERE id = :u AND role = 'SR'").bind("u", userId).map { rs, _ -> U(rs.getString(1), rs.getObject(2) as Long?) }.findOne().orElse(null)
            ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such SR")
        val user = u.name
        // The SR must belong to the caller's reach: work the route (an assignment, a day's acting row or any call on it) or have a home zone in reach (no name of an SR outside the reach is ever returned).
        val onRoute = h.createQuery(
            """
            SELECT EXISTS (SELECT 1 FROM app.route_assignment a WHERE a.route_id = :r AND a.user_id = :u)
                OR EXISTS (SELECT 1 FROM app.route_day d WHERE d.route_id = :r AND d.acting_user_id = :u)
                OR EXISTS (SELECT 1 FROM app.visit v WHERE v.route_id = :r AND v.user_id = :u)
            """,
        ).bind("r", routeId).bind("u", userId).mapTo(Boolean::class.java).one()
        if (!onRoute && !(u.homeZone != null && reach.coversZone(u.homeZone) && !reach.ownRecordsOnly)) throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such SR on this route")
        class V(val uuid: String, val outlet: JourneyOutlet, val lat: Double?, val lng: Double?, val opened: java.time.Instant, val closed: java.time.Instant?, val edited: Boolean, val sold: Boolean)
        val visits = h.createQuery(
            """
            SELECT v.client_uuid::text AS uuid, o.id AS oid, o.name, o.code, o.contact_number, c.name AS cluster, o.lat, o.lng, v.opened_at, v.ended_at,
                   EXISTS (SELECT 1 FROM app.memo m WHERE m.visit_client_uuid = v.client_uuid AND m.voided_at IS NULL
                                      AND ((m.status = 'active' AND m.supersedes_client_uuid IS NOT NULL)
                                        OR (m.status = 'superseded' AND EXISTS (SELECT 1 FROM app.memo e WHERE e.client_uuid = m.superseded_by_client_uuid AND e.status = 'active' AND e.voided_at IS NULL)))) AS edited,
                   EXISTS (SELECT 1 FROM app.memo m WHERE m.visit_client_uuid = v.client_uuid AND m.voided_at IS NULL AND m.status = 'active' AND m.memo_kind = 'sale') AS sold
              FROM app.visit v JOIN app.outlet o ON o.id = v.outlet_id LEFT JOIN app.cluster c ON c.id = o.cluster_id
             WHERE v.user_id = :u AND v.route_id = :r AND v.business_date = :d AND v.visit_kind = 'sr_call' AND v.voided_at IS NULL
             ORDER BY v.opened_at, v.client_uuid
            """,
        ).bind("u", userId).bind("r", routeId).bind("d", date).map { rs, _ ->
            V(rs.getString("uuid"), JourneyOutlet(rs.getLong("oid"), rs.getString("name"), rs.getString("code"), mask(rs.getString("contact_number")), rs.getString("cluster")),
                rs.getObject("lat") as Double?, rs.getObject("lng") as Double?, rs.getObject("opened_at", OffsetDateTime::class.java).toInstant(),
                rs.getObject("ended_at", OffsetDateTime::class.java)?.toInstant(), rs.getBoolean("edited"), rs.getBoolean("sold"))
        }.list()
        var total = 0.0
        // The status rule is the phone's (shared rules JourneyTimeline.build): edited first; a sold call at an outlet already sold earlier that day is repeat_sale
        // (only while cfg.sale.journey_repeat_sale_enabled is on: the AMO build that knows the value must be the minimum version first); sold = new_sale; else no_sale.
        val repeatOn = repeatSaleEnabled()
        val soldBefore = HashSet<Long>()
        val calls = visits.mapIndexed { i, v ->
            val status = when {
                v.edited -> "edited_sale"
                v.sold && repeatOn && v.outlet.id in soldBefore -> "repeat_sale"
                v.sold -> "new_sale"
                else -> "no_sale"
            }
            if (v.sold) soldBefore += v.outlet.id
            val prev = visits.getOrNull(i - 1)
            // Distance from the previous call's outlet base point (haversine); null for the first call or when either outlet has no base point.
            val d = if (prev?.lat != null && prev.lng != null && v.lat != null && v.lng != null) Geo.haversineM(prev.lat, prev.lng, v.lat, v.lng) else null
            if (d != null) total = r2(total + r2(d))
            val next = visits.getOrNull(i + 1)
            JourneyCall(
                v.uuid, v.outlet, status, v.opened.wire(), v.closed?.wire(), v.closed?.let { Duration.between(v.opened, it).seconds.coerceAtLeast(0) },
                d?.let { r2(it) }, total, if (v.closed != null && next != null) Duration.between(v.closed, next.opened).seconds.coerceAtLeast(0) else null,
            )
        }
        TeamJourney(JourneyUser(userId, user), JourneyRoute(routeId, route.first), date.toString(), calls, total)
    }

    /** Routes of the reach with their SR of the day (assignment valid on [date], else none: vacant). */
    fun members(reach: Reach, role: Role, userId: Long, date: LocalDate): TeamMemberList {
        val items = db.readJdbi.withHandle<List<TeamMemberRow>, Exception> { h ->
            val homeZone = h.createQuery("SELECT home_zone_id FROM app.app_user WHERE id = :u").bind("u", userId).mapTo(Long::class.javaObjectType).findOne().orElse(null)
            val zones = if (reach.national) null else reach.zoneIds.toList().ifEmpty { listOf(-1L) }
            val q = h.createQuery(
                """
                SELECT r.id AS rid, r.code AS rcode, r.name AS rname, r.visit_days_mask, r.zone_id, u.id AS uid, u.full_name, u.phone,
                       (SELECT count(*) FROM app.outlet o WHERE o.route_id = r.id AND o.status = 'active')::int AS outlets
                  FROM app.route r
                  LEFT JOIN LATERAL (SELECT a.user_id FROM app.route_assignment a WHERE a.route_id = r.id AND a.kind = 'primary' AND a.valid_from <= :d AND (a.valid_to IS NULL OR a.valid_to > :d) AND a.ended_at IS NULL LIMIT 1) pa ON true
                  LEFT JOIN app.app_user u ON u.id = coalesce((SELECT rd.acting_user_id FROM app.route_day rd WHERE rd.route_id = r.id AND rd.business_date = :d), pa.user_id) AND u.status = 'active'
                 WHERE r.status = 'active' AND r.kind = 'sr' AND ${if (zones == null) "true" else "r.zone_id = ANY(:zones)"}
                 ORDER BY u.full_name NULLS LAST, r.name, r.id
                """,
            ).bind("d", date)
            if (zones != null) q.bindArray("zones", Long::class.javaObjectType, zones)
            q.map { rs, _ ->
                val phone = rs.getString("phone")
                val daysMask = rs.getInt("visit_days_mask")
                val own = role == Role.AMO && !reach.national && homeZone != null && rs.getLong("zone_id") == homeZone
                TeamMemberRow(
                    rs.getObject("uid") as Long?, rs.getString("full_name"), rs.getLong("rid"), rs.getString("rcode"), rs.getString("rname"),
                    DayOfWeek.entries.filter { DayPlan.visitsOn(daysMask, LocalDate.of(2026, 1, 5).with(it)) }.map { it.value }.sorted(),
                    if (own) phone else null, mask(phone), rs.getInt("outlets"), rs.getObject("uid") == null,
                )
            }.list()
        }
        return TeamMemberList(clock.now().wire(), date.toString(), items)
    }

    /** Everything sold to the outlet on [date] by any SR, per SKU, in the SKU's field unit (rule 5) and in base units. */
    fun salesByDate(reach: Reach, outletId: Long, date: LocalDate, today: LocalDate): SalesByDate {
        if (date.isAfter(today) || date.isBefore(today.minusDays(90))) throw ApiProblem(ProblemCode.ERR_VALIDATION, "business_date must be within the last 90 days", errors = listOf(FieldError("query.business_date", "out_of_range")))
        return db.readJdbi.withHandle<SalesByDate, Exception> { h ->
            val o = h.createQuery("SELECT zone_id, route_id FROM app.outlet WHERE id = :o").bind("o", outletId).map { rs, _ -> rs.getLong(1) to (rs.getObject(2) as Long?) }.findOne().orElse(null)
                ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "outlet not found")
            // The outlet's route must be in the caller's reach (route's zone, else the outlet's own zone).
            val routeZone = o.second?.let { r -> h.createQuery("SELECT zone_id FROM app.route WHERE id = :r").bind("r", r).mapTo(Long::class.java).findOne().orElse(null) } ?: o.first
            val inReach = if (o.second != null) reach.coversRoute(o.second!!, routeZone) else reach.coversZone(routeZone)
            if (!inReach) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the outlet is outside your reach")
            val lines = h.createQuery(
                """
                SELECT s.id, s.code, s.short_name, s.category_code, s.base_unit, s.base_per_pack, s.entry_unit_default, sum(l.qty_base)::bigint AS qty, sum(l.gross_mtk)::bigint AS val
                  FROM app.memo m JOIN app.memo_line l ON l.memo_client_uuid = m.client_uuid AND l.business_date = m.business_date AND l.voided_at IS NULL AND l.line_kind = 'sale'
                  JOIN app.sku s ON s.id = l.sku_id
                 WHERE m.outlet_id = :o AND m.business_date = :d AND m.status = 'active' AND m.voided_at IS NULL
                 GROUP BY s.id, s.code, s.short_name, s.category_code, s.base_unit, s.base_per_pack, s.entry_unit_default ORDER BY s.code
                """,
            ).bind("o", outletId).bind("d", date).map { rs, _ ->
                val qty = rs.getLong("qty"); val pack = rs.getString("entry_unit_default") == "pack"
                val per = rs.getInt("base_per_pack").coerceAtLeast(1)
                SalesByDateLine(
                    SalesSku(rs.getLong("id"), rs.getString("code"), rs.getString("short_name")),
                    java.math.BigDecimal(qty).divide(java.math.BigDecimal(if (pack) per else 1), 3, java.math.RoundingMode.HALF_UP).toPlainString(),
                    // rule 5 (owner 2026-10-22): the field counts lighters as a box of 25, cigarettes in packets; the quantity is the same, the label differs (D-AMO-FND-14).
                    if (pack) (if (rs.getString("category_code") == "lighter") "box" else "pack") else rs.getString("base_unit"), qty, rs.getLong("val"),
                )
            }.list()
            SalesByDate(outletId, date.toString(), lines, lines.sumOf { it.qty_base }, lines.sumOf { it.value_mtk })
        }
    }
}

/** Supervisors and the national read roles; SR, SUPPORT and BRAND (a brand user sees no people or outlets, docs/34 s1) are refused. */
private val READERS = Role.entries.toSet() - Role.SR - Role.SUPPORT - Role.BRAND

private fun ApplicationCall.longParam(name: String): Long = request.queryParameters[name]?.toLongOrNull()?.takeIf { it >= 1 }
    ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "$name is required", errors = listOf(FieldError("query.$name", "required")))

private fun ApplicationCall.dateOrToday(today: LocalDate): LocalDate = request.queryParameters["business_date"]?.let { raw ->
    runCatching { LocalDate.parse(raw) }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad business_date", errors = listOf(FieldError("query.business_date", "invalid_value")))
} ?: today

/** `GET /v1/team/journey`, `GET /v1/team/members`, `GET /v1/outlets/{outlet_id}/sales-by-date` (contract 1.11.0). The reach comes from the token. */
fun Route.amoTeamRoutes(d: AppTeamDeps, reads: AmoTeamReads) {
    authenticated(d.guard) {
        get("/team/journey") {
            val p = call.principal
            if (p.role !in READERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the sales journey is for supervisors")
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            val date = call.dateOrToday(today)
            // D-AMO-FND-13 (L2): the same window as sales-by-date, 90 days back and never the future.
            if (date.isAfter(today) || date.isBefore(today.minusDays(90))) throw ApiProblem(ProblemCode.ERR_VALIDATION, "business_date must be within the last 90 days", errors = listOf(FieldError("query.business_date", "out_of_range")))
            call.respond(reads.journey(d.reach.reach(p.userId, p.role, p.scopeVersion, today), call.longParam("user_id"), call.longParam("route_id"), date))
        }
        get("/team/members") {
            val p = call.principal
            if (p.role !in READERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the team list is for supervisors")
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            call.respond(reads.members(d.reach.reach(p.userId, p.role, p.scopeVersion, today), p.role, p.userId, call.dateOrToday(today)))
        }
        get("/outlets/{outlet_id}/sales-by-date") {
            val p = call.principal
            if (p.role !in READERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the previous sale data is for supervisors")
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            val id = call.parameters["outlet_id"]?.toLongOrNull()?.takeIf { it >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad outlet_id", errors = listOf(FieldError("path.outlet_id", "invalid_value")))
            call.respond(reads.salesByDate(d.reach.reach(p.userId, p.role, p.scopeVersion, today), id, call.dateOrToday(today), today))
        }
    }
}
