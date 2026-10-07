package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import org.jdbi.v3.core.Handle
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

@Serializable
data class TeamDayRow(
    val user_id: Long, val full_name: String, val route_id: Long, val route_name: String?, val state: String, val visited: Int, val target_outlets: Int,
    val successful_calls: Int, val net_mtk: Long, val last_sync_at: String?,
)

/** Contract `AppHome`. `targets` stays null: target programmes are deferred (docs/27). */
@Serializable
data class AppHome(val as_of: String, val business_date: String, val node: NodeRefDto, val kpis: DashboardKpis, val team: List<TeamDayRow>, val targets: String? = null)

@Serializable
data class TeamStockSku(val sku_id: Long, val base_unit: String, val issued_qty_base: Long, val sold_qty_base: Long, val returned_qty_base: Long, val current_qty_base: Long)

@Serializable
data class TeamStockItem(val user_id: Long, val full_name: String, val by_sku: List<TeamStockSku>)

@Serializable
data class TeamStockList(val as_of: String, val business_date: String, val items: List<TeamStockItem>)

/** App home and team stock for AMO, TSO and the web roles (`GET /team/locations` is backend:sync's, N-036); the reach comes from the token, `zone_id` only narrows (403 outside). */
class TeamService(private val db: Database, private val dashboards: DashboardService, private val clock: AronClock = AronClock.SYSTEM) {

    /** Zones the call may read: the reach, narrowed by [zoneId]. null = every zone. Outside the reach, or unknown, is 403. */
    private fun zones(h: Handle, reach: Reach, zoneId: Long?): List<Long>? {
        if (zoneId == null) return if (reach.national) null else reach.zoneIds.toList().ifEmpty { listOf(-1L) }
        val known = h.createQuery("SELECT count(*) FROM dw.dim_geo WHERE zone_id = :z").bind("z", zoneId).mapTo(Long::class.java).one() > 0
        if (!known || (!reach.national && zoneId !in reach.zoneIds)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "zone is outside your reach")
        return listOf(zoneId)
    }

    private fun clause(z: List<Long>?, col: String) = if (z == null) "true" else "$col = ANY(:zones)"
    private fun bindZones(q: org.jdbi.v3.core.statement.Query, z: List<Long>?) = q.also { if (z != null) it.bindArray("zones", Long::class.javaObjectType, z) }

    fun home(reach: Reach, date: LocalDate): AppHome {
        val s = dashboards.summary(reach, null, null, date, date)
        val team = db.readJdbi.withHandle<List<TeamDayRow>, Exception> { h ->
            val z = zones(h, reach, null)
            bindZones(
                h.createQuery(
                    """
                    SELECT u.id AS uid, u.full_name, a.route_id, g.route_name, a.day_state, a.visited_outlets, a.target_outlets, a.successful_calls, a.net_mtk, rd.last_batch_at
                      FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id JOIN app.route_day rd ON rd.route_id = a.route_id AND rd.business_date = a.business_date
                      JOIN app.app_user u ON u.id = coalesce(rd.acting_user_id, rd.assigned_user_id)
                     WHERE a.business_date = :d AND a.planned AND ${clause(z, "a.zone_id")} ORDER BY g.route_code LIMIT 200
                    """,
                ).bind("d", date), z,
            ).map { rs, _ ->
                TeamDayRow(rs.getLong("uid"), rs.getString("full_name"), rs.getLong("route_id"), rs.getString("route_name"), rs.getString("day_state"), rs.getInt("visited_outlets"),
                    rs.getInt("target_outlets"), rs.getInt("successful_calls"), rs.getLong("net_mtk"), rs.getObject("last_batch_at", OffsetDateTime::class.java)?.toInstant()?.wire())
            }.list()
        }
        return AppHome(s.as_of, date.toString(), s.node, s.kpis, team)
    }

    fun stock(reach: Reach, zoneId: Long?, date: LocalDate): TeamStockList {
        val rows = db.readJdbi.withHandle<List<TeamStockItem>, Exception> { h ->
            val z = zones(h, reach, zoneId)
            // current = issued - sold - free (promo, DRP reward, sample lines leave the bag too) - returned. No phone numbers are read.
            val flat = bindZones(
                h.createQuery(
                    """
                    SELECT u.id AS uid, u.full_name, p.sku_id, p.base_unit, sum(a.issued_qty_base) iss, sum(a.sold_qty_base) sold, sum(a.returned_qty_base) ret,
                           sum(a.issued_qty_base - a.sold_qty_base - a.free_qty_base - a.returned_qty_base) cur
                      FROM dw.agg_daily_route_sku a JOIN dw.dim_geo g ON g.route_id = a.route_id JOIN dw.dim_product p ON p.sku_id = a.sku_id
                      JOIN app.route_day rd ON rd.route_id = a.route_id AND rd.business_date = a.business_date
                      JOIN app.app_user u ON u.id = coalesce(rd.acting_user_id, rd.assigned_user_id)
                     WHERE a.business_date = :d AND ${clause(z, "g.zone_id")} GROUP BY u.id, u.full_name, p.sku_id, p.base_unit ORDER BY u.full_name, u.id, p.sku_id
                    """,
                ).bind("d", date), z,
            ).map { rs, _ -> Triple(rs.getLong("uid") to rs.getString("full_name"), rs.getLong("sku_id"), listOf(rs.getString("base_unit"), rs.getLong("iss"), rs.getLong("sold"), rs.getLong("ret"), rs.getLong("cur"))) }.list()
            flat.groupBy { it.first }.map { (u, skus) ->
                TeamStockItem(u.first, u.second, skus.map { (_, sku, v) -> TeamStockSku(sku, v[0] as String, v[1] as Long, v[2] as Long, v[3] as Long, v[4] as Long) })
            }
        }
        return TeamStockList(clock.now().wire(), date.toString(), rows)
    }
}

class AppTeamDeps(val service: TeamService, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val TEAM_ROLES = Role.entries.toSet() - Role.SR - Role.SUPPORT

private fun ApplicationCall.dateParam(name: String, today: LocalDate, required: Boolean = false): LocalDate {
    val raw = request.queryParameters[name] ?: if (required) throw ApiProblem(ProblemCode.ERR_VALIDATION, "$name is required", errors = listOf(FieldError("query.$name", "required"))) else return today
    return runCatching { LocalDate.parse(raw) }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad $name", errors = listOf(FieldError("query.$name", "invalid_value")))
}

private fun ApplicationCall.zoneParam(): Long? = request.queryParameters["zone_id"]?.let {
    it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad zone_id", errors = listOf(FieldError("query.zone_id", "invalid_value")))
}

fun Route.appTeamRoutes(d: AppTeamDeps) {
    authenticated(d.guard) {
        get("/app/home") {
            val p = call.principal
            if (p.role !in TEAM_ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "app home is for AMO, TSO and the web roles")
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            call.respond(d.service.home(d.reach.reach(p.userId, p.role, p.scopeVersion, today), call.dateParam("business_date", today)))
        }
        get("/team/stock") {
            val p = call.principal
            if (p.role !in TEAM_ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "team stock is not available to this role")
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            call.respond(d.service.stock(d.reach.reach(p.userId, p.role, p.scopeVersion, today), call.zoneParam(), call.dateParam("business_date", today, required = true)))
        }
    }
}
