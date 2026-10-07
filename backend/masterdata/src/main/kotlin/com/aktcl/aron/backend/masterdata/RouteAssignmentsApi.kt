package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.Role
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime

/** One assignee of a route: the contract `RouteAssignment` members plus who the user is (the Browse Routes screen shows names). */
@Serializable
data class RouteAssigneeDto(
    val id: Long, val route_id: Long, val user_id: Long, val kind: String, val valid_from: String, val valid_to: String?, val reason: String?, val created_at: String,
    val username: String, val full_name: String, val user_role: String,
)

/** `sr_not_set` is true when no SR is assigned on `valid_on`; for an AMO route (`route_kind` = amo) that is the normal state. */
@Serializable
data class RouteAssignmentsDto(val route_id: Long, val route_kind: String, val valid_on: String, val sr_not_set: Boolean, val items: List<RouteAssigneeDto>)

class RouteAssignmentsDeps(val db: Database, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val ROUTE_READERS = setOf(Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

/**
 * F-API-020b `GET /v1/routes/{id}/assignments?valid_on=` (backlog row; the contract's nearest operation is
 * `GET /v1/admin/route-assignments?route_id=`). A route outside the caller's reach, or unknown, is 403 ERR_OUT_OF_SCOPE.
 */
fun Route.routeAssignmentRoutes(d: RouteAssignmentsDeps) {
    authenticated(d.guard) {
        get("/routes/{id}/assignments") { call.respond(list(call, d)) }
    }
}

private fun list(call: ApplicationCall, d: RouteAssignmentsDeps): RouteAssignmentsDto {
    val p = call.principal
    if (p.role !in ROUTE_READERS) throw AdminSupport.forbidden("route assignments are not available to this role")
    val routeId = call.parameters["id"]?.toLongOrNull()?.takeIf { it >= 1 } ?: AdminSupport.bad("path.id")
    val on = AdminSupport.queryDate(call, "valid_on") ?: AdminSupport.today(d.clock)
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    return d.db.jdbi.withHandle<RouteAssignmentsDto, Exception> { h ->
        val route = h.createQuery("SELECT zone_id, kind FROM app.route WHERE id = :r").bind("r", routeId).map { rs, _ -> rs.getLong(1) to rs.getString(2) }.findOne().orElse(null)
        if (route == null || !reach.coversRoute(routeId, route.first)) throw AdminSupport.outOfScope("route outside your reach")
        val items = h.createQuery(
            "SELECT a.id, a.user_id, a.kind, a.valid_from, a.valid_to, a.reason, a.created_at, u.username, u.full_name, u.role FROM app.route_assignment a JOIN app.app_user u ON u.id = a.user_id " +
                "WHERE a.route_id = :r AND a.ended_at IS NULL AND a.valid_from <= :on AND (a.valid_to IS NULL OR a.valid_to > :on) ORDER BY a.kind, a.valid_from, a.id",
        ).bind("r", routeId).bind("on", on).map { rs, _ ->
            RouteAssigneeDto(
                rs.getLong("id"), routeId, rs.getLong("user_id"), rs.getString("kind"), rs.getObject("valid_from", LocalDate::class.java).toString(),
                rs.getObject("valid_to", LocalDate::class.java)?.toString(), rs.getString("reason"), rs.getObject("created_at", OffsetDateTime::class.java).toInstant().wire(),
                rs.getString("username"), rs.getString("full_name"), rs.getString("role"),
            )
        }.list()
        RouteAssignmentsDto(routeId, route.second, on.toString(), items.none { it.user_role == "SR" }, items)
    }
}
